import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import type { AccessTokenVerifier, SubjectRateLimiter } from './privy-auth.js';
import { PantaClientError, type PantaClient, type PantaMarketListInput,
  type PantaPrimaryBuildInput, type PantaPrimaryQuoteInput, type PantaPrimarySubmitInput,
  type PantaPrimaryVerifyInput } from './panta-client.js';

export type PantaApi = Pick<PantaClient, 'listMarkets' | 'getMarket' | 'positions' |
  'quotePrimaryBuy' | 'buildPrimaryBuy' | 'submitPrimaryBuy' | 'verifyPrimaryBuy'>;

const EmptyQuery = z.object({}).strict();
const MarketQuery = z.object({
  limit: z.coerce.number().int().min(1).max(50).optional(),
  category: z.string().min(1).max(120).regex(/^[A-Za-z0-9 _-]+$/).optional(),
  status: z.enum(['primary', 'secondary', 'resolved', 'cancelled']).optional(),
  cursor: z.string().min(1).max(160).regex(/^[A-Za-z0-9_-]+$/).optional(),
}).strict();
const MarketParams = z.object({ marketId: z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/) }).strict();
const PositionQuery = z.object({ wallet: z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/) }).strict();
const QuoteBody = z.object({
  wallet: PositionQuery.shape.wallet,
  marketId: MarketParams.shape.marketId,
  side: z.enum(['yes', 'no']),
  amountUsdc: z.string().regex(/^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/).refine(value => Number(value) > 0),
}).strict();
const BuildBody = z.object({
  quoteId: z.string().min(1).max(160).regex(/^[A-Za-z0-9_-]+$/),
  wallet: PositionQuery.shape.wallet,
  maxSlippageBps: z.number().int().min(0).max(5_000).optional(),
}).strict();
const SubmitBody = z.object({
  orderId: BuildBody.shape.quoteId,
  signature: z.string().min(80).max(88).regex(/^[1-9A-HJ-NP-Za-km-z]+$/),
  wallet: PositionQuery.shape.wallet,
}).strict();
const VerifyBody = z.object({
  orderId: BuildBody.shape.quoteId,
  signature: SubmitBody.shape.signature.optional(),
  wallet: PositionQuery.shape.wallet,
}).strict();

class PantaSessionError extends Error {}

function invalid(reply: FastifyReply) {
  return reply.code(400).send({ error: 'invalid_request', message: 'Prediction request is invalid.' });
}

function unavailable(reply: FastifyReply) {
  return reply.code(503).header('Retry-After', '30').send({
    error: 'panta_unavailable', message: 'Prediction markets are temporarily unavailable.',
  });
}

function providerFailure(error: unknown, reply: FastifyReply) {
  if (error instanceof PantaSessionError) return reply.code(409).send({
    error: 'order_session_unavailable', message: 'This order session has expired. Start a new quote.',
  });
  if (!(error instanceof PantaClientError)) return unavailable(reply);
  const status = error.statusCode === 400 || error.statusCode === 404 ||
    error.statusCode === 409 || error.statusCode === 422 ? error.statusCode :
    error.statusCode === 502 ? 502 : 503;
  if (error.retryAfterMs) reply.header('Retry-After', String(Math.max(1, Math.ceil(error.retryAfterMs / 1_000))));
  return reply.code(status).send({ error: error.code, message: error.message });
}

/**
 * The API key never leaves this backend. Order build responses remain unsigned; these
 * routes never sign or broadcast a transaction, nor fabricate a confirmed position.
 */
export function registerPantaRoutes(app: FastifyInstance, api: PantaApi | null,
  verifier: AccessTokenVerifier, readLimiter: SubjectRateLimiter,
  orderLimiter: SubjectRateLimiter, globalReadLimiter: SubjectRateLimiter,
  globalOrderLimiter: SubjectRateLimiter): void {
  type Binding = { subject: string; wallet: string; expiresAt: number };
  const quotes = new Map<string, Binding>();
  const orders = new Map<string, Binding>();
  const MAX_BINDINGS = 5_000;
  const now = () => Date.now();
  function remember(target: Map<string, Binding>, id: string, binding: Binding) {
    for (const [key, value] of target) if (value.expiresAt <= now()) target.delete(key);
    const existing = target.get(id);
    if (existing && (existing.subject !== binding.subject || existing.wallet !== binding.wallet)) {
      throw new PantaSessionError();
    }
    target.delete(id);
    target.set(id, binding);
    while (target.size > MAX_BINDINGS) target.delete(target.keys().next().value!);
  }
  function requireBinding(target: Map<string, Binding>, id: string, subject: string, wallet: string): Binding {
    const binding = target.get(id);
    if (!binding) throw new PantaSessionError();
    if (binding.expiresAt <= now()) { target.delete(id); throw new PantaSessionError(); }
    if (binding.subject !== subject || binding.wallet !== wallet) throw new PantaSessionError();
    return binding;
  }
  async function run<T>(request: FastifyRequest, reply: FastifyReply, kind: 'read' | 'order',
    action: (service: PantaApi, subject: string) => Promise<T>) {
    const principal = await verifier.verifyAuthorization(request.headers.authorization);
    (kind === 'read' ? readLimiter : orderLimiter).take(principal.subject);
    (kind === 'read' ? globalReadLimiter : globalOrderLimiter).take('panta-global');
    if (!api) return unavailable(reply);
    try { return await action(api, principal.subject); }
    catch (error) { return providerFailure(error, reply); }
  }

  app.get('/v1/panta/markets', async (request, reply) => {
    const parsed = MarketQuery.safeParse(request.query);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'read', service => service.listMarkets(parsed.data as PantaMarketListInput));
  });
  app.get<{ Params: { marketId: string } }>('/v1/panta/markets/:marketId', async (request, reply) => {
    if (!EmptyQuery.safeParse(request.query).success) return invalid(reply);
    const parsed = MarketParams.safeParse(request.params);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'read', service => service.getMarket(parsed.data.marketId));
  });
  app.get('/v1/panta/positions', async (request, reply) => {
    const parsed = PositionQuery.safeParse(request.query);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'read', service => service.positions(parsed.data.wallet));
  });
  app.post('/v1/panta/orders/quote', async (request, reply) => {
    if (!EmptyQuery.safeParse(request.query).success) return invalid(reply);
    const parsed = QuoteBody.safeParse(request.body);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'order', async (service, subject) => {
      const quote = await service.quotePrimaryBuy(parsed.data as PantaPrimaryQuoteInput);
      const upstreamExpiry = Date.parse(quote.expiresAt);
      remember(quotes, quote.quoteId, { subject, wallet: parsed.data.wallet,
        expiresAt: Number.isFinite(upstreamExpiry) ? Math.min(upstreamExpiry, now() + 90_000) : now() + 90_000 });
      return quote;
    });
  });
  app.post('/v1/panta/orders/build', async (request, reply) => {
    if (!EmptyQuery.safeParse(request.query).success) return invalid(reply);
    const parsed = BuildBody.safeParse(request.body);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'order', async (service, subject) => {
      requireBinding(quotes, parsed.data.quoteId, subject, parsed.data.wallet);
      const built = await service.buildPrimaryBuy(parsed.data as PantaPrimaryBuildInput);
      remember(orders, built.orderId, { subject, wallet: parsed.data.wallet, expiresAt: now() + 10 * 60_000 });
      return built;
    });
  });
  app.post('/v1/panta/orders/submit', async (request, reply) => {
    if (!EmptyQuery.safeParse(request.query).success) return invalid(reply);
    const parsed = SubmitBody.safeParse(request.body);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'order', async (service, subject) => {
      requireBinding(orders, parsed.data.orderId, subject, parsed.data.wallet);
      return service.submitPrimaryBuy(parsed.data as PantaPrimarySubmitInput);
    });
  });
  app.post('/v1/panta/orders/verify', async (request, reply) => {
    if (!EmptyQuery.safeParse(request.query).success) return invalid(reply);
    const parsed = VerifyBody.safeParse(request.body);
    if (!parsed.success) return invalid(reply);
    return run(request, reply, 'order', async (service, subject) => {
      requireBinding(orders, parsed.data.orderId, subject, parsed.data.wallet);
      return service.verifyPrimaryBuy(parsed.data as PantaPrimaryVerifyInput);
    });
  });
}

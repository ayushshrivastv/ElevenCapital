import bs58 from 'bs58';
import { Decimal } from 'decimal.js';
import { z } from 'zod';
import { decodeBase58 } from './transfer.js';

/** Panta's API is custodial of neither wallets nor signatures. This module only calls its HTTP API. */
const LIVE_BASE_URL = 'https://live-api.panta.market/api/v1';
const STAGING_BASE_URL = 'https://staging-api.panta.market/api/v1';
const MAX_RESPONSE_BYTES = 1_000_000;
const DEFAULT_TIMEOUT_MS = 8_000;

const Address = z.string().refine(value => {
  try { decodeBase58(value); return true; } catch { return false; }
});
const Signature = z.string().min(1).max(88).refine(value => {
  try { return bs58.decode(value).length === 64 && bs58.encode(bs58.decode(value)) === value; }
  catch { return false; }
});
const Identifier = z.string().min(1).max(160).regex(/^[A-Za-z0-9_-]+$/);
const Cursor = z.string().min(1).max(160).refine(value => !/[\u0000-\u001f\u007f]/.test(value));
const Usdc = z.string().regex(/^(?:0|[1-9]\d{0,11})(?:\.\d{1,6})?$/)
  .refine(value => Number(value) > 0 && Number.isFinite(Number(value)));
const NonnegativeDecimal = z.string().regex(/^(?:0|[1-9]\d{0,17})(?:\.\d{1,12})?$/);
const Side = z.enum(['yes', 'no']);
const Phase = z.enum(['primary', 'secondary', 'resolved', 'cancelled']);
const IsoDate = z.string().max(80).refine(value => Number.isFinite(Date.parse(value)));

const Market = z.object({
  marketId: Address,
  category: z.string().max(120),
  title: z.string().min(1).max(1_000),
  description: z.string().max(20_000).optional(),
  images: z.array(z.string().url().max(2_048)).max(12).optional(),
  phase: Phase,
  marketType: z.string().max(80).optional(),
  startTime: z.number().int().nonnegative().nullable().optional(),
  endTime: z.number().int().nonnegative().nullable().optional(),
  resolutionTime: z.number().int().nonnegative().nullable().optional(),
  region: z.string().max(160).optional(),
  resolved: z.boolean().optional(),
  status: z.string().max(80).optional(),
  volumeUsdc: NonnegativeDecimal.optional(),
  createdByPartner: z.boolean().optional(),
  yesPrice: NonnegativeDecimal.nullable().optional(),
  noPrice: NonnegativeDecimal.nullable().optional(),
  primaryYesPrice: NonnegativeDecimal.nullable().optional(),
  primaryNoPrice: NonnegativeDecimal.nullable().optional(),
  secondaryYesPrice: NonnegativeDecimal.nullable().optional(),
  secondaryNoPrice: NonnegativeDecimal.nullable().optional(),
}).strip();
const MarketList = z.object({ items: z.array(Market).max(50), nextCursor: Cursor.nullable().optional() }).strip();
const Position = z.object({
  marketId: Address,
  category: z.string().max(120).nullable().optional(),
  side: Side,
  shares: NonnegativeDecimal,
  phase: Phase,
  claimable: z.boolean(),
  claimed: z.boolean(),
  outcome: Side.nullable().optional(),
}).strip();
const Positions = z.object({ wallet: Address, positions: z.array(Position).max(200) }).strip();

const PrimaryQuote = z.object({
  quoteId: Identifier, marketId: Address, side: Side, amountUsdc: Usdc,
  shares: NonnegativeDecimal, avgPrice: NonnegativeDecimal, feeUsdc: NonnegativeDecimal,
  expiresAt: IsoDate,
}).strip();
const Instruction = z.object({
  programId: Address,
  data: z.string().max(20_000).refine(value => {
    try { return Buffer.from(value, 'base64').toString('base64') === value; }
    catch { return false; }
  }),
  accounts: z.array(z.object({ pubkey: Address, isSigner: z.boolean(), isWritable: z.boolean() }).strip()).max(100),
}).strip();
const PrimaryBuild = z.object({
  orderId: Identifier, quoteId: Identifier, wallet: Address, marketId: Address,
  side: Side, amountUsdc: Usdc, expectedShares: NonnegativeDecimal,
  feeUsdc: NonnegativeDecimal, status: z.literal('built'),
  instructions: z.array(Instruction).min(1).max(64),
  recentBlockhash: Address,
  lastValidBlockHeight: z.number().int().nonnegative().optional(),
  expiresAt: IsoDate.optional(),
}).strip();
const PrimarySubmit = z.object({ orderId: Identifier, status: z.literal('submitted'), signature: Signature }).strip();
const PrimaryVerify = z.object({
  orderId: Identifier,
  status: z.enum(['built', 'submitted', 'confirmed', 'failed', 'expired']),
  signature: Signature.optional(),
  marketId: Address.optional(),
  side: Side.optional(),
  amountUsdc: z.union([NonnegativeDecimal, z.number().int().nonnegative().safe()]).optional(),
}).strip();
const ErrorEnvelope = z.object({ code: z.string().min(1).max(80).regex(/^[A-Z][A-Z0-9_]*$/) }).strip();

const MarketListInput = z.object({
  limit: z.number().int().min(1).max(50).optional(),
  category: z.string().min(1).max(120).refine(value => !/[\u0000-\u001f\u007f]/.test(value)).optional(),
  status: Phase.optional(),
  cursor: Cursor.optional(),
}).strict();
const PrimaryQuoteInput = z.object({
  wallet: Address, marketId: Address, side: Side, amountUsdc: Usdc,
  userId: Identifier.optional(),
}).strict();
const PrimaryBuildInput = z.object({
  quoteId: Identifier, wallet: Address, maxSlippageBps: z.number().int().min(0).max(5_000).optional(),
  userId: Identifier.optional(),
}).strict();
const PrimarySubmitInput = z.object({ orderId: Identifier, signature: Signature, wallet: Address.optional() }).strict();
const PrimaryVerifyInput = z.object({ orderId: Identifier, signature: Signature.optional(), wallet: Address.optional() }).strict();

export type PantaMarket = z.infer<typeof Market>;
export type PantaMarketList = z.infer<typeof MarketList>;
export type PantaPositions = z.infer<typeof Positions>;
export type PantaPrimaryQuote = z.infer<typeof PrimaryQuote>;
/** Untrusted upstream instructions. Independently review programs, accounts, amounts and wallet before signing. */
export type PantaPrimaryBuild = z.infer<typeof PrimaryBuild>;
export type PantaPrimarySubmit = z.infer<typeof PrimarySubmit>;
export type PantaPrimaryVerify = z.infer<typeof PrimaryVerify>;
export type PantaMarketListInput = z.input<typeof MarketListInput>;
export type PantaPrimaryQuoteInput = z.input<typeof PrimaryQuoteInput>;
export type PantaPrimaryBuildInput = z.input<typeof PrimaryBuildInput>;
export type PantaPrimarySubmitInput = z.input<typeof PrimarySubmitInput>;
export type PantaPrimaryVerifyInput = z.input<typeof PrimaryVerifyInput>;
export type PantaClientErrorCode = 'invalid_request' | 'unauthorized' | 'rate_limited' | 'not_found' |
  'upstream_failure' | 'invalid_response' | 'timeout' | 'transport_failure' |
  'quote_expired' | 'quote_stale' | 'market_closed' | 'amount_too_small';

export class PantaClientError extends Error {
  constructor(readonly code: PantaClientErrorCode, readonly statusCode: number, message: string,
    readonly retryAfterMs?: number, readonly upstreamCode?: string) { super(message); this.name = 'PantaClientError'; }
}

export interface PantaClientOptions {
  apiKey: string;
  /** Only Panta's documented live and staging API origins are accepted. */
  baseUrl?: string;
  fetcher?: typeof fetch;
  timeoutMs?: number;
}

function checkedBaseUrl(value: string | undefined): string {
  const base = (value ?? LIVE_BASE_URL).replace(/\/$/, '');
  if (base !== LIVE_BASE_URL && base !== STAGING_BASE_URL) {
    throw new PantaClientError('invalid_request', 400, 'The Panta API endpoint is not allowlisted.');
  }
  return base;
}

function parseInput<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) throw new PantaClientError('invalid_request', 400, 'The Panta request has invalid fields.');
  return result.data;
}

function parseOutput<T>(schema: z.ZodType<T>, value: unknown): T {
  const result = schema.safeParse(value);
  if (!result.success) throw new PantaClientError('invalid_response', 502, 'Panta returned invalid data.');
  return result.data;
}

async function boundedJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader();
  if (!reader) throw new PantaClientError('invalid_response', 502, 'Panta returned an empty response.');
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) break;
      size += part.value.byteLength;
      if (size > MAX_RESPONSE_BYTES) throw new PantaClientError('invalid_response', 502, 'Panta returned too much data.');
      chunks.push(part.value);
    }
  } finally { await reader.cancel(); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new PantaClientError('invalid_response', 502, 'Panta returned invalid JSON.'); }
}

export class PantaClient {
  private readonly baseUrl: string;
  private readonly fetcher: typeof fetch;
  private readonly timeoutMs: number;
  private readonly apiKey: string;

  constructor(options: PantaClientOptions) {
    if (!/^(?:pk_test_|pk_live_)[A-Za-z0-9_-]{1,256}$/.test(options.apiKey)) {
      throw new PantaClientError('invalid_request', 400, 'A Panta API key is required.');
    }
    if (options.timeoutMs !== undefined && (!Number.isInteger(options.timeoutMs) || options.timeoutMs < 500 || options.timeoutMs > 30_000)) {
      throw new PantaClientError('invalid_request', 400, 'The Panta timeout is invalid.');
    }
    this.apiKey = options.apiKey;
    this.baseUrl = checkedBaseUrl(options.baseUrl);
    this.fetcher = options.fetcher ?? fetch;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  private async request<T>(method: 'GET' | 'POST', path: string, schema: z.ZodType<T>,
    query?: URLSearchParams, body?: object): Promise<T> {
    const url = `${this.baseUrl}${path}${query && query.size ? `?${query.toString()}` : ''}`;
    let response: Response;
    try {
      response = await this.fetcher(url, {
        method, redirect: 'error', cache: 'no-store', signal: AbortSignal.timeout(this.timeoutMs),
        headers: { Accept: 'application/json', 'X-Api-Key': this.apiKey,
          ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    } catch (error) {
      const timedOut = error instanceof Error && (error.name === 'TimeoutError' || error.name === 'AbortError');
      throw new PantaClientError(timedOut ? 'timeout' : 'transport_failure', 502,
        timedOut ? 'Panta did not respond in time.' : 'Panta is unavailable.');
    }
    if (!response.ok) {
      let upstreamCode: string | undefined;
      try {
        const parsed = ErrorEnvelope.safeParse(await boundedJson(response));
        if (parsed.success) upstreamCode = parsed.data.code;
      } catch { /* A malformed provider error must not become a client-visible diagnostic. */ }
      if (upstreamCode === 'QUOTE_EXPIRED') {
        throw new PantaClientError('quote_expired', 409, 'The quote expired. Request a new one.', undefined, upstreamCode);
      }
      if (upstreamCode === 'QUOTE_STALE') {
        throw new PantaClientError('quote_stale', 409, 'The market price moved. Request a new quote.', undefined, upstreamCode);
      }
      if (upstreamCode === 'MARKET_NOT_IN_PRIMARY') {
        throw new PantaClientError('market_closed', 409, 'This market is not accepting primary buys.', undefined, upstreamCode);
      }
      if (upstreamCode === 'AMOUNT_TOO_SMALL') {
        throw new PantaClientError('amount_too_small', 422, 'The buy amount is below the market minimum.', undefined, upstreamCode);
      }
      if (upstreamCode === 'MARKET_NOT_FOUND') {
        throw new PantaClientError('not_found', 404, 'Panta could not find this market.', undefined, upstreamCode);
      }
      if (response.status === 400 || response.status === 409 || response.status === 413) {
        throw new PantaClientError('invalid_request', 400, 'Panta rejected this request.', undefined, upstreamCode);
      }
      if (response.status === 401 || response.status === 403) {
        throw new PantaClientError('unauthorized', 503, 'Panta access is unavailable.', undefined, upstreamCode);
      }
      if (response.status === 404) {
        throw new PantaClientError('not_found', 404, 'Panta could not find this market or order.', undefined, upstreamCode);
      }
      if (response.status === 429) {
        const seconds = Number(response.headers.get('retry-after'));
        const retryAfterMs = Number.isFinite(seconds) && seconds > 0 ? Math.min(60_000, Math.ceil(seconds * 1_000)) : 5_000;
        throw new PantaClientError('rate_limited', 503, 'Panta is temporarily busy.', retryAfterMs, upstreamCode);
      }
      throw new PantaClientError('upstream_failure', 502, 'Panta could not complete this request.', undefined, upstreamCode);
    }
    return parseOutput(schema, await boundedJson(response));
  }

  async listMarkets(input: PantaMarketListInput = {}): Promise<PantaMarketList> {
    const request = parseInput(MarketListInput, input);
    const query = new URLSearchParams();
    if (request.limit !== undefined) query.set('limit', String(request.limit));
    if (request.category) query.set('category', request.category);
    if (request.status) query.set('status', request.status);
    if (request.cursor) query.set('cursor', request.cursor);
    return this.request('GET', '/markets/', MarketList, query);
  }

  async getMarket(marketId: string): Promise<PantaMarket> {
    const id = parseInput(Address, marketId);
    const market = await this.request('GET', `/markets/${encodeURIComponent(id)}/`, Market);
    if (market.marketId !== id) throw new PantaClientError('invalid_response', 502, 'Panta returned a different market.');
    return market;
  }

  async positions(wallet: string): Promise<PantaPositions> {
    const address = parseInput(Address, wallet);
    const positions = await this.request('GET', '/positions/', Positions, new URLSearchParams({ wallet: address }));
    if (positions.wallet !== address) throw new PantaClientError('invalid_response', 502, 'Panta returned a different wallet.');
    return positions;
  }

  async quotePrimaryBuy(input: PantaPrimaryQuoteInput): Promise<PantaPrimaryQuote> {
    const request = parseInput(PrimaryQuoteInput, input);
    const quote = await this.request('POST', '/primaryorderquote/', PrimaryQuote, undefined, request);
    if (quote.marketId !== request.marketId || quote.side !== request.side ||
      !new Decimal(quote.amountUsdc).eq(request.amountUsdc)) {
      throw new PantaClientError('invalid_response', 502, 'Panta returned a different quote.');
    }
    return quote;
  }

  async buildPrimaryBuy(input: PantaPrimaryBuildInput): Promise<PantaPrimaryBuild> {
    const request = parseInput(PrimaryBuildInput, input);
    const built = await this.request('POST', '/primaryorderbuild/', PrimaryBuild, undefined, request);
    if (built.quoteId !== request.quoteId || built.wallet !== request.wallet) {
      throw new PantaClientError('invalid_response', 502, 'Panta returned a different order.');
    }
    return built;
  }

  async submitPrimaryBuy(input: PantaPrimarySubmitInput): Promise<PantaPrimarySubmit> {
    const request = parseInput(PrimarySubmitInput, input);
    const submitted = await this.request('POST', '/primaryordersubmit/', PrimarySubmit, undefined, request);
    if (submitted.orderId !== request.orderId || submitted.signature !== request.signature) {
      throw new PantaClientError('invalid_response', 502, 'Panta returned a different submission.');
    }
    return submitted;
  }

  async verifyPrimaryBuy(input: PantaPrimaryVerifyInput): Promise<PantaPrimaryVerify> {
    const request = parseInput(PrimaryVerifyInput, input);
    const verified = await this.request('POST', '/primaryorderverify/', PrimaryVerify, undefined, request);
    if (verified.orderId !== request.orderId || request.signature && verified.signature && verified.signature !== request.signature) {
      throw new PantaClientError('invalid_response', 502, 'Panta returned a different order status.');
    }
    return verified;
  }
}

/** Returns null until a private server-side credential is configured. Never serialize this client into an APK. */
export function createPantaClientFromEnv(env: NodeJS.ProcessEnv = process.env): PantaClient | null {
  const apiKey = env.PANTA_API_KEY;
  if (!apiKey) return null;
  return new PantaClient({ apiKey, baseUrl: env.PANTA_API_BASE_URL });
}

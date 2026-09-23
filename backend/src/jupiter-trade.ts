import { Decimal } from 'decimal.js';
import { z } from 'zod';
import { decodeBase58, encodeBase58 } from './transfer.js';

// Official contracts: https://developers.jup.ag/docs/api-reference/swap/order
// https://developers.jup.ag/docs/portal/rate-limits (keyless: 30 requests/minute).
// This adapter only retrieves orders. It never signs, executes, or broadcasts.
const ENDPOINT = 'https://api.jup.ag/swap/v2/order';
const U64_MAX = (1n << 64n) - 1n;
const MAX_BODY_BYTES = 1_000_000;
const Mint = z.string().refine(value => { try { decodeBase58(value); return true; } catch { return false; } });
const Units = z.string().regex(/^(?:0|[1-9]\d*)$/).max(20).refine(value => /^\d{1,20}$/.test(value) && BigInt(value) <= U64_MAX);
const PositiveUnits = Units.refine(value => /^\d{1,20}$/.test(value) && BigInt(value) > 0n);
const Precision = z.number().int().min(0).max(18);
const UiMultiplier = z.string().max(340).refine(value => /^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value) && new Decimal(value).gt(0) && new Decimal(value).lte('1000000000000'));
const Router = z.enum(['metis', 'jupiterz', 'dflow', 'okx']);
export type JupiterRouter = z.infer<typeof Router>;

export interface JupiterOrderRequest {
  inputMint: string;
  outputMint: string;
  amountBaseUnits: string;
  /** Chain-verified mint precision, not inferred from the ticker or quote. */
  inputDecimals: number;
  outputDecimals: number;
  /** Local chain-verified display scaling. Never sent to the Jupiter API. */
  inputUiMultiplier?: string;
  outputUiMultiplier?: string;
  /** Omit to ask Jupiter for its default slippage; review the returned value. */
  slippageBps?: number;
  /** Omit for a quote-only request. No transaction is then returned. */
  taker?: string;
  excludeRouters?: JupiterRouter[];
}

const Request = z.object({ inputMint: Mint, outputMint: Mint, amountBaseUnits: PositiveUnits,
  inputDecimals: Precision, outputDecimals: Precision, inputUiMultiplier: UiMultiplier.optional(), outputUiMultiplier: UiMultiplier.optional(), slippageBps: z.number().int().min(0).max(5000).optional(),
  taker: Mint.optional(), excludeRouters: z.array(Router).max(3).optional() });
const DecimalValue = z.union([z.number().finite(), z.string().regex(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d{1,3})?$/).max(100)])
  .transform(value => new Decimal(value).toFixed());
const NullableDecimal = DecimalValue.nullish();
const FeeUnits = z.union([z.number().int().nonnegative().safe().transform(String), Units]);
const ResponseBody = z.object({
  inputMint: Mint, outputMint: Mint, inAmount: PositiveUnits, outAmount: PositiveUnits,
  otherAmountThreshold: PositiveUnits, swapMode: z.literal('ExactIn'), slippageBps: z.number().int().min(0).max(5000),
  inputDecimals: Precision.optional(), outputDecimals: Precision.optional(),
  requestId: z.string().min(1).max(200).regex(/^[A-Za-z0-9_-]+$/), router: Router,
  mode: z.enum(['ultra', 'manual']), transaction: z.string().max(1644).nullable(), taker: Mint.nullish(),
  feeMint: Mint, feeBps: z.number().int().min(0).max(1000),
  platformFee: z.object({ amount: Units.optional(), feeBps: z.number().int().min(0).max(1000), feeMint: Mint.optional() }).optional(),
  inUsdValue: NullableDecimal, outUsdValue: NullableDecimal, priceImpact: NullableDecimal, priceImpactPct: NullableDecimal,
  signatureFeeLamports: FeeUnits.optional(), prioritizationFeeLamports: FeeUnits.optional(), rentFeeLamports: FeeUnits.optional(),
  signatureFeePayer: Mint.nullish(), prioritizationFeePayer: Mint.nullish(), rentFeePayer: Mint.nullish(),
  lastValidBlockHeight: FeeUnits.nullish(), expireAt: z.string().max(64).nullish(),
  gasless: z.boolean().optional(), errorCode: z.number().int().optional(),
}).passthrough();

export interface JupiterOrderQuote {
  inputMint: string; outputMint: string; inputDecimals: number; outputDecimals: number;
  inputUiMultiplier?: string; outputUiMultiplier?: string;
  inAmount: string; outAmount: string; minimumOutputAmount: string;
  inputAmount: string; outputAmount: string; minimumOutput: string;
  requestId: string; router: JupiterRouter; mode: 'ultra' | 'manual'; slippageBps: number;
  inputUsdValue: string | null; outputUsdValue: string | null; priceImpactPercent: string | null;
  feeMint: string; feeBps: number; platformFeeAmount: string | null;
  signatureFeeLamports: string | null; prioritizationFeeLamports: string | null; rentFeeLamports: string | null;
  signatureFeePayer: string | null; prioritizationFeePayer: string | null; rentFeePayer: string | null;
  taker: string | null; transactionBase64: string | null; lastValidBlockHeight: string | null; expiresAt: string | null;
  gasless: boolean; quoteOnly: boolean; executableTransactionAvailable: boolean;
  /** A returned transaction still requires independent instruction/state validation before signing. */
  safetyValidationRequired: true;
  buildErrorCode: number | null; buildErrorReason: string | null;
  receivedAt: string;
}

export type JupiterTradeErrorCode = 'invalid_request' | 'route_unavailable' | 'rate_limited' | 'authentication_required' |
  'router_unavailable' | 'unsafe_router_response' | 'quote_expired';
export class JupiterTradeError extends Error {
  constructor(readonly code: JupiterTradeErrorCode, message: string, readonly retryAfterMs: number | null = null) { super(message); }
}
function unsafe(message: string): never { throw new JupiterTradeError('unsafe_router_response', message); }

function canonicalTransaction(value: string, taker: string): string {
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) unsafe('The swap transaction encoding is invalid.');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length < 100 || bytes.length > 1232 || bytes.toString('base64') !== value) unsafe('The swap transaction size is invalid.');
  // Structural signer binding only. Instruction decoding, lookups and simulation are the caller's responsibility.
  let cursor = 0;
  const vector = (): number => {
    let result = 0;
    for (let index = 0; index < 3; index++) {
      const byte = bytes[cursor++]; if (byte === undefined) return unsafe('The swap transaction is truncated.');
      if (index > 0 && byte === 0) return unsafe('The swap transaction uses a noncanonical length.');
      result |= (byte & 0x7f) << (index * 7);
      if ((byte & 0x80) === 0) return result;
    }
    return unsafe('The swap transaction length is invalid.');
  };
  const signatures = vector(); const signaturesOffset = cursor;
  if (signatures < 1 || signatures > 12) unsafe('The swap transaction signature count is invalid.');
  cursor += signatures * 64;
  if (cursor + 4 >= bytes.length) unsafe('The swap transaction is truncated.');
  if ((bytes[cursor]! & 0x80) !== 0) { if (bytes[cursor++] !== 0x80) unsafe('The swap transaction version is unsupported.'); }
  const required = bytes[cursor++]!; const readonlySigned = bytes[cursor++]!; const readonlyUnsigned = bytes[cursor++]!;
  const accounts = vector();
  if (required !== signatures || required < 1 || required > accounts || readonlySigned >= required ||
    readonlyUnsigned > accounts - required || accounts > 256 || cursor + accounts * 32 + 33 > bytes.length) {
    unsafe('The swap transaction account header is invalid.');
  }
  let signerIndex = -1;
  for (let index = 0; index < required; index++) {
    if (encodeBase58(bytes.subarray(cursor + index * 32, cursor + (index + 1) * 32)) === taker) signerIndex = index;
  }
  if (signerIndex < 0) unsafe('The swap transaction does not request the reviewed wallet signature.');
  if (bytes.subarray(signaturesOffset + signerIndex * 64, signaturesOffset + (signerIndex + 1) * 64).some(byte => byte !== 0)) {
    unsafe('The swap transaction unexpectedly contains the wallet signature.');
  }
  return value;
}

function expiry(value: string | null | undefined, now: number): string | null {
  if (value === undefined || value === null) return null;
  const ms = /^\d+$/.test(value) ? Number(value) * (value.length <= 10 ? 1000 : 1) : Date.parse(value);
  if (!Number.isSafeInteger(ms)) return unsafe('The swap quote expiry is invalid.');
  if (ms <= now) throw new JupiterTradeError('quote_expired', 'The swap quote has expired. Request a new quote.');
  if (ms > now + 24 * 60 * 60 * 1000) return unsafe('The swap quote expiry is outside the supported window.');
  return new Date(ms).toISOString();
}
function nonnegative(value: string | null | undefined): string | null {
  if (value == null) return null;
  const n = new Decimal(value); if (!n.isFinite() || n.isNegative() || n.gt('1e18')) return unsafe('The swap quote value is invalid.');
  return n.toFixed();
}
function buildReason(router: JupiterRouter, code: number | undefined): string {
  if (code === 1) return 'The wallet has insufficient funds for this swap.';
  if (code === 2) return router === 'jupiterz' ? 'The wallet needs the required token account.' : 'The wallet needs SOL for network fees.';
  if (code === 3) return router === 'jupiterz' ? 'The router could not build this swap.' : 'The swap is below the gasless minimum.';
  return 'The router returned a price but could not build a transaction.';
}
async function boundedJson(response: Response): Promise<unknown> {
  const reader = response.body?.getReader(); if (!reader) unsafe('The swap router returned no data.');
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break;
      size += chunk.value.length; if (size > MAX_BODY_BYTES) unsafe('The swap router response is too large.');
      chunks.push(chunk.value);
    }
  } finally { await reader.cancel(); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { return unsafe('The swap router returned invalid JSON.'); }
}

export class JupiterSwapV2 {
  constructor(private readonly fetcher: typeof fetch = fetch, private readonly apiKey: string | undefined = process.env.JUPITER_API_KEY,
    private readonly now: () => number = Date.now, private readonly timeoutMs = 15_000) {}

  readiness() {
    return { provider: 'jupiter-swap-v2' as const, authentication: this.apiKey ? 'api-key' as const : 'keyless' as const,
      quoteOnlySupported: true, signsOrBroadcasts: false, documentation: 'https://developers.jup.ag/docs/portal/rate-limits' };
  }

  async quote(input: JupiterOrderRequest): Promise<JupiterOrderQuote> {
    const parsed = Request.safeParse(input);
    if (!parsed.success || input.inputMint === input.outputMint) throw new JupiterTradeError('invalid_request', 'Select distinct valid assets and a positive base-unit amount.');
    const request = parsed.data;
    const url = new URL(ENDPOINT);
    url.searchParams.set('inputMint', request.inputMint); url.searchParams.set('outputMint', request.outputMint);
    url.searchParams.set('amount', request.amountBaseUnits); url.searchParams.set('swapMode', 'ExactIn');
    if (request.taker) url.searchParams.set('taker', request.taker);
    if (request.slippageBps !== undefined) url.searchParams.set('slippageBps', String(request.slippageBps));
    if (request.excludeRouters?.length) url.searchParams.set('excludeRouters', [...new Set(request.excludeRouters)].join(','));
    let response: Response; let raw: unknown;
    try {
      response = await this.fetcher(url, { method: 'GET', headers: { accept: 'application/json', ...(this.apiKey ? { 'x-api-key': this.apiKey } : {}) },
        redirect: 'error', signal: AbortSignal.timeout(this.timeoutMs) });
      if (!response.ok) {
        await response.body?.cancel();
        if (response.status === 429) {
          const reset = Number(response.headers.get('x-ratelimit-reset'));
          const after = Number(response.headers.get('retry-after'));
          const delay = reset > 0 ? reset * 1000 - this.now() : after > 0 ? after * 1000 : 2000;
          throw new JupiterTradeError('rate_limited', 'The swap provider is busy. Wait before requesting another quote.', Math.max(1000, Math.min(delay, 60_000)));
        }
        if ([401, 403].includes(response.status)) throw new JupiterTradeError('authentication_required', 'The swap provider rejected access. Check the configured API plan.');
        if ([400, 404, 422].includes(response.status)) throw new JupiterTradeError('route_unavailable', 'No swap route is currently available for these exact assets and amount.');
        throw new JupiterTradeError('router_unavailable', 'The swap provider is temporarily unavailable.');
      }
      raw = await boundedJson(response);
    } catch (error) {
      if (error instanceof JupiterTradeError) throw error;
      throw new JupiterTradeError('router_unavailable', 'The swap provider did not return a usable response in time.');
    }
    const decoded = ResponseBody.safeParse(raw);
    if (!decoded.success) return unsafe('The swap quote response has invalid fields.');
    const q = decoded.data;
    if (q.inputMint !== request.inputMint || q.outputMint !== request.outputMint || q.inAmount !== request.amountBaseUnits ||
      (q.taker ?? null) !== (request.taker ?? null) || q.inputDecimals !== undefined && q.inputDecimals !== request.inputDecimals ||
      q.outputDecimals !== undefined && q.outputDecimals !== request.outputDecimals || request.excludeRouters?.includes(q.router)) {
      unsafe('The swap quote does not match the reviewed assets, amount, wallet or router.');
    }
    if (request.slippageBps !== undefined && q.slippageBps > request.slippageBps) unsafe('The swap quote exceeds the selected slippage.');
    const minimum = BigInt(q.otherAmountThreshold); const output = BigInt(q.outAmount);
    if (minimum > output || minimum < output * BigInt(10_000 - q.slippageBps) / 10_000n) unsafe('The swap quote minimum does not match its slippage bound.');
    if (![q.inputMint, q.outputMint].includes(q.feeMint) || q.platformFee?.feeMint && q.platformFee.feeMint !== q.feeMint ||
      q.platformFee && q.platformFee.feeBps > q.feeBps) unsafe('The swap quote fee details do not match the asset pair.');
    const expiresAt = expiry(q.expireAt, this.now());
    let transaction: string | null = null;
    if (!request.taker) { if (q.transaction !== null) unsafe('A quote-only request unexpectedly returned a transaction.'); }
    else if (q.transaction) {
      if (q.errorCode !== undefined) unsafe('The swap response includes both a transaction and a build error.');
      transaction = canonicalTransaction(q.transaction, request.taker);
      if (q.router === 'jupiterz' ? expiresAt === null : !q.lastValidBlockHeight) unsafe('The swap transaction has no documented expiry bound.');
    } else if (q.transaction === null || q.errorCode === undefined) unsafe('The router omitted the requested transaction without a build error.');
    const impact = q.priceImpact ?? (q.priceImpactPct == null ? null : new Decimal(q.priceImpactPct).times(100).toFixed());
    if (impact !== null && (!new Decimal(impact).isFinite() || new Decimal(impact).abs().gt(10000))) unsafe('The swap quote price impact is invalid.');
    const Precise = Decimal.clone({ precision: 80 });
    const display = (value: string, decimals: number, factor = '1') => new Precise(value).times(factor).floor().div(new Precise(10).pow(decimals)).toFixed();
    return { inputMint: q.inputMint, outputMint: q.outputMint, inputDecimals: request.inputDecimals, outputDecimals: request.outputDecimals,
      inputUiMultiplier: request.inputUiMultiplier ?? '1', outputUiMultiplier: request.outputUiMultiplier ?? '1',
      inAmount: q.inAmount, outAmount: q.outAmount, minimumOutputAmount: q.otherAmountThreshold,
      inputAmount: display(q.inAmount, request.inputDecimals, request.inputUiMultiplier), outputAmount: display(q.outAmount, request.outputDecimals, request.outputUiMultiplier), minimumOutput: display(q.otherAmountThreshold, request.outputDecimals, request.outputUiMultiplier),
      requestId: q.requestId, router: q.router, mode: q.mode, slippageBps: q.slippageBps,
      inputUsdValue: nonnegative(q.inUsdValue), outputUsdValue: nonnegative(q.outUsdValue), priceImpactPercent: impact,
      feeMint: q.feeMint, feeBps: q.feeBps, platformFeeAmount: q.platformFee?.amount ?? null,
      signatureFeeLamports: q.signatureFeeLamports ?? null, prioritizationFeeLamports: q.prioritizationFeeLamports ?? null,
      rentFeeLamports: q.rentFeeLamports ?? null, signatureFeePayer: q.signatureFeePayer ?? null,
      prioritizationFeePayer: q.prioritizationFeePayer ?? null, rentFeePayer: q.rentFeePayer ?? null,
      taker: q.taker ?? null, transactionBase64: transaction, lastValidBlockHeight: q.lastValidBlockHeight ?? null, expiresAt,
      gasless: q.gasless ?? false, quoteOnly: request.taker === undefined, executableTransactionAvailable: transaction !== null,
      safetyValidationRequired: true, buildErrorCode: q.errorCode ?? null,
      buildErrorReason: q.errorCode === undefined ? null : buildReason(q.router, q.errorCode), receivedAt: new Date(this.now()).toISOString() };
  }
}

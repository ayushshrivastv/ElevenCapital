import { Decimal } from 'decimal.js';
import { z } from 'zod';
import type { PaymentAssetDefinition } from './purchase-chain.js';
import type { PurchaseDestination, PurchaseNetwork } from './purchase-schema.js';
import { encodeBase58 } from './transfer.js';

const ORIGIN = 'https://li.quest';
const MAX_BODY = 2_000_000;
const Address = z.string().min(1).max(64);
const IntegerString = z.string().regex(/^\d+$/);
const DecimalString = z.string().regex(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/);
const ChainValue = z.union([z.number().int().safe(), z.string().regex(/^\d+$/)]).transform(value => value.toString());

const LiFiToken = z.object({ address: Address, symbol: z.string().min(1).max(30), decimals: z.number().int().min(0).max(36),
  chainId: ChainValue, name: z.string().max(100).optional(), priceUSD: DecimalString.optional() }).passthrough();
const Cost = z.object({ amountUSD: DecimalString.optional() }).passthrough();
const GasCost = z.object({ amountUSD: DecimalString.optional(), amount: IntegerString.optional(), token: LiFiToken.optional() }).passthrough();
const LiFiQuote = z.object({
  id: z.string().min(1).max(200), tool: z.string().min(1).max(100),
  action: z.object({
    fromChainId: ChainValue, toChainId: ChainValue, fromToken: LiFiToken, toToken: LiFiToken,
    fromAmount: IntegerString, fromAddress: Address.optional(), toAddress: Address.optional(),
  }).passthrough(),
  estimate: z.object({
    fromAmount: IntegerString, toAmount: IntegerString, toAmountMin: IntegerString,
    fromAmountUSD: DecimalString.optional(), toAmountUSD: DecimalString.optional(),
    approvalAddress: z.string().regex(/^0x[0-9a-fA-F]{40}$/).nullable().optional(),
    feeCosts: z.array(Cost).max(50).optional(), gasCosts: z.array(GasCost).max(50).optional(),
    priceImpact: DecimalString.optional(), executionDuration: z.number().nonnegative().max(86_400).optional(),
  }).passthrough(),
  transactionRequest: z.unknown(),
}).passthrough();

const EvmTransaction = z.object({
  from: z.string().regex(/^0x[0-9a-fA-F]{40}$/), to: z.string().regex(/^0x[0-9a-fA-F]{40}$/),
  chainId: ChainValue,
  data: z.string().min(2).max(200_002).regex(/^0x(?:[0-9a-fA-F]{2})*$/),
  value: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/),
  gas: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
  gasLimit: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
  gasPrice: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
  maxFeePerGas: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
  maxPriorityFeePerGas: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
  nonce: z.string().regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/).optional(),
}).passthrough();
const SolanaTransaction = z.object({
  data: z.string().min(16).max(5_464).regex(/^[A-Za-z0-9+/]+={0,2}$/).optional(),
  serializedTransaction: z.string().min(16).max(5_464).regex(/^[A-Za-z0-9+/]+={0,2}$/).optional(),
  transaction: z.string().min(16).max(5_464).regex(/^[A-Za-z0-9+/]+={0,2}$/).optional(),
  minContextSlot: z.union([z.number().int().nonnegative().safe(), IntegerString]).optional(),
  lastValidBlockHeight: z.union([z.number().int().nonnegative().safe(), IntegerString]).optional(),
}).passthrough();

export interface LiFiQuoteRequest {
  source: PaymentAssetDefinition; destination: PurchaseDestination; amountBaseUnits: string;
  fromAddress: string; toAddress: string; slippageBps: number;
}
export interface LiFiEvmTransaction {
  kind: 'EVM'; from: string; to: string; data: string; value: string; gas: string | null;
  gasPrice: string | null; maxFeePerGas: string | null; maxPriorityFeePerGas: string | null; nonce: string | null;
}
export interface LiFiSolanaTransaction {
  kind: 'SOLANA'; transactionBase64: string; minContextSlot: string | null; lastValidBlockHeight: string | null;
}
export interface LiFiRouteQuote {
  routeId: string; tool: string; sourceChainId: string; destinationChainId: string;
  executionValidated?: boolean; expiresAt?: string;
  fromTokenAddress: string; toTokenAddress: string; fromAddress: string; toAddress: string;
  fromAmount: string; toAmount: string; toAmountMin: string; toDecimals: number;
  fromAmountUsd: string | null; toAmountUsd: string | null; feesUsd: string | null; priceImpactPercent: string | null;
  sourceGasBaseUnits: string | null;
  approvalAddress: string | null; transaction: LiFiEvmTransaction | LiFiSolanaTransaction;
}
export type LiFiExecutionStatus = { state: 'PENDING' | 'DONE' | 'FAILED'; receivedBaseUnits: string | null;
  message: string | null; destinationTransactionId?: string | null };

export interface PurchaseRouteStatusRequest {
  transactionId: string; tool: string; fromChainId: string; toChainId: string; routeId?: string;
  expectedTransactionBase64?: string; destinationAddress?: string; recipientAddress?: string;
  minimumReceivedBaseUnits?: string;
}

export interface PurchaseRouter {
  quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote>;
  status(request: PurchaseRouteStatusRequest): Promise<LiFiExecutionStatus>;
}

export class LiFiError extends Error {
  constructor(readonly code: 'route_unavailable' | 'router_unavailable' | 'unsafe_router_response', message: string) { super(message); }
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok) {
    await response.body?.cancel();
    if ([400, 404, 422].includes(response.status)) throw new LiFiError('route_unavailable', 'No executable route is currently available for this payment and stock.');
    throw new LiFiError('router_unavailable', 'The purchase router is temporarily unavailable.');
  }
  const reader = response.body?.getReader(); if (!reader) throw new LiFiError('router_unavailable', 'The purchase router returned no response.');
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break;
      size += chunk.value.length; if (size > MAX_BODY) throw new LiFiError('unsafe_router_response', 'The purchase router response exceeded the safety limit.');
      chunks.push(chunk.value);
    }
  } finally { await reader.cancel(); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new LiFiError('unsafe_router_response', 'The purchase router returned invalid data.'); }
}

function sameAddress(network: PurchaseNetwork, first: string, second: string): boolean {
  return network === 'SOLANA' ? first === second : first.toLowerCase() === second.toLowerCase();
}
function sumUsd(costs: unknown[]): string | null {
  let total = new Decimal(0); let found = false;
  for (const raw of costs) {
    const parsed = Cost.safeParse(raw); if (!parsed.success || parsed.data.amountUSD === undefined) continue;
    const value = new Decimal(parsed.data.amountUSD); if (!value.isFinite() || value.isNegative() || value.gt('1e12')) continue;
    found = true; total = total.add(value);
  }
  return found ? total.toFixed() : null;
}
function normalizePriceImpact(value: string | undefined): string | null {
  if (value === undefined) return null;
  const impact = new Decimal(value); if (!impact.isFinite() || impact.isNegative() || impact.gt(1)) return null;
  return impact.times(100).toFixed();
}
function safeEvmTransaction(raw: unknown, request: LiFiQuoteRequest): LiFiEvmTransaction {
  const transaction = EvmTransaction.safeParse(raw);
  if (!transaction.success || transaction.data.from.toLowerCase() !== request.fromAddress.toLowerCase() ||
    transaction.data.chainId !== request.source.chainId) throw new LiFiError('unsafe_router_response', 'The purchase transaction does not match the reviewed wallet and network.');
  const value = BigInt(transaction.data.value);
  if (request.source.address === '0x0000000000000000000000000000000000000000') {
    if (value !== BigInt(request.amountBaseUnits)) throw new LiFiError('unsafe_router_response', 'The native value does not match the reviewed amount.');
  } else if (value !== 0n) throw new LiFiError('unsafe_router_response', 'The token route unexpectedly requests native value.');
  const gas = transaction.data.gas ?? transaction.data.gasLimit ?? null;
  if (gas === null || BigInt(gas) < 21_000n || BigInt(gas) > 10_000_000n) {
    throw new LiFiError('unsafe_router_response', 'The purchase transaction has no bounded gas limit.');
  }
  for (const fee of [transaction.data.gasPrice, transaction.data.maxFeePerGas, transaction.data.maxPriorityFeePerGas]) {
    if (fee !== undefined && BigInt(fee) > 1_000_000_000_000_000n) throw new LiFiError('unsafe_router_response', 'The purchase transaction fee is abnormal.');
  }
  const hasEip1559 = transaction.data.maxFeePerGas !== undefined && transaction.data.maxPriorityFeePerGas !== undefined;
  if (!hasEip1559 && transaction.data.gasPrice === undefined) throw new LiFiError('unsafe_router_response',
    'The purchase transaction has no bounded fee mode.');
  if (hasEip1559 && BigInt(transaction.data.maxPriorityFeePerGas!) > BigInt(transaction.data.maxFeePerGas!)) {
    throw new LiFiError('unsafe_router_response', 'The purchase transaction priority fee exceeds its fee cap.');
  }
  return { kind: 'EVM', from: transaction.data.from, to: transaction.data.to, data: transaction.data.data,
    value: transaction.data.value, gas, gasPrice: hasEip1559 ? null : transaction.data.gasPrice!,
    maxFeePerGas: hasEip1559 ? transaction.data.maxFeePerGas! : null,
    maxPriorityFeePerGas: hasEip1559 ? transaction.data.maxPriorityFeePerGas! : null,
    nonce: transaction.data.nonce ?? null };
}
function shortVector(bytes: Buffer, offset: number): { value: number; offset: number } {
  let value = 0; let shift = 0;
  for (let index = 0; index < 3; index++) {
    if (offset >= bytes.length) throw new LiFiError('unsafe_router_response', 'The Solana transaction is truncated.');
    const byte = bytes[offset++]!; value |= (byte & 0x7f) << shift;
    if ((byte & 0x80) === 0) return { value, offset }; shift += 7;
  }
  throw new LiFiError('unsafe_router_response', 'The Solana transaction uses an invalid compact length.');
}
function solanaFeePayer(bytes: Buffer): { address: string; signatures: number; requiredSignatures: number } {
  const signatureCount = shortVector(bytes, 0); let offset = signatureCount.offset;
  if (signatureCount.value < 1 || signatureCount.value > 32 || offset + signatureCount.value * 64 >= bytes.length) {
    throw new LiFiError('unsafe_router_response', 'The Solana transaction signature list is invalid.');
  }
  if (!bytes.subarray(offset, offset + 64).every(value => value === 0)) throw new LiFiError('unsafe_router_response',
    'The Solana transaction already contains a fee-payer signature.');
  offset += signatureCount.value * 64;
  const first = bytes[offset++]!; let requiredSignatures: number;
  if ((first & 0x80) !== 0) {
    if ((first & 0x7f) !== 0) throw new LiFiError('unsafe_router_response', 'Unsupported Solana transaction version.');
    requiredSignatures = bytes[offset++]!; offset += 2;
  } else { requiredSignatures = first; offset += 2; }
  if (requiredSignatures < 1 || requiredSignatures !== signatureCount.value || offset > bytes.length) {
    throw new LiFiError('unsafe_router_response', 'The Solana signer header is invalid.');
  }
  const accounts = shortVector(bytes, offset); offset = accounts.offset;
  if (accounts.value < requiredSignatures || offset + accounts.value * 32 > bytes.length) {
    throw new LiFiError('unsafe_router_response', 'The Solana account list is invalid.');
  }
  return { address: encodeBase58(bytes.subarray(offset, offset + 32)), signatures: signatureCount.value, requiredSignatures };
}
function safeSolanaTransaction(raw: unknown, expectedWallet: string): LiFiSolanaTransaction {
  const parsed = SolanaTransaction.safeParse(raw);
  const transactionBase64 = parsed.success ? parsed.data.data ?? parsed.data.serializedTransaction ?? parsed.data.transaction : undefined;
  if (!parsed.success || !transactionBase64) throw new LiFiError('unsafe_router_response', 'The Solana purchase transaction is missing or invalid.');
  const bytes = Buffer.from(transactionBase64, 'base64');
  if (bytes.length < 32 || bytes.length > 4_096 || bytes.toString('base64') !== transactionBase64) {
    throw new LiFiError('unsafe_router_response', 'The Solana purchase transaction encoding is invalid.');
  }
  if (solanaFeePayer(bytes).address !== expectedWallet) throw new LiFiError('unsafe_router_response',
    'The Solana transaction fee payer does not match the reviewed wallet.');
  return { kind: 'SOLANA', transactionBase64,
    minContextSlot: parsed.data.minContextSlot?.toString() ?? null,
    lastValidBlockHeight: parsed.data.lastValidBlockHeight?.toString() ?? null };
}

export class LiFiClient implements PurchaseRouter {
  constructor(private readonly fetcher: typeof fetch = fetch, private readonly apiKey = process.env.LIFI_API_KEY) {}

  async quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote> {
    const url = new URL('/v1/quote', ORIGIN);
    const query = {
      fromChain: request.source.chainId, toChain: request.destination.chainId,
      fromToken: request.source.address, toToken: request.destination.address,
      fromAddress: request.fromAddress, toAddress: request.toAddress, fromAmount: request.amountBaseUnits,
      slippage: new Decimal(request.slippageBps).div(10_000).toFixed(), order: 'CHEAPEST', integrator: 'eleven-capital',
      maxPriceImpact: '0.1', allowDestinationCall: 'true', skipSimulation: 'false',
    };
    for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
    if (url.origin !== ORIGIN || url.pathname !== '/v1/quote') throw new Error('Invalid LI.FI endpoint');
    let raw: unknown;
    try {
      const headers: Record<string, string> = { Accept: 'application/json', 'User-Agent': 'ElevenCapital/0.1 purchase-router' };
      if (this.apiKey) headers['x-lifi-api-key'] = this.apiKey;
      raw = await readJson(await this.fetcher(url, { method: 'GET', redirect: 'error', signal: AbortSignal.timeout(15_000), headers }));
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      throw new LiFiError('router_unavailable', 'The purchase router is temporarily unavailable.');
    }
    const result = LiFiQuote.safeParse(raw);
    if (!result.success) throw new LiFiError('unsafe_router_response', 'The purchase router returned an unsupported transaction.');
    const { action, estimate } = result.data;
    if (action.fromChainId !== request.source.chainId || action.toChainId !== request.destination.chainId ||
      action.fromToken.chainId !== request.source.chainId || action.toToken.chainId !== request.destination.chainId ||
      !sameAddress(request.source.network, action.fromToken.address, request.source.address) ||
      !sameAddress(request.destination.network, action.toToken.address, request.destination.address) ||
      action.fromAmount !== request.amountBaseUnits || estimate.fromAmount !== request.amountBaseUnits ||
      action.fromAddress === undefined || action.toAddress === undefined ||
      !sameAddress(request.source.network, action.fromAddress, request.fromAddress) ||
      !sameAddress(request.destination.network, action.toAddress, request.toAddress) ||
      estimate.toAmount === '0' || estimate.toAmountMin === '0' || BigInt(estimate.toAmountMin) > BigInt(estimate.toAmount)) {
      throw new LiFiError('unsafe_router_response', 'The purchase quote does not match the requested assets, wallets, and amount.');
    }
    const costs = [...(estimate.feeCosts ?? []), ...(estimate.gasCosts ?? [])];
    let sourceGas = 0n; let foundSourceGas = false;
    for (const gas of estimate.gasCosts ?? []) {
      if (gas.amount === undefined || gas.token === undefined || gas.token.chainId !== request.source.chainId) continue;
      const native = request.source.network === 'SOLANA' ? gas.token.address === '11111111111111111111111111111111' :
        ['0x0000000000000000000000000000000000000000', '0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee'].includes(gas.token.address.toLowerCase());
      if (native) { sourceGas += BigInt(gas.amount); foundSourceGas = true; }
    }
    return {
      routeId: result.data.id, tool: result.data.tool,
      sourceChainId: action.fromChainId, destinationChainId: action.toChainId,
      fromTokenAddress: action.fromToken.address, toTokenAddress: action.toToken.address,
      fromAddress: action.fromAddress, toAddress: action.toAddress,
      fromAmount: estimate.fromAmount, toAmount: estimate.toAmount, toAmountMin: estimate.toAmountMin,
      toDecimals: action.toToken.decimals, fromAmountUsd: estimate.fromAmountUSD ?? null,
      toAmountUsd: estimate.toAmountUSD ?? null, feesUsd: sumUsd(costs), priceImpactPercent: normalizePriceImpact(estimate.priceImpact),
      sourceGasBaseUnits: foundSourceGas ? sourceGas.toString() : null,
      approvalAddress: estimate.approvalAddress ?? null,
      transaction: request.source.network === 'SOLANA' ? safeSolanaTransaction(result.data.transactionRequest, request.fromAddress)
        : safeEvmTransaction(result.data.transactionRequest, request),
    };
  }

  async status(request: { transactionId: string; tool: string; fromChainId: string; toChainId: string }): Promise<LiFiExecutionStatus> {
    const url = new URL('/v1/status', ORIGIN);
    for (const [key, value] of Object.entries({ txHash: request.transactionId, bridge: request.tool,
      fromChain: request.fromChainId, toChain: request.toChainId })) url.searchParams.set(key, value);
    if (url.origin !== ORIGIN || url.pathname !== '/v1/status') throw new Error('Invalid LI.FI endpoint');
    let raw: unknown;
    try {
      const headers: Record<string, string> = { Accept: 'application/json', 'User-Agent': 'ElevenCapital/0.1 purchase-router' };
      if (this.apiKey) headers['x-lifi-api-key'] = this.apiKey;
      raw = await readJson(await this.fetcher(url, { method: 'GET', redirect: 'error', signal: AbortSignal.timeout(10_000), headers }));
    } catch (error) {
      if (error instanceof LiFiError && error.code === 'route_unavailable') return { state: 'PENDING', receivedBaseUnits: null, message: null };
      throw error instanceof LiFiError ? error : new LiFiError('router_unavailable', 'Purchase status is temporarily unavailable.');
    }
    const parsed = z.object({ status: z.enum(['NOT_FOUND', 'PENDING', 'DONE', 'FAILED']), substatusMessage: z.string().max(240).optional(),
      receiving: z.object({ amount: IntegerString.optional() }).passthrough().optional() }).passthrough().safeParse(raw);
    if (!parsed.success) throw new LiFiError('unsafe_router_response', 'The purchase router returned an invalid status.');
    if (parsed.data.status === 'DONE') return { state: 'DONE', receivedBaseUnits: parsed.data.receiving?.amount ?? null, message: null };
    if (parsed.data.status === 'FAILED') return { state: 'FAILED', receivedBaseUnits: null, message: parsed.data.substatusMessage ?? 'The routed purchase failed.' };
    return { state: 'PENDING', receivedBaseUnits: null, message: parsed.data.substatusMessage ?? null };
  }
}

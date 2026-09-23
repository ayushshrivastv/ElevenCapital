import { JupiterSwapV2, JupiterTradeError, type JupiterOrderQuote, type JupiterOrderRequest } from './jupiter-trade.js';
import { LiFiClient, LiFiError, type LiFiExecutionStatus, type LiFiQuoteRequest, type LiFiRouteQuote,
  type PurchaseRouter, type PurchaseRouteStatusRequest } from './lifi.js';
import { publicPurchaseRpc, SOL_NATIVE, type JsonRpc } from './purchase-chain.js';
import { RelayClient, RELAY_ANTHROPIC_MINT, RELAY_ANTHROPIC_TOOL } from './relay.js';
import { encodeBase58 } from './transfer.js';
import { SolanaSwapValidationError, validateSolanaSwap, type SolanaSwapRpc, type ValidatedSolanaSwap } from './solana-swap-validation.js';

const SOL_CHAIN = '1151111081099710';
const WSOL = 'So11111111111111111111111111111111111111112';
const ARBITRUM_CHAIN = '42161';
export const JUPITER_PURCHASE_TOOL = 'eleven-jupiter-metis';
type OrderClient = Pick<JupiterSwapV2, 'quote'>;
type Validator = (quote: JupiterOrderQuote, rpc: SolanaSwapRpc) => Promise<ValidatedSolanaSwap>;
const pending = (message: string | null = null): LiFiExecutionStatus => ({ state: 'PENDING', receivedBaseUnits: null, message });
function unsafe(message: string): never { throw new LiFiError('unsafe_router_response', message); }
function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return unsafe('The confirmed swap observation has invalid fields.');
  return value as Record<string, unknown>;
}
function units(value: unknown): bigint {
  if ((typeof value !== 'string' || !/^(?:0|[1-9]\d{0,19})$/.test(value)) && (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)) return unsafe('A confirmed swap amount is invalid.');
  return BigInt(value);
}
function signedMessage(value: string): { signature: string; message: Buffer } {
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length < 100 || bytes.length > 1232 || bytes.toString('base64') !== value || bytes[0] !== 1) unsafe('The confirmed swap transaction serialization is invalid.');
  return { signature: encodeBase58(bytes.subarray(1, 65)), message: bytes.subarray(65) };
}
function tokenReceipt(meta: Record<string, unknown>, mint: string, recipient: string): bigint | null {
  if (!Array.isArray(meta.preTokenBalances) || !Array.isArray(meta.postTokenBalances)) return null;
  const pre = new Map<number, Record<string, unknown>>(); const post = new Map<number, Record<string, unknown>>();
  for (const [rows, map] of [[meta.preTokenBalances, pre], [meta.postTokenBalances, post]] as const) {
    for (const row of rows) {
      const value = object(row); const index = Number(units(value.accountIndex));
      if (index > 255 || map.has(index)) unsafe('The confirmed swap token balances contain duplicate or invalid account indices.');
      map.set(index, value);
    }
  }
  let sum = 0n; let found = false;
  for (const [index, value] of post) {
    if (value.mint !== mint || value.owner !== recipient) continue;
    const old = pre.get(index);
    if (old && (old.owner !== recipient || old.mint !== mint)) return null;
    sum += units(object(value.uiTokenAmount).amount) - (old ? units(object(old.uiTokenAmount).amount) : 0n);
    found = true;
  }
  // Include closed accounts of the same token so transfers between owned accounts
  // cannot be mistaken for a newly purchased position.
  for (const [index, value] of pre) if (!post.has(index) && value.mint === mint && value.owner === recipient) {
    sum -= units(object(value.uiTokenAmount).amount); found = true;
  }
  return found ? sum : null;
}

/** Only independently validated same-chain swaps and pinned Relay
 * Arbitrum/Anthropic routes can execute. This class never signs or broadcasts. */
export class JupiterPurchaseRouter implements PurchaseRouter {
  constructor(private readonly rpc: JsonRpc = publicPurchaseRpc(), private readonly orders: OrderClient = new JupiterSwapV2(),
    private readonly fallback: PurchaseRouter = new LiFiClient(), private readonly validate: Validator = validateSolanaSwap,
    private readonly now: () => number = Date.now, private readonly relay: Pick<RelayClient, 'quote' | 'status'> = new RelayClient()) {}

  async quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote> {
    if ((request.source.id === 'ARBITRUM:USDC' || request.source.id === 'ARBITRUM:ETH') &&
      request.source.chainId === ARBITRUM_CHAIN &&
      request.destination.network === 'SOLANA' && request.destination.chainId === SOL_CHAIN &&
      request.destination.address === RELAY_ANTHROPIC_MINT) {
      return this.relay.quote({ sourceAssetId: request.source.id, fromAddress: request.fromAddress, recipientAddress: request.toAddress,
        amountBaseUnits: request.amountBaseUnits, slippageBps: request.slippageBps });
    }
    if (request.source.network !== 'SOLANA' || request.destination.network !== 'SOLANA') {
      // A future validator may support these routes. Delegation alone never establishes execution eligibility.
      const preview = await this.fallback.quote(request); return { ...preview, executionValidated: false };
    }
    if (request.source.chainId !== SOL_CHAIN || request.destination.chainId !== SOL_CHAIN || request.fromAddress !== request.toAddress) {
      return unsafe('A same-chain stock trade must settle to the same connected Solana wallet.');
    }
    const input: JupiterOrderRequest = { inputMint: request.source.address === SOL_NATIVE ? WSOL : request.source.address,
      outputMint: request.destination.address === SOL_NATIVE ? WSOL : request.destination.address,
      amountBaseUnits: request.amountBaseUnits, inputDecimals: request.source.decimals, outputDecimals: request.destination.decimals,
      inputUiMultiplier: request.source.uiMultiplier ?? '1', outputUiMultiplier: request.destination.uiMultiplier ?? '1',
      slippageBps: request.slippageBps, taker: request.fromAddress, excludeRouters: ['jupiterz', 'dflow', 'okx'] };
    try {
      const quote = await this.orders.quote(input);
      if (!quote.transactionBase64 || !quote.executableTransactionAvailable) throw new LiFiError('route_unavailable', quote.buildErrorReason ?? 'No signable route is currently available for this stock.');
      const validated = await this.validate(quote, { call: (method, params) => this.rpc.call('SOLANA', method, params) });
      if (validated.transactionBase64 !== quote.transactionBase64) return unsafe('The validated transaction differs from the quoted transaction.');
      return { routeId: quote.requestId, tool: JUPITER_PURCHASE_TOOL, sourceChainId: SOL_CHAIN, destinationChainId: SOL_CHAIN,
        executionValidated: true, expiresAt: quote.expiresAt ?? new Date(this.now() + 20_000).toISOString(),
        fromTokenAddress: request.source.address, toTokenAddress: request.destination.address,
        fromAddress: request.fromAddress, toAddress: request.toAddress, fromAmount: quote.inAmount, toAmount: quote.outAmount,
        toAmountMin: quote.minimumOutputAmount, toDecimals: quote.outputDecimals, fromAmountUsd: quote.inputUsdValue,
        toAmountUsd: quote.outputUsdValue, feesUsd: null, priceImpactPercent: quote.priceImpactPercent,
        sourceGasBaseUnits: validated.maximumNativeCostLamports, approvalAddress: null,
        transaction: { kind: 'SOLANA', transactionBase64: validated.transactionBase64,
          minContextSlot: String(validated.simulationSlot), lastValidBlockHeight: validated.lastValidBlockHeight } };
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      if (error instanceof JupiterTradeError) {
        const code = error.code === 'unsafe_router_response' || error.code === 'invalid_request' ? 'unsafe_router_response' :
          ['route_unavailable', 'quote_expired'].includes(error.code) ? 'route_unavailable' : 'router_unavailable';
        throw new LiFiError(code, error.message);
      }
      if (error instanceof SolanaSwapValidationError) {
        const code = error.code === 'rpc_unavailable' ? 'router_unavailable' : error.code === 'transaction_mismatch' ? 'unsafe_router_response' : 'route_unavailable';
        throw new LiFiError(code, error.message);
      }
      throw new LiFiError('router_unavailable', 'The swap could not be independently verified. No wallet action was requested.');
    }
  }

  async status(request: PurchaseRouteStatusRequest): Promise<LiFiExecutionStatus> {
    if (request.tool === RELAY_ANTHROPIC_TOOL) {
      if (request.fromChainId !== ARBITRUM_CHAIN || request.toChainId !== SOL_CHAIN ||
        request.destinationAddress !== RELAY_ANTHROPIC_MINT || !request.routeId ||
        !request.recipientAddress || !request.minimumReceivedBaseUnits) {
        return unsafe('The Relay status request does not match the reviewed Anthropic route.');
      }
      return this.relay.status({ requestId: request.routeId, transactionId: request.transactionId,
        recipientAddress: request.recipientAddress, destinationMint: request.destinationAddress,
        minimumReceivedBaseUnits: request.minimumReceivedBaseUnits });
    }
    if (request.tool !== JUPITER_PURCHASE_TOOL) return this.fallback.status(request);
    if (request.fromChainId !== SOL_CHAIN || request.toChainId !== SOL_CHAIN || !request.expectedTransactionBase64 ||
      !request.destinationAddress || !request.recipientAddress || !request.minimumReceivedBaseUnits || !/^[1-9A-HJ-NP-Za-km-z]{64,88}$/.test(request.transactionId)) {
      return unsafe('The swap status request is missing its reviewed transaction and recipient.');
    }
    const expected = signedMessage(request.expectedTransactionBase64); const minimum = units(request.minimumReceivedBaseUnits);
    if (minimum <= 0n) return unsafe('The swap status request has no positive minimum receipt.');
    let raw: unknown;
    try { raw = await this.rpc.call('SOLANA', 'getTransaction', [request.transactionId, { commitment: 'confirmed', encoding: 'base64', maxSupportedTransactionVersion: 0 }]); }
    catch { return pending('Waiting for the confirmed onchain transaction.'); }
    if (raw === null) return pending();
    const receipt = object(raw); const serialized = receipt.transaction;
    if (!Array.isArray(serialized) || serialized.length !== 2 || serialized[1] !== 'base64' || typeof serialized[0] !== 'string') return unsafe('The RPC did not return the exact confirmed transaction bytes.');
    const actual = signedMessage(serialized[0]);
    if (actual.signature !== request.transactionId || !actual.message.equals(expected.message)) return unsafe('The submitted signature belongs to a different transaction than the reviewed swap.');
    // Independently bind the receipt recipient to the sole reviewed fee-payer signer.
    const versioned = (actual.message[0]! & 128) !== 0; const header = versioned ? 1 : 0;
    if (versioned && actual.message[0] !== 128 || actual.message[header] !== 1 || actual.message[header + 3] === undefined ||
      actual.message[header + 3]! & 128 || encodeBase58(actual.message.subarray(header + 4, header + 36)) !== request.recipientAddress) return unsafe('The confirmed swap has an unexpected wallet recipient.');
    const meta = object(receipt.meta);
    if (meta.err !== null) return { state: 'FAILED', receivedBaseUnits: null, message: 'The onchain swap failed. Network fees may still have been charged.' };
    let received: bigint | null;
    if (request.destinationAddress === SOL_NATIVE || request.destinationAddress === WSOL) {
      if (!Array.isArray(meta.preBalances) || !Array.isArray(meta.postBalances) || meta.preBalances.length === 0 || meta.preBalances.length !== meta.postBalances.length) return pending('Waiting for complete native balance settlement data.');
      // The validated native-output path closes only its newly created/empty WSOL
      // account to this wallet. Native delta plus the exact fee is its receipt.
      received = units(meta.postBalances[0]) - units(meta.preBalances[0]) + units(meta.fee);
    } else received = tokenReceipt(meta, request.destinationAddress, request.recipientAddress);
    if (received === null) return pending('Waiting for verified token settlement data.');
    if (received < minimum) return { state: 'FAILED', receivedBaseUnits: received >= 0n ? received.toString() : null,
      message: 'The confirmed receipt did not meet the reviewed minimum. Inspect the transaction before trying again.' };
    return { state: 'DONE', receivedBaseUnits: received.toString(), message: null };
  }
}

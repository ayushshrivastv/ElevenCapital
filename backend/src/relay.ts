import { Decimal } from 'decimal.js';
import bs58 from 'bs58';
import { getOrderId, type Order } from '@relay-protocol/settlement-sdk';
import { decodeFunctionData, encodeFunctionData, hexToBytes, parseAbi, recoverMessageAddress, type Hex } from 'viem';
import { z } from 'zod';
import { LiFiError, type LiFiEvmTransaction, type LiFiExecutionStatus, type LiFiRouteQuote } from './lifi.js';
import { publicPurchaseRpc, type JsonRpc } from './purchase-chain.js';
import { MAINNET_GENESIS } from './portfolio-upstream.js';

const ORIGIN = 'https://api.relay.link';
const ARBITRUM = 42161;
const SOLANA_RELAY = 792703809;
const SOLANA_ELEVEN = '1151111081099710';
const USDC = '0xaf88d065e77c8cc2239327c5edb3a432268e5831';
const ETH = '0x0000000000000000000000000000000000000000';
export const RELAY_ANTHROPIC_MINT = 'Pren1FvFX6J3E4kXhJuCiAD5aDmGEb7qJRncwA8Lkhw';
const SOLANA_USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const SOLANA_PYUSD = '2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo';
/** Independently pinned from Relay GET /chains protocol.v2 for Arbitrum. */
const DEPOSITORY = '0x4cd00e387622c35bddb9b4c962c136462338bc31';
const SOLVER = '0xf70da97812cb96acdf810712aa562db8dfa3dbef';
export const RELAY_ANTHROPIC_TOOL = 'eleven-relay-anthropic';
const VM_TYPES = { arbitrum: 'ethereum-vm', base: 'ethereum-vm', solana: 'solana-vm' } as const;
const MAX_INPUT = 10_000_000n;
const MAX_ETH_INPUT = 3_000_000_000_000_000n;
const MAX_GAS = 1_000_000n;
const MAX_GAS_WEI = 100_000_000_000_000_000n;
const MAX_BODY = 256_000;
const UInt = z.string().regex(/^(?:0|[1-9][0-9]*)$/).max(78);
const Positive = UInt.refine(value => BigInt(value) > 0n);
const Hash = z.string().regex(/^0x[0-9a-fA-F]{64}$/);
const EvmSignature = z.string().regex(/^0x[0-9a-fA-F]{130}$/);
const EvmAddress = z.string().regex(/^0x[0-9a-fA-F]{40}$/);
const SolAddress = z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/);
const HexData = z.string().min(2).max(200_002).regex(/^0x(?:[0-9a-fA-F]{2})*$/);
const Money = z.string().regex(/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/).max(100);
const Currency = z.object({ chainId: z.number().int(), address: z.string(), decimals: z.number().int() }).passthrough();
const Amount = z.object({ currency: Currency, amount: UInt, minimumAmount: UInt, amountUsd: Money.optional() }).passthrough();
const Fee = z.object({ currency: Currency, amount: UInt, amountUsd: Money }).passthrough();
const Step = z.object({ id: z.string(), kind: z.string(), requestId: Hash,
  items: z.array(z.object({ status: z.string(), data: z.unknown(),
    check: z.object({ endpoint: z.string(), method: z.string() }).passthrough().optional() }).passthrough()).length(1) }).passthrough();
const Quote = z.object({
  requestId: Hash, steps: z.array(Step).min(1).max(2),
  details: z.object({ operation: z.literal('swap'), sender: EvmAddress, recipient: SolAddress,
    currencyIn: Amount, currencyOut: Amount }).passthrough(),
  fees: z.object({ gas: Fee, relayer: Fee, app: Fee.optional() }).passthrough(),
  protocol: z.object({ v2: z.object({ orderId: Hash, hubType: z.literal('onchain'),
    orderData: z.unknown(), orderSignature: EvmSignature,
    paymentDetails: z.object({ chainId: z.string(), depository: EvmAddress,
      currency: EvmAddress, amount: UInt }).passthrough() }).passthrough() }).passthrough(),
}).passthrough();
const Refund = z.object({ chainId: z.enum(['arbitrum', 'solana']), recipient: z.string(),
  currency: z.string(), minimumAmount: UInt, deadline: z.number().int().safe(), extraData: HexData }).strict();
const OrderData = z.object({
  version: z.literal('v1'), solverChainId: z.literal('base'), solver: EvmAddress, salt: Hash,
  inputs: z.array(z.object({ payment: z.object({ chainId: z.literal('arbitrum'), currency: EvmAddress,
    amount: UInt, weight: z.literal('1') }).strict(), refunds: z.array(Refund).min(1).max(2) }).strict()).length(1),
  output: z.object({ chainId: z.literal('solana'),
    payments: z.array(z.object({ recipient: SolAddress, currency: SolAddress,
      minimumAmount: Positive, expectedAmount: Positive }).strict()).length(1),
    calls: z.array(z.unknown()).length(0), deadline: z.number().int().safe(), extraData: z.literal('0x') }).strict(),
  fees: z.array(z.unknown()).length(0),
}).strict();
const TxData = z.object({ from: EvmAddress, to: EvmAddress,
  chainId: z.union([z.literal(ARBITRUM), z.literal(String(ARBITRUM))]), data: HexData,
  value: UInt, gas: Positive, gasPrice: UInt.optional(), maxFeePerGas: UInt.optional(),
  maxPriorityFeePerGas: UInt.optional(), nonce: UInt.optional() }).strict();
const DEPOSIT_ABI = parseAbi(['function depositErc20(address depositor, address token, uint256 amount, bytes32 id)']);
const DEPOSIT_NATIVE_ABI = parseAbi(['function depositNative(address depositor, bytes32 id)']);
const APPROVAL_ABI = parseAbi(['function approve(address spender, uint256 amount)']);

function unsafe(message = 'Relay returned a route that does not match the reviewed order.'): never {
  throw new LiFiError('unsafe_router_response', message);
}
function unavailable(message = 'Relay has no executable route for this amount and stock.'): never {
  throw new LiFiError('route_unavailable', message);
}
function checked<T>(schema: z.ZodType<T>, raw: unknown): T {
  const parsed = schema.safeParse(raw);
  if (!parsed.success) return unsafe();
  return parsed.data;
}
function sameEvm(left: string, right: string): boolean { return left.toLowerCase() === right.toLowerCase(); }
function hex(value: bigint): string { return '0x' + value.toString(16); }
function canonicalSolana(value: string, size: number): boolean {
  try { const bytes = bs58.decode(value); return bytes.length === size && bs58.encode(bytes) === value; }
  catch { return false; }
}
function money(value: string): Decimal {
  const parsed = new Decimal(value);
  if (!parsed.isFinite() || parsed.isNegative()) return unsafe();
  return parsed;
}
async function json(response: Response): Promise<unknown> {
  if (!response.ok) {
    await response.body?.cancel();
    if ([400, 404, 422].includes(response.status)) return unavailable();
    throw new LiFiError('router_unavailable', 'Relay is temporarily unavailable.');
  }
  if (response.headers.get('content-type')?.split(';')[0]?.trim() !== 'application/json') return unsafe();
  const reader = response.body?.getReader();
  if (!reader) return unsafe();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.length;
      if (length > MAX_BODY) return unsafe('Relay response exceeded the safety limit.');
      chunks.push(next.value);
    }
  } finally { await reader.cancel(); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')) as unknown; }
  catch { return unsafe('Relay returned invalid JSON.'); }
}

export interface RelayQuoteRequest {
  sourceAssetId?: 'ARBITRUM:USDC' | 'ARBITRUM:ETH';
  fromAddress: string;
  recipientAddress: string;
  amountBaseUnits: string;
  slippageBps: number;
}
export type RelayQuote = LiFiRouteQuote & {
  requestId: string;
  executionValidated: true;
  transaction: LiFiEvmTransaction;
};
export interface RelayStatusRequest {
  requestId: string;
  transactionId: string;
  recipientAddress: string;
  destinationMint: string;
  minimumReceivedBaseUnits: string;
}
export type RelayStatus = LiFiExecutionStatus & { destinationTransactionId: string | null };

async function recoverSolver(orderId: Hex, signature: Hex): Promise<string> {
  return recoverMessageAddress({ message: { raw: hexToBytes(orderId) }, signature });
}

/** Exact Arbitrum USDC or native ETH to the pinned Anthropic PreStocks Solana mint. */
export class RelayClient {
  constructor(private readonly fetcher: typeof fetch = fetch, private readonly rpc: JsonRpc = publicPurchaseRpc(),
    private readonly now: () => number = Date.now, private readonly apiKey = process.env.RELAY_API_KEY,
    private readonly recoverOrderSigner: (orderId: Hex, signature: Hex) => Promise<string> = recoverSolver) {}

  async quote(request: RelayQuoteRequest): Promise<RelayQuote> {
    const sourceAssetId = request.sourceAssetId ?? 'ARBITRUM:USDC';
    if (sourceAssetId !== 'ARBITRUM:USDC' && sourceAssetId !== 'ARBITRUM:ETH') return unsafe();
    const native = sourceAssetId === 'ARBITRUM:ETH';
    const sourceCurrency = native ? ETH : USDC;
    const sourceDecimals = native ? 18 : 6;
    const from = checked(EvmAddress, request.fromAddress);
    const recipient = checked(SolAddress, request.recipientAddress);
    const amount = checked(Positive, request.amountBaseUnits);
    if (!canonicalSolana(recipient, 32) || sameEvm(from, '0x0000000000000000000000000000000000000000')) return unsafe();
    if (BigInt(amount) > (native ? MAX_ETH_INPUT : MAX_INPUT) || !Number.isInteger(request.slippageBps) ||
      request.slippageBps < 1 || request.slippageBps > 1_000) return unavailable('This route supports up to $10 of source asset and reviewed slippage up to 10%.');
    const url = new URL('/quote/v2', ORIGIN);
    const body = { user: from, recipient, originChainId: ARBITRUM, destinationChainId: SOLANA_RELAY,
      originCurrency: sourceCurrency, destinationCurrency: RELAY_ANTHROPIC_MINT, amount, tradeType: 'EXACT_INPUT',
      slippageTolerance: String(request.slippageBps), usePermit: false, includeProtocolData: true, ttl: 300 };
    let raw: unknown;
    try {
      const response = await this.fetcher(url, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15_000),
        headers: { Accept: 'application/json', 'Content-Type': 'application/json',
          ...(this.apiKey ? { 'x-api-key': this.apiKey } : {}) }, body: JSON.stringify(body) });
      raw = await json(response);
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      throw new LiFiError('router_unavailable', 'Relay is temporarily unavailable.');
    }
    const quote = checked(Quote, raw);
    const input = quote.details.currencyIn;
    const output = quote.details.currencyOut;
    if (!sameEvm(quote.details.sender, from) || quote.details.recipient !== recipient ||
      input.currency.chainId !== ARBITRUM || !sameEvm(input.currency.address, sourceCurrency) ||
      input.currency.decimals !== sourceDecimals || input.amount !== amount || BigInt(input.minimumAmount) > BigInt(amount) ||
      output.currency.chainId !== SOLANA_RELAY || output.currency.address !== RELAY_ANTHROPIC_MINT ||
      output.currency.decimals !== 9) return unsafe();
    const inputUsd = native ? (input.amountUsd === undefined ? unsafe() : money(input.amountUsd))
      : new Decimal(amount).div(1_000_000);
    if (inputUsd.lte(0) || inputUsd.gt(10)) return unavailable('The reviewed route supports up to $10 of source asset.');
    const expected = BigInt(output.amount);
    const minimum = BigInt(output.minimumAmount);
    if (minimum <= 0n || expected < minimum ||
      (expected - minimum) * 10_000n > expected * BigInt(request.slippageBps) + 10_000n) {
      return unavailable('Relay minimum output exceeds the reviewed slippage limit.');
    }
    const protocol = quote.protocol.v2;
    const order = checked(OrderData, protocol.orderData);
    const payment = order.inputs[0]!.payment;
    const outputPayment = order.output.payments[0]!;
    const nowSeconds = Math.floor(this.now() / 1_000);
    if (!sameEvm(order.solver, SOLVER) ||
      !sameEvm(payment.currency, sourceCurrency) || payment.amount !== amount ||
      outputPayment.recipient !== recipient || outputPayment.currency !== RELAY_ANTHROPIC_MINT ||
      outputPayment.expectedAmount !== output.amount || outputPayment.minimumAmount !== output.minimumAmount ||
      order.output.deadline <= nowSeconds || order.output.deadline > nowSeconds + 8 * 86_400 ||
      order.inputs[0]!.refunds.length !== 2 ||
      order.inputs[0]!.refunds.filter(refund => refund.chainId === 'arbitrum').length !== 1 ||
      order.inputs[0]!.refunds.filter(refund => refund.chainId === 'solana').length !== 1 ||
      order.inputs[0]!.refunds.some(refund =>
        refund.deadline <= nowSeconds || refund.deadline > nowSeconds + 8 * 86_400 ||
        (refund.chainId === 'arbitrum' && (!sameEvm(refund.recipient, from) || !sameEvm(refund.currency, sourceCurrency))) ||
        (refund.chainId === 'solana' && (refund.recipient !== recipient ||
          ![SOLANA_USDC, SOLANA_PYUSD].includes(refund.currency)))) ||
      protocol.paymentDetails.chainId !== 'arbitrum' ||
      !sameEvm(protocol.paymentDetails.depository, DEPOSITORY) ||
      !sameEvm(protocol.paymentDetails.currency, sourceCurrency) || protocol.paymentDetails.amount !== amount) return unsafe();
    let orderId: string;
    try { orderId = getOrderId(order as Order, VM_TYPES); }
    catch { return unsafe('Relay protocol order could not be independently verified.'); }
    if (orderId.toLowerCase() !== protocol.orderId.toLowerCase()) return unsafe('Relay order commitment does not match its terms.');
    let signer: string;
    try { signer = await this.recoverOrderSigner(orderId as Hex, protocol.orderSignature as Hex); }
    catch { return unsafe('Relay solver signature could not be verified.'); }
    if (!sameEvm(signer, SOLVER)) return unsafe('Relay order was not signed by the pinned solver.');
    const ids = quote.steps.map(step => step.id);
    if (native ? !(ids.length === 1 && ids[0] === 'deposit') :
      !((ids.length === 1 && ids[0] === 'deposit') ||
        (ids.length === 2 && ids[0] === 'approve' && ids[1] === 'deposit'))) {
      return unsafe('Relay requested unsupported wallet steps.');
    }
    for (const step of quote.steps) {
      if (step.kind !== 'transaction' || step.requestId !== quote.requestId || step.items[0]!.status !== 'incomplete') return unsafe();
    }
    const depositStep = quote.steps.at(-1)!;
    const check = depositStep.items[0]!.check;
    if (!check || check.method !== 'GET' ||
      check.endpoint !== '/intents/status/v3?requestId=' + quote.requestId) return unsafe();
    const transaction = this.evmTransaction(depositStep.items[0]!.data, from);
    if (!sameEvm(transaction.to, DEPOSITORY) ||
      BigInt(transaction.value) !== (native ? BigInt(amount) : 0n)) return unsafe();
    try {
      if (native) {
        const decoded = decodeFunctionData({ abi: DEPOSIT_NATIVE_ABI, data: transaction.data as Hex });
        if (decoded.functionName !== 'depositNative') return unsafe();
        const args = decoded.args as readonly [string, string];
        const encoded = encodeFunctionData({ abi: DEPOSIT_NATIVE_ABI, functionName: 'depositNative',
          args: [args[0] as Hex, args[1] as Hex] });
        if (encoded.toLowerCase() !== transaction.data.toLowerCase() || !sameEvm(args[0], from) ||
          args[1].toLowerCase() !== orderId.toLowerCase()) return unsafe();
      } else {
        const decoded = decodeFunctionData({ abi: DEPOSIT_ABI, data: transaction.data as Hex });
        if (decoded.functionName !== 'depositErc20') return unsafe();
        const args = decoded.args as readonly [string, string, bigint, string];
        const encoded = encodeFunctionData({ abi: DEPOSIT_ABI, functionName: 'depositErc20',
          args: [args[0] as Hex, args[1] as Hex, args[2], args[3] as Hex] });
        if (encoded.toLowerCase() !== transaction.data.toLowerCase() || !sameEvm(args[0], from) ||
          !sameEvm(args[1], USDC) || args[2] !== BigInt(amount) ||
          args[3].toLowerCase() !== orderId.toLowerCase()) return unsafe();
      }
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      return unsafe('Relay deposit calldata does not bind the reviewed order.');
    }
    if (!native && ids.length === 2) {
      if (quote.steps[0]!.items[0]!.check !== undefined) return unsafe();
      const approval = this.evmTransaction(quote.steps[0]!.items[0]!.data, from);
      if (!sameEvm(approval.to, USDC) || BigInt(approval.value) !== 0n) return unsafe();
      try {
        const decoded = decodeFunctionData({ abi: APPROVAL_ABI, data: approval.data as Hex });
        if (decoded.functionName !== 'approve') return unsafe();
        const args = decoded.args as readonly [string, bigint];
        const encoded = encodeFunctionData({ abi: APPROVAL_ABI, functionName: 'approve',
          args: [args[0] as Hex, args[1]] });
        if (encoded.toLowerCase() !== approval.data.toLowerCase() ||
          !sameEvm(args[0], DEPOSITORY) || args[1] !== BigInt(amount)) return unsafe();
      } catch (error) {
        if (error instanceof LiFiError) throw error;
        return unsafe('Relay approval does not match the exact reviewed spend.');
      }
    }
    const gas = quote.fees.gas;
    const relayer = quote.fees.relayer;
    const app = quote.fees.app;
    if (gas.currency.chainId !== ARBITRUM ||
      !sameEvm(gas.currency.address, ETH) ||
      gas.currency.decimals !== 18 || BigInt(gas.amount) <= 0n || BigInt(gas.amount) > MAX_GAS_WEI ||
      relayer.currency.chainId !== ARBITRUM || !sameEvm(relayer.currency.address, sourceCurrency) ||
      relayer.currency.decimals !== sourceDecimals || (app !== undefined && BigInt(app.amount) !== 0n)) {
      return unsafe('Relay fees do not match the supported source asset.');
    }
    const feesUsd = money(gas.amountUsd).plus(money(relayer.amountUsd))
      .plus(app ? money(app.amountUsd) : 0);
    if (feesUsd.gt(5) || feesUsd.gt(inputUsd) ||
      feesUsd.gt(Decimal.max(new Decimal('0.5'), inputUsd.times('0.3')))) {
      return unavailable('Relay fees exceed this route limit.');
    }
    return {
      requestId: quote.requestId, routeId: quote.requestId, tool: RELAY_ANTHROPIC_TOOL, executionValidated: true,
      sourceChainId: String(ARBITRUM), destinationChainId: SOLANA_ELEVEN,
      fromTokenAddress: sourceCurrency, toTokenAddress: RELAY_ANTHROPIC_MINT, fromAddress: from, toAddress: recipient,
      fromAmount: amount, toAmount: output.amount, toAmountMin: output.minimumAmount, toDecimals: 9,
      fromAmountUsd: input.amountUsd ?? null, toAmountUsd: output.amountUsd ?? null, feesUsd: feesUsd.toFixed(),
      priceImpactPercent: null, sourceGasBaseUnits: gas.amount, approvalAddress: native ? null : DEPOSITORY, transaction,
      expiresAt: new Date(Math.min(this.now() + 240_000, order.output.deadline * 1_000)).toISOString(),
    };
  }

  private evmTransaction(raw: unknown, from: string): LiFiEvmTransaction {
    const data = checked(TxData, raw);
    const gas = BigInt(data.gas);
    const fee = data.gasPrice === undefined ? data.maxFeePerGas : data.gasPrice;
    if (!sameEvm(data.from, from) || Number(data.chainId) !== ARBITRUM ||
      gas < 21_000n || gas > MAX_GAS || fee === undefined || BigInt(fee) <= 0n ||
      (data.gasPrice === undefined && data.maxPriorityFeePerGas === undefined) ||
      (data.gasPrice !== undefined && (data.maxFeePerGas !== undefined || data.maxPriorityFeePerGas !== undefined)) ||
      (data.maxPriorityFeePerGas !== undefined && BigInt(data.maxPriorityFeePerGas) > BigInt(fee)) ||
      gas * BigInt(fee) > MAX_GAS_WEI) return unsafe('Relay transaction gas or chain does not match the reviewed route.');
    return { kind: 'EVM', from: data.from, to: data.to, data: data.data,
      value: hex(BigInt(data.value)), gas: hex(gas),
      gasPrice: data.gasPrice === undefined ? null : hex(BigInt(data.gasPrice)),
      maxFeePerGas: data.maxFeePerGas === undefined ? null : hex(BigInt(data.maxFeePerGas)),
      maxPriorityFeePerGas: data.maxPriorityFeePerGas === undefined ? null : hex(BigInt(data.maxPriorityFeePerGas)),
      nonce: data.nonce === undefined ? null : hex(BigInt(data.nonce)) };
  }

  async status(request: RelayStatusRequest): Promise<RelayStatus> {
    const requestId = checked(Hash, request.requestId);
    const transactionId = checked(Hash, request.transactionId);
    const recipient = checked(SolAddress, request.recipientAddress);
    const minimum = checked(Positive, request.minimumReceivedBaseUnits);
    if (!canonicalSolana(recipient, 32) || request.destinationMint !== RELAY_ANTHROPIC_MINT) return unsafe();
    const url = new URL('/intents/status/v3', ORIGIN);
    url.searchParams.set('requestId', requestId);
    let raw: unknown;
    try {
      const response = await this.fetcher(url, { method: 'GET', redirect: 'error', signal: AbortSignal.timeout(10_000),
        headers: { Accept: 'application/json', ...(this.apiKey ? { 'x-api-key': this.apiKey } : {}) } });
      raw = await json(response);
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      throw new LiFiError('router_unavailable', 'Relay status is temporarily unavailable.');
    }
    const result = checked(z.object({ status: z.enum(['waiting', 'depositing', 'pending', 'submitted', 'delayed',
      'success', 'refund', 'failure']), inTxHashes: z.array(Hash).max(16).optional(),
      txHashes: z.array(z.string()).max(8).optional(),
      originChainId: z.number().int().optional(), destinationChainId: z.number().int().optional() }).passthrough(), raw);
    if ((result.originChainId !== undefined && result.originChainId !== ARBITRUM) ||
      (result.destinationChainId !== undefined && result.destinationChainId !== SOLANA_RELAY) ||
      (result.inTxHashes !== undefined && result.inTxHashes.length > 0 &&
        !result.inTxHashes.some(hash => hash.toLowerCase() === transactionId.toLowerCase()))) return unsafe();

    if (result.status === 'refund' || result.status === 'failure') {
      return { state: 'FAILED', receivedBaseUnits: null, destinationTransactionId: null,
        message: result.status === 'refund' ? 'Relay refunded this route.' : 'Relay could not complete this route.' };
    }
    const pending: RelayStatus = { state: 'PENDING', receivedBaseUnits: null, destinationTransactionId: null, message: null };
    let receipt: unknown;
    try {
      const chainId = await this.rpc.call('ARBITRUM', 'eth_chainId', []);
      if (typeof chainId !== 'string' || !/^0x[0-9a-fA-F]+$/.test(chainId) || BigInt(chainId) !== BigInt(ARBITRUM)) {
        return unsafe('Arbitrum RPC is on the wrong chain.');
      }
      receipt = await this.rpc.call('ARBITRUM', 'eth_getTransactionReceipt', [transactionId]);
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      return pending;
    }
    if (receipt !== null) {
      const verified = checked(z.object({ transactionHash: Hash,
        status: z.string().regex(/^0x[0-9a-fA-F]+$/) }).passthrough(), receipt);
      if (verified.transactionHash.toLowerCase() !== transactionId.toLowerCase()) return unsafe();
      if (BigInt(verified.status) === 0n) return { state: 'FAILED', receivedBaseUnits: null,
        destinationTransactionId: null, message: 'The Arbitrum deposit transaction reverted.' };
      if (BigInt(verified.status) !== 1n) return unsafe();
    }
    if (result.status !== 'success' || receipt === null) return pending;
    if (!result.inTxHashes?.some(hash => hash.toLowerCase() === transactionId.toLowerCase()) ||
      result.txHashes?.length !== 1) return unsafe('Relay success is missing the reviewed source or destination transaction.');
    const signature = result.txHashes[0]!;
    if (!canonicalSolana(signature, 64)) return unsafe();
    let destination: unknown;
    try {
      if (await this.rpc.call('SOLANA', 'getGenesisHash', []) !== MAINNET_GENESIS) return unsafe('Solana RPC is not mainnet.');
      destination = await this.rpc.call('SOLANA', 'getTransaction',
        [signature, { commitment: 'confirmed', encoding: 'jsonParsed', maxSupportedTransactionVersion: 0 }]);
    } catch (error) {
      if (error instanceof LiFiError) throw error;
      return pending;
    }
    if (destination === null) return pending;
    const transaction = checked(z.object({
      transaction: z.object({ signatures: z.array(z.string()).min(1) }).passthrough(),
      meta: z.object({ err: z.unknown(), preTokenBalances: z.array(z.unknown()),
        postTokenBalances: z.array(z.unknown()) }).passthrough(),
    }).passthrough(), destination);
    if (!transaction.transaction.signatures.includes(signature)) return unsafe();
    if (transaction.meta.err !== null) return { state: 'FAILED', receivedBaseUnits: null,
      destinationTransactionId: signature, message: 'The Solana settlement transaction failed.' };
    const Balance = z.object({ accountIndex: z.number().int().nonnegative(), owner: SolAddress.optional(),
      mint: SolAddress, uiTokenAmount: z.object({ amount: UInt,
        decimals: z.number().int().min(0).max(36) }).passthrough() }).passthrough();
    const pre = transaction.meta.preTokenBalances.map(row => checked(Balance, row));
    const post = transaction.meta.postTokenBalances.map(row => checked(Balance, row));
    const beforeByIndex = new Map(pre.map(row => [row.accountIndex, row]));
    const afterByIndex = new Map(post.map(row => [row.accountIndex, row]));
    if (beforeByIndex.size !== pre.length || afterByIndex.size !== post.length) return unsafe();
    const isTarget = (row: typeof pre[number] | undefined) =>
      row?.owner === recipient && row.mint === RELAY_ANTHROPIC_MINT;
    const indices = new Set([...pre.filter(isTarget), ...post.filter(isTarget)].map(row => row.accountIndex));
    if (indices.size === 0) return unsafe('The destination transaction did not credit the reviewed wallet and mint.');
    let received = 0n;
    for (const index of indices) {
      const before = beforeByIndex.get(index);
      const after = afterByIndex.get(index);
      if (isTarget(before)) {
        if (before!.uiTokenAmount.decimals !== 9) return unsafe();
        received -= BigInt(before!.uiTokenAmount.amount);
      }
      if (isTarget(after)) {
        if (after!.uiTokenAmount.decimals !== 9) return unsafe();
        received += BigInt(after!.uiTokenAmount.amount);
      }
    }
    if (received < BigInt(minimum)) return unsafe('The destination wallet received less than the reviewed minimum.');
    return { state: 'DONE', receivedBaseUnits: received.toString(), destinationTransactionId: signature, message: null };
  }
}

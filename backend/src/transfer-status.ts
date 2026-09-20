import { z } from 'zod';
import { ETH_USDC, MAINNET_GENESIS } from './portfolio-upstream.js';
import { checksumEthereumAddress, decodeBase58, TransferPreparationError } from './transfer.js';
import { publicTransferRpc, type TransferRpc } from './transfer-rpc.js';
import { TransferStatusRequestSchema, TransferStatusSchema, type TransferStatus, type TransferStatusRequest } from './transfer-status-schema.js';

const U64_MAX = (1n << 64n) - 1n;
const BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
function parseHex(raw: unknown, maximum = (1n << 256n) - 1n): bigint {
  if (typeof raw !== 'string' || !/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/.test(raw)) throw new Error('Invalid RPC integer');
  const value = BigInt(raw); if (value > maximum) throw new Error('RPC integer exceeded bound'); return value;
}
function unsigned(raw: unknown, maximum = U64_MAX): bigint {
  if ((typeof raw !== 'string' && typeof raw !== 'number') || !/^(?:0|[1-9]\d*)$/.test(String(raw))) throw new Error('Invalid RPC integer');
  const value = BigInt(raw); if (value > maximum) throw new Error('RPC integer exceeded bound'); return value;
}
function decodeSignature(value: string): Uint8Array {
  if (!/^[1-9A-HJ-NP-Za-km-z]{1,88}$/.test(value)) throw new TransferPreparationError('invalid_request', 400, 'Solana transaction signature is invalid.');
  let integer = 0n;
  for (const character of value) integer = integer * 58n + BigInt(BASE58.indexOf(character));
  const bytes: number[] = [];
  while (integer > 0n) { bytes.push(Number(integer & 0xffn)); integer >>= 8n; }
  bytes.reverse();
  const leading = value.match(/^1*/)?.[0].length ?? 0;
  const result = Uint8Array.from([...Array(leading).fill(0), ...bytes]);
  if (result.length !== 64 || result.every(byte => byte === 0)) throw new TransferPreparationError('invalid_request', 400, 'Solana transaction signature must be one nonzero 64-byte value.');
  return result;
}
function validHash(value: unknown): value is string { return typeof value === 'string' && /^0x[0-9a-fA-F]{64}$/.test(value); }
function ethereumSender(value: string): string {
  const result = checksumEthereumAddress(value);
  if (BigInt(value.toLowerCase()) <= 0xffffn) throw new TransferPreparationError('invalid_address', 400, 'Sender is not a usable externally owned address.');
  return result;
}
function ethereumRecipient(value: string, sender: string): string {
  const result = checksumEthereumAddress(value);
  const lower = result.toLowerCase();
  if (lower === sender.toLowerCase() || BigInt(lower) <= 0xffffn ||
    lower === '0x000000000000000000000000000000000000dead' ||
    lower === '0xdead000000000000000000000000000000000000') {
    throw new TransferPreparationError('invalid_address', 400, 'Recipient is not a usable wallet address.');
  }
  return result;
}
function ethereumRpcAddress(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Invalid RPC address');
  try { return checksumEthereumAddress(value); } catch { throw new Error('Invalid RPC address'); }
}
function solanaSender(value: string): string {
  decodeBase58(value);
  if (value === '11111111111111111111111111111111') throw new TransferPreparationError('invalid_address', 400, 'Sender is not a usable wallet address.');
  return value;
}
function solanaRecipient(value: string, sender: string): string {
  decodeBase58(value);
  if (value === sender || value === '11111111111111111111111111111111' ||
    value === '1nc1nerator11111111111111111111111111111111') {
    throw new TransferPreparationError('invalid_address', 400, 'Recipient is not a usable wallet address.');
  }
  return value;
}

function exactEthereumSemantics(input: TransferStatusRequest, sender: string, recipient: string, raw: unknown): { blockNumber: bigint | null } {
  const transaction = z.object({
    hash: z.string(), from: z.string(), to: z.string().nullable(), value: z.unknown(), input: z.string(),
    chainId: z.unknown(), blockNumber: z.unknown().nullable().optional(),
  }).passthrough().parse(raw);
  const transactionId = input.transactionId.toLowerCase();
  const baseUnits = parseHex(`0x${BigInt(input.baseUnits).toString(16)}`);
  const expectedTo = input.assetId === 'ETHEREUM:USDC' ? ETH_USDC : recipient;
  const expectedValue = input.assetId === 'ETHEREUM:native' ? baseUnits : 0n;
  const expectedInput = input.assetId === 'ETHEREUM:native' ? '0x' :
    `0xa9059cbb${recipient.slice(2).toLowerCase().padStart(64, '0')}${baseUnits.toString(16).padStart(64, '0')}`;
  if (transaction.hash.toLowerCase() !== transactionId ||
    ethereumRpcAddress(transaction.from).toLowerCase() !== sender.toLowerCase() ||
    transaction.to === null || ethereumRpcAddress(transaction.to).toLowerCase() !== expectedTo.toLowerCase() ||
    parseHex(transaction.value) !== expectedValue || parseHex(transaction.chainId, U64_MAX) !== 1n ||
    !/^0x[0-9a-fA-F]*$/.test(transaction.input) || transaction.input.toLowerCase() !== expectedInput) {
    throw new TransferPreparationError('transaction_mismatch', 422, 'Transaction does not match the reviewed transfer.');
  }
  return { blockNumber: transaction.blockNumber == null ? null : parseHex(transaction.blockNumber, U64_MAX) };
}

function parsedSolanaAccount(raw: unknown): { pubkey: string; signer: boolean; writable: boolean } {
  const account = z.object({ pubkey: z.string(), signer: z.boolean(), writable: z.boolean() }).passthrough().parse(raw);
  decodeBase58(account.pubkey);
  return account;
}

function verifySolanaTransfer(input: TransferStatusRequest, sender: string, recipient: string, slot: bigint,
  statusError: unknown, raw: unknown, nowMillis: number): { blockTime: string | null } {
  const transaction = z.object({
    slot: z.unknown(),
    blockTime: z.unknown().nullable(),
    meta: z.object({ err: z.unknown().nullable() }).passthrough(),
    transaction: z.object({
      signatures: z.array(z.string()).length(1),
      message: z.object({
        accountKeys: z.array(z.unknown()).length(3),
        instructions: z.array(z.unknown()).length(1),
      }).passthrough(),
    }).passthrough(),
  }).passthrough().parse(raw);
  if (unsigned(transaction.slot) !== slot || transaction.transaction.signatures[0] !== input.transactionId ||
    (transaction.meta.err === null) !== (statusError === null)) {
    throw new TransferPreparationError('transaction_mismatch', 422, 'Transaction does not match the reviewed transfer.');
  }
  const accounts = transaction.transaction.message.accountKeys.map(parsedSolanaAccount);
  const feePayer = accounts[0]!; const destination = accounts[1]!; const systemProgram = accounts[2]!;
  const instruction = z.object({
    program: z.literal('system'),
    programId: z.string(),
    parsed: z.object({
      type: z.literal('transfer'),
      info: z.object({ source: z.string(), destination: z.string(), lamports: z.unknown() }).passthrough(),
    }).passthrough(),
  }).passthrough().parse(transaction.transaction.message.instructions[0]);
  const expectedLamports = unsigned(input.baseUnits);
  if (feePayer.pubkey !== sender || !feePayer.signer || !feePayer.writable ||
    destination.pubkey !== recipient || destination.signer || !destination.writable ||
    systemProgram.pubkey !== '11111111111111111111111111111111' || systemProgram.signer || systemProgram.writable ||
    instruction.programId !== systemProgram.pubkey || instruction.parsed.info.source !== sender ||
    instruction.parsed.info.destination !== recipient || unsigned(instruction.parsed.info.lamports) !== expectedLamports) {
    throw new TransferPreparationError('transaction_mismatch', 422, 'Transaction does not match the reviewed transfer.');
  }
  if (transaction.blockTime === null) return { blockTime: null };
  const millis = Number(unsigned(transaction.blockTime, BigInt(Number.MAX_SAFE_INTEGER))) * 1_000;
  if (millis <= 0 || millis > nowMillis + 30_000) throw new Error('Invalid Solana block time');
  return { blockTime: new Date(millis).toISOString() };
}
function statusFailure(error: unknown): never {
  if (error instanceof TransferPreparationError) throw error;
  if (error instanceof Error && ['AbortError', 'TimeoutError'].includes(error.name)) throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet status read timed out. Poll again safely.');
  throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet transaction status is temporarily unavailable.');
}

export class TransferStatusService {
  private active = 0;
  constructor(private readonly rpc: TransferRpc = publicTransferRpc(), private readonly now = Date.now, private readonly timeoutMs = 10_000) {}
  async status(input: TransferStatusRequest): Promise<TransferStatus> {
    const parsed = TransferStatusRequestSchema.safeParse(input);
    if (!parsed.success) throw new TransferPreparationError('invalid_request', 400, 'Transfer status request is invalid.');
    if (this.active >= 8) throw new TransferPreparationError('busy', 503, 'Transaction status reads are busy. Poll again safely.');
    this.active++;
    const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const result = parsed.data.chain === 'ETHEREUM' ? await this.ethereum(parsed.data, controller.signal) : await this.solana(parsed.data, controller.signal);
      return TransferStatusSchema.parse(result);
    } catch (error) { return statusFailure(error); }
    finally { clearTimeout(timeout); this.active--; }
  }

  private async ethereum(input: TransferStatusRequest, signal: AbortSignal): Promise<TransferStatus> {
    const sender = ethereumSender(input.sender); const recipient = ethereumRecipient(input.recipient, sender);
    const transactionId = input.transactionId.toLowerCase();
    if (!['ETHEREUM:native', 'ETHEREUM:USDC'].includes(input.assetId) || BigInt(input.baseUnits) <= 0n) {
      throw new TransferPreparationError('invalid_request', 400, 'Reviewed Ethereum transfer details are invalid.');
    }
    if (parseHex(await this.rpc.call('ETHEREUM', 'eth_chainId', [], signal)) !== 1n) {
      throw new TransferPreparationError('wrong_network', 503, 'Configured Ethereum RPC is not mainnet.');
    }
    const [headRaw, transactionRaw, receiptRaw] = await Promise.all([
      this.rpc.call('ETHEREUM', 'eth_getBlockByNumber', ['latest', false], signal),
      this.rpc.call('ETHEREUM', 'eth_getTransactionByHash', [transactionId], signal),
      this.rpc.call('ETHEREUM', 'eth_getTransactionReceipt', [transactionId], signal),
    ]);
    const headBlock = z.object({ number: z.unknown(), timestamp: z.unknown() }).passthrough().parse(headRaw);
    const head = parseHex(headBlock.number, U64_MAX);
    const headMillis = Number(parseHex(headBlock.timestamp, BigInt(Number.MAX_SAFE_INTEGER))) * 1_000;
    if (head < 1n || headMillis < this.now() - 300_000 || headMillis > this.now() + 30_000) {
      throw new TransferPreparationError('stale_chain_data', 503, 'Ethereum mainnet status data is not fresh enough.');
    }
    const observedAt = new Date(this.now()).toISOString();
    const base = { schemaVersion: 1 as const, operationId: input.operationId, chain: 'ETHEREUM' as const, caip2: 'eip155:1' as const,
      transactionId, sender, observedAt };
    if (transactionRaw === null) {
      return TransferStatusSchema.parse({ ...base, senderVerified: null, status: 'unknown', confirmations: null,
        isFinalized: false, blockNumber: null, blockHash: null, blockTime: null, finalizedBlockNumber: null, failureCode: null });
    }
    const transaction = exactEthereumSemantics(input, sender, recipient, transactionRaw);
    if (receiptRaw === null) {
      return TransferStatusSchema.parse({ ...base, senderVerified: true, status: 'pending', confirmations: '0', isFinalized: false,
        blockNumber: null, blockHash: null, blockTime: null, finalizedBlockNumber: null, failureCode: null });
    }
    const receipt = z.object({ transactionHash: z.string(), from: z.string(), to: z.string().nullable(), blockNumber: z.unknown(),
      blockHash: z.unknown(), status: z.unknown() }).passthrough().parse(receiptRaw);
    const expectedTo = input.assetId === 'ETHEREUM:USDC' ? ETH_USDC : recipient;
    if (receipt.transactionHash.toLowerCase() !== transactionId ||
      ethereumRpcAddress(receipt.from).toLowerCase() !== sender.toLowerCase() || receipt.to === null ||
      ethereumRpcAddress(receipt.to).toLowerCase() !== expectedTo.toLowerCase() || !validHash(receipt.blockHash)) {
      throw new TransferPreparationError('transaction_mismatch', 422, 'Transaction receipt does not match the reviewed transfer.');
    }
    const blockNumber = parseHex(receipt.blockNumber, U64_MAX); const executionStatus = parseHex(receipt.status, 1n);
    if (blockNumber > head || transaction.blockNumber !== blockNumber) {
      throw new TransferPreparationError('transaction_mismatch', 422, 'Transaction inclusion does not match the reviewed transfer.');
    }
    const [blockRaw, finalizedRaw] = await Promise.all([
      this.rpc.call('ETHEREUM', 'eth_getBlockByNumber', [`0x${blockNumber.toString(16)}`, false], signal),
      this.rpc.call('ETHEREUM', 'eth_getBlockByNumber', ['finalized', false], signal).catch(() => null),
    ]);
    const block = z.object({ number: z.unknown(), hash: z.unknown(), timestamp: z.unknown() }).passthrough().parse(blockRaw);
    if (parseHex(block.number, U64_MAX) !== blockNumber || !validHash(block.hash) || block.hash.toLowerCase() !== receipt.blockHash.toLowerCase()) throw new Error('Receipt block identity mismatch');
    const blockMillis = Number(parseHex(block.timestamp, BigInt(Number.MAX_SAFE_INTEGER))) * 1_000;
    if (blockMillis <= 0 || blockMillis > this.now() + 30_000) throw new Error('Invalid receipt block time');
    let finalizedBlock: bigint | null = null;
    if (finalizedRaw !== null) {
      const parsed = z.object({ number: z.unknown() }).passthrough().safeParse(finalizedRaw);
      if (parsed.success) { const value = parseHex(parsed.data.number, U64_MAX); if (value <= head) finalizedBlock = value; }
    }
    const isFinalized = finalizedBlock !== null && blockNumber <= finalizedBlock;
    const failed = executionStatus === 0n;
    const status = failed ? 'failed' as const : isFinalized ? 'finalized' as const : 'confirmed' as const;
    return { ...base, senderVerified: true, status, confirmations: (head - blockNumber + 1n).toString(), isFinalized,
      blockNumber: blockNumber.toString(), blockHash: receipt.blockHash.toLowerCase(), blockTime: new Date(blockMillis).toISOString(),
      finalizedBlockNumber: finalizedBlock?.toString() ?? null, failureCode: failed ? 'execution_reverted' : null };
  }

  private async solana(input: TransferStatusRequest, signal: AbortSignal): Promise<TransferStatus> {
    const sender = solanaSender(input.sender); const recipient = solanaRecipient(input.recipient, sender);
    decodeSignature(input.transactionId);
    if (input.assetId !== 'SOLANA:native' || BigInt(input.baseUnits) <= 0n || BigInt(input.baseUnits) > U64_MAX) {
      throw new TransferPreparationError('invalid_request', 400, 'Reviewed Solana transfer details are invalid.');
    }
    if (await this.rpc.call('SOLANA', 'getGenesisHash', [], signal) !== MAINNET_GENESIS) {
      throw new TransferPreparationError('wrong_network', 503, 'Configured Solana RPC is not mainnet.');
    }
    const raw = await this.rpc.call('SOLANA', 'getSignatureStatuses', [[input.transactionId], { searchTransactionHistory: true }], signal);
    const parsed = z.object({ context: z.object({ slot: z.unknown() }), value: z.array(z.unknown()).length(1) }).parse(raw);
    const observedSlot = unsigned(parsed.context.slot); const value = parsed.value[0]; const observedAt = new Date(this.now()).toISOString();
    if (observedSlot === 0n) throw new Error('Invalid observed slot');
    const base = { schemaVersion: 1 as const, operationId: input.operationId, chain: 'SOLANA' as const,
      caip2: 'solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp' as const, transactionId: input.transactionId, sender, observedSlot: observedSlot.toString(), observedAt };
    const unknown = () => TransferStatusSchema.parse({ ...base, senderVerified: null, status: 'unknown' as const, confirmations: null,
      isFinalized: false, slot: null, blockTime: null, confirmationStatus: null, failureCode: null });
    if (value === null) return unknown();
    const statusRow = z.object({ slot: z.unknown(), confirmations: z.unknown().nullable(), err: z.unknown().nullable(),
      confirmationStatus: z.enum(['processed', 'confirmed', 'finalized']) }).passthrough().parse(value);
    const slot = unsigned(statusRow.slot); if (slot === 0n || slot > observedSlot) throw new Error('Signature status exceeds observed slot');
    const confirmations = statusRow.confirmations === null ? null : unsigned(statusRow.confirmations).toString();
    const failed = statusRow.err !== null;
    // Solana RPC does not provide a safe parsed transaction view at processed commitment. A
    // processed-only signature remains nonterminal until the exact reviewed transfer is readable.
    const readableCommitment = statusRow.confirmationStatus === 'processed' ? 'confirmed' : statusRow.confirmationStatus;
    const transactionRaw = await this.rpc.call('SOLANA', 'getTransaction', [input.transactionId,
      { encoding: 'jsonParsed', commitment: readableCommitment, maxSupportedTransactionVersion: 0 }], signal);
    if (transactionRaw === null) return unknown();
    const verified = verifySolanaTransfer(input, sender, recipient, slot, statusRow.err, transactionRaw, this.now());
    const senderVerified = true as const;
    const blockTime = verified.blockTime;
    if (failed && statusRow.confirmationStatus === 'processed') {
      // Never report an unrooted execution failure as terminal. It remains pollable and can be
      // replaced by a later observation after a fork.
      return { ...base, senderVerified, status: 'failed', confirmations, isFinalized: false, slot: slot.toString(),
        blockTime, confirmationStatus: statusRow.confirmationStatus, failureCode: 'transaction_error' };
    }
    const status = failed ? 'failed' as const : statusRow.confirmationStatus === 'processed' ? 'pending' as const :
      statusRow.confirmationStatus === 'confirmed' ? 'confirmed' as const : 'finalized' as const;
    return { ...base, senderVerified, status, confirmations, isFinalized: statusRow.confirmationStatus === 'finalized', slot: slot.toString(),
      blockTime, confirmationStatus: statusRow.confirmationStatus, failureCode: failed ? 'transaction_error' : null };
  }
}

export { decodeSignature };

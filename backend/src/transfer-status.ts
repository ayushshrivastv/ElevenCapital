import { z } from 'zod';
import { MAINNET_GENESIS } from './portfolio-upstream.js';
import { decodeBase58, TransferPreparationError } from './transfer.js';
import { publicTransferRpc, type TransferRpc } from './transfer-rpc.js';
import { TransferStatusRequestSchema, TransferStatusSchema, type TransferStatus, type TransferStatusRequest } from './transfer-status-schema.js';

const U64_MAX = (1n << 64n) - 1n;
const BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';

function unsigned(raw: unknown, maximum = U64_MAX): bigint {
  if ((typeof raw !== 'string' && typeof raw !== 'number') || !/^(?:0|[1-9]\d*)$/.test(String(raw))) {
    throw new Error('Invalid RPC integer');
  }
  const value = BigInt(raw);
  if (value > maximum) throw new Error('RPC integer exceeded bound');
  return value;
}

export function decodeSignature(value: string): Uint8Array {
  if (!/^[1-9A-HJ-NP-Za-km-z]{1,88}$/.test(value)) {
    throw new TransferPreparationError('invalid_request', 400, 'Solana transaction signature is invalid.');
  }
  let integer = 0n;
  for (const character of value) integer = integer * 58n + BigInt(BASE58.indexOf(character));
  const bytes: number[] = [];
  while (integer > 0n) { bytes.push(Number(integer & 0xffn)); integer >>= 8n; }
  bytes.reverse();
  const leading = value.match(/^1*/)?.[0].length ?? 0;
  const result = Uint8Array.from([...Array(leading).fill(0), ...bytes]);
  if (result.length !== 64 || result.every(byte => byte === 0)) {
    throw new TransferPreparationError('invalid_request', 400, 'Solana transaction signature must be one nonzero 64-byte value.');
  }
  return result;
}

function solanaSender(value: string): string {
  decodeBase58(value);
  if (value === '11111111111111111111111111111111') {
    throw new TransferPreparationError('invalid_address', 400, 'Sender is not a usable wallet address.');
  }
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
  const feePayer = accounts[0]!;
  const destination = accounts[1]!;
  const systemProgram = accounts[2]!;
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
  if (error instanceof Error && ['AbortError', 'TimeoutError'].includes(error.name)) {
    throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet status read timed out. Poll again safely.');
  }
  throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet transaction status is temporarily unavailable.');
}

export class TransferStatusService {
  private active = 0;
  constructor(private readonly rpc: TransferRpc = publicTransferRpc(), private readonly now = Date.now,
    private readonly timeoutMs = 10_000) {}

  async status(input: TransferStatusRequest): Promise<TransferStatus> {
    const parsed = TransferStatusRequestSchema.safeParse(input);
    if (!parsed.success) throw new TransferPreparationError('invalid_request', 400, 'Transfer status request is invalid.');
    if (this.active >= 8) throw new TransferPreparationError('busy', 503, 'Transaction status reads are busy. Poll again safely.');
    this.active++;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return TransferStatusSchema.parse(await this.solana(parsed.data, controller.signal));
    } catch (error) { return statusFailure(error); }
    finally { clearTimeout(timeout); this.active--; }
  }

  private async solana(input: TransferStatusRequest, signal: AbortSignal): Promise<TransferStatus> {
    const sender = solanaSender(input.sender);
    const recipient = solanaRecipient(input.recipient, sender);
    decodeSignature(input.transactionId);
    if (input.assetId !== 'SOLANA:native' || BigInt(input.baseUnits) <= 0n || BigInt(input.baseUnits) > U64_MAX) {
      throw new TransferPreparationError('invalid_request', 400, 'Reviewed Solana transfer details are invalid.');
    }
    if (await this.rpc.call('SOLANA', 'getGenesisHash', [], signal) !== MAINNET_GENESIS) {
      throw new TransferPreparationError('wrong_network', 503, 'Configured Solana RPC is not mainnet.');
    }
    const raw = await this.rpc.call('SOLANA', 'getSignatureStatuses', [[input.transactionId], { searchTransactionHistory: true }], signal);
    const parsed = z.object({ context: z.object({ slot: z.unknown() }), value: z.array(z.unknown()).length(1) }).parse(raw);
    const observedSlot = unsigned(parsed.context.slot);
    const value = parsed.value[0];
    const observedAt = new Date(this.now()).toISOString();
    if (observedSlot === 0n) throw new Error('Invalid observed slot');
    const base = { schemaVersion: 1 as const, operationId: input.operationId, chain: 'SOLANA' as const,
      caip2: 'solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp' as const, transactionId: input.transactionId, sender,
      observedSlot: observedSlot.toString(), observedAt };
    const unknown = () => TransferStatusSchema.parse({ ...base, senderVerified: null, status: 'unknown' as const,
      confirmations: null, isFinalized: false, slot: null, blockTime: null, confirmationStatus: null, failureCode: null });
    if (value === null) return unknown();
    const statusRow = z.object({ slot: z.unknown(), confirmations: z.unknown().nullable(), err: z.unknown().nullable(),
      confirmationStatus: z.enum(['processed', 'confirmed', 'finalized']) }).passthrough().parse(value);
    const slot = unsigned(statusRow.slot);
    if (slot === 0n || slot > observedSlot) throw new Error('Signature status exceeds observed slot');
    const confirmations = statusRow.confirmations === null ? null : unsigned(statusRow.confirmations).toString();
    const failed = statusRow.err !== null;
    const readableCommitment = statusRow.confirmationStatus === 'processed' ? 'confirmed' : statusRow.confirmationStatus;
    const transactionRaw = await this.rpc.call('SOLANA', 'getTransaction', [input.transactionId,
      { encoding: 'jsonParsed', commitment: readableCommitment, maxSupportedTransactionVersion: 0 }], signal);
    if (transactionRaw === null) return unknown();
    const verified = verifySolanaTransfer(input, sender, recipient, slot, statusRow.err, transactionRaw, this.now());
    const status = failed ? 'failed' as const : statusRow.confirmationStatus === 'processed' ? 'pending' as const :
      statusRow.confirmationStatus === 'confirmed' ? 'confirmed' as const : 'finalized' as const;
    return { ...base, senderVerified: true, status, confirmations,
      isFinalized: statusRow.confirmationStatus === 'finalized', slot: slot.toString(), blockTime: verified.blockTime,
      confirmationStatus: statusRow.confirmationStatus, failureCode: failed ? 'transaction_error' : null };
  }
}

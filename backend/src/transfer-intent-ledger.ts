import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';
import type { TransferPreparation, TransferRequest } from './transfer-schema.js';
import type { TransferStatus, TransferStatusRequest } from './transfer-status-schema.js';
import type { TransferIntentCommit, TransferIntentMutationResponse, TransferIntentRelease } from './transfer-intent-schema.js';

const IntentState = z.enum(['prepared', 'committed', 'broadcast', 'unknown', 'pending', 'confirmed', 'finalized', 'failed', 'released']);
const StoredIntent = z.object({
  operationId: z.uuid(), subject: z.string().min(1).max(200), walletId: z.string().min(1).max(200),
  chain: z.enum(['ETHEREUM', 'SOLANA']), sender: z.string(), recipient: z.string(),
  assetId: z.enum(['ETHEREUM:native', 'ETHEREUM:USDC', 'SOLANA:native', 'SOLANA:USDC']),
  baseUnits: z.string().regex(/^[1-9]\d*$/), state: IntentState, transactionId: z.string().nullable(),
  preparedAt: z.string().datetime(), expiresAt: z.string().datetime(), updatedAt: z.string().datetime(),
  finalized: z.boolean(),
}).strict();
type StoredIntent = z.infer<typeof StoredIntent>;
const LedgerFile = z.object({ schemaVersion: z.literal(1), intents: z.array(StoredIntent).max(20_000) }).strict();
type LedgerFile = z.infer<typeof LedgerFile>;

export class TransferIntentError extends Error {
  constructor(readonly code: 'intent_conflict' | 'unresolved_transfer' | 'intent_not_found' | 'intent_state_invalid' | 'intent_storage_unavailable',
    readonly statusCode: 409 | 422 | 503, message: string) { super(message); }
}

function identity(value: Pick<StoredIntent, 'operationId' | 'walletId' | 'chain' | 'sender' | 'recipient' | 'assetId' | 'baseUnits'>): string {
  return [value.operationId, value.walletId, value.chain, value.sender, value.recipient, value.assetId, value.baseUnits].join('\u0000');
}
function unresolved(value: StoredIntent): boolean {
  if (value.state === 'released' || value.state === 'prepared' || value.state === 'finalized') return false;
  return !value.finalized;
}

/**
 * Synchronous, atomic single-process ledger. Every mutation is persisted before success returns.
 * It stores public transaction metadata only; bearer tokens and signing material never enter it.
 */
export class TransferIntentLedger {
  private data: LedgerFile;
  private healthy = true;
  constructor(private readonly filename: string | null = resolve('runtime/private-transfer-intents.json'),
    private readonly now = Date.now) {
    this.data = { schemaVersion: 1, intents: [] };
    if (filename === null) return;
    try {
      if (existsSync(filename)) {
        const raw = readFileSync(filename, 'utf8');
        if (Buffer.byteLength(raw) > 8 * 1_024 * 1_024) throw new Error('Ledger too large');
        this.data = LedgerFile.parse(JSON.parse(raw));
        chmodSync(filename, 0o600);
      }
    } catch {
      this.healthy = false;
    }
  }

  registerPrepared(principal: PrivyPrincipal, request: TransferRequest, prepared: TransferPreparation): void {
    this.mutate(intents => {
      const candidate: StoredIntent = {
        operationId: request.operationId, subject: principal.subject, walletId: request.walletId,
        chain: request.chain, sender: prepared.sender, recipient: prepared.recipient,
        assetId: prepared.asset.id, baseUnits: prepared.baseUnits, state: 'prepared', transactionId: null,
        preparedAt: prepared.preparedAt, expiresAt: prepared.expiresAt, updatedAt: new Date(this.now()).toISOString(), finalized: false,
      };
      const existing = intents.find(value => value.operationId === request.operationId);
      if (existing) {
        if (existing.subject !== principal.subject || identity(existing) !== identity(candidate)) {
          throw new TransferIntentError('intent_conflict', 409, 'This transfer operation conflicts with an existing operation.');
        }
        if (existing.state !== 'prepared') throw new TransferIntentError('intent_state_invalid', 422,
          'This transfer operation was already submitted or closed.');
        existing.preparedAt = candidate.preparedAt; existing.expiresAt = candidate.expiresAt;
        existing.updatedAt = candidate.updatedAt; return true;
      }
      intents.push(candidate); return true;
    });
  }

  commit(principal: PrivyPrincipal, request: TransferIntentCommit): TransferIntentMutationResponse {
    let idempotent = false;
    this.mutate(intents => {
      const current = this.requireExact(intents, principal, request);
      if (current.state === 'committed') { idempotent = true; return false; }
      if (current.state !== 'prepared') throw new TransferIntentError('intent_state_invalid', 422,
        'This transfer operation can no longer be submitted.');
      if (Date.parse(current.expiresAt) <= this.now()) throw new TransferIntentError('intent_state_invalid', 422,
        'The prepared transfer expired. Review it again.');
      const prior = intents.find(value => value.subject === principal.subject && value.walletId === request.walletId &&
        value.operationId !== request.operationId && unresolved(value));
      if (prior) throw new TransferIntentError('unresolved_transfer', 409,
        'This wallet has an unresolved transfer. Check History before sending again.');
      current.state = 'committed'; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: request.operationId, state: 'committed', idempotent };
  }

  release(principal: PrivyPrincipal, request: TransferIntentRelease): TransferIntentMutationResponse {
    let idempotent = false;
    this.mutate(intents => {
      const current = this.requireExact(intents, principal, request);
      if (current.state === 'released') { idempotent = true; return false; }
      // The Android journal is persisted before commit. After process death it can therefore
      // prove Privy's provider was never called while the server is still either prepared or
      // committed. Both states are safe to release; any transaction-bound state fails closed.
      if (!['prepared', 'committed'].includes(current.state) || current.transactionId !== null) {
        throw new TransferIntentError('intent_state_invalid', 422,
          'A broadcast or unresolved transfer cannot be released. Check its status.');
      }
      current.state = 'released'; current.finalized = true; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: request.operationId, state: 'released', idempotent };
  }

  recordTransaction(principal: PrivyPrincipal, request: TransferStatusRequest): void {
    this.mutate(intents => {
      const current = this.requireExact(intents, principal, request);
      if (current.state === 'prepared' || current.state === 'released') throw new TransferIntentError('intent_state_invalid', 422,
        'This operation was not committed for submission.');
      if (current.transactionId !== null && current.transactionId !== request.transactionId) {
        throw new TransferIntentError('intent_conflict', 409, 'This operation is already bound to another transaction identifier.');
      }
      if (current.transactionId === request.transactionId) return false;
      current.transactionId = request.transactionId;
      current.state = 'broadcast'; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
  }

  recordObservation(principal: PrivyPrincipal, request: TransferStatusRequest, observation: TransferStatus): void {
    this.mutate(intents => {
      const current = this.requireExact(intents, principal, request);
      if (current.transactionId !== request.transactionId) throw new TransferIntentError('intent_conflict', 409,
        'Transaction identifier does not match the committed operation.');
      if (current.finalized) return false;
      current.state = observation.status;
      current.finalized = observation.isFinalized;
      current.updatedAt = observation.observedAt;
      return true;
    });
  }

  snapshot(): readonly StoredIntent[] { return structuredClone(this.data.intents); }

  private requireExact(intents: StoredIntent[], principal: PrivyPrincipal,
    request: Pick<StoredIntent, 'operationId' | 'walletId' | 'chain' | 'sender' | 'recipient' | 'assetId' | 'baseUnits'>): StoredIntent {
    const current = intents.find(value => value.operationId === request.operationId);
    if (!current || current.subject !== principal.subject) throw new TransferIntentError('intent_not_found', 422,
      'The reviewed transfer intent was not found for this user.');
    if (identity(current) !== identity(request)) throw new TransferIntentError('intent_conflict', 409,
      'Transfer details no longer match the reviewed intent.');
    return current;
  }

  private mutate(change: (intents: StoredIntent[]) => boolean): void {
    if (!this.healthy) throw new TransferIntentError('intent_storage_unavailable', 503,
      'Transfer safety storage is unavailable. Nothing was submitted.');
    const next = structuredClone(this.data);
    next.intents = next.intents.filter(value => value.state !== 'prepared' || Date.parse(value.expiresAt) > this.now());
    const changed = change(next.intents);
    if (!changed) return;
    const cutoff = this.now() - 30 * 24 * 60 * 60_000;
    next.intents = next.intents.filter(value => !(value.finalized && Date.parse(value.updatedAt) < cutoff));
    if (next.intents.length > 20_000) throw new TransferIntentError('intent_storage_unavailable', 503,
      'Transfer safety storage is full. Nothing was submitted.');
    if (this.filename !== null) {
      try { this.atomicWrite(next); }
      catch {
        this.healthy = false;
        throw new TransferIntentError('intent_storage_unavailable', 503,
          'Transfer safety storage could not be committed. Nothing was submitted.');
      }
    }
    this.data = next;
  }

  private atomicWrite(value: LedgerFile): void {
    const filename = requireFilename(this.filename);
    const directory = dirname(filename); mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
    const temporary = `${filename}.tmp-${process.pid}-${this.now()}`;
    let descriptor: number | null = null;
    try {
      descriptor = openSync(temporary, 'wx', 0o600);
      writeFileSync(descriptor, `${JSON.stringify(value)}\n`, 'utf8'); fsyncSync(descriptor); closeSync(descriptor); descriptor = null;
      renameSync(temporary, filename); chmodSync(filename, 0o600);
      const directoryFd = openSync(directory, 'r'); try { fsyncSync(directoryFd); } finally { closeSync(directoryFd); }
    } catch (error) {
      if (descriptor !== null) closeSync(descriptor);
      rmSync(temporary, { force: true }); throw error;
    }
  }
}

function requireFilename(value: string | null): string { if (value === null) throw new Error('No ledger path'); return value; }

import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';
import { CommunityBuyPreparationSchema, CommunityBuyPrepareRequestSchema, CommunityBuyStatusSchema,
  type CommunityBuyIdentity, type CommunityBuyMutationResponse, type CommunityBuyPreparation,
  type CommunityBuyPrepareRequest, type CommunityBuyStatus } from './community-buy-schema.js';

const State = z.enum(['prepared', 'committed', 'broadcast', 'unknown', 'confirmed', 'failed', 'expired', 'released']);
const StoredSchema = z.object({
  subject: z.string().min(1).max(200), request: CommunityBuyPrepareRequestSchema,
  preparation: CommunityBuyPreparationSchema, state: State, transactionId: z.string().nullable(),
  signedTransactionBase64: z.string().max(4_096).nullable(), submittedAt: z.iso.datetime().nullable(),
  updatedAt: z.iso.datetime(), finalized: z.boolean(), message: z.string().nullable(),
  tokenAmountReceived: z.string().nullable(), walletTokenBalanceRaw: z.string().nullable(),
}).strict();
export type StoredCommunityBuy = z.infer<typeof StoredSchema>;
const LedgerSchema = z.object({ schemaVersion: z.literal(1), buys: z.array(StoredSchema).max(2_000) }).strict();
type LedgerFile = z.infer<typeof LedgerSchema>;

export class CommunityBuyLedgerError extends Error {
  constructor(readonly code: 'buy_conflict' | 'unresolved_buy' | 'buy_not_found' | 'buy_state_invalid' |
    'buy_storage_unavailable', readonly statusCode: 409 | 422 | 503, message: string) { super(message); }
}
function identity(value: CommunityBuyPrepareRequest): string {
  return [value.operationId, value.network, value.walletId, value.walletAddress,
    value.mintAddress, value.amountInLamports, value.slippageBps].join('\u0000');
}
function unresolved(value: StoredCommunityBuy): boolean {
  return ['prepared', 'committed', 'broadcast', 'unknown'].includes(value.state) && !value.finalized;
}

/** Atomic, single-process intent ledger. It stores public transaction bytes, not wallet keys or bearer tokens. */
export class CommunityBuyLedger {
  private data: LedgerFile = { schemaVersion: 1, buys: [] };
  private healthy = true;
  constructor(private readonly filename: string | null = resolve('runtime/private-community-buy-mainnet-intents.json'),
    private readonly now = Date.now) {
    if (filename === null) return;
    try {
      if (existsSync(filename)) {
        const raw = readFileSync(filename, 'utf8');
        if (Buffer.byteLength(raw) > 32 * 1_024 * 1_024) throw new Error('Ledger too large');
        this.data = LedgerSchema.parse(JSON.parse(raw)); chmodSync(filename, 0o600);
      }
    } catch { this.healthy = false; }
  }

  findPreparation(principal: PrivyPrincipal, request: CommunityBuyPrepareRequest): CommunityBuyPreparation | null {
    this.requireHealthy(); this.mutate(() => false);
    const current = this.data.buys.find(row => row.request.operationId === request.operationId);
    if (!current) {
      const prior = this.data.buys.find(row => row.subject === principal.subject &&
        row.request.walletId === request.walletId && unresolved(row) &&
        !(row.state === 'prepared' && Date.parse(row.preparation.expiresAt) <= this.now()));
      if (prior) throw new CommunityBuyLedgerError('unresolved_buy', 409,
        'This wallet has an unresolved mainnet buy. Check its status first.');
      return null;
    }
    if (current.subject !== principal.subject || identity(current.request) !== identity(request)) {
      throw new CommunityBuyLedgerError('buy_conflict', 409, 'This buy operation conflicts with an existing operation.');
    }
    if (current.state !== 'prepared' || Date.parse(current.preparation.expiresAt) <= this.now()) {
      throw new CommunityBuyLedgerError('buy_state_invalid', 422, 'This buy quote has expired or was already used.');
    }
    return structuredClone(current.preparation);
  }

  registerPrepared(principal: PrivyPrincipal, request: CommunityBuyPrepareRequest, preparation: CommunityBuyPreparation): void {
    this.mutate(rows => {
      const existing = rows.find(row => row.request.operationId === request.operationId);
      if (existing) {
        if (existing.subject !== principal.subject || identity(existing.request) !== identity(request) ||
          existing.preparation.transactionSha256 !== preparation.transactionSha256) {
          throw new CommunityBuyLedgerError('buy_conflict', 409, 'This buy operation conflicts with an existing operation.');
        }
        if (existing.state !== 'prepared') throw new CommunityBuyLedgerError('buy_state_invalid', 422,
          'This buy operation was already submitted or closed.');
        return false;
      }
      if (rows.some(row => row.subject === principal.subject && row.request.walletId === request.walletId && unresolved(row))) {
        throw new CommunityBuyLedgerError('unresolved_buy', 409, 'This wallet has an unresolved mainnet buy.');
      }
      rows.push({ subject: principal.subject, request, preparation, state: 'prepared', transactionId: null,
        signedTransactionBase64: null, submittedAt: null, updatedAt: new Date(this.now()).toISOString(),
        finalized: false, message: null, tokenAmountReceived: null, walletTokenBalanceRaw: null });
      return true;
    });
  }

  commit(principal: PrivyPrincipal, value: CommunityBuyIdentity): CommunityBuyMutationResponse {
    let idempotent = false;
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, value);
      if (current.state === 'committed') { idempotent = true; return false; }
      if (current.state !== 'prepared' || Date.parse(current.preparation.expiresAt) <= this.now()) {
        throw new CommunityBuyLedgerError('buy_state_invalid', 422, 'This buy quote can no longer be committed.');
      }
      current.state = 'committed'; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: value.operationId, state: 'committed', idempotent };
  }

  release(principal: PrivyPrincipal, value: CommunityBuyIdentity): CommunityBuyMutationResponse {
    let idempotent = false;
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, value);
      if (current.state === 'released') { idempotent = true; return false; }
      if (!['prepared', 'committed'].includes(current.state) || current.transactionId !== null) {
        throw new CommunityBuyLedgerError('buy_state_invalid', 422, 'A submitted or uncertain buy cannot be released.');
      }
      current.state = 'released'; current.finalized = true; current.message = 'Buy cancelled before broadcast.';
      current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: value.operationId, state: 'released', idempotent };
  }

  requireForSubmission(principal: PrivyPrincipal, value: CommunityBuyIdentity): StoredCommunityBuy {
    this.requireHealthy(); const current = this.requireExact(this.data.buys, principal, value);
    if (!['committed', 'broadcast', 'unknown'].includes(current.state)) {
      throw new CommunityBuyLedgerError('buy_state_invalid', 422, 'This buy was not committed for wallet signing.');
    }
    return structuredClone(current);
  }

  bindBroadcast(principal: PrivyPrincipal, value: CommunityBuyIdentity, transactionId: string,
    signedTransactionBase64: string): { idempotent: boolean; submittedAt: string } {
    let idempotent = false; let submittedAt = new Date(this.now()).toISOString();
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, value);
      if (current.transactionId !== null) {
        if (current.transactionId !== transactionId || current.signedTransactionBase64 !== signedTransactionBase64) {
          throw new CommunityBuyLedgerError('buy_conflict', 409, 'This buy is bound to a different transaction.');
        }
        idempotent = true; submittedAt = current.submittedAt ?? submittedAt; return false;
      }
      if (current.state !== 'committed') throw new CommunityBuyLedgerError('buy_state_invalid', 422,
        'This buy was not committed for submission.');
      current.transactionId = transactionId; current.signedTransactionBase64 = signedTransactionBase64;
      current.submittedAt = submittedAt; current.state = 'unknown'; current.message = 'Broadcast outcome is being checked.';
      current.updatedAt = submittedAt; return true;
    });
    return { idempotent, submittedAt };
  }

  recordBroadcast(principal: PrivyPrincipal, value: CommunityBuyIdentity, succeeded: boolean): void {
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, value);
      if (!current.transactionId || current.finalized) return false;
      current.state = succeeded ? 'broadcast' : 'unknown';
      current.message = succeeded ? 'Buy sent to Solana mainnet.' : 'Broadcast outcome is uncertain; check status.';
      current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
  }

  current(principal: PrivyPrincipal, value: CommunityBuyIdentity): StoredCommunityBuy {
    this.requireHealthy(); return structuredClone(this.requireExact(this.data.buys, principal, value));
  }
  recordObservation(principal: PrivyPrincipal, value: CommunityBuyIdentity, status: CommunityBuyStatus): void {
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, value);
      if (current.transactionId !== status.transactionId) {
        throw new CommunityBuyLedgerError('buy_conflict', 409, 'Buy transaction identifier changed.');
      }
      current.state = status.state; current.finalized = status.isFinalized; current.message = status.message;
      current.tokenAmountReceived = status.tokenAmountReceived;
      current.walletTokenBalanceRaw = status.walletTokenBalanceRaw;
      current.updatedAt = status.observedAt; return true;
    });
  }
  snapshot(): readonly StoredCommunityBuy[] { return structuredClone(this.data.buys); }

  private requireExact(rows: StoredCommunityBuy[], principal: PrivyPrincipal, value: CommunityBuyIdentity): StoredCommunityBuy {
    const current = rows.find(row => row.request.operationId === value.operationId);
    if (!current || current.subject !== principal.subject) throw new CommunityBuyLedgerError('buy_not_found', 422,
      'This buy was not found for the signed-in user.');
    if (current.request.walletId !== value.walletId || current.request.walletAddress !== value.walletAddress ||
      current.preparation.transactionSha256 !== value.transactionSha256) {
      throw new CommunityBuyLedgerError('buy_conflict', 409, 'Buy details changed from the reviewed quote.');
    }
    return current;
  }
  private requireHealthy(): void {
    if (!this.healthy) throw new CommunityBuyLedgerError('buy_storage_unavailable', 503,
      'Buy safety storage is unavailable. Nothing was submitted.');
  }
  private mutate(change: (rows: StoredCommunityBuy[]) => boolean): void {
    this.requireHealthy(); const next = structuredClone(this.data);
    const before = next.buys.length;
    next.buys = next.buys.filter(row => !(row.state === 'prepared' && Date.parse(row.preparation.expiresAt) <= this.now()));
    const changed = change(next.buys); if (!changed && next.buys.length === before) return;
    const cutoff = this.now() - 30 * 24 * 60 * 60_000;
    next.buys = next.buys.filter(row => !(row.finalized && Date.parse(row.updatedAt) < cutoff));
    if (next.buys.length > 2_000 || Buffer.byteLength(JSON.stringify(next)) > 32 * 1_024 * 1_024) {
      throw new CommunityBuyLedgerError('buy_storage_unavailable', 503, 'Buy safety storage is full. Nothing was submitted.');
    }
    if (this.filename !== null) {
      try { this.atomicWrite(next); }
      catch { this.healthy = false; throw new CommunityBuyLedgerError('buy_storage_unavailable', 503,
        'Buy safety storage could not be committed. Nothing was submitted.'); }
    }
    this.data = next;
  }
  private atomicWrite(value: LedgerFile): void {
    const filename = this.filename;
    if (filename === null) throw new Error('Missing buy ledger path');
    const directory = dirname(filename); mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
    const temporary = `${filename}.tmp-${process.pid}-${this.now()}`; let fd: number | null = null;
    try {
      fd = openSync(temporary, 'wx', 0o600); writeFileSync(fd, `${JSON.stringify(value)}\n`, 'utf8');
      fsyncSync(fd); closeSync(fd); fd = null; renameSync(temporary, filename); chmodSync(filename, 0o600);
      const directoryFd = openSync(directory, 'r'); try { fsyncSync(directoryFd); } finally { closeSync(directoryFd); }
    } catch (error) { if (fd !== null) closeSync(fd); rmSync(temporary, { force: true }); throw error; }
  }
}

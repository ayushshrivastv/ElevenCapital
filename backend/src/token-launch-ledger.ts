import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';
import { TokenLaunchPreparationSchema, TokenLaunchPrepareRequestSchema, type TokenLaunchIdentity,
  type TokenLaunchMutationResponse, type TokenLaunchPreparation, type TokenLaunchPrepareRequest,
  type TokenLaunchStatus } from './token-launch-schema.js';

const State = z.enum(['prepared', 'committed', 'broadcast', 'unknown', 'confirmed', 'failed', 'expired', 'released']);
const StoredLaunchSchema = z.object({
  subject: z.string().min(1).max(200), request: TokenLaunchPrepareRequestSchema, preparation: TokenLaunchPreparationSchema,
  state: State, transactionId: z.string().nullable(), signedTransactionBase64: z.string().max(4_096).nullable(),
  submittedAt: z.string().datetime().nullable(), updatedAt: z.string().datetime(), finalized: z.boolean(),
  message: z.string().max(240).nullable(),
}).strict();
export type StoredTokenLaunch = z.infer<typeof StoredLaunchSchema>;
const LedgerFileSchema = z.object({ schemaVersion: z.literal(1), launches: z.array(StoredLaunchSchema).max(2_000) }).strict();
type LedgerFile = z.infer<typeof LedgerFileSchema>;

export class TokenLaunchLedgerError extends Error {
  constructor(readonly code: 'launch_conflict' | 'unresolved_launch' | 'launch_not_found' | 'launch_state_invalid' |
    'launch_storage_unavailable', readonly statusCode: 409 | 422 | 503, message: string) { super(message); }
}

function requestIdentity(value: TokenLaunchPrepareRequest): string {
  return [value.operationId, value.network, value.walletId, value.walletAddress, value.name, value.symbol, value.metadataUri,
    value.totalTokenSupply, value.feeBps, value.migrationThresholdSol, value.percentageSupplyOnMigration].join('\u0000');
}
function unresolved(value: StoredTokenLaunch): boolean {
  return ['prepared', 'committed', 'broadcast', 'unknown'].includes(value.state) && !value.finalized;
}

/** Atomic single-process safety ledger. It persists public transactions, never keypair secret material or bearer tokens. */
export class TokenLaunchLedger {
  private data: LedgerFile = { schemaVersion: 1, launches: [] };
  private healthy = true;
  constructor(private readonly filename: string | null = resolve('runtime/private-token-launch-mainnet-intents.json'),
    private readonly now = Date.now) {
    if (filename === null) return;
    try {
      if (existsSync(filename)) {
        const raw = readFileSync(filename, 'utf8');
        if (Buffer.byteLength(raw) > 32 * 1_024 * 1_024) throw new Error('Ledger too large');
        this.data = LedgerFileSchema.parse(JSON.parse(raw)); chmodSync(filename, 0o600);
      }
    } catch { this.healthy = false; }
  }

  findPreparation(principal: PrivyPrincipal, request: TokenLaunchPrepareRequest): TokenLaunchPreparation | null {
    this.requireHealthy(); this.mutate(() => false);
    const current = this.data.launches.find(row => row.request.operationId === request.operationId);
    if (!current) {
      const prior = this.data.launches.find(row => row.subject === principal.subject &&
        row.request.walletId === request.walletId && unresolved(row) &&
        !(row.state === 'prepared' && Date.parse(row.preparation.expiresAt) <= this.now()));
      if (prior) throw new TokenLaunchLedgerError('unresolved_launch', 409,
        'This wallet already has an unresolved token launch. Resume or release it first.');
      return null;
    }
    if (current.subject !== principal.subject || requestIdentity(current.request) !== requestIdentity(request)) {
      throw new TokenLaunchLedgerError('launch_conflict', 409, 'This launch operation conflicts with an existing operation.');
    }
    if (current.state !== 'prepared' || Date.parse(current.preparation.expiresAt) <= this.now()) {
      throw new TokenLaunchLedgerError('launch_state_invalid', 422, 'This launch operation can no longer be prepared.');
    }
    return structuredClone(current.preparation);
  }

  registerPrepared(principal: PrivyPrincipal, request: TokenLaunchPrepareRequest, preparation: TokenLaunchPreparation): void {
    this.mutate(rows => {
      const existing = rows.find(row => row.request.operationId === request.operationId);
      if (existing) {
        if (existing.subject !== principal.subject || requestIdentity(existing.request) !== requestIdentity(request) ||
          existing.preparation.transactionSha256 !== preparation.transactionSha256) {
          throw new TokenLaunchLedgerError('launch_conflict', 409, 'This launch operation conflicts with an existing operation.');
        }
        if (existing.state !== 'prepared') throw new TokenLaunchLedgerError('launch_state_invalid', 422,
          'This launch operation was already submitted or closed.');
        return false;
      }
      const prior = rows.find(row => row.subject === principal.subject && row.request.walletId === request.walletId && unresolved(row));
      if (prior) throw new TokenLaunchLedgerError('unresolved_launch', 409,
        'This wallet already has an unresolved token launch. Resume or release it first.');
      const updatedAt = new Date(this.now()).toISOString();
      rows.push({ subject: principal.subject, request, preparation, state: 'prepared', transactionId: null,
        signedTransactionBase64: null, submittedAt: null, updatedAt, finalized: false, message: null });
      return true;
    });
  }

  commit(principal: PrivyPrincipal, identity: TokenLaunchIdentity): TokenLaunchMutationResponse {
    let idempotent = false;
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, identity);
      if (current.state === 'committed') { idempotent = true; return false; }
      if (current.state !== 'prepared') throw new TokenLaunchLedgerError('launch_state_invalid', 422,
        'This launch can no longer be committed.');
      if (Date.parse(current.preparation.expiresAt) <= this.now()) throw new TokenLaunchLedgerError('launch_state_invalid', 422,
        'The prepared launch expired. Prepare it again.');
      current.state = 'committed'; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: identity.operationId, state: 'committed', idempotent };
  }

  release(principal: PrivyPrincipal, identity: TokenLaunchIdentity): TokenLaunchMutationResponse {
    let idempotent = false;
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, identity);
      if (current.state === 'released') { idempotent = true; return false; }
      if (!['prepared', 'committed'].includes(current.state) || current.transactionId !== null) {
        throw new TokenLaunchLedgerError('launch_state_invalid', 422,
          'A broadcast or unresolved launch cannot be released. Check its status.');
      }
      current.state = 'released'; current.finalized = true; current.message = 'Launch was cancelled before broadcast.';
      current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return { schemaVersion: 1, operationId: identity.operationId, state: 'released', idempotent };
  }

  requireForSubmission(principal: PrivyPrincipal, identity: TokenLaunchIdentity): StoredTokenLaunch {
    this.requireHealthy();
    const current = this.requireExact(this.data.launches, principal, identity);
    if (!['committed', 'broadcast', 'unknown'].includes(current.state)) throw new TokenLaunchLedgerError('launch_state_invalid', 422,
      'This launch was not committed for signing.');
    return structuredClone(current);
  }

  bindBroadcast(principal: PrivyPrincipal, identity: TokenLaunchIdentity, transactionId: string,
    signedTransactionBase64: string): { idempotent: boolean; submittedAt: string } {
    let idempotent = false; let submittedAt = new Date(this.now()).toISOString();
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, identity);
      if (current.transactionId !== null) {
        if (current.transactionId !== transactionId || current.signedTransactionBase64 !== signedTransactionBase64) {
          throw new TokenLaunchLedgerError('launch_conflict', 409, 'This launch is already bound to another transaction.');
        }
        idempotent = true; submittedAt = current.submittedAt ?? submittedAt; return false;
      }
      if (current.state !== 'committed') throw new TokenLaunchLedgerError('launch_state_invalid', 422,
        'This launch was not committed for submission.');
      current.transactionId = transactionId; current.signedTransactionBase64 = signedTransactionBase64;
      current.submittedAt = submittedAt; current.state = 'unknown'; current.message = 'Broadcast outcome is being checked.';
      current.updatedAt = submittedAt; return true;
    });
    return { idempotent, submittedAt };
  }

  recordBroadcast(principal: PrivyPrincipal, identity: TokenLaunchIdentity, succeeded: boolean): void {
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, identity);
      if (!current.transactionId || current.finalized) return false;
      current.state = succeeded ? 'broadcast' : 'unknown';
      current.message = succeeded ? 'Transaction submitted to Solana mainnet.' : 'Broadcast result is uncertain; status checks are safe.';
      current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
  }

  current(principal: PrivyPrincipal, identity: TokenLaunchIdentity): StoredTokenLaunch {
    this.requireHealthy(); return structuredClone(this.requireExact(this.data.launches, principal, identity));
  }

  recordObservation(principal: PrivyPrincipal, identity: TokenLaunchIdentity, observation: TokenLaunchStatus): void {
    this.mutate(rows => {
      const current = this.requireExact(rows, principal, identity);
      if (current.transactionId !== observation.transactionId && observation.transactionId !== null) {
        throw new TokenLaunchLedgerError('launch_conflict', 409, 'Transaction identifier does not match this launch.');
      }
      current.state = observation.state; current.finalized = observation.isFinalized;
      current.message = observation.message; current.updatedAt = observation.observedAt; return true;
    });
  }

  snapshot(): readonly StoredTokenLaunch[] { return structuredClone(this.data.launches); }

  private requireExact(rows: StoredTokenLaunch[], principal: PrivyPrincipal, identity: TokenLaunchIdentity): StoredTokenLaunch {
    const current = rows.find(row => row.request.operationId === identity.operationId);
    if (!current || current.subject !== principal.subject) throw new TokenLaunchLedgerError('launch_not_found', 422,
      'The reviewed token launch was not found for this user.');
    if (current.request.walletId !== identity.walletId || current.request.walletAddress !== identity.walletAddress ||
      current.preparation.transactionSha256 !== identity.transactionSha256) {
      throw new TokenLaunchLedgerError('launch_conflict', 409, 'Launch details no longer match the reviewed intent.');
    }
    return current;
  }

  private requireHealthy(): void {
    if (!this.healthy) throw new TokenLaunchLedgerError('launch_storage_unavailable', 503,
      'Token launch safety storage is unavailable. Nothing was submitted.');
  }
  private mutate(change: (rows: StoredTokenLaunch[]) => boolean): void {
    this.requireHealthy(); const next = structuredClone(this.data);
    const before = next.launches.length;
    next.launches = next.launches.filter(row => !(row.state === 'prepared' && Date.parse(row.preparation.expiresAt) <= this.now()));
    const changed = change(next.launches); if (!changed && next.launches.length === before) return;
    const cutoff = this.now() - 30 * 24 * 60 * 60_000;
    next.launches = next.launches.filter(row => !(row.finalized && Date.parse(row.updatedAt) < cutoff));
    if (next.launches.length > 2_000 || Buffer.byteLength(JSON.stringify(next)) > 32 * 1_024 * 1_024) {
      throw new TokenLaunchLedgerError('launch_storage_unavailable', 503, 'Token launch safety storage is full. Nothing was submitted.');
    }
    if (this.filename !== null) {
      try { this.atomicWrite(next); }
      catch {
        this.healthy = false; throw new TokenLaunchLedgerError('launch_storage_unavailable', 503,
          'Token launch safety storage could not be committed. Nothing was submitted.');
      }
    }
    this.data = next;
  }
  private atomicWrite(value: LedgerFile): void {
    if (this.filename === null) throw new Error('No token launch ledger path');
    const directory = dirname(this.filename); mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
    const temporary = `${this.filename}.tmp-${process.pid}-${this.now()}`; let descriptor: number | null = null;
    try {
      descriptor = openSync(temporary, 'wx', 0o600); writeFileSync(descriptor, `${JSON.stringify(value)}\n`, 'utf8');
      fsyncSync(descriptor); closeSync(descriptor); descriptor = null; renameSync(temporary, this.filename); chmodSync(this.filename, 0o600);
      const directoryFd = openSync(directory, 'r'); try { fsyncSync(directoryFd); } finally { closeSync(directoryFd); }
    } catch (error) { if (descriptor !== null) closeSync(descriptor); rmSync(temporary, { force: true }); throw error; }
  }
}

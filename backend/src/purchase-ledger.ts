import { chmodSync, closeSync, existsSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';
import { PurchaseQuoteResponseSchema, PurchaseStatusSchema, type PurchaseQuoteResponse, type PurchaseStatus } from './purchase-schema.js';
import { decodeSignature } from './transfer-status.js';

const RouteMetaSchema = z.object({ routeId: z.string().min(1).max(200), tool: z.string().min(1).max(100),
  fromChainId: z.string().regex(/^\d+$/), toChainId: z.string().regex(/^\d+$/),
  destinationRecipientAddress: z.string().min(32).max(64).optional() }).strict();
export type PurchaseRouteMeta = z.infer<typeof RouteMetaSchema>;
const StoredPurchaseSchema = z.object({
  operationId: z.uuid(), quoteId: z.uuid(), subject: z.string().min(1).max(200), fingerprint: z.string().regex(/^[0-9a-f]{64}$/),
  // Old journals did not record direction. Keep it unknown rather than mislabel a sale.
  side: z.enum(['BUY', 'SELL']).nullable().default(null),
  quote: PurchaseQuoteResponseSchema, route: RouteMetaSchema,
  state: z.enum(['QUOTED', 'COMMITTED', 'EXECUTING', 'COMPLETED', 'FAILED', 'EXPIRED']),
  invoking: z.object({ actionId: z.uuid(), sessionId: z.string().min(1).max(200), invokingAt: z.iso.datetime() }).strict().nullable(),
  submitted: z.array(z.object({ actionId: z.uuid(), transactionId: z.string().min(32).max(128),
    submittedAt: z.iso.datetime(), confirmedAt: z.iso.datetime().nullable() }).strict()).max(4),
  solanaTransactionSignature: z.string().min(80).max(88).refine(value => {
    try { decodeSignature(value); return true; } catch { return false; }
  }).nullable().default(null),
  receivedAmount: z.string().regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/).nullable(), message: z.string().min(1).max(240).nullable(),
  createdAt: z.iso.datetime(), updatedAt: z.iso.datetime(),
}).strict();
export type StoredPurchase = z.infer<typeof StoredPurchaseSchema>;
const LedgerFileSchema = z.object({ schemaVersion: z.literal(1), purchases: z.array(StoredPurchaseSchema).max(2_000) }).strict();
type LedgerFile = z.infer<typeof LedgerFileSchema>;
export const MAX_ACTIVE_PURCHASES_PER_SUBJECT = 16;

export class PurchaseLedgerError extends Error {
  constructor(readonly code: 'quote_conflict' | 'quote_capacity' | 'quote_not_found' | 'quote_expired' | 'quote_state_invalid' | 'action_out_of_order' | 'purchase_storage_unavailable',
    readonly statusCode: 409 | 422 | 429 | 503, message: string) { super(message); }
}

/** Durable, single-process quote journal. No access token, private key, or signature is persisted. */
export class PurchaseLedger {
  private data: LedgerFile = { schemaVersion: 1, purchases: [] };
  private healthy = true;
  constructor(private readonly filename: string | null = resolve('runtime/private-purchase-intents.json'), private readonly now = Date.now) {
    if (filename === null) return;
    try {
      if (existsSync(filename)) {
        const raw = readFileSync(filename, 'utf8');
        if (Buffer.byteLength(raw) > 64 * 1_024 * 1_024) throw new Error('Purchase ledger too large');
        this.data = LedgerFileSchema.parse(JSON.parse(raw)); chmodSync(filename, 0o600);
        const before = this.data.purchases.length;
        this.data.purchases = this.data.purchases.filter(value => !this.isExpiredUnstartedQuote(value));
        if (this.data.purchases.length !== before) this.atomicWrite(this.data);
      }
    } catch { this.healthy = false; }
  }

  findOperation(principal: PrivyPrincipal, operationId: string): StoredPurchase | null {
    this.requireHealthy();
    const value = this.data.purchases.find(row => row.subject === principal.subject && row.operationId === operationId);
    return value ? structuredClone(value) : null;
  }

  findByQuote(principal: PrivyPrincipal, quoteId: string): StoredPurchase {
    this.requireHealthy();
    const value = this.data.purchases.find(row => row.subject === principal.subject && row.quoteId === quoteId);
    if (!value) throw new PurchaseLedgerError('quote_not_found', 422, 'The reviewed purchase quote was not found for this user.');
    return structuredClone(value);
  }

  register(principal: PrivyPrincipal, operationId: string, fingerprint: string, quote: PurchaseQuoteResponse, route: PurchaseRouteMeta,
    side: 'BUY' | 'SELL' = 'BUY'): StoredPurchase {
    let created!: StoredPurchase;
    this.mutate(rows => {
      const existing = rows.find(row => row.subject === principal.subject && row.operationId === operationId);
      if (existing) {
        if (existing.fingerprint !== fingerprint) throw new PurchaseLedgerError('quote_conflict', 409,
          'This purchase operation was already used with different details.');
        created = existing; return false;
      }
      const activeForSubject = rows.filter(row => row.subject === principal.subject &&
        ['QUOTED', 'COMMITTED', 'EXECUTING'].includes(row.state)).length;
      if (activeForSubject >= MAX_ACTIVE_PURCHASES_PER_SUBJECT) throw new PurchaseLedgerError('quote_capacity', 429,
        'Too many purchase reviews are active for this account. Wait for an earlier quote to expire.');
      const now = new Date(this.now()).toISOString();
      created = StoredPurchaseSchema.parse({ operationId, quoteId: quote.quoteId, subject: principal.subject, fingerprint, side,
        quote, route, state: 'QUOTED', invoking: null, submitted: [], solanaTransactionSignature: null,
        receivedAmount: null, message: null, createdAt: now, updatedAt: now });
      rows.push(created); return true;
    });
    return structuredClone(created);
  }

  commit(principal: PrivyPrincipal, quoteId: string): { idempotent: boolean; purchase: StoredPurchase } {
    let idempotent = false; let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId);
      if (current.state === 'COMMITTED') { idempotent = true; purchase = current; return false; }
      if (current.state !== 'QUOTED') throw new PurchaseLedgerError('quote_state_invalid', 422, 'This purchase quote can no longer be committed.');
      if (Date.parse(current.quote.expiresAt) <= this.now()) {
        current.state = 'EXPIRED'; current.updatedAt = new Date(this.now()).toISOString(); purchase = current;
        return true;
      }
      // A reviewed quote that was committed but never reserved or broadcast can be
      // released safely when its signed quote window expires. Reservations and
      // submitted hashes deliberately never auto-clear: those need exact
      // reconciliation because a wallet provider might already have been called.
      const now = new Date(this.now()).toISOString();
      for (const value of rows) {
        if (value.quoteId !== current.quoteId && value.subject === principal.subject && value.state === 'COMMITTED' &&
            value.invoking === null && value.submitted.length === 0 && Date.parse(value.quote.expiresAt) <= this.now()) {
          value.state = 'EXPIRED'; value.updatedAt = now;
        }
      }
      const identity = this.sourceIdentity(current);
      const prior = rows.find(value => value.quoteId !== current.quoteId && value.subject === principal.subject &&
        ['COMMITTED', 'EXECUTING'].includes(value.state) && this.sourceIdentity(value) === identity);
      if (prior) throw new PurchaseLedgerError('quote_conflict', 409,
        'This source wallet already has an unresolved purchase. Track it before starting another.');
      current.state = 'COMMITTED'; current.updatedAt = new Date(this.now()).toISOString(); purchase = current; return true;
    });
    if (purchase.state === 'EXPIRED') throw new PurchaseLedgerError('quote_expired', 422, 'The purchase quote expired. Request a fresh quote.');
    return { idempotent, purchase: structuredClone(purchase) };
  }

  submit(principal: PrivyPrincipal, quoteId: string, actionId: string, transactionId: string): StoredPurchase {
    let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId);
      const action = current.quote.actions.find(value => value.id === actionId);
      if (!action) throw new PurchaseLedgerError('quote_state_invalid', 422, 'The submitted action is not part of this quote.');
      const expectedEvm = action.network !== 'SOLANA';
      if (expectedEvm !== /^0x[0-9a-fA-F]{64}$/.test(transactionId)) throw new PurchaseLedgerError('quote_state_invalid', 422,
        'The transaction identifier does not match the action network.');
      const prior = current.submitted.find(value => value.actionId === actionId);
      if (prior) {
        if (prior.transactionId !== transactionId) throw new PurchaseLedgerError('quote_conflict', 409,
          'This action is already bound to a different transaction.');
        purchase = current; return false;
      }
      if (current.invoking?.actionId !== actionId || current.invoking.sessionId !== principal.sessionId) {
        throw new PurchaseLedgerError('quote_state_invalid', 422,
          'The exact purchase action must be reserved by this wallet session before transaction submission.');
      }
      if (!['COMMITTED', 'EXECUTING'].includes(current.state)) throw new PurchaseLedgerError('quote_state_invalid', 422,
        'Commit the reviewed purchase before submitting a transaction.');
      if (current.submitted.length === 0 && Date.parse(current.quote.expiresAt) <= this.now()) {
        current.state = 'EXPIRED'; current.updatedAt = new Date(this.now()).toISOString(); purchase = current; return true;
      }
      const expected = current.quote.actions[current.submitted.length];
      if (!expected || expected.id !== actionId) throw new PurchaseLedgerError('action_out_of_order', 409,
        'Purchase actions must be submitted in the reviewed order.');
      if (current.submitted.some(value => value.confirmedAt === null)) throw new PurchaseLedgerError('action_out_of_order', 409,
        'The previous purchase action must be confirmed before the next action is submitted.');
      if (current.submitted.some(value => value.transactionId === transactionId)) throw new PurchaseLedgerError('quote_conflict', 409,
        'The transaction identifier is already bound to another action.');
      current.submitted.push({ actionId, transactionId, submittedAt: new Date(this.now()).toISOString(), confirmedAt: null });
      current.invoking = null;
      current.state = 'EXECUTING'; current.updatedAt = new Date(this.now()).toISOString(); purchase = current; return true;
    });
    if (purchase.state === 'EXPIRED') throw new PurchaseLedgerError('quote_expired', 422, 'The purchase quote expired before submission.');
    return structuredClone(purchase);
  }

  get(principal: PrivyPrincipal, quoteId: string): StoredPurchase {
    let value!: StoredPurchase;
    this.mutate(rows => {
      value = this.requirePurchase(rows, principal, quoteId);
      if (['QUOTED', 'COMMITTED'].includes(value.state) && value.invoking === null && value.submitted.length === 0 && Date.parse(value.quote.expiresAt) <= this.now()) {
        value.state = 'EXPIRED'; value.updatedAt = new Date(this.now()).toISOString(); return true;
      }
      return false;
    });
    return structuredClone(value);
  }

  recordRouterStatus(principal: PrivyPrincipal, quoteId: string,
    observation: { state: 'PENDING' | 'DONE' | 'FAILED'; receivedAmount: string | null; message: string | null;
      destinationTransactionId?: string | null }): StoredPurchase {
    let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId); purchase = current;
      if (['COMPLETED', 'FAILED', 'EXPIRED'].includes(current.state)) return false;
      if (current.submitted.length !== current.quote.actions.length) return false;
      const next = observation.state === 'DONE' ? 'COMPLETED' : observation.state === 'FAILED' ? 'FAILED' : 'EXECUTING';
      const signature = next === 'COMPLETED' && current.quote.destinationId.startsWith('SOLANA:')
        ? observation.destinationTransactionId ?? (current.quote.actions.at(-1)?.type === 'SOLANA_ROUTE'
          ? current.submitted.at(-1)?.transactionId ?? null : null)
        : null;
      if (signature !== null) decodeSignature(signature);
      if (current.state === next && current.receivedAmount === observation.receivedAmount &&
          current.message === observation.message && current.solanaTransactionSignature === signature) return false;
      current.state = next; current.receivedAmount = observation.receivedAmount; current.message = observation.message;
      current.solanaTransactionSignature = signature;
      if (next === 'COMPLETED' && current.submitted.at(-1)) {
        current.submitted.at(-1)!.confirmedAt = new Date(this.now()).toISOString();
      }
      current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return structuredClone(purchase);
  }

  response(value: StoredPurchase): PurchaseStatus {
    const state: PurchaseStatus['state'] = value.state === 'COMPLETED' ? 'COMPLETED' : value.state === 'FAILED' ? 'FAILED' :
      value.state === 'EXPIRED' ? 'EXPIRED' : value.submitted.length ? 'EXECUTING' : 'COMMITTED';
    return PurchaseStatusSchema.parse({ schemaVersion: 1, quoteId: value.quoteId, state,
      step: value.submitted.filter(item => item.confirmedAt !== null).length, stepCount: value.quote.actions.length,
      transactionIds: value.submitted.map(item => item.transactionId), receivedAmount: value.receivedAmount,
      solanaTransactionSignature: value.state === 'COMPLETED' ? value.solanaTransactionSignature : null,
      message: value.message, updatedAt: value.updatedAt });
  }

  snapshot(): readonly StoredPurchase[] { this.requireHealthy(); return structuredClone(this.data.purchases); }

  invoking(principal: PrivyPrincipal, quoteId: string, actionId: string): { idempotent: boolean; purchase: StoredPurchase } {
    let idempotent = false; let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId); purchase = current;
      if (current.invoking !== null) {
        if (current.invoking.actionId === actionId && current.invoking.sessionId === principal.sessionId) {
          idempotent = true; return false;
        }
        throw new PurchaseLedgerError('quote_conflict', 409, 'This purchase already has an action reserved by another wallet session.');
      }
      if (!['COMMITTED', 'EXECUTING'].includes(current.state)) throw new PurchaseLedgerError('quote_state_invalid', 422,
        'Commit the reviewed purchase before invoking the wallet provider.');
      if (current.submitted.length === 0 && Date.parse(current.quote.expiresAt) <= this.now()) {
        current.state = 'EXPIRED'; current.updatedAt = new Date(this.now()).toISOString(); return true;
      }
      if (current.submitted.some(value => value.confirmedAt === null)) throw new PurchaseLedgerError('action_out_of_order', 409,
        'The previous purchase action must be confirmed before invoking the next action.');
      const expected = current.quote.actions[current.submitted.length];
      if (!expected || expected.id !== actionId) throw new PurchaseLedgerError('action_out_of_order', 409,
        'Purchase actions must be invoked in the reviewed order.');
      current.invoking = { actionId, sessionId: principal.sessionId, invokingAt: new Date(this.now()).toISOString() };
      current.updatedAt = current.invoking.invokingAt; return true;
    });
    if (purchase.state === 'EXPIRED') throw new PurchaseLedgerError('quote_expired', 422, 'The purchase quote expired before wallet invocation.');
    return { idempotent, purchase: structuredClone(purchase) };
  }

  releaseInvocation(principal: PrivyPrincipal, quoteId: string, actionId: string): StoredPurchase {
    let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId); purchase = current;
      if (current.invoking === null) return false;
      if (current.invoking.actionId !== actionId || current.invoking.sessionId !== principal.sessionId) {
        throw new PurchaseLedgerError('quote_conflict', 409, 'Only the wallet session that reserved this action can release it.');
      }
      current.invoking = null; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return structuredClone(purchase);
  }

  confirmAction(principal: PrivyPrincipal, quoteId: string, actionId: string): StoredPurchase {
    let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId); purchase = current;
      const submitted = current.submitted.find(value => value.actionId === actionId);
      if (!submitted) throw new PurchaseLedgerError('quote_state_invalid', 422, 'The purchase action has not been submitted.');
      if (submitted.confirmedAt !== null) return false;
      const preceding = current.quote.actions.slice(0, current.quote.actions.findIndex(value => value.id === actionId));
      if (!preceding.every(action => current.submitted.find(value => value.actionId === action.id)?.confirmedAt)) {
        throw new PurchaseLedgerError('action_out_of_order', 409, 'Earlier purchase actions are not confirmed.');
      }
      submitted.confirmedAt = new Date(this.now()).toISOString(); current.updatedAt = submitted.confirmedAt; return true;
    });
    return structuredClone(purchase);
  }

  fail(principal: PrivyPrincipal, quoteId: string, message: string): StoredPurchase {
    let purchase!: StoredPurchase;
    this.mutate(rows => {
      const current = this.requirePurchase(rows, principal, quoteId); purchase = current;
      if (['COMPLETED', 'FAILED', 'EXPIRED'].includes(current.state)) return false;
      current.state = 'FAILED'; current.message = message; current.updatedAt = new Date(this.now()).toISOString(); return true;
    });
    return structuredClone(purchase);
  }

  private requirePurchase(rows: StoredPurchase[], principal: PrivyPrincipal, quoteId: string): StoredPurchase {
    const current = rows.find(row => row.quoteId === quoteId && row.subject === principal.subject);
    if (!current) throw new PurchaseLedgerError('quote_not_found', 422, 'The reviewed purchase quote was not found for this user.');
    return current;
  }
  private sourceIdentity(value: StoredPurchase): string {
    const source = value.quote.actions[0];
    if (!source) throw new PurchaseLedgerError('quote_state_invalid', 422, 'Purchase quote has no source action.');
    return `${source.network}:${source.walletAddress.toLowerCase()}`;
  }
  private requireHealthy(): void {
    if (!this.healthy) throw new PurchaseLedgerError('purchase_storage_unavailable', 503,
      'Purchase safety storage is unavailable. Nothing was submitted.');
  }
  private isExpiredUnstartedQuote(value: StoredPurchase): boolean {
    return value.state === 'QUOTED' && value.invoking === null && value.submitted.length === 0 &&
      Date.parse(value.quote.expiresAt) <= this.now();
  }
  private mutate(change: (purchases: StoredPurchase[]) => boolean): void {
    this.requireHealthy(); const next = structuredClone(this.data);
    const before = next.purchases.length;
    next.purchases = next.purchases.filter(value => !this.isExpiredUnstartedQuote(value));
    const changed = change(next.purchases); if (!changed && next.purchases.length === before) return;
    const cutoff = this.now() - 30 * 24 * 60 * 60_000;
    next.purchases = next.purchases.filter(value => !(['COMPLETED', 'FAILED', 'EXPIRED'].includes(value.state) && Date.parse(value.updatedAt) < cutoff));
    if (next.purchases.length > 2_000 || Buffer.byteLength(JSON.stringify(next)) > 64 * 1_024 * 1_024) {
      throw new PurchaseLedgerError('purchase_storage_unavailable', 503, 'Purchase safety storage is full. Nothing was submitted.');
    }
    if (this.filename !== null) {
      try { this.atomicWrite(next); }
      catch {
        this.healthy = false; throw new PurchaseLedgerError('purchase_storage_unavailable', 503,
          'Purchase safety storage could not be committed. Nothing was submitted.');
      }
    }
    this.data = next;
  }
  private atomicWrite(value: LedgerFile): void {
    if (this.filename === null) throw new Error('No purchase ledger path');
    const directory = dirname(this.filename); mkdirSync(directory, { recursive: true, mode: 0o700 }); chmodSync(directory, 0o700);
    const temporary = `${this.filename}.tmp-${process.pid}-${this.now()}`; let descriptor: number | null = null;
    try {
      descriptor = openSync(temporary, 'wx', 0o600); writeFileSync(descriptor, `${JSON.stringify(value)}\n`, 'utf8');
      fsyncSync(descriptor); closeSync(descriptor); descriptor = null; renameSync(temporary, this.filename); chmodSync(this.filename, 0o600);
      const directoryFd = openSync(directory, 'r'); try { fsyncSync(directoryFd); } finally { closeSync(directoryFd); }
    } catch (error) { if (descriptor !== null) closeSync(descriptor); rmSync(temporary, { force: true }); throw error; }
  }
}

import type { PrivyPrincipal } from './privy-auth.js';
import type { CommunityBuyChain } from './community-buy-chain.js';
import { CommunityBuyLedger, CommunityBuyLedgerError, type StoredCommunityBuy } from './community-buy-ledger.js';
import { CommunityBuyPreparationSchema, type CommunityBuyIdentity, type CommunityBuyMutationResponse, type CommunityBuyPreparation,
  CommunityBuyPrepareRequest, CommunityBuyReleaseRequest, CommunityBuyStatus, CommunityBuySubmitRequest,
  CommunityBuySubmitResponse } from './community-buy-schema.js';

export interface CommunityBuyApi {
  prepare(principal: PrivyPrincipal, request: CommunityBuyPrepareRequest): Promise<CommunityBuyPreparation>;
  commit(principal: PrivyPrincipal, request: CommunityBuyIdentity): CommunityBuyMutationResponse;
  release(principal: PrivyPrincipal, request: CommunityBuyReleaseRequest): CommunityBuyMutationResponse;
  submit(principal: PrivyPrincipal, request: CommunityBuySubmitRequest): Promise<CommunityBuySubmitResponse>;
  status(principal: PrivyPrincipal, request: CommunityBuyIdentity): Promise<CommunityBuyStatus>;
}

/** Privy-authenticated, one-shot Solana mainnet buys. Stock purchases remain a separate flow. */
export class CommunityBuyService implements CommunityBuyApi {
  private readonly preparing = new Map<string, Promise<CommunityBuyPreparation>>();
  private chainPromise?: Promise<CommunityBuyChain>;
  constructor(private readonly chain?: CommunityBuyChain,
    private readonly ledger: CommunityBuyLedger = new CommunityBuyLedger(), private readonly now = Date.now) {}

  private chainForRequest(): Promise<CommunityBuyChain> {
    if (this.chain) return Promise.resolve(this.chain);
    return this.chainPromise ??= import('./community-buy-chain.js').then(({ MeteoraCommunityBuyChain }) =>
      new MeteoraCommunityBuyChain());
  }

  async prepare(principal: PrivyPrincipal, request: CommunityBuyPrepareRequest): Promise<CommunityBuyPreparation> {
    const existing = this.ledger.findPreparation(principal, request);
    if (existing) return existing;
    const key = `${principal.subject}\u0000${request.walletId}`;
    const pending = this.preparing.get(key);
    if (pending) {
      await pending;
      const prepared = this.ledger.findPreparation(principal, request);
      if (!prepared) throw new Error('Prepared buy was not persisted');
      return prepared;
    }
    const task = (async () => {
      const chain = await this.chainForRequest();
      const prepared = CommunityBuyPreparationSchema.parse(await chain.prepare(request));
      if (prepared.operationId !== request.operationId || prepared.walletId !== request.walletId ||
        prepared.walletAddress !== request.walletAddress || prepared.mintAddress !== request.mintAddress ||
        prepared.amountInLamports !== request.amountInLamports || prepared.slippageBps !== request.slippageBps) {
        throw new Error('Prepared buy does not match the authenticated request');
      }
      this.ledger.registerPrepared(principal, request, prepared);
      return prepared;
    })();
    this.preparing.set(key, task);
    try { return await task; } finally { if (this.preparing.get(key) === task) this.preparing.delete(key); }
  }

  commit(principal: PrivyPrincipal, request: CommunityBuyIdentity): CommunityBuyMutationResponse {
    return this.ledger.commit(principal, request);
  }
  release(principal: PrivyPrincipal, request: CommunityBuyReleaseRequest): CommunityBuyMutationResponse {
    return this.ledger.release(principal, request);
  }

  async submit(principal: PrivyPrincipal, request: CommunityBuySubmitRequest): Promise<CommunityBuySubmitResponse> {
    const current = this.ledger.requireForSubmission(principal, request);
    const chain = await this.chainForRequest();
    const signed = chain.assembleSigned(current.preparation, request.walletSignedTransactionBase64);
    if (current.transactionId !== null) {
      const binding = this.ledger.bindBroadcast(principal, request, signed.transactionId, signed.base64);
      return { schemaVersion: 1, operationId: request.operationId,
        state: current.state === 'broadcast' ? 'broadcast' : 'unknown', transactionId: signed.transactionId,
        idempotent: binding.idempotent, submittedAt: binding.submittedAt };
    }
    await chain.preflight(current.preparation, signed);
    // Persist exact signed bytes and transaction ID before the first network write.
    const binding = this.ledger.bindBroadcast(principal, request, signed.transactionId, signed.base64);
    const broadcast = await chain.broadcast(signed);
    this.ledger.recordBroadcast(principal, request, broadcast);
    return { schemaVersion: 1, operationId: request.operationId, state: broadcast ? 'broadcast' : 'unknown',
      transactionId: signed.transactionId, idempotent: binding.idempotent, submittedAt: binding.submittedAt };
  }

  async status(principal: PrivyPrincipal, request: CommunityBuyIdentity): Promise<CommunityBuyStatus> {
    const current = this.ledger.current(principal, request);
    if (current.state === 'prepared' || current.state === 'released') {
      throw new CommunityBuyLedgerError('buy_state_invalid', 422, 'This buy was not committed for submission.');
    }
    if (current.finalized && ['failed', 'expired'].includes(current.state)) {
      return this.response(current, { state: current.state as 'failed' | 'expired', isFinalized: true,
        message: current.message ?? 'This buy is closed.', tokenAmountReceived: null, walletTokenBalanceRaw: null });
    }
    const observed = await (await this.chainForRequest()).observe(current);
    // A transient RPC gap cannot erase a previously finalized purchase; without a fresh balance,
    // the app still must not show it as currently owned.
    const observation = current.finalized && current.state === 'confirmed' && observed.state !== 'confirmed'
      ? { state: 'confirmed' as const, isFinalized: true, message: 'Purchase confirmed; checking current wallet balance.',
          tokenAmountReceived: current.tokenAmountReceived, walletTokenBalanceRaw: null }
      : observed;
    const status = this.response(current, observation);
    this.ledger.recordObservation(principal, request, status);
    return status;
  }

  private response(current: StoredCommunityBuy, observation: Awaited<ReturnType<CommunityBuyChain['observe']>>): CommunityBuyStatus {
    return {
      schemaVersion: 1, operationId: current.request.operationId, network: 'SOLANA_MAINNET',
      state: observation.state, transactionId: current.transactionId, isFinalized: observation.isFinalized,
      message: observation.message, observedAt: new Date(this.now()).toISOString(),
      walletAddress: current.request.walletAddress, mintAddress: current.request.mintAddress,
      poolAddress: current.preparation.poolAddress, amountInLamports: current.request.amountInLamports,
      tokenAmountReceived: observation.tokenAmountReceived, walletTokenBalanceRaw: observation.walletTokenBalanceRaw,
      explorerUrl: `https://solscan.io/token/${current.request.mintAddress}`,
    };
  }
}

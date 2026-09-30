import type { PrivyPrincipal } from './privy-auth.js';
import type { TokenLaunchChain } from './token-launch-chain.js';
import { TokenLaunchLedger, TokenLaunchLedgerError, type StoredTokenLaunch } from './token-launch-ledger.js';
import { TokenLaunchPreparationSchema } from './token-launch-schema.js';
import type { TokenLaunchCommitRequest, TokenLaunchMutationResponse, TokenLaunchPreparation,
  TokenLaunchPrepareRequest, TokenLaunchReleaseRequest, TokenLaunchStatus, TokenLaunchStatusRequest,
  TokenLaunchSubmitRequest, TokenLaunchSubmitResponse } from './token-launch-schema.js';

export interface TokenLaunchApi {
  prepare(principal: PrivyPrincipal, request: TokenLaunchPrepareRequest): Promise<TokenLaunchPreparation>;
  commit(principal: PrivyPrincipal, request: TokenLaunchCommitRequest): TokenLaunchMutationResponse;
  release(principal: PrivyPrincipal, request: TokenLaunchReleaseRequest): TokenLaunchMutationResponse;
  submit(principal: PrivyPrincipal, request: TokenLaunchSubmitRequest): Promise<TokenLaunchSubmitResponse>;
  status(principal: PrivyPrincipal, request: TokenLaunchStatusRequest): Promise<TokenLaunchStatus>;
}

export class TokenLaunchService implements TokenLaunchApi {
  private readonly preparing = new Map<string, Promise<TokenLaunchPreparation>>();
  private chainPromise?: Promise<TokenLaunchChain>;
  constructor(private readonly chain?: TokenLaunchChain,
    private readonly ledger: TokenLaunchLedger = new TokenLaunchLedger(), private readonly now = Date.now) {}

  private chainForRequest(): Promise<TokenLaunchChain> {
    if (this.chain) return Promise.resolve(this.chain);
    return this.chainPromise ??= import('./token-launch-chain.js').then(({ MeteoraTokenLaunchChain }) =>
      new MeteoraTokenLaunchChain());
  }

  async prepare(principal: PrivyPrincipal, request: TokenLaunchPrepareRequest): Promise<TokenLaunchPreparation> {
    const existing = this.ledger.findPreparation(principal, request);
    if (existing) return existing;
    const key = `${principal.subject}\u0000${request.walletId}`;
    const pending = this.preparing.get(key);
    if (pending) {
      await pending;
      const prepared = this.ledger.findPreparation(principal, request);
      if (!prepared) throw new Error('Prepared launch was not persisted');
      return prepared;
    }
    const task = (async () => {
      const chain = await this.chainForRequest();
      const prepared = TokenLaunchPreparationSchema.parse(await chain.prepare(request));
      if (prepared.operationId !== request.operationId || prepared.network !== request.network ||
        prepared.walletId !== request.walletId || prepared.walletAddress !== request.walletAddress ||
        prepared.name !== request.name || prepared.symbol !== request.symbol ||
        prepared.metadataUri !== request.metadataUri || prepared.totalTokenSupply !== request.totalTokenSupply ||
        prepared.feeBps !== request.feeBps || prepared.migrationThresholdSol !== request.migrationThresholdSol ||
        prepared.percentageSupplyOnMigration !== request.percentageSupplyOnMigration) {
        throw new Error('Prepared launch does not match the authenticated request');
      }
      this.ledger.registerPrepared(principal, request, prepared);
      return prepared;
    })();
    this.preparing.set(key, task);
    try { return await task; } finally { if (this.preparing.get(key) === task) this.preparing.delete(key); }
  }

  commit(principal: PrivyPrincipal, request: TokenLaunchCommitRequest): TokenLaunchMutationResponse {
    return this.ledger.commit(principal, request);
  }
  release(principal: PrivyPrincipal, request: TokenLaunchReleaseRequest): TokenLaunchMutationResponse {
    return this.ledger.release(principal, request);
  }

  async submit(principal: PrivyPrincipal, request: TokenLaunchSubmitRequest): Promise<TokenLaunchSubmitResponse> {
    const current = this.ledger.requireForSubmission(principal, request);
    const chain = await this.chainForRequest();
    const signed = chain.assembleSigned(current.preparation, request.walletSignedTransactionBase64);
    const wasBound = current.transactionId !== null;
    if (wasBound) {
      const binding = this.ledger.bindBroadcast(principal, request, signed.transactionId, signed.base64);
      return { schemaVersion: 1, operationId: request.operationId,
        state: current.state === 'broadcast' ? 'broadcast' : 'unknown', transactionId: signed.transactionId,
        idempotent: binding.idempotent, submittedAt: binding.submittedAt };
    }
    await chain.preflight(current.preparation, signed);
    const binding = this.ledger.bindBroadcast(principal, request, signed.transactionId, signed.base64);
    const broadcast = await chain.broadcast(signed);
    this.ledger.recordBroadcast(principal, request, broadcast);
    return { schemaVersion: 1, operationId: request.operationId, state: broadcast ? 'broadcast' : 'unknown',
      transactionId: signed.transactionId, idempotent: binding.idempotent, submittedAt: binding.submittedAt };
  }

  async status(principal: PrivyPrincipal, request: TokenLaunchStatusRequest): Promise<TokenLaunchStatus> {
    const current = this.ledger.current(principal, request);
    if (current.state === 'prepared' || current.state === 'released') {
      throw new TokenLaunchLedgerError('launch_state_invalid', 422,
        'This launch was not committed for submission.');
    }
    if (current.finalized && ['confirmed', 'failed', 'expired'].includes(current.state)) return this.response(current);
    const observation = await (await this.chainForRequest()).observe(current);
    const status = this.response(current, observation);
    this.ledger.recordObservation(principal, request, status);
    return status;
  }

  private response(current: StoredTokenLaunch, observation?: Awaited<ReturnType<TokenLaunchChain['observe']>>): TokenLaunchStatus {
    const state = observation?.state ?? current.state;
    if (!['committed', 'broadcast', 'confirmed', 'failed', 'expired', 'unknown'].includes(state)) {
      throw new Error('Invalid token launch state');
    }
    return {
      schemaVersion: 1, operationId: current.request.operationId,
      state: state as TokenLaunchStatus['state'], transactionId: current.transactionId,
      isFinalized: observation?.isFinalized ?? current.finalized,
      message: observation?.message ?? current.message,
      observedAt: new Date(this.now()).toISOString(), mintAddress: current.preparation.mintAddress,
      configAddress: current.preparation.configAddress, poolAddress: current.preparation.poolAddress,
      explorerUrl: `https://solscan.io/token/${current.preparation.mintAddress}`,
    };
  }
}

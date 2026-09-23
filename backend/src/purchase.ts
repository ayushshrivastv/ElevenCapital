import { createHash, randomUUID } from 'node:crypto';
import { Decimal } from 'decimal.js';
import type { PrivyPrincipal } from './privy-auth.js';
import { destinationFrom, displayAmount, EVM_NATIVE, humanAmount, normalizeNetwork, paymentAssetDefinition,
  PurchaseRpcUnavailableError, walletForNetwork, type PaymentAssetDefinition, type PurchaseChainReader } from './purchase-chain.js';
import { LiFiError, type LiFiRouteQuote, type PurchaseRouter } from './lifi.js';
import { RELAY_ANTHROPIC_TOOL } from './relay.js';
import { PurchaseLedger, PurchaseLedgerError } from './purchase-ledger.js';
import { completedPurchaseActivity, type PurchaseActivityResponse } from './purchase-activity.js';
import { PurchaseQuoteResponseSchema, type PurchaseAction, type PurchaseCommitResponse, type PurchaseDestination,
  type PurchaseInvokingResponse,
  type PurchaseNetwork, type PurchaseOptionsRequest, type PurchaseOptionsResponse, type PurchaseQuoteRequest, type PurchaseQuoteResponse,
  type PurchaseStatus, type PurchaseSubmittedRequest } from './purchase-schema.js';
import type { Catalog, Stock } from './schema.js';

export class PurchaseError extends Error {
  constructor(readonly code: 'stock_not_purchasable' | 'asset_unavailable' | 'insufficient_balance' | 'route_unavailable' |
    'router_unavailable' | 'unsafe_router_response' | 'invalid_purchase', readonly statusCode: 409 | 422 | 503, message: string) { super(message); }
}

export interface PurchaseApi {
  options(principal: PrivyPrincipal, request: PurchaseOptionsRequest): Promise<PurchaseOptionsResponse>;
  quote(principal: PrivyPrincipal, request: PurchaseQuoteRequest): Promise<PurchaseQuoteResponse>;
  commit(principal: PrivyPrincipal, quoteId: string): PurchaseCommitResponse;
  invoking(principal: PrivyPrincipal, quoteId: string, actionId: string): PurchaseInvokingResponse;
  releaseInvocation(principal: PrivyPrincipal, quoteId: string, actionId: string): PurchaseStatus;
  submitted(principal: PrivyPrincipal, quoteId: string, actionId: string, request: PurchaseSubmittedRequest): PurchaseStatus;
  status(principal: PrivyPrincipal, quoteId: string): Promise<PurchaseStatus>;
  activity(principal: PrivyPrincipal): PurchaseActivityResponse;
}

function fingerprint(request: PurchaseQuoteRequest): string {
  const canonical = { schemaVersion: request.schemaVersion, operationId: request.operationId, stockId: request.stockId,
    fromAssetId: request.fromAssetId, destinationId: request.destinationId, amountBaseUnits: request.amountBaseUnits,
    side: request.side ?? 'BUY', slippageBps: request.slippageBps, wallets: [...request.wallets].sort((a, b) => a.chain.localeCompare(b.chain)) };
  return createHash('sha256').update(JSON.stringify(canonical)).digest('hex');
}
function validDeployment(network: ReturnType<typeof normalizeNetwork>, address: string): boolean {
  return network === 'SOLANA' ? /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address) :
    network !== null && /^0x[0-9a-fA-F]{40}$/.test(address);
}
function approvalData(spender: string, amount: string): string {
  const word = (value: string) => value.toLowerCase().replace(/^0x/, '').padStart(64, '0');
  const quantity = BigInt(amount).toString(16).padStart(64, '0');
  return `0x095ea7b3${word(spender)}${quantity}`;
}
function executableUnitPrice(route: LiFiRouteQuote, outputAmount: string): string | null {
  if (route.toAmountUsd === null) return null;
  const output = new Decimal(outputAmount); const usd = new Decimal(route.toAmountUsd);
  if (!output.isFinite() || output.lte(0) || !usd.isFinite() || usd.isNegative()) return null;
  return usd.div(output).toFixed();
}
function routeScore(route: LiFiRouteQuote, destination: PurchaseDestination): Decimal {
  if (route.toAmountUsd !== null) {
    const usd = new Decimal(route.toAmountUsd); if (usd.isFinite() && usd.isPositive()) return usd;
  }
  return new Decimal(displayAmount(route.toAmount, destination.decimals, destination.uiMultiplier));
}

export class PurchaseService implements PurchaseApi {
  constructor(private readonly catalog: () => Promise<Catalog>, private readonly chains: PurchaseChainReader,
    private readonly router: PurchaseRouter, private readonly ledger = new PurchaseLedger(), private readonly now = Date.now,
    private readonly executionEnabled = false) {}

  activity(principal: PrivyPrincipal): PurchaseActivityResponse {
    return completedPurchaseActivity(principal, this.ledger.snapshot(), this.now());
  }

  async options(_principal: PrivyPrincipal, request: PurchaseOptionsRequest): Promise<PurchaseOptionsResponse> {
    const stock = await this.stock(request.stockId);
    const selling = request.side === 'SELL';
    const [paymentAssets, destinations] = await Promise.all([
      selling ? this.stockBalances(stock, request.wallets) : this.chains.paymentAssets(request.wallets),
      selling ? this.sellDestinations(request.wallets) : this.destinations(stock,
        candidate => walletForNetwork(request.wallets, candidate.network) !== undefined),
    ]);
    const enabled = paymentAssets.filter(asset => asset.enabled);
    const funded = enabled.filter(asset => BigInt(asset.balanceBaseUnits) > 0n);
    const valued = funded.filter(asset => asset.usdValue !== null && new Decimal(asset.usdValue).gt(0))
      .sort((a, b) => {
        const byValue = new Decimal(b.usdValue!).comparedTo(new Decimal(a.usdValue!));
        return byValue !== 0 ? byValue : a.id.localeCompare(b.id);
      });
    const fundedUsdc = funded.filter(asset => asset.symbol === 'USDC').sort((a, b) => a.id.localeCompare(b.id));
    const fundedById = [...funded].sort((a, b) => a.id.localeCompare(b.id));
    const enabledById = [...enabled].sort((a, b) => a.id.localeCompare(b.id));
    const reason = destinations.length === 0 ? 'This listing has no verified token deployment on a supported wallet network. Exchange-only listings require an exchange account.' :
      selling && !funded.length ? `No ${stock.symbol} tokens are available to sell in this wallet.` :
      enabled.length === 0 ? 'Wallet balances are temporarily unavailable on every supported network.' : null;
    return { schemaVersion: 1, stockId: stock.id, purchasable: reason === null, reason, paymentAssets, destinations,
      executionEnabled: this.executionEnabled,
      executionReason: this.executionEnabled ? null : 'Trading is not enabled on this server.',
      defaultPaymentAssetId: valued[0]?.id ?? fundedUsdc[0]?.id ?? fundedById[0]?.id ?? enabledById[0]?.id ?? null,
      // Auto prefers an independently verified direct route, then compares preview alternatives.
      defaultDestinationId: null };
  }

  async quote(principal: PrivyPrincipal, request: PurchaseQuoteRequest): Promise<PurchaseQuoteResponse> {
    const requestFingerprint = fingerprint(request);
    const prior = this.ledger.findOperation(principal, request.operationId);
    if (prior) {
      if (prior.fingerprint !== requestFingerprint) throw new PurchaseLedgerError('quote_conflict', 409,
        'This purchase operation was already used with different details.');
      if (Date.parse(prior.quote.expiresAt) <= this.now()) throw new PurchaseLedgerError('quote_expired', 422,
        'The prior quote for this operation expired. Start a fresh purchase.');
      return prior.quote;
    }
    const stock = await this.stock(request.stockId);
    const source = request.side === 'SELL'
      ? (await this.stockSources(stock)).find(asset => asset.id === request.fromAssetId)
      : paymentAssetDefinition(request.fromAssetId);
    if (!source) throw new PurchaseError('asset_unavailable', 422, 'The selected payment asset is not supported.');
    const fromWallet = walletForNetwork(request.wallets, source.network);
    if (!fromWallet) throw new PurchaseError('asset_unavailable', 422, 'The selected payment network is not connected to this user.');
    const balance = await this.chains.balance(source, fromWallet).catch(() => { throw new PurchaseError('router_unavailable', 503,
      'The selected wallet balance could not be verified. Nothing was submitted.'); });
    if (BigInt(balance) < BigInt(request.amountBaseUnits)) throw new PurchaseError('insufficient_balance', 422,
      'The connected wallet does not hold enough of the selected payment asset.');
    let destinations = request.side === 'SELL' ? await this.sellDestinations(request.wallets) : await this.destinations(stock,
      candidate => walletForNetwork(request.wallets, candidate.network) !== undefined &&
        (request.destinationId === null || request.destinationId === candidate.id));
    if (request.destinationId !== null) destinations = destinations.filter(value => value.id === request.destinationId);
    destinations = destinations.filter(value => walletForNetwork(request.wallets, value.network));
    if (destinations.length === 0) throw new PurchaseError('stock_not_purchasable', 422,
      'The selected stock has no verified deployment for the connected destination wallet.');

    const quoteDestination = async (destination: PurchaseDestination) => {
      const toWallet = walletForNetwork(request.wallets, destination.network)!;
      const route = await this.router.quote({ source, destination, amountBaseUnits: request.amountBaseUnits,
        fromAddress: fromWallet.address, toAddress: toWallet.address, slippageBps: request.slippageBps });
      if (route.toDecimals !== destination.decimals) throw new LiFiError('unsafe_router_response',
        'The router token precision does not match the on-chain xStock contract.');
      return { destination, route };
    };
    // Do not make a working Solana route wait for unrelated cross-chain preview timeouts.
    const direct = source.network === 'SOLANA' ? destinations.filter(d => d.network === 'SOLANA') : [];
    const attempts = await Promise.allSettled(direct.map(quoteDestination));
    if (!attempts.some(result => result.status === 'fulfilled' && result.value.route.executionValidated === true)) {
      const remaining = destinations.filter(d => !direct.includes(d));
      attempts.push(...await Promise.allSettled(remaining.map(quoteDestination)));
    }
    const available = attempts.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);
    if (available.length === 0) {
      const unsafe = attempts.find(result => result.status === 'rejected' && result.reason instanceof LiFiError && result.reason.code === 'unsafe_router_response');
      const unavailable = attempts.find(result => result.status === 'rejected' && result.reason instanceof LiFiError && result.reason.code === 'router_unavailable');
      if (unsafe?.status === 'rejected') throw unsafe.reason;
      if (unavailable?.status === 'rejected') throw unavailable.reason;
      throw new PurchaseError('route_unavailable', 422, 'No executable route is currently available for this payment and stock.');
    }
    available.sort((a, b) => Number(b.route.executionValidated === true) - Number(a.route.executionValidated === true) ||
      routeScore(b.route, b.destination).cmp(routeScore(a.route, a.destination)));
    const selected = available[0]!; const route = selected.route; const destination = selected.destination;
    const actions: PurchaseAction[] = [];
    if (source.network !== 'SOLANA' && source.address !== EVM_NATIVE) {
      if (!route.approvalAddress) throw new PurchaseError('unsafe_router_response', 503,
        'The router omitted the required token approval target. Nothing was submitted.');
      const allowance = await this.chains.allowance(source.network, source.address, fromWallet.address, route.approvalAddress)
        .catch(() => { throw new PurchaseError('router_unavailable', 503, 'The token allowance could not be verified. Nothing was submitted.'); });
      if (BigInt(allowance) < BigInt(request.amountBaseUnits)) {
        const data = approvalData(route.approvalAddress, request.amountBaseUnits);
        const fee = await this.chains.prepareApproval(source.network, fromWallet.address, source.address, data)
          .catch(() => { throw new PurchaseError('router_unavailable', 503, 'The exact token approval could not be simulated. Nothing was submitted.'); });
        actions.push({ id: randomUUID(), index: actions.length,
          type: 'EVM_APPROVAL', network: source.network, chainId: source.chainId as '1' | '8453' | '42161', walletAddress: fromWallet.address,
          transaction: { from: fromWallet.address, to: source.address, data,
            value: '0x0', gas: fee.gas, gasPrice: fee.gasPrice, maxFeePerGas: fee.maxFeePerGas,
            maxPriorityFeePerGas: fee.maxPriorityFeePerGas, nonce: null } });
      }
    } else if (route.approvalAddress !== null) throw new PurchaseError('unsafe_router_response', 503,
      'The router requested an unexpected approval for a native asset. Nothing was submitted.');
    if (route.transaction.kind === 'EVM') {
      if (source.network === 'SOLANA') throw new PurchaseError('unsafe_router_response', 503, 'The router returned the wrong transaction family.');
      actions.push({ id: randomUUID(), index: actions.length, type: 'EVM_ROUTE', network: source.network,
        chainId: source.chainId as '1' | '8453' | '42161', walletAddress: fromWallet.address,
        transaction: { from: route.transaction.from, to: route.transaction.to, data: route.transaction.data,
          value: route.transaction.value, gas: route.transaction.gas, gasPrice: route.transaction.gasPrice,
          maxFeePerGas: route.transaction.maxFeePerGas, maxPriorityFeePerGas: route.transaction.maxPriorityFeePerGas,
          nonce: route.transaction.nonce } });
    } else {
      if (source.network !== 'SOLANA') throw new PurchaseError('unsafe_router_response', 503, 'The router returned the wrong transaction family.');
      actions.push({ id: randomUUID(), index: actions.length, type: 'SOLANA_ROUTE', network: 'SOLANA', chainId: '1151111081099710',
        walletAddress: fromWallet.address, transactionBase64: route.transaction.transactionBase64,
        minContextSlot: route.transaction.minContextSlot, lastValidBlockHeight: route.transaction.lastValidBlockHeight });
    }
    let requiredNativeGas = 0n;
    for (const action of actions) if (action.type !== 'SOLANA_ROUTE') {
      const gas = action.transaction.gas === null ? null : BigInt(action.transaction.gas);
      const fee = action.transaction.maxFeePerGas ?? action.transaction.gasPrice;
      if (gas !== null && fee !== null) requiredNativeGas += gas * BigInt(fee);
      else if (action.type === 'EVM_ROUTE' && route.sourceGasBaseUnits !== null) requiredNativeGas += BigInt(route.sourceGasBaseUnits);
      else throw new PurchaseError('unsafe_router_response', 503, 'The router did not provide a bounded source-network gas estimate.');
    }
    if (source.network === 'SOLANA') requiredNativeGas = route.sourceGasBaseUnits === null ? 100_000n :
      (BigInt(route.sourceGasBaseUnits) < 100_000n ? 100_000n : BigInt(route.sourceGasBaseUnits));
    const nativeId = source.network === 'SOLANA' ? 'SOLANA:SOL' : `${source.network}:ETH`;
    const nativeAsset = paymentAssetDefinition(nativeId);
    if (!nativeAsset) throw new PurchaseError('invalid_purchase', 422, 'The source network gas asset is not configured.');
    const nativeBalance = source.address === nativeAsset.address ? BigInt(balance) : BigInt(await this.chains.balance(nativeAsset, fromWallet)
      .catch(() => { throw new PurchaseError('router_unavailable', 503, 'The source-network gas balance could not be verified.'); }));
    const requiredNative = requiredNativeGas + (source.address === nativeAsset.address ? BigInt(request.amountBaseUnits) : 0n);
    if (nativeBalance < requiredNative) throw new PurchaseError('insufficient_balance', 422,
      `The connected wallet needs more ${nativeAsset.symbol} on ${source.network} to cover the reviewed amount and network fees.`);
    const output = displayAmount(route.toAmount, destination.decimals, destination.uiMultiplier);
    const minimum = displayAmount(route.toAmountMin, destination.decimals, destination.uiMultiplier);
    const quote = PurchaseQuoteResponseSchema.parse({ schemaVersion: 1, operationId: request.operationId, quoteId: randomUUID(),
      stockId: stock.id, fromAssetId: source.id, destinationId: destination.id,
      inputAmount: displayAmount(request.amountBaseUnits, source.decimals, source.uiMultiplier), inputBaseUnits: request.amountBaseUnits,
      estimatedOutputAmount: output, estimatedOutputBaseUnits: route.toAmount,
      outputDecimals: destination.decimals, outputUiMultiplier: destination.uiMultiplier ?? '1',
      executableUnitPriceUsd: executableUnitPrice(route, output),
      // An ERC-20 approval has an additional gas cost that /v1/quote does not estimate; leave the aggregate unknown instead of understating it.
      feesUsd: actions.some(action => action.type === 'EVM_APPROVAL') ? null : route.feesUsd,
      priceImpactPercent: route.priceImpactPercent, slippageBps: request.slippageBps,
      minimumReceived: minimum, minimumReceivedBaseUnits: route.toAmountMin,
      expiresAt: new Date(Math.min(this.now() + (route.tool === RELAY_ANTHROPIC_TOOL ? 300_000 : 90_000),
        route.expiresAt ? Date.parse(route.expiresAt) : Infinity)).toISOString(), walletConfirmations: actions.length, actions,
      executionEnabled: this.executionEnabled && route.executionValidated === true,
      executionReason: this.executionEnabled && route.executionValidated === true ? null
        : 'This route is a preview. No independently verified purchase route is available for this payment and stock.' });
    return this.ledger.register(principal, request.operationId, requestFingerprint, quote,
      { routeId: route.routeId, tool: route.tool, fromChainId: route.sourceChainId,
        toChainId: route.destinationChainId, destinationRecipientAddress: route.toAddress }, request.side ?? 'BUY').quote;
  }

  commit(principal: PrivyPrincipal, quoteId: string): PurchaseCommitResponse {
    if (!this.executionEnabled) throw new PurchaseError('stock_not_purchasable', 422,
      'Live purchase execution is disabled. No wallet action was requested.');
    const stored = this.ledger.findByQuote(principal, quoteId);
    if (!stored.quote.executionEnabled) throw new PurchaseError('stock_not_purchasable', 422,
      stored.quote.executionReason ?? 'Live purchase execution is disabled.');
    const result = this.ledger.commit(principal, quoteId);
    return { schemaVersion: 1, quoteId, state: 'COMMITTED', idempotent: result.idempotent };
  }

  invoking(principal: PrivyPrincipal, quoteId: string, actionId: string): PurchaseInvokingResponse {
    if (!this.executionEnabled) throw new PurchaseError('stock_not_purchasable', 422,
      'Live purchase execution is disabled. No wallet action was requested.');
    const stored = this.ledger.findByQuote(principal, quoteId);
    if (!stored.quote.executionEnabled) throw new PurchaseError('stock_not_purchasable', 422,
      stored.quote.executionReason ?? 'Live purchase execution is disabled.');
    const result = this.ledger.invoking(principal, quoteId, actionId);
    return { schemaVersion: 1, quoteId, actionId, state: 'INVOKING', idempotent: result.idempotent };
  }

  releaseInvocation(principal: PrivyPrincipal, quoteId: string, actionId: string): PurchaseStatus {
    return this.ledger.response(this.ledger.releaseInvocation(principal, quoteId, actionId));
  }

  submitted(principal: PrivyPrincipal, quoteId: string, actionId: string, request: PurchaseSubmittedRequest): PurchaseStatus {
    return this.ledger.response(this.ledger.submit(principal, quoteId, actionId, request.transactionId));
  }

  async status(principal: PrivyPrincipal, quoteId: string): Promise<PurchaseStatus> {
    let purchase = this.ledger.get(principal, quoteId);
    if (purchase.state === 'QUOTED') throw new PurchaseLedgerError('quote_state_invalid', 422,
      'Commit the reviewed purchase before tracking execution.');
    if (purchase.state !== 'EXECUTING') return this.ledger.response(purchase);
    const latestSubmission = purchase.submitted.at(-1);
    const latestAction = latestSubmission ? purchase.quote.actions.find(action => action.id === latestSubmission.actionId) : undefined;
    if (latestSubmission && latestAction?.type === 'EVM_APPROVAL' && latestSubmission.confirmedAt === null) {
      try {
        const receipt = await this.chains.transactionStatus(latestAction.network, latestSubmission.transactionId);
        if (receipt === 'FAILED') return this.ledger.response(this.ledger.fail(principal, quoteId,
          'The token approval transaction reverted. No route transaction was submitted.'));
        if (receipt === 'PENDING') return this.ledger.response(purchase);
        const source = paymentAssetDefinition(purchase.quote.fromAssetId);
        if (!source || source.network === 'SOLANA' || source.address === EVM_NATIVE) throw new PurchaseError('invalid_purchase', 422,
          'The reviewed approval asset is invalid.');
        const spenderWord = latestAction.transaction.data.slice(10, 74);
        const spender = `0x${spenderWord.slice(24)}`;
        const approvedAmount = BigInt(`0x${latestAction.transaction.data.slice(74, 138)}`);
        if (approvedAmount !== BigInt(purchase.quote.inputBaseUnits)) throw new PurchaseError('invalid_purchase', 422,
          'The reviewed approval amount is invalid.');
        const allowance = await this.chains.allowance(latestAction.network, source.address, latestAction.walletAddress, spender);
        if (BigInt(allowance) < BigInt(purchase.quote.inputBaseUnits)) return this.ledger.response(this.ledger.fail(principal, quoteId,
          'The confirmed approval did not grant the reviewed amount. No route transaction was submitted.'));
        purchase = this.ledger.confirmAction(principal, quoteId, latestAction.id);
      } catch (error) {
        if (error instanceof PurchaseError || error instanceof PurchaseLedgerError) throw error;
        // RPC propagation can lag briefly after a receipt. Keep the durable state and poll again automatically.
        return this.ledger.response(purchase);
      }
    }
    if (purchase.submitted.length !== purchase.quote.actions.length) return this.ledger.response(purchase);
    const transactionId = purchase.submitted.at(-1)!.transactionId;
    try {
      const finalAction = purchase.quote.actions.at(-1)!;
      const statusRequest: Parameters<PurchaseRouter['status']>[0] = {
        transactionId, tool: purchase.route.tool, routeId: purchase.route.routeId,
        fromChainId: purchase.route.fromChainId, toChainId: purchase.route.toChainId,
        ...(purchase.quote.destinationId.startsWith('SOLANA:') ? {
          destinationAddress: purchase.quote.destinationId.slice(purchase.quote.destinationId.indexOf(':') + 1),
          recipientAddress: purchase.route.destinationRecipientAddress ??
            (finalAction.type === 'SOLANA_ROUTE' ? finalAction.walletAddress : undefined),
          minimumReceivedBaseUnits: purchase.quote.minimumReceivedBaseUnits,
        } : {}),
        ...(finalAction.type === 'SOLANA_ROUTE' ? { expectedTransactionBase64: finalAction.transactionBase64 } : {}),
      };
      const observation = await this.router.status(statusRequest);
      const destinationTransactionId = observation.destinationTransactionId ?? null;
      if (observation.state === 'DONE' && purchase.quote.destinationId.startsWith('SOLANA:') &&
          finalAction.type !== 'SOLANA_ROUTE' && destinationTransactionId === null) {
        purchase = this.ledger.recordRouterStatus(principal, quoteId, { state: 'PENDING', receivedAmount: null,
          message: 'Waiting for the Solana settlement transaction.' });
        return this.ledger.response(purchase);
      }
      const receivedAmount = observation.receivedBaseUnits === null ? null :
        displayAmount(observation.receivedBaseUnits, this.destinationDecimals(purchase.quote, purchase.quote.destinationId), purchase.quote.outputUiMultiplier);
      purchase = this.ledger.recordRouterStatus(principal, quoteId, { state: observation.state, receivedAmount,
        message: observation.message, destinationTransactionId });
    } catch (error) {
      // A temporary tracker outage never changes the durable execution state. The app keeps polling automatically.
      if (!(error instanceof LiFiError && error.code === 'router_unavailable')) throw error;
    }
    return this.ledger.response(purchase);
  }

  private destinationDecimals(quote: PurchaseQuoteResponse, destinationId: string): number {
    const action = quote.actions.at(-1);
    if (!action || !destinationId) throw new PurchaseError('invalid_purchase', 422, 'The purchase destination is invalid.');
    if (quote.outputDecimals !== undefined &&
      displayAmount(quote.estimatedOutputBaseUnits, quote.outputDecimals, quote.outputUiMultiplier) === quote.estimatedOutputAmount) return quote.outputDecimals;
    // Decimal precision is recoverable exactly from the reviewed base-unit/display pair, including outputs below one token.
    for (let decimals = 0; decimals <= 36; decimals++) if (humanAmount(quote.estimatedOutputBaseUnits, decimals) === quote.estimatedOutputAmount) return decimals;
    throw new PurchaseError('invalid_purchase', 422, 'The reviewed purchase precision is invalid.');
  }

  private async stock(id: string): Promise<Stock> {
    const stock = (await this.catalog()).stocks.find(value => value.id === id);
    if (!stock) throw new PurchaseError('stock_not_purchasable', 422, 'Stock is not in the current verified catalog.');
    return stock;
  }

  private async destinations(stock: Stock,
    include: (candidate: { id: string; network: PurchaseNetwork; address: string }) => boolean = () => true): Promise<PurchaseDestination[]> {
    const seen = new Set<string>();
    const candidates = stock.deployments.flatMap(deployment => {
      const network = normalizeNetwork(deployment.network); const address = deployment.address;
      if (!network || !['ETHEREUM', 'SOLANA'].includes(network) || !validDeployment(network, address)) return [];
      const id = `${network}:${address}`; if (!include({ id, network, address }) || seen.has(id)) return []; seen.add(id);
      return [{ network, address, declaredDecimals: deployment.decimals }];
    });
    const settled = await Promise.allSettled(candidates.map(async deployment => {
      const decimals = await this.chains.tokenDecimals(deployment.network, deployment.address);
      if (deployment.declaredDecimals !== null && deployment.declaredDecimals !== decimals) throw new Error('Mint precision differs from issuer metadata');
      const uiMultiplier = await this.chains.tokenUiMultiplier?.(deployment.network, deployment.address, decimals) ?? '1';
      return { ...destinationFrom(deployment.network, deployment.address, stock.symbol, decimals), uiMultiplier };
    }));
    const available = settled.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);
    if (available.length === 0 && settled.some(result => result.status === 'rejected' && result.reason instanceof PurchaseRpcUnavailableError)) {
      throw new PurchaseError('router_unavailable', 503,
        'Stock deployment metadata is temporarily unavailable from the connected network. Refresh and try again.');
    }
    return available;
  }
  private async stockSources(stock: Stock): Promise<PaymentAssetDefinition[]> {
    return (await this.destinations(stock, candidate => candidate.network === 'SOLANA')).map(asset => ({
      id: asset.id, symbol: stock.symbol, name: stock.name.slice(0, 80), network: asset.network,
      chainId: asset.chainId, address: asset.address, decimals: asset.decimals, walletChain: 'SOLANA', uiMultiplier: asset.uiMultiplier,
    }));
  }

  private async stockBalances(stock: Stock, wallets: PurchaseOptionsRequest['wallets']) {
    const wallet = walletForNetwork(wallets, 'SOLANA');
    if (!wallet) return [];
    return Promise.all((await this.stockSources(stock)).map(async source => {
      try {
        const units = await this.chains.balance(source, wallet);
        return { ...source, walletChain: undefined, balanceBaseUnits: units,
          balance: displayAmount(units, source.decimals, source.uiMultiplier), usdValue: null, enabled: true };
      } catch {
        return { ...source, walletChain: undefined, balanceBaseUnits: '0', balance: '0', usdValue: null, enabled: false };
      }
    })).then(rows => rows.map(({ walletChain: _walletChain, ...row }) => row));
  }

  private async sellDestinations(wallets: PurchaseOptionsRequest['wallets']): Promise<PurchaseDestination[]> {
    if (!walletForNetwork(wallets, 'SOLANA')) return [];
    return ['SOLANA:USDC', 'SOLANA:SOL'].map(id => {
      const asset = paymentAssetDefinition(id)!;
      return destinationFrom(asset.network, asset.address, asset.symbol, asset.decimals);
    });
  }

}

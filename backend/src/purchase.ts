import { createHash, randomUUID } from 'node:crypto';
import { Decimal } from 'decimal.js';
import type { PrivyPrincipal } from './privy-auth.js';
import { destinationFrom, displayAmount, humanAmount, normalizeNetwork, paymentAssetDefinition,
  PurchaseRpcUnavailableError, walletForNetwork, type PaymentAssetDefinition, type PurchaseChainReader } from './purchase-chain.js';
import { PurchaseRouteError, type PurchaseRouteQuote, type PurchaseRouter } from './purchase-router.js';
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
const ROBINHOOD_TRADING_UNAVAILABLE = 'Robinhood Stock Tokens are available for browsing, but purchases are not supported in Eleven yet.';

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
  return network === 'SOLANA' && /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(address);
}
function executableUnitPrice(route: PurchaseRouteQuote, outputAmount: string): string | null {
  if (route.toAmountUsd === null) return null;
  const output = new Decimal(outputAmount); const usd = new Decimal(route.toAmountUsd);
  if (!output.isFinite() || output.lte(0) || !usd.isFinite() || usd.isNegative()) return null;
  return usd.div(output).toFixed();
}
function routeScore(route: PurchaseRouteQuote, destination: PurchaseDestination): Decimal {
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
    if (stock.provider === 'robinhood') return { schemaVersion: 1, stockId: stock.id, purchasable: false,
      reason: ROBINHOOD_TRADING_UNAVAILABLE, paymentAssets: [], destinations: [], executionEnabled: false,
      executionReason: ROBINHOOD_TRADING_UNAVAILABLE, defaultPaymentAssetId: null, defaultDestinationId: null };
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
      // Auto compares verified same-wallet Solana alternatives.
      defaultDestinationId: null };
  }

  async quote(principal: PrivyPrincipal, request: PurchaseQuoteRequest): Promise<PurchaseQuoteResponse> {
    if (request.stockId.startsWith('robinhood:')) throw new PurchaseError('stock_not_purchasable', 422, ROBINHOOD_TRADING_UNAVAILABLE);
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
    if (stock.provider === 'robinhood') throw new PurchaseError('stock_not_purchasable', 422, ROBINHOOD_TRADING_UNAVAILABLE);
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
      if (route.toDecimals !== destination.decimals) throw new PurchaseRouteError('unsafe_router_response',
        'The router token precision does not match the verified Solana asset.');
      return { destination, route };
    };
    const attempts = await Promise.allSettled(destinations.map(quoteDestination));
    const available = attempts.flatMap(result => result.status === 'fulfilled' ? [result.value] : []);
    if (available.length === 0) {
      const unsafe = attempts.find(result => result.status === 'rejected' && result.reason instanceof PurchaseRouteError && result.reason.code === 'unsafe_router_response');
      const unavailable = attempts.find(result => result.status === 'rejected' && result.reason instanceof PurchaseRouteError && result.reason.code === 'router_unavailable');
      if (unsafe?.status === 'rejected') throw unsafe.reason;
      if (unavailable?.status === 'rejected') throw unavailable.reason;
      throw new PurchaseError('route_unavailable', 422, 'No executable route is currently available for this payment and stock.');
    }
    available.sort((a, b) => Number(b.route.executionValidated === true) - Number(a.route.executionValidated === true) ||
      routeScore(b.route, b.destination).cmp(routeScore(a.route, a.destination)));
    const selected = available[0]!; const route = selected.route; const destination = selected.destination;
    const actions: PurchaseAction[] = [];
    if (route.approvalAddress !== null) throw new PurchaseError('unsafe_router_response', 503,
      'The router requested an unexpected approval for a native asset. Nothing was submitted.');
    actions.push({ id: randomUUID(), index: 0, type: 'SOLANA_ROUTE', network: 'SOLANA', chainId: '1151111081099710',
      walletAddress: fromWallet.address, transactionBase64: route.transaction.transactionBase64,
      minContextSlot: route.transaction.minContextSlot, lastValidBlockHeight: route.transaction.lastValidBlockHeight });
    const requiredNativeGas = route.sourceGasBaseUnits === null ? 100_000n :
      (BigInt(route.sourceGasBaseUnits) < 100_000n ? 100_000n : BigInt(route.sourceGasBaseUnits));
    const nativeAsset = paymentAssetDefinition('SOLANA:SOL');
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
      feesUsd: route.feesUsd,
      priceImpactPercent: route.priceImpactPercent, slippageBps: request.slippageBps,
      minimumReceived: minimum, minimumReceivedBaseUnits: route.toAmountMin,
      expiresAt: new Date(Math.min(this.now() + 90_000,
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
    if (purchase.submitted.length !== purchase.quote.actions.length) return this.ledger.response(purchase);
    const transactionId = purchase.submitted.at(-1)!.transactionId;
    try {
      const finalAction = purchase.quote.actions.at(-1)!;
      const statusRequest: Parameters<PurchaseRouter['status']>[0] = {
        transactionId, tool: purchase.route.tool, routeId: purchase.route.routeId,
        fromChainId: purchase.route.fromChainId, toChainId: purchase.route.toChainId,
        destinationAddress: purchase.quote.destinationId.slice(purchase.quote.destinationId.indexOf(':') + 1),
        recipientAddress: purchase.route.destinationRecipientAddress ?? finalAction.walletAddress,
        minimumReceivedBaseUnits: purchase.quote.minimumReceivedBaseUnits,
        expectedTransactionBase64: finalAction.transactionBase64,
      };
      const observation = await this.router.status(statusRequest);
      const destinationTransactionId = observation.destinationTransactionId ?? null;
      const receivedAmount = observation.receivedBaseUnits === null ? null :
        displayAmount(observation.receivedBaseUnits, this.destinationDecimals(purchase.quote, purchase.quote.destinationId), purchase.quote.outputUiMultiplier);
      purchase = this.ledger.recordRouterStatus(principal, quoteId, { state: observation.state, receivedAmount,
        message: observation.message, destinationTransactionId });
    } catch (error) {
      // A temporary tracker outage never changes the durable execution state. The app keeps polling automatically.
      if (!(error instanceof PurchaseRouteError && error.code === 'router_unavailable')) throw error;
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
      if (!network || !validDeployment(network, address)) return [];
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

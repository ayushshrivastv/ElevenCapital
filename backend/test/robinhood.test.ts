import assert from 'node:assert/strict';
import test from 'node:test';
import { WebSocket } from 'ws';
import { createGetJson, type GetJson } from '../src/http.js';
import { featuredRobinhoodStocks, loadRobinhood, normalizeRobinhoodQuote, ROBINHOOD_FEATURED_SYMBOLS, type RobinhoodAsset } from '../src/robinhood.js';
import { CatalogSchema, StockSchema, type Catalog, type Stock } from '../src/schema.js';
import { TokenStatisticsService } from '../src/statistics.js';
import { LiveMarketService } from '../src/live-service.js';
import { MarketDataService } from '../src/service.js';
import { snapshotAt } from '../src/realtime.js';
import { PurchaseError, PurchaseService } from '../src/purchase.js';
import { PurchaseOptionsResponseSchema, PurchaseQuoteRequestSchema, type PurchaseWallet } from '../src/purchase-schema.js';
import type { PurchaseChainReader } from '../src/purchase-chain.js';
import type { PurchaseRouter } from '../src/lifi.js';
import { buildApp } from '../src/app.js';
import { matchesReviewedRobinhoodHistory, reviewedRobinhoodHistorySymbol } from '../src/underlying-reference.js';
import { featuredCompanySummary } from '../src/featured-about.js';

const NOW = Date.parse('2026-09-29T10:00:00.000Z');
const CONTRACT = '0x1111111111111111111111111111111111111111';
const ID = `0x${'a'.repeat(64)}`;
const asset: RobinhoodAsset = { id: ID, tokenSymbol: 'MSFT', tokenName: 'Microsoft • Robinhood Token',
  deployments: [{ contractAddress: CONTRACT, chainId: 4663, networkName: 'Robinhood Chain' }], tokenDecimals: 18,
  currentMultiplier: '1.001148322800714293', isin: 'US5949181045', status: 'ASSET_STATUS_ACTIVE',
  logoUrl: 'https://cdn.robinhood.com/msft.png' };
const quote = { tokenSymbol: 'MSFT', deployments: asset.deployments, bid: '226.17', ask: '228.17', currency: 'USD',
  generatedAt: '2026-09-29T09:59:59.864511474Z', isTradingHalt: false,
  tokenBid: '226.429716167837551648', tokenAsk: '228.432012813438980234', dailyTradingVolume: '100000',
  mintBurnTokenVolume: '999', mintBurnUsdVolume: '1000' };
const stockId = `robinhood:${ID}`;
function providerHttp(assets: unknown[] = [asset], quotes: unknown[] = [quote]): GetJson {
  return async (provider, path) => {
    assert.equal(provider, 'robinhood');
    if (path === '/rhj/assets') return { assets };
    assert.equal(path, '/rhj/prices'); return { quotes };
  };
}
async function until(check: () => boolean): Promise<void> {
  const deadline = Date.now() + 3_000;
  while (!check()) {
    if (Date.now() > deadline) throw new Error('Timed out');
    await new Promise(resolve => setTimeout(resolve, 5));
  }
}

test('official Robinhood catalog preserves chain 4663 and verified contract provenance without underlying volume or executable trading', async () => {
  const snapshot = await loadRobinhood(providerHttp(), () => NOW);
  const stock = StockSchema.parse(snapshot.stocks[0]);
  assert.equal(snapshot.partial, false);
  assert.deepEqual(snapshot.reconciliation!.includedIds, [stockId]);
  assert.equal(stock.providerLabel, 'Robinhood Stock Tokens');
  assert.equal(stock.quote.price, '227.43086449063826594081');
  assert.equal(stock.quote.basis, 'provider_indicative_token');
  assert.equal(stock.quote.currencyBasis, 'quoted_currency');
  assert.equal(stock.quote.asOf, '2026-09-29T09:59:59.864Z');
  assert.equal(stock.quote.receivedAt, new Date(NOW).toISOString());
  assert.equal(stock.quote.changePercent, null);
  assert.deepEqual(stock.deployments, [{ network: 'Robinhood Chain', chainId: 4663, address: CONTRACT,
    explorerUrl: `https://robin.etherscan.io/address/${CONTRACT}`, decimals: 18,
    depositEnabled: null, withdrawEnabled: null }]);
  assert.equal(stock.statistics.scope, 'robinhood_chain_token');
  assert.equal(stock.statistics.network, 'robinhood_chain');
  assert.equal(stock.statistics.mint, CONTRACT);
  assert.equal(stock.statistics.marketCapitalization.value, null);
  assert.equal(stock.activity.source, 'Robinhood');
  assert.equal(stock.activity.scope, 'robinhood_chain_token');
  assert.equal(stock.activity.volume24h, null); assert.equal(stock.volume24h, null);
  assert.equal(stock.trading.enabled, false);
  assert.equal(stock.robinhoodReference!.bid, '226.17');
  assert.equal(stock.robinhoodReference!.ask, '228.17');
  assert.equal(stock.robinhoodReference!.dailyTradingVolume, '100000');
  assert.equal(stock.robinhoodReference!.volumeUnit, 'underlying_shares');
  assert.equal(stock.robinhoodReference!.currentMultiplier, asset.currentMultiplier);
  assert.match(stock.description!, /current token multiplier/);
  const enriched = await new TokenStatisticsService(async () => { throw new Error('No Solana lookup is allowed'); }, () => NOW).enrich([stock]);
  assert.deepEqual(enriched[0]!.statistics, stock.statistics);
});

test('Robinhood quote normalization rejects foreign contracts, chains, duplicates, stale and unsafe prices and supports precision-rounded token quotes', async () => {
  assert.ok(normalizeRobinhoodQuote(quote, asset, NOW));
  const ethereumAsset = { ...asset, deployments: [{ ...asset.deployments[0]!, chainId: 1 }] };
  assert.equal(normalizeRobinhoodQuote({ ...quote, deployments: ethereumAsset.deployments }, ethereumAsset, NOW), null);
  assert.ok(normalizeRobinhoodQuote({ ...quote, tokenBid: '226.429716167837551647' }, asset, NOW), 'official truncation within one token precision unit is accepted');
  for (const row of [
    { ...quote, tokenSymbol: 'AAPL' }, { ...quote, currency: 'EUR' }, { ...quote, bid: '0' },
    { ...quote, ask: '1' }, { ...quote, tokenBid: '100' },
    { ...quote, deployments: [{ ...asset.deployments[0], chainId: 1 }] },
    { ...quote, deployments: [{ ...asset.deployments[0], chainId: 42161 }] },
    { ...quote, deployments: [{ ...asset.deployments[0], contractAddress: '0x2222222222222222222222222222222222222222' }] },
    { ...quote, generatedAt: new Date(NOW - 300_001).toISOString() },
    { ...quote, generatedAt: new Date(NOW + 1).toISOString() },
  ]) assert.equal(normalizeRobinhoodQuote(row, asset, NOW), null);
  for (const rows of [[], [quote, quote], [{ ...quote, bid: 'invalid' }]]) {
    const result = await loadRobinhood(providerHttp([asset], rows), () => NOW);
    assert.equal(result.stocks.length, 1); assert.equal(result.stocks[0]!.quote.price, null); assert.equal(result.partial, true);
  }
});

test('Robinhood identities fail closed on duplicates, missing precision, invalid factors and Ethereum/Arbitrum One substitutions', async () => {
  const variants = [
    [asset, asset],
    [{ ...asset, tokenDecimals: undefined }],
    [{ ...asset, currentMultiplier: '0' }],
    [{ ...asset, deployments: [{ ...asset.deployments[0], chainId: 1 }] }],
    [{ ...asset, deployments: [{ ...asset.deployments[0], chainId: 42161 }] }],
    [{ ...asset, deployments: [{ ...asset.deployments[0], contractAddress: '0x0000000000000000000000000000000000000000' }] }],
  ];
  for (const rows of variants) {
    const result = await loadRobinhood(providerHttp(rows), () => NOW);
    assert.equal(result.stocks.length, 0);
    assert.equal(result.reconciliation!.unresolved.length, rows.length);
    assert.equal(result.reconciliation!.sourceCount, rows.length);
  }
  const funds = await loadRobinhood(providerHttp([
    { ...asset, tokenSymbol: 'SPY', isin: 'US78462F1030' },
    { ...asset, id: `0x${'b'.repeat(64)}`, tokenSymbol: 'IBIT', deployments: [{ ...asset.deployments[0], contractAddress: '0x2222222222222222222222222222222222222222' }] },
  ], []), () => NOW);
  assert.deepEqual(funds.stocks.map(stock => stock.symbol), ['SPY']);
  assert.equal(funds.reconciliation!.excluded.length, 1, 'existing stock/equity-ETF classification excludes crypto funds');
});

test('Robinhood HTTP transport only permits the two read-only resources and never redirects to another origin', async () => {
  const original = globalThis.fetch; const requested: string[] = [];
  try {
    globalThis.fetch = (async input => { requested.push(String(input)); return new Response('{"assets":[]}', { status: 200 }); }) as typeof fetch;
    const http = createGetJson();
    assert.deepEqual(await http('robinhood', '/rhj/assets'), { assets: [] });
    assert.deepEqual(requested, ['https://api.robinhood.com/rhj/assets']);
    for (const path of ['/rhj/orders', '/rhj/prices/MSFT', 'https://evil.example/rhj/assets']) await assert.rejects(http('robinhood', path));
    await assert.rejects(http('robinhood', '/rhj/assets', { symbol: 'MSFT' }));
    assert.equal(requested.length, 1);
  } finally { globalThis.fetch = original; }
});

test('live Robinhood refresh updates references and multiplier together, retains dated failure snapshots and expires stale quotes', async () => {
  let clock = NOW; let multiplier = asset.currentMultiplier; let available = true; let validQuote = true; let calls = 0;
  const http: GetJson = async (provider, path) => {
    if (provider !== 'robinhood') throw new Error('Unrelated provider unavailable');
    if (!available) throw new Error('Robinhood unavailable');
    if (path === '/rhj/assets') { calls++; return { assets: [{ ...asset, currentMultiplier: multiplier }] }; }
    return { quotes: validQuote ? [{ ...quote, tokenBid: undefined, tokenAsk: undefined, generatedAt: new Date(clock - 1000).toISOString() }] : [] };
  };
  const service = new LiveMarketService(http, () => clock);
  const control = service as unknown as { refreshMetadata(id: 'robinhood'): Promise<void> };
  try {
    await service.catalog(); await control.refreshMetadata('robinhood');
    assert.equal(service.snapshot().providers.find(row => row.id === 'robinhood')!.status, 'ok');
    for (let i = 0; i < 20; i++) service.setInterest([stockId]);
    await new Promise(resolve => setTimeout(resolve, 0)); assert.equal(calls, 1);
    multiplier = '2'; clock += 30_001;
    await control.refreshMetadata('robinhood');
    assert.equal(service.snapshot().stocks[0]!.quote.price, '454.34');
    validQuote = false; clock += 30_001; await control.refreshMetadata('robinhood');
    assert.equal(service.snapshot().stocks[0]!.quote.price, null, 'missing references must not reuse an old multiplier');
    validQuote = true; await control.refreshMetadata('robinhood');
    const receivedAt = service.snapshot().stocks[0]!.quote.receivedAt;
    available = false; clock += 30_001; await control.refreshMetadata('robinhood');
    assert.equal(service.snapshot().stocks[0]!.quote.receivedAt, receivedAt);
    assert.equal(service.snapshot().providers.find(row => row.id === 'robinhood')!.status, 'stale');
    clock += 300_001;
    assert.equal(snapshotAt(service.snapshot(), clock).stocks[0]!.quote.price, null);
    const chart = await service.chart(stockId, 'ONE_DAY');
    assert.equal(chart.status, 'unavailable'); assert.deepEqual(chart.points, []);
    assert.equal(chart.basis, 'underlying_share_reference');
    assert.match(chart.statusReason!, /Robinhood Chain token-trade history/);
  } finally { service.stop(); }
});

test('undocumented day-high/low fields stay unavailable, zero provider volume is missing, and expired references are cleared', async () => {
  const stock = (await loadRobinhood(providerHttp([asset], [{ ...quote, dailyTradingVolume: '0', dailyHigh: '230.1234', dailyLow: '220.5' }]), () => NOW)).stocks[0]!;
  assert.equal(stock.robinhoodReference!.dailyHigh, null);
  assert.equal(stock.robinhoodReference!.dailyLow, null);
  assert.equal(stock.robinhoodReference!.dailyTradingVolume, null);
  assert.equal(stock.activity.volume24h, null); assert.equal(stock.statistics.marketCapitalization.value, null);
  const invalidDay = (await loadRobinhood(providerHttp([asset], [{ ...quote, dailyHigh: '1', dailyLow: '2' }]), () => NOW)).stocks[0]!;
  assert.equal(invalidDay.robinhoodReference!.dailyHigh, null); assert.equal(invalidDay.robinhoodReference!.dailyLow, null);
  const catalog: Catalog = { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'robinhood', status: 'ok' }], stocks: [stock] };
  assert.equal(snapshotAt(catalog, NOW).stocks[0]!.robinhoodReference!.asOf, stock.quote.asOf);
  assert.equal(snapshotAt(catalog, NOW + 300_001).stocks[0]!.robinhoodReference, undefined);
});

test('failed share-history refresh keeps the original dated cache briefly, then expires it without token-price substitution', async () => {
  let clock = NOW; let available = true;
  const http: GetJson = async (provider, path) => {
    if (provider === 'robinhood') return providerHttp()(provider, path);
    if (provider !== 'yahoo' || !available) throw new Error('Unavailable');
    if (path.endsWith('/search')) return { quotes: [{ symbol: 'MSFT', exchange: 'NMS', quoteType: 'EQUITY', isYahooFinance: true }] };
    return yahooHistory();
  };
  const service = new LiveMarketService(http, () => clock);
  try {
    await service.prewarm(); const first = await service.chart(stockId, 'ONE_DAY');
    assert.equal(first.status, 'ok');
    available = false; clock += 60_001;
    const cached = await service.chart(stockId, 'ONE_DAY');
    assert.equal(cached.status, 'ok'); assert.deepEqual(cached.points, first.points);
    assert.equal(cached.receivedAt, first.receivedAt); assert.match(cached.statusReason!, /History refresh is delayed/);
    clock += 300_001;
    const expired = await service.chart(stockId, 'ONE_DAY');
    assert.equal(expired.status, 'unavailable'); assert.deepEqual(expired.points, []);
  } finally { service.stop(); }
});

function featuredFixture(): RobinhoodAsset[] {
  return ROBINHOOD_FEATURED_SYMBOLS.map((symbol, index) => ({ ...asset, id: `0x${(index + 1).toString(16).padStart(64, '0')}`,
    tokenSymbol: symbol, tokenName: `${symbol} • Robinhood Token`, isin: `US${String(index + 1).padStart(9, '0')}0`,
    deployments: [{ ...asset.deployments[0]!, contractAddress: `0x${(index + 1).toString(16).padStart(40, '0')}` }] }));
}

test('Robinhood featured ordering uses all20 exact verified rows and never substitutes an absent ticker or another provider', async () => {
  const rows = featuredFixture().reverse();
  const catalog = await loadRobinhood(providerHttp(rows, []), () => NOW);
  assert.deepEqual(catalog.stocks.map(stock => stock.symbol), [...ROBINHOOD_FEATURED_SYMBOLS]);
  const missing = catalog.stocks.filter(stock => stock.symbol !== 'MSFT');
  const otherProvider = { ...catalog.stocks[0]!, provider: 'backed' as const };
  assert.deepEqual(featuredRobinhoodStocks([otherProvider, ...missing]).map(stock => stock.symbol), [...ROBINHOOD_FEATURED_SYMBOLS].slice(1));
});

function yahooHistory(symbol = 'MSFT') {
  return { chart: { result: [{ meta: { symbol, currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: '228',
    regularMarketTime: String(Math.floor((NOW - 1_000) / 1000)) },
    timestamp: [String(Math.floor((NOW - 900_000) / 1000)), String(Math.floor((NOW - 600_000) / 1000)), String(Math.floor((NOW - 300_000) / 1000))],
    indicators: { quote: [{ close: ['225.123456789', '226.5', '227.75'], volume: ['10', '20', '30'] }] } }] } };
}

test('startup prewarms official rows before app traffic while exact-ISIN share history loads independently without applying the token multiplier', async () => {
  let release!: () => void; const historyGate = new Promise<void>(resolve => { release = resolve; });
  const calls: { provider: string; path: string; query?: Record<string, string> }[] = [];
  const http: GetJson = async (provider, path, query) => {
    calls.push({ provider, path, query });
    if (provider === 'robinhood') return providerHttp()(provider, path);
    if (provider === 'yahoo' && path.endsWith('/search')) {
      assert.equal(query!.q, asset.isin);
      return { quotes: [{ symbol: 'MSFT', exchange: 'NMS', quoteType: 'EQUITY', isYahooFinance: true }] };
    }
    if (provider === 'yahoo') { await historyGate; return yahooHistory(); }
    throw new Error('Unrelated provider unavailable');
  };
  const service = new LiveMarketService(http, () => NOW);
  try {
    await service.prewarm();
    assert.equal(service.snapshot().stocks[0]!.quote.price, '227.43086449063826594081');
    const featured = await service.featuredCatalog('robinhood');
    assert.deepEqual(featured.stocks.map(stock => stock.symbol), ['MSFT']);
    assert.equal(featured.providers.length, 4);
    assert.equal(service.metrics.robinhoodHistoryPrewarms, 0, 'catalog readiness does not await Yahoo history');
    release(); await until(() => service.metrics.robinhoodHistoryPrewarms === 1);
    const chart = await service.chart(stockId, 'ONE_DAY');
    assert.equal(chart.status, 'ok'); assert.equal(chart.basis, 'underlying_share_reference');
    assert.deepEqual(chart.points.map(point => point.price), ['225.123456789', '226.5', '227.75']);
    assert.match(chart.statusReason!, /current token multiplier is not applied/);
    assert.equal(calls.filter(call => call.provider === 'yahoo' && call.path.endsWith('/search')).length, 1);
    assert.equal(calls.filter(call => call.provider === 'yahoo' && call.path.includes('/chart/')).length, 1);
    assert.equal(service.snapshot().stocks[0]!.quote.basis, 'provider_indicative_token');
    assert.equal(service.snapshot().stocks[0]!.activity.volume24h, null);
  } finally { release(); service.stop(); }
});

test('startup has a bounded wait and featured route neither joins slow unrelated catalogs nor returns invented rows', async () => {
  let release!: (value: unknown) => void;
  const pending = new Promise<unknown>(resolve => { release = resolve; });
  const http: GetJson = async provider => provider === 'robinhood' ? pending : Promise.reject(new Error('Unrelated provider unavailable'));
  const cold = new LiveMarketService(http, () => NOW);
  try {
    const started = performance.now(); await cold.prewarm(15);
    assert.ok(performance.now() - started < 1_000); assert.equal(cold.snapshot().stocks.length, 0);
  } finally { release({ assets: [] }); cold.stop(); }
  const stock = (await loadRobinhood(providerHttp(), () => NOW)).stocks[0]!;
  const catalog: Catalog = { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'robinhood', status: 'ok' }], stocks: [stock] };
  let fullCatalogCalls = 0;
  const app = await buildApp({ catalog: async () => { fullCatalogCalls++; throw new Error('Do not join the full catalog'); },
    chart: async () => { throw new Error('unused'); }, featuredCatalog: async () => catalog }, false, { now: () => NOW });
  try {
    const response = await app.inject('/v1/stocks/featured?provider=robinhood');
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json().stocks.map((item: Stock) => item.symbol), ['MSFT']);
    assert.equal(response.json().stocks[0].robinhoodReference.currentMultiplier, asset.currentMultiplier);
    assert.equal(fullCatalogCalls, 0);
    assert.equal((await app.inject('/v1/stocks/featured?provider=backed')).statusCode, 400);
  } finally { await app.close(); }
});

test('only the exact reviewed GOOGL Class A and TSM NYSE ADS identities can bypass an empty ISIN search', async () => {
  for (const listing of [{ symbol: 'GOOGL', isin: 'US02079K3059', exchange: 'NMS', fullExchange: 'NasdaqGS' },
    { symbol: 'TSM', isin: 'US8740391003', exchange: 'NYQ', fullExchange: 'NYSE' }]) {
    const product = { ...asset, tokenSymbol: listing.symbol, tokenName: `${listing.symbol} • Robinhood Token`, isin: listing.isin };
    const price = { ...quote, tokenSymbol: listing.symbol };
    const stock = (await loadRobinhood(providerHttp([product], [price]), () => NOW)).stocks[0]!;
    assert.equal(reviewedRobinhoodHistorySymbol(stock), listing.symbol);
    const history = yahooHistory(listing.symbol);
    history.chart.result[0]!.meta = { ...history.chart.result[0]!.meta, exchangeName: listing.exchange,
      fullExchangeName: listing.fullExchange } as typeof history.chart.result[0]['meta'];
    assert.equal(matchesReviewedRobinhoodHistory(history, stock), true);
    for (const badStock of [
      { ...stock, symbol: listing.symbol === 'GOOGL' ? 'GOOG' : '2330' },
      { ...stock, underlying: { ...stock.underlying, isin: 'US02079K1079' } },
      { ...stock, underlying: { ...stock.underlying, listingCountry: 'TW' } },
      { ...stock, deployments: [{ ...stock.deployments[0]!, chainId: 1 }] },
      { ...stock, provider: 'backed' as const },
    ]) assert.equal(reviewedRobinhoodHistorySymbol(badStock), null);
    for (const badMeta of [
      { symbol: listing.symbol === 'GOOGL' ? 'GOOG' : '2330.TW' }, { currency: 'TWD' }, { instrumentType: 'ETF' },
      { exchangeName: 'TAI' }, { fullExchangeName: 'Taiwan' }, { exchangeName: undefined, fullExchangeName: undefined },
    ]) assert.equal(matchesReviewedRobinhoodHistory({ chart: { result: [{ ...history.chart.result[0],
      meta: { ...history.chart.result[0]!.meta, ...badMeta } }] } }, stock), false);
    const http: GetJson = async (provider, path) => {
      if (provider === 'robinhood') return providerHttp([product], [price])(provider, path);
      if (provider === 'yahoo') return path.endsWith('/search') ? { quotes: [] } : history;
      throw new Error('Unrelated provider unavailable');
    };
    const service = new LiveMarketService(http, () => NOW);
    try {
      await service.prewarm(); const chart = await service.chart(stock.id, 'ONE_DAY');
      assert.equal(chart.status, 'ok'); assert.equal(chart.basis, 'underlying_share_reference');
      assert.deepEqual(chart.points.map(point => point.price), ['225.123456789', '226.5', '227.75']);
    } finally { service.stop(); }
  }
  const stock = (await loadRobinhood(providerHttp(), () => NOW)).stocks[0]!;
  assert.equal(reviewedRobinhoodHistorySymbol(stock), null);
});

test('TSM ADS units are disclosed only for the exact Robinhood NYSE underlying identity', async () => {
  const tsm = { ...asset, tokenSymbol: 'TSM', tokenName: 'TSMC • Robinhood Token', isin: 'US8740391003' };
  const tsmQuote = { ...quote, tokenSymbol: 'TSM' };
  const stock = (await loadRobinhood(providerHttp([tsm], [tsmQuote]), () => NOW)).stocks[0]!;
  assert.match(stock.description!, /NYSE-listed American depositary share \(ADS\); each ADS represents five Taiwan ordinary shares/);
  assert.ok(stock.description!.startsWith(featuredCompanySummary('TSM')!));
  assert.doesNotMatch(featuredCompanySummary('TSM')!, /ADS|five Taiwan/);
  for (const product of [{ ...tsm, isin: 'US5949181045' }, { ...asset, isin: tsm.isin }]) {
    const other = (await loadRobinhood(providerHttp([product], [{ ...quote, tokenSymbol: product.tokenSymbol }]), () => NOW)).stocks[0]!;
    assert.doesNotMatch(other.description!, /NYSE-listed American depositary share|five Taiwan/);
  }
});

test('legacy market service includes Robinhood provider status and does not fabricate Backpack candles for it', async () => {
  const http: GetJson = async (provider, path) => {
    if (provider === 'robinhood') return providerHttp()(provider, path);
    throw new Error('Unrelated provider unavailable');
  };
  const service = new MarketDataService(http, () => NOW);
  const catalog = CatalogSchema.parse(await service.catalog());
  assert.equal(catalog.providers.length, 4);
  assert.equal(catalog.providers.find(provider => provider.id === 'robinhood')!.status, 'ok');
  assert.equal((await service.chart(stockId, 'ONE_DAY')).status, 'unsupported');
});

test('a malformed Robinhood metadata refresh retains the previous dated live and legacy catalogs', async () => {
  let clock = NOW; let malformed = false;
  const http: GetJson = async (provider, path) => {
    if (provider !== 'robinhood') throw new Error('Unrelated provider unavailable');
    return path === '/rhj/assets' ? { assets: [malformed ? { ...asset, tokenDecimals: undefined } : asset] } : { quotes: [quote] };
  };
  const legacy = new MarketDataService(http, () => clock);
  const service = new LiveMarketService(http, () => clock);
  const control = service as unknown as { refreshMetadata(id: 'robinhood'): Promise<void> };
  try {
    await legacy.catalog(); await service.catalog(); await control.refreshMetadata('robinhood');
    malformed = true; clock += 30_001;
    await control.refreshMetadata('robinhood');
    for (const catalog of [service.snapshot(), await legacy.catalog()]) {
      assert.equal(catalog.stocks.length, 1);
      assert.equal(catalog.stocks[0]!.quote.receivedAt, new Date(NOW).toISOString());
      assert.equal(catalog.providers.find(row => row.id === 'robinhood')!.status, 'stale');
    }
  } finally { service.stop(); }
});

test('Robinhood purchase options and BUY/SELL quotes explicitly reject unsupported trading before RPC/router access', async () => {
  const stock = (await loadRobinhood(providerHttp(), () => NOW)).stocks[0]!;
  const catalog: Catalog = { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'robinhood', status: 'ok' }], stocks: [stock] };
  const unexpected = new Proxy({}, { get() { throw new Error('Robinhood admission must not query chain balances or routes'); } });
  const service = new PurchaseService(async () => catalog, unexpected as PurchaseChainReader, unexpected as PurchaseRouter,
    undefined, () => NOW, true);
  const principal = { subject: 'did:privy:test-user', sessionId: 'test-session' };
  const wallets: PurchaseWallet[] = [{ chain: 'SOLANA', address: '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J' }];
  for (const side of ['BUY', 'SELL'] as const) {
    const options = PurchaseOptionsResponseSchema.parse(await service.options(principal, { schemaVersion: 1, stockId, wallets, side }));
    assert.equal(options.purchasable, false); assert.equal(options.executionEnabled, false);
    assert.deepEqual(options.paymentAssets, []); assert.deepEqual(options.destinations, []);
    assert.match(options.reason!, /available for browsing/);
    const request = PurchaseQuoteRequestSchema.parse({ schemaVersion: 1, operationId: '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc',
      stockId, side, wallets, fromAssetId: 'SOLANA:SOL', destinationId: 'SOLANA:Vote111111111111111111111111111111111111111',
      amountBaseUnits: '1', slippageBps: 100 });
    await assert.rejects(service.quote(principal, request), (error: unknown) => error instanceof PurchaseError && error.code === 'stock_not_purchasable');
  }
});

test('Robinhood stock detail route and socket subscriptions accept stable provider IDs and retain chain metadata', async () => {
  const stock: Stock = (await loadRobinhood(providerHttp(), () => NOW)).stocks[0]!;
  const catalog: Catalog = { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'robinhood', status: 'ok' }], stocks: [stock] };
  let interest: string[] = [];
  const app = await buildApp({ catalog: async () => catalog, setInterest: ids => { interest = [...ids]; },
    chart: async (id, range) => ({ stockId: id, range, status: 'unsupported', basis: stock.quote.basis,
      currency: 'USD', receivedAt: new Date(NOW).toISOString(), points: [] }) }, false, { now: () => NOW });
  await app.listen({ host: '127.0.0.1', port: 0 });
  const frames: any[] = [];
  const ws = new WebSocket(`ws://127.0.0.1:${(app.server.address() as { port: number }).port}/v1/market/ws`);
  ws.on('message', data => frames.push(JSON.parse(data.toString())));
  try {
    const response = await app.inject(`/v1/stocks/${stockId}/charts?range=ONE_DAY`);
    assert.equal(response.statusCode, 200); assert.equal(response.json().status, 'unsupported');
    await until(() => frames.some(frame => frame.type === 'snapshot'));
    assert.equal(frames.find(frame => frame.type === 'snapshot').catalog.stocks[0].deployments[0].chainId, 4663);
    ws.send(JSON.stringify({ type: 'subscribe', ids: [stockId], detail: { id: stockId, range: 'ONE_DAY' } }));
    await until(() => interest.includes(stockId));
    await until(() => frames.some(frame => frame.type === 'chart'));
    assert.equal(ws.readyState, WebSocket.OPEN);
  } finally { ws.terminate(); await app.close(); }
});

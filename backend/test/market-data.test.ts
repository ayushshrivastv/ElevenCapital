import assert from 'node:assert/strict';
import test from 'node:test';
import { AsyncCache, limitConcurrency } from '../src/cache.js';
import { classifyBacked, classifyBackpack, loadBacked, loadBackpack, percentagePoints } from '../src/providers.js';
import { Registry } from '../src/registry.js';
import { parseProviderJson, type GetJson } from '../src/http.js';
import { MarketDataService, candleRequest, normalizeCandles } from '../src/service.js';
import { buildApp } from '../src/app.js';
const msft = Registry.find(entry => entry.symbol === 'MSFT')!;
const now = Date.parse('2026-09-18T12:00:00.000Z');
const asset = {
  id: msft.backed.assetId, name: 'Microsoft xStock', symbol: 'MSFTx', isin: msft.backed.productIsin,
  description: 'Microsoft xStock', logo: 'https://example.com/msft.png', underlying: { symbol: 'MSFT', isin: msft.isin, type: null, currency: 'USD', listingCountry: 'US' },
  deployments: [{ network: 'Solana', address: 'backed-contract' }],
};
const security = { asset: 'MSFT.US', cusip: msft.cusip, name: 'Microsoft Corporation' };
const upstreamAsset = { symbol: 'MSFT.US', tokens: [{ blockchain: 'Solana', contractAddress: 'backpack-contract', nativeDecimals: '6', depositEnabled: false, withdrawEnabled: false }] };
const ticker = { symbol: 'MSFT.US_USDC', lastPrice: '497.200000000000000001', priceChange: '2.5', priceChangePercent: '0.005', volume: '1000.1234567', quoteVolume: '400000.2' };
const preMint = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const preStock = { name: 'OpenAI', symbol: 'OPENAI', description: 'Tokenized pre-IPO exposure', image: 'https://prestocks.com/openai.png',
  external_url: 'https://prestocks.com/products/openai', contract_address: preMint, markPrice: '500', markValuation: '1000000000',
  tokenPrice: '10.123456789012345678', impliedValuation: '1012345678', supply: '100000000' };
const candles = [{ start: '2026-09-18 11:59:00', close: '497.12345678901234567890' }];
function mockHttp(calls: { provider: string; path: string; query: unknown }[] = []): GetJson {
  return async (provider, path, query) => {
    calls.push({ provider, path, query });
    if (provider === 'prestocks') return [preStock];
    if (provider === 'backed') {
      if (path.endsWith('/price-data')) { assert.equal(path, '/api/v2/public/assets/MSFTx/price-data'); return { quote: '497.200000000000000001' }; }
      return { nodes: [asset], page: { currentPage: '0', hasNextPage: false } };
    }
    if (path.endsWith('/securities')) return [security];
    if (path.endsWith('/assets')) return [upstreamAsset];
    if (path.endsWith('/tickers')) return [ticker];
    if (path.endsWith('/klines')) return candles;
    throw new Error('Unexpected upstream path');
  };
}
test('reviewed identities fail closed on conflicts; approved equity ETFs can be classified dynamically', () => {
  assert.equal(classifyBacked(asset)?.symbol, 'MSFT');
  assert.equal(classifyBacked({ ...asset, id: 'imposter-id' }), undefined);
  assert.equal(classifyBacked({ ...asset, isin: 'wrong-product' }), undefined);
  assert.equal(classifyBacked({ ...asset, underlying: { ...asset.underlying, isin: 'US0000000000' } }), undefined);
  assert.equal(classifyBacked({ ...asset, underlying: { ...asset.underlying, type: 'ETF' } }), undefined);
  assert.equal(classifyBacked({ ...asset, underlying: { ...asset.underlying, currency: 'EUR' } }), undefined);
  assert.equal(classifyBacked({ ...asset, underlying: { ...asset.underlying, listingCountry: 'CA' } }), undefined);
  assert.equal(classifyBacked({ ...asset, underlying: null }), undefined);
  assert.equal(classifyBackpack(security)?.symbol, 'MSFT');
  assert.equal(classifyBackpack({ ...security, cusip: 'WRONG0000' }), undefined);
  assert.equal(classifyBackpack({ asset: 'SPY.US', cusip: '78462F103', name: 'SPDR S&P 500 ETF' })?.classification, 'equity_etf');
});
test('lossless raw numeric prices and exact fraction-to-percentage conversion', () => {
  assert.deepEqual(parseProviderJson('{"quote":497.12345678901234567890}'), { quote: '497.12345678901234567890' });
  assert.equal(percentagePoints('0.005045'), '0.5045');
  assert.equal(percentagePoints('-0.01234567890123456789'), '-1.234567890123456789');
  assert.equal(percentagePoints('0'), '0');
  assert.equal(percentagePoints('0.12345678901234567890123456789'), '12.345678901234567890123456789');
});
test('provider products retain separate identity, quote currencies, contract addresses and unknown timestamp', async () => {
  const service = new MarketDataService(mockHttp(), () => now);
  const result = await service.catalog();
  assert.equal(result.stocks.length, 3);
  assert.deepEqual(result.providers.map(p => p.status), ['ok', 'ok', 'ok']);
  const [backed, backpack, prestocks] = result.stocks;
  assert.equal(backed!.id, `backed:${msft.backed.assetId}`);
  assert.equal(backpack!.id, 'backpack:MSFT.US');
  assert.equal(backed!.quote.currency, 'USD');
  assert.equal(backpack!.quote.currency, 'USDC');
  assert.equal(backed!.quote.currencyBasis, 'underlying_metadata');
  assert.equal(backpack!.quote.currencyBasis, 'market_symbol');
  assert.equal(backed!.quote.price, ticker.lastPrice);
  assert.equal(backpack!.quote.changePercent, '0.5');
  assert.equal(backpack!.volume24h, ticker.quoteVolume);
  assert.equal(backpack!.volume24hBasis, 'quote_currency_turnover');
  assert.equal(backed!.volume24hBasis, null);
  assert.notEqual(backpack!.volume24h, ticker.volume);
  assert.equal(backed!.quote.changePercent, null);
  assert.equal(backpack!.deployments[0]!.depositEnabled, false);
  assert.equal(backed!.deployments[0]!.decimals, null);
  assert.equal(backed!.quote.asOf, null);
  assert.equal(backpack!.quote.asOf, null);
  assert.equal(backed!.trading.enabled, false);
  assert.equal(backpack!.trading.enabled, false);
  assert.equal(prestocks!.id, `prestocks:${preMint}`);
  assert.equal(prestocks!.quote.price, preStock.tokenPrice);
  assert.equal(prestocks!.quote.changePercent, null);
  assert.equal(prestocks!.activity.volume24h, null);
  assert.equal(prestocks!.deployments[0]!.address, preMint);
  assert.match(backed!.description!, /Microsoft develops software/);
  assert.match(backed!.description!, /collateralized 1:1/);
  assert.match(backed!.description!, /not shareholder voting rights/);
  assert.equal(backed!.informationUrl, null,'xStocks does not publish a provider-owned product page URL in its asset record');
  assert.match(backpack!.description!, /Microsoft develops software/);
  assert.match(backpack!.description!, /security entitlements/);
  assert.match(backpack!.description!, /Where supported/);
  assert.equal(backpack!.informationUrl, null);
  assert.equal(prestocks!.description, preStock.description);
  assert.equal(prestocks!.informationUrl, preStock.external_url);
});
test('missing quotes remain null and explicitly partial; no default zero price', async () => {
  const http: GetJson = async (provider, path, query) => {
    if (path.endsWith('/price-data')) throw new Error('SECRET_INTERNAL_PROVIDER_ERROR');
    if (path.endsWith('/tickers')) return [];
    return mockHttp()(provider, path, query);
  };
  const service = new MarketDataService(http, () => now);
  const result = await service.catalog();
  assert.deepEqual(result.providers.map(p => p.status), ['unavailable', 'unavailable', 'ok']);
  assert.equal(result.stocks.length, 3);
  for (const stock of result.stocks.filter(stock => stock.provider !== 'prestocks')) {
    assert.equal(stock.quote.price, null);
    assert.equal(stock.quote.receivedAt, null);
  }
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});
test('catalog follows zero-indexed pages, does not confuse ETFs and new reviewed entries require no UI switch', async () => {
  const calls: string[] = [];
  const http: GetJson = async (_provider, path, query) => {
    if (path.endsWith('/price-data')) return { quote: '497.2' };
    calls.push(query!.page!);
    return query!.page === '0'
      ? { nodes: [{ ...asset, id: 'conflicting-provider-entry', underlying: { ...asset.underlying, type: 'ETF' } }], page: { currentPage: '0', hasNextPage: true } }
      : { nodes: [asset], page: { currentPage: '1', hasNextPage: false } };
  };
  const result = await loadBacked(http, () => now);
  assert.deepEqual(calls, ['0', '1']);
  assert.equal(result.stocks.length, 1);
  const extra = { ...msft, symbol: 'NEW', isin: 'US1111111111', cusip: '111111111', backed: { ...msft.backed, assetId: 'new-id', symbol: 'NEWx', productIsin: 'new-product' }, backpack: { assetId: 'NEW.US', cusip: '111111111' } };
  assert.equal(classifyBacked({ ...asset, id: 'new-id', symbol: 'NEWx', isin: 'new-product', underlying: { ...asset.underlying, symbol: 'NEW', isin: extra.isin } }, [extra])?.symbol, 'NEW');
});
test('TTL caches deduplicate concurrent calls, preserve fetch time on failure, then expire stale data', async () => {
  let clock = 1000;
  let loads = 0;
  const cache = new AsyncCache<number>(60, 300, () => clock, 10);
  const success = async () => { loads++; await new Promise(resolve => setTimeout(resolve, 5)); return 7; };
  const values = await Promise.all([cache.get(success), cache.get(success), cache.get(success)]);
  assert.equal(loads, 1);
  assert.deepEqual(values.map(v => v.value), [7, 7, 7]);
  clock += 59;
  await cache.get(success);
  assert.equal(loads, 1);
  clock += 2;
  const failure = async () => { loads++; throw new Error('Failed'); };
  const stale = await cache.get(failure);
  assert.equal(stale.status, 'stale');
  assert.equal(stale.receivedAt, new Date(1000).toISOString());
  await cache.get(failure);
  assert.equal(loads, 2);
  clock = 1400;
  assert.equal((await cache.get(failure)).status, 'unavailable');
  assert.equal((await cache.get(failure)).value, undefined);
});
test('global work limiter respects maximum concurrency', async () => {
  const limit = limitConcurrency(2);
  let active = 0; let max = 0;
  await Promise.all(Array.from({ length: 10 }, () => limit(async () => {
    active++; max = Math.max(max, active);
    await new Promise(resolve => setTimeout(resolve, 1)); active--;
  })));
  assert.equal(max, 2);
});
test('provider outage has no fixture fallback and health is immediate without upstream access', async () => {
  const service = new MarketDataService(async () => { throw new Error('provider_failure'); }, () => now);
  const result = await service.catalog();
  assert.equal(result.stocks.length, 0);
  assert.deepEqual(result.providers.map(p => p.status), ['unavailable', 'unavailable', 'unavailable']);
  let invoked = false;
  const app = await buildApp(new MarketDataService(async () => { invoked = true; throw new Error('never'); }));
  const health = await app.inject({ method: 'GET', url: '/health' });
  assert.equal(health.statusCode, 200);
  assert.equal(invoked, false);
  await app.close();
});
test('Backed history unsupported, Backpack chart preserves own currency and UTC candle precision', async () => {
  const calls: { provider: string; path: string; query: unknown }[] = [];
  const service = new MarketDataService(mockHttp(calls), () => now);
  const backed = await service.chart(`backed:${msft.backed.assetId}`, 'ONE_DAY');
  assert.equal(backed.status, 'unsupported');
  assert.deepEqual(backed.points, []);
  assert.equal(calls.filter(c => c.path.endsWith('/klines')).length, 0);
  const backpack = await service.chart('backpack:MSFT.US', 'ONE_DAY');
  assert.equal(backpack.currency, 'USDC');
  assert.equal(backpack.basis, 'external_reference_non_executable');
  assert.deepEqual(backpack.points, [{ timestamp: '2026-09-18T11:59:00.000Z', price: candles[0]!.close }]);
  await service.chart('backpack:MSFT.US', 'ONE_DAY');
  assert.equal(calls.filter(c => c.path.endsWith('/klines')).length, 1);
  assert.equal(candleRequest('ONE_DAY', now).startTime, String(now / 1000 - 86400));
  assert.equal(candleRequest('YEAR_TO_DATE', now).startTime, String(Date.UTC(2026, 0, 1) / 1000));
  assert.throws(() => normalizeCandles([{ start: '09/18/2026', close: '1' }]));
});
test('HTTP validates ranges/identities, rejects URL injection and exposes no trading or credential routes', async () => {
  const app = await buildApp(new MarketDataService(mockHttp(), () => now));
  const response = await app.inject({ method: 'GET', url: '/v1/stocks' });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().stocks.length, 3);
  assert.equal(response.headers['cache-control'], 'no-store');
  for (const url of ['/v1/stocks/backpack%3AMSFT.US/charts?range=INVALID', '/v1/stocks/backpack%3AMSFT.US/charts', '/v1/stocks?url=https://evil.example']) {
    assert.equal((await app.inject({ method: 'GET', url })).statusCode, 400, url);
  }
  assert.equal((await app.inject({ method: 'GET', url: '/v1/stocks/backpack%3AUNKNOWN/charts?range=ONE_DAY' })).statusCode, 404);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/stocks/backpack%3AMSFT.US/charts?range=ONE_DAY' })).statusCode, 200);
  assert.equal((await app.inject({ method: 'GET', url: `/v1/stocks/prestocks%3A${preMint}/charts?range=ONE_DAY` })).statusCode, 200);
  for (const url of ['/v1/orders', '/v1/wallets', '/v1/transactions/sign', '/api/v1/rfq', '/v1/credentials', '/v1/stocks']) {
    assert.equal((await app.inject({ method: 'POST', url, payload: {} })).statusCode, 404, url);
  }
  await app.close();
});
test('service exposes provider stale state and original quote timestamps after upstream failure', async () => {
  let clock = now; let fail = false;
  const upstream = mockHttp();
  const http: GetJson = (...args) => fail ? Promise.reject(new Error('outage')) : upstream(...args);
  const service = new MarketDataService(http, () => clock, 60_000);
  const first = await service.catalog();
  fail = true; clock += 61_000;
  const stale = await service.catalog();
  assert.deepEqual(stale.providers.map(p => p.status), ['stale', 'stale', 'stale']);
  assert.equal(stale.stocks[0]!.quote.receivedAt, first.stocks[0]!.quote.receivedAt);
  clock += 300_000;
  const expired = await service.catalog();
  assert.equal(expired.stocks.length, 0);
});
test('partial quote failures retain the same provider instrument briefly, recover automatically and never renew its age', async () => {
  let clock = now; let missing = false;
  const original = mockHttp();
  const http: GetJson = async (provider, path, query) => {
    if (missing && path.endsWith('/price-data')) throw new Error('temporary single-instrument outage');
    return original(provider, path, query);
  };
  const service = new MarketDataService(http, () => clock, 15_000);
  const first = await service.catalog();
  const firstBacked = first.stocks.find(stock => stock.provider === 'backed')!;
  missing = true; clock += 20_000;
  const retained = await service.catalog();
  assert.equal(retained.providers.find(provider => provider.id === 'backed')!.status, 'stale');
  assert.deepEqual(retained.stocks.find(stock => stock.provider === 'backed')!.quote, firstBacked.quote);
  assert.equal(retained.providers.find(provider => provider.id === 'backpack')!.status, 'ok');
  clock += 281_000;
  assert.equal((await service.catalog()).stocks.find(stock => stock.provider === 'backed')!.quote.price, null);
  missing = false; clock += 20_000;
  const recovered = await service.catalog();
  assert.equal(recovered.providers.find(provider => provider.id === 'backed')!.status, 'ok');
  assert.equal(recovered.stocks.find(stock => stock.provider === 'backed')!.quote.receivedAt, new Date(clock).toISOString());
});
test('faster public quote refreshes do not increase the separate sixty-second Jupiter analytics cadence', async () => {
  let clock = now; let quotes = 0; let analytics = 0;
  const mint = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
  const original = mockHttp();
  const http: GetJson = async (provider, path, query) => {
    if (provider === 'jupiter') {
      analytics++;
      return [{ id: mint, updatedAt: new Date(clock).toISOString(), mcap: '2000', liquidity: '1000', holderCount: '100', organicScore: '70' }];
    }
    if (provider === 'backed' && path === '/api/v2/public/assets') {
      return { nodes: [{ ...asset, deployments: [{ network: 'Solana', address: mint }] }], page: { currentPage: '0', hasNextPage: false } };
    }
    if (path.endsWith('/price-data')) quotes++;
    return original(provider, path, query);
  };
  const service = new MarketDataService(http, () => clock);
  await service.catalog();
  clock += 20_000; await service.catalog();
  assert.equal(quotes, 2);
  assert.equal(analytics, 1);
  clock += 41_000; await service.catalog();
  assert.equal(analytics, 2);
});
test('completed catalog timestamp never precedes quote or metric receipt during asynchronous cold loads and refreshes', async () => {
  let clock = now;
  const mint = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
  const original = mockHttp();
  const http: GetJson = async (provider, path, query) => {
    if (provider === 'jupiter') {
      await Promise.resolve();
      clock += 5_000;
      return [{ id: mint, mcap: '52530178.417716764', liquidity: '553947.5452896208', holderCount: '23407',
        organicScore: '77.27969317595814', updatedAt: new Date(clock - 1_000).toISOString() }];
    }
    if (provider === 'backed' && path === '/api/v2/public/assets') {
      return { nodes: [{ ...asset, deployments: [{ network: 'Solana', address: mint }] }],
        page: { currentPage: '0', hasNextPage: false } };
    }
    clock += 100;
    return original(provider, path, query);
  };
  const service = new MarketDataService(http, () => clock);
  for (let pass = 0; pass < 2; pass++) {
    const catalog = await service.catalog();
    const receivedAt = Date.parse(catalog.receivedAt);
    const backed = catalog.stocks.find(stock => stock.provider === 'backed')!;
    assert.equal(backed.statistics.marketCapitalization.status, 'available');
    for (const stock of catalog.stocks) {
      if (stock.quote.receivedAt) assert.ok(receivedAt >= Date.parse(stock.quote.receivedAt));
      for (const name of ['marketCapitalization', 'liquidity', 'holderCount', 'organicScore'] as const) {
        const metric = stock.statistics[name];
        if (metric.receivedAt) assert.ok(receivedAt >= Date.parse(metric.receivedAt), `${name} cannot appear future-dated to the client`);
      }
    }
    clock += 61_000;
  }
});

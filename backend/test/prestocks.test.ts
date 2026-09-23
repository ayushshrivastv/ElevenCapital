import assert from 'node:assert/strict';
import test from 'node:test';
import { loadPreStocks } from '../src/providers.js';
import { getJson, type GetJson } from '../src/http.js';
import { TokenStatisticsService } from '../src/statistics.js';
import { LiveMarketService } from '../src/live-service.js';
import { WalletPortfolioService } from '../src/portfolio.js';
import type { Catalog, Stock } from '../src/schema.js';
import type { PortfolioUpstream } from '../src/portfolio-upstream.js';

const NOW = Date.parse('2026-09-22T10:00:00.000Z');
const MINT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const OTHER_MINT = 'So11111111111111111111111111111111111111112';
const product = {
  name: 'OpenAI', symbol: 'OPENAI', description: 'Tokenized pre-IPO exposure',
  image: 'https://prestocks.com/images/openai.png', external_url: 'https://prestocks.com/products/openai',
  contract_address: MINT, markPrice: '500.000000000000000001', markValuation: '1000000000',
  tokenPrice: '10.123456789012345678', impliedValuation: '1012345678.901', supply: '100000000',
};

function providerHttp(rows: unknown[]): GetJson {
  return async (provider, path, query) => {
    assert.equal(provider, 'prestocks'); assert.equal(path, '/api/prestocks'); assert.deepEqual(query, undefined);
    return rows;
  };
}

test('PreStocks transport permits only one exact bare/www canonical redirect', async () => {
  const original = globalThis.fetch; const requested: string[] = [];
  try {
    globalThis.fetch = (async input => {
      const url = String(input); requested.push(url);
      if (requested.length === 1) return new Response(null, { status: 308, headers: { location: 'https://www.prestocks.com/api/prestocks' } });
      return new Response(JSON.stringify([product]), { status: 200, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;
    assert.deepEqual(await getJson('prestocks', '/api/prestocks'), [product]);
    assert.deepEqual(requested, ['https://prestocks.com/api/prestocks', 'https://www.prestocks.com/api/prestocks']);
    requested.length = 0;
    globalThis.fetch = (async () => new Response(null, { status: 302, headers: { location: 'https://evil.example/api/prestocks' } })) as typeof fetch;
    await assert.rejects(getJson('prestocks', '/api/prestocks'), /Invalid PreStocks redirect/);
    await assert.rejects(getJson('prestocks', '/api/other'), /Invalid PreStocks request/);
  } finally { globalThis.fetch = original; }
});

test('PreStocks official rows retain exact mint identity and only publish fields the provider actually supplies', async () => {
  const snapshot = await loadPreStocks(providerHttp([product]), () => NOW);
  assert.equal(snapshot.partial, false);
  assert.deepEqual(snapshot.reconciliation, { sourceCount: 1, includedIds: [`prestocks:${MINT}`], excluded: [], unresolved: [],
    classificationAsOf: new Date(NOW).toISOString() });
  const stock = snapshot.stocks[0]!;
  assert.equal(stock.id, `prestocks:${MINT}`); assert.equal(stock.providerAssetId, MINT);
  assert.equal(stock.provider, 'prestocks'); assert.equal(stock.providerLabel, 'PreStocks · Pre-IPO');
  assert.equal(stock.quote.price, product.tokenPrice); assert.equal(stock.quote.basis, 'provider_indicative_token');
  assert.equal(stock.quote.currency, 'USD'); assert.equal(stock.quote.currencyBasis, 'quoted_currency');
  assert.equal(stock.quote.changeAmount, null); assert.equal(stock.quote.changePercent, null);
  assert.equal(stock.description, product.description); assert.equal(stock.informationUrl, product.external_url);
  assert.equal(stock.volume24h, null); assert.equal(stock.activity.volume24h, null); assert.equal(stock.activity.netVolume24h, null);
  assert.deepEqual(stock.deployments, [{ network: 'Solana', address: MINT, decimals: null, depositEnabled: null, withdrawEnabled: null }]);
  assert.equal(stock.statistics.marketCapitalization.value, null);
  assert.equal(stock.trading.enabled, false);
});

test('PreStocks does not publish an unverified or non-HTTPS product link', async () => {
  for (const external_url of ['http://prestocks.com/products/openai', 'https://user@prestocks.com/products/openai']) {
    await assert.rejects(loadPreStocks(providerHttp([{ ...product, external_url }]), () => NOW), /Incomplete PreStocks product metadata/);
  }
});

test('PreStocks keeps valid products visible when price is unusable and rejects incomplete metadata', async () => {
  const missingPrice = await loadPreStocks(providerHttp([{ ...product, tokenPrice: '0' }]), () => NOW);
  assert.equal(missingPrice.stocks.length, 1); assert.equal(missingPrice.stocks[0]!.quote.price, null);
  assert.equal(missingPrice.stocks[0]!.quote.receivedAt, null); assert.equal(missingPrice.partial, true);
  await assert.rejects(loadPreStocks(providerHttp([product, { ...product, contract_address: OTHER_MINT }]), () => NOW),
    /Incomplete PreStocks product metadata/);
});

test('an incomplete PreStocks refresh retains the last fully parsed mint directory', async () => {
  let rows: unknown[] = [product];
  const http: GetJson = async provider => {
    if (provider === 'prestocks') return rows;
    throw new Error('Provider unavailable');
  };
  const service = new LiveMarketService(http, () => NOW);
  const control = service as unknown as { refreshMetadata(id: 'prestocks'): Promise<void> };
  try {
    await service.catalog(); await control.refreshMetadata('prestocks');
    assert.equal(service.snapshot().stocks.some(stock => stock.id === `prestocks:${MINT}`), true);
    rows = [product, { ...product, contract_address: OTHER_MINT }];
    await control.refreshMetadata('prestocks');
    assert.equal(service.snapshot().stocks.some(stock => stock.id === `prestocks:${MINT}`), true);
    assert.equal(service.snapshot().providers.find(provider => provider.id === 'prestocks')?.status, 'stale');
  } finally { service.stop(); }
});

test('PreStocks exact mint receives Jupiter price, change, volume and statistics without symbol matching', async () => {
  const stock = (await loadPreStocks(providerHttp([product]), () => NOW)).stocks[0]!;
  const http: GetJson = async (provider, path, query) => {
    assert.equal(provider, 'jupiter'); assert.equal(path, '/tokens/v2/search'); assert.equal(query!.query, MINT);
    return [
      { id: OTHER_MINT, symbol: 'OPENAI', usdPrice: '999', updatedAt: new Date(NOW).toISOString() },
      { id: MINT, usdPrice: '11.25', updatedAt: new Date(NOW).toISOString(), mcap: '1125000000', liquidity: '250000',
        holderCount: '321', organicScore: '81.5', stats24h: { priceChange: '2.5', buyVolume: '900', sellVolume: '400' } },
    ];
  };
  const enriched = (await new TokenStatisticsService(http, () => NOW).enrich([stock]))[0]!;
  assert.equal(enriched.quote.price, '11.25'); assert.equal(enriched.quote.basis, 'onchain_token_market');
  assert.equal(enriched.quote.changePercent, '2.5'); assert.equal(enriched.activity.volume24h, '1300');
  assert.equal(enriched.activity.netVolume24h, '500'); assert.equal(enriched.statistics.marketCapitalization.value, '1125000000');
  assert.equal(enriched.statistics.holderCount.value, '321');
});

test('live PreStocks chart refuses a one-point local trace when exact-mint history is unavailable and never requests Backpack candles', async () => {
  let backpackKlines = 0; let geckoRequests = 0;
  const http: GetJson = async (provider, path) => {
    if (provider === 'prestocks') return [product];
    if (provider === 'jupiter') return [];
    if (provider === 'geckoterminal') { geckoRequests++; throw new Error('No public token history'); }
    if (provider === 'backpack' && path.endsWith('/klines')) { backpackKlines++; return []; }
    throw new Error('Provider unavailable');
  };
  const service = new LiveMarketService(http, () => NOW);
  const control = service as unknown as { refreshMetadata(id: 'prestocks'): Promise<void> };
  try {
    await service.catalog(); await control.refreshMetadata('prestocks');
    const chart = await service.chart(`prestocks:${MINT}`, 'ONE_DAY');
    assert.equal(chart.status, 'unavailable'); assert.equal(chart.basis, 'provider_indicative_token'); assert.deepEqual(chart.points, []);
    assert.match(chart.statusReason!, /1 locally observed price point is insufficient/);
    assert.equal(geckoRequests, 1); assert.equal(backpackKlines, 0);
  } finally { service.stop(); }
});

test('live PreStocks chart publishes verified exact-mint GeckoTerminal OHLCV with separate token-market provenance', async () => {
  const pool = 'D6bRhQUcR9B7bPbbqgxpE17MjyUjBtr8hHQCcJoHrrv1';
  const quote = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
  let requestedToken: string | undefined;
  const http: GetJson = async (provider, path, query) => {
    if (provider === 'prestocks') return [product];
    if (provider === 'jupiter') return [];
    if (provider === 'geckoterminal' && path.endsWith('/pools')) return { data: [{ id: `solana_${pool}`, type: 'pool',
      attributes: { address: pool, reserve_in_usd: '1000', volume_usd: { h24: '500' } },
      relationships: { base_token: { data: { id: `solana_${MINT}` } }, quote_token: { data: { id: `solana_${quote}` } } } }] };
    if (provider === 'geckoterminal') {
      requestedToken = query!.token;
      const end = Math.floor(NOW / 1000);
      return { data: { type: 'ohlcv_request_response', attributes: { ohlcv_list: [
        [String(end - 1800), '10', '11', '9', '10.1', '50'], [String(end - 900), '10.1', '11', '10', '10.2', '60'],
        [String(end), '10.2', '11', '10', '10.3', '70'],
      ] } }, meta: { base: { address: MINT }, quote: { address: quote } } };
    }
    throw new Error('Provider unavailable');
  };
  const service = new LiveMarketService(http, () => NOW);
  const control = service as unknown as { refreshMetadata(id: 'prestocks'): Promise<void> };
  try {
    await service.catalog(); await control.refreshMetadata('prestocks');
    const chart = await service.chart(`prestocks:${MINT}`, 'ONE_DAY');
    assert.equal(chart.status, 'ok'); assert.equal(chart.basis, 'onchain_token_market'); assert.equal(chart.currency, 'USD');
    assert.equal(chart.points.length, 3); assert.equal(requestedToken, MINT); assert.match(chart.statusReason!, /GeckoTerminal exact-mint/);
  } finally { service.stop(); }
});

test('fresh exact-mint quotes survive provider polling, then stale Jupiter data yields to a new provider price', async () => {
  let clock = NOW; let providerPrice = '12';
  const http: GetJson = async provider => {
    if (provider === 'prestocks') return [{ ...product, tokenPrice: providerPrice }];
    if (provider === 'jupiter') return [];
    throw new Error('Provider unavailable');
  };
  const service = new LiveMarketService(http, () => clock);
  const control = service as unknown as { refreshMetadata(id: 'prestocks'): Promise<void>; store(stock: Stock): void };
  try {
    await service.catalog(); await control.refreshMetadata('prestocks');
    const source = service.snapshot().stocks.find(stock => stock.provider === 'prestocks')!;
    control.store({ ...source, quote: { ...source.quote, price: '11', receivedAt: new Date(clock).toISOString(), basis: 'onchain_token_market' } });
    providerPrice = '13'; clock += 30_000; await control.refreshMetadata('prestocks');
    assert.equal(service.snapshot().stocks.find(stock => stock.provider === 'prestocks')!.quote.price, '11');
    providerPrice = '14'; clock += 270_001; await control.refreshMetadata('prestocks');
    const fallback = service.snapshot().stocks.find(stock => stock.provider === 'prestocks')!.quote;
    assert.equal(fallback.price, '14'); assert.equal(fallback.basis, 'provider_indicative_token');
  } finally { service.stop(); }
});

test('scrolling PreStocks rows does not turn viewport subscriptions into catalog request storms', async () => {
  let clock = NOW; let requests = 0;
  const http: GetJson = async provider => {
    if (provider === 'prestocks') { requests++; return [product]; }
    if (provider === 'jupiter') return [];
    throw new Error('Provider unavailable');
  };
  const service = new LiveMarketService(http, () => clock);
  const control = service as unknown as { refreshMetadata(id: 'prestocks'): Promise<void> };
  try {
    await service.catalog(); await control.refreshMetadata('prestocks');
    assert.equal(requests, 1);
    for (let index = 0; index < 20; index++) service.setInterest([`prestocks:${MINT}`]);
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    assert.equal(requests, 1, 'repeated viewport messages inside the poll window must be coalesced');
    clock += 30_001;
    service.setInterest([`prestocks:${MINT}`]);
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    assert.equal(requests, 2, 'a visible row can refresh metadata after the poll window elapses');
  } finally { service.stop(); }
});

test('wallet portfolio dynamically discovers PreStocks mint holdings from the live catalog', async () => {
  const stock = (await loadPreStocks(providerHttp([product]), () => NOW)).stocks[0]!;
  const catalog: Catalog = { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'backed', status: 'unavailable' }, { id: 'backpack', status: 'unavailable' }, { id: 'prestocks', status: 'ok' }], stocks: [stock] };
  let sawMint = false;
  const upstream: PortfolioUpstream = {
    balances: async (_wallet, assets) => ({ complete: true, observedAt: new Date(NOW).toISOString(), balances: assets.map(asset => {
      if (asset.stockId === stock.id) sawMint = true;
      return { asset, quantity: asset.stockId === stock.id ? '2.5' : '0' };
    }) }),
    prices: async assets => new Map(assets.map(asset => [asset.key, '12'])),
  };
  const result = await new WalletPortfolioService(async () => catalog, upstream, () => NOW).portfolio({ wallets: [
    { chain: 'SOLANA', address: '11111111111111111111111111111111' },
    { chain: 'ETHEREUM', address: '0x1111111111111111111111111111111111111111' },
  ] });
  assert.equal(sawMint, true);
  assert.deepEqual(result.holdings, [{ stockId: stock.id, quantity: '2.5', valueUsd: '30' }]);
  assert.equal(result.holdingsComplete, false, 'missing legacy provider catalogs must still prevent a complete total');
});

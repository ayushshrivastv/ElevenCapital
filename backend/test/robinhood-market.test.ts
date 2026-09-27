import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeRobinhoodMarket, normalizeRobinhoodTokenQuotes, RobinhoodMarketService } from '../src/robinhood-market.js';
import { loadRobinhood } from '../src/robinhood.js';
import { createGetJson, type GetJson } from '../src/http.js';
import { StockSchema, type Stock } from '../src/schema.js';
import { LiveMarketService } from '../src/live-service.js';
import { chartMatchesStock } from '../src/market-socket.js';
import { ReviewedRobinhoodMarketPools, reviewedRobinhoodMarketPool } from '../src/robinhood-market-pools.js';

const NOW = Date.parse('2026-09-29T12:00:00Z');
const TOKEN = '0x1111111111111111111111111111111111111111';
const USDG = '0x5fc5360d0400a0fd4f2af552add042d716f1d168';
const POOL = `0x${'a'.repeat(64)}`;
const asset = { id: `0x${'b'.repeat(64)}`, tokenSymbol: 'MSFT', tokenName: 'Microsoft • Robinhood Token',
  deployments: [{ contractAddress: TOKEN, chainId: 4663, networkName: 'Robinhood Chain' }], tokenDecimals: 18,
  currentMultiplier: '1.2', isin: 'US5949181045', status: 'ASSET_STATUS_ACTIVE' };
const issuerQuote = { tokenSymbol: 'MSFT', deployments: asset.deployments, bid: '200', ask: '202', currency: 'USD',
  generatedAt: new Date(NOW - 1_000).toISOString(), isTradingHalt: false, dailyTradingVolume: '90000' };
async function stock(): Promise<Stock> {
  return (await loadRobinhood(async (_provider, path) => path === '/rhj/assets' ? { assets: [asset] } : { quotes: [issuerQuote] }, () => NOW)).stocks[0]!;
}
function pool(address = TOKEN, id = POOL) {
  return { id: `robinhood_${id}`, type: 'pool', attributes: { address: id, base_token_price_usd: '250.1234567890123456789',
    reserve_in_usd: '50000', pool_created_at: '2026-07-01T00:00:00Z', price_change_percentage: { h24: '-1.25' },
    volume_usd: { h24: '1000.1234567890123456789' }, buy_volume_usd: { h24: '400.1' },
    sell_volume_usd: { h24: '600.0234567890123456789' }, net_buy_volume_usd: { h24: '-199.9234567890123456789' } },
    relationships: { base_token: { data: { type: 'token', id: `robinhood_${address}` } },
      quote_token: { data: { type: 'token', id: `robinhood_${USDG}` } } } };
}
const tokenRow = (value: Stock, poolId = POOL) => ({ id: `robinhood_${value.deployments[0]!.address.toLowerCase()}`, type: 'token',
  attributes: { address: value.deployments[0]!.address.toLowerCase(), decimals: '18' },
  relationships: { top_pools: { data: [{ type: 'pool', id: `robinhood_${poolId}` }] } } });
const simple = (stocks: readonly Stock[]) => ({ data: { type: 'simple_token_price', attributes: {
  token_prices: Object.fromEntries(stocks.map(row => [row.deployments[0]!.address.toLowerCase(), '250.1234567890123456789'])),
  h24_price_change_percentage: Object.fromEntries(stocks.map(row => [row.deployments[0]!.address.toLowerCase(), '-2.75'])),
} } });

test('exact Robinhood USD contract prices retain their paired return without adopting pool prices or percentages', async () => {
  const value = await stock();
  const quote = normalizeRobinhoodTokenQuotes(simple([value]), [value], NOW).get(value.id)!;
  assert.equal(quote.price, '250.1234567890123456789'); assert.equal(quote.changePercent, '-2.75');
  assert.equal(quote.basis, 'onchain_token_market'); assert.equal(quote.currency, 'USD');
  assert.equal(quote.changeAmount, null); assert.equal(quote.asOf, null);
  assert.equal(quote.receivedAt, new Date(NOW).toISOString());
  const other = { ...value, deployments: [{ ...value.deployments[0]!, address: '0x2222222222222222222222222222222222222222' }] };
  assert.equal(normalizeRobinhoodTokenQuotes(simple([other]), [value], NOW).size, 0);
  assert.equal(normalizeRobinhoodTokenQuotes(simple([value]), [{ ...value, deployments: [{ ...value.deployments[0]!, chainId: 1 }] }], NOW).size, 0);
  const missing = simple([value]); missing.data.attributes.h24_price_change_percentage = {};
  assert.equal(normalizeRobinhoodTokenQuotes(missing, [value], NOW).get(value.id)!.changePercent, null);
  const invalid = simple([value]); invalid.data.attributes.token_prices[TOKEN] = '0';
  assert.equal(normalizeRobinhoodTokenQuotes(invalid, [value], NOW).size, 0);
  for (const percent of ['1e-101', '1e-1000000000', '-100', '1e1000000000']) {
    const extreme = simple([value]); extreme.data.attributes.h24_price_change_percentage[TOKEN] = percent;
    const safe = normalizeRobinhoodTokenQuotes(extreme, [value], NOW).get(value.id)!;
    assert.equal(safe.price, quote.price); assert.equal(safe.changePercent, null);
    assert.doesNotThrow(() => StockSchema.parse({ ...value, robinhoodReference: undefined, quote: safe }));
  }
  assert.throws(() => normalizeRobinhoodTokenQuotes({ data: { type: 'pool' } }, [value], NOW));
});

test('exact Robinhood spot pool derives signed net only from complete USD sides', async () => {
  const value = await stock();
  const result = normalizeRobinhoodMarket(pool(), value, NOW, new Set([USDG]));
  assert.ok(result); assert.equal(result.pool, POOL);
  assert.equal('quote' in result, false, 'pool price and denomination-ambiguous return cannot replace the USD contract quote');
  assert.equal(result.activity.source, 'GeckoTerminal'); assert.equal(result.activity.scope, 'robinhood_chain_token');
  assert.equal(result.activity.volume24h, '1000.1234567890123456789');
  assert.equal(result.activity.netVolume24h, '-199.9234567890123456789');
  assert.equal(result.activity.updatedAt, null);
  assert.equal(result.activity.receivedAt, new Date(NOW).toISOString());
  for (const attributes of [
    { ...pool().attributes, buy_volume_usd: undefined },
    { ...pool().attributes, sell_volume_usd: { h24: '-1' } },
    { ...pool().attributes, volume_usd: { h24: '500' } },
    { ...pool().attributes, net_buy_volume_usd: { h24: '199' } },
    { ...pool().attributes, net_buy_volume_usd: { h24: 'not-a-number' } },
    { ...pool().attributes, buy_volume_usd: undefined, sell_volume_usd: undefined, transactions: { h24: { buys: '40', sells: '60' } } },
  ]) {
    const incomplete = normalizeRobinhoodMarket({ ...pool(), attributes }, value, NOW, new Set([USDG]));
    assert.ok(incomplete); assert.equal(incomplete.activity.netVolume24h, null);
    assert.match(incomplete.activity.netVolumeReason!, /Complete matching/);
  }
  const zero = normalizeRobinhoodMarket({ ...pool(), attributes: { ...pool().attributes, volume_usd: { h24: '0' },
    buy_volume_usd: { h24: '0' }, sell_volume_usd: { h24: '0' }, net_buy_volume_usd: { h24: '0' } } }, value, NOW, new Set([USDG]));
  assert.equal(zero!.activity.volume24h, '0'); assert.equal(zero!.activity.netVolume24h, '0');
});

test('verified two-token quote-side activity negates validated base net while rejecting foreign counterparties and multi-token pools', async () => {
  const value = await stock();
  const original = pool();
  const quoted = { ...original, attributes: { ...original.attributes, volume_usd: { h24: '1000' },
    buy_volume_usd: { h24: '700' }, sell_volume_usd: { h24: '300' }, net_buy_volume_usd: { h24: '400' } },
    relationships: { base_token: original.relationships.quote_token, quote_token: original.relationships.base_token,
      dex: { data: { type: 'dex', id: 'uniswap-v4-robinhood' } } } };
  const result = normalizeRobinhoodMarket(quoted, value, NOW, new Set([USDG]));
  assert.equal(result?.stockSide, 'quote'); assert.equal(result?.activity.volume24h, '1000');
  assert.equal(result?.activity.netVolume24h, '-400');
  for (const invalid of [
    { ...quoted, relationships: { ...quoted.relationships, base_token: { data: { type: 'token', id: `robinhood_0x${'f'.repeat(40)}` } } } },
    { ...quoted, relationships: { ...quoted.relationships, dex: { data: { type: 'dex', id: 'unknown-protocol' } } } },
    { ...quoted, relationships: { ...quoted.relationships, quote_tokens: { data: [quoted.relationships.quote_token.data,
      { type: 'token', id: `robinhood_0x${'f'.repeat(40)}` }] } } },
    { ...quoted, attributes: { ...quoted.attributes, quote_tokens: [TOKEN, USDG] } },
  ]) assert.equal(normalizeRobinhoodMarket(invalid, value, NOW, new Set([USDG])), null);
  const mismatched = normalizeRobinhoodMarket({ ...quoted, attributes: { ...quoted.attributes,
    net_buy_volume_usd: { h24: '-400' } } }, value, NOW, new Set([USDG]));
  assert.equal(mismatched?.activity.volume24h, '1000'); assert.equal(mismatched?.activity.netVolume24h, null,
    'reported net is checked before inversion, rather than accepting a stock-oriented number as the base report');
});

test('a verified quote-side top pool loads activity in bulk without an unnecessary per-token alternative request', async () => {
  const native = await stock(), original = pool();
  const quoted = { ...original, relationships: { base_token: original.relationships.quote_token,
    quote_token: original.relationships.base_token, dex: { data: { type: 'dex', id: 'uniswap-v3-robinhood' } } } };
  let alternativeCalls = 0;
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) return simple([native]);
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [quoted] };
    if (path.endsWith('/pools')) { alternativeCalls++; throw new Error('Unnecessary alternative'); }
    return { data: [quoted] };
  };
  const service = new RobinhoodMarketService(http, () => NOW);
  await service.refresh([native], () => {});
  assert.equal(alternativeCalls, 0);
  assert.equal(service.project(native).activity.volume24h, original.attributes.volume_usd.h24);
  assert.equal(service.project(native).activity.netVolume24h, '199.9234567890123456789');
  assert.equal(service.observations()[0]!.activityStockSide, 'quote');
});

test('foreign networks, quote-side directions, ambiguous chains and invalid pool IDs cannot become token activity', async () => {
  const value = await stock(), original = pool();
  for (const malformed of [
    { ...original, id: `eth_${POOL}` },
    { ...original, type: 'perpetual' },
    { ...original, attributes: { ...original.attributes, address: `${POOL}/orders` } },
    { ...original, attributes: { ...original.attributes, reserve_in_usd: '-1' } },
    { ...original, attributes: { ...original.attributes, pool_created_at: new Date(NOW + 1).toISOString() } },
    { ...original, relationships: { ...original.relationships, base_token: original.relationships.quote_token,
      quote_token: original.relationships.base_token } },
  ]) assert.equal(normalizeRobinhoodMarket(malformed, value, NOW, new Set([USDG])), null);
  assert.equal(normalizeRobinhoodMarket(original, { ...value, deployments: [{ ...value.deployments[0]!, chainId: 1 }] }, NOW, new Set([USDG])), null);
  assert.equal(normalizeRobinhoodMarket(original, value, NOW, new Set()), null);
  const young = normalizeRobinhoodMarket({ ...original, attributes: { ...original.attributes,
    pool_created_at: new Date(NOW - 3_600_000).toISOString() } }, value, NOW, new Set([USDG]));
  assert.ok(young); assert.equal('quote' in young, false);
  const thin = normalizeRobinhoodMarket({ ...original, attributes: { ...original.attributes,
    reserve_in_usd: '0', base_token_price_usd: null } }, value, NOW, new Set([USDG]));
  assert.equal(thin?.activity.volume24h, original.attributes.volume_usd.h24,
    'valid historical USD swaps do not require current liquidity or an unused pool price');
  const nativeEth = '0x0000000000000000000000000000000000000000';
  const nativePool = { ...original, relationships: { ...original.relationships,
    quote_token: { data: { type: 'token', id: `robinhood_${nativeEth}` } } } };
  assert.equal(normalizeRobinhoodMarket(nativePool, value, NOW, new Set([nativeEth]))?.activity.volume24h, original.attributes.volume_usd.h24);
});

test('market cache preserves fetch time, removes share-reference binding, invalidates changed contracts and falls back to issuer after expiry', async () => {
  let clock = NOW, available = true;
  const native = await stock(); const calls: string[] = [];
  const http: GetJson = async (provider, path, query) => {
    assert.equal(provider, 'geckoterminal'); calls.push(path);
    if (!available) throw new Error('Unavailable');
    if (path.includes('/token_price/')) return simple([native]);
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [pool()] };
    assert.match(path, /\/pools\/multi\//); assert.equal(query!.include_volume_breakdown, 'true');
    return { data: [pool()] };
  };
  const service = new RobinhoodMarketService(http, () => clock);
  await service.refresh([native], () => {});
  const projected = StockSchema.parse(service.project(native));
  assert.equal(projected.quote.basis, 'onchain_token_market'); assert.equal(projected.robinhoodReference, undefined);
  assert.equal(native.quote.price, '241.2'); assert.ok(native.robinhoodReference, 'issuer reference remains available internally');
  assert.equal(calls.length, 3);
  const receivedAt = projected.quote.receivedAt;
  clock += 30_000; await service.refresh([native], () => {}); assert.equal(calls.length, 3);
  available = false; clock += 30_001; await service.refresh([native], () => {});
  assert.equal(service.project(native).quote.receivedAt, receivedAt, 'failure cannot retime a cached market observation');
  const other = { ...native, deployments: [{ ...native.deployments[0]!, address: '0x2222222222222222222222222222222222222222' }] };
  assert.equal(service.project(other).quote.basis, 'provider_indicative_token');
  clock += 300_001;
  assert.equal(service.project(native).quote.basis, 'provider_indicative_token');
  assert.equal(service.project(native).quote.changePercent, null);
});

test('fresh pool activity survives independent USD-price expiry without retiming the issuer quote or reference', async () => {
  let clock = NOW, priceAvailable = true;
  const native = await stock();
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) { if (!priceAvailable) throw new Error('Price feed outage'); return simple([native]); }
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [pool()] };
    return { data: [pool()] };
  };
  const service = new RobinhoodMarketService(http, () => clock);
  await service.refresh([native], () => {});
  priceAvailable = false; clock += 300_001;
  const freshIssuer = (await loadRobinhood(async (_provider, path) => path === '/rhj/assets' ? { assets: [asset] } :
    { quotes: [{ ...issuerQuote, generatedAt: new Date(clock - 1_000).toISOString() }] }, () => clock)).stocks[0]!;
  await service.refresh([freshIssuer], () => {});
  const projected = service.project(freshIssuer);
  assert.deepEqual(projected.quote, freshIssuer.quote); assert.deepEqual(projected.robinhoodReference, freshIssuer.robinhoodReference);
  assert.equal(projected.activity.source, 'GeckoTerminal'); assert.equal(projected.activity.volume24h, pool().attributes.volume_usd.h24);
  assert.equal(projected.activity.receivedAt, new Date(clock).toISOString());
  assert.equal(service.metrics.available, 0); assert.equal(service.metrics.activityAvailable, 1);
  const changed = { ...freshIssuer, deployments: [{ ...freshIssuer.deployments[0]!, address: '0x2222222222222222222222222222222222222222' }] };
  assert.deepEqual(service.project(changed), changed, 'independent activity is still bound to the exact issuer deployment');
  clock += 300_001;
  assert.deepEqual(service.project(freshIssuer), freshIssuer, 'expired activity is removed independently');
});

test('negative discoveries are cached while one fair alternative per batch progresses and healthy rows publish first', async () => {
  let clock = NOW, discoveries = 0;
  const original = await stock();
  const native = Array.from({ length: 4 }, (_, i) => ({ ...original, id: `robinhood:missing-${i}`, providerAssetId: `missing-${i}`,
    symbol: `OTHER${i}`, deployments: [{ ...original.deployments[0]!, address: `0x${(i + 1).toString(16).padStart(40, '0')}` }] }));
  const healthy = native.at(-1)!, healthyPool = pool(healthy.deployments[0]!.address, `0x${'c'.repeat(64)}`);
  const alternatives: string[] = [], published: Stock[][] = [];
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) return simple(native);
    if (path.includes('/tokens/multi/')) {
      discoveries++;
      return { data: native.map(row => tokenRow(row, healthyPool.attributes.address)), included: [healthyPool] };
    }
    if (path.endsWith('/pools')) {
      alternatives.push(path.split('/').at(-2)!);
      assert.ok(published.some(batch => batch.some(row => row.id === healthy.id && row.activity.netVolume24h !== null)),
        'verified healthy activity is published before an alternative can block');
      throw new Error('This alternative is temporarily unavailable');
    }
    return { data: [healthyPool] };
  };
  const service = new RobinhoodMarketService(http, () => clock);
  for (let pass = 0; pass < 4; pass++) {
    await service.refresh(native, batch => { published.push(batch); });
    clock += 60_001;
  }
  assert.equal(discoveries, 1, 'an explicit discovery miss does not repeat the bulk request before ten minutes');
  assert.deepEqual(alternatives, native.slice(0, 3).map(row => row.deployments[0]!.address));
  assert.ok(native.slice(0, 3).every(row => service.project(row).activity.volume24h === null));
  clock += 600_001;
  await service.refresh(native, batch => { published.push(batch); });
  assert.equal(discoveries, 2); assert.equal(alternatives.length, 4);
});

test('quote-side largest pool triggers exact-base discovery; one failed alternative cannot block healthy rows', async () => {
  const native = await stock(); const second = { ...native, id: 'robinhood:second', symbol: 'AAPL', providerAssetId: 'second',
    deployments: [{ ...native.deployments[0]!, address: '0x2222222222222222222222222222222222222222' }] };
  const secondPool = pool(second.deployments[0]!.address, `0x${'c'.repeat(40)}`);
  const wrong = { ...pool(), relationships: { base_token: pool().relationships.quote_token, quote_token: pool().relationships.base_token } };
  let alternatives = 0;
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) return simple([native, second]);
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native), tokenRow(second, secondPool.attributes.address)], included: [wrong, secondPool] };
    if (path.endsWith('/pools')) { alternatives++; throw new Error('This discovery is temporarily unavailable'); }
    return { data: [secondPool] };
  };
  const service = new RobinhoodMarketService(http, () => NOW);
  await service.refresh([native, second], () => {});
  assert.equal(alternatives, 1); assert.equal(service.project(native).quote.basis, 'onchain_token_market');
  assert.equal(service.project(native).quote.changePercent, '-2.75');
  assert.equal(service.project(native).activity.source, 'GeckoTerminal');
  assert.equal(service.project(native).activity.volume24h, null);
  assert.match(service.project(native).activity.volumeReason!, /exact-token spot pool/);
  assert.equal(service.project(second).quote.basis, 'onchain_token_market');
});

test('featured20 form the first bounded batch and readiness does not wait for the remaining catalog', async () => {
  const original = await stock();
  const symbols = ['MSFT', 'AVGO', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'LLY',
    'COIN', 'AMD', 'PLTR', 'ORCL', 'ADBE', 'CRM', 'INTC', 'COST', 'TSM', 'CSCO', 'SPY'];
  const native = symbols.map((symbol, i) => ({ ...original, id: `robinhood:asset-${i}`, providerAssetId: `asset-${i}`, symbol,
    deployments: [{ ...original.deployments[0]!, address: `0x${(i + 1).toString(16).padStart(40, '0')}` }] }));
  const poolIds = new Map(native.map((row, i) => [row.deployments[0]!.address, `0x${(i + 100).toString(16).padStart(64, '0')}`]));
  let release!: () => void; const delayed = new Promise<void>(resolve => { release = resolve; });
  const batches: string[][] = [], published: Stock[][] = [];
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) {
      const addresses = path.split('/').at(-1)!.split(',');
      return simple(native.filter(row => addresses.includes(row.deployments[0]!.address)));
    }
    if (path.includes('/tokens/multi/')) {
      const addresses = path.split('/').at(-1)!.split(','); batches.push(addresses);
      if (addresses.length === 1) await delayed;
      const requested = native.filter(row => addresses.includes(row.deployments[0]!.address));
      return { data: requested.map(row => tokenRow(row, poolIds.get(row.deployments[0]!.address)!)),
        included: requested.map(row => pool(row.deployments[0]!.address, poolIds.get(row.deployments[0]!.address)!)) };
    }
    const ids = path.split('/').at(-1)!.split(',');
    return { data: native.filter(row => ids.includes(poolIds.get(row.deployments[0]!.address)!))
      .map(row => pool(row.deployments[0]!.address, poolIds.get(row.deployments[0]!.address)!)) };
  };
  const service = new RobinhoodMarketService(http, () => NOW);
  const pending = service.refresh([...native].reverse(), rows => { published.push(rows); });
  await service.featuredReady();
  assert.ok(published.length >= 2); assert.deepEqual(published[1]!.map(row => row.symbol), symbols.slice(0, 20));
  assert.equal(batches[0]!.length, 20); assert.ok(published[1]!.every(row => row.quote.basis === 'onchain_token_market' && row.activity.netVolume24h !== null));
  release(); await pending; assert.equal(published.at(-1)![0]!.symbol, 'SPY');
});

test('Gecko transport allows bounded Robinhood token batches and 32-byte v4 pool IDs while rejecting injection and unrelated networks', async () => {
  const original = globalThis.fetch, requested: string[] = [];
  try {
    globalThis.fetch = (async input => { requested.push(String(input)); return new Response('{"data":[]}', { status: 200 }); }) as typeof fetch;
    const http = createGetJson();
    const path = `/api/v2/networks/robinhood/pools/multi/${POOL}`;
    await http('geckoterminal', path, { include: 'base_token,quote_token', include_volume_breakdown: 'true' });
    assert.equal(requested.length, 1); assert.match(requested[0]!, /include_volume_breakdown=true/);
    await http('geckoterminal', `/api/v2/simple/networks/robinhood/token_price/${TOKEN}`, { include_24hr_price_change: 'true' });
    assert.match(requested[1]!, /include_24hr_price_change=true/);
    for (const invalid of [path.replace('robinhood', 'eth'), `${path}/orders`, path.replace(POOL, '0x123'),
      `/api/v2/networks/robinhood/tokens/multi/${Array(21).fill(TOKEN).join(',')}`]) {
      await assert.rejects(http('geckoterminal', invalid, { include: 'top_pools' }));
    }
    await assert.rejects(http('geckoterminal', path, { include: 'base_token,quote_token', include_volume_breakdown: 'true', destination: 'https://evil.example' }));
    assert.equal(requested.length, 2);
  } finally { globalThis.fetch = original; }
});

test('bounded sweeps fetch catalog quotes before slow activity and allow the featured batch to refresh again', async () => {
  let clock = NOW;
  const original = await stock(), first = [...ReviewedRobinhoodMarketPools].map(([symbol, isin, contract]) => ({
    ...original, id: `robinhood:${symbol}`, providerAssetId: symbol, symbol, underlying: { ...original.underlying, symbol, isin },
    deployments: [{ ...original.deployments[0]!, address: contract }],
  }));
  const tail = { ...original, id: 'robinhood:tail', symbol: 'SPY', providerAssetId: 'tail' };
  let usdBatches = 0;
  const http: GetJson = async (_provider, path, _query, signal) => {
    if (path.includes('/token_price/')) {
      usdBatches++;
      return simple([...first, tail]);
    }
    if (path.includes('/pools/multi/')) return { data: first.map(row => pool(row.deployments[0]!.address, reviewedRobinhoodMarketPool(row)!)) };
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(resolve, 100);
      signal!.addEventListener('abort', () => { clearTimeout(timer); reject(signal!.reason); }, { once: true });
    });
    return { data: [], included: [] };
  };
  const service = new RobinhoodMarketService(http, () => clock, 15);
  await service.refresh([...first, tail], () => {});
  assert.equal(usdBatches, 2, 'the remaining catalog gets independent USD quotes before its slow pool discovery');
  assert.equal(service.metrics.timedOut, 1); assert.equal(service.project(tail).quote.basis, 'onchain_token_market');
  assert.equal(service.project(tail).activity.volume24h, null);
  const originalClock = service.project(first[0]!).quote.receivedAt;
  clock += 60_001; await service.refresh([...first, tail], () => {});
  assert.equal(usdBatches, 4); assert.notEqual(service.project(first[0]!).quote.receivedAt, originalClock);
  assert.equal(service.metrics.timedOut, 2);
});

test('bounded rotation reaches all188 quotes and activities despite a failed first background discovery batch', async () => {
  let clock = NOW, discoveryFailed = false;
  const original = await stock();
  const featured = ReviewedRobinhoodMarketPools.map(([symbol, isin, address]) => ({ ...original,
    id: `robinhood:${symbol}`, providerAssetId: symbol, symbol, underlying: { ...original.underlying, symbol, isin },
    deployments: [{ ...original.deployments[0]!, address }] }));
  const tail = Array.from({ length: 168 }, (_, i) => ({ ...original, id: `robinhood:other-${i}`, providerAssetId: `other-${i}`,
    symbol: `OTHER${i.toString().padStart(3, '0')}`, deployments: [{ ...original.deployments[0]!, address: `0x${(i + 1).toString(16).padStart(40, '0')}` }] }));
  const native = [...featured, ...tail], batches: string[][] = [];
  const ids = new Map(native.map((row, i) => [row.id, reviewedRobinhoodMarketPool(row) ?? `0x${(i + 500).toString(16).padStart(64, '0')}`]));
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) {
      const addresses = path.split('/').at(-1)!.split(','); batches.push(addresses);
      return simple(native.filter(row => addresses.includes(row.deployments[0]!.address.toLowerCase())));
    }
    if (path.includes('/tokens/multi/')) {
      if (!discoveryFailed) { discoveryFailed = true; throw new Error('Temporary first-batch outage'); }
      const addresses = path.split('/').at(-1)!.split(',');
      const requested = native.filter(row => addresses.includes(row.deployments[0]!.address.toLowerCase()));
      return { data: requested.map(row => tokenRow(row, ids.get(row.id)!)),
        included: requested.map(row => pool(row.deployments[0]!.address.toLowerCase(), ids.get(row.id)!)) };
    }
    const requested = path.split('/').at(-1)!.split(',');
    return { data: native.filter(row => requested.includes(ids.get(row.id)!))
      .map(row => pool(row.deployments[0]!.address.toLowerCase(), ids.get(row.id)!)) };
  };
  const service = new RobinhoodMarketService(http, () => clock);
  for (let sweep = 0; sweep < 5; sweep++) {
    batches.length = 0;
    await service.refresh([...native].reverse(), () => {});
    assert.equal(batches.length, 3, 'each pass is limited to featured20 plus two background batches');
    assert.deepEqual(batches[0], featured.map(row => row.deployments[0]!.address.toLowerCase()));
    assert.ok(batches.every(batch => batch.length <= 20));
    if (sweep === 0) {
      assert.equal(service.project(tail[0]!).activity.volume24h, null);
      assert.notEqual(service.project(tail[20]!).activity.volume24h, null, 'later activity runs despite the first discovery failure');
    }
    if (sweep < 4) clock += 60_001;
  }
  assert.ok(native.every(row => service.project(row).quote.basis === 'onchain_token_market'));
  assert.ok(native.every(row => service.project(row).activity.volume24h !== null && service.project(row).activity.netVolume24h !== null));
  assert.equal(service.metrics.available, 188); assert.equal(service.metrics.activityAvailable, 188);
});

test('reviewed featured pool hints require exact issuer listing identity and never seed any market values', async () => {
  const native = await stock(); let clock = NOW, discoveries = 0;
  assert.equal(ReviewedRobinhoodMarketPools.length, 20);
  assert.equal(new Set(ReviewedRobinhoodMarketPools.map(row => row[2])).size, 20);
  const [symbol, isin, contract, poolId] = ReviewedRobinhoodMarketPools[0];
  const verified = { ...native, symbol, underlying: { ...native.underlying, symbol, isin },
    deployments: [{ ...native.deployments[0]!, address: contract }] };
  assert.equal(reviewedRobinhoodMarketPool(verified), poolId);
  for (const foreign of [
    { ...verified, provider: 'backed' as const },
    { ...verified, symbol: 'GOOG' },
    { ...verified, underlying: { ...verified.underlying, isin: 'US02079K1079' } },
    { ...verified, underlying: { ...verified.underlying, listingCountry: 'TW' } },
    { ...verified, deployments: [{ ...verified.deployments[0]!, chainId: 1 }] },
    { ...verified, deployments: [{ ...verified.deployments[0]!, address: TOKEN }] },
  ]) assert.equal(reviewedRobinhoodMarketPool(foreign), null);
  const http: GetJson = async (_provider, path) => {
    if (path.includes('/token_price/')) return simple([verified]);
    if (path.includes('/tokens/multi/')) { discoveries++; return { data: [], included: [] }; }
    assert.match(path, /\/pools\/multi\//);
    return { data: [{ ...pool(contract, poolId), relationships: pool().relationships }] };
  };
  const service = new RobinhoodMarketService(http, () => clock);
  await service.refresh([verified], () => {});
  assert.equal(service.project(verified).quote.basis, 'onchain_token_market', 'a valid separate USD token quote survives pool rejection');
  assert.equal(service.project(verified).activity.volume24h, null, 'a hint cannot override a mismatched live base contract');
  clock += 60_001; await service.refresh([verified], () => {});
  assert.equal(discoveries, 1, 'a rejected hint must trigger fresh discovery rather than repeatedly pinning a bad pool');
});

test('startup waits for featured markets while later bounded batches run independently; metadata refresh retains native reference and charts remain share history', async () => {
  let clock = NOW, marketAvailable = true;
  const http: GetJson = async (provider, path) => {
    if (provider === 'robinhood') return path === '/rhj/assets' ? { assets: [asset] } :
      { quotes: [{ ...issuerQuote, generatedAt: new Date(clock - 1_000).toISOString() }] };
    if (provider !== 'geckoterminal' || !marketAvailable) throw new Error('Unavailable');
    const native = await stock();
    if (path.includes('/token_price/')) return simple([native]);
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [pool()] };
    return { data: [pool()] };
  };
  const service = new LiveMarketService(http, () => clock);
  try {
    const first = await service.featuredCatalog('robinhood');
    assert.equal(first.stocks[0]!.quote.basis, 'onchain_token_market', JSON.stringify(service.diagnostics()));
    assert.equal(first.stocks[0]!.robinhoodReference, undefined);
    assert.equal(first.stocks[0]!.quote.changePercent, '-2.75');
    const chart = { stockId: first.stocks[0]!.id, range: 'ONE_DAY' as const, status: 'ok' as const,
      basis: 'underlying_share_reference', currency: 'USD' as const, receivedAt: new Date(NOW).toISOString(),
      points: [{ timestamp: new Date(NOW - 1000).toISOString(), price: '201' }] };
    assert.equal(chartMatchesStock(chart, first.stocks[0]!), true);
    assert.equal(chartMatchesStock({ ...chart, currency: 'USDC' }, first.stocks[0]!), false);
    const control = service as unknown as { refreshMetadata(id: 'robinhood'): Promise<void>; refreshRobinhoodMarkets(): Promise<void> };
    clock += 30_001; await control.refreshMetadata('robinhood');
    assert.equal(service.snapshot().stocks[0]!.quote.receivedAt, first.stocks[0]!.quote.receivedAt);
    marketAvailable = false; clock += 300_001; await control.refreshMetadata('robinhood');
    await control.refreshRobinhoodMarkets();
    assert.equal(service.snapshot().stocks[0]!.quote.basis, 'provider_indicative_token');
    assert.equal(service.snapshot().stocks[0]!.quote.changePercent, null);
    assert.ok(service.snapshot().stocks[0]!.robinhoodReference);
  } finally { service.stop(); }
});

test('cold featured requests wait for their first market attempt even after issuer identities arrive, with bounded startup warmup', async () => {
  const native = await stock();
  let entered!: () => void, release!: () => void;
  const activityStarted = new Promise<void>(resolve => { entered = resolve; });
  const heldActivity = new Promise<void>(resolve => { release = resolve; });
  const http: GetJson = async (provider, path) => {
    if (provider === 'robinhood') return path === '/rhj/assets' ? { assets: [asset] } : { quotes: [issuerQuote] };
    if (provider !== 'geckoterminal') throw new Error('Unavailable');
    if (path.includes('/token_price/')) return simple([native]);
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [pool()] };
    entered(); await heldActivity;
    return { data: [pool()] };
  };
  const service = new LiveMarketService(http, () => NOW);
  try {
    service.start(); await activityStarted;
    assert.equal(service.snapshot().stocks[0]!.quote.basis, 'onchain_token_market');
    assert.equal(service.snapshot().stocks[0]!.activity.volume24h, null);
    await service.prewarm(5); // An unavailable/slow first attempt cannot make startup wait indefinitely.
    let settled = false;
    const cold = service.featuredCatalog('robinhood').then(result => { settled = true; return result; });
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(settled, false, 'issuer identities and the earlier USD quote do not bypass the initial activity attempt');
    release();
    const first = await cold;
    assert.equal(first.stocks[0]!.activity.volume24h, pool().attributes.volume_usd.h24);
    assert.equal(first.stocks[0]!.activity.receivedAt, new Date(NOW).toISOString());
  } finally { release(); service.stop(); }
});

test('warm featured snapshots return before blocked market and metadata refreshes while preserving clocks and expiry', async () => {
  let clock = NOW, blocked = false;
  const native = await stock();
  let priceEntered!: () => void, metadataEntered!: () => void, releasePrice!: () => void, releaseMetadata!: () => void;
  const priceStarted = new Promise<void>(resolve => { priceEntered = resolve; });
  const metadataStarted = new Promise<void>(resolve => { metadataEntered = resolve; });
  const heldPrice = new Promise<void>(resolve => { releasePrice = resolve; });
  const heldMetadata = new Promise<void>(resolve => { releaseMetadata = resolve; });
  const http: GetJson = async (provider, path) => {
    if (provider === 'robinhood') {
      if (blocked) { metadataEntered(); await heldMetadata; }
      return path === '/rhj/assets' ? { assets: [asset] } : { quotes: [{ ...issuerQuote, generatedAt: new Date(clock - 1_000).toISOString() }] };
    }
    if (provider !== 'geckoterminal') throw new Error('Unavailable');
    if (path.includes('/token_price/')) {
      if (blocked) { priceEntered(); await heldPrice; }
      return simple([native]);
    }
    if (path.includes('/tokens/multi/')) return { data: [tokenRow(native)], included: [pool()] };
    return { data: [pool()] };
  };
  const service = new LiveMarketService(http, () => clock);
  const pending: Promise<void>[] = [];
  try {
    const first = await service.featuredCatalog('robinhood');
    blocked = true; clock += 60_001;
    const control = service as unknown as { refreshMetadata(id: 'robinhood'): Promise<void>; refreshRobinhoodMarkets(): Promise<void> };
    pending.push(control.refreshMetadata('robinhood'), control.refreshRobinhoodMarkets());
    await Promise.all([priceStarted, metadataStarted]);
    let settled = false;
    const warm = service.featuredCatalog('robinhood').then(result => { settled = true; return result; });
    await new Promise<void>(resolve => setImmediate(resolve));
    assert.equal(settled, true, 'a later provider cooldown or metadata request cannot hold an existing featured response');
    const cached = await warm;
    assert.deepEqual(cached.stocks[0]!.quote, first.stocks[0]!.quote);
    assert.deepEqual(cached.stocks[0]!.activity, first.stocks[0]!.activity);
    clock += 300_001;
    const expired = await service.featuredCatalog('robinhood');
    assert.equal(expired.stocks[0]!.quote.price, null); assert.equal(expired.stocks[0]!.activity.volume24h, null);
    assert.equal(expired.stocks[0]!.activity.netVolume24h, null);
    assert.equal(expired.stocks[0]!.quote.receivedAt, first.stocks[0]!.quote.receivedAt);
    assert.equal(expired.stocks[0]!.activity.receivedAt, first.stocks[0]!.activity.receivedAt);
  } finally {
    service.stop(); releasePrice(); releaseMetadata(); await Promise.allSettled(pending);
  }
});

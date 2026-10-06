import assert from 'node:assert/strict';
import test from 'node:test';
import type { Catalog, Stock } from '../src/schema.js';
import { buildApp } from '../src/app.js';
import { WalletPortfolioService, portfolioAssets } from '../src/portfolio.js';
import { PortfolioRequestSchema, PortfolioSchema, isSolanaAddress } from '../src/portfolio-schema.js';
import { MAINNET_GENESIS, PublicPortfolioUpstream, SOL_MINT, SOL_USDC, TOKEN_PROGRAMS,
  jupiterPrices, rpcEndpoint, rpcRequestGroups, type PortfolioTransport, type PortfolioUpstream } from '../src/portfolio-upstream.js';

const now = Date.parse('2026-09-18T12:00:00.000Z');
const iso = new Date(now).toISOString();
const wallet = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const mint = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const stockId = 'backed:MSFT';
const request = { wallets: [{ chain: 'SOLANA' as const, address: wallet }] };
const stock = { id: stockId, deployments: [
  { network: 'Solana', address: mint, decimals: 6 },
  { network: 'Unsupported', address: 'not-a-solana-mint', decimals: 18 },
] } as unknown as Stock;
function catalog(stocks = [stock]): Catalog {
  return { schemaVersion: 1, mode: 'live-read-only', receivedAt: iso,
    providers: [{ id: 'backed', status: 'ok' }], stocks };
}
function upstream(balances: Record<string, string> = {}, prices: Record<string, string> = {}): PortfolioUpstream {
  return {
    balances: async (_wallet, definitions) => ({ complete: true, observedAt: iso,
      balances: definitions.map(asset => ({ asset, quantity: balances[asset.key] ?? '0' })) }),
    prices: async definitions => new Map(definitions.flatMap(asset => prices[asset.key] ? [[asset.key, prices[asset.key]!]] : [])),
  };
}
function service(source: PortfolioUpstream = upstream(), expected = [stockId]) {
  return new WalletPortfolioService(async () => catalog(), source, () => now, expected);
}

test('portfolio requests accept only unique Solana wallets', () => {
  assert.equal(isSolanaAddress(wallet), true);
  assert.equal(PortfolioRequestSchema.safeParse(request).success, true);
  for (const body of [
    { wallets: [{ chain: 'BITCOIN', address: 'not-a-solana-address' }] },
    { wallets: [{ chain: 'UNSUPPORTED', address: 'not-a-solana-address' }] },
    { wallets: [{ chain: 'SOLANA', address: '1'.repeat(33) }] },
    { wallets: [request.wallets[0], request.wallets[0]] },
  ]) assert.equal(PortfolioRequestSchema.safeParse(body).success, false);
});

test('portfolio asset registry includes Solana deployments and ignores all other networks', () => {
  const result = portfolioAssets(catalog(), [stockId]);
  assert.equal(result.complete, true);
  assert.deepEqual(result.assets.map(asset => asset.key).sort(), [
    `SOLANA:${SOL_MINT}`, `SOLANA:${SOL_USDC}`, `SOLANA:${mint}`, 'SOLANA:native',
  ].sort());
});

test('an empty verified Solana wallet returns an exact zero without querying prices', async () => {
  let queried = false;
  const source = upstream(); source.prices = async () => { queried = true; throw new Error('offline'); };
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'ok'); assert.equal(result.balanceUsd, '0'); assert.equal(queried, false);
  assert.deepEqual(result.networks, [{ chain: 'SOLANA', status: 'ok', observedAt: iso }]);
});

test('an empty fully scanned wallet stays zero while the PreStocks directory is unavailable', async () => {
  const result = await new WalletPortfolioService(async () => catalog(), upstream(), () => now).portfolio(request);
  assert.equal(result.status, 'ok'); assert.equal(result.balanceUsd, '0');
  assert.equal(result.holdingsComplete, true);
});

test('a missing PreStocks directory does not permit a total for a nonempty wallet', async () => {
  const source = upstream({ 'SOLANA:native': '1' }, { 'SOLANA:native': '100' });
  const result = await new WalletPortfolioService(async () => catalog(), source, () => now).portfolio(request);
  assert.equal(result.status, 'partial'); assert.equal(result.balanceUsd, null);
  assert.equal(result.holdingsComplete, false);
});

test('Solana native, USDC and stock holdings preserve decimal precision', async () => {
  const assets = portfolioAssets(catalog(), [stockId]).assets;
  const native = assets.find(asset => asset.key === 'SOLANA:native')!;
  const usdc = assets.find(asset => asset.address === SOL_USDC)!;
  const share = assets.find(asset => asset.address === mint)!;
  const result = await service(upstream({ [native.key]: '0.000000001', [usdc.key]: '10.123456', [share.key]: '0.000001' },
    { [native.key]: '100', [usdc.key]: '0.98', [share.key]: '497.123456789123456789' })).portfolio(request);
  assert.equal(result.status, 'ok'); assert.equal(result.balanceUsd, '9.921484103456789123456789');
  assert.deepEqual(result.holdings, [{ stockId, quantity: '0.000001', valueUsd: '0.000497123456789123456789' }]);
  assert.deepEqual(result.tokenHoldings.map(row => row.symbol).sort(), ['SOL', 'USDC']);
});

test('missing price leaves a positive asset visible without publishing a partial total', async () => {
  const share = portfolioAssets(catalog(), [stockId]).assets.find(asset => asset.address === mint)!;
  const result = await service(upstream({ [share.key]: '1.25' })).portfolio(request);
  assert.equal(result.status, 'partial'); assert.equal(result.balanceUsd, null); assert.equal(result.unpricedAssets, 1);
  assert.deepEqual(result.holdings, [{ stockId, quantity: '1.25', valueUsd: null }]);
});

test('read failures never become a fabricated zero', async () => {
  const result = await service({ balances: async () => { throw new Error('private rpc details'); }, prices: async () => new Map() }).portfolio(request);
  assert.equal(result.status, 'unavailable'); assert.equal(result.balanceUsd, null); assert.equal(result.holdingsComplete, false);
  assert.ok(!JSON.stringify(result).includes('private'));
});

test('public RPC adapter serializes Solana calls and rejects write methods and unsafe endpoints', () => {
  const calls = [{ method: 'getGenesisHash', params: [] }, { method: 'getBalance', params: [wallet] }];
  assert.deepEqual(rpcRequestGroups('SOLANA', calls), calls.map(call => [call]));
  assert.throws(() => rpcRequestGroups('SOLANA', [{ method: 'sendTransaction', params: [] }]));
  assert.throws(() => rpcEndpoint('http://localhost:8899'));
  assert.equal(rpcEndpoint('https://api.mainnet-beta.solana.com').hostname, 'api.mainnet-beta.solana.com');
});

test('Jupiter price normalization binds exact mint and freshness', () => {
  const asset = portfolioAssets(catalog(), [stockId]).assets.find(row => row.address === mint)!;
  const prices = jupiterPrices([{ id: mint, usdPrice: '12.345', updatedAt: iso }], [asset], now);
  assert.equal(prices.get(asset.key), '12.345');
  assert.equal(jupiterPrices([{ id: SOL_USDC, usdPrice: '1', updatedAt: iso }], [asset], now).size, 0);
});

test('portfolio HTTP route keeps addresses out of its response and rejects non-Solana input', async () => {
  const app = await buildApp(undefined, false, { portfolio: service() });
  const response = await app.inject({ method: 'POST', url: '/v1/wallet/portfolio', payload: request });
  assert.equal(response.statusCode, 200); assert.ok(!response.body.includes(wallet));
  const rejected = await app.inject({ method: 'POST', url: '/v1/wallet/portfolio', payload: {
    wallets: [{ chain: 'UNSUPPORTED', address: 'not-a-solana-address' }],
  } });
  assert.equal(rejected.statusCode, 400);
  await app.close();
});

test('Solana upstream checks mainnet and scans both token programs', async () => {
  const methods: string[] = [];
  const transport: PortfolioTransport = {
    async rpc(_chain, calls) {
      const call = calls[0]!; methods.push(call.method);
      if (call.method === 'getGenesisHash') return [MAINNET_GENESIS];
      if (call.method === 'getBalance') return [{ value: '1' }];
      if (call.method === 'getTokenAccountsByOwner') return [{ value: [] }];
      throw new Error('unexpected');
    },
    async json() { throw new Error('not used'); },
  };
  const result = await new PublicPortfolioUpstream(transport, () => now).balances(request.wallets[0],
    portfolioAssets(catalog(), [stockId]).assets, new AbortController().signal);
  assert.equal(result.complete, true); assert.equal(result.balances[0]?.quantity, '0.000000001');
  assert.deepEqual(methods, ['getGenesisHash', 'getBalance', 'getTokenAccountsByOwner', 'getTokenAccountsByOwner']);
  assert.equal(TOKEN_PROGRAMS.length, 2);
});

test('response schema requires exactly one Solana network observation', () => {
  const value = { schemaVersion: 1, scope: 'supported-wallet-assets', currency: 'USD', status: 'ok', receivedAt: iso,
    balanceUsd: '0', holdingsComplete: true, holdings: [], tokenHoldings: [], unpricedAssets: 0,
    networks: [{ chain: 'SOLANA', status: 'ok', observedAt: iso }], message: null };
  assert.equal(PortfolioSchema.safeParse(value).success, true);
  assert.equal(PortfolioSchema.safeParse({ ...value, networks: [] }).success, false);
});

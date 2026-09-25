import assert from 'node:assert/strict';
import test from 'node:test';
import type { Catalog, Stock } from '../src/schema.js';
import { WalletPortfolioService, portfolioAssets } from '../src/portfolio.js';
import { PortfolioRequestSchema, PortfolioSchema, isSolanaAddress } from '../src/portfolio-schema.js';
import { ARB_USDC, ETH_USDC, SOL_USDC, TOKEN_PROGRAMS, SOL_MINT, MAINNET_GENESIS, PublicPortfolioUpstream, ethereumPrices, jupiterPrices, rpcEndpoint,
  publicPortfolioTransport, rpcRequestGroups, type PortfolioTransport, type PortfolioUpstream, type RpcCall } from '../src/portfolio-upstream.js';
import { buildApp } from '../src/app.js';

const now = Date.parse('2026-09-18T12:00:00.000Z');
const iso = new Date(now).toISOString();
const solWallet = '11111111111111111111111111111111';
const ethWallet = '0x1111111111111111111111111111111111111111';
const mint = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const contract = '0x5621737f42dae558b81269fcb9e9e70c19aa6b35';
const stockId = 'backed:MSFT';
const request = { wallets: [{ chain: 'SOLANA' as const, address: solWallet }, { chain: 'ETHEREUM' as const, address: ethWallet }] };

test('public Solana reads are serialized while Ethereum retains bounded JSON-RPC batching', () => {
  const solanaCalls = [
    { method: 'getGenesisHash', params: [] },
    { method: 'getBalance', params: [solWallet] },
    { method: 'getTokenAccountsByOwner', params: [] },
  ];
  assert.deepEqual(rpcRequestGroups('SOLANA', solanaCalls), solanaCalls.map(call => [call]));
  const ethereumCalls = [{ method: 'eth_chainId', params: [] }, { method: 'eth_blockNumber', params: [] }];
  assert.deepEqual(rpcRequestGroups('ETHEREUM', ethereumCalls), [ethereumCalls]);
  assert.throws(() => rpcRequestGroups('SOLANA', [{ method: 'sendTransaction', params: [] }]));
});
// Portfolio deliberately only consumes issuer identity/deployments, never a stock quote or statistics.
const stock = { id: stockId, deployments: [{ network: 'Solana', address: mint, decimals: 6 }, { network: 'Ethereum', address: contract, decimals: null }] } as unknown as Stock;
function catalog(stocks = [stock]): Catalog {
  return { schemaVersion: 1, mode: 'live-read-only', receivedAt: iso, providers: [{ id: 'backed', status: 'unavailable' }, { id: 'backpack', status: 'ok' }], stocks };
}
const assets = portfolioAssets(catalog(), [stockId]).assets;
const nativeSol = assets.find(asset => asset.key === 'SOLANA:native')!;
const nativeEth = assets.find(asset => asset.key === 'ETHEREUM:native')!;
const nativeArb = assets.find(asset => asset.key === 'ARBITRUM:native')!;
const arbUsdc = assets.find(asset => asset.key === `ARBITRUM:${ARB_USDC}`)!;
const solStock = assets.find(asset => asset.address === mint)!;
const ethStock = assets.find(asset => asset.address === contract)!;
const solUsdc = assets.find(asset => asset.address === SOL_USDC)!;
function upstream(balances: Record<string, string> = {}, prices: Record<string, string> = {}): PortfolioUpstream {
  return {
    balances: async (wallet, definitions) => ({ complete: true, observedAt: iso,
      balances: definitions.map(asset => ({ asset, quantity: balances[`${wallet.address}:${asset.key}`] ?? balances[asset.key] ?? '0' })) }),
    prices: async definitions => new Map(definitions.flatMap(asset => prices[asset.key] ? [[asset.key, prices[asset.key]!]] : [])),
  };
}
function service(source: PortfolioUpstream = upstream(), clock = () => now, loadCatalog = async () => catalog(), timeoutMs = 25_000) {
  return new WalletPortfolioService(loadCatalog, source, clock, [stockId], timeoutMs);
}
test('empty connected wallet returns an exact zero without prices, even when stock quotes are unavailable', async () => {
  let queried = false;
  const source = upstream(); source.prices = async () => { queried = true; throw new Error('quote outage'); };
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'ok'); assert.equal(result.balanceUsd, '0');
  assert.equal(result.holdingsComplete, true); assert.deepEqual(result.holdings, []); assert.equal(queried, false);
  assert.deepEqual(result.networks.map(network => network.status), ['ok', 'ok', 'ok']);
});
test('loaded deployment metadata allows a verified zero even while every market quote provider is unavailable', async () => {
  const { Registry } = await import('../src/registry.js');
  const { encodeBase58 } = await import('../src/transfer.js');
  const ids = [...Registry.flatMap(row => [`backed:${row.backed.assetId}`, `backpack:${row.backpack.assetId}`]), 'prestocks:ANTHROPIC'];
  const rows = ids.map((id, index) => {
    const bytes = Buffer.alloc(32); bytes.writeUInt32LE(index + 4000);
    return { id, provider: id.split(':')[0], deployments: [{ network: 'Solana', address: encodeBase58(bytes), decimals: 6 }] } as Stock;
  });
  const current = { ...catalog(rows), providers: ['backed', 'backpack', 'prestocks'].map(id => ({ id, status: 'unavailable' })) } as Catalog;
  const observed = await new WalletPortfolioService(async () => current, upstream(), () => now).portfolio(request);
  assert.equal(observed.status, 'ok');
  assert.equal(observed.balanceUsd, '0');
  assert.equal(observed.holdingsComplete, true);

  const missingPreStocks = { ...current, stocks: rows.slice(0, -1) };
  const incomplete = await new WalletPortfolioService(async () => missingPreStocks, upstream(), () => now).portfolio(request);
  assert.equal(incomplete.status, 'partial');
  assert.equal(incomplete.balanceUsd, null);
  assert.equal(incomplete.holdingsComplete, false);
});
test('aggregates native, USDC and stock assets without rounding small fractions or pegging USDC to USD', async () => {
  const result = await service(upstream({ [nativeSol.key]: '0.000000001', [nativeEth.key]: '0.000000000000000001',
    [solUsdc.key]: '10.123456', [solStock.key]: '0.000001' },
  { [nativeSol.key]: '100', [nativeEth.key]: '2000', [solUsdc.key]: '0.98', [solStock.key]: '497.123456789123456789' })).portfolio(request);
  assert.equal(result.status, 'ok');
  assert.equal(result.balanceUsd, '9.921484103456791123456789');
  assert.deepEqual(result.holdings, [{ stockId, quantity: '0.000001', valueUsd: '0.000497123456789123456789' }]);
  assert.deepEqual(result.tokenHoldings, [
    { chain: 'ETHEREUM', assetId: nativeEth.key, symbol: 'ETH', quantity: '0.000000000000000001', valueUsd: '0.000000000000002', unitPriceUsd: '2000' },
    { chain: 'SOLANA', assetId: solUsdc.key, symbol: 'USDC', quantity: '10.123456', valueUsd: '9.92098688', unitPriceUsd: '0.98' },
    { chain: 'SOLANA', assetId: nativeSol.key, symbol: 'SOL', quantity: '0.000000001', valueUsd: '0.0000001', unitPriceUsd: '100' },
  ]);
});
test('verified EVM address contributes Arbitrum ETH and canonical USDC to the wallet total', async () => {
  const seen: { chain: string; address: string }[] = [];
  const source = upstream({ [nativeArb.key]: '0.001', [arbUsdc.key]: '1.25' },
    { [nativeArb.key]: '2100', [arbUsdc.key]: '0.99' });
  const original = source.balances;
  source.balances = async (wallet, definitions, signal) => {
    seen.push({ chain: wallet.chain, address: wallet.address });
    return original(wallet, definitions, signal);
  };
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'ok');
  assert.equal(result.balanceUsd, '3.3375');
  assert.deepEqual(result.tokenHoldings, [
    { chain: 'ARBITRUM', assetId: arbUsdc.key, symbol: 'USDC', quantity: '1.25', valueUsd: '1.2375', unitPriceUsd: '0.99' },
    { chain: 'ARBITRUM', assetId: nativeArb.key, symbol: 'ETH', quantity: '0.001', valueUsd: '2.1', unitPriceUsd: '2100' },
  ]);
  assert.deepEqual(seen.find(wallet => wallet.chain === 'ARBITRUM'), { chain: 'ARBITRUM', address: ethWallet });
});
test('an unavailable Arbitrum read cannot publish a complete wallet total', async () => {
  const source = upstream({ [nativeEth.key]: '1' }, { [nativeEth.key]: '2000' });
  const original = source.balances;
  source.balances = (wallet, definitions, signal) => wallet.chain === 'ARBITRUM'
    ? Promise.reject(new Error('RPC unavailable')) : original(wallet, definitions, signal);
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'partial');
  assert.equal(result.balanceUsd, null);
  assert.equal(result.networks.find(network => network.chain === 'ARBITRUM')?.status, 'unavailable');
  assert.equal(result.tokenHoldings.find(token => token.assetId === nativeEth.key)?.quantity, '1');
});
test('positive stocks stay visible when price is missing; no fabricated zero or partial total', async () => {
  const result = await service(upstream({ [solStock.key]: '1.25', [nativeEth.key]: '1' }, { [nativeEth.key]: '2000' })).portfolio(request);
  assert.equal(result.status, 'partial'); assert.equal(result.balanceUsd, null); assert.equal(result.holdingsComplete, true);
  assert.equal(result.unpricedAssets, 1); assert.deepEqual(result.holdings, [{ stockId, quantity: '1.25', valueUsd: null }]);
});
test('an independently owned unlisted SPL token remains visible by mint when no USD price exists', async () => {
  const unknownMint = 'Vote111111111111111111111111111111111111111';
  assert.equal(isSolanaAddress(unknownMint), true);
  const unknown = { key: `SOLANA:${unknownMint}`, chain: 'SOLANA' as const, address: unknownMint,
    stockId: null, decimals: 6, pricing: 'solana' as const, symbol: `${unknownMint.slice(0, 4)}…${unknownMint.slice(-4)}` };
  const source = upstream();
  const original = source.balances;
  source.balances = async (wallet, definitions, signal) => {
    const read = await original(wallet, definitions, signal);
    return wallet.chain === 'SOLANA' ? { ...read, balances: [...read.balances,
      { asset: unknown, quantity: '1.234567' }] } : read;
  };
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'partial');
  assert.equal(result.balanceUsd, null);
  assert.equal(result.holdingsComplete, true);
  assert.deepEqual(result.tokenHoldings, [{ chain: 'SOLANA', assetId: unknown.key, symbol: unknown.symbol,
    quantity: '1.234567', valueUsd: null, unitPriceUsd: null }]);
});
test('a read failure never means zero and successful chain stock quantities are preserved', async () => {
  const source = upstream({ [solStock.key]: '2' }, { [solStock.key]: '100' }); const original = source.balances;
  source.balances = (wallet, definitions, signal) => wallet.chain === 'ETHEREUM' ? Promise.reject(new Error('SECRET_URL')) : original(wallet, definitions, signal);
  const result = await service(source).portfolio(request);
  assert.equal(result.status, 'partial'); assert.equal(result.balanceUsd, null); assert.equal(result.holdingsComplete, false);
  assert.equal(result.holdings[0]?.quantity, '2'); assert.equal(result.networks[1]?.observedAt, null);
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});
test('missing one wallet chain cannot yield a complete total', async () => {
  const result = await service().portfolio({ wallets: [request.wallets[0]!] });
  assert.equal(result.status, 'partial'); assert.equal(result.holdingsComplete, false); assert.equal(result.balanceUsd, null);
});
test('catalog missing a reviewed stock or ambiguous deployment cannot prove holdings complete', async () => {
  for (const stocks of [[], [{ ...stock, deployments: [] }], [stock, { ...stock, id: 'backpack:OTHER' }]]) {
    const expected = stocks.length === 2 ? [stockId, 'backpack:OTHER'] : [stockId];
    const result = await new WalletPortfolioService(async () => catalog(stocks), upstream(), () => now, expected).portfolio(request);
    assert.equal(result.holdingsComplete, false); assert.equal(result.balanceUsd, null);
  }
});
test('same provider stock across chains aggregates quantity and value; one missing valuation makes holding value unavailable', async () => {
  const values = { [solStock.key]: '1.5', [ethStock.key]: '2.25' };
  const priced = await service(upstream(values, { [solStock.key]: '100', [ethStock.key]: '101' })).portfolio(request);
  assert.deepEqual(priced.holdings, [{ stockId, quantity: '3.75', valueUsd: '377.25' }]);
  const missing = await service(upstream(values, { [solStock.key]: '100' })).portfolio(request);
  assert.deepEqual(missing.holdings, [{ stockId, quantity: '3.75', valueUsd: null }]);
});
test('multiple unique wallets aggregate and case-insensitive EVM duplicates are rejected', async () => {
  const second = '0x2222222222222222222222222222222222222222';
  const result = await service(upstream({ [nativeEth.key]: '1' }, { [nativeEth.key]: '2000' })).portfolio({ wallets: [...request.wallets, { chain: 'ETHEREUM', address: second }] });
  assert.equal(result.balanceUsd, '4000');
  assert.equal(PortfolioRequestSchema.safeParse({ wallets: [...request.wallets, { chain: 'ETHEREUM', address: ethWallet }] }).success, false);
  const caseAddress = '0xabcdefabcdefabcdefabcdefabcdefabcdefabcd';
  assert.equal(PortfolioRequestSchema.safeParse({ wallets: [{ chain: 'ETHEREUM', address: caseAddress }, { chain: 'ETHEREUM', address: `0x${caseAddress.slice(2).toUpperCase()}` }] }).success, false);
});
test('wallet validation rejects unsupported networks, malformed keys, >10 wallets and extra fields', () => {
  assert.equal(isSolanaAddress(solWallet), true); assert.equal(isSolanaAddress('1'.repeat(33)), false);
  for (const body of [{ wallets: [] }, { wallets: [{ chain: 'ARBITRUM', address: ethWallet }] }, { wallets: [{ chain: 'SOLANA', address: '1'.repeat(33) }] },
    { wallets: [{ chain: 'ETHEREUM', address: 'https://evil.example' }] }, { ...request, url: 'https://evil.example' },
    { wallets: Array(11).fill(request.wallets[0]) }]) assert.equal(PortfolioRequestSchema.safeParse(body).success, false);
  assert.equal(PortfolioRequestSchema.safeParse({ wallets: Array.from({ length: 6 }, (_, i) => ({ chain: 'ETHEREUM', address: `0x${String(i + 1).repeat(40)}` })) }).success, false);
});
test('15 second cache deduplicates, preserves observation time, and never returns an old total after failed refresh', async () => {
  let time = now; let loads = 0; let fail = false;
  const source = upstream(); const original = source.balances;
  source.balances = async (...args) => { loads++; if (fail) throw new Error('offline'); return original(...args); };
  const reader = service(source, () => time);
  const [first, second] = await Promise.all([reader.portfolio(request), reader.portfolio(request)]);
  assert.deepEqual(first, second); assert.equal(loads, 3);
  time += 14_999; assert.equal((await reader.portfolio(request)).receivedAt, iso); assert.equal(loads, 3);
  time++; fail = true; const failed = await reader.portfolio(request);
  assert.equal(failed.status, 'unavailable'); assert.equal(failed.balanceUsd, null); assert.equal(failed.holdingsComplete, false); assert.equal(loads, 6);
});
test('a stuck provider hits the total request deadline and returns a safe unavailable response', async () => {
  const source = upstream(); source.balances = () => new Promise(() => {});
  const result = await service(source, () => now, async () => catalog(), 10).portfolio(request);
  assert.equal(result.status, 'unavailable'); assert.equal(result.balanceUsd, null);
});
test('response contract rejects an ok total with missing network or holdings coverage', () => {
  const base = { schemaVersion: 1, scope: 'supported-wallet-assets', currency: 'USD', status: 'ok', receivedAt: iso,
    balanceUsd: '0', holdingsComplete: true, holdings: [], tokenHoldings: [], unpricedAssets: 0,
    networks: [{ chain: 'SOLANA', status: 'ok', observedAt: iso }, { chain: 'ETHEREUM', status: 'ok', observedAt: iso },
      { chain: 'ARBITRUM', status: 'ok', observedAt: iso }], message: null };
  assert.equal(PortfolioSchema.safeParse(base).success, true);
  assert.equal(PortfolioSchema.safeParse({ ...base, holdingsComplete: false }).success, false);
  assert.equal(PortfolioSchema.safeParse({ ...base, networks: [base.networks[0], base.networks[0]] }).success, false);
  assert.equal(PortfolioSchema.safeParse({ ...base, tokenHoldings: [{ chain: 'SOLANA', assetId: nativeSol.key, symbol: 'SOL',
    quantity: '1', valueUsd: '10', unitPriceUsd: '9' }] }).success, false);
});
test('HTTP route has bounded strict POST body, fixed path and no-address response; public health is independent', async () => {
  const app = await buildApp(undefined, false, { portfolio: service() });
  const result = await app.inject({ method: 'POST', url: '/v1/wallet/portfolio', payload: request });
  assert.equal(result.statusCode, 200); assert.equal(result.json().balanceUsd, '0'); assert.equal(result.headers['cache-control'], 'no-store');
  assert.ok(!result.body.includes(solWallet)); assert.ok(!result.body.includes(ethWallet));
  for (const payload of [{ wallets: [...request.wallets, request.wallets[0]] }, { wallets: [{ chain: 'SOLANA', address: '1'.repeat(33) }] }, { ...request, rpcUrl: 'https://evil.example' }]) {
    assert.equal((await app.inject({ method: 'POST', url: '/v1/wallet/portfolio', payload })).statusCode, 400);
  }
  assert.equal((await app.inject({ method: 'GET', url: '/v1/wallet/portfolio' })).statusCode, 404);
  assert.equal((await app.inject({ method: 'GET', url: '/health' })).statusCode, 200);
  await app.close();
});

function tokenAccount(pubkey: string, mintAddress: string, amount: string, program: string, decimals = 6) {
  return { pubkey, account: { owner: program, data: { parsed: { type: 'account', info: { mint: mintAddress, owner: solWallet, tokenAmount: { amount, decimals: String(decimals) } } } } } };
}
const noopTransport: PortfolioTransport = { rpc: async () => { throw new Error('No RPC'); }, json: async () => { throw new Error('No prices'); } };
test('Solana reads both token programs, exact raw amounts and sums accounts instead of uiAmount', async () => {
  let methods: string[] = [];
  const transport: PortfolioTransport = { ...noopTransport, rpc: async (_chain, calls) => {
    methods.push(...calls.map(call => call.method));
    if (calls[0]?.method === 'getAccountInfo') {
      const mintData = Buffer.alloc(82); mintData[44] = 6; mintData[45] = 1;
      return [{ value: { owner: TOKEN_PROGRAMS[1], executable: false, lamports: '1', data: [mintData.toString('base64'), 'base64'] } }];
    }
    const call = calls[0]!;
    if (call.method === 'getGenesisHash') return [MAINNET_GENESIS];
    if (call.method === 'getBalance') return [{ value: '1' }];
    return [{ value: [tokenAccount(call.params[1] && (call.params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0]
      ? SOL_USDC : SOL_MINT, mint, call.params[1] && (call.params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0]
        ? '2000001' : '3000002', (call.params[1] as { programId: string }).programId)] }];
  } };
  const adapter = new PublicPortfolioUpstream(transport, () => now);
  const result = await adapter.balances(request.wallets[0]!, assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal);
  assert.equal(result.complete, true); assert.deepEqual(result.balances.map(balance => balance.quantity), ['0.000000001', '2.000001', '3.000002']);
  assert.deepEqual(methods, ['getGenesisHash', 'getBalance', 'getTokenAccountsByOwner', 'getTokenAccountsByOwner', 'getAccountInfo']);
});
test('Solana ownership scan discovers an arbitrary positive SPL token outside the stock catalog', async () => {
  const unknownMint = 'Vote111111111111111111111111111111111111111';
  const transport: PortfolioTransport = { ...noopTransport, rpc: async (_chain, calls) => {
    const call = calls[0]!;
    if (call.method === 'getGenesisHash') return [MAINNET_GENESIS];
    if (call.method === 'getBalance') return [{ value: '0' }];
    return [{ value: (call.params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0]
      ? [tokenAccount('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL', unknownMint,
        '1250000', TOKEN_PROGRAMS[0])] : [] }];
  } };
  const result = await new PublicPortfolioUpstream(transport, () => now).balances(request.wallets[0]!,
    assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal);
  assert.equal(result.complete, true);
  const unlisted = result.balances.find(balance => balance.asset.address === unknownMint);
  assert.equal(unlisted?.quantity, '1.25');
  assert.equal(unlisted?.asset.stockId, null);
  assert.equal(unlisted?.asset.symbol, 'Vote…1111');
});
test('one failed Solana token-program read preserves verified native and other token balances as partial', async () => {
  const unknownMint = 'Vote111111111111111111111111111111111111111';
  const transport: PortfolioTransport = { ...noopTransport, rpc: async (_chain, calls) => {
    const call = calls[0]!;
    if (call.method === 'getGenesisHash') return [MAINNET_GENESIS];
    if (call.method === 'getBalance') return [{ value: '2000000000' }];
    if (call.method === 'getTokenAccountsByOwner' &&
      (call.params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0]) throw new Error('RPC rate limited');
    return [{ value: [tokenAccount('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL', unknownMint,
      '500000', TOKEN_PROGRAMS[1])] }];
  } };
  const result = await new PublicPortfolioUpstream(transport, () => now).balances(request.wallets[0]!,
    assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal);
  assert.equal(result.complete, false);
  assert.deepEqual(result.balances.map(balance => balance.quantity), ['2', '0.5']);
  assert.equal(result.balances[1]?.asset.address, unknownMint);
});
test('Solana wrong network, duplicate accounts or wrong account owner fail closed', async () => {
  const row = tokenAccount(SOL_USDC, mint, '1', TOKEN_PROGRAMS[0]);
  for (const response of [['testnet', { value: '0' }, { value: [] }, { value: [] }],
    ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d', { value: '0' }, { value: [row, row] }, { value: [] }],
    ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d', { value: '0' }, { value: [tokenAccount(SOL_USDC, mint, '1', TOKEN_PROGRAMS[1])] }, { value: [] }]]) {
    const reader = new PublicPortfolioUpstream({ ...noopTransport, rpc: async (_chain, calls) => {
      const method = calls[0]!.method;
      return [method === 'getGenesisHash' ? response[0] : method === 'getBalance' ? response[1] :
        (calls[0]!.params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0] ? response[2] : response[3]];
    } }, () => now);
    assert.equal((await reader.balances(request.wallets[0]!, assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal)).complete, false);
  }
});
test('Solana accepts full mainnet RPC genesis hash and rejects the truncated CAIP-2 identifier', async () => {
  for (const [genesis, expected] of [
    ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d', true],
    ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp', false],
  ] as const) {
    const reader = new PublicPortfolioUpstream({ ...noopTransport,
      rpc: async (_chain, calls) => [calls[0]!.method === 'getGenesisHash' ? genesis :
        calls[0]!.method === 'getBalance' ? { value: '0' } : { value: [] }],
    }, () => now);
    const result = await reader.balances(request.wallets[0]!, assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal);
    assert.equal(result.complete, expected);
  }
});
test('failed network reads emit only bounded safe diagnostics and throttle identical failures', async () => {
  let clock = now;
  const diagnostics: unknown[] = [];
  const reader = new PublicPortfolioUpstream({ ...noopTransport,
    rpc: async () => ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp'],
  }, () => clock, diagnostic => diagnostics.push(diagnostic));
  const args = [request.wallets[0]!, assets.filter(asset => asset.chain === 'SOLANA'), new AbortController().signal] as const;
  await reader.balances(...args); await reader.balances(...args);
  assert.deepEqual(diagnostics, [{ chain: 'SOLANA', reason: 'wrong_network' }]);
  clock += 60_000; await reader.balances(...args);
  assert.equal(diagnostics.length, 2);
  assert.ok(!JSON.stringify(diagnostics).includes(solWallet));
  const unsafe = new PublicPortfolioUpstream({ ...noopTransport, rpc: async () => { throw new TypeError(`https://secret.example/${solWallet}`); } },
    () => clock, diagnostic => diagnostics.push(diagnostic));
  await unsafe.balances(...args);
  assert.deepEqual(diagnostics[2], { chain: 'SOLANA', reason: 'network_unavailable' });
  assert.ok(!JSON.stringify(diagnostics).includes('secret'));
});
test('Ethereum pins reads to one verified mainnet block and obtains decimals from each exact ERC20', async () => {
  const captured: RpcCall[][] = [];
  const transport: PortfolioTransport = { ...noopTransport, rpc: async (_chain, calls) => {
    captured.push(calls);
    if (calls[0]!.method === 'eth_chainId') return ['0x1', '0x123'];
    return ['0x1', '0x989680', '0x6', '0xde0b6b3a7640000', '0x12'];
  } };
  const result = await new PublicPortfolioUpstream(transport, () => now).balances(request.wallets[1]!, assets.filter(asset => asset.chain === 'ETHEREUM'), new AbortController().signal);
  assert.equal(result.complete, true); assert.deepEqual(result.balances.map(balance => balance.quantity), ['0.000000000000000001', '10', '1']);
  for (const call of captured[1]!) assert.equal(call.params[1], '0x123');
  assert.deepEqual(captured[1]!.filter(call => call.method === 'eth_call').map(call => (call.params[0] as { to: string }).to), [ETH_USDC, ETH_USDC, contract, contract]);
});
test('Ethereum malformed hex, wrong chain or incorrect canonical USDC decimals produce unavailable reads', async () => {
  for (const [head, values] of [[['0x89', '0x123'], []], [['0x1', '0x123'], ['0x', '0x0', '0x6', '0x0', '0x12']], [['0x1', '0x123'], ['0x0', '0x0', '0x12', '0x0', '0x12']]]) {
    const reader = new PublicPortfolioUpstream({ ...noopTransport, rpc: async (_chain, calls) => calls[0]!.method === 'eth_chainId' ? head! : values! }, () => now);
    assert.equal((await reader.balances(request.wallets[1]!, assets.filter(asset => asset.chain === 'ETHEREUM'), new AbortController().signal)).complete, false);
  }
});
test('Arbitrum reads verified chain 42161 at one block with exact native ETH and canonical USDC', async () => {
  const calls: { chain: string; batch: RpcCall[] }[] = [];
  const transport: PortfolioTransport = { ...noopTransport, rpc: async (chain, batch) => {
    calls.push({ chain, batch });
    return batch[0]?.method === 'eth_chainId' ? ['0xa4b1', '0x123'] :
      ['0xde0b6b3a7640000', '0x1312d00', '0x6'];
  } };
  const wallet = { chain: 'ARBITRUM' as const, address: ethWallet };
  const result = await new PublicPortfolioUpstream(transport, () => now).balances(wallet,
    assets.filter(asset => asset.chain === 'ARBITRUM'), new AbortController().signal);
  assert.equal(result.complete, true);
  assert.deepEqual(result.balances.map(balance => balance.quantity), ['1', '20']);
  assert.ok(calls.every(call => call.chain === 'ARBITRUM'));
  assert.deepEqual(calls[1]!.batch.filter(call => call.method === 'eth_call').map(call => (call.params[0] as { to: string }).to),
    [ARB_USDC, ARB_USDC]);
  assert.ok(calls[1]!.batch.every(call => call.params[1] === '0x123'));
});
test('Arbitrum wrong chain and noncanonical USDC decimals fail closed', async () => {
  for (const [header, values] of [
    [['0x1', '0x123'], ['0x0', '0x0', '0x6']],
    [['0xa4b1', '0x123'], ['0x0', '0x0', '0x12']],
  ]) {
    const reader = new PublicPortfolioUpstream({ ...noopTransport,
      rpc: async (_chain, batch) => batch[0]?.method === 'eth_chainId' ? header! : values!,
    }, () => now);
    const result = await reader.balances({ chain: 'ARBITRUM', address: ethWallet },
      assets.filter(asset => asset.chain === 'ARBITRUM'), new AbortController().signal);
    assert.equal(result.complete, false);
  }
});
test('Jupiter prices require one exact mint, current metadata and positive USD price', () => {
  const match = { id: mint, updatedAt: iso, usdPrice: '497.123456789' };
  assert.equal(jupiterPrices([match], [solStock], now).get(solStock.key), match.usdPrice);
  for (const rows of [[{ ...match, id: SOL_MINT }], [match, match], [{ ...match, usdPrice: '0' }], [{ ...match, updatedAt: new Date(now - 300_001).toISOString() }]]) {
    assert.equal(jupiterPrices(rows, [solStock], now).size, 0);
  }
});
test('DEX USD price selects the highest liquidity exact Ethereum base contract and ignores wrong-chain/ticker/quote matches', () => {
  const pair = (price: string, liquidity: string, address = contract, chainId = 'ethereum', pairAddress = ethWallet) => ({ chainId, baseToken: { address }, pairAddress, priceUsd: price, liquidity: { usd: liquidity } });
  const values = ethereumPrices([pair('100', '10'), pair('101', '1000', contract, 'ethereum', '0x2222222222222222222222222222222222222222'),
    pair('10000', '999999', ETH_USDC), pair('10000', '999999', contract, 'arbitrum')], [ethStock]);
  assert.equal(values.get(ethStock.key), '101');
  assert.equal(ethereumPrices([pair('100', '0')], [ethStock]).size, 0);
});
test('live pricing wiring uses mint and contract requests plus ETH/USDC USD spot, with 15s cache and no stale fallback', async () => {
  let clock = now; let fail = false; const calls: { provider: string; path: string; query: Record<string, string> }[] = [];
  const transport: PortfolioTransport = { ...noopTransport, json: async (provider, path, query) => {
    calls.push({ provider, path, query }); if (fail) throw new Error('offline');
    if (provider === 'jupiter') return [{ id: mint, updatedAt: iso, usdPrice: '100' }];
    if (provider === 'dexscreener') return [{ chainId: 'ethereum', baseToken: { address: contract }, pairAddress: ethWallet, priceUsd: '101', liquidity: { usd: '1000' } }];
    return { data: { currency: 'USD', base: path.includes('USDC') ? 'USDC' : 'ETH', amount: path.includes('USDC') ? '0.99' : '2000' } };
  } };
  const reader = new PublicPortfolioUpstream(transport, () => clock);
  const requested = [solStock, ethStock, nativeEth, assets.find(asset => asset.address === ETH_USDC)!];
  assert.equal((await reader.prices(requested, new AbortController().signal)).size, 4);
  assert.equal(calls.length, 4);
  assert.equal(calls.find(call => call.provider === 'jupiter')?.query.query, mint);
  assert.equal(calls.find(call => call.provider === 'dexscreener')?.path, `/tokens/v1/ethereum/${contract}`);
  await reader.prices(requested, new AbortController().signal); assert.equal(calls.length, 4);
  clock += 15_000; fail = true; assert.equal((await reader.prices(requested, new AbortController().signal)).size, 0);
});
test('RPC configuration refuses insecure or local endpoints', () => {
  assert.equal(rpcEndpoint('https://api.mainnet-beta.solana.com').hostname, 'api.mainnet-beta.solana.com');
  for (const value of ['http://example.com', 'https://127.0.0.1', 'https://localhost', 'https://user:pass@example.com', 'https://example.com:9000', 'https://[::1]']) assert.throws(() => rpcEndpoint(value));
});
test('Jupiter cached prices expire at the source age boundary even inside the fifteen-second cache window', async () => {
  let clock = now; let requests = 0;
  const transport: PortfolioTransport = { ...noopTransport, json: async () => {
    requests++;
    return [{ id: mint, updatedAt: new Date(now - 299_999).toISOString(), usdPrice: '100' }];
  } };
  const reader = new PublicPortfolioUpstream(transport, () => clock);
  const signal = new AbortController().signal;
  assert.equal((await reader.prices([solStock], signal)).get(solStock.key), '100');
  assert.equal(requests, 1);
  clock += 2;
  assert.equal((await reader.prices([solStock], signal)).size, 0);
  assert.equal(requests, 2);
});
test('public Solana RPC transport sends sequential reads and preserves integers before JavaScript rounding', async () => {
  const original = globalThis.fetch;
  const captured: RequestInit[] = [];
  globalThis.fetch = async (_url, options) => {
    captured.push(options!);
    const request = JSON.parse(options!.body as string) as { id: string };
    return new Response(request.id === '1'
      ? '{"id":"1","jsonrpc":"2.0","result":"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d"}'
      : '{"id":"2","jsonrpc":"2.0","result":{"value":9007199254740993}}', { status: 200 });
  };
  try {
    const transport = publicPortfolioTransport({});
    const result = await transport.rpc('SOLANA', [{ method: 'getGenesisHash', params: [] }, { method: 'getBalance', params: [solWallet] }], new AbortController().signal);
    assert.deepEqual(result, ['5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d', { value: '9007199254740993' }]);
    assert.equal(captured.length, 2);
    assert.ok(captured.every(request => request.method === 'POST' && request.redirect === 'error'));
    assert.equal(JSON.parse(captured[1]!.body as string).params[0], solWallet);
  } finally { globalThis.fetch = original; }
});
test('public RPC transport rejects write methods and duplicate/error batch responses without leaking upstream text', async () => {
  const original = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response('[{"id":"1","jsonrpc":"2.0","result":"0x0"},{"id":"1","jsonrpc":"2.0","error":{"message":"secret"}}]'); };
  try {
    const transport = publicPortfolioTransport({});
    await assert.rejects(transport.rpc('ETHEREUM', [{ method: 'eth_sendRawTransaction', params: ['never'] }], new AbortController().signal), /Only read-only/);
    assert.equal(calls, 0);
    await assert.rejects(transport.rpc('ETHEREUM', [{ method: 'eth_chainId', params: [] }, { method: 'eth_blockNumber', params: [] }], new AbortController().signal), /Incomplete RPC response/);
  } finally { globalThis.fetch = original; }
});
test('public fixed-origin price transport rejects unsupported pairs, arbitrary URLs and oversized responses', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(' '.repeat(4_000_001));
  try {
    const transport = publicPortfolioTransport({});
    const signal = new AbortController().signal;
    await assert.rejects(transport.json('coinbase', 'https://evil.example', {}, signal), /Invalid public/);
    await assert.rejects(transport.json('coinbase', '/v2/prices/BTC-USD/spot', {}, signal), /Invalid public/);
    await assert.rejects(transport.json('coinbase', '/v2/prices/ETH-USD/spot', {}, signal), /exceeded bound/);
  } finally { globalThis.fetch = original; }
});

test('portfolio displays and values scaled shares using the matching UI-unit Jupiter price', async () => {
  const source = upstream({ [solStock.key]: '1' }, { [solStock.key]: '500' });
  const original = source.balances;
  source.balances = async (...args) => {
    const read = await original(...args);
    return { ...read, balances: read.balances.map(position => position.asset.key === solStock.key
      ? { ...position, displayQuantity: '1.00590339' } : position) };
  };
  const result = await service(source).portfolio(request);
  assert.deepEqual(result.holdings, [{ stockId, quantity: '1.00590339', valueUsd: '502.951695' }]);
  assert.equal(result.balanceUsd, '502.951695');
});

test('new catalog Solana stocks use normalized network names without expanding Ethereum RPC fanout', async () => {
  const { Registry } = await import('../src/registry.js');
  const { encodeBase58 } = await import('../src/transfer.js');
  const ids = [...Registry.flatMap(row => [`backed:${row.backed.assetId}`, `backpack:${row.backpack.assetId}`]), 'prestocks:ANTHROPIC',
    ...Array.from({ length: 150 }, (_, i) => `backpack:NEW${i}.US`)];
  const rows = ids.map((id, i) => {
    const addressBytes = Buffer.alloc(32); addressBytes.writeUInt32LE(i + 1234);
    return { id, provider: id.split(':')[0], deployments: [{ network: 'Solana', address: encodeBase58(addressBytes), decimals: 6 },
      { network: 'Ethereum', address: `0x${(i + 1).toString(16).padStart(40, '0')}`, decimals: 18 }] } as Stock;
  });
  const target = rows.at(-1)!;
  target.deployments[0]!.network = ' Solana ';
  let observedSolana = 0; let observedEthereum = 0;
  const source: PortfolioUpstream = {
    balances: async (wallet, definitions) => {
      if (wallet.chain === 'SOLANA') observedSolana = definitions.length;
      if (wallet.chain === 'ETHEREUM') observedEthereum = definitions.length;
      return { complete: true, observedAt: iso, balances: definitions.map(asset => ({ asset,
        quantity: asset.stockId === target.id ? '0.75' : '0' })) };
    },
    prices: async definitions => new Map(definitions.map(asset => [asset.key, '20'])),
  };
  const current = { ...catalog(rows), providers: ['backed', 'backpack', 'prestocks'].map(id => ({ id, status: 'ok' })) } as Catalog;
  const result = await new WalletPortfolioService(async () => current, source, () => now).portfolio(request);
  assert.equal(result.holdingsComplete, true);
  assert.ok(observedSolana > 150);
  assert.equal(observedEthereum, Registry.length * 2 + 2);
  assert.deepEqual(result.holdings, [{ stockId: target.id, quantity: '0.75', valueUsd: '15' }]);
});

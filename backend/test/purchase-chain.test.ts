import assert from 'node:assert/strict';
import test from 'node:test';
import { PublicPurchaseChainReader, PurchaseRpcError, PurchaseRpcUnavailableError, publicPurchaseRpc,
  paymentAssetDefinition } from '../src/purchase-chain.js';
import { MAINNET_GENESIS, SOL_MINT, type PortfolioAsset } from '../src/portfolio-upstream.js';
import type { PurchaseNetwork, PurchaseWallet } from '../src/purchase-schema.js';

const wallets: PurchaseWallet[] = [
  { chain: 'ETHEREUM', address: '0x1111111111111111111111111111111111111111' },
  { chain: 'SOLANA', address: '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J' },
];

function rpc() {
  return { async call(network: PurchaseNetwork, method: string): Promise<unknown> {
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'eth_chainId') {
      const id = network === 'ETHEREUM' ? 1n : network === 'BASE' ? 8453n : 42161n;
      return `0x${id.toString(16)}`;
    }
    if (method === 'eth_getBalance') return '0x1bc16d674ec80000'; // 2 ETH
    if (method === 'eth_call') return '0x4c4b40'; // 5 USDC
    if (method === 'getBalance') return { value: '3000000000' }; // 3 SOL
    if (method === 'getTokenAccountsByOwner') return { value: [{ account: { data: { parsed: { info: {
      tokenAmount: { amount: '7000000', decimals: 6 },
    } } } } }] }; // 7 USDC
    throw new Error('Unexpected purchase RPC method');
  } };
}

test('public purchase balances receive exact bounded USD values from the shared price reader', async () => {
  let requested: PortfolioAsset[] = [];
  const prices = { async prices(assets: PortfolioAsset[]) {
    requested = assets;
    return new Map(assets.map(asset => [asset.key,
      asset.pricing === 'ETH' ? '2000' : asset.pricing === 'USDC' ? '0.999' : '100']));
  } };
  const rows = await new PublicPurchaseChainReader(rpc(), prices).paymentAssets(wallets);
  const byId = new Map(rows.map(row => [row.id, row]));
  assert.equal(byId.get('ETHEREUM:ETH')?.usdValue, '4000');
  assert.equal(byId.get('BASE:ETH')?.usdValue, '4000');
  assert.equal(byId.get('ARBITRUM:ETH')?.usdValue, '4000');
  assert.equal(byId.get('ETHEREUM:USDC')?.usdValue, '4.995');
  assert.equal(byId.get('SOLANA:SOL')?.usdValue, '300');
  assert.equal(byId.get('SOLANA:USDC')?.usdValue, '6.993');
  assert.equal(requested.length, 8);
  assert.deepEqual(requested.find(asset => asset.key === 'SOLANA:SOL'), {
    key: 'SOLANA:SOL', chain: 'SOLANA', address: SOL_MINT, stockId: null, decimals: 9, pricing: 'solana',
  });
  assert.equal(requested.find(asset => asset.key === 'BASE:ETH')?.address, null);
  assert.equal(requested.find(asset => asset.key === 'ARBITRUM:USDC')?.pricing, 'USDC');
});

test('payment balances remain usable while USD values fail closed when pricing is unavailable', async () => {
  const prices = { async prices(): Promise<Map<string, string>> { throw new Error('pricing unavailable'); } };
  const rows = await new PublicPurchaseChainReader(rpc(), prices).paymentAssets(wallets);
  assert.equal(rows.length, 8);
  assert.ok(rows.every(row => row.enabled && BigInt(row.balanceBaseUnits) > 0n));
  assert.ok(rows.every(row => row.usdValue === null));
});

test('Buy options share wallet reads briefly while direct quote balance reads stay fresh', async () => {
  let clock = 1_000; let nativeBalance = '0x1bc16d674ec80000'; let evmBalanceReads = 0; let priceReads = 0;
  const upstream = rpc();
  const chain = new PublicPurchaseChainReader({ async call(network, method, params) {
    if (method === 'eth_getBalance') { evmBalanceReads++; return nativeBalance; }
    return upstream.call(network, method, params);
  } }, { async prices(assets) {
    priceReads++;
    return new Map(assets.map(asset => [asset.key, '1']));
  } }, () => clock);
  const [first, shared] = await Promise.all([chain.paymentAssets(wallets), chain.paymentAssets([...wallets].reverse())]);
  assert.deepEqual(first, shared);
  assert.equal(evmBalanceReads, 3, 'concurrent option calls should share the three native balance reads');
  assert.equal(priceReads, 1);
  nativeBalance = '0xde0b6b3a7640000'; // 1 ETH
  assert.equal(await chain.balance(paymentAssetDefinition('ARBITRUM:ETH')!, wallets[0]!), '1000000000000000000',
    'the balance read used by quote must bypass the options snapshot');
  assert.equal((await chain.paymentAssets(wallets)).find(asset => asset.id === 'ARBITRUM:ETH')?.balance, '2');
  assert.equal(evmBalanceReads, 4);
  clock += 15_000;
  assert.equal((await chain.paymentAssets(wallets)).find(asset => asset.id === 'ARBITRUM:ETH')?.balance, '1');
  assert.equal(evmBalanceReads, 7);
  const otherWallets = [{ ...wallets[0]!, address: '0x2222222222222222222222222222222222222222' }, wallets[1]!];
  await chain.paymentAssets(otherWallets);
  assert.equal(evmBalanceReads, 10, 'a different wallet must not reuse the prior wallet snapshot');
});

test('wire-level Solana mint precision and balances survive the lossless JSON parser', async () => {
  const { publicPurchaseRpc, paymentAssetDefinition } = await import('../src/purchase-chain.js');
  const fetcher: typeof fetch = async (_url, init) => {
    const { method } = JSON.parse(String(init?.body));
    const result = method === 'getGenesisHash' ? MAINNET_GENESIS : method === 'getTokenSupply' ? { value: { decimals: 6 } } : {
      value: [{ account: { data: { parsed: { info: { tokenAmount: { amount: '9007199254740993123', decimals: 6 } } } } } }],
    };
    return new Response(JSON.stringify({ jsonrpc: '2.0', id: '1', result }));
  };
  const chain = new PublicPurchaseChainReader(publicPurchaseRpc({}, fetcher));
  const usdc = paymentAssetDefinition('SOLANA:USDC')!;
  assert.equal(await chain.tokenDecimals('SOLANA', usdc.address), 6);
  assert.equal(await chain.balance(usdc, wallets[1]!), '9007199254740993123');
});

test('malformed or out-of-range mint precision never enables a stock deployment', async () => {
  for (const decimals of [true, null, '06', '6.5', '37', '10000000000000000000']) {
    const chain = new PublicPurchaseChainReader({ async call(_network, method) {
      return method === 'getGenesisHash' ? MAINNET_GENESIS : { value: { decimals } };
    } });
    await assert.rejects(chain.tokenDecimals('SOLANA', SOL_MINT));
  }
});

test('Solana purchase reads reject non-mainnet RPC before accepting balances or mint metadata', async () => {
  const methods: string[] = [];
  const chain = new PublicPurchaseChainReader({ async call(_network, method) {
    methods.push(method);
    return 'devnet-genesis';
  } });
  await assert.rejects(chain.balance(paymentAssetDefinition('SOLANA:SOL')!, wallets[1]!));
  await assert.rejects(chain.tokenDecimals('SOLANA', SOL_MINT));
  await assert.rejects(chain.tokenUiMultiplier('SOLANA', SOL_MINT, 9));
  assert.deepEqual(methods, ['getGenesisHash', 'getGenesisHash', 'getGenesisHash']);
});

test('Solana mainnet verification is shared, bounded and retried after transient failure', async () => {
  let genesisCalls = 0; let clock = 1_000; let fail = true;
  const chain = new PublicPurchaseChainReader({ async call(_network, method) {
    if (method === 'getGenesisHash') {
      genesisCalls++;
      if (fail) throw new Error('temporarily unavailable');
      return MAINNET_GENESIS;
    }
    if (method === 'getTokenSupply') return { value: { decimals: 9 } };
    if (method === 'getBalance') return { value: '123' };
    throw new Error('Unexpected RPC');
  } }, { async prices() { return new Map(); } }, () => clock);
  await assert.rejects(chain.tokenDecimals('SOLANA', SOL_MINT));
  fail = false;
  const results = await Promise.all([
    chain.balance(paymentAssetDefinition('SOLANA:SOL')!, wallets[1]!),
    chain.tokenDecimals('SOLANA', SOL_MINT),
  ]);
  assert.deepEqual(results, ['123', 9]);
  assert.equal(genesisCalls, 2);
  clock += 59_999;
  assert.equal(await chain.tokenDecimals('SOLANA', SOL_MINT), 9);
  assert.equal(genesisCalls, 2);
  clock++;
  await chain.tokenDecimals('SOLANA', SOL_MINT);
  assert.equal(genesisCalls, 3);
});

test('purchase transport failures are retryable without reclassifying invalid RPC parameters', async () => {
  const unavailable: typeof fetch[] = [
    async () => { throw new Error('private upstream details'); },
    async () => new Response('rate limited', { status: 429 }),
    async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: '1', error: { code: -32005, message: 'behind' } })),
  ];
  for (const fetcher of unavailable) {
    await assert.rejects(publicPurchaseRpc({}, fetcher).call('SOLANA', 'getTokenSupply', [SOL_MINT]),
      error => error instanceof PurchaseRpcUnavailableError && !error.message.includes('private'));
  }
  const invalid: typeof fetch = async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: '1',
    error: { code: -32602, message: 'Invalid account' } }));
  await assert.rejects(publicPurchaseRpc({}, invalid).call('SOLANA', 'getTokenSupply', [SOL_MINT]),
    error => error instanceof PurchaseRpcError && !(error instanceof PurchaseRpcUnavailableError));
});

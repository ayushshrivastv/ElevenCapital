import assert from 'node:assert/strict';
import test from 'node:test';
import { MAINNET_GENESIS, SOL_MINT, type PortfolioAsset } from '../src/portfolio-upstream.js';
import { PublicPurchaseChainReader, PurchaseRpcError, PurchaseRpcUnavailableError,
  paymentAssetDefinition, publicPurchaseRpc } from '../src/purchase-chain.js';
import type { PurchaseNetwork, PurchaseWallet } from '../src/purchase-schema.js';

const wallet: PurchaseWallet = {
  chain: 'SOLANA',
  address: '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J',
};

function rpc() {
  return { async call(_network: PurchaseNetwork, method: string): Promise<unknown> {
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'getBalance') return { value: '3000000000' };
    if (method === 'getTokenAccountsByOwner') return { value: [{ account: { data: { parsed: { info: {
      tokenAmount: { amount: '7000000', decimals: 6 },
    } } } } }] };
    if (method === 'getTokenSupply') return { value: { decimals: 6 } };
    throw new Error('Unexpected purchase RPC method');
  } };
}

test('Solana payment assets receive exact bounded USD values from the shared price reader', async () => {
  let requested: PortfolioAsset[] = [];
  const prices = { async prices(assets: PortfolioAsset[]) {
    requested = assets;
    return new Map(assets.map(asset => [asset.key, asset.key === 'SOLANA:SOL' ? '100' : '0.999']));
  } };
  const rows = await new PublicPurchaseChainReader(rpc(), prices).paymentAssets([wallet]);
  assert.deepEqual(rows.map(row => row.id), ['SOLANA:SOL', 'SOLANA:USDC']);
  assert.equal(rows[0]?.usdValue, '300');
  assert.equal(rows[1]?.usdValue, '6.993');
  assert.deepEqual(requested.find(asset => asset.key === 'SOLANA:SOL'), {
    key: 'SOLANA:SOL', chain: 'SOLANA', address: SOL_MINT, stockId: null, decimals: 9, pricing: 'solana',
  });
});

test('balances stay usable while unavailable prices remain explicitly unknown', async () => {
  const rows = await new PublicPurchaseChainReader(rpc(), {
    async prices(): Promise<Map<string, string>> { throw new Error('pricing unavailable'); },
  }).paymentAssets([wallet]);
  assert.equal(rows.length, 2);
  assert.ok(rows.every(row => row.enabled && BigInt(row.balanceBaseUnits) > 0n));
  assert.ok(rows.every(row => row.usdValue === null));
});

test('option reads are briefly shared but quote balance reads are always fresh', async () => {
  let clock = 1_000; let lamports = '3000000000'; let balanceReads = 0; let priceReads = 0;
  const chain = new PublicPurchaseChainReader({ async call(network, method, params) {
    if (method === 'getBalance') { balanceReads++; return { value: lamports }; }
    return rpc().call(network, method, params);
  } }, { async prices(assets) {
    priceReads++; return new Map(assets.map(asset => [asset.key, '1']));
  } }, () => clock);
  const [first, shared] = await Promise.all([chain.paymentAssets([wallet]), chain.paymentAssets([wallet])]);
  assert.deepEqual(first, shared); assert.equal(balanceReads, 1); assert.equal(priceReads, 1);
  lamports = '1000000000';
  assert.equal(await chain.balance(paymentAssetDefinition('SOLANA:SOL')!, wallet), lamports);
  assert.equal((await chain.paymentAssets([wallet]))[0]?.balance, '3');
  clock += 15_000;
  assert.equal((await chain.paymentAssets([wallet]))[0]?.balance, '1');
});

test('wire-level Solana balances preserve integers beyond JavaScript safe precision', async () => {
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
  assert.equal(await chain.balance(usdc, wallet), '9007199254740993123');
});

test('wrong-network and malformed mint observations fail closed', async () => {
  const wrong = new PublicPurchaseChainReader({ async call() { return 'devnet-genesis'; } });
  await assert.rejects(wrong.balance(paymentAssetDefinition('SOLANA:SOL')!, wallet));
  for (const decimals of [true, null, '06', '6.5', '37']) {
    const chain = new PublicPurchaseChainReader({ async call(_network, method) {
      return method === 'getGenesisHash' ? MAINNET_GENESIS : { value: { decimals } };
    } });
    await assert.rejects(chain.tokenDecimals('SOLANA', SOL_MINT));
  }
});

test('purchase transport separates retryable outages from invalid RPC parameters', async () => {
  const unavailable: typeof fetch = async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: '1',
    error: { code: -32005, message: 'behind' } }));
  await assert.rejects(publicPurchaseRpc({}, unavailable).call('SOLANA', 'getTokenSupply', [SOL_MINT]),
    error => error instanceof PurchaseRpcUnavailableError);
  const invalid: typeof fetch = async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: '1',
    error: { code: -32602, message: 'Invalid account' } }));
  await assert.rejects(publicPurchaseRpc({}, invalid).call('SOLANA', 'getTokenSupply', [SOL_MINT]),
    error => error instanceof PurchaseRpcError && !(error instanceof PurchaseRpcUnavailableError));
});

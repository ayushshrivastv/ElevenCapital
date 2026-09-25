import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { DEVNET_GENESIS, SolanaDevnetWalletService, type SolanaDevnetResponse } from '../src/solana-devnet-wallet.js';
import type { WalletActivityRpc } from '../src/wallet-activity.js';

const wallet = '11111111111111111111111111111111';
const faucet = 'Vote111111111111111111111111111111111111111';
const signature = '4'.repeat(87);
const at = 1_782_000_000_000;
const blockTime = at / 1_000;

function airdropTransaction() {
  return { blockTime, meta: { err: null, fee: 0, preBalances: ['0', '2000000000'],
    postBalances: ['1250000000', '750000000'], preTokenBalances: [], postTokenBalances: [] },
  transaction: { message: { accountKeys: [{ pubkey: wallet }, { pubkey: faucet }],
    instructions: [{ program: 'system', parsed: { type: 'transfer',
      info: { source: faucet, destination: wallet, lamports: '1250000000' } } }] } } };
}

test('confirmed Devnet faucet transfer changes the separate SOL balance and activity, never USD', async () => {
  const methods: string[] = [];
  const rpc: WalletActivityRpc = { async call(method) {
    methods.push(method);
    if (method === 'getGenesisHash') return DEVNET_GENESIS;
    if (method === 'getBalance') return { value: '1250000000' };
    if (method === 'getSignaturesForAddress') return [{ signature, slot: '10', blockTime, err: null }];
    return airdropTransaction();
  } };
  const result = await new SolanaDevnetWalletService(rpc, () => at).wallet(wallet);
  assert.equal(result.status, 'ok');
  assert.equal(result.network, 'SOLANA_DEVNET');
  assert.equal(result.balanceSol, '1.25');
  assert.deepEqual(result.transactions.map(row => [row.signature, row.direction, row.amount, row.counterparty]),
    [[signature, 'RECEIVE', '1.25', faucet]]);
  assert.ok(!JSON.stringify(result).includes('Usd'));
  assert.deepEqual(methods, ['getGenesisHash', 'getBalance', 'getSignaturesForAddress', 'getTransaction']);
});

test('mainnet or wrong Devnet RPC identity cannot appear as a faucet balance', async () => {
  for (const genesis of [MAINNET_GENESIS, 'unknown']) {
    const methods: string[] = [];
    const rpc: WalletActivityRpc = { async call(method) { methods.push(method); return genesis; } };
    const result = await new SolanaDevnetWalletService(rpc, () => at).wallet(wallet);
    assert.equal(result.status, 'unavailable');
    assert.equal(result.balanceSol, null);
    assert.deepEqual(result.transactions, []);
    assert.deepEqual(methods, ['getGenesisHash']);
  }
});

test('transaction-index outage retains an observed Devnet balance without inventing transfers', async () => {
  const rpc: WalletActivityRpc = { async call(method) {
    if (method === 'getGenesisHash') return DEVNET_GENESIS;
    if (method === 'getBalance') return { value: '1' };
    throw new Error('RPC unavailable');
  } };
  const result = await new SolanaDevnetWalletService(rpc, () => at).wallet(wallet);
  assert.equal(result.status, 'partial');
  assert.equal(result.balanceSol, '0.000000001');
  assert.deepEqual(result.transactions, []);
});

test('Devnet route accepts only a public Solana address and keeps network identity explicit', async () => {
  const snapshot: SolanaDevnetResponse = {
    schemaVersion: 1, scope: 'solana-devnet-wallet', network: 'SOLANA_DEVNET', walletAddress: wallet,
    status: 'ok', observedAt: new Date(at).toISOString(), balanceSol: '0', transactions: [],
  };
  const app = await buildApp(undefined, false, { devnet: { async wallet(address) {
    assert.equal(address, wallet); return snapshot;
  } } });
  const response = await app.inject({ method: 'POST', url: '/v1/wallet/devnet', payload: { walletAddress: wallet } });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().network, 'SOLANA_DEVNET');
  assert.equal(response.headers['cache-control'], 'no-store');
  for (const body of [{ walletAddress: 'not-a-key' }, { walletAddress: wallet, rpcUrl: 'https://evil.example' }]) {
    assert.equal((await app.inject({ method: 'POST', url: '/v1/wallet/devnet', payload: body })).statusCode, 400);
  }
  await app.close();
});

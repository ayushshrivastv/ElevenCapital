import assert from 'node:assert/strict';
import test from 'node:test';
import { MAINNET_GENESIS, SOL_USDC, TOKEN_PROGRAMS } from '../src/portfolio-upstream.js';
import { WalletActivityService, activityRowsFromTransaction, type WalletActivityRpc } from '../src/wallet-activity.js';

const wallet = '11111111111111111111111111111111';
const tokenAccount = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA';
const recipient = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL';
const signature = '4'.repeat(87);
const blockTime = 1_782_000_000;

function transaction(meta: Record<string, unknown>, keys: string[] = [wallet, recipient]) {
  return { blockTime, meta, transaction: { message: { accountKeys: keys.map(pubkey => ({ pubkey })), instructions: [] } } };
}

test('wallet SOL send removes network fee and does not invent a fee-only transfer', () => {
  const sent = transaction({ err: null, fee: 5_000, preBalances: [1_000_000, 0], postBalances: [895_000, 100_000],
    preTokenBalances: [], postTokenBalances: [] });
  sent.transaction.message.instructions.push({ program: 'system', parsed: { type: 'transfer',
    info: { source: wallet, destination: recipient, lamports: 100_000 } } } as never);
  const rows = activityRowsFromTransaction(sent, signature, wallet, new Set(), blockTime);
  assert.equal(rows?.length, 1);
  assert.equal(rows?.[0]?.direction, 'SEND');
  assert.equal(rows?.[0]?.amount, '0.0001');
  assert.equal(rows?.[0]?.counterparty, recipient);

  const feeOnly = transaction({ err: null, fee: 5_000, preBalances: [1_000_000, 0], postBalances: [995_000, 0],
    preTokenBalances: [], postTokenBalances: [] });
  assert.deepEqual(activityRowsFromTransaction(feeOnly, signature, wallet, new Set(), blockTime), []);
});

test('owned SPL token account receive is found even when wallet is absent from transaction keys', () => {
  const mintBalance = (amount: string) => ({ accountIndex: 0, mint: SOL_USDC, owner: wallet,
    uiTokenAmount: { amount, decimals: 6 } });
  const received = transaction({ err: null, fee: 5_000, preBalances: [2_039_280, 0],
    postBalances: [2_039_280, 0], preTokenBalances: [mintBalance('0')],
    postTokenBalances: [mintBalance('1234567')] }, [tokenAccount, recipient]);
  const rows = activityRowsFromTransaction(received, signature, wallet, new Set([tokenAccount]), blockTime);
  assert.equal(rows?.length, 1);
  assert.equal(rows?.[0]?.direction, 'RECEIVE');
  assert.equal(rows?.[0]?.amount, '1.234567');
  assert.equal(rows?.[0]?.assetSymbol, 'USDC');
  assert.equal(rows?.[0]?.mint, SOL_USDC);
});

test('SPL counterparty is shown only when token ownership proves the external sender', () => {
  const externalOwner = 'Vote111111111111111111111111111111111111111';
  const balance = (index: number, owner: string, amount: string) => ({ accountIndex: index,
    mint: SOL_USDC, owner, uiTokenAmount: { amount, decimals: 6 } });
  const received = transaction({ err: null, fee: 5_000, preBalances: [0, 2_039_280],
    postBalances: [0, 2_039_280],
    preTokenBalances: [balance(0, externalOwner, '1000000'), balance(1, wallet, '0')],
    postTokenBalances: [balance(0, externalOwner, '500000'), balance(1, wallet, '500000')],
  }, [recipient, tokenAccount]);
  received.transaction.message.instructions.push({ program: 'spl-token', parsed: { type: 'transferChecked',
    info: { source: recipient, destination: tokenAccount, mint: SOL_USDC } } } as never);
  const rows = activityRowsFromTransaction(received, signature, wallet, new Set([tokenAccount]), blockTime);
  assert.equal(rows?.[0]?.counterparty, externalOwner);
});

test('failed transactions and internal SOL rent movements do not display as transfers', () => {
  const failed = transaction({ err: { InstructionError: [0, 'Custom'] }, fee: 5_000, preBalances: [1_000_000],
    postBalances: [995_000], preTokenBalances: [], postTokenBalances: [] }, [wallet]);
  assert.deepEqual(activityRowsFromTransaction(failed, signature, wallet, new Set(), blockTime), []);
  const rent = transaction({ err: null, fee: 5_000, preBalances: [1_000_000, 0], postBalances: [895_000, 100_000],
    preTokenBalances: [], postTokenBalances: [] }, [wallet, tokenAccount]);
  assert.deepEqual(activityRowsFromTransaction(rent, signature, wallet, new Set([tokenAccount]), blockTime), []);
});

test('service scans owned token-account signatures to discover external receives', async () => {
  const calls: string[] = [];
  const rpc: WalletActivityRpc = { async call(method, params) {
    calls.push(method);
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'getTokenAccountsByOwner') return { value: params[1] &&
      (params[1] as { programId: string }).programId === TOKEN_PROGRAMS[0] ? [{ pubkey: tokenAccount }] : [] };
    if (method === 'getSignaturesForAddress') return params[0] === tokenAccount ?
      [{ signature, slot: '1', blockTime, err: null }] : [];
    return transaction({ err: null, fee: 5_000, preBalances: [2_039_280, 0], postBalances: [2_039_280, 0],
      preTokenBalances: [{ accountIndex: 0, mint: SOL_USDC, owner: wallet,
        uiTokenAmount: { amount: '0', decimals: 6 } }],
      postTokenBalances: [{ accountIndex: 0, mint: SOL_USDC, owner: wallet,
        uiTokenAmount: { amount: '500000', decimals: 6 } }] }, [tokenAccount, recipient]);
  } };
  const service = new WalletActivityService(rpc, () => 1_782_000_000_000,
    { async prices() { return new Map([[`SOLANA:${SOL_USDC}`, '0.98']]); } });
  const response = await service.activity(wallet);
  assert.equal(response.status, 'ok');
  assert.equal(response.walletAddress, wallet);
  assert.equal(response.transactions[0]?.amount, '0.5');
  assert.equal(response.transactions[0]?.valueUsd, '0.49');
  assert.equal(response.transactions[0]?.direction, 'RECEIVE');
  assert.ok(calls.includes('getTransaction'));
});

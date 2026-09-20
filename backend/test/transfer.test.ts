import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { keccak256Hex } from '../src/keccak.js';
import { ETH_USDC, MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { TransferRequestSchema } from '../src/transfer-schema.js';
import { buildSolanaTransfer, checksumEthereumAddress, decodeBase58, decodeSolanaTransfer, encodeBase58, exactBaseUnits,
  TransferPreparationError, TransferPreparationService, TransferRateLimiter } from '../src/transfer.js';
import { publicTransferRpc, type TransferRpc } from '../src/transfer-rpc.js';

const NOW = Date.parse('2026-09-22T12:00:00.000Z');
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const WALLET_ID = 'wallet-test-ethereum';
const ETH_SENDER = '0x1111111111111111111111111111111111111111';
const ETH_RECIPIENT = '0x2222222222222222222222222222222222222222';
const SOL_SENDER = '4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi';
const SOL_RECIPIENT = '8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR';
const BLOCKHASH = 'CktRuQ2mttgRGkXJtyksdKHjUdc2C4TgDzyB98oEzy8';

test('Keccak and EIP-55 validation use Ethereum hashing, not SHA3, and reject invalid mixed case', () => {
  assert.equal(keccak256Hex(''), 'c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470');
  assert.equal(keccak256Hex('abc'), '4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45');
  for (const address of ['0x52908400098527886E0F7030069857D2E4169EE7', '0xde709f2102306220921060314715629080e2fb77',
    '0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed']) assert.equal(checksumEthereumAddress(address), address);
  assert.throws(() => checksumEthereumAddress('0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed'), (error: unknown) =>
    error instanceof TransferPreparationError && error.code === 'invalid_address');
});

test('amount conversion is exact, rejects exponent/rounding/zero, and enforces integer bounds', () => {
  assert.equal(exactBaseUnits('1.000001', 6, (1n << 256n) - 1n), '1000001');
  assert.equal(exactBaseUnits('0.000000001', 9, (1n << 64n) - 1n), '1');
  for (const amount of ['0', '01', '1e2', '-1', ' 1', '1.0000001']) assert.throws(() => exactBaseUnits(amount, 6, (1n << 64n) - 1n));
  assert.throws(() => exactBaseUnits('18446744073.709551616', 9, (1n << 64n) - 1n));
});

test('Solana keys are canonical 32-byte base58 and unsigned legacy transfer self-decodes exactly', () => {
  assert.equal(encodeBase58(decodeBase58(SOL_SENDER)), SOL_SENDER);
  for (const value of ['1'.repeat(31), '1'.repeat(33), `${SOL_SENDER.slice(0, -1)}0`]) assert.throws(() => decodeBase58(value));
  const built = buildSolanaTransfer(SOL_SENDER, SOL_RECIPIENT, BLOCKHASH, 123456789n);
  assert.deepEqual(decodeSolanaTransfer(built.transaction), {
    sender: SOL_SENDER, recipient: SOL_RECIPIENT, recentBlockhash: BLOCKHASH, lamports: '123456789',
  });
  assert.equal(built.transaction[0], 1);
  assert.ok(built.transaction.slice(1, 65).every(value => value === 0), 'unsigned transaction must contain only a zero signature placeholder');
});

function ethereumRpc(overrides: Partial<Record<string, unknown | Error>> = {}): TransferRpc {
  return { async call(_chain, method, params) {
    const override = overrides[method]; if (override instanceof Error) throw override; if (override !== undefined) return override;
    if (method === 'eth_chainId') return '0x1';
    if (method === 'eth_getBlockByNumber') return { number: '0xabc', timestamp: `0x${Math.floor(NOW / 1000).toString(16)}`, baseFeePerGas: '0x3b9aca00' };
    if (method === 'eth_getTransactionCount') return '0x5';
    if (method === 'eth_getBalance') return '0xde0b6b3a7640000';
    if (method === 'eth_getCode') return params[0] === ETH_USDC ? '0x60006000' : '0x';
    if (method === 'eth_estimateGas') return (params[0] as { to: string }).to === ETH_USDC ? '0x11170' : '0x5208';
    if (method === 'eth_maxPriorityFeePerGas') return '0x77359400';
    if (method === 'eth_call') {
      const data = (params[0] as { data: string }).data;
      if (data === '0x313ce567') return `0x${'0'.repeat(63)}6`;
      if (data.startsWith('0x70a08231')) return `0x${'0'.repeat(58)}0f4240`;
      if (data.startsWith('0xa9059cbb')) return `0x${'0'.repeat(63)}1`;
      return '0x';
    }
    throw new Error(`Unexpected method ${method}`);
  } };
}

test('Ethereum native preparation verifies mainnet/freshness/balances/EOAs, estimates fees and simulates without broadcasting', async () => {
  const methods: string[] = [];
  const delegate = ethereumRpc();
  const service = new TransferPreparationService({ call: async (...args) => { methods.push(args[1]); return delegate.call(...args); } }, () => NOW);
  const result = await service.prepare({ schemaVersion: 1, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM', sender: ETH_SENDER,
    recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:native', amount: '0.1' });
  assert.equal(result.chain, 'ETHEREUM');
  assert.equal(result.caip2, 'eip155:1');
  assert.equal(result.baseUnits, '100000000000000000');
  assert.equal(result.balanceBaseUnits, '1000000000000000000');
  assert.equal(result.estimatedFeeBaseUnits, '63000000000000');
  assert.equal(result.maxFeeBaseUnits, '84000000000000');
  assert.deepEqual(result.transaction, { chainId: '0x1', from: ETH_SENDER, to: ETH_RECIPIENT, nonce: '0x5', gas: '0x5208',
    value: '0x16345785d8a0000', data: '0x', type: '0x2', maxFeePerGas: '0xee6b2800', maxPriorityFeePerGas: '0x77359400' });
  assert.ok(methods.includes('eth_estimateGas') && methods.includes('eth_call'));
  assert.ok(!methods.some(method => method.startsWith('eth_send')));
});

test('Ethereum canonical USDC preparation verifies exact contract/decimals and emits exact transfer calldata', async () => {
  const result = await new TransferPreparationService(ethereumRpc(), () => NOW).prepare({ schemaVersion: 1, operationId: OPERATION, walletId: WALLET_ID,
    chain: 'ETHEREUM', sender: ETH_SENDER, recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:USDC', amount: '0.123456' });
  assert.equal(result.chain, 'ETHEREUM');
  assert.equal(result.asset.address, ETH_USDC);
  assert.equal(result.baseUnits, '123456');
  assert.equal(result.balanceBaseUnits, '1000000');
  assert.equal(result.transaction.to, ETH_USDC);
  assert.equal(result.transaction.value, '0x0');
  assert.equal(result.transaction.data, `0xa9059cbb${ETH_RECIPIENT.slice(2).padStart(64, '0')}${(123456).toString(16).padStart(64, '0')}`);
});

test('Ethereum preparation fails closed for wrong network, stale data, contracts, insufficient funds and failed simulation', async () => {
  const request = { schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM' as const, sender: ETH_SENDER,
    recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:native' as const, amount: '0.1' };
  const cases: [Partial<Record<string, unknown | Error>>, string][] = [
    [{ eth_chainId: '0x89' }, 'wrong_network'],
    [{ eth_getBlockByNumber: { number: '0x1', timestamp: '0x1', baseFeePerGas: '0x1' } }, 'stale_chain_data'],
    [{ eth_getCode: '0x6000' }, 'invalid_address'],
    [{ eth_getCode: '0x00' }, 'invalid_address'],
    [{ eth_getBalance: '0x1' }, 'insufficient_asset_balance'],
    [{ eth_call: 'not-hex' }, 'simulation_failed'],
  ];
  for (const [overrides, code] of cases) await assert.rejects(new TransferPreparationService(ethereumRpc(overrides), () => NOW).prepare(request),
    (error: unknown) => error instanceof TransferPreparationError && error.code === code, code);
});

test('Ethereum preparation rejects catastrophic RPC fee and gas suggestions before signing', async () => {
  const request = { schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM' as const, sender: ETH_SENDER,
    recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:native' as const, amount: '0.1' };
  for (const overrides of [
    { eth_maxPriorityFeePerGas: '0x38d7ea4c68000' }, // 1,000,000 gwei
    { eth_getBlockByNumber: { number: '0xabc', timestamp: `0x${Math.floor(NOW / 1000).toString(16)}`, baseFeePerGas: '0x1d1a94a2000' } },
    { eth_estimateGas: '0xc3501' },
  ]) {
    await assert.rejects(new TransferPreparationService(ethereumRpc(overrides), () => NOW).prepare(request),
      (error: unknown) => error instanceof TransferPreparationError && error.code === 'rpc_unavailable');
  }
});

function solanaRpc(overrides: Partial<Record<string, unknown>> = {}): TransferRpc {
  return { async call(_chain, method, params) {
    if (method in overrides) return overrides[method];
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'getBalance') return { context: { slot: '9000' }, value: '2000000000' };
    if (method === 'getAccountInfo') return { context: { slot: '9000' }, value: { executable: false, owner: '11111111111111111111111111111111', data: ['', 'base64'] } };
    if (method === 'getLatestBlockhash') return { context: { slot: '9001' }, value: { blockhash: BLOCKHASH, lastValidBlockHeight: '12345' } };
    if (method === 'getFeeForMessage') { assert.ok(typeof params[0] === 'string'); return { context: { slot: '9002' }, value: '5000' }; }
    if (method === 'simulateTransaction') { assert.equal((params[1] as { sigVerify: boolean }).sigVerify, false); return { context: { slot: '9003' }, value: { err: null, logs: [] } }; }
    throw new Error(`Unexpected method ${method}`);
  } };
}

test('Solana native preparation validates mainnet accounts, fee, balance, serialized transaction and simulation', async () => {
  const methods: string[] = []; const delegate = solanaRpc();
  const service = new TransferPreparationService({ call: async (...args) => { methods.push(args[1]); return delegate.call(...args); } }, () => NOW);
  const result = await service.prepare({ schemaVersion: 1, operationId: OPERATION, walletId: WALLET_ID, chain: 'SOLANA', sender: SOL_SENDER,
    recipient: SOL_RECIPIENT, assetId: 'SOLANA:native', amount: '1.000000001' });
  assert.equal(result.chain, 'SOLANA');
  assert.equal(result.baseUnits, '1000000001');
  assert.equal(result.estimatedFeeBaseUnits, '5000');
  assert.equal(result.maxFeeBaseUnits, '5000');
  assert.equal(result.recentBlockhash, BLOCKHASH);
  assert.deepEqual(decodeSolanaTransfer(Buffer.from(result.transactionBase64, 'base64')), {
    sender: SOL_SENDER, recipient: SOL_RECIPIENT, recentBlockhash: BLOCKHASH, lamports: '1000000001',
  });
  assert.ok(methods.includes('getFeeForMessage') && methods.includes('simulateTransaction'));
  assert.ok(!methods.some(method => method.toLowerCase().includes('send')));
});

test('Solana rejects unsafe/program/self recipients and exposes typed unsupported USDC without guessing ATA behavior', async () => {
  const base = { schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'SOLANA' as const, sender: SOL_SENDER,
    recipient: SOL_RECIPIENT, assetId: 'SOLANA:native' as const, amount: '1' };
  await assert.rejects(new TransferPreparationService(solanaRpc({ getAccountInfo: { context: { slot: '9000' }, value: { executable: true, owner: SOL_SENDER, data: ['', 'base64'] } } }), () => NOW).prepare(base),
    (error: unknown) => error instanceof TransferPreparationError && ['invalid_address', 'unsafe_recipient'].includes(error.code));
  await assert.rejects(new TransferPreparationService(solanaRpc(), () => NOW).prepare({ ...base, recipient: SOL_SENDER }),
    (error: unknown) => error instanceof TransferPreparationError && error.code === 'unsafe_recipient');
  await assert.rejects(new TransferPreparationService(solanaRpc(), () => NOW).prepare({ ...base, assetId: 'SOLANA:USDC' }),
    (error: unknown) => error instanceof TransferPreparationError && error.code === 'unsupported_asset' && error.message.includes('transferChecked'));
});

test('Solana preparation rejects an abnormal RPC fee quote', async () => {
  const request = { schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'SOLANA' as const, sender: SOL_SENDER,
    recipient: SOL_RECIPIENT, assetId: 'SOLANA:native' as const, amount: '1' };
  await assert.rejects(new TransferPreparationService(solanaRpc({
    getFeeForMessage: { context: { slot: '9002' }, value: '5000001' },
  }), () => NOW).prepare(request),
  (error: unknown) => error instanceof TransferPreparationError && error.code === 'rpc_unavailable');
});

test('request contract is strict, chain-bound, exponent-free and rate limiter is bounded', () => {
  const valid = { schemaVersion: 1, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM', sender: ETH_SENDER, recipient: ETH_RECIPIENT,
    assetId: 'ETHEREUM:native', amount: '1' };
  assert.equal(TransferRequestSchema.safeParse(valid).success, true);
  for (const value of [{ ...valid, amount: '1e2' }, { ...valid, assetId: 'SOLANA:native' }, { ...valid, rpcUrl: 'https://evil.example' },
    { ...valid, operationId: 'not-uuid' }]) assert.equal(TransferRequestSchema.safeParse(value).success, false);
  const limiter = new TransferRateLimiter(() => NOW, 2);
  limiter.take(); limiter.take();
  assert.throws(() => limiter.take(), (error: unknown) => error instanceof TransferPreparationError && error.code === 'rate_limited');
});

test('HTTP endpoint returns no-store unsigned preparation, rejects oversized/extra input and exposes typed rate limits', async () => {
  const service = new TransferPreparationService(ethereumRpc(), () => NOW);
  const limiter = new TransferRateLimiter(() => NOW, 1);
  const auth = { verifyAuthorization: async () => ({ subject: 'did:privy:test-user', sessionId: 'session-one' }) };
  const ledger = { registerPrepared() {}, commit() { throw new Error(); }, release() { throw new Error(); },
    recordTransaction() {}, recordObservation() {} };
  const app = await buildApp(undefined, false, { transfer: service, transferRateLimiter: limiter, accessTokenVerifier: auth, transferIntentLedger: ledger });
  const payload = { schemaVersion: 1, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM', sender: ETH_SENDER,
    recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:native', amount: '0.1' };
  const first = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare', headers: { authorization: 'Bearer test' }, payload });
  assert.equal(first.statusCode, 200); assert.equal(first.headers['cache-control'], 'no-store');
  assert.ok(!JSON.stringify(first.json()).includes('signature'));
  const limited = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare', headers: { authorization: 'Bearer test' }, payload: { ...payload, operationId: '37a9b840-3e9d-4ed5-a22f-c886327d0920' } });
  assert.equal(limited.statusCode, 429); assert.equal(limited.json().error, 'rate_limited'); assert.equal(limited.headers['retry-after'], '60');
  const app2 = await buildApp(undefined, false, { transfer: service, accessTokenVerifier: auth, transferIntentLedger: ledger });
  assert.equal((await app2.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare', payload: { ...payload, rpcUrl: 'https://evil.example' } })).statusCode, 400);
  assert.equal((await app2.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare', payload: { ...payload, padding: 'x'.repeat(2_000) } })).statusCode, 413);
  assert.equal((await app2.inject({ method: 'GET', url: '/v1/wallet/transfer/prepare' })).statusCode, 404);
  await app.close(); await app2.close();
});

test('public transfer RPC uses fixed HTTPS origins and cannot call signing or broadcast methods', async () => {
  const original = globalThis.fetch; let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response('{"jsonrpc":"2.0","id":"1","result":"0x1"}'); };
  try {
    const rpc = publicTransferRpc({ ETHEREUM_RPC_URL: 'https://ethereum.example/rpc', SOLANA_RPC_URL: 'https://solana.example/rpc' });
    await assert.rejects(rpc.call('ETHEREUM', 'eth_sendTransaction', [], new AbortController().signal), /Only bounded/);
    await assert.rejects(rpc.call('SOLANA', 'sendTransaction', [], new AbortController().signal), /Only bounded/);
    assert.equal(calls, 0);
    assert.equal(await rpc.call('ETHEREUM', 'eth_chainId', [], new AbortController().signal), '0x1');
    assert.equal(calls, 1);
  } finally { globalThis.fetch = original; }
});

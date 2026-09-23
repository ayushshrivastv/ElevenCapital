import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { JUPITER_V6, SolanaSwapValidationError, validateSolanaSwap, readSolanaMintUiMultiplier, type SolanaSwapRpc } from '../src/solana-swap-validation.js';
import { decodeBase58, encodeBase58 } from '../src/transfer.js';
import type { JupiterOrderQuote } from '../src/jupiter-trade.js';

const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA';
const TOKEN22 = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb';
const SYSTEM = '11111111111111111111111111111111';
const COMPUTE = 'ComputeBudget111111111111111111111111111111';
const ASSOCIATED = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL';
const LOOKUP = 'AddressLookupTab1e1111111111111111111111111';
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const WSOL = 'So11111111111111111111111111111111111111112';
const MSFT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const key = (name: string) => encodeBase58(createHash('sha256').update(name).digest());
const WALLET = key('wallet'); const SOURCE = key('source'); const DEST = key('dest'); const FEE = key('fee');
const EXTRA = key('another-wallet-token'); const EVENT = key('event'); const TABLE = key('table');
const integer = (value: bigint, length: number): Buffer => { const b = Buffer.alloc(length); if (length === 8) b.writeBigUInt64LE(value); else if (length === 4) b.writeUInt32LE(Number(value)); else b.writeUInt16LE(Number(value)); return b; };
function raw(data: Buffer, owner = TOKEN, lamports = 2039280) { return { owner, lamports, data: [data.toString('base64'), 'base64'], executable: false }; }
function token(mint: string, amount: bigint, owner = WALLET) {
  const data = Buffer.alloc(165); Buffer.from(decodeBase58(mint)).copy(data); Buffer.from(decodeBase58(owner)).copy(data, 32);
  data.writeBigUInt64LE(amount, 64); data[108] = 1; return raw(data, mint === MSFT ? TOKEN22 : TOKEN);
}
function mint(decimals: number, program: string) { const data = Buffer.alloc(82); data[44] = decimals; data[45] = 1; return raw(data, program); }
interface Ix { program: string; accounts: string[]; data: Buffer }
function route(overrides: { input?: bigint; output?: bigint; slippage?: number; fee?: number; variant?: number; recipient?: string } = {}): Ix {
  return { program: JUPITER_V6, accounts: [TOKEN, WALLET, SOURCE, overrides.recipient ?? DEST, JUPITER_V6, MSFT, FEE, EVENT, JUPITER_V6],
    data: Buffer.concat([createHash('sha256').update('global:route').digest().subarray(0, 8), integer(1n, 4),
      Buffer.from([overrides.variant ?? 38, 100, 0, 1]), integer(overrides.input ?? 100000000n, 8), integer(overrides.output ?? 20000000n, 8),
      integer(BigInt(overrides.slippage ?? 50), 2), Buffer.from([overrides.fee ?? 10])]) };
}
function serialize(ixs: Ix[], options: { versioned?: boolean; lookup?: boolean; signed?: boolean; suffix?: Buffer } = {}) {
  const writable = [WALLET, SOURCE, DEST, FEE, EXTRA];
  const readonly = [...new Set(ixs.flatMap(ix => [ix.program, ...ix.accounts]))].filter(k => !writable.includes(k));
  const staticReadonly = options.lookup ? readonly.filter(k => k !== EVENT) : readonly;
  const statics = [...writable, ...staticReadonly]; const all = options.lookup ? [...statics, EVENT] : statics;
  const compiled = ixs.map(ix => { const indices = ix.accounts.map(a => all.indexOf(a)); return Buffer.concat([Buffer.from([all.indexOf(ix.program), indices.length, ...indices, ix.data.length]), ix.data]); });
  const lookups = options.lookup ? Buffer.concat([Buffer.from([1]), Buffer.from(decodeBase58(TABLE)), Buffer.from([0, 1, 0])]) : Buffer.from([0]);
  return Buffer.concat([Buffer.from([1]), Buffer.alloc(64, options.signed ? 1 : 0), ...(options.versioned ? [Buffer.from([128])] : []),
    Buffer.from([1, 0, staticReadonly.length, statics.length]), ...statics.map(k => Buffer.from(decodeBase58(k))), Buffer.alloc(32, 1),
    Buffer.from([ixs.length]), ...compiled, ...(options.versioned ? [lookups] : []), ...(options.suffix ? [options.suffix] : [])]).toString('base64');
}
function quote(transactionBase64 = serialize([route()])): JupiterOrderQuote {
  return { inputMint: USDC, outputMint: MSFT, inputDecimals: 6, outputDecimals: 8, inAmount: '100000000', outAmount: '20000000',
    minimumOutputAmount: '19900000', inputAmount: '100', outputAmount: '0.2', minimumOutput: '0.199',
    requestId: 'fixture', router: 'metis', mode: 'manual', slippageBps: 50, inputUsdValue: '100', outputUsdValue: '100', priceImpactPercent: '0',
    feeMint: USDC, feeBps: 10, platformFeeAmount: null, signatureFeeLamports: '5000', prioritizationFeeLamports: '0', rentFeeLamports: '0',
    signatureFeePayer: WALLET, prioritizationFeePayer: WALLET, rentFeePayer: WALLET, taker: WALLET, transactionBase64,
    lastValidBlockHeight: '2000', expiresAt: null, gasless: false, quoteOnly: false, executableTransactionAvailable: true,
    safetyValidationRequired: true, buildErrorCode: null, buildErrorReason: null, receivedAt: '2026-09-23T00:00:00Z' };
}
function fixture() {
  const before: Record<string, unknown> = { [WALLET]: raw(Buffer.alloc(0), SYSTEM, 100000000), [SOURCE]: token(USDC, 200000000n),
    [DEST]: token(MSFT, 0n), [FEE]: token(USDC, 0n, key('protocol')), [EXTRA]: token(USDC, 99000000n),
    [USDC]: mint(6, TOKEN), [MSFT]: mint(8, TOKEN22) };
  const after: Record<string, unknown> = { ...before, [WALLET]: raw(Buffer.alloc(0), SYSTEM, 99995000),
    [SOURCE]: token(USDC, 100000000n), [DEST]: token(MSFT, 20000000n) };
  let simulationErr: unknown = null; let genesis = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d'; let height = 1000;
  let fee = 5000; let lookupOwner = LOOKUP; let lookupSlot = 900n; let lookupActive = true;
  const calls: string[] = [];
  const rpc: SolanaSwapRpc = { async call(method, params) {
    calls.push(method);
    if (method === 'getGenesisHash') return genesis;
    if (method === 'getBlockHeight') return height;
    if (method === 'getMultipleAccounts') return { context: { slot: 1001 }, value: (params[0] as string[]).map(a => before[a] ?? null) };
    if (method === 'getFeeForMessage') return { context: { slot: 1001 }, value: fee };
    if (method === 'simulateTransaction') {
      assert.equal((params[1] as { sigVerify: boolean }).sigVerify, false);
      assert.equal((params[1] as { replaceRecentBlockhash: boolean }).replaceRecentBlockhash, false);
      const addresses = (params[1] as { accounts: { addresses: string[] } }).accounts.addresses;
      return { context: { slot: 1002 }, value: { err: simulationErr, accounts: addresses.map(a => after[a] ?? null) } };
    }
    if (method === 'getAccountInfo') {
      assert.equal(params[0], TABLE); const data = Buffer.alloc(88); data.writeUInt32LE(1); data.writeBigUInt64LE(lookupActive ? (1n << 64n) - 1n : 99n, 4);
      data.writeBigUInt64LE(lookupSlot, 12); Buffer.from(decodeBase58(EVENT)).copy(data, 56);
      return { context: { slot: 1000 }, value: raw(data, lookupOwner) };
    }
    assert.fail(`Unexpected RPC method ${method}`);
  } };
  return { before, after, calls, rpc, setSimulationError: (v: unknown) => { simulationErr = v; }, setGenesis: (v: string) => { genesis = v; },
    setHeight: (v: number) => { height = v; }, setFee: (v: number) => { fee = v; }, setLookupOwner: (v: string) => { lookupOwner = v; },
    setLookupSlot: (v: bigint) => { lookupSlot = v; }, setLookupActive: (v: boolean) => { lookupActive = v; } };
}
const rejected = (error: unknown) => error instanceof SolanaSwapValidationError;

test('verified exact-input legacy and v0 swaps bind wallet, mints, amounts and independently simulated account changes', async () => {
  for (const versioned of [false, true]) {
    const f = fixture(); const q = quote(serialize([route()], { versioned }));
    const result = await validateSolanaSwap(q, f.rpc);
    assert.equal(result.sourceTokenAccount, SOURCE); assert.equal(result.destinationTokenAccount, DEST);
    assert.equal(result.minimumOutputBaseUnits, '19900000'); assert.equal(result.maximumNativeCostLamports, '5000');
    assert.equal(result.simulationSlot, 1002); assert.equal(result.transactionBase64, q.transactionBase64);
    assert.equal(f.calls.includes('simulateTransaction'), true); assert.equal(f.calls.some(c => /send|sign/i.test(c)), false);
  }
});

test('current onchain route_v2 with Meteora DLMM binds its new account layout, u16 fees and exact-input fields', async () => {
  const current: Ix = { program: JUPITER_V6, accounts: [WALLET, SOURCE, DEST, USDC, MSFT, TOKEN, TOKEN22, JUPITER_V6,
    'D8cy77BBepLMngZx6ZukaTff5hCt1HrWyKk3Hnd9oitf', JUPITER_V6], data: Buffer.concat([
      createHash('sha256').update('global:route_v2').digest().subarray(0, 8), integer(100000000n, 8), integer(20000000n, 8),
      integer(50n, 2), integer(10n, 2), integer(0n, 2), integer(1n, 4), Buffer.from([75]), integer(0n, 4), integer(10000n, 2), Buffer.from([0, 1])]) };
  const q = quote(serialize([current], { versioned: true }));
  assert.equal((await validateSolanaSwap(q, fixture().rpc)).minimumOutputBaseUnits, '19900000');
  const wrongRecipient = { ...current, accounts: current.accounts.map((a, i) => i === 2 ? EXTRA : a) };
  await assert.rejects(validateSolanaSwap(quote(serialize([wrongRecipient])), fixture().rpc), rejected);
  for (const offset of [8, 16, 24, 26, 28, 34, 39]) {
    const data = Buffer.from(current.data); data[offset] = data[offset]! ^ 128;
    await assert.rejects(validateSolanaSwap(quote(serialize([{ ...current, data }])), fixture().rpc), rejected);
  }
});

test('v0 address lookup tables require exact owner, active state, matured slot and bounded indices', async () => {
  const q = quote(serialize([route()], { versioned: true, lookup: true }));
  assert.equal((await validateSolanaSwap(q, fixture().rpc)).sourceTokenAccount, SOURCE);
  for (const change of [(f: ReturnType<typeof fixture>) => f.setLookupOwner(SYSTEM), (f: ReturnType<typeof fixture>) => f.setLookupSlot(1000n),
    (f: ReturnType<typeof fixture>) => f.setLookupActive(false)]) { const f = fixture(); change(f); await assert.rejects(validateSolanaSwap(q, f.rpc), rejected); }
});

test('signed transactions, extra signers, trailing data and unreviewed Jupiter route variants fail closed', async () => {
  const variants = [serialize([route()], { signed: true }), serialize([route()], { suffix: Buffer.from([0]) }), serialize([route({ variant: 39 })])];
  for (const tx of variants) await assert.rejects(validateSolanaSwap(quote(tx), fixture().rpc), rejected);
  const changed = Buffer.from(serialize([route()]), 'base64'); changed[65] = 2;
  await assert.rejects(validateSolanaSwap(quote(changed.toString('base64')), fixture().rpc), rejected);
});

test('instruction amount, output recipient, slippage and platform fee are matched to the reviewed quote', async () => {
  for (const ix of [route({ input: 1n }), route({ output: 1n }), route({ slippage: 100 }), route({ fee: 20 }), route({ recipient: EXTRA })]) {
    await assert.rejects(validateSolanaSwap(quote(serialize([ix])), fixture().rpc), rejected);
  }
  const f = fixture(); f.before[FEE] = token(MSFT, 0n, key('protocol'));
  await assert.rejects(validateSolanaSwap(quote(), f.rpc), rejected);
});

test('unrelated transfers and approval or authority-change instructions cannot accompany a swap', async () => {
  const attacks: Ix[] = [{ program: SYSTEM, accounts: [WALLET, FEE], data: Buffer.concat([integer(2n, 4), integer(100n, 8)]) },
    { program: TOKEN, accounts: [SOURCE, FEE, WALLET], data: Buffer.from([4]) },
    { program: TOKEN, accounts: [SOURCE, WALLET], data: Buffer.from([6]) },
    { program: key('untrusted-program'), accounts: [WALLET], data: Buffer.alloc(0) }];
  for (const attack of attacks) await assert.rejects(validateSolanaSwap(quote(serialize([attack, route()])), fixture().rpc), rejected);
});

test('a simulated drain or authority change on any writable wallet token rejects the transaction', async () => {
  for (const change of [
    (f: ReturnType<typeof fixture>) => { f.after[EXTRA] = token(USDC, 1n); },
    (f: ReturnType<typeof fixture>) => { f.after[EXTRA] = token(USDC, 99000000n, key('attacker')); },
    (f: ReturnType<typeof fixture>) => { f.after[EXTRA] = null; },
    (f: ReturnType<typeof fixture>) => { const a = token(USDC, 99000000n); a.lamports = 1; f.after[EXTRA] = a; },
    (f: ReturnType<typeof fixture>) => { f.after[WALLET] = raw(Buffer.alloc(0), SYSTEM, 50000000); },
  ]) { const f = fixture(); change(f); await assert.rejects(validateSolanaSwap(quote(), f.rpc), rejected); }
});

test('simulation must prove the exact input spent and minimum output received by the same wallet', async () => {
  for (const [address, replacement] of [[SOURCE, token(USDC, 99999999n)], [DEST, token(MSFT, 19899999n)],
    [DEST, token(MSFT, 20000000n, key('attacker'))]]) { const f = fixture(); f.after[address as string] = replacement; await assert.rejects(validateSolanaSwap(quote(), f.rpc), rejected); }
  const f = fixture(); f.setSimulationError({ InstructionError: [0, 'error'] });
  await assert.rejects(validateSolanaSwap(quote(), f.rpc), e => e instanceof SolanaSwapValidationError && e.code === 'simulation_failed');
});

test('network, expiry, mint precision and compute fee limits cannot be bypassed', async () => {
  const wrongChain = fixture(); wrongChain.setGenesis('devnet'); await assert.rejects(validateSolanaSwap(quote(), wrongChain.rpc), rejected);
  const expired = fixture(); expired.setHeight(1995); await assert.rejects(validateSolanaSwap(quote(), expired.rpc), rejected);
  const precision = fixture(); precision.before[MSFT] = mint(6, TOKEN22); await assert.rejects(validateSolanaSwap(quote(), precision.rpc), rejected);
  const expensive = fixture(); expensive.setFee(10000001); await assert.rejects(validateSolanaSwap(quote(), expensive.rpc), rejected);
  const hugeCompute: Ix = { program: COMPUTE, accounts: [], data: Buffer.concat([Buffer.from([2]), integer(1400001n, 4)]) };
  await assert.rejects(validateSolanaSwap(quote(serialize([hugeCompute, route()])), fixture().rpc), rejected);
  const hugePrice: Ix = { program: COMPUTE, accounts: [], data: Buffer.concat([Buffer.from([3]), integer(100000000n, 8)]) };
  await assert.rejects(validateSolanaSwap(quote(serialize([hugePrice, route()])), fixture().rpc), rejected);
});

test('new receiving ATA is allowed only for the reviewed wallet and mint with bounded creation rent', async () => {
  const ata: Ix = { program: ASSOCIATED, accounts: [WALLET, DEST, WALLET, MSFT, SYSTEM, TOKEN22], data: Buffer.from([1]) };
  const f = fixture(); f.before[DEST] = null; f.after[WALLET] = raw(Buffer.alloc(0), SYSTEM, 99995000 - 2039280);
  assert.equal((await validateSolanaSwap(quote(serialize([ata, route()])), f.rpc)).maximumNativeCostLamports, '2044280');
  const malicious = { ...ata, accounts: [WALLET, DEST, key('attacker'), MSFT, SYSTEM, TOKEN22] };
  await assert.rejects(validateSolanaSwap(quote(serialize([malicious, route()])), f.rpc), rejected);
});

test('RPC outages and missing observations never mark a route execution eligible', async () => {
  await assert.rejects(validateSolanaSwap(quote(), { async call() { throw new Error('offline'); } }), e => e instanceof SolanaSwapValidationError && e.code === 'rpc_unavailable');
  for (const change of [{ router: 'jupiterz' as const }, { gasless: true }, { transactionBase64: null }, { lastValidBlockHeight: null }]) {
    await assert.rejects(validateSolanaSwap({ ...quote(), ...change }, fixture().rpc), rejected);
  }
});

test('scaled share units must match independently verified local quote metadata; unsupported mint extensions fail closed', async () => {
  function extended(type: number, size: number, current = 1, next = 1) {
    const data = Buffer.alloc(170 + size); data[44] = 8; data[45] = 1; data[165] = 1;
    data.writeUInt16LE(type, 166); data.writeUInt16LE(size, 168);
    if (type === 25 && size === 56) { data.writeDoubleLE(current, 202); data.writeDoubleLE(next, 218); }
    return raw(data, TOKEN22);
  }
  const unit = fixture(); unit.before[MSFT] = extended(25, 56);
  assert.equal((await validateSolanaSwap(quote(), unit.rpc)).minimumOutputBaseUnits, '19900000');
  const scaled = fixture(); scaled.before[MSFT] = extended(25, 56, 1, 1.0059033904787456);
  assert.equal(readSolanaMintUiMultiplier(scaled.before[MSFT], 8), '1.0059033904787456');
  assert.equal((await validateSolanaSwap({ ...quote(), outputUiMultiplier: '1.0059033904787456' }, scaled.rpc)).minimumOutputBaseUnits, '19900000');
  for (const account of [extended(25, 56, 1.0059033904787456, 1.0059033904787456), extended(25, 56, 1, 1.01), extended(25, 56, NaN),
    extended(25, 55), extended(10, 52), extended(29, 4)]) {
    const f = fixture(); f.before[MSFT] = account;
    await assert.rejects(validateSolanaSwap(quote(), f.rpc), rejected);
    assert.equal(f.calls.includes('simulateTransaction'), false);
  }
});

test('mint multiplier uses the active onchain f64 and rejects a scheduled transition during quote lifetime', () => {
  const at = 1_800_000_000_000;
  function scaled(effective: bigint, current: number, next: number) {
    const data = Buffer.alloc(226); data[44] = 8; data[45] = 1; data[165] = 1;
    data.writeUInt16LE(25, 166); data.writeUInt16LE(56, 168);
    data.writeDoubleLE(current, 202); data.writeBigInt64LE(effective, 210); data.writeDoubleLE(next, 218);
    return raw(data, TOKEN22);
  }
  assert.equal(readSolanaMintUiMultiplier(scaled(1_799_999_999n, 1, 1.005), 8, at), '1.005');
  assert.equal(readSolanaMintUiMultiplier(scaled(1_800_000_091n, 1.005, 1.006), 8, at), '1.005');
  assert.equal(readSolanaMintUiMultiplier(scaled(1_800_000_010n, 1.005, 1.005), 8, at), '1.005');
  assert.throws(() => readSolanaMintUiMultiplier(scaled(1_800_000_010n, 1.005, 1.006), 8, at), rejected);
  assert.throws(() => readSolanaMintUiMultiplier(scaled(1_800_000_030n, 1.005, 1.006), 8, at), rejected);
  assert.throws(() => readSolanaMintUiMultiplier(scaled(1_800_000_090n, 1.005, 1.006), 8, at), rejected);
  assert.throws(() => readSolanaMintUiMultiplier(scaled(0n, 1, Infinity), 8, at), rejected);
});

test('native SOL funding permits only exact wrap, sync and same-wallet close with bounded total debit', async () => {
  const wrap: Ix = { program: SYSTEM, accounts: [WALLET, SOURCE], data: Buffer.concat([integer(2n, 4), integer(100000000n, 8)]) };
  const sync: Ix = { program: TOKEN, accounts: [SOURCE], data: Buffer.from([17]) };
  const close: Ix = { program: TOKEN, accounts: [SOURCE, WALLET, WALLET], data: Buffer.from([9]) };
  const f = fixture(); f.before[WSOL] = mint(9, TOKEN); f.before[SOURCE] = token(WSOL, 0n);
  f.before[WALLET] = raw(Buffer.alloc(0), SYSTEM, 1000000000); f.after[WALLET] = raw(Buffer.alloc(0), SYSTEM, 899995000);
  f.after[SOURCE] = null; f.before[FEE] = token(WSOL, 0n, key('protocol'));
  const q = { ...quote(serialize([wrap, sync, route(), close])), inputMint: WSOL, inputDecimals: 9, feeMint: WSOL };
  assert.equal((await validateSolanaSwap(q, f.rpc)).minimumOutputBaseUnits, '19900000');
  await assert.rejects(validateSolanaSwap({ ...q, transactionBase64: serialize([wrap, route(), close]) }, f.rpc), rejected);
  f.after[WALLET] = raw(Buffer.alloc(0), SYSTEM, 800000000);
  await assert.rejects(validateSolanaSwap(q, f.rpc), rejected);
});

test('native SOL output requires a new wrapped account so an existing rent refund cannot disguise a short receipt', async () => {
  const swap = route(); swap.accounts[5] = WSOL;
  const ata: Ix = { program: ASSOCIATED, accounts: [WALLET, DEST, WALLET, WSOL, SYSTEM, TOKEN], data: Buffer.from([1]) };
  const close: Ix = { program: TOKEN, accounts: [DEST, WALLET, WALLET], data: Buffer.from([9]) };
  const f = fixture(); f.before[WSOL] = mint(9, TOKEN); f.before[DEST] = null; f.after[DEST] = null;
  f.after[WALLET] = raw(Buffer.alloc(0), SYSTEM, 119995000);
  const q = { ...quote(serialize([ata, swap, close])), outputMint: WSOL, outputDecimals: 9 };
  assert.equal((await validateSolanaSwap(q, f.rpc)).minimumOutputBaseUnits, '19900000');
  f.before[DEST] = token(WSOL, 0n);
  await assert.rejects(validateSolanaSwap(q, f.rpc), e => e instanceof SolanaSwapValidationError && e.code === 'unsupported_transaction');
});

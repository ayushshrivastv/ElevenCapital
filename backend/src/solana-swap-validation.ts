import { createHash } from 'node:crypto';
import { decodeBase58, encodeBase58 } from './transfer.js';
import type { JupiterOrderQuote } from './jupiter-trade.js';
import { MAINNET_GENESIS } from './portfolio-upstream.js';
import { Decimal } from 'decimal.js';

export const JUPITER_V6 = 'JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4';
const TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA';
const TOKEN_2022 = 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb';
const ASSOCIATED = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL';
const SYSTEM = '11111111111111111111111111111111';
const COMPUTE = 'ComputeBudget111111111111111111111111111111';
const LOOKUP = 'AddressLookupTab1e1111111111111111111111111';
const WSOL = 'So11111111111111111111111111111111111111112';
const MAX_NATIVE_COST = 10_000_000n;
// Cover the purchase service's maximum quote lifetime, including routes whose
// expiry is longer than the usual 20-second Jupiter review window.
const MAX_QUOTE_LIFETIME_SECONDS = 90n;
const MAX_U64 = (1n << 64n) - 1n;
// V1 layouts: official jup-ag/jupiter-cpi IDL. V2 layouts: current program-owned
// Anchor IDL C88XWfp26heEmDkmfSzeXP7Fd7GQJ2j9dDTUsyiZbUTa, read from mainnet.
// Full IDL, owner, observation slot and source hashes: verification/provider-trade.
const discriminator = (name: string) => createHash('sha256').update(`global:${name}`).digest().subarray(0, 8);

export interface SolanaSwapRpc { call(method: string, params: unknown[]): Promise<unknown> }
export interface ValidatedSolanaSwap {
  transactionBase64: string; messageSha256: string; sourceTokenAccount: string; destinationTokenAccount: string;
  minimumOutputBaseUnits: string; estimatedNetworkFeeLamports: string; maximumNativeCostLamports: string;
  simulationSlot: number; lastValidBlockHeight: string;
}
export class SolanaSwapValidationError extends Error {
  constructor(readonly code: 'unsupported_transaction' | 'transaction_mismatch' | 'rpc_unavailable' | 'simulation_failed' | 'quote_expired', message: string) { super(message); }
}
function reject(message: string): never { throw new SolanaSwapValidationError('transaction_mismatch', message); }
function unsupported(message: string): never { throw new SolanaSwapValidationError('unsupported_transaction', message); }
function record(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return reject('An RPC observation has an invalid shape.');
  return value as Record<string, unknown>;
}
function uint(value: unknown, max = MAX_U64): bigint {
  if ((typeof value !== 'string' || !/^(?:0|[1-9]\d*)$/.test(value)) && (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)) return reject('An RPC integer is invalid.');
  const parsed = BigInt(value); if (parsed > max) return reject('An RPC integer exceeded its bound.'); return parsed;
}
class Reader {
  at = 0;
  constructor(readonly bytes: Buffer) {}
  take(n: number): Buffer { if (!Number.isInteger(n) || n < 0 || this.at + n > this.bytes.length) reject('The transaction is truncated.'); const out = this.bytes.subarray(this.at, this.at + n); this.at += n; return out; }
  u8(): number { return this.take(1)[0]!; }
  u16(): number { return this.take(2).readUInt16LE(); }
  u32(): number { return this.take(4).readUInt32LE(); }
  u64(): bigint { return this.take(8).readBigUInt64LE(); }
  short(): number {
    let result = 0;
    for (let i = 0; i < 3; i++) { const byte = this.u8(); if (i > 0 && byte === 0) reject('A transaction length is noncanonical.'); result |= (byte & 127) << (7 * i); if (!(byte & 128)) { if (result > 65535) reject('A transaction length is too large.'); return result; } }
    return reject('A transaction length is invalid.');
  }
  done(): void { if (this.at !== this.bytes.length) reject('Unexpected trailing transaction data.'); }
}
interface Instruction { programIndex: number; indices: number[]; data: Buffer }
interface Address { key: string; writable: boolean }
interface Account { owner: string; lamports: bigint; executable: boolean; data: Buffer }
function account(value: unknown): Account | null {
  if (value === null) return null;
  const a = record(value); const data = a.data;
  if (typeof a.owner !== 'string' || !Array.isArray(data) || data.length !== 2 || data[1] !== 'base64' || typeof data[0] !== 'string' || data[0].length > 100_000 || typeof a.executable !== 'boolean') return reject('An account observation is invalid.');
  decodeBase58(a.owner);
  const bytes = Buffer.from(data[0], 'base64'); if (bytes.toString('base64') !== data[0]) return reject('An account has invalid encoding.');
  return { owner: a.owner, lamports: uint(a.lamports), executable: a.executable, data: bytes };
}
function envelope(value: unknown): { slot: number; value: unknown } {
  const body = record(value); const slot = Number(uint(record(body.context).slot, BigInt(Number.MAX_SAFE_INTEGER)));
  if (!Object.hasOwn(body, 'value')) reject('An RPC observation omitted its value.');
  return { slot, value: body.value };
}
function token(a: Account | null): { mint: string; owner: string; amount: bigint } | null {
  if (!a || ![TOKEN, TOKEN_2022].includes(a.owner) || a.data.length < 165 || a.executable) return null;
  if (a.owner === TOKEN && a.data.length !== 165 || a.data.length > 165 && a.data[165] !== 2) return null;
  if (a.data[108] !== 1) return reject('A reviewed token account is frozen or uninitialized.');
  return { mint: encodeBase58(a.data.subarray(0, 32)), owner: encodeBase58(a.data.subarray(32, 64)), amount: a.data.readBigUInt64LE(64) };
}
function mint(a: Account | null, decimals: number, nowMs: number): { program: string; uiMultiplier: string } {
  if (!a || ![TOKEN, TOKEN_2022].includes(a.owner) || a.data.length < 82 || a.data[44] !== decimals || a.data[45] !== 1 || a.executable) return reject('The stock or funding mint does not match its verified precision.');
  let uiMultiplier = '1';
  if (a.owner === TOKEN && a.data.length !== 82) reject('The legacy mint has an unexpected layout.');
  if (a.owner === TOKEN_2022 && a.data.length !== 82) {
    // Official token-2022 interface: mint padding through byte164, account type1,
    // then u16 type/u16 length TLVs. UI scaling changes displayed share units.
    // https://github.com/solana-program/token-2022/tree/main/interface/src/extension
    if (a.data.length < 166 || a.data[165] !== 1 || a.data.subarray(82, 165).some(byte => byte !== 0)) reject('The extended mint layout is invalid.');
    const seen = new Set<number>(); let at = 166;
    while (at < a.data.length) {
      if (a.data.subarray(at).every(byte => byte === 0)) break;
      if (at + 4 > a.data.length) reject('The mint extension is truncated.');
      const type = a.data.readUInt16LE(at); const size = a.data.readUInt16LE(at + 2); at += 4;
      if (type === 0 || seen.has(type) || at + size > a.data.length) reject('The mint extension is malformed.');
      seen.add(type);
      if (type > 28) unsupported('This mint extension has not been reviewed.');
      if (type === 10) unsupported('Interest-bearing share amounts require a supported unit conversion before trading.');
      if (type === 25) {
        if (size !== 56) reject('The scaled share amount extension has an invalid size.');
        const current = a.data.readDoubleLE(at + 32); const next = a.data.readDoubleLE(at + 48);
        const effective = a.data.readBigInt64LE(at + 40); const timestamp = BigInt(Math.floor(nowMs / 1000));
        if (![current, next].every(value => Number.isFinite(value) && value > 0 && value <= 1e12)) reject('The scaled share multiplier is invalid.');
        if (effective > timestamp && effective <= timestamp + MAX_QUOTE_LIFETIME_SECONDS && current !== next) unsupported('A share multiplier change is pending. Request a fresh quote after it takes effect.');
        // String(number) preserves the shortest round-trippable value of the
        // onchain f64. Decimal normalizes scientific notation for wire clients.
        uiMultiplier = new Decimal(String(timestamp >= effective ? next : current)).toFixed();
      }
      at += size;
    }
  }
  return { program: a.owner, uiMultiplier };
}

/** Reads a raw base64 getAccountInfo value, with exact Token-2022 TLV semantics. */
export function readSolanaMintUiMultiplier(rawAccount: unknown, decimals: number, nowMs = Date.now()): string {
  if (!Number.isFinite(nowMs) || !Number.isInteger(decimals) || decimals < 0 || decimals > 18) reject('The mint precision or observation time is invalid.');
  return mint(account(rawAccount), decimals, nowMs).uiMultiplier;
}

async function decodeTransaction(serialized: string, wallet: string, rpc: SolanaSwapRpc) {
  const bytes = Buffer.from(serialized, 'base64');
  if (bytes.length > 1232 || bytes.toString('base64') !== serialized) reject('The transaction encoding or size is invalid.');
  const r = new Reader(bytes);
  if (r.short() !== 1 || r.take(64).some(byte => byte !== 0)) unsupported('Only one unsigned Privy wallet signature is supported.');
  const message = bytes.subarray(r.at); let versioned = false;
  if ((bytes[r.at]! & 128) !== 0) { if (r.u8() !== 128) unsupported('This Solana message version is not supported.'); versioned = true; }
  if (r.u8() !== 1 || r.u8() !== 0) unsupported('The transaction requests an unexpected signer.');
  const readonly = r.u8(); const count = r.short();
  if (count < 1 || count > 256 || readonly > count - 1) reject('The transaction account header is invalid.');
  const addresses: Address[] = Array.from({ length: count }, (_, index) => ({ key: encodeBase58(r.take(32)), writable: index < count - readonly }));
  if (addresses[0]!.key !== wallet) reject('The transaction fee payer is not the selected wallet.');
  r.take(32);
  const instructionCount = r.short(); if (instructionCount < 1 || instructionCount > 24) unsupported('The transaction instruction count is outside the supported bound.');
  const instructions: Instruction[] = Array.from({ length: instructionCount }, () => ({ programIndex: r.u8(), indices: [...r.take(r.short())], data: r.take(r.short()) }));
  const writable: Address[] = []; const readonlyLoaded: Address[] = []; let minimumSlot = 0;
  if (versioned) {
    const lookupCount = r.short(); if (lookupCount > 8) unsupported('The transaction contains too many lookup tables.');
    for (let i = 0; i < lookupCount; i++) {
      const key = encodeBase58(r.take(32)); const writeIndices = [...r.take(r.short())]; const readIndices = [...r.take(r.short())];
      const observation = envelope(await rpc.call('getAccountInfo', [key, { encoding: 'base64', commitment: 'confirmed' }]));
      minimumSlot = Math.max(minimumSlot, observation.slot); const table = account(observation.value);
      if (!table || table.owner !== LOOKUP || table.executable || table.data.length < 56 || (table.data.length - 56) % 32 !== 0 ||
        table.data.readUInt32LE(0) !== 1 || table.data.readBigUInt64LE(4) !== MAX_U64 || table.data.readBigUInt64LE(12) >= BigInt(observation.slot)) reject('A lookup table is invalid, inactive or not yet usable.');
      for (const [indices, target, isWritable] of [[writeIndices, writable, true], [readIndices, readonlyLoaded, false]] as const) {
        for (const index of indices) { const offset = 56 + index * 32; if (offset + 32 > table.data.length) reject('A lookup table index is out of range.'); target.push({ key: encodeBase58(table.data.subarray(offset, offset + 32)), writable: isWritable }); }
      }
    }
  }
  r.done(); addresses.push(...writable, ...readonlyLoaded);
  if (addresses.length > 256 || new Set(addresses.map(a => a.key)).size !== addresses.length) reject('The transaction contains duplicate or excessive accounts.');
  for (const ix of instructions) if (ix.programIndex >= addresses.length || ix.indices.some(index => index >= addresses.length)) reject('A compiled instruction references an invalid account.');
  return { addresses, instructions, message, minimumSlot };
}

function routeArguments(data: Buffer) {
  const r = new Reader(data); const tag = r.take(8);
  const shared = tag.equals(discriminator('shared_accounts_route'));
  const v2 = tag.equals(discriminator('route_v2'));
  if (!v2 && !shared && !tag.equals(discriminator('route'))) unsupported('This Jupiter instruction variant has not been independently validated.');
  if (shared) r.u8();
  // V2 moves exact amounts before the route plan and uses u16 fee/bps fields.
  const head = v2 ? { input: r.u64(), output: r.u64(), slippage: r.u16(), feeBps: r.u16() } : null;
  if (v2 && r.u16() !== 0) unsupported('Undisclosed positive-slippage fees are not supported.');
  const count = r.u32(); if (count < 1 || count > 16) unsupported('The Jupiter route has too many steps.');
  const booleans = new Set([8, 12, 15, 16, 17, 18, 21, 23, 24, 27, 28]);
  // Static spot-AMM payloads from the pinned live IDL. Side is a two-case u8 enum.
  const spotFields: Record<number, readonly ('bool' | 'u8' | 'u64')[]> = {
    39: ['bool'], 40: [], 46: [], 61: ['bool'], 67: [], 77: [], 86: ['bool', 'u8'], 87: ['u64', 'bool'],
    89: ['bool'], 95: ['bool'], 104: ['bool'], 108: [], 109: [], 110: ['bool'], 117: ['bool'], 118: ['u64', 'bool'],
    121: ['bool'], 125: ['bool'], 126: ['bool', 'u64', 'u64'], 127: ['bool'], 129: ['bool'], 136: ['bool'],
    141: ['bool'], 151: ['bool'], 154: [], 163: [], 177: ['bool'], 178: ['bool'], 180: [],
  };
  const remaining = () => { const size = r.u32(); if (size > 32) reject('A Jupiter remaining-account slice is too large.'); for (let i = 0; i < size; i++) { r.u8(); r.u8(); } };
  for (let i = 0; i < count; i++) {
    const variant = r.u8();
    // Additional spot venues pinned to the current onchain IDL. Dynamic/RFQ,
    // lending and all unknown variants remain unsupported.
    if (variant > 38 && !Object.hasOwn(spotFields, variant) && ![47, 75].includes(variant)) unsupported('This Jupiter venue instruction is not supported by the verified decoder.');
    if (booleans.has(variant) && r.u8() > 1) reject('A Jupiter route boolean or side is invalid.');
    for (const field of spotFields[variant] ?? []) { if (field === 'u64') r.u64(); else { const value = r.u8(); if (field === 'bool' && value > 1) reject('A Jupiter venue direction is invalid.'); } }
    if (variant === 29) { r.u64(); r.u64(); }
    if (variant === 33) r.u32();
    if (variant === 47) { if (r.u8() > 1) reject('A Whirlpool direction is invalid.'); const has = r.u8(); if (has > 1) reject('A Whirlpool account option is invalid.'); if (has) remaining(); }
    if (variant === 75) remaining();
    const percent = v2 ? r.u16() : r.u8(); if (percent < 1 || percent > (v2 ? 10000 : 100)) reject('A Jupiter route allocation is invalid.');
    r.u8(); r.u8();
  }
  const args = head ?? { input: r.u64(), output: r.u64(), slippage: r.u16(), feeBps: r.u8() }; r.done();
  return { shared, v2, ...args };
}

/** Strict same-chain, Metis-only validation. Unknown instructions are unsupported, never implicitly trusted. */
export async function validateSolanaSwap(quote: JupiterOrderQuote, rpc: SolanaSwapRpc): Promise<ValidatedSolanaSwap> {
  if (!quote.taker || !quote.transactionBase64 || quote.router !== 'metis' || quote.gasless || !quote.lastValidBlockHeight) unsupported('Only unsigned, self-funded Solana Metis swaps are supported.');
  if (quote.inputMint === quote.outputMint) reject('Input and output assets must differ.');
  const wallet = quote.taker; const serialized = quote.transactionBase64;
  const caller: SolanaSwapRpc = { async call(method, params) { try { return await rpc.call(method, params); } catch (error) {
    if (error instanceof SolanaSwapValidationError) throw error;
    throw new SolanaSwapValidationError('rpc_unavailable', 'The swap could not be independently verified with Solana.');
  } } };
  const genesis = await caller.call('getGenesisHash', []); if (genesis !== MAINNET_GENESIS) reject('The swap RPC is not Solana mainnet.');
  const height = uint(await caller.call('getBlockHeight', [{ commitment: 'confirmed' }]));
  if (height + 5n >= uint(quote.lastValidBlockHeight)) throw new SolanaSwapValidationError('quote_expired', 'The swap transaction is too close to expiry. Request a fresh quote.');
  const tx = await decodeTransaction(serialized, wallet, caller);
  const key = (index: number) => tx.addresses[index]!.key;
  const writable = tx.addresses.filter(a => a.writable).map(a => a.key);
  if (writable.length > 64) unsupported('The swap writes too many accounts to verify independently.');
  const inspectKeys = [...new Set([...writable, quote.inputMint, quote.outputMint])];
  const preResult = envelope(await caller.call('getMultipleAccounts', [inspectKeys, { encoding: 'base64', commitment: 'confirmed', minContextSlot: tx.minimumSlot }]));
  if (!Array.isArray(preResult.value) || preResult.value.length !== inspectKeys.length || preResult.slot < tx.minimumSlot) reject('The pre-swap account snapshot is incomplete or stale.');
  const before = new Map(inspectKeys.map((address, i) => [address, account((preResult.value as unknown[])[i])]));
  const inputMint = mint(before.get(quote.inputMint)!, quote.inputDecimals, Date.now()); const outputMint = mint(before.get(quote.outputMint)!, quote.outputDecimals, Date.now());
  if (inputMint.uiMultiplier !== (quote.inputUiMultiplier ?? '1') || outputMint.uiMultiplier !== (quote.outputUiMultiplier ?? '1')) reject('The share unit multiplier changed or does not match the reviewed quote.');
  const inputProgram = inputMint.program; const outputProgram = outputMint.program;
  const swaps = tx.instructions.filter(ix => key(ix.programIndex) === JUPITER_V6);
  if (swaps.length !== 1) unsupported('Exactly one reviewed Jupiter swap is required.');
  const swap = swaps[0]!; const args = routeArguments(swap.data);
  const accounts = swap.indices.map(key); const source = accounts[args.v2 ? 1 : args.shared ? 3 : 2]; const destination = accounts[args.v2 ? 2 : args.shared ? 6 : 3];
  if (args.v2) {
    if (!source || !destination || accounts.length < 10 || accounts[0] !== wallet || accounts[3] !== quote.inputMint || accounts[4] !== quote.outputMint ||
      accounts[5] !== inputProgram || accounts[6] !== outputProgram || ![JUPITER_V6, destination].includes(accounts[7]!) ||
      accounts[8] !== 'D8cy77BBepLMngZx6ZukaTff5hCt1HrWyKk3Hnd9oitf' || accounts[9] !== JUPITER_V6 ||
      !writable.includes(source) || !writable.includes(destination)) reject('The Jupiter V2 accounts do not match the reviewed wallet and mints.');
  } else {
  if (!source || !destination || accounts.length < (args.shared ? 13 : 9) || accounts[args.shared ? 2 : 1] !== wallet ||
    accounts[args.shared ? 8 : 5] !== quote.outputMint || args.shared && accounts[7] !== quote.inputMint ||
    accounts[args.shared ? 12 : 8] !== JUPITER_V6 || !writable.includes(source) || !writable.includes(destination)) reject('The Jupiter swap account identities do not match the reviewed trade.');
  if (!args.shared && ![JUPITER_V6, destination].includes(accounts[4]!)) reject('The Jupiter swap names an unexpected output recipient.');
  if (![TOKEN, TOKEN_2022].includes(accounts[0]!)) reject('The Jupiter swap token program is invalid.');
  if (args.feeBps > 0) {
    const feeAddress = accounts[args.shared ? 9 : 6]; const feeToken = token(before.get(feeAddress!) ?? null);
    if (!feeAddress || !writable.includes(feeAddress) || [source, destination].includes(feeAddress) || !feeToken || feeToken.mint !== quote.feeMint) reject('The Jupiter platform fee account does not match the reviewed fee token.');
  }
  }
  if (args.input !== uint(quote.inAmount) || args.output !== uint(quote.outAmount) || args.slippage !== quote.slippageBps ||
    args.feeBps !== quote.feeBps || uint(quote.minimumOutputAmount) !== args.output * BigInt(10_000 - args.slippage) / 10_000n) reject('The Jupiter instruction amounts or slippage differ from the reviewed quote.');
  const creates = new Map<string, string>(); let wrapped = 0n; let units = 200_000; let price = 0n; let unitCount = 0; let priceCount = 0;
  const close = new Set<string>(); let synced = false;
  for (const ix of tx.instructions) {
    const program = key(ix.programIndex); const a = ix.indices.map(key);
    if (program === JUPITER_V6) continue;
    const r = new Reader(ix.data);
    if (program === COMPUTE) {
      if (a.length) reject('A compute budget instruction unexpectedly names accounts.');
      const kind = r.u8(); if (kind === 2) { if (++unitCount > 1) reject('Duplicate compute limit.'); units = r.u32(); if (units < 1 || units > 1_400_000) reject('The compute unit limit is excessive.'); }
      else if (kind === 3) { if (++priceCount > 1) reject('Duplicate compute price.'); price = r.u64(); }
      else unsupported('This compute budget instruction is not supported.'); r.done();
    } else if (program === ASSOCIATED) {
      if (ix.data.length !== 1 || ix.data[0] !== 1 || a.length !== 6 || a[0] !== wallet || a[2] !== wallet || a[4] !== SYSTEM ||
        ![source, destination].includes(a[1]!) || a[3] !== (a[1] === source ? quote.inputMint : quote.outputMint) || a[5] !== (a[1] === source ? inputProgram : outputProgram) || creates.has(a[1]!)) reject('Only the reviewed wallet token accounts may be created.');
      creates.set(a[1]!, a[3]!);
    } else if (program === SYSTEM) {
      if (r.u32() !== 2 || a.length !== 2 || a[0] !== wallet || a[1] !== source || quote.inputMint !== WSOL || wrapped !== 0n) unsupported('An unrelated native transfer is not allowed in a stock swap.');
      wrapped = r.u64(); r.done(); if (wrapped !== args.input) reject('The native funding transfer differs from the reviewed amount.');
    } else if (program === TOKEN || program === TOKEN_2022) {
      const kind = r.u8(); r.done();
      if (kind === 17) { if (a.length !== 1 || a[0] !== source || quote.inputMint !== WSOL || program !== TOKEN || synced) reject('An unexpected wrapped SOL account is being synchronized.'); synced = true; }
      else if (kind === 9) { if (a.length !== 3 || a[1] !== wallet || a[2] !== wallet || ![source, destination].includes(a[0]!) ||
        (a[0] === source ? quote.inputMint : quote.outputMint) !== WSOL || program !== TOKEN || close.has(a[0]!)) reject('Only a reviewed wrapped SOL account can be closed to the same wallet.'); close.add(a[0]!); }
      else unsupported('Token approvals, authority changes and unrelated transfers are not allowed in a stock swap.');
    } else unsupported('The transaction contains an unreviewed top-level program.');
  }
  if (quote.inputMint === WSOL && (wrapped !== args.input || !synced || !close.has(source))) unsupported('The native SOL funding lifecycle is incomplete.');
  if (quote.outputMint === WSOL && !close.has(destination)) unsupported('The native SOL receiving lifecycle is incomplete.');
  for (const [address, expectedMint] of [[source, quote.inputMint], [destination, quote.outputMint]]) {
    const pre = before.get(address!); const t = token(pre ?? null);
    if (!t && (!creates.has(address!) || pre)) reject('The reviewed token account does not belong to this wallet.');
    if (t && (t.owner !== wallet || t.mint !== expectedMint)) reject('The reviewed token account has the wrong owner or mint.');
    if (address === destination && expectedMint === WSOL && pre) unsupported('Native SOL output requires a newly created wrapped account so an old rent refund cannot be counted as swap proceeds.');
    if (t && expectedMint === WSOL && t.amount !== 0n) unsupported('Existing wrapped SOL inventory must not be mixed with a native SOL swap.');
  }
  const feeObservation = envelope(await caller.call('getFeeForMessage', [tx.message.toString('base64'), { commitment: 'confirmed', minContextSlot: preResult.slot }]));
  if (feeObservation.value === null) throw new SolanaSwapValidationError('quote_expired', 'The swap blockhash is no longer valid.');
  const fee = uint(feeObservation.value);
  if (fee < 5000n || fee > MAX_NATIVE_COST || BigInt(units) * price / 1_000_000n > MAX_NATIVE_COST) reject('The swap requests excessive network fees.');
  const payerBefore = before.get(wallet); if (!payerBefore || payerBefore.owner !== SYSTEM || payerBefore.executable) reject('The fee payer is not a standard wallet account.');
  const simulationResult = envelope(await caller.call('simulateTransaction', [serialized, { encoding: 'base64', sigVerify: false, replaceRecentBlockhash: false,
    commitment: 'confirmed', minContextSlot: preResult.slot, accounts: { encoding: 'base64', addresses: writable } }]));
  const simulation = record(simulationResult.value);
  if (simulation.err !== null) throw new SolanaSwapValidationError('simulation_failed', 'The exact swap failed Solana simulation. Nothing was signed.');
  if (simulationResult.slot < preResult.slot || !Array.isArray(simulation.accounts) || simulation.accounts.length !== writable.length) reject('The swap simulation did not return all reviewed account changes.');
  const after = new Map(writable.map((address, i) => [address, account((simulation.accounts as unknown[])[i])]));
  const payerAfter = after.get(wallet); if (!payerAfter || payerAfter.owner !== SYSTEM || !payerAfter.data.equals(payerBefore.data)) reject('The swap changes the wallet authority.');
  let rent = 0n;
  for (const [address] of creates) { if (!before.get(address) && after.get(address)) rent += after.get(address)!.lamports; }
  if (fee + rent > MAX_NATIVE_COST) reject('The swap network and account costs exceed the supported bound.');
  for (const address of writable) {
    const pre = before.get(address) ?? null; const post = after.get(address) ?? null; const preToken = token(pre); const postToken = token(post);
    if (preToken?.owner === wallet) {
      if (!post && close.has(address)) continue;
      if (!post || !postToken || postToken.owner !== wallet || post.owner !== pre!.owner || !pre!.data.subarray(0, 64).equals(post.data.subarray(0, 64)) || !pre!.data.subarray(72).equals(post.data.subarray(72))) reject('The swap changes a wallet token authority or extension.');
      if (address !== source && postToken.amount < preToken.amount) reject('The swap spends an unrelated wallet token.');
      if (post.lamports < pre!.lamports) reject('The swap drains rent from a wallet token account.');
    }
    if (!pre && postToken?.owner === wallet && !creates.has(address)) reject('The swap created an unexpected wallet token account.');
  }
  if (quote.inputMint !== WSOL) {
    const pre = token(before.get(source) ?? null); const post = token(after.get(source) ?? null);
    if (!pre || !post || pre.amount - post.amount !== args.input) reject('The simulated payment differs from the exact reviewed input.');
  }
  if (quote.outputMint !== WSOL) {
    const pre = token(before.get(destination) ?? null); const post = token(after.get(destination) ?? null);
    if (!post || post.owner !== wallet || post.mint !== quote.outputMint || post.amount - (pre?.amount ?? 0n) < uint(quote.minimumOutputAmount)) reject('The simulated stock receipt is below the reviewed minimum.');
    if (payerBefore.lamports - payerAfter.lamports > wrapped + fee + rent) reject('The swap drains native funds beyond the reviewed payment and costs.');
  } else if (payerAfter.lamports + fee + rent - payerBefore.lamports < uint(quote.minimumOutputAmount)) reject('The simulated native receipt is below the reviewed minimum.');
  return { transactionBase64: serialized, messageSha256: createHash('sha256').update(tx.message).digest('hex'), sourceTokenAccount: source,
    destinationTokenAccount: destination, minimumOutputBaseUnits: quote.minimumOutputAmount, estimatedNetworkFeeLamports: fee.toString(),
    maximumNativeCostLamports: (fee + rent).toString(), simulationSlot: simulationResult.slot, lastValidBlockHeight: quote.lastValidBlockHeight };
}

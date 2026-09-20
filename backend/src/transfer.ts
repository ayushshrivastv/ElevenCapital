import { z } from 'zod';
import { keccak256Hex } from './keccak.js';
import { ETH_USDC, MAINNET_GENESIS, SOL_USDC } from './portfolio-upstream.js';
import { TransferPreparationSchema, TransferRequestSchema, type TransferPreparation, type TransferRequest } from './transfer-schema.js';
import { publicTransferRpc, type TransferRpc } from './transfer-rpc.js';

const U64_MAX = (1n << 64n) - 1n;
const U256_MAX = (1n << 256n) - 1n;
// These are deliberate product safety ceilings, not estimates. A single RPC is
// allowed to make a transfer unavailable during an extreme fee event, but it is
// never allowed to make the wallet sign an economically unbounded fee quote.
const MAX_ETH_NATIVE_GAS = 50_000n;
const MAX_ETH_USDC_GAS = 150_000n;
const MAX_ETH_PRIORITY_FEE_PER_GAS = 10_000_000_000n; // 10 gwei
const MAX_ETH_FEE_PER_GAS = 2_000_000_000_000n; // 2,000 gwei
const MAX_ETH_TOTAL_FEE = 50_000_000_000_000_000n; // 0.05 ETH
const MAX_SOLANA_TRANSFER_FEE = 5_000_000n; // 0.005 SOL
const SYSTEM_PROGRAM = '11111111111111111111111111111111';
const INCINERATOR = '1nc1nerator11111111111111111111111111111111';
const CAIP_SOLANA = 'solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp' as const;

export type TransferErrorCode = 'invalid_request' | 'invalid_address' | 'unsafe_recipient' | 'unsupported_asset' |
  'wrong_network' | 'stale_chain_data' | 'insufficient_asset_balance' | 'insufficient_fee_balance' |
  'simulation_failed' | 'transaction_mismatch' | 'rpc_unavailable' | 'busy' | 'rate_limited';
export class TransferPreparationError extends Error {
  constructor(readonly code: TransferErrorCode, readonly statusCode: 400 | 422 | 429 | 503, message: string) { super(message); }
}

export const Assets = {
  'ETHEREUM:native': { id: 'ETHEREUM:native', symbol: 'ETH', kind: 'native', address: null, decimals: 18 },
  'ETHEREUM:USDC': { id: 'ETHEREUM:USDC', symbol: 'USDC', kind: 'erc20', address: ETH_USDC, decimals: 6 },
  'SOLANA:native': { id: 'SOLANA:native', symbol: 'SOL', kind: 'native', address: null, decimals: 9 },
} as const;

function exactBaseUnits(amount: string, decimals: number, maximum: bigint): string {
  const match = /^(0|[1-9]\d*)(?:\.(\d+))?$/.exec(amount);
  if (!match) throw new TransferPreparationError('invalid_request', 400, 'Amount must be a plain positive decimal.');
  const fraction = match[2] ?? '';
  if (fraction.length > decimals) throw new TransferPreparationError('invalid_request', 400, `Amount supports at most ${decimals} decimal places.`);
  const integer = BigInt(`${match[1]}${fraction.padEnd(decimals, '0')}`);
  if (integer <= 0n) throw new TransferPreparationError('invalid_request', 400, 'Amount must be greater than zero.');
  if (integer > maximum) throw new TransferPreparationError('invalid_request', 400, 'Amount exceeds the supported asset bound.');
  return integer.toString();
}

function hex(value: bigint): `0x${string}` { return `0x${value.toString(16)}`; }
function parseHex(raw: unknown, maximum = U256_MAX): bigint {
  if (typeof raw !== 'string' || !/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/.test(raw)) throw new Error('Invalid RPC integer');
  const value = BigInt(raw);
  if (value > maximum) throw new Error('RPC integer exceeded bound');
  return value;
}
function parseDataWord(raw: unknown, maximum = U256_MAX): bigint {
  if (typeof raw !== 'string' || !/^0x[0-9a-fA-F]{1,64}$/.test(raw)) throw new Error('Invalid RPC data word');
  const value = BigInt(raw);
  if (value > maximum) throw new Error('RPC data word exceeded bound');
  return value;
}
function unsigned(raw: unknown, maximum = U64_MAX): bigint {
  if ((typeof raw !== 'string' && typeof raw !== 'number') || !/^(?:0|[1-9]\d*)$/.test(String(raw))) throw new Error('Invalid RPC integer');
  const value = BigInt(raw);
  if (value > maximum) throw new Error('RPC integer exceeded bound');
  return value;
}

export function checksumEthereumAddress(value: string): string {
  if (!/^0x[0-9a-fA-F]{40}$/.test(value)) throw new TransferPreparationError('invalid_address', 400, 'Ethereum address is invalid.');
  const lower = value.slice(2).toLowerCase();
  const digest = keccak256Hex(lower);
  let result = '0x';
  for (let index = 0; index < lower.length; index++) result += Number.parseInt(digest[index]!, 16) >= 8 ? lower[index]!.toUpperCase() : lower[index];
  const letters = value.slice(2).replace(/[0-9]/g, '');
  const mixed = letters !== letters.toLowerCase() && letters !== letters.toUpperCase();
  if (mixed && result !== value) throw new TransferPreparationError('invalid_address', 400, 'Mixed-case Ethereum address has an invalid EIP-55 checksum.');
  return result;
}

const BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
export function decodeBase58(value: string): Uint8Array {
  if (!/^[1-9A-HJ-NP-Za-km-z]{1,44}$/.test(value)) throw new TransferPreparationError('invalid_address', 400, 'Solana address is invalid.');
  let integer = 0n;
  for (const character of value) {
    const digit = BASE58.indexOf(character);
    if (digit < 0) throw new TransferPreparationError('invalid_address', 400, 'Solana address is invalid.');
    integer = integer * 58n + BigInt(digit);
  }
  const bytes: number[] = [];
  while (integer > 0n) { bytes.push(Number(integer & 0xffn)); integer >>= 8n; }
  bytes.reverse();
  const leading = value.match(/^1*/)?.[0].length ?? 0;
  const output = Uint8Array.from([...Array(leading).fill(0), ...bytes]);
  if (output.length !== 32 || encodeBase58(output) !== value) throw new TransferPreparationError('invalid_address', 400, 'Solana address must be one canonical 32-byte public key.');
  return output;
}
export function encodeBase58(value: Uint8Array): string {
  let integer = 0n;
  for (const byte of value) integer = (integer << 8n) + BigInt(byte);
  let encoded = '';
  while (integer > 0n) { encoded = BASE58[Number(integer % 58n)]! + encoded; integer /= 58n; }
  let leading = 0;
  while (leading < value.length && value[leading] === 0) leading++;
  return '1'.repeat(leading) + encoded;
}

function validateEthereumParties(sender: string, recipient: string): { sender: string; recipient: string } {
  const parties = { sender: checksumEthereumAddress(sender), recipient: checksumEthereumAddress(recipient) };
  if (sender.toLowerCase() === recipient.toLowerCase()) throw new TransferPreparationError('unsafe_recipient', 422, 'Sender and recipient must be different.');
  const recipientValue = BigInt(recipient.toLowerCase());
  if (recipientValue <= 0xffffn || recipient.toLowerCase() === '0x000000000000000000000000000000000000dead' ||
    recipient.toLowerCase() === '0xdead000000000000000000000000000000000000') {
    throw new TransferPreparationError('unsafe_recipient', 422, 'Recipient is a zero, burn, or system address.');
  }
  if (BigInt(sender.toLowerCase()) <= 0xffffn) throw new TransferPreparationError('invalid_address', 400, 'Sender is not a usable externally owned address.');
  return parties;
}

function validateSolanaParties(sender: string, recipient: string): void {
  decodeBase58(sender); decodeBase58(recipient);
  if (sender === recipient) throw new TransferPreparationError('unsafe_recipient', 422, 'Sender and recipient must be different.');
  if ([SYSTEM_PROGRAM, INCINERATOR].includes(recipient)) throw new TransferPreparationError('unsafe_recipient', 422, 'Recipient is a zero, burn, or system address.');
  if (sender === SYSTEM_PROGRAM) throw new TransferPreparationError('invalid_address', 400, 'Sender is not a usable wallet address.');
}

function short(value: number): Uint8Array {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('Invalid compact integer');
  const output: number[] = [];
  do { let byte = value & 0x7f; value >>= 7; if (value) byte |= 0x80; output.push(byte); } while (value);
  return Uint8Array.from(output);
}
function littleU32(value: number): Uint8Array {
  const output = Buffer.alloc(4); output.writeUInt32LE(value); return output;
}
function littleU64(value: bigint): Uint8Array {
  if (value < 0n || value > U64_MAX) throw new Error('Invalid u64');
  const output = new Uint8Array(8);
  for (let index = 0; index < 8; index++) output[index] = Number((value >> BigInt(index * 8)) & 0xffn);
  return output;
}
function concat(...values: Uint8Array[]): Uint8Array { return Uint8Array.from(values.flatMap(value => [...value])); }

export function buildSolanaTransfer(sender: string, recipient: string, recentBlockhash: string, lamports: bigint): { message: Uint8Array; transaction: Uint8Array } {
  const senderKey = decodeBase58(sender); const recipientKey = decodeBase58(recipient); const programKey = decodeBase58(SYSTEM_PROGRAM);
  const blockhash = decodeBase58(recentBlockhash);
  const data = concat(littleU32(2), littleU64(lamports));
  const instruction = concat(Uint8Array.of(2), short(2), Uint8Array.of(0, 1), short(data.length), data);
  const message = concat(Uint8Array.of(1, 0, 1), short(3), senderKey, recipientKey, programKey, blockhash, short(1), instruction);
  return { message, transaction: concat(short(1), new Uint8Array(64), message) };
}

export function decodeSolanaTransfer(raw: Uint8Array): { sender: string; recipient: string; recentBlockhash: string; lamports: string } {
  let offset = 0;
  const byte = () => { if (offset >= raw.length) throw new Error('Truncated transaction'); return raw[offset++]!; };
  const compact = () => { let value = 0; let shift = 0; for (let count = 0; count < 3; count++) { const next = byte(); value |= (next & 0x7f) << shift; if (!(next & 0x80)) return value; shift += 7; } throw new Error('Invalid compact integer'); };
  if (compact() !== 1) throw new Error('Unexpected signature count');
  if (raw.slice(offset, offset + 64).some(value => value !== 0)) throw new Error('Unsigned transaction contains a signature');
  offset += 64;
  if (byte() !== 1 || byte() !== 0 || byte() !== 1 || compact() !== 3) throw new Error('Unexpected message header');
  const keys = [0, 1, 2].map(() => { const key = raw.slice(offset, offset + 32); if (key.length !== 32) throw new Error('Truncated key'); offset += 32; return encodeBase58(key); });
  const blockhash = raw.slice(offset, offset + 32); if (blockhash.length !== 32) throw new Error('Truncated blockhash'); offset += 32;
  if (compact() !== 1 || byte() !== 2 || compact() !== 2 || byte() !== 0 || byte() !== 1 || compact() !== 12) throw new Error('Unexpected transfer instruction');
  const data = raw.slice(offset, offset + 12); offset += 12;
  if (offset !== raw.length || Buffer.from(data.subarray(0, 4)).readUInt32LE() !== 2 || keys[2] !== SYSTEM_PROGRAM) throw new Error('Unexpected transfer payload');
  let lamports = 0n; for (let index = 0; index < 8; index++) lamports |= BigInt(data[index + 4]!) << BigInt(8 * index);
  return { sender: keys[0]!, recipient: keys[1]!, recentBlockhash: encodeBase58(blockhash), lamports: lamports.toString() };
}

// JSON-RPC defines an account without bytecode as exactly `0x`; `0x00` is real STOP bytecode.
function isEmptyCode(raw: unknown): boolean { return raw === '0x'; }
function rpcFailure(error: unknown): never {
  if (error instanceof TransferPreparationError) throw error;
  if (error instanceof Error && ['AbortError', 'TimeoutError'].includes(error.name)) throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet RPC timed out. Try again safely.');
  throw new TransferPreparationError('rpc_unavailable', 503, 'Mainnet RPC data is unavailable. No transaction was created.');
}

export class TransferRateLimiter {
  private startedAt: number;
  private count = 0;
  constructor(private readonly now = Date.now, private readonly limit = 30, private readonly windowMs = 60_000) { this.startedAt = now(); }
  take(): void {
    const current = this.now();
    if (current < this.startedAt || current - this.startedAt >= this.windowMs) { this.startedAt = current; this.count = 0; }
    if (this.count >= this.limit) throw new TransferPreparationError('rate_limited', 429, 'Too many transfer preparations. Wait before trying again.');
    this.count++;
  }
}

export class TransferPreparationService {
  private active = 0;
  constructor(private readonly rpc: TransferRpc = publicTransferRpc(), private readonly now = Date.now, private readonly timeoutMs = 12_000) {}
  async prepare(input: TransferRequest): Promise<TransferPreparation> {
    const parsed = TransferRequestSchema.safeParse(input);
    if (!parsed.success) throw new TransferPreparationError('invalid_request', 400, 'Transfer preparation request is invalid.');
    if (this.active >= 4) throw new TransferPreparationError('busy', 503, 'Transfer preparation is busy. Try again safely.');
    this.active++;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const result = parsed.data.chain === 'ETHEREUM'
        ? await this.ethereum(parsed.data, controller.signal)
        : await this.solana(parsed.data, controller.signal);
      return TransferPreparationSchema.parse(result);
    } catch (error) { return rpcFailure(error); }
    finally { clearTimeout(timeout); this.active--; }
  }

  private async ethereum(input: TransferRequest, signal: AbortSignal): Promise<TransferPreparation> {
    const parties = validateEthereumParties(input.sender, input.recipient);
    const asset = input.assetId === 'ETHEREUM:native' ? Assets['ETHEREUM:native'] : Assets['ETHEREUM:USDC'];
    const amount = BigInt(exactBaseUnits(input.amount, asset.decimals, U256_MAX));
    const chain = parseHex(await this.rpc.call('ETHEREUM', 'eth_chainId', [], signal));
    if (chain !== 1n) throw new TransferPreparationError('wrong_network', 503, 'Configured Ethereum RPC is not mainnet.');
    const rawBlock = await this.rpc.call('ETHEREUM', 'eth_getBlockByNumber', ['latest', false], signal);
    const block = z.object({ number: z.unknown(), timestamp: z.unknown(), baseFeePerGas: z.unknown().optional() }).passthrough().parse(rawBlock);
    const blockNumber = parseHex(block.number, U64_MAX); const blockTime = Number(parseHex(block.timestamp, BigInt(Number.MAX_SAFE_INTEGER))) * 1_000;
    if (blockNumber < 1n || blockTime < this.now() - 300_000 || blockTime > this.now() + 30_000) {
      throw new TransferPreparationError('stale_chain_data', 503, 'Ethereum mainnet data is not fresh enough to prepare a transfer.');
    }
    const blockTag = hex(blockNumber);
    const [nonceRaw, nativeRaw, senderCode, recipientCode] = await Promise.all([
      this.rpc.call('ETHEREUM', 'eth_getTransactionCount', [parties.sender, 'pending'], signal),
      this.rpc.call('ETHEREUM', 'eth_getBalance', [parties.sender, blockTag], signal),
      this.rpc.call('ETHEREUM', 'eth_getCode', [parties.sender, blockTag], signal),
      this.rpc.call('ETHEREUM', 'eth_getCode', [parties.recipient, blockTag], signal),
    ]);
    if (!isEmptyCode(senderCode)) throw new TransferPreparationError('invalid_address', 400, 'Sender must be the Privy externally owned wallet.');
    if (!isEmptyCode(recipientCode)) throw new TransferPreparationError('unsafe_recipient', 422, 'Recipient is a contract address and requires a contract-specific flow.');
    const nonce = parseHex(nonceRaw, U64_MAX); const nativeBalance = parseHex(nativeRaw);
    let assetBalance = nativeBalance;
    let to = parties.recipient; let value = amount; let data = '0x';
    if (asset.id === 'ETHEREUM:USDC') {
      const [code, decimalsRaw, balanceRaw] = await Promise.all([
        this.rpc.call('ETHEREUM', 'eth_getCode', [ETH_USDC, blockTag], signal),
        this.rpc.call('ETHEREUM', 'eth_call', [{ to: ETH_USDC, data: '0x313ce567' }, blockTag], signal),
        this.rpc.call('ETHEREUM', 'eth_call', [{ to: ETH_USDC, data: `0x70a08231${parties.sender.slice(2).toLowerCase().padStart(64, '0')}` }, blockTag], signal),
      ]);
      if (isEmptyCode(code) || parseDataWord(decimalsRaw) !== 6n) throw new TransferPreparationError('wrong_network', 503, 'Canonical Ethereum USDC identity could not be verified.');
      assetBalance = parseDataWord(balanceRaw);
      to = ETH_USDC; value = 0n;
      data = `0xa9059cbb${parties.recipient.slice(2).toLowerCase().padStart(64, '0')}${amount.toString(16).padStart(64, '0')}`;
    }
    if (assetBalance < amount) throw new TransferPreparationError('insufficient_asset_balance', 422, `Insufficient ${asset.symbol} balance.`);
    const draft = { from: parties.sender, to, value: hex(value), data };
    const gasCeiling = asset.kind === 'native' ? MAX_ETH_NATIVE_GAS : MAX_ETH_USDC_GAS;
    const gas = parseHex(await this.rpc.call('ETHEREUM', 'eth_estimateGas', [draft, 'pending'], signal), gasCeiling);
    if (gas < 21_000n) throw new Error('Invalid gas estimate');
    let type: '0x0' | '0x2'; let estimatedPrice: bigint; let maxPrice: bigint;
    let gasPrice: string | undefined; let maxFeePerGas: string | undefined; let maxPriorityFeePerGas: string | undefined;
    if (block.baseFeePerGas !== undefined) {
      const baseFee = parseHex(block.baseFeePerGas);
      let priority: bigint;
      try { priority = parseHex(await this.rpc.call('ETHEREUM', 'eth_maxPriorityFeePerGas', [], signal)); }
      catch {
        const freshGasPrice = parseHex(await this.rpc.call('ETHEREUM', 'eth_gasPrice', [], signal));
        priority = freshGasPrice > baseFee ? freshGasPrice - baseFee : 0n;
      }
      if (baseFee <= 0n || baseFee > MAX_ETH_FEE_PER_GAS || priority <= 0n ||
        priority > MAX_ETH_PRIORITY_FEE_PER_GAS) throw new Error('Fee quote exceeded safety ceiling');
      type = '0x2'; estimatedPrice = baseFee + priority; maxPrice = baseFee * 2n + priority;
      maxFeePerGas = hex(maxPrice); maxPriorityFeePerGas = hex(priority);
    } else {
      type = '0x0'; estimatedPrice = maxPrice = parseHex(await this.rpc.call('ETHEREUM', 'eth_gasPrice', [], signal));
      gasPrice = hex(maxPrice);
    }
    if (estimatedPrice <= 0n || maxPrice <= 0n || maxPrice < estimatedPrice ||
      estimatedPrice > MAX_ETH_FEE_PER_GAS || maxPrice > MAX_ETH_FEE_PER_GAS) {
      throw new Error('Fee quote exceeded safety ceiling');
    }
    const estimatedFee = gas * estimatedPrice; const maxFee = gas * maxPrice;
    if (maxFee > MAX_ETH_TOTAL_FEE) throw new Error('Total fee exceeded safety ceiling');
    if (nativeBalance < maxFee + (asset.kind === 'native' ? amount : 0n)) throw new TransferPreparationError('insufficient_fee_balance', 422, 'Insufficient ETH for the transfer and its maximum network fee.');
    const transaction = { chainId: '0x1' as const, from: parties.sender, to, nonce: hex(nonce), gas: hex(gas), value: hex(value), data,
      type, ...(gasPrice ? { gasPrice } : {}), ...(maxFeePerGas ? { maxFeePerGas, maxPriorityFeePerGas } : {}) };
    const simulation = await this.rpc.call('ETHEREUM', 'eth_call', [transaction, 'pending'], signal);
    let simulationOk = simulation === '0x' && asset.kind === 'native';
    if (asset.kind === 'erc20') {
      try { simulationOk = parseDataWord(simulation) === 1n; } catch { simulationOk = false; }
    }
    if (!simulationOk) {
      throw new TransferPreparationError('simulation_failed', 422, 'Transfer simulation failed. No transaction was created.');
    }
    const preparedAt = new Date(this.now()).toISOString();
    return { schemaVersion: 1, operationId: input.operationId, chain: 'ETHEREUM', caip2: 'eip155:1', asset,
      sender: parties.sender, recipient: parties.recipient, amount: input.amount, baseUnits: amount.toString(), balanceBaseUnits: assetBalance.toString(),
      estimatedFeeBaseUnits: estimatedFee.toString(), maxFeeBaseUnits: maxFee.toString(), preparedAt,
      expiresAt: new Date(this.now() + 60_000).toISOString(), observedBlock: blockNumber.toString(), transaction };
  }

  private async solana(input: TransferRequest, signal: AbortSignal): Promise<TransferPreparation> {
    validateSolanaParties(input.sender, input.recipient);
    if (input.assetId === 'SOLANA:USDC') throw new TransferPreparationError('unsupported_asset', 422,
      `Solana USDC preparation is disabled until token-account creation and transferChecked are independently verified for ${SOL_USDC}.`);
    const asset = Assets['SOLANA:native'];
    const amount = BigInt(exactBaseUnits(input.amount, asset.decimals, U64_MAX));
    if (await this.rpc.call('SOLANA', 'getGenesisHash', [], signal) !== MAINNET_GENESIS) {
      throw new TransferPreparationError('wrong_network', 503, 'Configured Solana RPC is not mainnet.');
    }
    const [balanceRaw, senderRaw, recipientRaw, latestRaw] = await Promise.all([
      this.rpc.call('SOLANA', 'getBalance', [input.sender, { commitment: 'confirmed' }], signal),
      this.rpc.call('SOLANA', 'getAccountInfo', [input.sender, { encoding: 'base64', commitment: 'confirmed' }], signal),
      this.rpc.call('SOLANA', 'getAccountInfo', [input.recipient, { encoding: 'base64', commitment: 'confirmed' }], signal),
      this.rpc.call('SOLANA', 'getLatestBlockhash', [{ commitment: 'confirmed' }], signal),
    ]);
    const balanceResult = z.object({ context: z.object({ slot: z.unknown() }), value: z.unknown() }).parse(balanceRaw);
    const balance = unsigned(balanceResult.value); const observedSlot = unsigned(balanceResult.context.slot);
    const account = (raw: unknown) => z.object({ context: z.object({ slot: z.unknown() }),
      value: z.union([z.null(), z.object({ executable: z.boolean(), owner: z.string(), data: z.unknown() }).passthrough()]) }).passthrough().parse(raw);
    const senderRead = account(senderRaw); const recipientRead = account(recipientRaw);
    const senderAccount = senderRead.value; const recipientAccount = recipientRead.value;
    if (!senderAccount || senderAccount.executable || senderAccount.owner !== SYSTEM_PROGRAM) throw new TransferPreparationError('invalid_address', 400, 'Sender must be a funded, system-owned Privy wallet.');
    if (recipientAccount && (recipientAccount.executable || recipientAccount.owner !== SYSTEM_PROGRAM)) throw new TransferPreparationError('unsafe_recipient', 422, 'Recipient is a program or token account and requires a program-specific flow.');
    const latest = z.object({ context: z.object({ slot: z.unknown() }), value: z.object({ blockhash: z.string(), lastValidBlockHeight: z.unknown() }) }).parse(latestRaw);
    const latestSlot = unsigned(latest.context.slot); const lastValidBlockHeight = unsigned(latest.value.lastValidBlockHeight);
    const accountSlots = [unsigned(senderRead.context.slot), unsigned(recipientRead.context.slot)];
    if ([observedSlot, ...accountSlots].some(slot => latestSlot + 150n < slot || slot + 150n < latestSlot)) {
      throw new TransferPreparationError('stale_chain_data', 503, 'Solana RPC observations are not from a consistent recent slot.');
    }
    const built = buildSolanaTransfer(input.sender, input.recipient, latest.value.blockhash, amount);
    const decoded = decodeSolanaTransfer(built.transaction);
    if (decoded.sender !== input.sender || decoded.recipient !== input.recipient || decoded.recentBlockhash !== latest.value.blockhash || decoded.lamports !== amount.toString()) throw new Error('Serialized transaction verification failed');
    const messageBase64 = Buffer.from(built.message).toString('base64');
    const feeRaw = await this.rpc.call('SOLANA', 'getFeeForMessage', [messageBase64, { commitment: 'confirmed' }], signal);
    const feeResult = z.object({ context: z.object({ slot: z.unknown() }), value: z.unknown() }).parse(feeRaw);
    const fee = unsigned(feeResult.value, MAX_SOLANA_TRANSFER_FEE); const feeSlot = unsigned(feeResult.context.slot);
    if (feeSlot + 150n < latestSlot || latestSlot + 150n < feeSlot) throw new TransferPreparationError('stale_chain_data', 503, 'Solana fee data is not from a consistent recent slot.');
    if (balance < amount) throw new TransferPreparationError('insufficient_asset_balance', 422, 'Insufficient SOL balance.');
    if (balance < amount + fee) throw new TransferPreparationError('insufficient_fee_balance', 422, 'Insufficient SOL for the transfer and network fee.');
    const transactionBase64 = Buffer.from(built.transaction).toString('base64');
    const simulationRaw = await this.rpc.call('SOLANA', 'simulateTransaction', [transactionBase64,
      { encoding: 'base64', commitment: 'confirmed', sigVerify: false, replaceRecentBlockhash: false }], signal);
    const simulation = z.object({ context: z.object({ slot: z.unknown() }), value: z.object({ err: z.unknown() }).passthrough() }).parse(simulationRaw);
    if (simulation.value.err !== null) throw new TransferPreparationError('simulation_failed', 422, 'Transfer simulation failed. No transaction was created.');
    const simulationSlot = unsigned(simulation.context.slot);
    if (simulationSlot + 150n < latestSlot || latestSlot + 150n < simulationSlot) throw new TransferPreparationError('stale_chain_data', 503, 'Solana simulation is not from a consistent recent slot.');
    const preparedAt = new Date(this.now()).toISOString();
    return { schemaVersion: 1, operationId: input.operationId, chain: 'SOLANA', caip2: CAIP_SOLANA, asset,
      sender: input.sender, recipient: input.recipient, amount: input.amount, baseUnits: amount.toString(), balanceBaseUnits: balance.toString(),
      estimatedFeeBaseUnits: fee.toString(), maxFeeBaseUnits: fee.toString(), preparedAt,
      expiresAt: new Date(this.now() + 45_000).toISOString(), observedSlot: latestSlot.toString(), recentBlockhash: latest.value.blockhash,
      lastValidBlockHeight: lastValidBlockHeight.toString(), transactionBase64, encoding: 'base64' };
  }
}

export { exactBaseUnits };

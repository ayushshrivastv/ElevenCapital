const MASK_64 = (1n << 64n) - 1n;
const ROTATION = [
  0, 1, 62, 28, 27,
  36, 44, 6, 55, 20,
  3, 10, 43, 25, 39,
  41, 45, 15, 21, 8,
  18, 2, 61, 56, 14,
] as const;
const ROUND = [
  0x0000000000000001n, 0x0000000000008082n, 0x800000000000808an, 0x8000000080008000n,
  0x000000000000808bn, 0x0000000080000001n, 0x8000000080008081n, 0x8000000000008009n,
  0x000000000000008an, 0x0000000000000088n, 0x0000000080008009n, 0x000000008000000an,
  0x000000008000808bn, 0x800000000000008bn, 0x8000000000008089n, 0x8000000000008003n,
  0x8000000000008002n, 0x8000000000000080n, 0x000000000000800an, 0x800000008000000an,
  0x8000000080008081n, 0x8000000000008080n, 0x0000000080000001n, 0x8000000080008008n,
] as const;

function rotate(value: bigint, amount: number): bigint {
  if (amount === 0) return value & MASK_64;
  return ((value << BigInt(amount)) | (value >> BigInt(64 - amount))) & MASK_64;
}

function permutation(state: bigint[]): void {
  for (const round of ROUND) {
    const columns = Array<bigint>(5);
    for (let x = 0; x < 5; x++) columns[x] = state[x]! ^ state[x + 5]! ^ state[x + 10]! ^ state[x + 15]! ^ state[x + 20]!;
    const deltas = columns.map((_, x) => columns[(x + 4) % 5]! ^ rotate(columns[(x + 1) % 5]!, 1));
    for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) state[x + 5 * y] = (state[x + 5 * y]! ^ deltas[x]!) & MASK_64;
    const moved = Array<bigint>(25).fill(0n);
    for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) moved[y + 5 * ((2 * x + 3 * y) % 5)] = rotate(state[x + 5 * y]!, ROTATION[x + 5 * y]!);
    for (let y = 0; y < 5; y++) for (let x = 0; x < 5; x++) {
      state[x + 5 * y] = (moved[x + 5 * y]! ^ ((~moved[(x + 1) % 5 + 5 * y]!) & moved[(x + 2) % 5 + 5 * y]!)) & MASK_64;
    }
    state[0] = (state[0]! ^ round) & MASK_64;
  }
}

/** Keccak-256 (Ethereum padding, not NIST SHA3-256). */
export function keccak256(input: Uint8Array): Uint8Array {
  const rate = 136;
  const paddedLength = Math.ceil((input.length + 1) / rate) * rate;
  const padded = new Uint8Array(paddedLength);
  padded.set(input);
  padded[input.length] = 0x01;
  padded[padded.length - 1] = padded[padded.length - 1]! | 0x80;
  const state = Array<bigint>(25).fill(0n);
  for (let offset = 0; offset < padded.length; offset += rate) {
    for (let index = 0; index < rate; index++) {
      const lane = Math.floor(index / 8);
      state[lane] = state[lane]! ^ (BigInt(padded[offset + index]!) << BigInt(8 * (index % 8)));
    }
    permutation(state);
  }
  const result = new Uint8Array(32);
  for (let index = 0; index < result.length; index++) result[index] = Number((state[Math.floor(index / 8)]! >> BigInt(8 * (index % 8))) & 0xffn);
  return result;
}

export function keccak256Hex(value: string): string {
  return Buffer.from(keccak256(Buffer.from(value, 'utf8'))).toString('hex');
}

import type { AccountInfo } from '@solana/web3.js';
import { z } from 'zod';
import { MAINNET_GENESIS } from './portfolio-upstream.js';
import { isSolanaAddress } from './portfolio-schema.js';
import { publicTransferRpc, type TransferRpc } from './transfer-rpc.js';

export const CommunityMintParamsSchema = z.object({ mint: z.string().min(32).max(44).refine(isSolanaAddress) }).strict();
export const CommunityMintValidationSchema = z.object({
  schemaVersion: z.literal(1), network: z.literal('solana-mainnet'),
  mintAddress: z.string().min(32).max(44).refine(isSolanaAddress), valid: z.literal(true),
}).strict();
export type CommunityMintValidation = z.infer<typeof CommunityMintValidationSchema>;
export interface CommunityMintValidationApi { validate(mintAddress: string): Promise<CommunityMintValidation> }

export class CommunityMintValidationError extends Error {
  constructor(readonly code: 'invalid_mint' | 'mint_not_found' | 'not_token_mint' | 'mainnet_rpc_unavailable',
    readonly statusCode: 400 | 404 | 422 | 503, message: string) { super(message); }
}

const RPC_UNAVAILABLE = () => new CommunityMintValidationError('mainnet_rpc_unavailable', 503,
  'Solana Mainnet token validation is temporarily unavailable.');
const NOT_MINT = () => new CommunityMintValidationError('not_token_mint', 422,
  'This address is not an initialized Solana token mint.');
const MAX_ACCOUNT_BYTES = 65_536;

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function decodeAccount(raw: unknown): { owner: string; executable: boolean; data: Buffer } | null {
  const envelope = record(raw);
  if (!envelope || !record(envelope.context) || !('value' in envelope)) throw RPC_UNAVAILABLE();
  if (envelope.value === null) return null;
  const account = record(envelope.value);
  if (!account || typeof account.owner !== 'string' || typeof account.executable !== 'boolean' ||
    !Array.isArray(account.data) || account.data.length !== 2 || account.data[1] !== 'base64' ||
    typeof account.data[0] !== 'string') throw RPC_UNAVAILABLE();
  const encoded = account.data[0];
  if (encoded.length > Math.ceil(MAX_ACCOUNT_BYTES * 4 / 3) + 4 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw RPC_UNAVAILABLE();
  const data = Buffer.from(encoded, 'base64');
  if (data.length > MAX_ACCOUNT_BYTES || data.toString('base64') !== encoded) throw RPC_UNAVAILABLE();
  return { owner: account.owner, executable: account.executable, data };
}

/** AccountType.Mint follows the 165-byte base account span in extended Token-2022 mints. */
function hasValidToken2022Extensions(data: Buffer, mintSize: number, mintAccountType: number): boolean {
  if (data.length === mintSize) return true;
  if (data.length <= 165 || data[165] !== mintAccountType) return false;
  let offset = 166;
  while (offset < data.length) {
    if (offset + 4 > data.length) return data.subarray(offset).every(byte => byte === 0);
    const kind = data.readUInt16LE(offset);
    const length = data.readUInt16LE(offset + 2);
    if (kind === 0) return data.subarray(offset).every(byte => byte === 0); // Unused account padding.
    if (length > data.length - offset - 4) return false;
    offset += 4 + length;
  }
  return true;
}

/** Read-only validation: a successful result confirms a Mainnet mint, not Meteora origin or ownership. */
export class CommunityMintValidationService implements CommunityMintValidationApi {
  constructor(private readonly rpc: TransferRpc = publicTransferRpc()) {}

  async validate(mintAddress: string): Promise<CommunityMintValidation> {
    const params = CommunityMintParamsSchema.safeParse({ mint: mintAddress });
    if (!params.success) throw new CommunityMintValidationError('invalid_mint', 400, 'Token address is invalid.');
    const [{ AccountType, MINT_SIZE, TOKEN_2022_PROGRAM_ID, TOKEN_PROGRAM_ID, unpackMint }, { PublicKey }] = await Promise.all([
      import('@solana/spl-token'), import('@solana/web3.js'),
    ]);
    let mint: InstanceType<typeof PublicKey>;
    try { mint = new PublicKey(mintAddress); }
    catch { throw new CommunityMintValidationError('invalid_mint', 400, 'Token address is invalid.'); }
    if (mint.toBase58() !== mintAddress) throw new CommunityMintValidationError('invalid_mint', 400, 'Token address is invalid.');

    let raw: unknown;
    try {
      const signal = AbortSignal.timeout(13_000);
      const genesis = await this.rpc.call('SOLANA', 'getGenesisHash', [], signal);
      if (genesis !== MAINNET_GENESIS) throw RPC_UNAVAILABLE();
      raw = await this.rpc.call('SOLANA', 'getAccountInfo', [mintAddress,
        { encoding: 'base64', commitment: 'confirmed' }], signal);
    } catch { throw RPC_UNAVAILABLE(); }

    const account = decodeAccount(raw);
    if (account === null) throw new CommunityMintValidationError('mint_not_found', 404,
      'No token was found at this address on Solana Mainnet.');
    const tokenProgram = account.owner === TOKEN_PROGRAM_ID.toBase58() ? TOKEN_PROGRAM_ID :
      account.owner === TOKEN_2022_PROGRAM_ID.toBase58() ? TOKEN_2022_PROGRAM_ID : null;
    if (!tokenProgram || account.executable || account.data.length < MINT_SIZE ||
      (tokenProgram.equals(TOKEN_PROGRAM_ID) && account.data.length !== MINT_SIZE) ||
      (tokenProgram.equals(TOKEN_2022_PROGRAM_ID) &&
        !hasValidToken2022Extensions(account.data, MINT_SIZE, AccountType.Mint)) ||
      account.data.readUInt32LE(0) > 1 || account.data[45] !== 1 || account.data.readUInt32LE(46) > 1) throw NOT_MINT();
    try {
      const decoded = unpackMint(mint, { data: account.data, owner: tokenProgram,
        executable: false, lamports: 0, rentEpoch: 0 } as AccountInfo<Buffer>, tokenProgram);
      if (!decoded.isInitialized) throw NOT_MINT();
    } catch { throw NOT_MINT(); }
    return { schemaVersion: 1, network: 'solana-mainnet', mintAddress, valid: true };
  }
}

/** Offline canonical DBC launch vector for Android's independent signing validator. Never broadcasts. */
import { Connection, Keypair, PublicKey, SystemProgram, Transaction, TransactionInstruction } from '@solana/web3.js';
import { NATIVE_MINT, TOKEN_PROGRAM_ID } from '@solana/spl-token';
import { DYNAMIC_BONDING_CURVE_PROGRAM_ID, DynamicBondingCurveClient, deriveDbcEventAuthority,
  deriveDbcPoolAddress, deriveDbcPoolAuthority, deriveDbcTokenVaultAddress, deriveMintMetadata,
} from '@meteora-ag/dynamic-bonding-curve-sdk';
import { reviewedCurve } from '../../src/token-launch-chain.js';

const wallet = Keypair.fromSeed(Uint8Array.from({ length: 32 }, () => 1));
const mint = Keypair.fromSeed(Uint8Array.from({ length: 32 }, () => 2));
const config = Keypair.fromSeed(Uint8Array.from({ length: 32 }, () => 3));
const blockhash = Keypair.fromSeed(Uint8Array.from({ length: 32 }, () => 4)).publicKey.toBase58();
const name = 'Launch Test'; const symbol = 'TEST'; const metadataUri = 'https://metadata.example/token.json';
const pool = deriveDbcPoolAddress(NATIVE_MINT, mint.publicKey, config.publicKey);
const program = DYNAMIC_BONDING_CURVE_PROGRAM_ID;
const event = deriveDbcEventAuthority();
const k = (pubkey: PublicKey, isSigner = false, isWritable = false) => ({ pubkey, isSigner, isWritable });
const coder = new DynamicBondingCurveClient(new Connection('https://api.mainnet.solana.com'), 'confirmed')
  .state.getProgram().coder.instruction;
const configInstruction = new TransactionInstruction({ programId: program,
  data: coder.encode('createConfig', { configParameters: reviewedCurve({ totalTokenSupply: '1000000000', feeBps: 100,
    migrationThresholdSol: '1', percentageSupplyOnMigration: 20 }) }),
  keys: [k(config.publicKey, true, true), k(wallet.publicKey), k(wallet.publicKey), k(NATIVE_MINT),
    k(wallet.publicKey, true, true), k(SystemProgram.programId), k(event), k(program)] });
const poolInstruction = new TransactionInstruction({ programId: program,
  data: coder.encode('initializeVirtualPoolWithSplToken', { params: { name, symbol, uri: metadataUri } }),
  keys: [k(config.publicKey), k(deriveDbcPoolAuthority()), k(wallet.publicKey, true), k(mint.publicKey, true, true),
    k(NATIVE_MINT), k(pool, false, true), k(deriveDbcTokenVaultAddress(pool, mint.publicKey), false, true),
    k(deriveDbcTokenVaultAddress(pool, NATIVE_MINT), false, true), k(deriveMintMetadata(mint.publicKey), false, true),
    k(new PublicKey('metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s')),
    k(wallet.publicKey, true, true), k(TOKEN_PROGRAM_ID), k(TOKEN_PROGRAM_ID), k(SystemProgram.programId),
    k(event), k(program)] });
const transaction = new Transaction({ feePayer: wallet.publicKey, blockhash, lastValidBlockHeight: 1_000 });
transaction.add(configInstruction, poolInstruction);
transaction.partialSign(mint, config);
const partial = transaction.serialize({ requireAllSignatures: false, verifySignatures: true });
process.stdout.write(JSON.stringify({ network: 'SOLANA_MAINNET', walletAddress: wallet.publicKey.toBase58(),
  mintAddress: mint.publicKey.toBase58(), configAddress: config.publicKey.toBase58(), poolAddress: pool.toBase58(),
  recentBlockhash: blockhash, name, symbol, metadataUri, totalTokenSupply: '1000000000', feeBps: 100,
  migrationThresholdSol: '1', percentageSupplyOnMigration: 20,
  programId: program.toBase58(), quoteMint: NATIVE_MINT.toBase58(),
  configInstructionHex: configInstruction.data.toString('hex'), poolInstructionHex: poolInstruction.data.toString('hex'),
  transactionBase64: partial.toString('base64'),
}) + '\n');

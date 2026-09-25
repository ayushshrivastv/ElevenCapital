import { z } from 'zod';
import { Decimal } from 'decimal.js';

const ExactDecimal = Decimal.clone({ precision: 160 });

export const WalletChain = z.enum(['SOLANA', 'ETHEREUM']);
export type WalletChain = z.infer<typeof WalletChain>;
/** A Privy EVM wallet has one address that can own assets on both EVM networks. */
export const PortfolioNetwork = z.enum(['SOLANA', 'ETHEREUM', 'ARBITRUM']);
export type PortfolioNetwork = z.infer<typeof PortfolioNetwork>;
export const SolanaAddress = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
export const EthereumAddress = /^0x[0-9a-fA-F]{40}$/;
/** Reject syntactically base58 strings that are not exactly 32 decoded bytes. */
export function isSolanaAddress(value: string): boolean {
  if (!SolanaAddress.test(value)) return false;
  const alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  let integer = 0n;
  for (const letter of value) integer = integer * 58n + BigInt(alphabet.indexOf(letter));
  const leading = value.match(/^1*/)?.[0].length ?? 0;
  const bytes = integer === 0n ? 0 : Math.ceil(integer.toString(16).length / 2);
  return leading + bytes === 32;
}
const Wallet = z.object({ chain: WalletChain, address: z.string().max(44) }).strict().refine(
  wallet => wallet.chain === 'SOLANA' ? isSolanaAddress(wallet.address) : EthereumAddress.test(wallet.address),
  'Invalid wallet address');
export const PortfolioRequestSchema = z.object({ wallets: z.array(Wallet).min(1).max(10) }).strict().refine(
  request => new Set(request.wallets.map(wallet => `${wallet.chain}:${wallet.chain === 'ETHEREUM' ? wallet.address.toLowerCase() : wallet.address}`)).size === request.wallets.length,
  'Duplicate wallet address').refine(request => ['SOLANA', 'ETHEREUM'].every(chain => request.wallets.filter(wallet => wallet.chain === chain).length <= 5),
  'Too many wallets on one network');
export type PortfolioRequest = z.infer<typeof PortfolioRequestSchema>;
export type WalletAddress = PortfolioRequest['wallets'][number];
export type PortfolioReadWallet = { chain: PortfolioNetwork; address: string };
export const PortfolioDecimal = z.string().max(100).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/).refine(
  value => new Decimal(value).isFinite() && new Decimal(value).lte('1e60'));
export const PortfolioSchema = z.object({
  schemaVersion: z.literal(1), scope: z.literal('supported-wallet-assets'), currency: z.literal('USD'),
  status: z.enum(['ok', 'partial', 'unavailable']), receivedAt: z.iso.datetime(),
  balanceUsd: PortfolioDecimal.nullable(),
  holdingsComplete: z.boolean(),
  holdings: z.array(z.object({
    stockId: z.string().min(1).max(100), quantity: PortfolioDecimal.refine(value => new Decimal(value).gt(0)),
    valueUsd: PortfolioDecimal.nullable(),
  }).strict()).max(200),
  tokenHoldings: z.array(z.object({
    chain: PortfolioNetwork,
    assetId: z.string().min(1).max(60).regex(/^(?:SOLANA:(?:native|[1-9A-HJ-NP-Za-km-z]{32,44})|(?:ETHEREUM|ARBITRUM):(?:native|0x[0-9a-f]{40}))$/),
    symbol: z.string().min(1).max(12).regex(/^(?:[A-Z0-9]+|[1-9A-HJ-NP-Za-km-z]{4}…[1-9A-HJ-NP-Za-km-z]{4})$/),
    quantity: PortfolioDecimal.refine(value => new Decimal(value).gt(0)),
    valueUsd: PortfolioDecimal.nullable(),
    unitPriceUsd: PortfolioDecimal.refine(value => new ExactDecimal(value).gt(0)).nullable(),
  }).strict()).max(200),
  unpricedAssets: z.number().int().min(0).max(2_000),
  networks: z.array(z.object({ chain: PortfolioNetwork, status: z.enum(['ok', 'unavailable']), observedAt: z.iso.datetime().nullable() }).strict()).length(3),
  message: z.string().max(300).nullable(),
}).strict().superRefine((value, context) => {
  if (new Set(value.networks.map(network => network.chain)).size !== PortfolioNetwork.options.length ||
    PortfolioNetwork.options.some(chain => !value.networks.some(network => network.chain === chain)) ||
    new Set(value.holdings.map(holding => holding.stockId)).size !== value.holdings.length ||
    new Set(value.tokenHoldings.map(holding => holding.assetId)).size !== value.tokenHoldings.length) {
    context.addIssue({ code: 'custom', message: 'Duplicate portfolio identity' });
  }
  for (const holding of value.tokenHoldings) {
    if (!holding.assetId.startsWith(`${holding.chain}:`) || (holding.valueUsd === null) !== (holding.unitPriceUsd === null) ||
      (holding.unitPriceUsd !== null && new ExactDecimal(holding.quantity).times(holding.unitPriceUsd).toFixed() !== holding.valueUsd)) {
      context.addIssue({ code: 'custom', message: 'Invalid token valuation' });
    }
  }
  for (const network of value.networks) if ((network.status === 'ok') !== (network.observedAt !== null)) {
    context.addIssue({ code: 'custom', message: 'Network status requires matching observation' });
  }
  if (value.holdingsComplete && value.networks.some(network => network.status !== 'ok')) context.addIssue({ code: 'custom', message: 'Holdings coverage requires both networks' });
  if (value.status === 'ok') {
    if (!value.holdingsComplete || value.balanceUsd === null || value.unpricedAssets !== 0 || value.networks.some(network => network.status !== 'ok') ||
      value.holdings.some(holding => holding.valueUsd === null) || value.tokenHoldings.some(holding => holding.valueUsd === null) ||
      value.message !== null) context.addIssue({ code: 'custom', message: 'Incomplete total' });
    if (value.balanceUsd !== null && [...value.holdings, ...value.tokenHoldings]
      .reduce((sum, holding) => sum.plus(holding.valueUsd ?? 0), new ExactDecimal(0)).toFixed() !== value.balanceUsd) {
      context.addIssue({ code: 'custom', message: 'Total does not equal holdings' });
    }
  } else if (value.balanceUsd !== null || !value.message) context.addIssue({ code: 'custom', message: 'Incomplete data cannot publish a total' });
});
export type Portfolio = z.infer<typeof PortfolioSchema>;

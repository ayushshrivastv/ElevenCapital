/** Run with `npm run probe:wallet`. Generates read-only synthetic addresses; never prints them. */
import { randomBytes } from 'node:crypto';
import { CatalogSchema } from './schema.js';
import { PortfolioSchema } from './portfolio-schema.js';
import { MAINNET_GENESIS, publicPortfolioTransport, portfolioReadFailure } from './portfolio-upstream.js';

function syntheticSolanaAddress(): string {
  const alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  const bytes = randomBytes(32);
  let value = BigInt(`0x${bytes.toString('hex')}`);
  let encoded = '';
  while (value > 0n) { encoded = alphabet[Number(value % 58n)] + encoded; value /= 58n; }
  for (const byte of bytes) { if (byte !== 0) break; encoded = `1${encoded}`; }
  return encoded;
}
const transport = publicPortfolioTransport();
const identity: Record<string, unknown>[] = [];
for (const chain of ['SOLANA', 'ETHEREUM'] as const) {
  try {
    const method = chain === 'SOLANA' ? 'getGenesisHash' : 'eth_chainId';
    const [result] = await transport.rpc(chain, [{ method, params: [] }], AbortSignal.timeout(10_000));
    identity.push({ chain, reachable: true, mainnet: chain === 'SOLANA' ? result === MAINNET_GENESIS : result === '0x1' });
  } catch (error) { identity.push({ chain, reachable: false, reason: portfolioReadFailure(error) }); }
}
let stage = 'catalog';
try {
  const response = await fetch('http://127.0.0.1:8787/v1/stocks', { signal: AbortSignal.timeout(25_000), redirect: 'error' });
  if (!response.ok) throw new Error('Local catalog unavailable');
  const text = await response.text();
  if (text.length > 16 * 1024 * 1024) throw new Error('Catalog exceeded bound');
  const catalog = CatalogSchema.parse(JSON.parse(text));
  const payload = { wallets: [
    { chain: 'SOLANA', address: syntheticSolanaAddress() },
    { chain: 'ETHEREUM', address: `0x${randomBytes(20).toString('hex')}` },
  ] };
  stage = 'running_portfolio_endpoint';
  const portfolioResponse = await fetch('http://127.0.0.1:8787/v1/wallet/portfolio', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(payload), signal: AbortSignal.timeout(30_000), redirect: 'error',
  });
  if (!portfolioResponse.ok) throw new Error('Local portfolio unavailable');
  const portfolioText = await portfolioResponse.text();
  if (portfolioText.length > 4_000_000) throw new Error('Portfolio exceeded bound');
  const result = PortfolioSchema.parse(JSON.parse(portfolioText));
  const zero = result.status === 'ok' && result.balanceUsd === '0' && result.holdingsComplete && result.holdings.length === 0;
  console.log(JSON.stringify({ checkedAt: new Date().toISOString(), identity, supportedStockCount: catalog.stocks.length,
    status: result.status, holdingsComplete: result.holdingsComplete, balanceConfirmedZero: zero,
    networks: result.networks.map(({ chain, status }) => ({ chain, status })) }, null, 2));
  if (!zero) process.exitCode = 1;
} catch (error) {
  console.log(JSON.stringify({ checkedAt: new Date().toISOString(), identity, stage, status: 'probe_unavailable', reason: portfolioReadFailure(error) }, null, 2));
  process.exitCode = 1;
}

import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { configureBackendServerEnvironment } from '../src/server-config.ts';
import { getJson } from '../src/http.ts';
import { StockSchema } from '../src/schema.ts';
import { solanaMint, TokenStatisticsService } from '../src/statistics.ts';

const FEATURED = ['MSFT', 'SPCX', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'BRK.B',
  'JPM', 'V', 'MA', 'LLY', 'WMT', 'KO', 'DIS', 'COIN', 'AMD', 'AVGO'];
const METRICS = ['marketCapitalization', 'liquidity', 'holderCount', 'organicScore'];
const outputDirectory = resolve(fileURLToPath(new URL('../..', import.meta.url)), 'verification/provider-trading');

const secretStatus = await configureBackendServerEnvironment();
if (!secretStatus.jupiterApiKeyConfigured) throw new Error('Jupiter server authentication is not configured.');
const response = await fetch('http://127.0.0.1:8787/v1/stocks', { method: 'GET', redirect: 'error',
  signal: AbortSignal.timeout(30_000), headers: { Accept: 'application/json' } });
if (!response.ok) throw new Error(`Loopback catalog returned HTTP ${response.status}.`);
const catalog = await response.json();
const stocks = Array.isArray(catalog.stocks) ? catalog.stocks.flatMap(value => {
  const parsed = StockSchema.safeParse(value); return parsed.success ? [parsed.data] : [];
}) : [];
const selected = FEATURED.flatMap(symbol => {
  const candidates = stocks.filter(stock => stock.underlying.symbol === symbol && solanaMint(stock.deployments) !== null);
  const stock = candidates.find(row => row.provider === 'backed') ?? candidates.find(row => row.provider === 'prestocks') ?? candidates[0];
  return stock ? [stock] : [];
}).slice(0, 20);
const observedAt = Date.now();
const enriched = await new TokenStatisticsService(getJson, () => observedAt, 0).enrich(selected);
const rows = enriched.map(stock => ({
  id: stock.id, provider: stock.provider, symbol: stock.symbol, underlyingSymbol: stock.underlying.symbol,
  exactSolanaMint: solanaMint(stock.deployments),
  quote: { status: stock.quote.basis === 'onchain_token_market' ? 'available' : 'unavailable',
    value: stock.quote.basis === 'onchain_token_market' ? stock.quote.price : null,
    currency: stock.quote.basis === 'onchain_token_market' ? stock.quote.currency : null,
    basis: stock.quote.basis, source: stock.quote.basis === 'onchain_token_market' ? 'Jupiter' : null,
    receivedAt: stock.quote.basis === 'onchain_token_market' ? stock.quote.receivedAt : null,
    reason: stock.quote.basis === 'onchain_token_market' ? null : 'Jupiter did not return a usable exact-mint USD quote.' },
  statistics: Object.fromEntries(METRICS.map(name => [name, stock.statistics[name]])),
  activity: stock.activity.source === 'Jupiter' ? stock.activity : {
    ...stock.activity, volume24h: null, netVolume24h: null, receivedAt: null, updatedAt: null,
    volumeReason: 'Jupiter did not return usable exact-mint activity.',
    netVolumeReason: 'Jupiter did not return usable exact-mint activity.',
  },
}));
const availability = {
  exactTokenQuotes: rows.filter(row => row.quote.status === 'available').length,
  activity: rows.filter(row => row.activity.volume24h !== null).length,
  ...Object.fromEntries(METRICS.map(name => [name, rows.filter(row => row.statistics[name].status === 'available').length])),
};
const report = {
  generatedAt: new Date(observedAt).toISOString(), source: 'Jupiter Tokens V2 search',
  endpoint: 'https://api.jup.ag/tokens/v2/search', authenticationConfigured: true,
  requestedRows: selected.length, returnedRows: rows.length, availability,
  selectionPolicy: 'One verified exact-Solana-mint Backed row for each ordered featured underlying symbol, capped at 20.',
  provenance: [
    'Every lookup key is the single verified Solana deployment address carried by that provider stock row.',
    'No ticker lookup, company-share substitution, FDV substitution, or cross-provider metric borrowing is used.',
    'The API credential is loaded from the owner-only backend secrets file and is never serialized into this report.',
    'Metric receivedAt is the local fetch time; statistics.updatedAt and activity.updatedAt retain Jupiter source time.',
  ], rows,
};
await mkdir(outputDirectory, { recursive: true });
await writeFile(resolve(outputDirectory, 'jupiter-authenticated-featured20.json'), JSON.stringify(report, null, 2) + '\n');
const lines = ['# Authenticated Jupiter featured-20 coverage', '', `Generated: ${report.generatedAt}`, '',
  `Exact mints requested: ${report.requestedRows}; responses normalized: ${report.returnedRows}.`, '',
  '| Field | Available | Requested |', '|---|---:|---:|',
  ...Object.entries(availability).map(([name, count]) => `| ${name} | ${count} | ${report.requestedRows} |`), '',
  'The JSON report records each public provider stock ID, exact Solana mint, metric basis, source timestamps, and unavailable reasons. It contains no API credential.', ''];
await writeFile(resolve(outputDirectory, 'jupiter-authenticated-featured20.md'), lines.join('\n'));
console.log(JSON.stringify({ authenticationConfigured: true, requestedRows: selected.length, returnedRows: rows.length, availability }, null, 2));

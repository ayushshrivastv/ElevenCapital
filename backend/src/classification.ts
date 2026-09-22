import { z } from 'zod';
import baseline from './classification-directory.json' with { type: 'json' };
import foreignBaseline from './foreign-classification-directory.json' with { type: 'json' };
import type { GetJson } from './http.js';
import { InactiveListings, ReviewedEquityFunds } from './classification-evidence.js';

export type Classification = { kind: 'company_share' | 'equity_etf' | 'excluded' | 'unresolved'; reason: string; source: string };
export type DirectoryRecord = { symbols: string[]; name: string; etf: boolean; testIssue: boolean };
export type ClassificationDirectory = { observedAt: string; sources: string[]; records: DirectoryRecord[] };
export const NasdaqBaseline: ClassificationDirectory = baseline;
const source = 'https://www.nasdaqtrader.com/Trader.aspx?id=SymbolDirDefs';
const excludedFund = /\b(bitcoin|ethereum|ether|crypto|hyperliquid|staking|solana|digital (?:asset|currency)|treasury|t-bill|bond|fixed income|muni|municipal|senior loan|\bCLO\b|MBS|short maturity|ultra-short income|VIX|volatility index|futures|ETNs?\b)/i;
const commodity = /\b(United States Oil|gold|silver|copper|platinum|palladium|crude oil|brent oil|natural gas|commodity|commodities|physical)\b/i;
// Equity-industry funds own company shares, e.g. Gold Miners and Oil Services; they are not bullion/oil products.
const equityIndustry = /\b(miners?|mining|oil services|oil & gas (?:exploration|exp\.)|agribusiness|nuclear)\b/i;
const equityFund = /(?:S&P\s*\d|Russell\s*\d|NASDAQ\s*100|\bQQQ\b|\bDow(?:\s*Jones|30)|\bMSCI\b|\bFTSE|\bCSI\b|\b(?:stock|equity|equities|shares|company|companies|dividend|Div Appreciation|growth|value|mid.?cap|small.?cap|large.?cap|mega.?cap|broad market|extended market|sector|technology|tech|semiconductor|software|internet|cybersecurity|real estate|REIT|biotech|biotechnology|banking|infrastructure|defense|aerospace|robotics|artificial intelligence|quantum|memory|photonics|optics|space|genomic|innovation|earnings|Latin America|South Korea|Developed World|retail|homebuilders|rare earth|DAX|EURO STOXX|NVIDIA|SpaceX|intelligent machines)\b)/i;
const equityShare = /\b(common (?:stock|shares|units)|ordinary shares?|voting shares|registry shares|capital stock|ADS|depositary (?:shares|receipts)|preferred (?:stock|shares)|class [A-Z] (?:shares|stock))\b/i;
/** The directory supplies an explicit ETF flag and security description. Unclear descriptions remain unresolved. */
export function classifySymbol(symbol: string, directory: ClassificationDirectory = NasdaqBaseline): Classification {
  const matches = directory.records.filter(row => row.symbols.includes(symbol));
  if (!matches.length && InactiveListings[symbol]) return { kind: 'excluded', ...InactiveListings[symbol]! };
  if (matches.length !== 1) return { kind: 'unresolved', reason: matches.length ? 'Ambiguous exchange-directory identity.' : 'Not present in the current US exchange directory.', source };
  const row = matches[0]!;
  if (row.testIssue) return { kind: 'excluded', reason: 'Exchange test security.', source };
  const reviewedFund = ReviewedEquityFunds[symbol];
  if (row.etf && reviewedFund?.name === row.name) return { kind: 'equity_etf', reason: reviewedFund.reason, source: reviewedFund.source };
  if (!row.etf && /\b(warrants?|rights|notes?|ETNs?|futures)\b/i.test(row.name)) return { kind: 'excluded', reason: 'Non-share security: ' + row.name, source };
  if (!row.etf) return (equityShare.test(row.name) || /\b(Inc\.?|Corp(?:oration)?\.?|plc|ASA|REIT|Ltd\.?)\b/i.test(row.name))
    ? { kind: 'company_share', reason: row.name, source }
    : { kind: 'unresolved', reason: 'Exchange description does not establish a company share: ' + row.name, source };
  if (excludedFund.test(row.name)) return { kind: 'excluded', reason: 'Outside stock-market ETF scope: ' + row.name, source };
  if (commodity.test(row.name) && !equityIndustry.test(row.name)) return { kind: 'excluded', reason: 'Commodity exposure: ' + row.name, source };
  // Single-company leveraged equity ETF names explicitly identify their long/short underlying; require that underlying to be a company share.
  const single = /\b(?:Long|Short|Inverse|Daily)\s+([A-Z][A-Z0-9.]{0,8})\b/.exec(row.name);
  const singleRow = single && directory.records.find(candidate => candidate.symbols.includes(single[1]!) && !candidate.etf && !candidate.testIssue && equityShare.test(candidate.name));
  if (equityFund.test(row.name) || equityIndustry.test(row.name) || singleRow) return { kind: 'equity_etf', reason: row.name, source };
  return { kind: 'unresolved', reason: 'ETF exposure needs classification: ' + row.name, source };
}
export function parseNasdaqDirectory(raw: unknown): DirectoryRecord[] {
  const text = z.string().max(4_000_000).parse(raw);
  const lines = text.trim().split(/\r?\n/); const header = lines.shift()!.split('|');
  if (!header.includes('ETF') || !header.includes('Test Issue') || !header.includes('Security Name')) throw new Error('Invalid exchange directory');
  const records = lines.filter(line => !line.startsWith('File Creation Time:')).map(line => {
    const values = line.split('|'); const get = (key: string) => values[header.indexOf(key)];
    if (!['Y','N'].includes(get('ETF') ?? '') || !['Y','N'].includes(get('Test Issue') ?? '')) throw new Error('Invalid exchange classification');
    const symbols = [...new Set(['Symbol','NASDAQ Symbol','CQS Symbol','ACT Symbol'].flatMap(key => get(key) ? [get(key)!] : []))];
    if (!symbols.length || !get('Security Name')) throw new Error('Missing exchange identity');
    return { symbols, name: get('Security Name')!.trim(), etf: get('ETF') === 'Y', testIssue: get('Test Issue') === 'Y' };
  });
  if (!records.length) throw new Error('Empty exchange directory');
  return records;
}
const directories = new WeakMap<GetJson, { value: ClassificationDirectory; checked: number; pending?: Promise<ClassificationDirectory> }>();
/** Shared six-hour classification cache, independent of quotes. Bundled dated official data is only a first-run fallback. */
export async function loadClassificationDirectory(http: GetJson, now = Date.now): Promise<ClassificationDirectory> {
  let entry = directories.get(http);
  if (!entry) { entry = { value: NasdaqBaseline, checked: -Infinity }; directories.set(http, entry); }
  if (entry.pending) return entry.pending;
  if (now() - entry.checked < 21_600_000) return entry.value;
  const current = entry;
  current.pending = (async () => {
    try {
      const responses = await Promise.all(['/dynamic/SymDir/nasdaqlisted.txt','/dynamic/SymDir/otherlisted.txt'].map(path => http('nasdaq', path)));
      current.value = { observedAt: new Date(now()).toISOString(), sources: NasdaqBaseline.sources, records: responses.flatMap(parseNasdaqDirectory) };
    } catch { /* Keep dated metadata; reconciliation exposes its age. Never manufacture classifications. */ }
    current.checked = now(); return current.value;
  })();
  try { return await current.pending; } finally { current.pending = undefined; }
}

/** Foreign listings are classified by exact ISIN and market, never by a US ticker collision. */
export function classifyForeign(isin: string, country: string | null): Classification {
  const records: {isin:string;country:string;reason:string;source?:string}[] = foreignBaseline.records;
  const matches = records.filter(row => row.isin === isin && row.country === country);
  if (matches.length === 1) return { kind: 'company_share', reason: matches[0]!.reason, source: matches[0]!.source ?? foreignBaseline.sources[0]! };
  return { kind: 'unresolved', reason: 'No verified foreign exchange classification for this ISIN and listing country.', source: '' };
}

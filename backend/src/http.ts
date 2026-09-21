import { parse } from 'lossless-json';
import { limitConcurrency } from './cache.js';

export type GetJson = (provider: 'backed' | 'backpack' | 'prestocks' | 'jupiter' | 'nasdaq' | 'yahoo' | 'geckoterminal', path: string, query?: Record<string, string>, signal?: AbortSignal) => Promise<unknown>;
export type GetJsonOptions = { jupiterApiKey?: () => string | undefined };
const Origins = { backed: 'https://api.xstocks.fi', backpack: 'https://api.backpack.exchange', prestocks: 'https://prestocks.com', jupiter: 'https://api.jup.ag', nasdaq: 'https://www.nasdaqtrader.com', yahoo: 'https://query1.finance.yahoo.com', geckoterminal: 'https://api.geckoterminal.com' } as const;
const PreStockOrigins = new Set(['https://prestocks.com', 'https://www.prestocks.com']);
const run = limitConcurrency(4);
// The issuer exposes one small cached price resource per xStock. Give these requests
// their own pool so catalog/classification work cannot make the quote rotation wait.
// A few slow symbols must not block the whole catalog; request starts are still
// globally paced below, so this is bounded in-flight concurrency rather than a burst.
const runBacked = limitConcurrency(4);
const runYahoo = limitConcurrency(4);
const runJupiter = limitConcurrency(1);
const runGeckoTerminal = limitConcurrency(1);
const MAX_BODY = 8_000_000;

/** Serialize request starts, including failed requests, to respect Jupiter's keyless 0.5 RPS. */
export function minimumRequestSpacing(intervalMs: number, now = Date.now,
  sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))) {
  let previousStart: number | undefined;
  let queue = Promise.resolve();
  return async () => {
    const turn = queue.then(async () => {
      if (previousStart !== undefined) {
        const remaining = intervalMs - (now() - previousStart);
        if (remaining > 0) await sleep(remaining);
      }
      previousStart = now();
    });
    queue = turn.catch(() => {});
    await turn;
  };
}
const waitForJupiterSlot = minimumRequestSpacing(2_050);
// The public issuer endpoint returns 429 when a cold catalog fans out. A single
// paced queue fills the catalog continuously without causing retry storms.
const waitForBackedSlot = minimumRequestSpacing(250);
const waitForYahooSlot = minimumRequestSpacing(250);
// GeckoTerminal's public API permits 30 calls/minute. Keep a small margin and
// serialize detail-only pool/history requests so multiple clients share the cap.
const waitForGeckoTerminalSlot = minimumRequestSpacing(2_050);

const SolanaAddress = '[1-9A-HJ-NP-Za-km-z]{32,44}';
const GeckoPoolsPath = new RegExp(`^/api/v2/networks/solana/tokens/${SolanaAddress}/pools$`);
const GeckoOhlcvPath = new RegExp(`^/api/v2/networks/solana/pools/${SolanaAddress}/ohlcv/(minute|hour|day)$`);
function validGeckoTerminalRequest(path: string, query: Record<string, string>): boolean {
  if (GeckoPoolsPath.test(path)) return Object.keys(query).length === 2 &&
    query.include === 'base_token,quote_token' && query.page === '1';
  const match = path.match(GeckoOhlcvPath);
  if (!match) return false;
  const keys = Object.keys(query);
  if (keys.some(key => !['aggregate', 'limit', 'currency', 'token', 'include_empty_intervals', 'before_timestamp'].includes(key)) ||
    (keys.length !== 5 && keys.length !== 6) || query.currency !== 'usd' || query.include_empty_intervals !== 'false' ||
    typeof query.token !== 'string' || !new RegExp(`^${SolanaAddress}$`).test(query.token) ||
    !/^\d{1,3}$/.test(query.limit ?? '') || Number(query.limit) < 1 || Number(query.limit) > 100 ||
    (query.before_timestamp !== undefined && !/^\d{9,12}$/.test(query.before_timestamp))) return false;
  const allowed = match[1] === 'minute' ? new Set(['1', '5', '15']) : match[1] === 'hour' ? new Set(['1', '4', '12']) : new Set(['1']);
  return typeof query.aggregate === 'string' && allowed.has(query.aggregate);
}

/** Raw JSON decimal literals become strings before Number can round them. */
export function parseProviderJson(text: string): unknown {
  return parse(text, undefined, { parseNumber: value => value });
}

export function createGetJson(options: GetJsonOptions = {}): GetJson {
  // Resolve lazily: ESM dependencies evaluate before server.ts loads its owner-only secrets file.
  const jupiterApiKey = options.jupiterApiKey ?? (() => process.env.JUPITER_API_KEY);
  return async (provider, path, query = {}, signal) => {
  const url = new URL(path, Origins[provider]);
  if (url.origin !== Origins[provider] || !path.startsWith('/')) throw new Error('Invalid upstream request');
  if (provider === 'nasdaq' && !['/dynamic/SymDir/nasdaqlisted.txt', '/dynamic/SymDir/otherlisted.txt'].includes(path)) throw new Error('Invalid classification request');
  if (provider === 'prestocks' && (path !== '/api/prestocks' || Object.keys(query).length > 0)) throw new Error('Invalid PreStocks request');
  if (provider === 'jupiter' && path !== '/tokens/v2/search') throw new Error('Invalid statistics request');
  if (provider === 'yahoo' && path !== '/v1/finance/search' && !/^\/v8\/finance\/chart\/[A-Za-z0-9.%^=_-]{1,80}$/.test(path)) throw new Error('Invalid underlying-reference request');
  if (provider === 'geckoterminal' && !validGeckoTerminalRequest(path, query)) throw new Error('Invalid GeckoTerminal request');
  for (const [key, value] of Object.entries(query)) url.searchParams.set(key, value);
  const request = async () => {
    if (provider === 'jupiter') await waitForJupiterSlot();
    if (provider === 'backed') await waitForBackedSlot();
    if (provider === 'yahoo') await waitForYahooSlot();
    if (provider === 'geckoterminal') await waitForGeckoTerminalSlot();
    signal?.throwIfAborted();
    const requestSignal = signal ? AbortSignal.any([signal, AbortSignal.timeout(8_000)]) : AbortSignal.timeout(8_000);
    const key = provider === 'jupiter' ? jupiterApiKey() : undefined;
    if (key !== undefined && (key.length === 0 || key.length > 2_048 || key.trim() !== key || /[\u0000-\u001f\u007f]/.test(key))) {
      throw new Error('Invalid Jupiter API key configuration');
    }
    const headers: Record<string, string> = { Accept: 'application/json', 'User-Agent': 'ElevenCapital/0.1 public-read-only' };
    if (key !== undefined) headers['x-api-key'] = key;
    const requestOptions: RequestInit = { method: 'GET', redirect: provider === 'prestocks' ? 'manual' : 'error', signal: requestSignal, headers };
    let response = await fetch(url, requestOptions);
    // The public site may canonicalize between its bare and www hosts. Follow at
    // most that one exact same-path redirect; never accept an arbitrary Location.
    if (provider === 'prestocks' && response.status >= 300 && response.status < 400) {
      const location = response.headers.get('location');
      const canonical = location ? new URL(location, url) : null;
      if (!canonical || !PreStockOrigins.has(canonical.origin) || canonical.pathname !== '/api/prestocks' || canonical.search || canonical.hash) {
        throw new Error('Invalid PreStocks redirect');
      }
      await response.body?.cancel();
      response = await fetch(canonical, { ...requestOptions, redirect: 'error' });
    }
    if (!response.ok) throw new Error('Public provider unavailable');
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Missing provider response');
    const chunks: Uint8Array[] = [];
    let length = 0;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        length += chunk.value.length;
        if (length > (provider === 'geckoterminal' ? 2_000_000 : MAX_BODY)) throw new Error('Provider response too large');
        chunks.push(chunk.value);
      }
    } finally { await reader.cancel(); }
    const text = Buffer.concat(chunks).toString('utf8');
    return provider === 'nasdaq' ? text : parseProviderJson(text);
  };
  // Statistics have their own single request slot, so a rate-limit wait cannot hold up quote work.
  return provider === 'jupiter' ? runJupiter(request) : provider === 'backed' ? runBacked(request) : provider === 'yahoo' ? runYahoo(request) :
    provider === 'geckoterminal' ? runGeckoTerminal(request) : run(request);
  };
}

export const getJson: GetJson = createGetJson();

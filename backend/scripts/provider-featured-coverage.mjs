import { mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import WebSocket from 'ws';

const FEATURED = ['MSFT', 'SPCX', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'BRK.B',
  'JPM', 'V', 'MA', 'LLY', 'WMT', 'KO', 'DIS', 'COIN', 'AMD', 'AVGO'];
const PROVIDERS = ['backed', 'backpack', 'prestocks'];
const METRICS = ['marketCapitalization', 'liquidity', 'holderCount', 'organicScore'];
const argv = process.argv.slice(2);
const valueAfter = key => { const index = argv.indexOf(key); return index >= 0 ? argv[index + 1] : undefined; };
const base = new URL(valueAfter('--base') ?? 'http://127.0.0.1:8787');
if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '::1'].includes(base.hostname) || (base.port || '80') !== '8787' ||
  base.username || base.password || base.pathname !== '/' || base.search || base.hash) {
  throw new Error('Coverage reports may query only the loopback HTTP service on port 8787.');
}
const interest = argv.includes('--interest');
const ranges = argv.includes('--all-ranges')
  ? ['ONE_HOUR', 'ONE_DAY', 'ONE_WEEK', 'ONE_MONTH', 'YEAR_TO_DATE'] : ['ONE_DAY'];
const root = fileURLToPath(new URL('../..', import.meta.url));
const outputDirectory = resolve(root, valueAfter('--output') ?? 'verification/provider-trading');

async function json(path, timeoutMs = 30_000) {
  const response = await fetch(new URL(path, base), { method: 'GET', redirect: 'error', signal: AbortSignal.timeout(timeoutMs),
    headers: { Accept: 'application/json', 'User-Agent': 'ElevenCapitalCoverage/0.1 read-only-loopback' } });
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}`);
  return response.json();
}

function selectSample(stocks) {
  const publicRows = provider => FEATURED.flatMap(symbol => {
    const row = stocks.find(stock => stock.provider === provider && stock.underlying?.symbol === symbol);
    return row ? [row] : [];
  }).slice(0, 20);
  return [...publicRows('backed'), ...publicRows('backpack'),
    ...stocks.filter(stock => stock.provider === 'prestocks').sort((a, b) => a.id.localeCompare(b.id))];
}

const delay = ms => new Promise(resolveDelay => setTimeout(resolveDelay, ms));
function sampleCounts(sample) {
  return Object.fromEntries(PROVIDERS.map(provider => [provider, sample.filter(stock => stock.provider === provider).length]));
}
function sampleReady(sample) {
  const counts = sampleCounts(sample);
  return counts.backed >= 20 && counts.backpack >= 20 && counts.prestocks > 0;
}
async function awaitProviderSample(initialCatalog, timeoutMs = 30_000) {
  const startedAt = Date.now();
  const deadline = startedAt + timeoutMs;
  let latest = initialCatalog;
  let sample = selectSample(latest.stocks ?? []);
  while (!sampleReady(sample) && Date.now() < deadline) {
    await delay(Math.min(500, Math.max(1, deadline - Date.now())));
    if (Date.now() >= deadline) break;
    try {
      latest = await json('/v1/stocks', Math.min(5_000, Math.max(1, deadline - Date.now())));
      sample = selectSample(latest.stocks ?? []);
    } catch { /* Retain the last complete provider snapshot and retry inside the fixed readiness window. */ }
  }
  return { catalog: latest, sample, readiness: { elapsedMs: Math.min(Date.now(), deadline) - startedAt,
    counts: sampleCounts(sample), timedOut: !sampleReady(sample) } };
}
function warmState(catalog, ids, startedAt) {
  const selected = (catalog.stocks ?? []).filter(stock => ids.has(stock.id));
  const eligible = selected.filter(stock => stock.deployments?.some(deployment => String(deployment.network).toLowerCase() === 'solana'));
  const fresh = eligible.filter(stock => {
    const metric = stock.statistics?.marketCapitalization;
    const received = Date.parse(metric?.receivedAt ?? '');
    return metric?.source === 'Jupiter' && Number.isFinite(received) && received >= startedAt - 5_000;
  });
  return { eligibleRows: eligible.length, freshRows: fresh.length, complete: eligible.length > 0 && fresh.length === eligible.length };
}
async function prioritize(ids) {
  if (!ids.length) return { catalog: await json('/v1/stocks'), warmup: { elapsedMs: 0, eligibleRows: 0, freshRows: 0, timedOut: false } };
  const socketUrl = new URL('/v1/market/ws', base); socketUrl.protocol = 'ws:';
  const socket = new WebSocket(socketUrl, { maxPayload: 20 * 1024 * 1024 });
  await Promise.race([new Promise((resolveOpen, reject) => {
    socket.once('open', resolveOpen); socket.once('error', reject);
  }), delay(10_000).then(() => { throw new Error('Timed out opening the bounded interest subscription.'); })]);
  const startedAt = Date.now();
  const deadline = startedAt + 120_000;
  let latest = await json('/v1/stocks');
  let subscribedIds = ids;
  let subscribedIdSet = new Set(subscribedIds);
  let state = warmState(latest, subscribedIdSet, startedAt);
  try {
    socket.send(JSON.stringify({ type: 'subscribe', ids: subscribedIds, detail: null }));
    while (!state.complete && Date.now() < deadline) {
      await delay(Math.min(2_000, Math.max(1, deadline - Date.now())));
      if (Date.now() >= deadline) break;
      try {
        latest = await json('/v1/stocks', Math.min(5_000, Math.max(1, deadline - Date.now())));
        const refreshedIds = selectSample(latest.stocks ?? []).map(stock => stock.id);
        if (refreshedIds.length !== subscribedIds.length || refreshedIds.some(id => !subscribedIdSet.has(id))) {
          subscribedIds = refreshedIds;
          subscribedIdSet = new Set(subscribedIds);
          socket.send(JSON.stringify({ type: 'subscribe', ids: subscribedIds, detail: null }));
        }
        state = warmState(latest, subscribedIdSet, startedAt);
      } catch { /* Retain the last snapshot and retry only inside the fixed warm-up window. */ }
    }
  } finally {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify({ type: 'subscribe', ids: [], detail: null }));
      await delay(100);
      const closed = new Promise(resolveClose => socket.once('close', resolveClose));
      socket.close(1000, 'coverage complete');
      await Promise.race([closed, delay(2_000)]);
      if (socket.readyState !== WebSocket.CLOSED) socket.terminate();
    } else socket.terminate();
  }
  return { catalog: latest, warmup: { elapsedMs: Math.min(Date.now(), deadline) - startedAt,
    eligibleRows: state.eligibleRows, freshRows: state.freshRows, timedOut: !state.complete } };
}

function metricSummary(stock) {
  return Object.fromEntries(METRICS.map(name => {
    const metric = stock.statistics?.[name];
    return [name, { status: metric?.status ?? 'missing', value: metric?.value ?? null, unit: metric?.unit ?? null,
      source: metric?.source ?? null, basis: metric?.basis ?? null, receivedAt: metric?.receivedAt ?? null,
      reason: metric?.reason ?? 'Metric is absent from the catalog row.' }];
  }));
}

function missingFields(stock, charts) {
  const gaps = [];
  if (stock.quote?.price == null) gaps.push('quote: ' + (stock.quote?.basis ?? 'missing basis'));
  if (stock.quote?.changePercent == null) gaps.push('changePercent: source supplied no usable percentage change');
  if (!stock.description) gaps.push('description: provider supplied no product description');
  if (!stock.informationUrl) gaps.push('informationUrl: provider supplied no product page URL');
  if (!stock.logoUrl) gaps.push('logo: provider supplied no verified artwork');
  if (!Array.isArray(stock.deployments) || stock.deployments.length === 0) gaps.push('explorer: no verified deployment address');
  for (const [name, metric] of Object.entries(metricSummary(stock))) if (metric.status !== 'available') gaps.push(`${name}: ${metric.reason}`);
  if (stock.activity?.volume24h == null) gaps.push('volume24h: ' + (stock.activity?.volumeReason ?? 'unavailable'));
  if (stock.activity?.netVolume24h == null) gaps.push('netVolume24h: ' + (stock.activity?.netVolumeReason ?? 'unavailable'));
  for (const chart of charts) if (chart.status !== 'ok') gaps.push(`${chart.range} graph: ${chart.statusReason ?? chart.status}`);
  return gaps;
}

const health = await json('/health');
let catalog = await json('/v1/stocks');
let sample = selectSample(catalog.stocks ?? []);
let catalogReadiness = { elapsedMs: 0, counts: sampleCounts(sample), timedOut: !sampleReady(sample) };
let warmup = { elapsedMs: 0, eligibleRows: 0, freshRows: 0, timedOut: false };
if (interest) {
  const ready = await awaitProviderSample(catalog);
  catalog = ready.catalog; sample = ready.sample; catalogReadiness = ready.readiness;
  const result = await prioritize(sample.map(stock => stock.id));
  catalog = result.catalog; warmup = result.warmup;
  sample = selectSample(catalog.stocks ?? []);
}
const diagnostics = await json('/v1/market/diagnostics');
const sampledRows = [];
for (const stock of sample) {
  const charts = [];
  for (const range of ranges) {
    try {
      const chart = await json(`/v1/stocks/${encodeURIComponent(stock.id)}/charts?range=${range}`);
      charts.push({ range, status: chart.status, basis: chart.basis, currency: chart.currency,
        receivedAt: chart.receivedAt ?? null, points: Array.isArray(chart.points) ? chart.points.length : 0,
        statusReason: chart.statusReason ?? null });
    } catch (failure) {
      charts.push({ range, status: 'request_failed', basis: stock.quote?.basis ?? null, currency: stock.quote?.currency ?? null,
        receivedAt: null, points: 0, statusReason: failure instanceof Error ? failure.message : 'Unknown chart request failure' });
    }
  }
  const row = { id: stock.id, provider: stock.provider, symbol: stock.symbol, underlyingSymbol: stock.underlying?.symbol ?? null,
    quote: { available: stock.quote?.price != null, price: stock.quote?.price ?? null, basis: stock.quote?.basis ?? null,
      currency: stock.quote?.currency ?? null, currencyBasis: stock.quote?.currencyBasis ?? null,
      changeAmount: stock.quote?.changeAmount ?? null, changePercent: stock.quote?.changePercent ?? null,
      asOf: stock.quote?.asOf ?? null, receivedAt: stock.quote?.receivedAt ?? null },
    metadata: { description: stock.description ?? null, informationUrl: stock.informationUrl ?? null, logoUrl: stock.logoUrl ?? null,
      deployments: Array.isArray(stock.deployments) ? stock.deployments.map(deployment => ({ network: deployment.network,
        address: deployment.address, decimals: deployment.decimals ?? null })) : [] },
    statisticsIdentity: { scope: stock.statistics?.scope ?? null, network: stock.statistics?.network ?? null,
      mint: stock.statistics?.mint ?? null, updatedAt: stock.statistics?.updatedAt ?? null },
    metrics: metricSummary(stock), activity: { volume24h: stock.activity?.volume24h ?? null,
      netVolume24h: stock.activity?.netVolume24h ?? null, currency: stock.activity?.currency ?? null,
      source: stock.activity?.source ?? null, scope: stock.activity?.scope ?? null,
      receivedAt: stock.activity?.receivedAt ?? null, updatedAt: stock.activity?.updatedAt ?? null,
      volumeReason: stock.activity?.volumeReason ?? null, netVolumeReason: stock.activity?.netVolumeReason ?? null }, charts };
  row.gaps = missingFields(stock, charts);
  sampledRows.push(row);
}

const stocks = catalog.stocks ?? [];
function countBy(values) {
  const counts = {};
  for (const value of values) counts[value] = (counts[value] ?? 0) + 1;
  return counts;
}
const providerSummary = Object.fromEntries(PROVIDERS.map(provider => {
  const rows = stocks.filter(stock => stock.provider === provider);
  const featured = rows.filter(stock => FEATURED.includes(stock.underlying?.symbol));
  const audited = sampledRows.filter(stock => stock.provider === provider);
  return [provider, { catalogRows: rows.length, featuredRows: featured.length, auditedRows: audited.length,
    pricesAvailable: audited.filter(stock => stock.quote.available).length,
    changesAvailable: audited.filter(stock => stock.quote.changePercent !== null).length,
    volumesAvailable: audited.filter(stock => stock.activity.volume24h !== null).length,
    netVolumesAvailable: audited.filter(stock => stock.activity.netVolume24h !== null).length,
    descriptionsAvailable: audited.filter(stock => stock.metadata.description !== null).length,
    informationUrlsAvailable: audited.filter(stock => stock.metadata.informationUrl !== null).length,
    logosAvailable: audited.filter(stock => stock.metadata.logoUrl !== null).length,
    explorerDeploymentsAvailable: audited.filter(stock => stock.metadata.deployments.length > 0).length,
    chartsAvailable: audited.filter(stock => stock.charts.some(chart => chart.status === 'ok')).length,
    genuineSourceLabeledCharts: audited.filter(stock => stock.charts.some(chart => chart.status === 'ok' && chart.basis !== null)).length,
    chartBasisCounts: countBy(audited.flatMap(stock => stock.charts.filter(chart => chart.status === 'ok').map(chart => chart.basis ?? 'missing'))),
    metricAvailability: Object.fromEntries(METRICS.map(name => [name,
      audited.filter(stock => stock.metrics[name]?.status === 'available').length])),
    preciseGaps: countBy(audited.flatMap(stock => stock.gaps)) }];
}));
const featuredMatrix = FEATURED.map(symbol => ({ symbol, rows: stocks.filter(stock => stock.underlying?.symbol === symbol)
  .map(stock => ({ id: stock.id, provider: stock.provider, quoteAvailable: stock.quote?.price != null,
    quoteBasis: stock.quote?.basis ?? null, description: !!stock.description, informationUrl: !!stock.informationUrl })) }));
const report = { generatedAt: new Date().toISOString(), service: base.href, readOnly: true, interestApplied: interest,
  samplePolicy: { backed: 'Ordered 20 featured underlying symbols', backpack: 'Ordered 20 featured underlying symbols',
    prestocks: 'Every current provider row' }, sampleSize: sampledRows.length, chartRangesProbed: ranges,
  catalogReadiness, warmup,
  health, catalogReceivedAt: catalog.receivedAt, providerStatus: catalog.providers, providerSummary, featuredMatrix, sampledRows,
  diagnostics: { appCount: diagnostics.appCount ?? null, visibleSubscriptionsAfterCleanup: diagnostics.metrics?.visibleSubscriptions ?? null,
    analyticsErrors: diagnostics.metrics?.analyticsErrors ?? null, quoteErrors: diagnostics.metrics?.quoteErrors ?? null,
    backedQuoteErrors: diagnostics.metrics?.backedQuoteErrors ?? null,
    lastBackedQuoteErrors: diagnostics.metrics?.lastBackedQuoteErrors ?? [],
    invalidUpdates: diagnostics.metrics?.invalidUpdates ?? null },
  provenanceNotes: [
    'This is a point-in-time observation of the process currently bound to loopback:8787; restart that process after source changes before comparing a rerun.',
    'Descriptions and information links count only fields present in provider catalog rows; no company biography or product URL is generated.',
    'Metrics are exact verified Solana-mint observations when available; missing values retain provider reasons.',
    'Chart status is recorded from the loopback service. A share-reference graph is not an executable xStock quote.',
    'The default run probes ONE_DAY for the 20 featured Backed rows, matching 20 Backpack rows, and every PreStocks row.',
  ] };

await mkdir(outputDirectory, { recursive: true });
await writeFile(resolve(outputDirectory, 'featured20-coverage.json'), JSON.stringify(report, null, 2) + '\n');
const lines = [
  '# Featured provider data coverage', '',
  `Generated: ${report.generatedAt}`, '',
  `Catalog: ${stocks.length} rows; sampled: ${sampledRows.length}; temporary interest: ${interest ? 'yes' : 'no'}.`, '',
  `Catalog readiness: Backed ${catalogReadiness.counts.backed}, Backpack ${catalogReadiness.counts.backpack}, PreStocks ${catalogReadiness.counts.prestocks}; timed out: ${catalogReadiness.timedOut ? 'yes' : 'no'}; elapsed: ${catalogReadiness.elapsedMs} ms.`, '',
  `Jupiter warm-up: ${warmup.freshRows}/${warmup.eligibleRows} eligible rows observed; timed out: ${warmup.timedOut ? 'yes' : 'no'}; elapsed: ${warmup.elapsedMs} ms.`, '',
  '| Provider | Audited | Price | Change % | Volume | Net volume | Charts | Descriptions | Info URLs | Logos | Explorer |',
  '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|',
  ...PROVIDERS.map(provider => { const item = providerSummary[provider]; return `| ${provider} | ${item.auditedRows} | ${item.pricesAvailable} | ${item.changesAvailable} | ${item.volumesAvailable} | ${item.netVolumesAvailable} | ${item.chartsAvailable} | ${item.descriptionsAvailable} | ${item.informationUrlsAvailable} | ${item.logosAvailable} | ${item.explorerDeploymentsAvailable} |`; }),
  '', '## Sampled rows', '',
  '| Provider | Symbol | Quote basis | 1d graph | Missing fields |', '|---|---|---|---|---|',
  ...sampledRows.map(row => `| ${row.provider} | ${row.symbol} | ${row.quote.basis ?? 'N/A'} | ${row.charts[0]?.status ?? 'not probed'} (${row.charts[0]?.points ?? 0}) | ${row.gaps.length} |`),
  '', 'See `featured20-coverage.json` for per-metric reasons, chart provenance, and the complete featured-symbol matrix.', '',
];
await writeFile(resolve(outputDirectory, 'README.md'), lines.join('\n'));
console.log(JSON.stringify({ outputDirectory, sampleSize: sampledRows.length, warmup,
  providerSummary: Object.fromEntries(PROVIDERS.map(provider => [provider, {
    auditedRows: providerSummary[provider].auditedRows, pricesAvailable: providerSummary[provider].pricesAvailable,
    changesAvailable: providerSummary[provider].changesAvailable, volumesAvailable: providerSummary[provider].volumesAvailable,
    netVolumesAvailable: providerSummary[provider].netVolumesAvailable, chartsAvailable: providerSummary[provider].chartsAvailable,
    metricAvailability: providerSummary[provider].metricAvailability }])),
  visibleSubscriptionsAfterCleanup: report.diagnostics.visibleSubscriptionsAfterCleanup }, null, 2));

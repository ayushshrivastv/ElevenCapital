import test from 'node:test';
import assert from 'node:assert/strict';
import { classifySymbol, classifyForeign, parseNasdaqDirectory, type ClassificationDirectory } from '../src/classification.js';
import { backpackCompanyLogo, loadBacked, loadBackpack } from '../src/providers.js';
import { normalizeJupiterQuotes, TokenStatisticsService, initialStatistics } from '../src/statistics.js';
import { unavailableJupiterActivity } from '../src/activity.js';
import type { GetJson } from '../src/http.js';
import type { Stock } from '../src/schema.js';
const NOW = Date.parse('2026-09-19T07:00:00Z');
const directory: ClassificationDirectory = { observedAt: new Date(NOW).toISOString(), sources: ['https://www.nasdaqtrader.com'], records: [
  { symbols: ['NEW'], name: 'New Company Common Stock', etf: false, testIssue: false },
  { symbols: ['INDEX'], name: 'Example Total Stock Market ETF', etf: true, testIssue: false },
  { symbols: ['GOLD'], name: 'Physical Gold ETF', etf: true, testIssue: false },
] };
function asset(symbol = 'NEW', id = '00000000-0000-0000-0000-000000000001') { return {
  id, symbol: symbol + 'x', name: 'New xStock', description: 'Provider-authored xStock product description', isin: 'CH1234567890', underlying: { symbol, isin: 'US1234567890', type: null, currency: 'USD', listingCountry: 'US' }, deployments: [], trading: null,
}; }
test('exchange ETF flags and descriptions include equities and equity ETFs, exclude commodity/crypto/bonds, keep unknown unresolved', () => {
  for (const symbol of ['MSFT','GOOG','ASML','TSM','V','SPCX']) assert.equal(classifySymbol(symbol).kind, 'company_share', symbol);
  for (const symbol of ['SPY','QQQ','GDX','SOXX','TSLL','YLDE','URA','PGJ']) assert.equal(classifySymbol(symbol).kind, 'equity_etf', symbol);
  for (const symbol of ['GLD','IBIT','ETHA','BND','USO','VXX']) assert.equal(classifySymbol(symbol).kind, 'excluded', symbol);
  assert.equal(classifySymbol('UNKNOWN').kind, 'unresolved');
  for (const symbol of ['AVB','EQR','EA','SATS','GTLS','BLD','WBS','MASI','APGE']) assert.equal(classifySymbol(symbol).kind, 'excluded', symbol);
  assert.equal(classifySymbol('SATS', { ...directory, records: [{ symbols:['SATS'], name:'New Company Common Stock', etf:false, testIssue:false }] }).kind, 'company_share');
  assert.equal(classifySymbol('URA', { ...directory, records: [{ symbols:['URA'], name:'Different Exposure ETF', etf:true, testIssue:false }] }).kind, 'unresolved');
  assert.equal(classifyForeign('ES0178430E18','ES').kind,'company_share');
  assert.equal(classifyForeign('DE0007664039','DE').kind,'company_share');
  assert.equal(classifyForeign('DE0007664039','US').kind,'unresolved');
});
test('directory parser honors explicit flags, excludes footer, and rejects malformed schemas', () => {
  assert.deepEqual(parseNasdaqDirectory('Symbol|Security Name|ETF|Test Issue\nABC|ABC Common Stock|N|N\nFile Creation Time: 2026||||'), [{ symbols:['ABC'],name:'ABC Common Stock',etf:false,testIssue:false }]);
  assert.throws(() => parseNasdaqDirectory('Symbol|Name\nABC|ABC'));
});
test('all Backed pages render newly classified assets with no per-symbol price calls; categories reconcile exactly', async () => {
  const calls: string[] = [];
  const http: GetJson = async (_provider,path,query) => {
    calls.push(path); assert.equal(path,'/api/v2/public/assets');
    return query?.page === '0' ? { nodes:[asset()],page:{currentPage:0,hasNextPage:true} } :
      { nodes:[asset('INDEX','00000000-0000-0000-0000-000000000002'),asset('GOLD','00000000-0000-0000-0000-000000000003'),asset('UNKNOWN','00000000-0000-0000-0000-000000000004')],page:{currentPage:1,hasNextPage:false} };
  };
  const result = await loadBacked(http,()=>NOW,{metadataOnly:true,directory});
  assert.equal(calls.length,2);assert.equal(result.stocks.length,2);assert.equal(result.stocks[0]!.quote.price,null);
  assert.equal(result.reconciliation!.sourceCount,4);assert.equal(result.reconciliation!.excluded.length,1);assert.equal(result.reconciliation!.unresolved.length,1);
  assert.equal(result.stocks[0]!.marketState?.status,'unknown');
  assert.equal(result.stocks[0]!.description,'Provider-authored xStock product description');
  assert.equal(result.stocks[0]!.informationUrl,null,'the asset response supplies no provider-owned product page URL');
});
test('nullable Backpack CUSIP does not hide new stocks and metadata caching is separate from ticker refresh', async () => {
  const calls: Record<string,number> = {};
  const http: GetJson = async(_provider,path)=>{calls[path]=(calls[path]??0)+1;
    if(path.endsWith('securities'))return[{asset:'NEW.US',name:'New Company',cusip:null}];
    if(path.endsWith('assets'))return[];
    return[{symbol:'NEW.US_USDC',lastPrice:'12',priceChange:'1',priceChangePercent:'0.1',quoteVolume:'100'}];};
  const options={directory,metadataTtlMs:300000};
  const first=await loadBackpack(http,()=>NOW,options);const second=await loadBackpack(http,()=>NOW+10000,options);
  assert.equal(first.stocks[0]!.id,'backpack:NEW.US');assert.equal(first.stocks[0]!.underlying.cusip,null);
  assert.equal(first.stocks[0]!.description,null);assert.equal(first.stocks[0]!.informationUrl,null);
  assert.equal(first.stocks[0]!.logoUrl,'https://financialmodelingprep.com/image-stock/NEW.png');
  assert.equal(calls['/api/v1/securities'],1);assert.equal(calls['/api/v1/tickers'],2);assert.equal(second.stocks.length,1);
});
test('Backpack company logo URLs require a bounded verified ticker', () => {
  assert.equal(backpackCompanyLogo('BRK.B'), 'https://financialmodelingprep.com/image-stock/BRK.B.png');
  for (const symbol of ['', '../AAPL', 'AAPL/evil', 'aapl', 'AAPL?x']) assert.equal(backpackCompanyLogo(symbol), null);
});
test('duplicate provider IDs are quarantined and reported instead of merging or hiding other valid stocks', async()=>{
  const duplicate=asset(); const http:GetJson=async()=>({nodes:[duplicate,duplicate,asset('INDEX','00000000-0000-0000-0000-000000000002')],page:{currentPage:0,hasNextPage:false}});
  const result=await loadBacked(http,()=>NOW,{metadataOnly:true,directory});assert.equal(result.stocks.length,1);assert.equal(result.reconciliation!.unresolved.length,2);
});
const mint='XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
test('token USD quote matches exact issuer mint and preserves same-source change; stale, duplicate and zero are rejected',()=>{
  const row={id:mint,usdPrice:'110',stats24h:{priceChange:'10'},updatedAt:new Date(NOW-1000).toISOString()};
  const quote=normalizeJupiterQuotes([row],[mint],NOW).get(mint)!;assert.equal(quote.price,'110');assert.equal(quote.changeAmount,'10');assert.equal(quote.changePercent,'10');assert.equal(quote.basis,'onchain_token_market');assert.equal(quote.currencyBasis,'quoted_currency');assert.equal(quote.asOf,null);
  for(const rows of [[{...row,id:'other'}],[row,row],[{...row,usdPrice:'0'}],[{...row,usdPrice:'1e-999999999'}],[{...row,updatedAt:new Date(NOW-300001).toISOString()}]])assert.equal(normalizeJupiterQuotes(rows,[mint],NOW).size,0);
});
test('batch callback publishes the first100 before slow later batch resolves and never replaces Backpack quotes',async()=>{
  const alphabet='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  const stocks=Array.from({length:101},(_,i)=>{const address='1'.repeat(30)+alphabet[Math.floor(i/58)]!+alphabet[i%58]!; const deployments=[{network:'Solana',address,decimals:8,depositEnabled:false,withdrawEnabled:false}];return{
    id:'backpack:'+i,provider:'backpack',providerAssetId:String(i),providerLabel:'Backpack',symbol:String(i),name:'Company',logoUrl:null,underlying:{symbol:String(i),isin:null,cusip:null},deployments,
    quote:{price:'8',currency:'USDC',currencyBasis:'market_symbol',changeAmount:null,changePercent:null,asOf:null,receivedAt:new Date(NOW).toISOString(),basis:'external_reference_non_executable'},statistics:initialStatistics(deployments),activity:unavailableJupiterActivity('Not received'),volume24h:null,volume24hBasis:null,trading:{enabled:false,reason:'Read only'},
  } satisfies Stock;});
  let release!:()=>void; const gate=new Promise<void>(resolve=>{release=resolve}); let calls=0; const published:number[]=[];
  const service=new TokenStatisticsService(async(_provider,_path,query)=>{if(++calls===2)await gate;return query!.query!.split(',').map(id=>({id,updatedAt:new Date(NOW).toISOString(),usdPrice:'999',holderCount:'1'}));},()=>NOW);
  const pending=service.enrichBatches(stocks,rows=>{published.push(rows.length);assert.ok(rows.every(row=>row.quote.price==='8'));});
  await new Promise(resolve=>setTimeout(resolve,10));assert.deepEqual(published,[100]);release();await pending;assert.deepEqual(published,[100,1]);
});

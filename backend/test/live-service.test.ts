import test from 'node:test';
import assert from 'node:assert/strict';
import { applyBackedIndicativePrice, LiveMarketService } from '../src/live-service.js';
import { Registry } from '../src/registry.js';
import type { GetJson } from '../src/http.js';
import type { Stock } from '../src/schema.js';
const entry=Registry.find(e=>e.symbol==='MSFT')!;
const at=Date.parse('2026-09-19T07:30:00Z');
const mint='XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const wait=async(check:()=>boolean)=>{const until=Date.now()+3000;while(!check()){if(Date.now()>until)throw Error('Timed out');await new Promise(r=>setTimeout(r,5));}};
test('complete identities and Backpack quotes publish without waiting for token analytics; same-basis chart tail follows quote',async()=>{
 let release!:(x:unknown)=>void;let calls=0;
 const pending=new Promise<unknown>(r=>release=r);
 const http:GetJson=async(provider,path)=>{
  if(provider==='nasdaq')throw Error('offline classification uses dated metadata');
  if(provider==='jupiter'){calls++;return pending;}
  if(provider==='backed')return{nodes:[{id:entry.backed.assetId,name:'Microsoft xStock',symbol:'MSFTx',isin:entry.backed.productIsin,
   underlying:{symbol:'MSFT',isin:entry.isin,currency:'USD',listingCountry:'US',type:null},deployments:[{network:'Solana',address:mint}]}],page:{currentPage:'0',hasNextPage:false}};
  if(path.endsWith('/securities'))return[{asset:'MSFT.US',cusip:entry.cusip,name:'Microsoft'}];
  if(path.endsWith('/assets'))return[];
  if(path.endsWith('/tickers'))return[{symbol:'MSFT.US_USDC',lastPrice:'491.234567890123456789',priceChange:'1',priceChangePercent:'0.002',quoteVolume:'456789.0123456789'}];
  if(provider==='geckoterminal')throw Error('no exact-mint pool history');
  if(path.endsWith('/klines'))return[{start:'2026-09-19 06:50:00',close:'489.5'},{start:'2026-09-19 07:00:00',close:'490.1'},{start:'2026-09-19 07:10:00',close:'491'}];
  throw Error('unexpectedpath');
 };
 let clock=at;
 const service=new LiveMarketService(http,()=>clock);const events:number[]=[];service.onUpdate(c=>events.push(c.stocks.length));
 try{
  await service.catalog();await wait(()=>service.snapshot().stocks.length===2&&calls>0);
  assert.equal(service.snapshot().stocks.find(s=>s.provider==='backpack')!.quote.price,'491.234567890123456789');
  assert.equal(service.snapshot().stocks.find(s=>s.provider==='backed')!.quote.basis,'underlying_share_reference');
  assert.equal(service.snapshot().stocks.find(s=>s.provider==='backed')!.quote.price,'491.234567890123456789');
  const referenceHistory=await service.chart(`backed:${entry.backed.assetId}`,'ONE_DAY');
  assert.equal(referenceHistory.basis,'underlying_share_reference');assert.equal(referenceHistory.currency,'USDC');
  release([{id:mint,usdPrice:'492.123456789012345',updatedAt:new Date(at).toISOString(),mcap:'12',liquidity:'12',holderCount:'2',organicScore:'1',stats24h:{buyVolume:'10',sellVolume:'3',priceChange:'2'}}]);
  await wait(()=>service.snapshot().stocks.find(s=>s.provider==='backed')!.quote.basis==='onchain_token_market');
  const backed=service.snapshot().stocks.find(s=>s.provider==='backed')!;
  const observed=await service.chart(backed.id,'ONE_DAY');assert.equal(observed.basis,'underlying_share_reference');assert.equal(observed.currency,'USDC');
  assert.equal(observed.points.length,3);assert.match(observed.statusReason!,/Verified same-share Backpack external reference history/);
  const chart=await service.chart('backpack:MSFT.US','ONE_DAY');assert.equal(chart.points.at(-1)!.price,'491.234567890123456789');assert.equal(chart.basis,'external_reference_non_executable');
  const newer={...chart,points:[{timestamp:new Date(at+1000).toISOString(),price:'500'}]};
  assert.deepEqual(service.withCurrent(newer).points,newer.points,'older quote cannot truncate newer history');
  clock=at+300_001;
  const oldHistory=await service.chart(backed.id,'ONE_DAY');assert.equal(oldHistory.status,'ok');assert.equal(oldHistory.points.length,3);
  assert.equal(service.diagnostics().metrics.invalidUpdates,0);
 }finally{service.stop();release?.([]);}
});
test('public xStocks indicative prices preserve identity and reject invalid observations', () => {
 const source={id:`backed:${entry.backed.assetId}`,provider:'backed',providerAssetId:entry.backed.assetId,
  providerLabel:'Backed xStocks',symbol:'MSFTx',name:'Microsoft xStock',logoUrl:'https://example.com/msft.png',
  underlying:{symbol:'MSFT',isin:entry.isin,cusip:entry.cusip,listingCountry:'US',currency:'USD'},
  quote:{price:null,currency:'USD',currencyBasis:'underlying_metadata',changeAmount:null,changePercent:null,asOf:null,receivedAt:null,basis:'provider_indicative_token'},
  volume24h:null,volume24hBasis:null,activity:{currency:'USD',source:'Jupiter',scope:'solana_token',volume24h:null,netVolume24h:null,receivedAt:null,updatedAt:null,volumeReason:'Not received',netVolumeReason:'Not received'},
  statistics:{scope:'solana_token',network:null,mint:null,updatedAt:null,
   marketCapitalization:{value:null,unit:'USD',source:null,status:'unavailable',basis:'token_market_cap',receivedAt:null,reason:'Not received'},
   liquidity:{value:null,unit:'USD',source:null,status:'unavailable',basis:'reported_token_liquidity',receivedAt:null,reason:'Not received'},
   holderCount:{value:null,unit:'count',source:null,status:'unavailable',basis:'token_holders',receivedAt:null,reason:'Not received'},
   organicScore:{value:null,unit:'score',source:null,status:'unavailable',basis:'organic_activity_score',receivedAt:null,reason:'Not received'}},
  deployments:[],trading:{enabled:false,reason:'Read only'}} satisfies Stock;
 const value=applyBackedIndicativePrice(source,{quote:'497.1234567890123456789'},at)!;
 assert.equal(value.quote.price,'497.1234567890123456789');assert.equal(value.quote.receivedAt,new Date(at).toISOString());
 assert.equal(value.quote.basis,'provider_indicative_token');assert.equal(value.id,source.id);
 for(const quote of [null,'0','-1','invalid','1e41'])assert.equal(applyBackedIndicativePrice(source,{quote},at),null);
 assert.equal(applyBackedIndicativePrice({...source,provider:'backpack'} as never,{quote:'1'},at),null);
});

test('token-priced stocks resolve exact-ISIN share history without borrowing a conflicting security', async () => {
 let searches=0; let backpackCharts=0;
 const http:GetJson=async(provider,path,query)=>{
  if(provider==='nasdaq')throw Error('classification offline');
  if(provider==='backed')return {nodes:[{id:entry.backed.assetId,name:'Microsoft xStock',symbol:'MSFTx',isin:entry.backed.productIsin,
   underlying:{symbol:'MSFT',isin:entry.isin,currency:'USD',listingCountry:'US',type:null},deployments:[{network:'Solana',address:mint}]}],page:{currentPage:'0',hasNextPage:false}};
  if(provider==='jupiter')return [{id:mint,usdPrice:'492',updatedAt:new Date(at).toISOString(),mcap:'12',liquidity:'12',holderCount:'2',organicScore:'1',stats24h:{buyVolume:'10',sellVolume:'3',priceChange:'2'}}];
  if(provider==='geckoterminal')return {data:[]};
  if(provider==='yahoo'&&path.endsWith('/search')){
   searches++;assert.equal(query?.q,entry.isin);
   return {quotes:[{symbol:'MSFT',exchange:'NMS',quoteType:'EQUITY',isYahooFinance:true}]};
  }
  if(provider==='yahoo'&&path.endsWith('/MSFT'))return {chart:{result:[{meta:{symbol:'MSFT',currency:'USD',instrumentType:'EQUITY',regularMarketPrice:'500',regularMarketTime:at/1000},
   timestamp:[at/1000-600,at/1000-300,at/1000],indicators:{quote:[{close:['498','499','500'],volume:['1','2','3']}]}}]}};
  if(path.endsWith('/securities'))return [{asset:'MSFT.US',cusip:'000000000',name:'Conflicting reference'}];
  if(path.endsWith('/assets'))return [];
  if(path.endsWith('/tickers'))return [{symbol:'MSFT.US_USDC',lastPrice:'999',priceChange:'1',priceChangePercent:'0.002',quoteVolume:'1'}];
  if(path.endsWith('/klines')){backpackCharts++;throw Error('Wrong security history must not be used');}
  throw Error('Unexpected request');
 };
 const service=new LiveMarketService(http,()=>at);
 try{
  await service.catalog();await wait(()=>service.snapshot().stocks.some(s=>s.provider==='backed'&&s.quote.basis==='onchain_token_market'));
  const id=`backed:${entry.backed.assetId}`;
  const [first,second]=await Promise.all([service.chart(id,'ONE_DAY'),service.chart(id,'ONE_DAY')]);
  for(const chart of [first,second]){
   assert.equal(chart.status,'ok');assert.equal(chart.basis,'underlying_share_reference');assert.equal(chart.currency,'USD');
   assert.deepEqual(chart.points.map(p=>p.price),['498','499','500']);
  }
  assert.equal(searches,1);assert.equal(backpackCharts,0);
  assert.equal(service.snapshot().stocks.find(s=>s.id===id)!.quote.price,'492','share fallback must not rewrite the real token quote');
 }finally{service.stop();}
});

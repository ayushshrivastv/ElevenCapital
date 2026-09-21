import test from 'node:test';
import assert from 'node:assert/strict';
import { LiveMarketService, rotatingAnalyticsTargets } from '../src/live-service.js';
import type { Stock } from '../src/schema.js';
import type { GetJson } from '../src/http.js';
const START = Date.parse('2026-09-19T08:00:00Z');
type Controls = { refreshMetadata(id:'backed'|'backpack'|'prestocks'):Promise<void>; refreshQuotes():Promise<void>; retryFailedMetadata():Promise<void>; store(stock:Stock):void; acceptAnalytics(stocks:Stock[]):void };
function fixture() {
  const state = { now:START, cusip:'78462F103', price:'600', failMetadata:false, calls:{backed:0,securities:0} };
  const http:GetJson = async(provider,path)=>{
    if(provider==='nasdaq')throw Error('Use dated classification');
    if(provider==='jupiter')return[];
    if(provider==='backed') {state.calls.backed++;return {nodes:[{id:'00000000-0000-0000-0000-000000000001',name:'S&P500 xStock',symbol:'SPYx',isin:'CH1234567890',
      underlying:{symbol:'SPY',isin:'US78462F1030',currency:'USD',listingCountry:'US',type:null},deployments:[]}],page:{currentPage:0,hasNextPage:false}};}
    if(path.endsWith('/securities')){state.calls.securities++;if(state.failMetadata)throw Error('Offline');return[{asset:'SPY.US',cusip:state.cusip,name:'SPDR S&P500 ETF'}];}
    if(path.endsWith('/assets'))return[];
    if(path.endsWith('/tickers'))return[{symbol:'SPY.US_USDC',lastPrice:state.price,priceChange:'1',priceChangePercent:'0.001',quoteVolume:'123'}];
    throw Error('Unexpected endpoint');
  };
  const service=new LiveMarketService(http,()=>state.now);
  const controls=service as unknown as Controls;
  const ready=async()=>{await service.catalog();await Promise.all([controls.refreshMetadata('backed'),controls.refreshMetadata('backpack')]);};
  return {state,service,controls,ready};
}
test('cached quotes become stale without erasing rows, and Backed status accurately labels share references',async()=>{
  const f=fixture();try{await f.ready();
    const before=f.service.snapshot();assert.equal(before.stocks.length,2);
    assert.match(before.providers.find(p=>p.id==='backed')!.message!,/1 labelled share references/);
    assert.ok(before.providers.filter(p=>p.id!=='prestocks').every(p=>p.status==='ok'));
    f.state.now+=300001;const stale=f.service.snapshot();
    assert.equal(stale.stocks.length,2);assert.ok(stale.providers.filter(p=>p.id!=='prestocks').every(p=>p.status==='stale'));
    assert.ok(stale.providers.filter(p=>p.id!=='prestocks').every(p=>/cached quotes are stale/.test(p.message!)));
    assert.equal(stale.stocks[0]!.quote.price,before.stocks[0]!.quote.price);
  }finally{f.service.stop();}
});
test('changed CUSIP discards old quote identity and removes a conflicting share reference',async()=>{
  const f=fixture();try{await f.ready();f.state.now+=300001;f.state.cusip='037833100';f.state.price='700';
    await f.controls.refreshMetadata('backpack');
    const rows=f.service.snapshot().stocks;const ref=rows.find(s=>s.provider==='backpack')!;
    assert.equal(ref.underlying.cusip,'037833100');assert.equal(ref.quote.price,'700');
    assert.equal(rows.find(s=>s.provider==='backed')!.quote.basis,'provider_indicative_token');
    assert.equal(rows.find(s=>s.provider==='backed')!.quote.price,null);
  }finally{f.service.stop();}
});
test('metadata failures retain visible rows, surface staleness and retry only failed providers',async()=>{
  const f=fixture();try{await f.ready();f.state.now+=300001;
    await f.controls.refreshQuotes();f.state.failMetadata=true;await f.controls.refreshMetadata('backpack');
    assert.equal(f.service.snapshot().stocks.length,2);
    assert.equal(f.service.snapshot().providers.find(p=>p.id==='backpack')!.status,'stale');
    assert.match(f.service.snapshot().providers.find(p=>p.id==='backpack')!.message!,/Catalog refresh delayed/);
    const backedCalls=f.state.calls.backed;const failedCalls=f.state.calls.securities;
    f.state.failMetadata=false;f.state.now+=60000;await f.controls.retryFailedMetadata();
    assert.equal(f.state.calls.backed,backedCalls);assert.equal(f.state.calls.securities,failedCalls+1);
    assert.equal(f.service.snapshot().providers.find(p=>p.id==='backpack')!.status,'ok');
  }finally{f.service.stop();}
});
test('in-flight analytics from an old underlying currency cannot overwrite refreshed identity',async()=>{
  const f=fixture();try{await f.ready();
    const before=f.service.snapshot().stocks.find(s=>s.provider==='backed')!;
    const canonical={...before,providerLabel:'Backed xStocks',quote:{...before.quote,price:null,basis:'provider_indicative_token' as const,currency:'USD' as const},underlying:{...before.underlying,currency:'EUR'}};
    f.controls.store(canonical);
    const late={...canonical,underlying:{...canonical.underlying,currency:'USD'},quote:{...canonical.quote,price:'999',receivedAt:new Date(f.state.now).toISOString()},
      statistics:{...canonical.statistics,marketCapitalization:{...canonical.statistics.marketCapitalization,receivedAt:new Date(f.state.now).toISOString()}}};
    f.controls.acceptAnalytics([late]);
    const after=f.service.snapshot().stocks.find(s=>s.provider==='backed')!;
    assert.equal(after.underlying.currency,'EUR');assert.equal(after.quote.price,null);
  }finally{f.service.stop();}
});

test('background analytics rotates bounded exact-mint rows and leaves UI interests to the priority worker',async()=>{
  const f=fixture();try{await f.ready();
    const base=f.service.snapshot().stocks.find(s=>s.provider==='backed')!;
    const alphabet='123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
    const rows=Array.from({length:25},(_,index)=>({...base,id:`backed:${String(index).padStart(2,'0')}`,
      deployments:[{network:'Solana',address:'1'.repeat(31)+alphabet[index]!,decimals:8,depositEnabled:false,withdrawEnabled:false}]}));
    const interests=new Set([rows[3]!.id]);
    const first=rotatingAnalyticsTargets(rows,interests,0);
    const second=rotatingAnalyticsTargets(rows,interests,first.nextCursor);
    assert.equal(first.targets.length,20);assert.equal(second.targets.length,20);
    assert.ok(![...first.targets,...second.targets].some(row=>interests.has(row.id)));
    assert.equal(new Set([...first.targets,...second.targets].map(row=>row.id)).size,24,'rotation eventually covers every background row');
    assert.deepEqual(rotatingAnalyticsTargets([{...base,id:'no-mint',deployments:[]}],new Set(),0),{targets:[],nextCursor:0});
  }finally{f.service.stop();}
});

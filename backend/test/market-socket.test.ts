import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { WebSocket, WebSocketServer } from 'ws';
import { createServer } from 'node:http';
import { MarketSocket } from '../src/market-socket.js';
import { RealtimeCatalog } from '../src/realtime.js';
import { buildApp } from '../src/app.js';
import { applyExternalTicker, BackpackLive } from '../src/backpack-live.js';
import { initialStatistics } from '../src/statistics.js';
import { backpackActivity } from '../src/activity.js';
import { DisabledTrading, StockSchema, type Catalog, type Stock } from '../src/schema.js';
import type { CatalogMutation } from '../src/market-update.js';
const now = Date.parse('2026-09-19T12:00:00Z'), iso = new Date(now).toISOString();
function stock(id = 'MSFT.US'): Stock {
 return {id:`backpack:${id}`,provider:'backpack',providerAssetId:id,providerLabel:'Backpack Securities',symbol:id,name:id,logoUrl:null,
 underlying:{symbol:id.slice(0,-3),isin:null,cusip:null},quote:{price:'100.00000000000000001',currency:'USDC',currencyBasis:'market_symbol',changeAmount:'1',changePercent:'1',asOf:null,receivedAt:iso,basis:'external_reference_non_executable'},
 volume24h:'200',volume24hBasis:'quote_currency_turnover',activity:backpackActivity('200',iso),statistics:initialStatistics([]),deployments:[],trading:DisabledTrading};
}
const delay=(ms:number)=>new Promise(r=>setTimeout(r,ms));
async function until(check:()=>boolean){const end=Date.now()+3000;while(!check()){if(Date.now()>end)throw new Error('Timed out');await delay(5);}}
class MemorySocket extends EventEmitter {
 readonly readyState=WebSocket.OPEN;readonly bufferedAmount=0;readonly frames:any[]=[];
 send(value:unknown){this.frames.push(JSON.parse(String(value)));}
 ping(){}close(){}terminate(){}
}
test('external ticker preserves decimal precision and turnover; rejects old, foreign and future events',()=>{
 const original=stock(),tick={e:'externalTicker',s:'MSFT.US_USDC',o:'100',c:'100.00000000000000001',V:'123456789.00000000000000001',E:String(BigInt(now)*1000n)};
 const result=applyExternalTicker(original,tick,now)!;
 assert.equal(result.quote.price,tick.c);assert.equal(result.activity.volume24h,tick.V);assert.equal(result.volume24h,tick.V);
 assert.equal(result.quote.changeAmount,'0.00000000000000001');assert.equal(result.quote.changePercent,'0.00000000000000001');
 assert.equal(applyExternalTicker(result,tick,now),null);
 assert.equal(applyExternalTicker(original,{...tick,s:'AAPL.US_USDC'},now),null);
 assert.equal(applyExternalTicker(original,{...tick,E:String(BigInt(now+10_000)*1000n)},now),null);
 assert.equal(applyExternalTicker(original,{...tick,V:'-2'},now),null);
 const recurring=applyExternalTicker(original,{...tick,o:'491.937',c:'493.952'},now)!;
 assert.equal(StockSchema.safeParse(recurring).success,true);
 assert.ok(recurring.quote.changePercent!.length<100);
});
test('socket bootstrap, all-row ordered deltas, authoritative delisting and fresh reconnect snapshot',async()=>{
 let value:Catalog={schemaVersion:1,mode:'live-read-only',receivedAt:iso,providers:[{id:'backed',status:'unavailable'},{id:'backpack',status:'ok'}],stocks:[stock(),stock('AAPL.US')]};
 let publish:(v:Catalog,m?:CatalogMutation)=>void=()=>{};let selected:string[]=[];
 const service={catalog:async()=>value,onUpdate:(fn:typeof publish)=>{publish=fn;return()=>{};},setInterest:(ids:Iterable<string>)=>{selected=[...ids]},
 chart:async(id:string,range:any)=>({stockId:id,range,status:'unsupported' as const,currency:'USDC' as const,basis:'external_reference_non_executable',receivedAt:iso,points:[]})};
 const app=await buildApp(service,false,{now:()=>now});await app.listen({host:'127.0.0.1',port:0});
 const address=app.server.address() as {port:number},frames:any[]=[];
 const ws=new WebSocket(`ws://127.0.0.1:${address.port}/v1/market/ws`);ws.on('message',d=>frames.push(JSON.parse(d.toString())));
 try{
 await until(()=>frames.some(f=>f.type==='snapshot'));assert.equal(frames.find(f=>f.type==='snapshot').catalog.stocks.length,2);
 ws.send(JSON.stringify({type:'subscribe',ids:['backpack:MSFT.US']}));await until(()=>selected.length===1);
 value={...value,stocks:value.stocks.map(s=>({...s,quote:{...s.quote,price:'101'}}))};
 publish(value,{changedIds:value.stocks.map(s=>s.id),removedIds:[],membershipChanged:false,requestedAt:performance.now()});
 await until(()=>frames.some(f=>f.type==='delta'&&f.stocks.some((s:Stock)=>s.quote.price==='101')));
 assert.deepEqual(frames.filter(f=>f.type==='delta').at(-1).stocks.map((s:Stock)=>s.id),
  ['backpack:MSFT.US','backpack:AAPL.US']);
 const revisions=frames.filter(f=>['delta','snapshot'].includes(f.type)).map(f=>f.revision);
 assert.ok(revisions.every((r,i)=>i===0||r>revisions[i-1]));
 value={...value,stocks:[value.stocks[1]!]};
 publish(value,{changedIds:[],removedIds:['backpack:MSFT.US'],membershipChanged:true,requestedAt:performance.now()});
 await until(()=>frames.filter(f=>f.type==='snapshot').length===2);
 assert.deepEqual(frames.filter(f=>f.type==='snapshot').at(-1).catalog.stocks.map((s:Stock)=>s.id),['backpack:AAPL.US']);
 ws.close();await until(()=>selected.length===0);
 const again:any[]=[],ws2=new WebSocket(`ws://127.0.0.1:${address.port}/v1/market/ws`);ws2.on('message',d=>again.push(JSON.parse(d.toString())));
 await until(()=>again.some(f=>f.type==='snapshot'));assert.equal(again.find(f=>f.type==='snapshot').catalog.stocks.length,1);ws2.close();
 }finally{ws.terminate();await app.close();}
});
test('oversized subscriptions close only the offending connection',async()=>{
 const value:Catalog={schemaVersion:1,mode:'live-read-only',receivedAt:iso,providers:[],stocks:[]};
 const app=await buildApp({catalog:async()=>value,chart:async()=>{throw Error('unused')}},false,{now:()=>now});await app.listen({host:'127.0.0.1',port:0});
 const address=app.server.address() as {port:number};const ws=new WebSocket(`ws://127.0.0.1:${address.port}/v1/market/ws`);
 try{await new Promise<void>(r=>ws.once('open',()=>r()));const closed=new Promise<number>(r=>ws.once('close',r));
 ws.send(JSON.stringify({type:'subscribe',ids:Array(101).fill('backpack:MSFT.US')}));assert.equal(await closed,1008);assert.equal((await app.inject('/health')).statusCode,200);
 }finally{ws.terminate();await app.close();}
});
test('offscreen observations are delivered immediately without subscribing them upstream',async()=>{
 const initial:Catalog={schemaVersion:1,mode:'live-read-only',receivedAt:iso,providers:[],stocks:[stock(),stock('AAPL.US')]};
 const http=createServer();await new Promise<void>(r=>http.listen(0,'127.0.0.1',r));
 const feed=new RealtimeCatalog(async()=>initial,()=>now);let interests:string[]=[];
 let monotonic=10;
 const transport=new MarketSocket(http,feed,{chart:async()=>{throw Error('unused')},setInterest:ids=>{interests=[...ids]}},()=>now,()=>monotonic);
 const ws=new WebSocket(`ws://127.0.0.1:${(http.address() as {port:number}).port}/v1/market/ws`),frames:any[]=[];
 ws.on('message',raw=>frames.push(JSON.parse(raw.toString())));
 try{
  await until(()=>frames.some(f=>f.type==='snapshot'));
  ws.send(JSON.stringify({type:'subscribe',ids:['backpack:MSFT.US']}));await until(()=>interests.length===1);
  const before=frames.length;
  monotonic=15;
  feed.publish({...initial,stocks:[initial.stocks[0]!,{...initial.stocks[1]!,quote:{...initial.stocks[1]!.quote,price:'222'}}]},
    {changedIds:['backpack:AAPL.US'],removedIds:[],membershipChanged:false,requestedAt:10});
  await until(()=>frames.some(f=>f.type==='delta'&&f.stocks.some((s:Stock)=>s.id==='backpack:AAPL.US'&&s.quote.price==='222')));
  const delivered=frames.slice(before).filter(f=>f.type==='delta');
  assert.equal(delivered.length,1,'the changed offscreen row must be in the first publication, not a later catch-up');
  assert.deepEqual(delivered[0].stocks.map((s:Stock)=>s.id),['backpack:AAPL.US']);
  assert.deepEqual(interests,['backpack:MSFT.US'],'delivery must not create high-frequency offscreen source subscriptions');
  assert.equal(transport.metrics.publicationToWsEnqueueLastMs,5);
  assert.equal(transport.metrics.publicationToWsEnqueueMaxMs,5);
  assert.equal(transport.metrics.publicationToWsEnqueueP95Ms,5);
  assert.equal(transport.metrics.publicationToWsEnqueueP99Ms,5);
  assert.equal(transport.metrics.publicationToWsEnqueueSamples,1);
  const revisions=frames.filter(f=>['snapshot','delta'].includes(f.type)).map(f=>f.revision);
  assert.ok(revisions.every((r,i)=>i===0||r>revisions[i-1]));
  ws.close();await until(()=>interests.length===0);
 }finally{ws.terminate();transport.close();feed.stop();await new Promise<void>(r=>http.close(()=>r()));}
});
test('in-memory fanout puts every changed row in the measured frame and measures membership snapshots',()=>{
 const initial:Catalog={schemaVersion:1,mode:'live-read-only',receivedAt:iso,providers:[],stocks:[stock(),stock('AAPL.US')]};
 const feed=new RealtimeCatalog(async()=>initial,()=>now);let monotonic=10;
 feed.publish(initial);
 const http=createServer();
 const transport=new MarketSocket(http,feed,{chart:async()=>{throw Error('unused')}},()=>now,()=>monotonic);
 const socket=new MemorySocket();
 (transport as unknown as {attach(socket:WebSocket):void}).attach(socket as unknown as WebSocket);
 try{
  assert.equal(socket.frames.filter(frame=>frame.type==='snapshot').length,1);
  monotonic=15;
  const changed={...initial.stocks[1]!,quote:{...initial.stocks[1]!.quote,price:'222'}};
  feed.publish({...initial,stocks:[initial.stocks[0]!,changed]},
    {changedIds:[changed.id],removedIds:[],membershipChanged:false,requestedAt:10});
  const delta=socket.frames.at(-1);
  assert.equal(delta.type,'delta');
  assert.deepEqual(delta.stocks.map((row:Stock)=>row.id),['backpack:AAPL.US']);
  assert.equal(transport.metrics.publicationToWsEnqueueLastMs,5);
  assert.equal(transport.metrics.publicationToWsEnqueueSamples,1);

  monotonic=20;
  feed.publish({...initial,stocks:[initial.stocks[0]!]},
    {changedIds:[],removedIds:['backpack:AAPL.US'],membershipChanged:true,requestedAt:15});
  const membership=socket.frames.at(-1);
  assert.equal(membership.type,'snapshot');
  assert.deepEqual(membership.catalog.stocks.map((row:Stock)=>row.id),['backpack:MSFT.US']);
  assert.equal(transport.metrics.publicationToWsEnqueueLastMs,5);
  assert.equal(transport.metrics.publicationToWsEnqueueSamples,2);
 }finally{transport.close();feed.stop();}
});
test('unchanged periodic refresh sends an empty delta instead of repeating the full catalog snapshot',async()=>{
 const value:Catalog={schemaVersion:1,mode:'live-read-only',receivedAt:iso,providers:[{id:'backpack',status:'ok'}],stocks:[stock(),stock('AAPL.US')]};
 let loads=0;
 const app=await buildApp({catalog:async()=>{loads++;return value;},chart:async()=>{throw Error('unused')}},false,
  {now:()=>now,refreshMs:20,retryMs:10});
 await app.listen({host:'127.0.0.1',port:0});
 const address=app.server.address() as {port:number},frames:any[]=[];
 const ws=new WebSocket(`ws://127.0.0.1:${address.port}/v1/market/ws`);ws.on('message',raw=>frames.push(JSON.parse(raw.toString())));
 try{
  await until(()=>frames.some(frame=>frame.type==='snapshot'));
  await until(()=>loads>=2&&frames.some(frame=>frame.type==='delta'));
  assert.equal(frames.filter(frame=>frame.type==='snapshot').length,1);
  assert.deepEqual(frames.find(frame=>frame.type==='delta').stocks,[]);
  assert.deepEqual(frames.find(frame=>frame.type==='delta').removedIds,[]);
 }finally{ws.terminate();await app.close();}
});
test('single upstream restores its subscriptions after a dropped socket',async()=>{
 const server=new WebSocketServer({port:0,host:'127.0.0.1'});await new Promise<void>(r=>server.once('listening',()=>r()));
 const messages:any[]=[];let connections=0;
 server.on('connection',socket=>{connections++;socket.on('message',raw=>{messages.push(JSON.parse(raw.toString()));if(connections===1)socket.close();});});
 const address=server.address() as {port:number},upstream=new BackpackLive(()=>{},()=>now,`ws://127.0.0.1:${address.port}`);
 try{upstream.setSymbols(['MSFT.US_USDC']);await until(()=>connections>=2&&messages.length>=2);
 assert.equal(messages[0].method,'SUBSCRIBE');assert.deepEqual(messages[1].params,['externalTicker.MSFT.US_USDC']);
 upstream.setSymbols(['AAPL.US_USDC']);await until(()=>messages.some(m=>m.method==='UNSUBSCRIBE'));
 assert.ok(messages.some(m=>m.method==='SUBSCRIBE'&&m.params[0]==='externalTicker.AAPL.US_USDC'));
 }finally{upstream.stop();for(const c of server.clients)c.terminate();await new Promise<void>(r=>server.close(()=>r()));}
});

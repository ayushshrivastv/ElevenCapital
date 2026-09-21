import test from 'node:test';import assert from 'node:assert/strict';
import{reconcileCatalog}from'../src/reconcile.js';import type{Catalog}from'../src/schema.js';
const catalog={stocks:[{id:'backed:a',provider:'backed'},{id:'backpack:B.US',provider:'backpack'},{id:'prestocks:mint',provider:'prestocks'}]} as Catalog;
const diagnostics={sources:[{provider:'backed',sourceCount:3,includedIds:['backed:a'],excluded:[{id:'backed:gold',reason:'Commodity'}],unresolved:[{id:'backed:unknown',reason:'Unknown classification'}],appCount:1},{provider:'backpack',sourceCount:1,includedIds:['backpack:B.US'],excluded:[],unresolved:[],appCount:1},{provider:'prestocks',sourceCount:1,includedIds:['prestocks:mint'],excluded:[],unresolved:[],appCount:1}]};
test('every discovery row must reconcile to displayed, excluded or explicitly unresolved; snapshot sizes are not hardcoded',()=>{const result=reconcileCatalog(catalog,diagnostics);assert.equal(result.ok,true);assert.equal(result.classificationComplete,false);});
test('missing and duplicate displayed IDs, undisclosed omissions and unexpected rows fail reconciliation',()=>{
 assert.equal(reconcileCatalog({...catalog,stocks:catalog.stocks.slice(0,1)},diagnostics).ok,false);
 assert.equal(reconcileCatalog({...catalog,stocks:[...catalog.stocks,catalog.stocks[0]!]},diagnostics).ok,false);
 assert.equal(reconcileCatalog(catalog,{sources:diagnostics.sources.map(s=>({...s,sourceCount:s.sourceCount+1}))}).ok,false);
 assert.equal(reconcileCatalog({...catalog,stocks:[...catalog.stocks,{...catalog.stocks[0]!,id:'backed:unexpected'}]},diagnostics).ok,false);
});

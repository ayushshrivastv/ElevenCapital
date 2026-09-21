import { pathToFileURL } from 'node:url';
import { writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { z } from 'zod';
import { CatalogSchema, type Catalog } from './schema.js';
const SourceReport = z.object({ provider:z.enum(['backed','backpack','prestocks']), sourceCount:z.number().int().nonnegative(), includedIds:z.array(z.string()),
  excluded:z.array(z.object({id:z.string(),reason:z.string()})), unresolved:z.array(z.object({id:z.string(),reason:z.string()})), appCount:z.number().int().nonnegative(), classificationAsOf:z.string().optional() });
export function reconcileCatalog(catalog: Catalog, diagnostics: unknown) {
  const sources=z.object({sources:z.array(SourceReport)}).parse(diagnostics).sources;
  const ids=catalog.stocks.map(stock=>stock.id);
  const duplicateAppIds=ids.filter((id,index)=>ids.indexOf(id)!==index);
  const reports=sources.map(source=>{
    const displayed=catalog.stocks.filter(stock=>stock.provider===source.provider).map(stock=>stock.id);
    const expected=new Set(source.includedIds);const actual=new Set(displayed);
    const missing=source.includedIds.filter(id=>!actual.has(id)); const unexpected=displayed.filter(id=>!expected.has(id));
    const duplicates=source.includedIds.filter((id,index)=>source.includedIds.indexOf(id)!==index);
    const categorized=source.includedIds.length+source.excluded.length+source.unresolved.length;
    return {...source,displayedIds:displayed,missing,unexpected,duplicates,categorized,
      ok:missing.length===0&&unexpected.length===0&&duplicates.length===0&&categorized===source.sourceCount&&displayed.length===source.appCount};
  });
  return {checkedAt:new Date().toISOString(),ok:reports.length===3&&new Set(reports.map(r=>r.provider)).size===3&&reports.every(r=>r.ok)&&duplicateAppIds.length===0,
    classificationComplete:reports.length===3&&reports.every(r=>r.unresolved.length===0),appCount:catalog.stocks.length,duplicateAppIds,sources:reports};
}
async function json(path:string) {
  const response=await fetch('http://127.0.0.1:8787'+path,{signal:AbortSignal.timeout(15000)});
  if(!response.ok)throw new Error(`Local market endpoint returned HTTP ${response.status}.`);
  return response.json();
}
async function main(){
  const args=process.argv.slice(2);
  if(args.length!==0&&(args.length!==2||args[0]!=='--output'))throw new Error('Usage: node dist/reconcile.js [--output report.json]');
  let report:ReturnType<typeof reconcileCatalog>|undefined;
  for(let attempt=0;attempt<30;attempt++){
    const [rawCatalog,rawDiagnostics]=await Promise.all([json('/v1/stocks'),json('/v1/market/diagnostics')]);
    try{report=reconcileCatalog(CatalogSchema.parse(rawCatalog),rawDiagnostics); if(report.ok)break;}catch(error){if(attempt===29)throw error;}
    await new Promise(resolve=>setTimeout(resolve,1000));
  }
  if(!report)throw new Error('Provider discovery did not finish within the verification window.');
  const output=resolve(args[1]??'verification/market-reconciliation.json');await mkdir(dirname(output),{recursive:true});await writeFile(output,JSON.stringify(report,null,2)+'\n');
  console.log(JSON.stringify({ok:report.ok,classificationComplete:report.classificationComplete,appCount:report.appCount,sources:report.sources.map(s=>({provider:s.provider,sourceCount:s.sourceCount,included:s.includedIds.length,excluded:s.excluded.length,unresolved:s.unresolved.length,missing:s.missing.length,unexpected:s.unexpected.length})),report:output},null,2));
  process.exitCode=report.ok?0:1;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href)main().catch(error=>{console.error(error instanceof Error?error.message:'Market reconciliation failed.');process.exitCode=1;});

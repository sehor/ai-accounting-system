import { FileBlob, SpreadsheetFile } from '@oai/artifact-tool';
import fs from 'node:fs/promises';
const w = await SpreadsheetFile.importXlsx(await FileBlob.load('F:/accounting/创和科技/2026-08/收到发票.xlsx'));
console.log((await w.inspect({kind:'sheet',include:'id,name'})).ndjson);
const s=w.worksheets.getItemAt(0);
const rows=s.getUsedRange().values;
await fs.writeFile('artifacts/invoice-review/source.json',JSON.stringify(rows));
const groups={};
for(let i=1;i<rows.length;i++){const r=rows[i];const k=r[4]?.split('*')[1]||r[4];const g=groups[k]??={n:0,total:0,sellers:new Set()};g.n++;g.total+=Number(r[12]);g.sellers.add(r[0]);}
console.log(JSON.stringify(Object.entries(groups).map(([k,g])=>({category:k,n:g.n,total:+g.total.toFixed(2),sellers:[...g.sellers]}))));
console.log('RAIL',JSON.stringify(w.worksheets.getItemAt(1).getUsedRange().values));

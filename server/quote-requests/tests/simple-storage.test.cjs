const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const create=()=>fixture({configure:false,monthly:true,now:Date.parse('2026-10-09T00:00:00Z')});
test('verified cutover preserves old NFQ receipts, UUIDs, raw data and sync cursors',()=>{
 const f=create();const first=f.submit();assert.equal(first.ok,true);
 const old=f.requestRows()[0].row,index=f.book.get('전체견적색인').cells[1];
 old[16]=old[16].replace('NAMU-','NFQ-');index[5]=old[16];
 const before=JSON.stringify(old);const cursor=old[1];
 const cutover=f.context.simplifyQuoteStorage();assert.equal(cutover.requests,1);
 assert.equal(f.properties.QUOTE_STORAGE_READY,'0');assert.equal(JSON.stringify(f.cells[1]),before);
 assert.equal(f.submit().receiptNumber,old[16]);assert.equal(f.cells.length,2);
 assert.equal(f.context.simplifyQuoteStorage().requests,1);
 const next=f.submit({...f.request,requestId:crypto.randomUUID()});assert.equal(next.ok,true);
 assert.equal(next.receiptNumber,'NAMU-20261009-0002');
 const page=f.readRequests(cursor);assert.equal(page.requests.length,1);assert.equal(page.requests[0].receiptNumber,next.receiptNumber);
});
for(const after of [false,true])test(`failed cutover keeps monthly reads active and retries without duplication (${after?'lost ack':'no write'})`,()=>{
 const f=create();f.submit();f.failNextWrite(op=>op.sheetName==='웹견적요청'&&op.row>1,{after});
 assert.throws(()=>f.context.simplifyQuoteStorage());assert.equal(f.properties.QUOTE_STORAGE_READY,'1');
 assert.equal(f.readRequests(0).requests.length,1);
 assert.equal(f.context.simplifyQuoteStorage().requests,1);assert.equal(f.cells.length,2);
});
test('simple intake opens one workbook and verifies one row write; retries write nothing',()=>{
 const f=create();f.submit();f.context.simplifyQuoteStorage();
 let opens=0,flushes=0;const original=f.context.SpreadsheetApp.openById;
 f.context.SpreadsheetApp.openById=id=>{opens++;return original(id)};
 f.context.SpreadsheetApp.flush=()=>flushes++;
 const start=f.operations.length,request={...f.request,requestId:crypto.randomUUID()};
 assert.equal(f.submit(request).ok,true);assert.equal(opens,1);assert.equal(flushes,1);
 assert.equal(f.operations.length-start,1);assert.equal(f.submit(request).duplicate,true);
 assert.equal(f.operations.length-start,1);
});
test('expiry clears both active records and pre-cutover customer copies',()=>{
 const f=create();f.submit();const archive=f.requestRows()[0].row;f.context.simplifyQuoteStorage();
 f.context.closeRequest_({requestId:f.request.requestId});f.context.purgeExpiredCustomerData();
 assert.equal(f.cells[1][15],'{"expired":true}');
 const source=f.requestRows().find(r=>r.book.getId()!=='test').row;
 assert.equal(source[15],'{"expired":true}');assert.equal(source.slice(3,9).join(''),'');
 assert.equal(f.properties.QUOTE_ARCHIVE_PENDING,undefined);
});

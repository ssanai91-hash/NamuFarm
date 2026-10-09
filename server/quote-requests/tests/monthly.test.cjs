const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const now=Date.parse('2026-10-06T15:00:00.000Z');
const create=options=>fixture({now,monthly:true,...options});
const plain=value=>JSON.parse(JSON.stringify(value));
const index=f=>f.book.get('전체견적색인').cells.slice(1).filter(r=>r?.[0]);
const months=f=>f.book.get('견적월별파일').cells.slice(1).filter(r=>r?.[0]);
const another=f=>({...f.request,requestId:crypto.randomUUID()});
const entry=(f,id=f.request.requestId)=>index(f).find(r=>r[0]===id);
const row=(f,id=f.request.requestId)=>f.requestRows().find(r=>r.row[0]===id).row;
const dayWrite=op=>/^\d{4}-\d{2}-\d{2}$/.test(op.sheetName)&&op.row>1;
const phaseWrite=phase=>op=>op.sheetName==='전체견적색인'&&op.row>1&&op.values[0][9]===phase;
const successful=f=>{const response=f.submit();assert.equal(response.ok,true,JSON.stringify(response));return response};

test('plain-text persisted copy flags and existing boolean flags keep receipts readable; malformed flags fail closed',()=>{
 const f=create();const first=successful(f);assert.equal(entry(f)[10],'false');
 assert.equal(f.readRequests(0).requests.length,1);assert.equal(successful(f).receiptNumber,first.receiptNumber);
 entry(f)[10]=false;assert.equal(f.context.auditMonthlyQuoteStorage().committed,1);
 entry(f)[10]='unexpected';assert.throws(()=>f.readRequests(0));
});

test('creates one registered monthly file and only days with receipts; registry survives file rename',()=>{
 const f=create();assert.equal(f.files.size,0);successful(f);successful(f);
 f.setTime('2026-10-08T03:00:00Z');assert.equal(f.submit(another(f)).ok,true);
 assert.equal(f.files.size,1);const m=months(f)[0],book=f.books.get(m[1]);
 assert.equal(book.getName(),'나무팜_견적요청_2026-10');assert.equal(book.timeZone,'Asia/Seoul');
 assert.deepEqual([...book.sheets.keys()].sort(),['2026-10-07','2026-10-08','월간현황']);
 f.files.get(m[1]).name='operator renamed file';f.context.maintainMonthlyQuoteStorage();assert.equal(f.files.size,1);
 const summary=book.getSheetByName('월간현황').cells;
 assert.deepEqual(plain(summary.slice(1).map(r=>r.slice(0,4))),[['2026-10-07',1,1,0],['2026-10-08',1,1,0]]);
 assert.equal(f.cells.length,1);assert.equal(f.context.auditMonthlyQuoteStorage().committed,2);
});

test('Seoul midnight, month end and year end route by server time with global sequences',()=>{
 const f=create({now:Date.parse('2026-10-31T14:59:59Z')});const first=successful(f);
 assert.equal(first.receiptNumber,'NAMU-20261031-0001');f.setTime('2026-10-31T15:00:00Z');
 const second=f.submit(another(f));assert.equal(second.receiptNumber,'NAMU-20261101-0001');
 f.setTime('2026-12-31T15:00:00Z');assert.equal(f.submit(another(f)).receiptNumber,'NAMU-20270101-0001');
 assert.deepEqual(index(f).map(r=>r[3]),['2026-10-31','2026-11-01','2027-01-01']);
 assert.deepEqual(index(f).map(r=>r[8]),[1,2,3]);assert.equal(months(f).length,3);
 assert.equal(successful(f).receiptNumber,first.receiptNumber);assert.equal(index(f).length,3);
});

test('same UUID across months retains receipt, original time, hash and ciphertext; changed content conflicts',()=>{
 const f=create(),first=successful(f),stored=plain(row(f));f.setTime('2026-11-07T00:00:00Z');
 assert.deepEqual(successful(f),{...first,duplicate:true});assert.deepEqual(plain(row(f)),stored);
 assert.equal(f.submit({...f.request,message:'다른 요청'}).code,'CONFLICT');assert.equal(f.files.size,1);
});

test('formal quote fields and encryption survive monthly storage without PII or ciphertext in index',()=>{
 const f=create();const request={...f.request,consent:undefined,processingBasis:'quote_request',formalQuoteRequested:true,recipientName:'나무 학교',replyChannel:'both',customerEmail:'buyer@example.com'};delete request.consent;
 assert.equal(f.submit(request).ok,true);const saved=f.saved();
 for(const key of ['processingBasis','formalQuoteRequested','recipientName','replyChannel','customerEmail'])assert.equal(saved[key],request[key]);
 assert.equal(row(f)[3],f.request.customerName);
 const text=JSON.stringify([...f.books.values()].map(b=>[...b.sheets.values()].map(s=>s.cells)));
 for(const value of ['김나무','01012345678','봄에 심어요','나무 학교','buyer@example.com'])assert.ok(text.includes(value));
 assert.ok(!JSON.stringify(index(f)).includes('김나무'));
 assert.ok(!JSON.stringify(index(f)).includes('encryptedRequest'));assert.ok(!JSON.stringify(index(f)).includes('ciphertext'));
 assert.equal(f.readRequests(0).requests[0].replyChannel,'both');
 assert.equal(f.context.doGet().version,5);assert.equal(f.context.doGet().encryptionReady,false);
});

test('interleaved submissions cannot enter the lock or allocate duplicate files, tabs, UUIDs or receipts',()=>{
 const f=create(),requests=Array.from({length:3},()=>another(f)),blocked=[];
 f.onNextWrite(()=>{for(const r of [f.request,...requests])blocked.push(f.submit(r))});
 successful(f);assert.ok(blocked.every(r=>r.code==='BUSY'));
 assert.equal(successful(f).duplicate,true);for(const request of requests)assert.equal(f.submit(request).ok,true);
 assert.equal(f.files.size,1);assert.equal(f.books.get(months(f)[0][1]).sheets.size,2);
 for(const c of [0,5,8])assert.equal(new Set(index(f).map(r=>r[c])).size,4);
});

for(const after of [false,true])test(`month registry reservation write failure ${after?'after':'before'} apply recovers`,()=>{
 const f=create();f.failNextWrite(op=>op.sheetName==='견적월별파일'&&op.row>1&&op.values[0][3]==='RESERVED',{after});
 assert.equal(f.submit().ok,false);assert.equal(f.files.size,0);successful(f);assert.equal(f.files.size,1);
});

test('Drive create response loss reconnects by creation token without a second file',()=>{
 const f=create();f.failCreate({after:true});assert.equal(f.submit().ok,false);assert.equal(f.files.size,1);
 assert.equal(months(f)[0][3],'CREATING');assert.equal(months(f)[0][1],'');
 f.files.values().next().value.name='name is not an identity';successful(f);
 assert.equal(f.operations.filter(op=>op.kind==='create').length,1);assert.equal(months(f)[0][3],'READY');
});

test('unknown Drive create result with no discoverable file stays closed instead of blindly creating again',()=>{
 const f=create();f.failCreate({after:false});assert.equal(f.submit().ok,false);assert.equal(f.submit().code,'NOT_READY');
 assert.equal(f.files.size,0);assert.equal(f.operations.filter(op=>op.kind==='create').length,1);
 assert.equal(index(f).length,0);assert.throws(()=>f.context.maintainMonthlyQuoteStorage());
});

test('lost registry file-ID response and incomplete day headers recover without duplicates',()=>{
 for(const after of [false,true]){
  const f=create();f.failNextWrite(op=>op.sheetName==='견적월별파일'&&op.row>1&&!!op.values[0][1],{after});
  assert.equal(f.submit().ok,false);successful(f);assert.equal(f.files.size,1);
  const g=create();g.failNextWrite(op=>op.sheetName==='2026-10-07'&&op.row===1,{after});
  assert.equal(g.submit().ok,false);successful(g);assert.equal(g.files.size,1);assert.equal(g.requestRows().length,1);
 }
});

for(const [name,predicate]of [
 ['reservation',phaseWrite('PREPARED')],
 ['candidate row',op=>dayWrite(op)&&op.values[0][1]===0],
 ['commit intent',phaseWrite('COMMITTING')],
 ['published data sequence',op=>dayWrite(op)&&op.values[0][1]>0],
 ['committed index',phaseWrite('COMMITTED')]
])for(const after of [false,true])test(`${name} write failure ${after?'after':'before'} apply: no false success and idempotent recovery`,()=>{
 const f=create();f.failNextWrite(predicate,{after});assert.equal(f.submit().ok,false);
 const reservation=entry(f)&&plain(entry(f));f.setTime('2026-11-01T00:00:00Z');
 const retry=successful(f);assert.equal(index(f).length,1);assert.equal(f.requestRows().length,1);
 if(reservation){assert.equal(retry.receiptNumber,reservation[5]);assert.equal(retry.createdAt,reservation[4]);}
 assert.equal(entry(f)[9],'COMMITTED');assert.equal(entry(f)[8],row(f)[1]);assert.ok(entry(f)[8]>0);
 assert.equal(f.context.listRequests_({afterSequence:0}).requests.length,1);
});

test('empty reservations do not reuse occupied slots or publish below a cursor already returned',()=>{
 const f=create();f.failNextWrite(op=>dayWrite(op)&&op.values[0][1]===0);assert.equal(f.submit().ok,false);
 const first=plain(entry(f)),other=another(f);assert.equal(f.submit(other).ok,true);
 const page=f.readRequests(0);assert.equal(page.requests.length,1);assert.equal(page.requests[0].requestId,other.requestId);
 const cursor=page.requests[0].sequence;successful(f);const next=f.readRequests(cursor);
 assert.equal(next.requests.length,1);assert.equal(next.requests[0].requestId,f.request.requestId);assert.ok(next.requests[0].sequence>cursor);
 assert.equal(entry(f)[5],first[5]);assert.notEqual(entry(f)[13],entry(f,other.requestId)[13]);assert.equal(f.requestRows().length,2);
});

test('data written before final index publication is repaired before any successful sync page',()=>{
 const f=create();f.failNextWrite(phaseWrite('COMMITTING'));assert.equal(f.submit().ok,false);
 assert.equal(row(f)[1],0);f.failNextWrite(op=>dayWrite(op)&&op.values[0][1]>0);
 assert.throws(()=>f.readRequests(0));assert.equal(entry(f)[9],'COMMITTING');
 const page=f.readRequests(0);assert.equal(page.requests.length,1);assert.equal(entry(f)[9],'COMMITTED');
 assert.equal(successful(f).duplicate,true);
});

test('a disappeared COMMITTING data row blocks cursor advancement; it is not silently skipped',()=>{
 const f=create();f.failNextWrite(op=>dayWrite(op)&&op.values[0][1]>0);assert.equal(f.submit().ok,false);
 f.requestRows()[0].sheet.cells.splice(1,1);assert.throws(()=>f.readRequests(0));assert.equal(f.submit(another(f)).code,'NOT_READY');
});

test('multiple-month pages retain the 100-row contract, ascending global cursor and no omissions',()=>{
 const f=create(),ids=[];
 for(let i=0;i<105;i++){
  if(i===50)f.setTime('2026-10-31T15:00:00Z');f.clearRate();const request=another(f);ids.push(request.requestId);assert.equal(f.submit(request).ok,true);
 }
 f.reads.length=0;const first=f.context.listRequests_({afterSequence:0});
 assert.equal(f.reads.filter(r=>/^\d{4}-\d{2}-\d{2}$/.test(r.sheetName)&&r.row===2).length,2,'page reads each daily sheet once');
 const second=f.context.listRequests_({afterSequence:first.requests.at(-1).sequence});
 assert.equal(first.requests.length,100);assert.equal(first.hasMore,true);assert.equal(second.requests.length,5);assert.equal(second.hasMore,false);
 const all=[...first.requests,...second.requests];assert.deepEqual(all.map(r=>r.requestId),ids);assert.deepEqual(all.map(r=>r.sequence),Array.from({length:105},(_,i)=>i+1));
 assert.equal(f.context.listRequests_({afterSequence:105}).requests.length,0);assert.equal(months(f).length,2);
});

test('UUID lookup tolerates row sorting for duplicate detection, reading and disposal',()=>{
 const f=create();successful(f);const other=another(f);f.submit(other);const cells=f.requestRows()[0].sheet.cells;
 [cells[1],cells[2]]=[cells[2],cells[1]];assert.equal(successful(f).duplicate,true);
 f.context.closeRequest_({requestId:f.request.requestId});assert.equal(JSON.parse(row(f)[15]).expired,true);
 assert.ok(JSON.parse(row(f,other.requestId)[15]).request);assert.equal(f.context.auditMonthlyQuoteStorage().requests,2);
});

test('previous-month expiry publishes a new tombstone and retains customer-free daily totals',()=>{
 const f=create();const original=successful(f);f.setTime('2026-11-06T15:00:00Z');
 const other=another(f);assert.equal(f.submit(other).ok,true);const cursor=Math.max(...index(f).map(r=>r[8]));
 f.context.purgeExpiredCustomerData();const changes=f.readRequests(cursor);
 assert.equal(changes.requests.length,1);assert.equal(changes.requests[0].requestId,f.request.requestId);assert.equal(changes.requests[0].expired,true);
 assert.ok(changes.requests[0].sequence>cursor);assert.equal(changes.requests[0].receiptNumber,original.receiptNumber);
 assert.equal(row(f)[15],'{"expired":true}');assert.ok(JSON.parse(row(f,other.requestId)[15]).request);
 const tombstone=entry(f)[8];f.context.purgeExpiredCustomerData();assert.equal(entry(f)[8],tombstone);
 f.context.maintainMonthlyQuoteStorage();assert.deepEqual(plain(f.books.get(months(f)[0][1]).getSheetByName('월간현황').cells[1].slice(0,4)),['2026-10-07',1,0,1]);
});

for(const [name,predicate]of [
 ['disposal intent',phaseWrite('DISPOSING')],
 ['ciphertext erasure',op=>dayWrite(op)&&op.values[0][15]==='{"expired":true}'],
 ['disposal publication',phaseWrite('COMMITTED')]
])for(const after of [false,true])test(`${name} failure ${after?'after':'before'} apply recovers and publishes disposal once`,()=>{
 const f=create();successful(f);const cursor=entry(f)[8];f.failNextWrite(predicate,{after});
 assert.throws(()=>f.context.closeRequest_({requestId:f.request.requestId}));
 f.context.closeRequest_({requestId:f.request.requestId});const changes=f.readRequests(cursor);assert.equal(changes.requests.length,1);assert.equal(changes.requests[0].expired,true);
 const sequence=entry(f)[8];f.context.closeRequest_({requestId:f.request.requestId});assert.equal(entry(f)[8],sequence);assert.equal(row(f)[15],'{"expired":true}');
});

test('closing an empty reservation prevents replay from restoring customer details',()=>{
 const f=create();f.failNextWrite(dayWrite);assert.equal(f.submit().ok,false);const receipt=entry(f)[5];
 f.context.closeRequest_({requestId:f.request.requestId});assert.equal(successful(f).receiptNumber,receipt);
 assert.equal(row(f)[15],'{"expired":true}');assert.equal(f.readRequests(0).requests[0].expired,true);
});

test('a reservation retried after 30 days remains a tombstone without saving fresh ciphertext',()=>{
 const f=create();f.failNextWrite(dayWrite);assert.equal(f.submit().ok,false);f.setTime('2026-11-07T00:00:00Z');successful(f);
 assert.equal(row(f)[15],'{"expired":true}');assert.equal(f.readRequests(0).requests[0].expired,true);
});

test('setup indexes legacy in place without changing data; mixed reads and legacy-write rollback keep all months',()=>{
 const f=fixture({now});successful(f);const before=plain(f.cells);f.context.setupMonthlyQuoteStorage();
 assert.deepEqual(plain(f.cells),before);assert.equal(f.files.size,0);f.context.setupMonthlyQuoteStorage();assert.equal(index(f).length,1);
 f.context.enableMonthlyQuoteStorage();f.setTime('2026-11-01T00:00:00Z');f.submit(another(f));
 f.context.useLegacyQuoteWrites();f.submit(another(f));assert.equal(f.cells.length,3);assert.equal(f.files.size,1);
 assert.equal(f.readRequests(0).requests.length,3);assert.deepEqual(index(f).map(r=>r[8]),[1,2,3]);
 assert.deepEqual(plain(f.cells[1]),before[1]);
});

test('interrupted legacy indexing resumes without altering originals or duplicating entries',()=>{
 const f=fixture({now});successful(f);f.submit(another(f));const before=plain(f.cells);
 f.failNextWrite(op=>phaseWrite('COMMITTED')(op)&&op.row===3,{after:true});assert.throws(()=>f.context.setupMonthlyQuoteStorage());
 assert.equal(f.properties.QUOTE_STORAGE_READY,undefined);f.context.setupMonthlyQuoteStorage();
 assert.equal(index(f).length,2);assert.deepEqual(plain(f.cells),before);assert.equal(f.context.auditMonthlyQuoteStorage().committed,2);
});

test('legacy duplicate UUIDs and index corruption fail closed instead of enabling ambiguous storage',()=>{
 const f=fixture({now});successful(f);f.cells.push(f.cells[1].slice());assert.throws(()=>f.context.setupMonthlyQuoteStorage());assert.equal(f.properties.QUOTE_STORAGE_READY,undefined);
 for(const corrupt of [r=>r[9]='UNKNOWN',r=>r[5]='NAMU-20261101-0001',r=>r[8]=99]){
  const g=create();successful(g);corrupt(entry(g));assert.throws(()=>g.context.auditMonthlyQuoteStorage());assert.throws(()=>g.readRequests(0));
 }
});

test('explicit migration preserves receipt, hash, time, ciphertext and sequence, retaining a source copy',()=>{
 const f=fixture({now});successful(f);const original=plain(f.cells[1]);f.context.setupMonthlyQuoteStorage();
 assert.equal(f.files.size,0);const result=f.context.copyLegacyQuotesToMonthly(1);assert.equal(result.copied,1);assert.equal(result.sourceRowsRetained,true);
 assert.equal(f.requestRows().length,2);for(const r of f.requestRows())assert.deepEqual(plain(r.row),original);
 assert.equal(f.context.copyLegacyQuotesToMonthly(20).copied,0);assert.equal(f.readRequests(0).requests.length,1);assert.equal(f.context.auditMonthlyQuoteStorage().committed,1);
 assert.equal(successful(f).receiptNumber,original[16]);
});

for(const [name,predicate]of [
 ['migration intent',phaseWrite('MIGRATING')],['migration target',dayWrite],['migration pointer',phaseWrite('COMMITTED')]
])for(const after of [false,true])test(`${name} failure ${after?'after':'before'} apply resumes with one target copy`,()=>{
 const f=fixture({now});successful(f);const original=plain(f.cells[1]);f.context.setupMonthlyQuoteStorage();f.failNextWrite(predicate,{after});
 assert.throws(()=>f.context.copyLegacyQuotesToMonthly(1));f.context.copyLegacyQuotesToMonthly(1);
 assert.equal(f.requestRows().length,2);for(const r of f.requestRows())assert.deepEqual(plain(r.row),original);
 assert.equal(f.context.auditMonthlyQuoteStorage().committed,1);assert.equal(f.readRequests(0).requests.length,1);
});

test('migrated request disposal clears both copies before publishing; source erase failure blocks sync',()=>{
 const f=fixture({now});successful(f);f.context.setupMonthlyQuoteStorage();f.context.copyLegacyQuotesToMonthly(1);const cursor=entry(f)[8];
 const failSource=op=>op.sheetName==='웹견적요청'&&op.row>1&&op.values[0][15]==='{"expired":true}';
 f.failNextWrite(failSource);assert.throws(()=>f.context.closeRequest_({requestId:f.request.requestId}));
 assert.ok(JSON.parse(f.cells[1][15]).request);f.failNextWrite(failSource);assert.throws(()=>f.readRequests(cursor));
 const change=f.readRequests(cursor);assert.equal(change.requests[0].expired,true);
 for(const r of f.requestRows())assert.equal(r.row[15],'{"expired":true}');assert.equal(f.context.auditMonthlyQuoteStorage().committed,1);
});

test('notification failure does not cancel monthly receipt; retry stays on the correct UUID',()=>{
 const f=create();f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({device:{fcmToken:'token'}});f.failPush();
 successful(f);assert.equal(row(f)[10],'PENDING');assert.equal(successful(f).duplicate,true);assert.equal(index(f).length,1);
 assert.equal(f.sent.length,0);f.context.dispatchQuoteNotifications();assert.ok(f.sent.length>0);const payload=JSON.stringify(f.sent);assert.ok(!payload.includes('김나무'));assert.ok(!payload.includes('01012345678'));
});

test('monthly audit makes no writes and detects missing, orphan or duplicate records',()=>{
 const f=create();successful(f);let count=f.operations.length;f.context.auditMonthlyQuoteStorage();assert.equal(f.operations.length,count);
 const sheet=f.requestRows()[0].sheet,copy=sheet.cells[1].slice();copy[0]=crypto.randomUUID();sheet.cells.push(copy);assert.throws(()=>f.context.auditMonthlyQuoteStorage());
 sheet.cells.pop();sheet.cells.push(sheet.cells[1].slice());assert.throws(()=>f.context.auditMonthlyQuoteStorage());
 sheet.cells.pop();sheet.cells.splice(1,1);assert.throws(()=>f.context.auditMonthlyQuoteStorage());
});

test('daily data and global index grow beyond the initial Sheet row grid',()=>{
 const f=create();successful(f);const sheet=f.requestRows()[0].sheet;
 sheet.maxRows=2;f.book.get('전체견적색인').maxRows=2;assert.equal(f.submit(another(f)).ok,true);
 assert.ok(sheet.getMaxRows()>2);assert.ok(f.book.get('전체견적색인').getMaxRows()>2);assert.equal(f.readRequests(0).requests.length,2);
});

test('sorting into an empty reserved row relocates the reservation without overwriting a different UUID',()=>{
 const f=create();f.failNextWrite(dayWrite);assert.equal(f.submit().ok,false);const other=another(f);f.submit(other);
 const sheet=f.requestRows()[0].sheet;sheet.cells[1]=sheet.cells[2];sheet.cells.splice(2,1);
 successful(f);assert.equal(f.requestRows().length,2);assert.equal(row(f,other.requestId)[0],other.requestId);assert.equal(f.context.auditMonthlyQuoteStorage().committed,2);
});

test('legacy-write fallback remains available after an uncertain empty month creation',()=>{
 const f=create();successful(f);f.setTime('2026-11-01T00:00:00Z');const next=another(f);f.failCreate({after:false});assert.equal(f.submit(next).ok,false);
 assert.equal(f.context.useLegacyQuoteWrites().unresolvedMonths,1);assert.equal(f.submit(next).ok,true);
 assert.equal(f.cells.length,2);assert.equal(f.readRequests(0).requests.length,2);assert.equal(f.files.size,1);
});

test('owner recovery verifies file creation token, rejecting another monthly file',()=>{
 const f=create();successful(f);const oldId=months(f)[0][1];f.setTime('2026-11-01T00:00:00Z');f.failCreate({after:true});assert.equal(f.submit(another(f)).ok,false);
 assert.throws(()=>f.context.recoverQuoteMonthFile('2026-11',oldId));
 const fileId=[...f.files.keys()].find(id=>id!==oldId);assert.equal(f.context.recoverQuoteMonthFile('2026-11',fileId).fileId,fileId);assert.equal(f.files.size,2);
});

test('monthly receipt does not depend on cache availability',()=>{
 const f=create();f.failCache();assert.equal(f.submit().ok,true);const receipt=entry(f)[5];
 assert.equal(successful(f).receiptNumber,receipt);assert.equal(index(f).length,1);assert.equal(f.requestRows().length,1);
});

test('purchase closes a monthly quote while leaving encrypted sales in their existing store',()=>{
 const f=create();successful(f);const timestamp=now-1000,eventId=crypto.randomUUID();
 const metadata={orderNumber:'MONTHLY_PURCHASE',orderDate:'2026-10-07 00:00',status:'구매확정',revision:1,totalQty:2,totalPrice:2000,isPrepaid:true,shippingFee:0,purchasedAt:timestamp,paidAt:0,deliveredAt:0,completedAt:0,updatedAt:timestamp,costTotal:1000,expenseTotal:0,costConfirmed:false,profitMode:'resale',commissionBps:0,taxAmount:0,items:[{species:'소나무',quantity:2,unitPrice:1000}]};
 const encryptedPersonal=f.seal({customerName:'김나무',metadata},'order',eventId),fingerprint=f.context.saleDigest_(metadata,encryptedPersonal);
 assert.equal(f.context.saveSale_({eventId,metadata,encryptedPersonal,fingerprint,sourceRequestId:f.request.requestId}).saved,true);
 assert.equal(row(f)[15],'{"expired":true}');const sale=f.context.listSales_({afterSequence:0}).sales[0];
 assert.equal(f.open(sale.encryptedPersonal,'order',sale.eventId).customerName,'김나무');assert.equal(f.book.get('판매기록').cells.length,2);
 assert.equal(f.context.listRequests_({afterSequence:1}).requests[0].expired,true);
});

test('derived summary failure cannot undo a receipt and maintenance rebuilds totals',()=>{
 const f=create();f.failNextWrite(op=>op.sheetName==='월간현황'&&op.row>1);successful(f);
 const book=f.books.get(months(f)[0][1]);assert.equal(book.getSheetByName('월간현황').cells.length,1);
 assert.throws(()=>f.context.maintainMonthlyQuoteStorage());f.context.maintainMonthlyQuoteStorage();assert.equal(book.getSheetByName('월간현황').cells[1][1],1);
});

test('successful monthly notifications update only the matching record, independently of sync sequence',()=>{
 const f=create();successful(f);const firstSequence=entry(f)[8],other=another(f);
 f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({device:{fcmToken:'token'}});
 assert.equal(f.submit(other).ok,true);assert.equal(row(f,other.requestId)[10],'PENDING');assert.equal(row(f)[10],'PENDING');assert.equal(f.sent.length,0);
 f.context.dispatchQuoteNotifications();assert.equal(row(f)[10],'SENT');assert.equal(entry(f)[8],firstSequence);
});


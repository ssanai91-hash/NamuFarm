const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const START='2026-10-31T14:50:00Z', PAID='2026-10-31T15:01:00Z';
function setup(options={}){const f=fixture({now:Date.parse(START),...options});f.context.setupAnnualQuoteOrderLedgers();return f;}
function quote(f){assert.equal(f.submit().ok,true);for(const status of ['견적확정','견적발송완료'])f.context.updateRequestStatus_({requestId:f.request.requestId,status});return f.request.requestId;}
function event(f,patch={},source=''){
 const time=Date.parse(PAID),meta={orderNumber:'PC_'+crypto.randomUUID(),orderDate:'2026-11-01 00:01',status:'입금확인',revision:1,totalQty:2,totalPrice:2000,isPrepaid:false,shippingFee:0,purchasedAt:time,paidAt:time,deliveredAt:0,completedAt:0,updatedAt:time,costTotal:1000,expenseTotal:0,costConfirmed:true,profitMode:'resale',commissionBps:3000,taxAmount:0,items:[{species:'소나무',quantity:2,unitPrice:1000}],...patch};
 const order={...meta,orderId:null,customerName:'테스트고객',customerPhone:'01012345678',customerEmail:'',receiverName:'테스트',zipNo:'',roadAddress:'테스트 주소',detailAddress:'',note:'',itemSummary:'소나무|||||2|1000|수기견적|500',sourceRequestId:source,sourceQuoteNumber:source?'NAMU-20261031-0001':''};delete order.items;
 const personalData={order};return {eventId:crypto.randomUUID(),metadata:meta,personalData,sourceRequestId:source,protocolVersion:5,fingerprint:f.context.saleDigest_(meta,personalData,true)};
}
function saved(f,body){return f.context.saveSale_(body);}
function records(f){return f.context.saleRecords_();}
function finish(f,body,status='배송완료'){
 f.setTime('2026-12-02T02:00:00Z');const time=Date.parse('2026-12-02T01:00:00Z');
 return event(f,{...body.metadata,revision:body.metadata.revision+1,status,updatedAt:time,...(status==='배송완료'?{completedAt:time,deliveredAt:time}:{})},body.sourceRequestId);
}
test('activation creates exactly two annual files with twelve KST monthly tabs; repeat creates nothing',()=>{
 const f=setup();assert.equal(f.files.size,2);assert.equal(f.context.doGet().ledgerStorage,'annual-v1');
 for(const b of f.books.values())if(b.getId()!=='test'){assert.equal(b.timeZone,'Asia/Seoul');assert.equal(b.getSheets().length,12);assert.deepEqual(b.getSheets().map(s=>s.getName()),Array.from({length:12},(_,i)=>String(i+1).padStart(2,'0')+'월'));}
 f.context.setupAnnualQuoteOrderLedgers();assert.equal(f.files.size,2);
});
test('October quote, November payment, December delivery retain original months and quote contents',()=>{
 const f=setup(),source=quote(f),before=JSON.stringify(f.requestRows()[0].row);f.setTime(PAID);const body=event(f,{},source);saved(f,body);
 assert.equal(f.requestRows()[0].sheet.getName(),'10월');assert.equal(f.requestRows()[0].book.getName(),'나무팜_견적_2026');
 assert.equal(records(f)[0].sheet.getName(),'11월');assert.equal(records(f)[0].sheet.getParent().getName(),'나무팜_주문_2026');
 saved(f,finish(f,body));assert.equal(records(f).length,1);assert.equal(records(f)[0].sheet.getName(),'11월');assert.equal(records(f)[0].row[2],'배송완료');assert.equal(JSON.stringify(f.requestRows()[0].row),before);
 assert.equal(f.context.listSales_({afterSequence:0}).sales.length,1);assert.equal(f.context.salesReport_({granularity:'month',from:'2026-01-01',to:'2026-12-31'}).report.buckets[0].period,'2026-12');
});
test('delayed sync uses the first local KST payment timestamp, not upload year',()=>{
 const f=setup();f.setTime('2027-01-05T00:00:00Z');const time=Date.parse('2026-12-31T14:59:00Z');const body=event(f,{paidAt:time,purchasedAt:time,updatedAt:time});saved(f,body);
 assert.equal(records(f)[0].sheet.getName(),'12월');assert.equal(records(f)[0].sheet.getParent().getName(),'나무팜_주문_2026');
 const t2=Date.parse('2026-12-31T15:00:00Z');saved(f,event(f,{paidAt:t2,purchasedAt:t2,updatedAt:t2}));assert.equal(records(f)[1].sheet.getName(),'01월');assert.equal(records(f)[1].sheet.getParent().getName(),'나무팜_주문_2027');
});
test('reordered rows are updated by order number; stale revision and changed paidAt are rejected',()=>{
 const f=setup();f.setTime(PAID);const a=event(f),b=event(f);saved(f,a);saved(f,b);const s=records(f)[0].sheet;[s.cells[1],s.cells[2]]=[s.cells[2],s.cells[1]];
 saved(f,event(f,{...a.metadata,revision:2,expenseTotal:10}));assert.equal(s.cells[1][0],b.metadata.orderNumber);assert.equal(s.cells[1][19],1);assert.equal(s.cells[2][19],2);
 assert.throws(()=>saved(f,event(f,{...a.metadata,revision:2,expenseTotal:11})),e=>e.code==='CONFLICT');
 assert.throws(()=>saved(f,event(f,{...a.metadata,revision:3,paidAt:a.metadata.paidAt-1})));assert.equal(records(f).length,2);
});
for(const after of [false,true])test(`interrupted paid row write can be replayed once (${after?'lost acknowledgement':'not written'})`,()=>{
 const f=setup(),source=quote(f);f.setTime(PAID);const body=event(f,{},source);
 f.failNextWrite(op=>op.sheetName==='11월'&&op.row>1,{after});assert.throws(()=>saved(f,body));assert.equal(saved(f,body).saved,true);saved(f,body);assert.equal(records(f).length,1);
 assert.equal(f.context.quoteRequestStatus_(source),'견적발송완료');assert.equal(f.book.get('주문견적연결').cells.length,2);
});
test('missing receipt is repaired and earlier retry remains valid after newer revision',()=>{
 const f=setup();f.setTime(PAID);const body=event(f);f.failReceipt();assert.throws(()=>saved(f,body));saved(f,event(f,{...body.metadata,revision:2}));assert.equal(saved(f,body).saved,true);assert.equal(records(f).length,1);assert.equal(records(f)[0].row[19],2);
});
for(const status of ['배송완료','구매취소'])test(`same quote cannot create another order after ${status}`,()=>{
 const f=setup(),source=quote(f);f.setTime(PAID);const body=event(f,{},source);saved(f,body);saved(f,finish(f,body,status));
 assert.throws(()=>saved(f,event(f,{},source)),e=>e.code==='CONFLICT');assert.equal(records(f).length,1);
});
test('invalid first revision does not reserve a quote; valid alternative remains possible',()=>{
 const f=setup(),source=quote(f);f.setTime(PAID);assert.throws(()=>saved(f,event(f,{revision:2},source)),e=>e.code==='CONFLICT');assert.equal(f.book.get('주문견적연결').getLastRow(),1);saved(f,event(f,{},source));assert.equal(records(f).length,1);
});
test('editing a paid order cannot detach its source quote or rename its quote reference',()=>{
 const f=setup(),source=quote(f);f.setTime(PAID);const body=event(f,{},source);saved(f,body);assert.throws(()=>saved(f,event(f,{...body.metadata,revision:2},'')),e=>e.code==='CONFLICT');
 const edit=event(f,{...body.metadata,revision:2},source);edit.personalData.order.sourceQuoteNumber='other';edit.fingerprint=f.context.saleDigest_(edit.metadata,edit.personalData,true);assert.throws(()=>saved(f,edit),e=>e.code==='CONFLICT');
});
test('lost annual file create response is recovered by token without a second file',()=>{
 const f=fixture({now:Date.parse(START)});f.failCreate({after:true});assert.throws(()=>f.context.setupAnnualQuoteOrderLedgers());assert.equal(f.files.size,1);assert.equal(f.properties.ANNUAL_LEDGER_MODE,undefined);f.context.setupAnnualQuoteOrderLedgers();assert.equal(f.files.size,2);
});
for(const monthly of [false,true])test(`existing ${monthly?'monthly':'single'} quotes survive cutover without moving or renumbering`,()=>{
 const f=fixture({monthly,now:Date.parse(START)});const receipt=f.submit();const before=JSON.stringify(f.requestRows()[0].row),book=f.requestRows()[0].book.getId();f.context.setupAnnualQuoteOrderLedgers();assert.equal(JSON.stringify(f.requestRows()[0].row),before);assert.equal(f.requestRows()[0].book.getId(),book);assert.equal(f.submit().receiptNumber,receipt.receiptNumber);quote(f);assert.equal(f.context.quoteRequestStatus_(f.request.requestId),'견적발송완료');
});
test('cutover from simplified storage ignores stale old index and retains original copy expiry',()=>{
 const f=fixture({monthly:true,now:Date.parse(START)});f.submit();f.context.simplifyQuoteStorage();f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
 f.context.setupAnnualQuoteOrderLedgers();assert.equal(f.context.quoteRequestStatus_(f.request.requestId),'견적확정');f.context.closeRequest_({requestId:f.request.requestId});f.context.purgeExpiredCustomerData();
 for(const r of f.requestRows())assert.equal(JSON.parse(r.row[15]).expired,true);
});
test('activation interruption keeps old route until the new index is verified',()=>{
 const f=fixture({now:Date.parse(START)});f.submit();f.failNextWrite(op=>op.sheetName==='연도견적색인'&&op.row>1,{after:true});assert.throws(()=>f.context.setupAnnualQuoteOrderLedgers());assert.equal(f.properties.ANNUAL_LEDGER_MODE,undefined);assert.equal(f.readRequests(0).requests.length,1);f.context.setupAnnualQuoteOrderLedgers();assert.equal(f.readRequests(0).requests.length,1);assert.equal(f.files.size,2);
});
test('existing legacy orders remain in place but participate in annual-mode reads and updates',()=>{
 const f=fixture({now:Date.parse(PAID)}),body=event(f);saved(f,body);f.context.setupAnnualQuoteOrderLedgers();saved(f,event(f,{...body.metadata,revision:2}));assert.equal(records(f)[0].sheet.getName(),'판매기록');assert.equal(records(f)[0].row[19],2);assert.equal(f.context.listSales_({afterSequence:0}).sales.length,1);
});
test('missing annual month fails closed instead of returning a partial order snapshot',()=>{
 const f=setup(),order=[...f.books.values()].find(b=>b.getName()==='나무팜_주문_2026');order.sheets.delete('01월');assert.throws(()=>f.context.listSales_({afterSequence:0}),e=>e.code==='NOT_READY');
});
test('annual customer retention scrubs source and terminal order but preserves sales totals',()=>{
 const f=setup(),source=quote(f);f.setTime(PAID);const body=event(f,{},source);saved(f,body);saved(f,finish(f,body));f.setTime('2032-01-01T00:00:00Z');f.context.purgeExpiredCustomerData();assert.equal(records(f)[0].row[16],'');assert.equal(JSON.parse(f.requestRows()[0].row[15]).expired,true);assert.equal(f.context.salesReport_({granularity:'year',from:'2026-01-01',to:'2026-12-31'}).report.buckets[0].orders,1);
});
test('annual setup is owner-only and is not a public web action',()=>{
 const f=setup();assert.equal(f.post({action:'setupAnnualQuoteOrderLedgers'}).ok,false);assert.equal(f.post({action:'save_sale',...event(f)}).code,'UNAUTHORIZED');
});
for(const after of [false,true])test(`quote status interruption recovers before snapshot (${after?'lost acknowledgement':'not written'})`,()=>{
 const f=setup();f.submit();f.failNextWrite(op=>op.sheetName==='10월'&&op.row>1&&op.values[0][9]==='견적확정',{after});assert.throws(()=>f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'}));
 assert.equal(f.readRequests(0).requests[0].status,'견적확정');f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적발송완료'});assert.equal(f.context.quoteRequestStatus_(f.request.requestId),'견적발송완료');
});
test('blank Drive default tabs are removed while monthly tabs remain',()=>{
 const f=fixture({now:Date.parse(START)}),create=f.context.Drive.Files.create;f.context.Drive.Files.create=(...args)=>{const file=create(...args);f.books.get(file.id).insertSheet('Sheet1');return file;};f.context.setupAnnualQuoteOrderLedgers();for(const b of f.books.values())if(b.getId()!=='test')assert.equal(b.getSheets().length,12);
});

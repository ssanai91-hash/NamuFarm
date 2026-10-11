const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const START=Date.parse('2026-10-31T14:59:00Z');
function create(){const f=fixture({now:START});f.context.setupYearlyLedgers();return f;}
function book(f,kind,year=2026){return [...f.books.values()].find(b=>b.getName()===`나무팜_${kind}_${year}`);}
function sentQuote(f){const result=f.submit();assert.equal(result.ok,true);const id=f.request.requestId;
 for(const status of ['견적확정','견적발송완료'])f.context.updateRequestStatus_({requestId:id,status});return id;}
function sale(f,{number='ORDER_1',source='',time=f.context.Date.now(),...patch}={}){
 const meta={orderNumber:number,orderDate:'2026-10-31 23:59',status:'입금확인',revision:1,totalQty:2,totalPrice:2000,isPrepaid:true,shippingFee:0,purchasedAt:time,paidAt:time,deliveredAt:0,completedAt:0,updatedAt:time,costTotal:1000,expenseTotal:0,costConfirmed:false,profitMode:'resale',commissionBps:0,taxAmount:0,items:[{species:'소나무',quantity:2,unitPrice:1000}],...patch};
 const personalData={order:{...meta,customerName:'테스트',customerPhone:'01012345678',customerEmail:'',receiverName:'테스트',zipNo:'12345',roadAddress:'주소',detailAddress:'',note:'',itemSummary:'소나무|상묘|1년|2|1000|농원|500',sourceQuoteNumber:'NAMU-20261031-0001',sourceRequestId:source}};
 return {eventId:crypto.randomUUID(),protocolVersion:5,metadata:meta,personalData,sourceRequestId:source,fingerprint:f.context.saleDigest_(meta,personalData,true)};
}
function next(f,body,patch={}){return sale(f,{number:body.metadata.orderNumber,source:body.sourceRequestId,...body.metadata,revision:body.metadata.revision+1,updatedAt:f.context.Date.now(),...patch});}
function save(f,body){return f.context.saveSale_(body);}
function orderRows(f){return f.context.saleStorageRows_();}

test('setup creates two yearly books with twelve KST month tabs and is repeatable',()=>{
 const f=create();assert.equal(f.files.size,2);
 for(const kind of ['견적','주문']){const b=book(f,kind);assert.equal(b.timeZone,'Asia/Seoul');assert.equal(b.sheets.size,12);assert.ok(b.sheets.has('01월'));assert.ok(b.sheets.has('12월'));}
 const setup=f.context.setupYearlyLedgers();assert.equal(setup.ready,true);assert.equal(f.files.size,2);
 assert.equal(f.context.doGet().ledgerStorage,'yearly-v1');
});
test('quotes use receipt month while payment uses its own month; quote row is untouched',()=>{
 const f=create(),source=sentQuote(f),quote=book(f,'견적').getSheetByName('10월').cells[1],before=JSON.stringify(quote);
 f.setTime('2026-10-31T15:01:00Z');const body=sale(f,{source});save(f,body);
 assert.equal(book(f,'주문').getSheetByName('11월').cells[1][0],body.metadata.orderNumber);
 assert.equal(book(f,'주문').getSheetByName('10월').getLastRow(),1);
 assert.equal(JSON.stringify(quote),before);assert.equal(quote[9],'견적발송완료');
});
test('delivery in another month updates the original order row without moving it',()=>{
 const f=create(),body=sale(f);save(f,body);f.setTime('2026-12-02T00:00:00Z');const now=f.context.Date.now();
 const completed=next(f,body,{status:'배송완료',deliveredAt:now,completedAt:now,costConfirmed:true});save(f,completed);
 assert.equal(book(f,'주문').getSheetByName('10월').cells[1][2],'배송완료');
 assert.equal(book(f,'주문').getSheetByName('12월').getLastRow(),1);assert.equal(orderRows(f).length,1);
 const report=f.context.salesReport_({granularity:'month',from:'2026-01-01',to:'2026-12-31'});assert.equal(report.report.buckets[0].period,'2026-12');
});
test('year rollover uses paidAt in KST and older years remain in combined sync',()=>{
 const f=create();save(f,sale(f));f.setTime('2026-12-31T15:01:00Z');save(f,sale(f,{number:'NEXT_YEAR'}));
 assert.equal(book(f,'주문',2027).getSheetByName('01월').cells[1][0],'NEXT_YEAR');
 assert.equal(f.context.listSales_({afterSequence:0}).sales.length,2);
 f.submit({...f.request,requestId:crypto.randomUUID()});assert.equal(book(f,'견적',2027).getSheetByName('01월').getLastRow(),2);
});
test('retrying a payment or an acknowledgment never appends a second order',()=>{
 const f=create(),body=sale(f);save(f,body);save(f,body);save(f,body);assert.equal(orderRows(f).length,1);
 assert.equal(f.book.get('판매변경이력').getLastRow(),2);
});
test('one quote cannot become two different orders, including concurrent-device attempts',()=>{
 const f=create(),source=sentQuote(f);save(f,sale(f,{source}));assert.throws(()=>save(f,sale(f,{source,number:'OTHER_DEVICE'})),e=>e.code==='CONFLICT');assert.equal(orderRows(f).length,1);
});
test('an unpaid or unsent quote cannot create an order ledger entry',()=>{
 const f=create();f.submit();assert.throws(()=>save(f,sale(f,{source:f.request.requestId})),e=>e.code==='CONFLICT');
 assert.throws(()=>save(f,sale(f,{status:'구매확정',paidAt:0})),e=>e.code==='CONFLICT');assert.equal(orderRows(f).length,0);
 assert.equal(f.book.get('주문저장위치').getLastRow(),1);
});
test('row reordering and insertion do not change the order being updated',()=>{
 const f=create(),one=sale(f),two=sale(f,{number:'ORDER_2'});save(f,one);save(f,two);
 const sheet=book(f,'주문').getSheetByName('10월');[sheet.cells[1],sheet.cells[2]]=[sheet.cells[2],sheet.cells[1]];
 save(f,next(f,one,{expenseTotal:100}));assert.equal(sheet.cells[2][0],'ORDER_1');assert.equal(sheet.cells[2][9],100);assert.equal(sheet.cells[1][9],0);
});
test('changed paidAt and stale revisions are rejected instead of moving or overwriting an order',()=>{
 const f=create(),one=sale(f);save(f,one);const newer=next(f,one,{expenseTotal:10});save(f,newer);
 assert.throws(()=>save(f,next(f,one,{expenseTotal:20})),e=>e.code==='CONFLICT');
 assert.throws(()=>save(f,next(f,newer,{paidAt:one.metadata.paidAt+1,updatedAt:one.metadata.updatedAt+2})),e=>e.code==='CONFLICT');assert.equal(orderRows(f)[0][9],10);
});
test('a lost row-write response is recovered in the reserved month across midnight',()=>{
 const f=create(),body=sale(f);f.failNextWrite(op=>op.sheetName==='10월' && op.values[0][0]==='ORDER_1',{after:true});
 assert.throws(()=>save(f,body));f.setTime('2026-10-31T15:01:00Z');save(f,body);assert.equal(orderRows(f).length,1);assert.equal(book(f,'주문').getSheetByName('10월').cells[1][0],'ORDER_1');
});
test('a failed payment row keeps its source reservation and cannot be replayed under a new order ID',()=>{
 const f=create(),source=sentQuote(f),body=sale(f,{source});f.failNextWrite(op=>op.sheetName==='10월' && op.values[0][0]==='ORDER_1');
 assert.throws(()=>save(f,body));assert.throws(()=>save(f,sale(f,{source,number:'OTHER'})),e=>e.code==='CONFLICT');save(f,body);assert.equal(orderRows(f).length,1);
});
test('a missing event receipt is repaired before a newer revision; old retries do not roll it back',()=>{
 const f=create(),body=sale(f);f.failReceipt();assert.throws(()=>save(f,body));save(f,next(f,body,{expenseTotal:100}));save(f,body);assert.equal(orderRows(f)[0][19],2);assert.equal(orderRows(f)[0][9],100);
});
test('lost yearly-file creation response is discovered by token instead of creating another file',()=>{
 const f=create();f.setTime('2027-01-01T00:00:00Z');f.failCreate({after:true});const body=sale(f);
 assert.throws(()=>save(f,body));assert.equal(f.files.size,3);save(f,body);assert.equal(f.files.size,3);assert.equal(orderRows(f).length,1);
});
test('uncertain file creation with no discoverable file stops safely without a second create',()=>{
 const f=create();f.setTime('2027-01-01T00:00:00Z');f.failCreate();const body=sale(f);assert.throws(()=>save(f,body));assert.throws(()=>save(f,body),e=>e.code==='NOT_READY');assert.equal(f.files.size,2);
});
test('retention reaches yearly order data and erases the routing source without losing totals',()=>{
 const f=create(),source=sentQuote(f),body=sale(f,{source});save(f,body);const now=f.context.Date.now();save(f,next(f,body,{status:'배송완료',deliveredAt:now,completedAt:now,costConfirmed:true}));
 const row=book(f,'주문').getSheetByName('10월').cells[1];row[14]='2000-01-01T00:00:00Z';f.context.purgeExpiredCustomerData();
 assert.equal(row[16],'');assert.equal(JSON.parse(row[15]).archived,true);assert.equal(f.book.get('주문저장위치').cells[1][1],'');
 assert.throws(()=>save(f,body),e=>e.code==='CONFLICT');assert.equal(f.context.salesReport_({granularity:'year',from:'2026-01-01',to:'2026-12-31'}).report.buckets[0].orders,1);
});
test('existing single-sheet quotes and sales stay in place and remain readable after setup',()=>{
 const f=fixture({now:START});const source=sentQuote(f),body=sale(f,{source});save(f,body);
 const quote=JSON.stringify(f.cells),old=JSON.stringify(f.book.get('판매기록').cells);f.context.setupYearlyLedgers();
 assert.equal(JSON.stringify(f.cells),quote);assert.equal(JSON.stringify(f.book.get('판매기록').cells),old);
 save(f,next(f,body,{expenseTotal:200}));assert.equal(f.book.get('판매기록').cells[1][9],200);assert.equal(f.context.listSales_({afterSequence:0}).sales.length,1);
});
test('setup after a prior monthly-to-single cutover preserves current quote status and copies',()=>{
 const f=fixture({now:START,monthly:true});f.submit();f.context.simplifyQuoteStorage();
 for(const status of ['견적확정','견적발송완료'])f.context.updateRequestStatus_({requestId:f.request.requestId,status});
 f.context.setupYearlyLedgers();assert.equal(f.context.quoteRequestStatus_(f.request.requestId),'견적발송완료');
 f.context.closeRequest_({requestId:f.request.requestId});assert.ok(f.requestRows().every(r=>JSON.parse(r.row[15]).expired));
});
test('yearly quote rows participate in audit, snapshot, and normal 30-day disposal',()=>{
 const f=create();f.submit();assert.equal(f.context.auditMonthlyQuoteStorage().committed,1);assert.equal(f.context.snapshotRequests_({afterSequence:0}).total,1);
 f.setTime('2026-12-15T00:00:00Z');f.context.purgeExpiredCustomerData();assert.equal(book(f,'견적').getSheetByName('10월').cells[1][15],'{"expired":true}');
});
test('a deleted month tab fails closed rather than silently replacing its orders',()=>{
 const f=create(),body=sale(f);save(f,body);book(f,'주문').sheets.delete('10월');
 assert.throws(()=>f.context.listSales_({afterSequence:0}));assert.throws(()=>save(f,next(f,body)));assert.equal(book(f,'주문').sheets.has('10월'),false);
});
test('different yearly ledgers use globally increasing sequences for incremental synchronization',()=>{
 const f=create();save(f,sale(f));const cursor=f.context.listSales_({afterSequence:0}).sales[0].sequence;
 f.setTime('2027-02-01T00:00:00Z');save(f,sale(f,{number:'ORDER_NEXT'}));const next=f.context.listSales_({afterSequence:cursor});
 assert.equal(next.sales.length,1);assert.equal(next.sales[0].metadata.orderNumber,'ORDER_NEXT');assert.ok(next.sales[0].sequence>cursor);
});

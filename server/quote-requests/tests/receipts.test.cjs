const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const now=Date.parse('2026-10-06T15:00:00.000Z');
test('receipt, Seoul time and original UUID are committed with the encrypted request',()=>{
  const f=fixture({now}),receipt=f.submit();
  assert.equal(receipt.ok,true); assert.equal(receipt.requestId,f.request.requestId);
  assert.equal(receipt.receiptNumber,'NFQ-20261007-0001'); assert.equal(receipt.createdAt,'2026-10-06T15:00:00.000Z');
  assert.equal(f.cells[1][16],receipt.receiptNumber); assert.equal(f.cells[1][17],'2026-10-07 00:00:00');
  assert.equal(f.readRequests(0).requests[0].receiptNumber,receipt.receiptNumber);
  assert.equal(f.saved().customerName,f.request.customerName); assert.equal(f.getTimeZone(),'Asia/Seoul');
});
test('daily numbers increase and reset at Seoul midnight',()=>{
  const f=fixture({now:Date.parse('2026-10-06T14:59:59.000Z')});
  assert.equal(f.submit().receiptNumber,'NFQ-20261006-0001');
  assert.equal(f.submit({...f.request,requestId:crypto.randomUUID()}).receiptNumber,'NFQ-20261006-0002');
  f.setTime('2026-10-06T15:00:00.000Z');
  assert.equal(f.submit({...f.request,requestId:crypto.randomUUID()}).receiptNumber,'NFQ-20261007-0001');
});
test('lost acknowledgement retry on a later day preserves the original receipt and date',()=>{
  const f=fixture({now});f.failCache();assert.equal(f.submit().ok,false);
  const number=f.cells[1][16],created=f.cells[1][2];f.setTime('2026-10-07T15:00:00.000Z');
  const retry=f.submit();assert.equal(retry.duplicate,true);assert.equal(retry.receiptNumber,number);assert.equal(retry.createdAt,created);assert.equal(f.cells.length,2);
});
test('failed Sheet write never acknowledges a receipt or reuses its reserved number',()=>{
  const f=fixture({now});f.failWrite();assert.equal(f.submit().ok,false);assert.equal(f.cells.length,1);
  assert.equal(f.submit().receiptNumber,'NFQ-20261007-0002');
});
test('deletion changes the sync sequence but retains the customer receipt',()=>{
  const f=fixture({now}),first=f.submit(),sequence=f.cells[1][1];
  f.context.closeRequest_({requestId:f.request.requestId});
  const row=f.readRequests(sequence).requests[0];assert.equal(row.expired,true);assert.equal(row.receiptNumber,first.receiptNumber);
  assert.equal(f.submit().receiptNumber,first.receiptNumber);
});
test('backfill preserves original UUID, dates, hashes, ciphertext and cursors after sorting',()=>{
  const f=fixture({now});f.submit();f.setTime('2026-10-06T15:10:00.000Z');f.submit({...f.request,requestId:crypto.randomUUID()});
  const before=f.cells.slice(1).map(r=>JSON.stringify(r.slice(0,16)));
  f.cells.slice(1).forEach(r=>{r[16]='';r[17]=''});delete f.properties['RFQ_RECEIPT_COUNTER:20261007'];
  [f.cells[1],f.cells[2]]=[f.cells[2],f.cells[1]];f.context.setupQuoteRequests();
  assert.equal(f.cells[2][16],'NFQ-20261007-0001');assert.equal(f.cells[1][16],'NFQ-20261007-0002');
  assert.deepEqual(f.cells.slice(1).map(r=>JSON.stringify(r.slice(0,16))).sort(),before.sort());
  const metadata=f.cells.slice(1).map(r=>r.slice(16));f.context.setupQuoteRequests();
  assert.deepEqual(f.cells.slice(1).map(r=>r.slice(16)),metadata);
});
test('durable counters prevent reusing a number after a row is missing',()=>{
  const f=fixture({now});assert.equal(f.submit().receiptNumber,'NFQ-20261007-0001');f.cells.splice(1,1);
  assert.equal(f.submit({...f.request,requestId:crypto.randomUUID()}).receiptNumber,'NFQ-20261007-0002');
});
test('setup refuses to overwrite unrelated columns or corrupt counters',()=>{
  const f=fixture({now});f.cells[0][16]='기존 다른 열';assert.throws(()=>f.context.setupQuoteRequests());assert.equal(f.cells[0][16],'기존 다른 열');
  const g=fixture({now});g.properties['RFQ_RECEIPT_COUNTER:20261007']='bad';assert.equal(g.submit().ok,false);assert.equal(g.cells.length,1);
  const h=fixture({now});h.submit();h.cells[0][16]='';h.cells[0][17]='';h.cells[1][16]='';h.cells[1][17]='다른 기존 자료';
  assert.throws(()=>h.context.setupQuoteRequests());assert.equal(h.cells[1][17],'다른 기존 자료');
});

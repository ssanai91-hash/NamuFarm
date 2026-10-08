const test=require('node:test'),assert=require('node:assert/strict');
const {fixture}=require('./fixture.cjs');
const now=Date.parse('2026-10-08T14:00:00Z');

test('recovery initializes an empty private store and precreates the Korean day',()=>{
 const f=fixture({configure:false,now});delete f.properties.WEB_SECRET;
 const result=f.context.initializeRecoveredServer();
 assert.equal(result.ready,true);assert.equal(result.managementId,'test');
 assert.match(f.properties.WEB_SECRET,/^[a-f0-9]{64}$/);
 assert.equal(f.properties.RECOVERY_INITIALIZED_FOR,'test');
 assert.equal(Number(f.properties.LAST_SEQUENCE),now);assert.equal(Number(f.properties.SALE_SEQUENCE),now);
 assert.equal(f.context.secureServiceInfo_().customerDataReady,true);
 assert.ok(f.books.get(result.monthId).getSheetByName('2026-10-08'));
 assert.equal(f.context.auditMonthlyQuoteStorage().requests,0);
});

test('recovery retry keeps the secret, counters, records and monthly file',()=>{
 const f=fixture({configure:false,now});f.context.initializeRecoveredServer();
 const secret=f.properties.WEB_SECRET;
 const {consent,...raw}=f.request;const req=f.context.validateRequest_({...raw,processingBasis:'quote_request',website:''});
 const crypto=require('node:crypto');
 const body={action:'submit_request',webSecret:secret,protocolVersion:5,requestId:req.requestId,request:{...req,website:''},
  rateKey:'b'.repeat(64),fingerprint:crypto.createHmac('sha256',secret).update(JSON.stringify(req)).digest('hex')};
 assert.equal(f.post(body).ok,true);
 const before=JSON.stringify(f.requestRows().map(r=>r.row)),sequence=f.properties.LAST_SEQUENCE;
 f.context.initializeRecoveredServer();
 assert.equal(f.properties.WEB_SECRET,secret);assert.equal(f.properties.LAST_SEQUENCE,sequence);
 assert.equal(JSON.stringify(f.requestRows().map(r=>r.row)),before);assert.equal(f.files.size,1);
});

test('recovery refuses an unmarked nonempty or already configured workbook',()=>{
 const f=fixture({now});assert.equal(f.submit().ok,true);
 assert.throws(()=>f.context.initializeRecoveredServer());assert.equal(f.properties.RECOVERY_INITIALIZED_FOR,undefined);
 const other=fixture({now,monthly:true});assert.throws(()=>other.context.initializeRecoveredServer());
});

test('recovery refuses to rebind an initialized server to a different workbook',()=>{
 const f=fixture({configure:false,now});f.properties.RECOVERY_INITIALIZED_FOR='different';
 assert.throws(()=>f.context.initializeRecoveredServer());assert.equal(f.files.size,0);
});

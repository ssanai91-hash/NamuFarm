const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
function body(f,patch={}) {
 const request={...f.request,processingBasis:'quote_request',customerEmail:'buyer@example.com',formalQuoteRequested:true,recipientName:'나무 고객',replyChannel:'both',...patch};
 delete request.consent;
 return {action:'submit_request',protocolVersion:5,request,requestId:request.requestId,webSecret:f.properties.WEB_SECRET,
  rateKey:'b'.repeat(64),fingerprint:crypto.createHmac('sha256',f.properties.WEB_SECRET).update(JSON.stringify(request)).digest('hex')};
}
function device(f){const token='d'.repeat(64);assert.equal(f.post({action:'pair_device',invite:f.context.createDeviceInvite(),deviceToken:token}).paired,true);return token;}
for(const monthly of [false,true]) {
 test(`ten plain requests from one IP need no seller key (${monthly?'monthly':'single'})`,()=>{
  const f=fixture({configure:false,monthly}),receipts=[];
  f.failCache();
  for(let i=1;i<=10;i++) {
   const data=body(f,{requestId:crypto.randomUUID(),customerName:'홈페이지검증'+i,
    items:[{species:'소나무',specification:'2년생',quantity:i}]});
   const receipt=f.post(data);assert.equal(receipt.ok,true);
   assert.equal(f.post(data).receiptNumber,receipt.receiptNumber);
   receipts.push(receipt.receiptNumber);
  }
  assert.equal(new Set(receipts).size,10);assert.equal(f.requestRows().length,10);
  const token=device(f),page=f.post({action:'list_requests',deviceToken:token,afterSequence:0});
  assert.equal(page.requests.length,10);assert.equal(page.hasMore,false);
  assert.deepEqual(Array.from(page.requests,r=>r.items[0].quantity),Array.from({length:10},(_,i)=>i+1));
  assert.ok(page.requests.every(r=>!r.encryptedRequest));
 });
 test(`no seller key: intake, authenticated sync, receipt retry and disposal (${monthly?'monthly':'single'})`,()=>{
  const f=fixture({configure:false,monthly});const data=body(f);
  assert.equal(f.context.doGet().customerDataReady,true);
  assert.equal(f.context.doGet().sellerKey,null);
  const receipt=f.post(data);assert.equal(receipt.ok,true);assert.equal(f.post(data).receiptNumber,receipt.receiptNumber);
  assert.equal(f.requestRows().length,1);
  const token=device(f);
  const page=f.post({action:'list_requests',deviceToken:token,afterSequence:0});
  assert.equal(page.requests[0].customerName,data.request.customerName);
  assert.equal(page.requests[0].customerPhone,'01012345678');
  assert.equal(page.requests[0].replyChannel,'both');
  assert.equal(page.requests[0].encryptedRequest,undefined);
  assert.equal(f.requestRows()[0].row[3],data.request.customerName);
  assert.equal(f.post({...data,request:{...data.request,customerName:'변경'},fingerprint:'0'.repeat(64)}).code,'CONFLICT');
  const cursor=page.requests[0].sequence;
  assert.equal(f.post({action:'close_request',deviceToken:token,requestId:data.requestId}).closed,true);
  const deleted=f.post({action:'list_requests',deviceToken:token,afterSequence:cursor}).requests[0];
  assert.equal(deleted.expired,true);assert.equal(deleted.customerName,undefined);
  assert.equal(f.requestRows()[0].row.slice(3,9).join(''),'');
 });
}
test('v5 authentication and input validation run before storing personal data',()=>{
 const f=fixture({configure:false}),data=body(f);
 for(const patch of [{webSecret:'wrong'},{protocolVersion:4},{requestId:crypto.randomUUID()},
   {encryptedRequest:{}},{request:{...data.request,customerPhone:'invalid'}},
   {request:{...data.request,items:[{species:'나무',quantity:0,specification:''}]}}])assert.equal(f.post({...data,...patch}).ok,false);
 for(const action of ['list_requests','list_sales','save_sale','sales_report','close_request'])assert.equal(f.post({action,deviceToken:'0'.repeat(64)}).code,'UNAUTHORIZED');
 assert.equal(f.requestRows().length,0);
 assert.equal(f.context.doGet().customerName,undefined);
});
test('v4 pending encrypted requests and v5 requests coexist without rotating or deleting the key',()=>{
 const f=fixture(),key=f.properties.SELLER_PUBLIC_KEY;
 f.seedEncrypted();const data=body(f,{requestId:crypto.randomUUID()});assert.equal(f.post(data).ok,true);
 const rows=f.context.listRequests_({afterSequence:0}).requests;
 assert.ok(rows[0].encryptedRequest);assert.equal(rows[1].customerName,data.request.customerName);
 assert.equal(f.properties.SELLER_PUBLIC_KEY,key);
});
test('formula-like names are stored as literal projections and returned unchanged',()=>{
 const f=fixture({configure:false,monthly:true}),data=body(f,{customerName:'=1+1',message:'@SUM(A1)'});
 assert.equal(f.post(data).ok,true);assert.equal(f.requestRows()[0].row[3],"'=1+1");
 assert.equal(f.context.listRequests_({afterSequence:0}).requests[0].customerName,'=1+1');
});


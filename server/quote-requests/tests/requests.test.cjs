const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');
function fixture() {
  const properties = {SPREADSHEET_ID:'test',WEB_SECRET:'a'.repeat(64)};
  const cells = []; const cache = new Map(); let locked=false; let failCache=false; let failWrite=false; let failPush=false;
  const sent=[];
  const sheet={getLastRow:()=>cells.length,getRange(r,c,n=1,m=1){return {
    getValues:()=>Array.from({length:n},(_,i)=>Array.from({length:m},(_,j)=>cells[r-1+i]?.[c-1+j]??'')),
    setValues(values){if(failWrite){failWrite=false;throw Error('sheet failure');}values.forEach((row,i)=>{cells[r-1+i]??=[];row.forEach((v,j)=>cells[r-1+i][c-1+j]=v)});return this;},
    setNumberFormat(){return this;},setBackground(){return this;},setFontColor(){return this;},setFontWeight(){return this;}
  }},setFrozenRows(){},setColumnWidth(){}};
  const props={getProperty:k=>properties[k]??null,setProperty(k,v){properties[k]=v},getProperties:()=>({...properties}),deleteProperty:k=>delete properties[k]};
  const context=vm.createContext({console:{log(){}},PropertiesService:{getScriptProperties:()=>props},SpreadsheetApp:{openById:()=>({getSheetByName:()=>sheet,insertSheet:()=>sheet}),flush(){}},
    LockService:{getScriptLock:()=>({tryLock(){assert.equal(locked,false);locked=true;return true;},releaseLock(){locked=false}})},
    CacheService:{getScriptCache:()=>({get:k=>cache.get(k),put(k,v){if(failCache){failCache=false;throw Error('cache failure')};cache.set(k,v)}})},
    Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(_,v)=>[...crypto.createHash('sha256').update(v).digest()],getUuid:()=>crypto.randomUUID()},
    ScriptApp:{getOAuthToken:()=> 'test-token',getProjectTriggers:()=>[{getHandlerFunction:()=> 'dispatchQuoteNotifications'}]},
    ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType:()=>JSON.parse(text)})},
    UrlFetchApp:{fetch(url,options){assert.equal(locked,false,'Network must not hold submission lock');sent.push(JSON.parse(options.payload));if(failPush)throw Error('offline');return{getResponseCode:()=>200,getContentText:()=> '{}'}}}
  });
  vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'../Code.gs'),'utf8'),context);
  vm.runInContext('setupQuoteRequests()',context);
  const request={requestId:crypto.randomUUID(),customerName:'김나무',customerPhone:'010-1234-5678',region:'충북 옥천군',items:[{species:'소나무',specification:'',quantity:12}],message:'봄에 심어요',consent:true,website:''};
  const submit=(req=request)=>context.doPost({postData:{contents:JSON.stringify({action:'submit_request',webSecret:properties.WEB_SECRET,rateKey:'b'.repeat(64),request:req})}});
  return{context,cells,properties,request,submit,sent,failCache:()=>failCache=true,failWrite:()=>failWrite=true,failPush:()=>failPush=true};
}
test('lost receipt and retries create one Sheet row',()=>{const f=fixture();assert.equal(f.submit().ok,true);assert.equal(f.submit().duplicate,true);assert.equal(f.cells.length,2);});
test('same ID with changed quantities conflicts',()=>{const f=fixture();f.submit();assert.equal(f.submit({...f.request,items:[{...f.request.items[0],quantity:13}]}).code,'CONFLICT');assert.equal(f.cells.length,2);});
test('cache failure after Sheet commit is safely recovered',()=>{const f=fixture();f.failCache();assert.equal(f.submit().ok,false);assert.equal(f.submit().duplicate,true);assert.equal(f.cells.length,2);});
test('Sheet write failure never reports receipt success',()=>{const f=fixture();f.failWrite();assert.equal(f.submit().ok,false);assert.equal(f.cells.length,1);assert.equal(f.submit().ok,true);assert.equal(f.cells[1][1],2);});
test('formula-like strings and leading zero phone round trip from raw JSON',()=>{const f=fixture();f.request.customerName='=김나무';f.request.message="'원본";f.submit();const page=f.context.listRequests_({afterSequence:0});assert.equal(page.requests[0].customerName,'=김나무');assert.equal(page.requests[0].message,"'원본");assert.equal(page.requests[0].customerPhone,'01012345678');});
test('manual Sheet sort does not break the cursor',()=>{const f=fixture();f.submit();f.submit({...f.request,requestId:crypto.randomUUID()});[f.cells[1],f.cells[2]]=[f.cells[2],f.cells[1]];assert.equal(f.context.listRequests_({afterSequence:1}).requests[0].sequence,2);});
test('forged credentials cannot submit or list',()=>{const f=fixture();const post=body=>f.context.doPost({postData:{contents:JSON.stringify(body)}});assert.equal(post({action:'submit_request',webSecret:'bad'}).code,'UNAUTHORIZED');assert.equal(post({action:'list_requests',deviceToken:'a'.repeat(64),afterSequence:0}).code,'UNAUTHORIZED');assert.equal(f.cells.length,1);});
test('pairing retry is idempotent and invite cannot be stolen by another token',()=>{const f=fixture();const invite=f.context.createDeviceInvite();const body={invite,deviceToken:'c'.repeat(64),label:'테스트'};assert.equal(f.context.pairDevice_(body).paired,true);assert.equal(f.context.pairDevice_(body).paired,true);assert.throws(()=>f.context.pairDevice_({...body,deviceToken:'d'.repeat(64)}));assert.equal(Object.keys(JSON.parse(f.properties.DEVICES)).length,1);});
test('notification failure preserves receipt for retry and never sends personal details',()=>{const f=fixture();f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({abc:{fcmToken:'test-device'}});f.failPush();assert.equal(f.submit().ok,true);assert.equal(f.cells[1][10],'PENDING');assert.deepEqual(Object.keys(f.sent[0].message.data).sort(),['requestId','type']);assert.equal(f.sent[0].message.data.requestId,f.request.requestId);});
test('successful notification marks only the committed request sent',()=>{const f=fixture();f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({abc:{fcmToken:'test-device'}});assert.equal(f.submit().ok,true);assert.equal(f.cells[1][10],'SENT');f.submit();assert.equal(f.sent.length,1);});
test('missing consent, invalid quantities and honeypot are rejected',()=>{for(const change of [{consent:false},{website:'bot'},{items:[{species:'소나무',specification:'',quantity:0}]}]){const f=fixture();assert.equal(f.submit({...f.request,...change}).code,'INVALID');assert.equal(f.cells.length,1)}});
test('quote processing basis needs no consent and survives retry/list without inventing consent',()=>{
  const f=fixture();const {consent,...request}=f.request;
  request.processingBasis='quote_request';
  assert.equal(f.submit(request).ok,true);
  assert.equal(f.submit(request).duplicate,true);
  assert.equal(f.cells.length,2);
  const saved=JSON.parse(f.cells[1][15]);
  assert.equal(saved.processingBasis,'quote_request');
  assert.equal(Object.hasOwn(saved,'consent'),false);
  const listed=f.context.listRequests_({afterSequence:0}).requests[0];
  assert.equal(listed.processingBasis,'quote_request');
  assert.equal(Object.hasOwn(listed,'consent'),false);
});
test('absent, unknown or mixed processing basis cannot create a receipt',()=>{
  for(const patch of [{processingBasis:undefined,consent:undefined},{processingBasis:'marketing',consent:undefined},{processingBasis:'quote_request',consent:true}]){
    const f=fixture();assert.equal(f.submit({...f.request,...patch}).code,'INVALID');assert.equal(f.cells.length,1);
  }
});
test('legacy stored JSON and receipt hash retain their original consent representation',()=>{
  const f=fixture();f.submit();
  const original={requestId:f.request.requestId,customerName:f.request.customerName,customerPhone:'01012345678',region:f.request.region,items:f.request.items,message:f.request.message,consent:true};
  assert.equal(f.cells[1][15],JSON.stringify(original));
  assert.equal(f.cells[1][11],crypto.createHash('sha256').update(JSON.stringify(original)).digest('hex'));
  assert.equal(f.submit(f.request).duplicate,true);
  assert.equal(f.context.listRequests_({afterSequence:0}).requests[0].consent,true);
});
test('rate limit does not block receipt retry',()=>{const f=fixture();for(let i=0;i<5;i++)assert.equal(f.submit({...f.request,requestId:crypto.randomUUID()}).ok,true);assert.equal(f.submit().code,'RATE_LIMITED');const saved=JSON.parse(f.cells[1][15]);assert.equal(f.submit({...saved,website:''}).duplicate,true);});

test('formal quote email and reply preference round trip with immutable receipt',()=>{
  const f=fixture();const {consent,...old}=f.request;
  const request={...old,processingBasis:'quote_request',customerEmail:'buyer@example.com',formalQuoteRequested:true,recipientName:'나무건설',replyChannel:'both'};
  assert.equal(f.submit(request).ok,true); assert.equal(f.submit(request).duplicate,true);
  const row=f.context.listRequests_({afterSequence:0}).requests[0];
  for(const key of ['customerEmail','formalQuoteRequested','recipientName','replyChannel'])assert.equal(row[key],request[key]);
  assert.equal(f.submit({...request,customerEmail:'other@example.com'}).code,'CONFLICT');
  assert.equal(f.cells.length,2);
});
test('email is optional for SMS and invalid email or email delivery without address cannot write',()=>{
  const f=fixture();assert.equal(f.submit({...f.request,customerEmail:'',formalQuoteRequested:true,replyChannel:'sms',recipientName:''}).ok,true);
  for(const change of [{customerEmail:'bad'},{customerEmail:'a@b.com\r\nBcc:c@d.com'},{customerEmail:'',replyChannel:'email'},{customerEmail:'',replyChannel:'both'},{formalQuoteRequested:'yes'},{replyChannel:'fax'}]){
    const g=fixture();assert.equal(g.submit({...g.request,...change}).code,'INVALID'); assert.equal(g.cells.length,1);
  }
});
test('v2 receipt hash and canonical JSON stay unchanged after v3 upgrade',()=>{
  const f=fixture();const {consent,...request}=f.request;request.processingBasis='quote_request';
  const original=f.context.validateRequest_(request); f.submit(request);
  assert.equal(Object.hasOwn(original,'customerEmail'),false);
  assert.equal(f.cells[1][15],JSON.stringify(original));
  assert.equal(f.submit({...original,website:''}).duplicate,true);
});

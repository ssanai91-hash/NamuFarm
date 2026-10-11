const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const crypto = require('node:crypto');
const {fixture}=require('./fixture.cjs');
test('lost receipt and retries create one Sheet row',()=>{const f=fixture();assert.equal(f.submit().ok,true);assert.equal(f.submit().duplicate,true);assert.equal(f.cells.length,2);});
test('same ID with changed quantities conflicts',()=>{const f=fixture();f.submit();assert.equal(f.submit({...f.request,items:[{...f.request.items[0],quantity:13}]}).code,'CONFLICT');assert.equal(f.cells.length,2);});
test('cache failure cannot turn a saved request into an intake error',()=>{const f=fixture();f.failCache();assert.equal(f.submit().ok,true);assert.equal(f.submit().duplicate,true);assert.equal(f.cells.length,2);});
test('Sheet write failure never reports receipt success',()=>{const f=fixture();f.failWrite();assert.equal(f.submit().ok,false);assert.equal(f.cells.length,1);assert.equal(f.submit().ok,true);assert.equal(f.cells[1][1],2);});
test('formula-like strings and leading zero phone round trip from raw JSON',()=>{const f=fixture();f.request.customerName='=김나무';f.request.message="'원본";f.submit();const page=f.readRequests(0);assert.equal(page.requests[0].customerName,'=김나무');assert.equal(page.requests[0].message,"'원본");assert.equal(page.requests[0].customerPhone,'01012345678');});
test('manual Sheet sort does not break the cursor',()=>{const f=fixture();f.submit();f.submit({...f.request,requestId:crypto.randomUUID()});[f.cells[1],f.cells[2]]=[f.cells[2],f.cells[1]];assert.equal(f.readRequests(1).requests[0].sequence,2);});
test('forged credentials cannot submit or list',()=>{const f=fixture();const post=body=>f.context.doPost({postData:{contents:JSON.stringify(body)}});assert.equal(post({action:'submit_request',webSecret:'bad'}).code,'UNAUTHORIZED');assert.equal(post({action:'list_requests',deviceToken:'a'.repeat(64),afterSequence:0}).code,'UNAUTHORIZED');assert.equal(f.cells.length,1);});
test('pairing retry is idempotent and invite cannot be stolen by another token',()=>{const f=fixture();const invite=f.context.createDeviceInvite();const body={invite,deviceToken:'c'.repeat(64),label:'테스트'};assert.equal(f.context.pairDevice_(body).paired,true);assert.equal(f.context.pairDevice_(body).paired,true);assert.throws(()=>f.context.pairDevice_({...body,deviceToken:'d'.repeat(64)}));assert.equal(Object.keys(JSON.parse(f.properties.DEVICES)).length,1);});
test('notification failure preserves receipt for retry and never sends personal details',()=>{const f=fixture();f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({abc:{fcmToken:'test-device'}});f.failPush();assert.equal(f.submit().ok,true);assert.equal(f.cells[1][10],'PENDING');assert.equal(f.sent.length,0);f.context.dispatchQuoteNotifications();assert.deepEqual(Object.keys(f.sent[0].message.data).sort(),['requestId','type']);assert.equal(f.sent[0].message.data.requestId,f.request.requestId);});
test('successful notification marks only the committed request sent',()=>{const f=fixture();f.properties.FIREBASE_PROJECT_ID='test-project';f.properties.DEVICES=JSON.stringify({abc:{fcmToken:'test-device'}});assert.equal(f.submit().ok,true);assert.equal(f.cells[1][10],'PENDING');assert.equal(f.sent.length,0);f.context.dispatchQuoteNotifications();assert.equal(f.cells[1][10],'SENT');f.submit();f.context.dispatchQuoteNotifications();assert.equal(f.sent.length,1);});
test('missing consent, invalid quantities and honeypot are rejected',()=>{for(const change of [{consent:false},{website:'bot'},{items:[{species:'소나무',specification:'',quantity:0}]}]){const f=fixture();assert.equal(f.submit({...f.request,...change}).code,'INVALID');assert.equal(f.cells.length,1)}});
test('quote processing basis needs no consent and survives retry/list without inventing consent',()=>{
  const f=fixture();const {consent,...request}=f.request;
  request.processingBasis='quote_request';
  assert.equal(f.submit(request).ok,true);
  assert.equal(f.submit(request).duplicate,true);
  assert.equal(f.cells.length,2);
  const saved=f.saved();
  assert.equal(saved.processingBasis,'quote_request');
  assert.equal(Object.hasOwn(saved,'consent'),false);
  const listed=f.readRequests(0).requests[0];
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
  assert.deepEqual(f.saved(),JSON.parse(JSON.stringify(original)));
  assert.equal(f.cells[1][3],f.request.customerName);
  assert.equal(f.cells[1][11],crypto.createHmac('sha256',f.properties.WEB_SECRET).update(JSON.stringify(original)).digest('hex'));
  assert.equal(f.submit(f.request).duplicate,true);
  assert.equal(f.readRequests(0).requests[0].consent,true);
});
test('ten requests from one client each get a receipt and retries stay unique',()=>{const f=fixture();const numbers=new Set();for(let i=0;i<10;i++){const result=f.submit({...f.request,requestId:crypto.randomUUID()});assert.equal(result.ok,true);numbers.add(result.receiptNumber);}assert.equal(numbers.size,10);assert.equal(f.cells.length,11);const saved=f.saved();assert.equal(f.submit({...saved,website:''}).duplicate,true);assert.equal(f.cells.length,11);});

test('formal quote email and reply preference round trip with immutable receipt',()=>{
  const f=fixture();const {consent,...old}=f.request;
  const request={...old,processingBasis:'quote_request',customerEmail:'buyer@example.com',formalQuoteRequested:true,recipientName:'나무건설',replyChannel:'both'};
  assert.equal(f.submit(request).ok,true); assert.equal(f.submit(request).duplicate,true);
  const row=f.readRequests(0).requests[0];
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
  assert.deepEqual(f.saved(),JSON.parse(JSON.stringify(original)));
  assert.equal(f.cells[1][3],f.request.customerName);
  assert.equal(f.submit({...original,website:''}).duplicate,true);
});


test('request status flows through 견적확정 and 견적발송완료 while removed 견적완료 is rejected',()=>{
  const f=fixture();f.submit();
  assert.equal(f.context.requestStatusTransitionAllowed_('신규','견적확정'),true);
  assert.equal(f.context.requestStatusTransitionAllowed_('신규','견적발송완료'),false);
  assert.equal(f.context.requestStatusTransitionAllowed_('신규','입금확인'),false);
  assert.throws(()=>f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적완료'}));
  const confirmed=f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
  assert.equal(confirmed.updated,true);assert.equal(confirmed.status,'견적확정');
  const sent=f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적발송완료'});
  assert.equal(sent.updated,true);assert.equal(sent.status,'견적발송완료');
  assert.equal(f.readRequests(0).requests[0].status,'견적발송완료');
  assert.equal(f.context.requestStatusTransitionAllowed_('견적발송완료','입금확인'),true);
});

test('authenticated quote email profile and send use Apps Script mail account',()=>{
  const f=fixture();f.submit();
  f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
  const info=f.context.secureServiceInfo_();
  assert.equal(info.quoteEmail,true);assert.equal(info.quoteEmailSender,'ssanai91@gmail.com');
  const profile=f.context.quoteEmailProfile_();
  assert.equal(profile.senderEmail,'ssanai91@gmail.com');
  const sent=f.context.sendQuoteEmail_({
    requestId:f.request.requestId,recipientEmail:'buyer@example.com',senderName:'나무팜농원',
    quoteNumber:'NAMU-20261011-0001',subject:'[나무팜] 견적 안내',
    bodyText:'안녕하세요. 견적 안내입니다.',
    bodyHtml:'<p>안녕하세요. 견적 안내입니다.</p><table><tr><td>감나무 10주</td></tr></table>'
  });
  assert.equal(sent.sent,true);assert.equal(sent.recipientEmail,'buyer@example.com');
  assert.equal(f.mail.length,1);assert.equal(f.mail[0].to,'buyer@example.com');
  assert.equal(f.mail[0].name,'나무팜농원');
});
test('quote email can attach generated official PDF',()=>{
  const f=fixture();f.submit();
  f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
  const pdf=Buffer.from('%PDF-1.4\nNAMUFARM TEST PDF\n');
  const sent=f.context.sendQuoteEmail_({
    requestId:f.request.requestId,recipientEmail:'buyer@example.com',senderName:'나무팜농원',
    quoteNumber:'NAMU-20261011-0001',subject:'묘목 견적 안내',
    bodyText:'견적 안내입니다.',bodyHtml:'<p>견적 안내입니다.</p>',
    attachmentName:'NAMU-20261011-0001_공식견적서.pdf',
    attachmentBase64:pdf.toString('base64')
  });
  assert.equal(sent.sent,true);
  assert.equal(sent.attachmentName,'NAMU-20261011-0001_공식견적서.pdf');
  assert.equal(f.mail.length,1);
  assert.equal(f.mail[0].attachments.length,1);
  assert.equal(f.mail[0].attachments[0].name,'NAMU-20261011-0001_공식견적서.pdf');
  assert.equal(f.mail[0].attachments[0].mime,'application/pdf');
  assert.equal(Buffer.from(f.mail[0].attachments[0].bytes).toString(),pdf.toString());
});
test('quote email cannot send before quote confirmation',()=>{
  const f=fixture();f.submit();
  assert.throws(()=>f.context.sendQuoteEmail_({
    requestId:f.request.requestId,recipientEmail:'buyer@example.com',senderName:'나무팜농원',
    quoteNumber:'NAMU-20261011-0001',subject:'견적 안내',
    bodyText:'견적 안내입니다.',bodyHtml:'<p>견적 안내입니다.</p>'
  }));
});


test('quote sms profile is available before SOLAPI keys are configured',()=>{
  const f=fixture();
  const info=f.context.secureServiceInfo_();
  assert.equal(info.quoteSms,true);assert.equal(info.quoteSmsProvider,'SOLAPI');
  const profile=f.context.quoteSmsProfile_();
  assert.deepEqual(JSON.parse(JSON.stringify(profile)),{provider:'SOLAPI',configured:false,senderPhone:''});
});

test('confirmed quote sms is accepted by SOLAPI with registered sender settings',()=>{
  const f=fixture();f.submit();
  f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
  f.properties.SOLAPI_KEY='test-api-key';
  f.properties.SOLAPI_SECRET='test-api-secret';
  f.properties.SOLAPI_FROM='010-4446-0304';
  const profile=f.context.quoteSmsProfile_();
  assert.equal(profile.configured,true);assert.equal(profile.senderPhone,'01044460304');
  const result=f.context.sendQuoteSms_({
    requestId:f.request.requestId,recipientPhone:'010-1234-5678',
    text:'김나무님, 요청하신 묘목 견적입니다.\n총금액 40,000원'
  });
  assert.equal(result.sent,true);assert.equal(result.statusCode,'2000');
  assert.equal(result.senderPhone,'01044460304');assert.equal(result.recipientPhone,'01012345678');
  assert.equal(f.sms.length,1);
  assert.equal(f.sms[0].payload.messages[0].from,'01044460304');
  assert.equal(f.sms[0].payload.messages[0].to,'01012345678');
  assert.equal(f.sms[0].payload.messages[0].autoTypeDetect,true);
  assert.match(f.sms[0].headers.Authorization,/^HMAC-SHA256 apiKey=test-api-key,/);
});

test('quote sms refuses sending without SOLAPI credentials',()=>{
  const f=fixture();f.submit();
  f.context.updateRequestStatus_({requestId:f.request.requestId,status:'견적확정'});
  assert.throws(()=>f.context.sendQuoteSms_({
    requestId:f.request.requestId,recipientPhone:'01012345678',text:'견적 안내입니다.'
  }));
});


test('quote sms status reports final success and user-facing reason',()=>{
  const f=fixture();f.properties.SOLAPI_KEY='test-api-key';f.properties.SOLAPI_SECRET='test-api-secret';f.properties.SOLAPI_FROM='01044460304';
  f.setSmsStatus('4000','COMPLETE');
  const result=f.context.quoteSmsStatus_({messageId:'M4V_TEST'});
  assert.equal(result.state,'success');assert.equal(result.final,true);assert.equal(result.success,true);
  assert.equal(result.statusMessage,'문자 전송에 성공했습니다.');
});
test('quote sms status reports carrier failure reason',()=>{
  const f=fixture();f.properties.SOLAPI_KEY='test-api-key';f.properties.SOLAPI_SECRET='test-api-secret';f.properties.SOLAPI_FROM='01044460304';
  f.setSmsStatus('3043','COMPLETE');
  const result=f.context.quoteSmsStatus_({messageId:'M4V_TEST'});
  assert.equal(result.state,'failed');assert.equal(result.final,true);assert.equal(result.success,false);
  assert.equal(result.statusMessage,'수신 단말기 전원이 꺼져 있습니다.');
});

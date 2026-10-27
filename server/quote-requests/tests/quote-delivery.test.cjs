const {test}=require('node:test'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
function setup(){const f=fixture();Object.assign(f.properties,{SOLAPI_KEY:'test-key',SOLAPI_SECRET:'test-secret',SOLAPI_FROM:'01044460304'});return f;}
function message(){return {deliveryId:crypto.randomUUID(),recipientPhone:'01012345678',text:'시험 견적'};}
test('same delivery ID sends once, replays saved acceptance, and stores no body/full contact',()=>{
 const f=setup(),body=message();const first=f.context.sendQuoteSms_(body),again=f.context.sendQuoteSms_(body);
 assert.equal(first.sent,true);assert.equal(again.duplicate,true);assert.equal(again.messageId,first.messageId);assert.equal(f.sms.length,1);
 const journal=JSON.stringify(f.book.get('견적발송기록').cells);assert.ok(!journal.includes(body.text));assert.ok(!journal.includes(body.recipientPhone));
 assert.throws(()=>f.context.sendQuoteSms_({...body,text:'다른 본문'}),error=>error.code==='CONFLICT');assert.equal(f.sms.length,1);
});
test('concurrent SENDING and lost provider response never resubmit',()=>{
 const f=setup(),body=message();let concurrent;f.context.UrlFetchApp.fetch=()=>{concurrent=f.context.sendQuoteSms_(body);throw Error('response lost');};
 const result=f.context.sendQuoteSms_(body);assert.equal(result.state,'unknown');assert.equal(concurrent.state,'unknown');
 assert.equal(f.context.quoteDeliveryStatus_({deliveryId:body.deliveryId}).state,'sending');
 assert.equal(f.context.sendQuoteSms_(body).state,'unknown');
});
test('definite rejection is sticky; malformed success is unknown',()=>{
 const f=setup(),body=message();let calls=0;f.context.UrlFetchApp.fetch=()=>{calls++;return {getResponseCode:()=>400,getContentText:()=>'{bad'};};
 assert.equal(f.context.sendQuoteSms_(body).state,'rejected');assert.equal(f.context.sendQuoteSms_(body).state,'rejected');assert.equal(calls,1);
 const other=message();f.context.UrlFetchApp.fetch=()=>({getResponseCode:()=>200,getContentText:()=>'{bad'});
 assert.equal(f.context.sendQuoteSms_(other).state,'unknown');
});
test('missing delivery ID preserves legacy sends and status action requires authentication',()=>{
 const f=setup(),body=message();delete body.deliveryId;f.context.sendQuoteSms_(body);f.context.sendQuoteSms_(body);assert.equal(f.sms.length,2);
 assert.equal(f.post({action:'quote_delivery_status',deliveryId:crypto.randomUUID()}).code,'UNAUTHORIZED');
 assert.equal(f.context.quoteDeliveryStatus_({deliveryId:crypto.randomUUID()}).found,false);
});
test('mail and report reuse the fence; cleanup removes only rows at least 30 days old',()=>{
 const f=setup();const mail={deliveryId:crypto.randomUUID(),recipientEmail:'buyer@example.com',quoteNumber:'PCQ_TEST',subject:'견적',bodyText:'안내',bodyHtml:'<p>안내</p>'};
 assert.equal(f.context.sendQuoteEmail_(mail).sent,true);assert.equal(f.context.sendQuoteEmail_(mail).duplicate,true);assert.equal(f.mail.length,1);
 const report={deliveryId:crypto.randomUUID(),recipientEmail:'seller@example.com',subject:'매출',bodyText:'매출 안내',attachmentName:'report.xlsx',attachmentBase64:Buffer.from('PK\x03\x04xlsx').toString('base64')};
 f.context.sendSalesReportEmail_(report);f.context.sendSalesReportEmail_(report);assert.equal(f.mail.length,2);
 const rows=f.book.get('견적발송기록').cells;f.setTime(new Date(Date.parse(rows[1][7])+30*86400000).toISOString());f.context.purgeQuoteDeliveries_();assert.equal(rows.length,1);
});

test('post-send journal lock failure remains unknown rather than preflight BUSY',()=>{
 const f=setup(),body=message(),fetch=f.context.UrlFetchApp.fetch;
 f.context.UrlFetchApp.fetch=(...args)=>{const response=fetch(...args);f.failLock();return response;};
 assert.equal(f.context.sendQuoteSms_(body).state,'unknown');assert.equal(f.sms.length,1);
 assert.equal(f.context.sendQuoteSms_(body).state,'unknown');assert.equal(f.sms.length,1);
 assert.equal(f.context.quoteDeliveryStatus_({deliveryId:body.deliveryId}).state,'sending');
});

test('provider failed-message list is rejected before acceptance and never replayed',()=>{
 const f=setup(),body=message();let calls=0;
 f.context.UrlFetchApp.fetch=()=>{calls++;return {getResponseCode:()=>200,getContentText:()=>JSON.stringify({failedMessageList:[{statusCode:'1030'}],messageList:[]})};};
 assert.equal(f.context.sendQuoteSms_(body).state,'rejected');assert.equal(f.context.sendQuoteSms_(body).state,'rejected');assert.equal(calls,1);
});

test('MailApp exception after acceptance is uncertain and never sent again',()=>{
 const f=setup(),body={deliveryId:crypto.randomUUID(),recipientEmail:'buyer@example.com',quoteNumber:'PCQ_TEST',subject:'견적',bodyText:'안내',bodyHtml:'<p>안내</p>'};let calls=0;
 f.context.MailApp.sendEmail=()=>{calls++;throw Error('response lost after mail acceptance');};
 assert.equal(f.context.sendQuoteEmail_(body).state,'unknown');assert.equal(f.context.sendQuoteEmail_(body).state,'unknown');assert.equal(calls,1);
});

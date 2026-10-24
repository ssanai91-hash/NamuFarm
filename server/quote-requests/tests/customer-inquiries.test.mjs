// Isolated Apps Script mocks only. Never contacts Google, SOLAPI, or customers.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

const uuid=n=>`${String(n).padStart(8,'0')}-0000-4000-8000-000000000001`;
function fixture() {
  let now=Date.parse('2026-12-31T14:59:00Z'),failFlush=false,mailThrows=false,mailCount=0,smsCount=0;
  const values=new Map([['WEB_SECRET','w'.repeat(64)],['APP_CONNECTION_KEY','a'.repeat(64)]]),books=new Map(),triggers=[],cache=new Map();
  class Clock extends Date { constructor(...args){super(...(args.length?args:[now]));}static now(){return now;} }
  class Sheet {
    constructor(book,name){this.book=book;this.name=name;this.rows=[];}
    getLastRow(){return this.rows.length;}
    getMaxRows(){return 1000;}
    getParent(){return this.book;}
    getName(){return this.name;}
    setName(name){this.name=name;return this;}
    setFrozenRows(){return this;}
    setColumnWidth(){return this;}
    insertRowsAfter(){}
    getRange(row,col,count=1,width=1){
      const sheet=this;
      return {setNumberFormat(){return this;},setBackground(){return this;},setFontColor(){return this;},setFontWeight(){return this;},
        setValues(rows){for(let i=0;i<rows.length;i++){sheet.rows[row-1+i]??=[];for(let j=0;j<rows[i].length;j++)sheet.rows[row-1+i][col-1+j]=rows[i][j];}return this;},
        getValues(){return Array.from({length:count},(_,i)=>Array.from({length:width},(_,j)=>sheet.rows[row-1+i]?.[col-1+j]??''));}};
    }
  }
  const props={getProperty:key=>values.get(key)??null,setProperty(key,value){values.set(key,String(value));return this;}};
  const sandbox={Date:Clock,console:{log(){}},PropertiesService:{getScriptProperties:()=>props},LockService:{getScriptLock:()=>({tryLock:()=>true,releaseLock(){}})},
    SpreadsheetApp:{create(name){const book={id:'book'+books.size,name,sheets:[],getId(){return this.id;},getUrl(){return 'https://docs.google.com/spreadsheets/d/'+this.id;},getSheets(){return this.sheets;},getSheetByName(name){return this.sheets.find(s=>s.name===name);},insertSheet(name){const s=new Sheet(this,name);this.sheets.push(s);return s;},setSpreadsheetTimeZone(){}};book.insertSheet('Sheet1');books.set(book.id,book);return book;},openById(id){assert.ok(books.has(id));return books.get(id);},flush(){if(failFlush){failFlush=false;throw Error('uncertain flush');}}},
    ScriptApp:{getProjectTriggers:()=>triggers,newTrigger(name){return {timeBased(){return this;},everyDays(){return this;},create(){triggers.push({getHandlerFunction:()=>name});}};}},
    CacheService:{getScriptCache:()=>({get:key=>cache.get(key)??null,put:(key,value)=>cache.set(key,value)})},
    Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(_,s)=>Array.from(createHash('sha256').update(s).digest())},
    ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType(){return JSON.parse(text);}})},
    MailApp:{getRemainingDailyQuota:()=>50,sendEmail(){mailCount++;if(mailThrows)throw Error('provider result unknown');}},
  };
  const context=vm.createContext(sandbox);
  for(const name of ['Code.gs','CustomerInquiries.gs'])vm.runInContext(fs.readFileSync(new URL('../'+name,import.meta.url),'utf8'),context);
  context.quoteSmsSettings_=()=>({configured:true});
  context.sendQuoteSms_=()=>{smsCount++;return {messageId:'M_mock',sentAt:new Clock().toISOString()};};
  context.quoteSmsStatus_=()=>({success:true,state:'success',statusCode:'4000'});
  const input=n=>({requestId:uuid(n),category:'구매·배송',customerName:'테스트고객',customerPhone:'01000000000',customerEmail:'fixture@example.invalid',message:'문의 저장 테스트입니다.',consent:true,website:''});
  const body=n=>({action:'submit_customer_inquiry',webSecret:'w'.repeat(64),protocolVersion:1,requestId:uuid(n),fingerprint:String(n).padStart(64,'0'),rateKey:'f'.repeat(64),inquiry:input(n)});
  return {context,books,values,body,props,advance:time=>{now=Date.parse(time);},failWrite:()=>{failFlush=true;},failMail:()=>{mailThrows=true;},mailCount:()=>mailCount,smsCount:()=>smsCount,post:body=>context.doPost({postData:{contents:JSON.stringify(body)}})};
}
test('owner setup is idempotent; public submit needs web secret; read/reply require app authentication',()=>{
  const f=fixture();f.context.setupCustomerInquiries();f.context.setupCustomerInquiries();
  assert.equal(f.books.size,1);assert.equal([...f.books.values()][0].name,'나무팜_고객문의_2026');
  assert.equal(f.post({...f.body(1),webSecret:'bad'}).code,'UNAUTHORIZED');
  assert.equal(f.post({action:'list_customer_inquiries',afterSequence:0}).code,'UNAUTHORIZED');
  assert.equal(f.post(f.body(1)).ok,true);
  const read=f.post({action:'list_customer_inquiries',connectionKey:'a'.repeat(64),afterSequence:0});
  assert.equal(read.inquiries.length,1);assert.equal(read.inquiries[0].inquiry.message,'문의 저장 테스트입니다.');
});
test('committed-but-unconfirmed retries reuse receipt across Korean New Year; pending records remain queryable',()=>{
  const f=fixture();f.context.setupCustomerInquiries();f.failWrite();
  assert.equal(f.post(f.body(1)).ok,false);
  f.advance('2026-12-31T15:01:00Z');
  const retry=f.post(f.body(1));assert.equal(retry.duplicate,true);assert.match(retry.inquiryNumber,/^INQ-20261231-/);
  const second=f.post(f.body(2));assert.match(second.inquiryNumber,/^INQ-20270101-/);
  assert.equal(f.books.size,2);
  const result=f.context.listCustomerInquiries_({afterSequence:0,limit:1});assert.equal(result.hasMore,true);
  assert.equal(f.context.listCustomerInquiries_({afterSequence:result.nextSequence}).inquiries.length,1);
  assert.equal(f.post({...f.body(1),fingerprint:'e'.repeat(64)}).code,'CONFLICT');
  assert.equal(f.post({...f.body(3),inquiry:{...f.body(3).inquiry,consent:false}}).code,'INVALID');
});
test('draft edits conflict safely; successful email send completes once; uncertain sends never auto-resend',()=>{
  const f=fixture();f.context.setupCustomerInquiries();f.post(f.body(1));
  const draft={requestId:uuid(1),eventId:uuid(10),revision:1,replyText:'답변입니다.',replyChannel:'email'};
  const saved=f.context.saveInquiryReply_(draft);assert.equal(saved.inquiry.status,'open');
  assert.equal(f.context.saveInquiryReply_(draft).duplicate,true);
  assert.throws(()=>f.context.saveInquiryReply_({...draft,eventId:uuid(11)}),e=>e.code==='CONFLICT');
  const send={requestId:uuid(1),deliveryId:uuid(12),revision:saved.inquiry.revision};
  assert.equal(f.context.sendInquiryReply_(send).inquiry.status,'closed');
  assert.equal(f.context.sendInquiryReply_(send).duplicate,true);assert.equal(f.mailCount(),1);
  f.post(f.body(2));const another=f.context.saveInquiryReply_({...draft,requestId:uuid(2),eventId:uuid(20)});
  f.failMail();const uncertain={requestId:uuid(2),deliveryId:uuid(21),revision:another.inquiry.revision};
  assert.equal(f.context.sendInquiryReply_(uncertain).inquiry.delivery.state,'unknown');
  f.context.sendInquiryReply_(uncertain);assert.equal(f.mailCount(),2);
});
test('SMS acceptance stays pending until confirmed; expiry removes personal data and emits delta tombstone',()=>{
  const f=fixture();f.context.setupCustomerInquiries();f.post(f.body(1));f.post(f.body(2));
  const draft=f.context.saveInquiryReply_({requestId:uuid(1),eventId:uuid(30),revision:1,replyText:'문자 답변입니다.',replyChannel:'sms'});
  const sent=f.context.sendInquiryReply_({requestId:uuid(1),deliveryId:uuid(31),revision:draft.inquiry.revision});
  assert.equal(sent.inquiry.status,'open');assert.equal(sent.inquiry.delivery.state,'pending');
  const complete=f.context.inquirySmsStatus_({requestId:uuid(1)});assert.equal(complete.inquiry.status,'closed');assert.equal(f.smsCount(),1);
  f.advance('2027-02-02T00:00:00Z');f.context.purgeExpiredInquiries();
  const updates=f.context.listCustomerInquiries_({afterSequence:complete.inquiry.sequence});assert.equal(updates.inquiries.length,1);
  assert.equal(updates.inquiries[0].expired,true);assert.equal(updates.inquiries[0].inquiry,undefined);
  assert.equal(f.context.listCustomerInquiries_({afterSequence:0}).inquiries.find(i=>i.requestId===uuid(2)).status,'open');
  const rows=[...f.books.values()][0].sheets[0].rows;assert.equal(rows[1][6],'');assert.equal(rows[1][9],'');assert.equal(rows[1][18],'{"expired":true}');
});

test('open/closed status actions preserve reply history, support phone resolution and legacy statuses',()=>{
  const f=fixture();f.context.setupCustomerInquiries();f.post(f.body(1));
  const draft=f.context.saveInquiryReply_({requestId:uuid(1),eventId:uuid(40),revision:1,replyText:'첫 답변',replyChannel:'email'});
  const sent=f.context.sendInquiryReply_({requestId:uuid(1),deliveryId:uuid(41),revision:draft.inquiry.revision});
  assert.equal(sent.inquiry.status,'closed');assert.equal(sent.inquiry.history[0].text,'첫 답변');
  const change={requestId:uuid(1),eventId:uuid(42),revision:sent.inquiry.revision,status:'open',note:'추가 상담'};
  const reopened=f.context.setInquiryStatus_(change);assert.equal(reopened.inquiry.status,'open');
  assert.equal(f.context.setInquiryStatus_(change).duplicate,true);
  const newDraft=f.context.saveInquiryReply_({requestId:uuid(1),eventId:uuid(43),revision:reopened.inquiry.revision,replyText:'두 번째 답변',replyChannel:'sms'});
  assert.equal(newDraft.inquiry.history[0].text,'첫 답변');assert.equal(f.mailCount(),1);
  assert.equal(f.context.sendInquiryReply_({requestId:uuid(1),deliveryId:uuid(41),revision:draft.inquiry.revision}).duplicate,true);
  assert.equal(f.mailCount(),1);
  const phone=f.context.setInquiryStatus_({requestId:uuid(1),eventId:uuid(44),revision:newDraft.inquiry.revision,status:'closed',note:'전화 안내 완료'});
  assert.equal(phone.inquiry.status,'closed');assert.equal(phone.inquiry.history.at(-1).note,'전화 안내 완료');
  assert.equal(f.post({action:'set_inquiry_status',requestId:uuid(1)}).code,'UNAUTHORIZED');
  const row=[...f.books.values()][0].sheets[0].rows[1];row[10]='답변 완료';
  assert.equal(f.context.listCustomerInquiries_({afterSequence:0}).inquiries[0].status,'closed');
});

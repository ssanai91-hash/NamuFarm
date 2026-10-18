const assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),crypto=require('node:crypto');
const pair=crypto.generateKeyPairSync('rsa',{modulusLength:3072});
const publicKey=pair.publicKey.export({type:'spki',format:'der'}).toString('base64');
const keyId=crypto.createHash('sha256').update(publicKey).digest('hex');
function seal(value,kind,reference) {
 const key=crypto.randomBytes(32),iv=crypto.randomBytes(12),aes=crypto.createCipheriv('aes-256-gcm',key,iv);
 aes.setAAD(Buffer.from(`namufarm:v1:${kind}:${reference}`));
 const ciphertext=Buffer.concat([aes.update(JSON.stringify(value),'utf8'),aes.final(),aes.getAuthTag()]);
 return {version:1,algorithm:'RSA-OAEP-256+A256GCM',keyId,wrappedKey:crypto.publicEncrypt({key:pair.publicKey,oaepHash:'sha256'},key).toString('base64'),iv:iv.toString('base64'),ciphertext:ciphertext.toString('base64')};
}
function open(envelope,kind,reference) {
 const key=crypto.privateDecrypt({key:pair.privateKey,oaepHash:'sha256'},Buffer.from(envelope.wrappedKey,'base64'));
 const bytes=Buffer.from(envelope.ciphertext,'base64'),aes=crypto.createDecipheriv('aes-256-gcm',key,Buffer.from(envelope.iv,'base64'));
 aes.setAAD(Buffer.from(`namufarm:v1:${kind}:${reference}`));aes.setAuthTag(bytes.subarray(-16));
 return JSON.parse(Buffer.concat([aes.update(bytes.subarray(0,-16)),aes.final()]).toString('utf8'));
}
function fixture({configure=true,now=Date.now(),monthly=false}={}) {
 const properties={SPREADSHEET_ID:'test',WEB_SECRET:'a'.repeat(64)},books=new Map(),files=new Map(),cache=new Map(),sent=[],mail=[],sms=[],triggers=['dispatchQuoteNotifications'];
 let locked=false,failCache=false,failWrite=false,failPush=false,failReceipt=false,clock=now,nextSheet=0,nextBook=0,createFailure=null,writeFailure=null,lockFailure=false,writeHook=null;
 let smsStatusCode='4000',smsProviderState='COMPLETE';
 const operations=[],reads=[];
 class FixtureDate extends Date { constructor(...args){super(...(args.length?args:[clock]));} static now(){return clock;} }
 function makeBook(id,name) {
  const sheets=new Map();
  const result={id,name,sheets,timeZone:'',getId:()=>id,getUrl:()=>`https://docs.google.com/spreadsheets/d/${id}/edit`,getName:()=>name,getSheets:()=>[...sheets.values()],getSheetByName:n=>sheets.get(n)||null,getSheetById:n=>[...sheets.values()].find(s=>s.getSheetId()===n)||null,setSpreadsheetTimeZone(value){result.timeZone=value;return this},insertSheet(n){
   if(sheets.has(n))throw Error('duplicate sheet name');
   const cells=[],sheetId=nextSheet++;
   const value={cells,maxRows:1000,getMaxRows:()=>value.maxRows,insertRowsAfter(after,count){assert.ok(after<=value.maxRows);value.maxRows+=count;return this},getName:()=>n,getParent:()=>result,getSheetId:()=>sheetId,getLastRow:()=>{let i=cells.length;while(i>0&&(!cells[i-1]||cells[i-1].every(v=>v==='')))i--;return i},getRange(r,c,count=1,width=1){if(typeof r==='number')assert.ok(r+count-1<=value.maxRows,'range exceeds sheet grid');return{
    getValues:()=>{reads.push({bookId:id,sheetName:n,row:r,column:c,count,width});return Array.from({length:count},(_,i)=>Array.from({length:width},(_,j)=>cells[r-1+i]?.[c-1+j]??''))},
    setValues(values){
     const op={bookId:id,sheetName:n,row:r,column:c,values:JSON.parse(JSON.stringify(values))};operations.push(op);
     if(writeHook){const hook=writeHook;writeHook=null;hook(op)}
     const injected=writeFailure&&writeFailure.predicate(op)?writeFailure:null;if(injected)writeFailure=null;
     if(failWrite||failReceipt&&n==='판매변경이력'||injected&&!injected.after){failWrite=false;failReceipt=false;throw Error('sheet failure')}
     values.forEach((row,i)=>{cells[r-1+i]??=[];row.forEach((v,j)=>cells[r-1+i][c-1+j]=v)});
     if(injected)throw Error('write response lost');return this;
    },setValue(value){return this.setValues([[value]])},clearContent(){return this.setValues(Array.from({length:count},()=>Array(width).fill('')))},setNumberFormat(){return this},setBackground(){return this},setFontColor(){return this},setFontWeight(){return this}
   }},setFrozenRows(){return this},setColumnWidth(){return this},hideColumns(){return this},deleteRow(row){cells.splice(row-1,1);return this}};
   sheets.set(n,value);return value;
  }};books.set(id,result);return result;
 }
 const management=makeBook('test','management'),book=management.sheets;
 const props={getProperty:k=>properties[k]??null,setProperty(k,v){properties[k]=v},getProperties:()=>({...properties}),deleteProperty:k=>delete properties[k]};
 const context=vm.createContext({Date:FixtureDate,console:{log(){}},PropertiesService:{getScriptProperties:()=>props},SpreadsheetApp:{openById:id=>{assert.ok(books.has(id),'unknown spreadsheet');return books.get(id)},flush(){}},
 Drive:{Files:{create(resource){
  operations.push({kind:'create',resource});const failure=createFailure;createFailure=null;if(failure&&!failure.after)throw Error('create failed');
  const id='month-'+(++nextBook);files.set(id,{id,...JSON.parse(JSON.stringify(resource))});makeBook(id,resource.name);
  if(failure)throw Error('create response lost');return{id};
 },get:id=>files.get(id),list({q}){const token=/key='namufarmQuoteToken' and value='([^']+)'/.exec(q)[1];return{files:[...files.values()].filter(f=>f.appProperties.namufarmQuoteToken===token).map(f=>({id:f.id}))}}}},
 LockService:{getScriptLock:()=>({tryLock(){if(lockFailure){lockFailure=false;return false}if(locked)return false;locked=true;return true},releaseLock(){locked=false}})},
 CacheService:{getScriptCache:()=>({get:k=>cache.get(k),put(k,v){if(failCache){failCache=false;throw Error('cache failure')};cache.set(k,v)}})},
 Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(_,v)=>[...crypto.createHash('sha256').update(v).digest()],computeHmacSha256Signature:(v,k)=>[...crypto.createHmac('sha256',k).update(v).digest()],getUuid:()=>crypto.randomUUID(),base64Decode:v=>[...Buffer.from(v,'base64')],base64Encode:v=>Buffer.from(v.map(x=>(x+256)%256)).toString('base64'),newBlob:(bytes,mime,name)=>({bytes:[...bytes],mime,name})},
 ScriptApp:{getOAuthToken:()=> 'test-token',getProjectTriggers:()=>triggers.map(name=>({getHandlerFunction:()=>name})),newTrigger(name){return{timeBased(){return this},everyMinutes(){return this},create(){triggers.push(name)}}}},
 MailApp:{getRemainingDailyQuota:()=>100,sendEmail:options=>mail.push(JSON.parse(JSON.stringify(options)))},
 ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType:()=>JSON.parse(text)})},
 UrlFetchApp:{fetch(url,options){assert.equal(locked,false,'Network must not hold submission lock');const payload=options.payload?JSON.parse(options.payload):null;
 if(url==='https://api.solapi.com/messages/v4/send-many/detail'){sms.push({url,payload,headers:{...(options.headers||{})}});return{getResponseCode:()=>200,getContentText:()=>JSON.stringify({groupInfo:{groupId:'G4V_TEST'},failedMessageList:[],messageList:[{messageId:'M4V_TEST',statusCode:'2000',statusMessage:'정상 접수'}]})}}
 if(url.startsWith('https://api.solapi.com/messages/v4/list?')){sms.push({url,payload:null,headers:{...(options.headers||{})}});return{getResponseCode:()=>200,getContentText:()=>JSON.stringify({messageList:{M4V_TEST:{messageId:'M4V_TEST',status:smsProviderState,statusCode:smsStatusCode,log:[]}}})}}
 sent.push(payload);if(failPush)throw Error('offline');
 if(url.endsWith('/api/internal/encrypt')) {const request=context.validateRequest_({...payload.request,website:''});return{getResponseCode:()=>200,getContentText:()=>JSON.stringify({ok:true,encryptedRequest:seal(request,'quote',request.requestId),fingerprint:crypto.createHmac('sha256',properties.WEB_SECRET).update(JSON.stringify(request)).digest('hex')})}}
 return{getResponseCode:()=>200,getContentText:()=> '{}'}}}
 });
 for(const file of ['Code.gs','SecureCommerce.gs','MonthlyStorage.gs','RecoverySetup.gs','AnnualLedgers.gs','TwoTabQuotes.gs','TwoBookCutover.gs'])vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'..',file),'utf8'),context);
 vm.runInContext('setupQuoteRequests()',context);
 if(configure)properties.SELLER_PUBLIC_KEY=JSON.stringify({keyId,publicKey});
 if(monthly){context.setupMonthlyQuoteStorage();context.enableMonthlyQuoteStorage()}
 const request={requestId:crypto.randomUUID(),customerName:'김나무',customerPhone:'010-1234-5678',region:'충북 옥천군',items:[{species:'소나무',specification:'',quantity:12}],message:'봄에 심어요',consent:true,website:''};
 const post=body=>context.doPost({postData:{contents:JSON.stringify(body)}});
 const submit=(req=request)=> {try{const checked=context.validateRequest_(req);return post({action:'submit_request',webSecret:properties.WEB_SECRET,rateKey:'b'.repeat(64),requestId:checked.requestId,fingerprint:crypto.createHmac('sha256',properties.WEB_SECRET).update(JSON.stringify(checked)).digest('hex'),protocolVersion:5,request:{...checked,website:''}})}catch(e){return{ok:false,code:e.code||'INVALID'}}};
 const cells=book.get('웹견적요청').cells;
 const requestRows=()=>[...books.values()].flatMap(b=>[...b.sheets.values()].filter(s=>s.getName()==='웹견적요청'||/^\d{4}-\d{2}-\d{2}$/.test(s.getName())).flatMap(s=>s.cells.slice(1).filter(r=>r&&r[0]).map(row=>({row,sheet:s,book:b}))));
 const saved=()=>{const r=requestRows()[0].row;const raw=JSON.parse(r[15]);return raw.request||open(raw.encryptedRequest,'quote',r[0])};
 const readRequests=after=>{const page=context.listRequests_({afterSequence:after});return{...page,requests:page.requests.map(r=>r.expired||!r.encryptedRequest?r:{...r,...open(r.encryptedRequest,'quote',r.requestId)})}};
 const seedEncrypted=(req=request)=>{const checked=context.validateRequest_(req);return context.commitQuoteRequest_({requestId:checked.requestId,fingerprint:crypto.createHmac('sha256',properties.WEB_SECRET).update(JSON.stringify(checked)).digest('hex')},{encryptedRequest:seal(checked,'quote',checked.requestId)})};
 return{seedEncrypted,context,cells,properties,request,submit,post,sent,mail,sms,book,books,files,operations,reads,requestRows,publicKey,keyId,seal,open,saved,readRequests,
 setTime:value=>clock=Date.parse(value),getTimeZone:()=>management.timeZone,clearRate:()=>cache.clear(),
 failCache:()=>failCache=true,failWrite:()=>failWrite=true,failPush:()=>failPush=true,failReceipt:()=>failReceipt=true,
 failNextWrite:(predicate,{after=false}={})=>writeFailure={predicate,after},failCreate:({after=false}={})=>createFailure={after},failLock:()=>lockFailure=true,onNextWrite:hook=>writeHook=hook,
 setSmsStatus:(code,state='COMPLETE')=>{smsStatusCode=String(code);smsProviderState=state;}};
}
module.exports={fixture,seal,open,publicKey,keyId,pair};




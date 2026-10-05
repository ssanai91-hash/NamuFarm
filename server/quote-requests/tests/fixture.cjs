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
function fixture({configure=true}={}) {
 const properties={SPREADSHEET_ID:'test',WEB_SECRET:'a'.repeat(64)},book=new Map(),cache=new Map(),sent=[],triggers=['dispatchQuoteNotifications'];
 let locked=false,failCache=false,failWrite=false,failPush=false,failReceipt=false;
 function sheet(name) {if(book.has(name))return book.get(name);const cells=[];
 const value={cells,getLastRow:()=>cells.length,getRange(r,c,n=1,m=1){return{
  getValues:()=>Array.from({length:n},(_,i)=>Array.from({length:m},(_,j)=>cells[r-1+i]?.[c-1+j]??'')),
  setValues(values){if(failWrite||failReceipt && name==='판매변경이력'){failWrite=false;failReceipt=false;throw Error('sheet failure')};values.forEach((row,i)=>{cells[r-1+i]??=[];row.forEach((v,j)=>cells[r-1+i][c-1+j]=v)});return this},
  setNumberFormat(){return this},setBackground(){return this},setFontColor(){return this},setFontWeight(){return this}
 }},setFrozenRows(){},setColumnWidth(){}};book.set(name,value);return value}
 const props={getProperty:k=>properties[k]??null,setProperty(k,v){properties[k]=v},getProperties:()=>({...properties}),deleteProperty:k=>delete properties[k]};
 const context=vm.createContext({console:{log(){}},PropertiesService:{getScriptProperties:()=>props},SpreadsheetApp:{openById:()=>({getSheetByName:name=>book.get(name),insertSheet:sheet}),flush(){}},
 LockService:{getScriptLock:()=>({tryLock(){assert.equal(locked,false);locked=true;return true},releaseLock(){locked=false}})},
 CacheService:{getScriptCache:()=>({get:k=>cache.get(k),put(k,v){if(failCache){failCache=false;throw Error('cache failure')};cache.set(k,v)}})},
 Utilities:{DigestAlgorithm:{SHA_256:'sha256'},Charset:{UTF_8:'utf8'},computeDigest:(_,v)=>[...crypto.createHash('sha256').update(v).digest()],getUuid:()=>crypto.randomUUID(),base64Decode:v=>[...Buffer.from(v,'base64')],base64Encode:v=>Buffer.from(v.map(x=>(x+256)%256)).toString('base64')},
 ScriptApp:{getOAuthToken:()=> 'test-token',getProjectTriggers:()=>triggers.map(name=>({getHandlerFunction:()=>name})),newTrigger(name){return{timeBased(){return this},everyMinutes(){return this},create(){triggers.push(name)}}}},
 ContentService:{MimeType:{JSON:'json'},createTextOutput:text=>({setMimeType:()=>JSON.parse(text)})},
 UrlFetchApp:{fetch(url,options){assert.equal(locked,false,'Network must not hold submission lock');const payload=JSON.parse(options.payload);sent.push(payload);if(failPush)throw Error('offline');
 if(url.endsWith('/api/internal/encrypt')) {const request=context.validateRequest_({...payload.request,website:''});return{getResponseCode:()=>200,getContentText:()=>JSON.stringify({ok:true,encryptedRequest:seal(request,'quote',request.requestId),fingerprint:crypto.createHmac('sha256',properties.WEB_SECRET).update(JSON.stringify(request)).digest('hex')})}}
 return{getResponseCode:()=>200,getContentText:()=> '{}'}}}
 });
 for(const file of ['Code.gs','SecureCommerce.gs'])vm.runInContext(fs.readFileSync(require('node:path').join(__dirname,'..',file),'utf8'),context);
 vm.runInContext('setupQuoteRequests()',context);
 if(configure)context.configurePrivacy_({publicKey});
 const request={requestId:crypto.randomUUID(),customerName:'김나무',customerPhone:'010-1234-5678',region:'충북 옥천군',items:[{species:'소나무',specification:'',quantity:12}],message:'봄에 심어요',consent:true,website:''};
 const post=body=>context.doPost({postData:{contents:JSON.stringify(body)}});
 const submit=(req=request)=> {try{const checked=context.validateRequest_(req);return post({action:'submit_request',webSecret:properties.WEB_SECRET,rateKey:'b'.repeat(64),requestId:checked.requestId,fingerprint:crypto.createHmac('sha256',properties.WEB_SECRET).update(JSON.stringify(checked)).digest('hex'),encryptedRequest:seal(checked,'quote',checked.requestId)})}catch(e){return{ok:false,code:e.code||'INVALID'}}};
 const cells=book.get('웹견적요청').cells;
 const saved=()=>open(JSON.parse(cells[1][15]).encryptedRequest,'quote',cells[1][0]);
 const readRequests=after=>{const page=context.listRequests_({afterSequence:after});return{...page,requests:page.requests.map(r=>r.expired?r:{...r,...open(r.encryptedRequest,'quote',r.requestId)})}};
 return{context,cells,properties,request,submit,post,sent,book,publicKey,keyId,seal,open,saved,readRequests,failCache:()=>failCache=true,failWrite:()=>failWrite=true,failPush:()=>failPush=true,failReceipt:()=>failReceipt=true};
}
module.exports={fixture,seal,open,publicKey,keyId,pair};

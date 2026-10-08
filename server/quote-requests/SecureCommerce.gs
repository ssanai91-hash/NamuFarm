/** API v5: authenticated customer records; v4 envelopes remain readable for migration. */
const SALE_SHEET = '판매기록';
const SALE_HEADERS = ['주문번호','변경순번','상태','구매시각(UTC)','판매완료(UTC)','수량','매출(세액제외)','세액','원가','비용','원가확인','수익방식','수수료비율(bp)','매출이익','보관기한(UTC)','공개판매JSON','고객정보JSON','내용해시','최종변경번호','revision'];
const SALE_EVENT_SHEET = '판매변경이력';
const SALE_EVENT_HEADERS = ['변경번호','주문번호','revision','내용해시','처리시각(UTC)'];
const UUID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const QUOTE_RETENTION_MS = 30*86400000;
function sellerKey_() {
  const raw = PropertiesService.getScriptProperties().getProperty('SELLER_PUBLIC_KEY');
  return raw ? JSON.parse(raw) : null;
}
function secureServiceInfo_() {
  const key = sellerKey_();
  let ready = false;
  try { ready = PropertiesService.getScriptProperties().getProperty('PRIVACY_SETUP') === '1' && quoteStorageReady_();
    sheet_(); namedSheet_(SALE_SHEET,SALE_HEADERS,false);
    ready=ready && rows_(sheet_()).every(row=>{const raw=JSON.parse(row[15]);return raw.expired===true || !!raw.request || !!raw.encryptedRequest;}); } catch (_) {}
  return {ok:true,service:'namufarm-quote-requests',version:5,connectionMode:'device-auth',customerDataReady:ready,
    processingBases:['quote_request'],requestFields:['customerEmail','formalQuoteRequested','recipientName','replyChannel'],
    encryptionReady:ready && !!key,sellerKey:key,retentionDays:30,requestDeletionSync:true,
    receiptFormat:'NFQ-YYYYMMDD-NNNN',receiptTimeZone:'Asia/Seoul',
    commerceStates:['구매확정','입금확인','배송확인','판매완료','주문취소']};
}
function byteString_(value, minimum, maximum) {
  assert_(typeof value === 'string' && /^[A-Za-z0-9+/]+={0,2}$/.test(value) && value.length <= maximum*2);
  const bytes = Utilities.base64Decode(value); assert_(bytes.length >= minimum && bytes.length <= maximum);
  assert_(Utilities.base64Encode(bytes) === value); return value;
}
function envelope_(value, key) {
  assert_(value && Object.keys(value).sort().join(',') === 'algorithm,ciphertext,iv,keyId,version,wrappedKey');
  assert_(value.version === 1 && value.algorithm === 'RSA-OAEP-256+A256GCM' && key && value.keyId === key.keyId, 'NOT_READY');
  return {version:1,algorithm:value.algorithm,keyId:value.keyId,
    wrappedKey:byteString_(value.wrappedKey,384,384),iv:byteString_(value.iv,12,12),ciphertext:byteString_(value.ciphertext,17,30016)};
}
function namedSheet_(name, headers, create) {
  const props = PropertiesService.getScriptProperties(); const id = props.getProperty('SPREADSHEET_ID'); assert_(id,'NOT_READY');
  const book = SpreadsheetApp.openById(id); let sheet = book.getSheetByName(name);
  if (!sheet && create) sheet = book.insertSheet(name);
  assert_(sheet,'NOT_READY');
  if (create && sheet.getLastRow() === 0) { sheet.getRange(1,1,1,headers.length).setValues([headers]); sheet.setFrozenRows(1); }
  const actual=sheet.getRange(1,1,1,headers.length).getValues()[0];
  // Old deployed sheets keep their identity and data; only the known header is renamed on owner setup.
  const legacy=headers===SALE_HEADERS && actual[16]==='암호화고객JSON' && JSON.stringify(actual.map((v,i)=>i===16?'고객정보JSON':v))===JSON.stringify(headers);
  assert_(legacy || JSON.stringify(actual)===JSON.stringify(headers),'NOT_READY');
  if(create && legacy)sheet.getRange(1,17,1,1).setValues([['고객정보JSON']]);
  return sheet;
}
function namedRows_(sheet, count) { return sheet.getLastRow() > 1 ? sheet.getRange(2,1,sheet.getLastRow()-1,count).getValues() : []; }
function setupPrivacyStorage_() {
    namedSheet_(SALE_SHEET,SALE_HEADERS,true); namedSheet_(SALE_EVENT_SHEET,SALE_EVENT_HEADERS,true);
    if (!ScriptApp.getProjectTriggers().some(t => t.getHandlerFunction() === 'purgeExpiredCustomerData')) {
      ScriptApp.newTrigger('purgeExpiredCustomerData').timeBased().everyMinutes(5).create();
    }
    PropertiesService.getScriptProperties().setProperty('PRIVACY_SETUP','1');
}
function configurePrivacy_(body) {
  byteString_(body.publicKey,422,422);
  const bytes=Utilities.base64Decode(body.publicKey).map(b=>(b+256)%256);
  const hex=bytes.map(b=>b.toString(16).padStart(2,'0')).join('');
  assert_(hex.startsWith('308201a2300d06092a864886f70d01010105000382018f003082018a0282018100') && bytes[33]>=128 && hex.endsWith('0203010001')); 
  const id = hash_(body.publicKey);
  lock_(() => {
    const existing = sellerKey_(); assert_(!existing || existing.publicKey === body.publicKey,'CONFLICT');
    PropertiesService.getScriptProperties().setProperty('SELLER_PUBLIC_KEY',JSON.stringify({keyId:id,publicKey:body.publicKey}));
    setupPrivacyStorage_();
  });
  // Convert legacy cells only after a successful encryption response and rechecking the original under lock.
  migrateLegacyQuoteRequests();
  return {keyRegistered:true,keyId:id,encryptionReady:secureServiceInfo_().encryptionReady};
}
function migrateLegacyQuoteRequests() {
  assert_(sellerKey_(),'NOT_READY');
  const pending = rows_(sheet_()).filter(row => { const raw = JSON.parse(row[15]); return !raw.expired && !raw.encryptedRequest && !raw.request; }).slice(0,20);
  pending.forEach(row => {
    const request = validateRequest_(Object.assign({},JSON.parse(row[15]),{website:''}));
    const props = PropertiesService.getScriptProperties();
    const url = props.getProperty('ENCRYPTION_BRIDGE_URL') || 'https://namufarm.co.kr/api/internal/encrypt';
    assert_(/^https:\/\/namufarm\.co\.kr\/api\/internal\/encrypt$/.test(url),'NOT_READY');
    const response = UrlFetchApp.fetch(url,{method:'post',contentType:'application/json',muteHttpExceptions:true,
      headers:{Authorization:'Bearer '+props.getProperty('WEB_SECRET')},payload:JSON.stringify({request:request})});
    assert_(response.getResponseCode() === 200,'NOT_READY');
    const result = JSON.parse(response.getContentText()); assert_(result.ok === true && /^[a-f0-9]{64}$/.test(result.fingerprint),'NOT_READY');
    const envelope = envelope_(result.encryptedRequest,sellerKey_());
    lock_(() => {
      const sheet = sheet_(); const all = rows_(sheet); const index = all.findIndex(r=>r[0]===row[0]);
      assert_(index >= 0 && all[index][15] === row[15],'CONFLICT');
      const updated = all[index].slice(); for (let c=3;c<=8;c++) updated[c]='';
      updated[11]=result.fingerprint; updated[15]=JSON.stringify({encryptedRequest:envelope});
      sheet.getRange(index+2,1,1,16).setValues([updated]); SpreadsheetApp.flush();
    });
  });
  return pending.length;
}
function submitCustomerRequest_(body) {
  assert_(secureServiceInfo_().customerDataReady,'NOT_READY');
  assert_(body.protocolVersion===5,'UPGRADE_REQUIRED');
  assert_(UUID_PATTERN.test(body.requestId) && /^[a-f0-9]{64}$/.test(body.fingerprint) && /^[a-f0-9]{64}$/.test(body.rateKey));
  assert_(body.encryptedRequest===undefined);
  const request=validateRequest_(body.request);
  assert_(request.requestId===body.requestId);
  return commitQuoteRequest_(body,{request:request});
}
function quoteCustomerCells_(stored) {
  const r=stored.request;
  return r ? [r.customerName,r.customerPhone,r.region,r.items.map(i=>i.species+' '+i.quantity+'주').join(' · '),JSON.stringify(r.items),r.message].map(cell_) : ['','','','','',''];
}
function quoteStoredData_(raw) {
  assert_(raw && !raw.expired && (!!raw.request !== !!raw.encryptedRequest),'NOT_READY');
  if(raw.request){
    const request=validateRequest_(Object.assign({},raw.request,{website:''}));
    assert_(canonical_(request)===canonical_(raw.request),'NOT_READY');
    return {request:request};
  }
  return {encryptedRequest:envelope_(raw.encryptedRequest,sellerKey_())};
}
function submitEncryptedRequest_(body) {
  assert_(secureServiceInfo_().encryptionReady,'NOT_READY');
  assert_(UUID_PATTERN.test(body.requestId) && /^[a-f0-9]{64}$/.test(body.fingerprint) && /^[a-f0-9]{64}$/.test(body.rateKey));
  assert_(!body.request && !body.customerName && !body.customerPhone,'INVALID');
  const encrypted = envelope_(body.encryptedRequest,sellerKey_());
  return commitQuoteRequest_(body,{encryptedRequest:encrypted});
}
function commitQuoteRequest_(body,stored) {
  return lock_(() => {
    if(quoteStorageConfigured_()) return submitIndexedQuote_(body,stored);
    const sheet = sheet_(); const rows = receiptRows_(sheet); const existingIndex = rows.findIndex(row=>row[0]===body.requestId);
    assert_(receiptColumnsReady_(sheet),'NOT_READY');
    if (existingIndex>=0) { const existing=rows[existingIndex]; assert_(existing[11]===body.fingerprint,'CONFLICT');
      return Object.assign(assignReceipt_(sheet,existing,existingIndex,rows),{duplicate:true}); }
    const cache = CacheService.getScriptCache(); const cacheKey = 'rfq-rate:'+body.rateKey; const now = Date.now();
    const prior = JSON.parse(cache.get(cacheKey) || '{"count":0,"until":0}'); const bucket = prior.until>now ? prior : {count:0,until:now+600000};
    assert_(bucket.count<5,'RATE_LIMITED');
    const props=PropertiesService.getScriptProperties(); const seq=rows.reduce((m,r)=>Math.max(m,Number(r[1])||0),Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
    assert_(Number.isSafeInteger(seq),'NOT_READY'); props.setProperty('LAST_SEQUENCE',String(seq));
    const createdAt=new Date(now).toISOString(),day=receiptDay_(createdAt);
    const floor=rows.reduce((max,r)=>r[16] && receiptDay_(r[2])===day ? Math.max(max,receiptCounter_(r)) : max,0);
    const receipt=reserveReceipt_(props,day,floor);
    const row=[body.requestId,seq,createdAt,...quoteCustomerCells_(stored),'신규','PENDING',body.fingerprint,0,createdAt,'',JSON.stringify(stored),receipt.number,receiptTime_(createdAt)];
    sheet.getRange(sheet.getLastRow()+1,1,1,18).setNumberFormat('@').setValues([row]); SpreadsheetApp.flush();
    cache.put(cacheKey,JSON.stringify({count:bucket.count+1,until:bucket.until}),Math.max(1,Math.ceil((bucket.until-now)/1000)));
    return Object.assign(receiptDetails_(row),{duplicate:false});
  });
}
function quoteResponseRow_(row) {
  const expires=Date.parse(String(row[2]))+QUOTE_RETENTION_MS,raw=JSON.parse(row[15]);
  const result={requestId:String(row[0]),sequence:Number(row[1]),createdAt:String(row[2]),expiresAt:expires};
  if(row[16])Object.assign(result,receiptDetails_(row));
  if(raw.expired===true || expires<=Date.now())return Object.assign(result,{expired:true});
  const stored=quoteStoredData_(raw);
  if(stored.request)assert_(stored.request.requestId===result.requestId,'NOT_READY');
  return Object.assign(result,stored.request || stored);
}
function listEncryptedRequests_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0);
  if(quoteStorageConfigured_())return lock_(()=>{
    const matches=indexedQuoteRecords_(body.afterSequence,true);
    const requests=matches.slice(0,100).map(({entry,cache})=>quoteResponseRow_(quoteRecord_(entry,cache,false).row));
    return {requests:requests,hasMore:matches.length>requests.length};
  });
  const rows=receiptRows_(sheet_()).filter(r=>Number(r[1])>body.afterSequence).sort((a,b)=>Number(a[1])-Number(b[1]));
  return {requests:rows.slice(0,100).map(quoteResponseRow_),hasMore:rows.length>100};
}
function closeRequest_(body) {
  assert_(UUID_PATTERN.test(body.requestId));
  return lock_(()=>{if(quoteStorageConfigured_()){closeIndexedQuote_(body.requestId);return {closed:true,requestId:body.requestId};}
    const sheet=sheet_(); const rows=rows_(sheet); const i=rows.findIndex(r=>r[0]===body.requestId);
    if (i>=0) expireQuoteRow_(sheet,rows[i],i); return {closed:true,requestId:body.requestId};});
}
function expireQuoteRow_(sheet,row,index) {
  // An already imported phone only asks for sequences beyond its committed cursor.
  // Publish disposal as a new change once; retries must not keep moving the cursor.
  if(JSON.parse(row[15]).expired===true)return;
  const props=PropertiesService.getScriptProperties();
  const sequence=rows_(sheet).reduce((m,r)=>Math.max(m,Number(r[1])||0),Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
  assert_(Number.isSafeInteger(sequence),'NOT_READY');
  props.setProperty('LAST_SEQUENCE',String(sequence));
  const next=row.slice(); for(let c=3;c<=8;c++)next[c]=''; next[9]='보관종료'; next[10]='STOPPED'; next[14]=''; next[15]='{"expired":true}';
  next[1]=sequence;
  sheet.getRange(index+2,1,1,16).setValues([next]); SpreadsheetApp.flush();
}
function saleMetadata_(raw) {
  const fields=['orderNumber','orderDate','status','revision','totalQty','totalPrice','isPrepaid','shippingFee','purchasedAt','paidAt','deliveredAt','completedAt','updatedAt','costTotal','expenseTotal','costConfirmed','profitMode','commissionBps','taxAmount','items'];
  assert_(raw && Object.keys(raw).sort().join(',')===fields.sort().join(','));
  assert_(typeof raw.orderNumber==='string' && /^[A-Za-z0-9_-]{1,120}$/.test(raw.orderNumber));
  assert_(typeof raw.orderDate==='string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(raw.orderDate));
  assert_(['구매확정','입금확인','배송확인','판매완료','주문취소'].includes(raw.status));
  ['revision','totalQty','totalPrice','shippingFee','purchasedAt','paidAt','deliveredAt','completedAt','updatedAt','costTotal','expenseTotal','commissionBps','taxAmount'].forEach(k=>assert_(Number.isSafeInteger(raw[k]) && raw[k]>=0));
  assert_(raw.revision>0 && raw.totalQty>0 && raw.totalPrice<=2147483647 && raw.shippingFee<=2147483647 && Number.isSafeInteger(raw.totalPrice+raw.taxAmount));
  assert_(Number.isSafeInteger(raw.costTotal+raw.expenseTotal));
  assert_(typeof raw.isPrepaid==='boolean' && typeof raw.costConfirmed==='boolean' && ['resale','commission'].includes(raw.profitMode) && raw.commissionBps<=10000);
  assert_(raw.purchasedAt>0 && raw.updatedAt>=raw.purchasedAt && raw.updatedAt<=Date.now()+300000);
  assert_(Array.isArray(raw.items) && raw.items.length>0 && raw.items.length<=100);
  let qty=0,amount=raw.isPrepaid?raw.shippingFee:0;
  raw.items.forEach(i=>{assert_(i && Object.keys(i).sort().join(',')==='quantity,species,unitPrice');
    text_(i.species,200,1); assert_(Number.isSafeInteger(i.quantity) && i.quantity>0 && Number.isSafeInteger(i.unitPrice) && i.unitPrice>=0);
    qty+=i.quantity; amount+=i.quantity*i.unitPrice; assert_(Number.isSafeInteger(qty) && Number.isSafeInteger(amount));});
  assert_(raw.totalQty===qty && raw.totalPrice===amount);
  const rank=['구매확정','입금확인','배송확인','판매완료'].indexOf(raw.status);
  if(rank>=1)assert_(raw.paidAt>=raw.purchasedAt && raw.paidAt<=raw.updatedAt);
  if(rank>=2)assert_(raw.deliveredAt>=raw.paidAt && raw.deliveredAt<=raw.updatedAt);
  if(rank>=3)assert_(raw.completedAt>=raw.deliveredAt && raw.completedAt<=raw.updatedAt && raw.costConfirmed);
  return raw;
}
function profit_(meta) {
  if(!meta.costConfirmed)return null;
  const product=meta.totalPrice*meta.commissionBps; assert_(Number.isSafeInteger(product));
  const income=meta.profitMode==='commission'?Math.floor(product/10000):meta.totalPrice;
  const value=income-(meta.profitMode==='commission'?0:meta.costTotal)-meta.expenseTotal;
  assert_(Number.isSafeInteger(value)); return value;
}
function retentionUntil_(meta) {
  const date=new Date(meta.status==='주문취소'?meta.updatedAt:(meta.completedAt||meta.purchasedAt)); date.setUTCFullYear(date.getUTCFullYear()+5); return date.toISOString();
}
function canonical_(value) {
  const sorted = v => Array.isArray(v) ? v.map(sorted) : v && typeof v==='object' ? Object.keys(v).sort().reduce((out,k)=>{out[k]=sorted(v[k]);return out;},{}) : v;
  return JSON.stringify(sorted(value)).replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029');
}
function normalizedPersonal_(value) {
  const copy=JSON.parse(JSON.stringify(value));
  if(copy.order)delete copy.order.orderId;
  if(copy.quoteDocumentJson===null)delete copy.quoteDocumentJson;
  return copy;
}
function saleDigest_(meta,personal,plain) { return hash_(canonical_(plain?{metadata:meta,personalData:normalizedPersonal_(personal)}:{metadata:meta,encryptedPersonal:personal})); }
function salePersonal_(value,meta) {
  assert_(value && typeof value==='object' && Object.keys(value).every(k=>['order','quoteDocumentJson'].includes(k)));
  const order=value.order;assert_(order && typeof order==='object' && !Array.isArray(order));
  Object.keys(meta).filter(k=>k!=='items').forEach(k=>assert_(canonical_(order[k])===canonical_(meta[k])));
  const limits={customerName:100,customerPhone:30,customerEmail:254,receiverName:100,zipNo:10,roadAddress:300,detailAddress:300,note:3000,itemSummary:20000,sourceQuoteNumber:120,sourceRequestId:36};
  Object.keys(limits).forEach(k=>assert_(typeof order[k]==='string' && order[k].length<=limits[k] && !/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(order[k])));
  assert_(/^0[0-9]{8,10}$/.test(order.customerPhone.replace(/[\s()-]/g,'')));
  assert_(!order.customerEmail || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(order.customerEmail));
  assert_(!order.sourceRequestId || UUID_PATTERN.test(order.sourceRequestId));
  assert_(order.itemSummary.length>0 && (!value.quoteDocumentJson || typeof value.quoteDocumentJson==='string' && value.quoteDocumentJson.length<=30000));
  const items=order.itemSummary.split(/\r?\n/).filter(line=>line.trim()).map(line=>{
    const fields=line.split('|').map(s=>s.trim());assert_([7,9,10].includes(fields.length));
    const q=fields.length===7?3:5,p=fields.length===7?4:6;
    assert_(/^[0-9]+$/.test(fields[q]) && /^[0-9]+$/.test(fields[p]));
    return {species:fields[0],quantity:Number(fields[q]),unitPrice:Number(fields[p])};
  });
  assert_(canonical_(items)===canonical_(meta.items));
  assert_(JSON.stringify(value).length<=40000);
  if(value.quoteDocumentJson){const doc=JSON.parse(value.quoteDocumentJson);assert_(doc.sourceQuoteNumber===order.sourceQuoteNumber && doc.customerPhone===order.customerPhone && doc.supplyAmount===meta.totalPrice && doc.taxAmount===meta.taxAmount);}
  return normalizedPersonal_(value);
}
function saveSale_(body) {
  assert_(UUID_PATTERN.test(body.eventId)); const meta=saleMetadata_(body.metadata);
  const plain=body.personalData!==undefined;
  assert_(plain ? body.encryptedPersonal===undefined && body.protocolVersion===5 : body.encryptedPersonal!==undefined);
  const personal=plain?salePersonal_(body.personalData,meta):envelope_(body.encryptedPersonal,sellerKey_());
  const stored=plain?{personalData:personal}:personal;
  if(plain)assert_(personal.order.sourceRequestId===body.sourceRequestId);
  const digest=saleDigest_(meta,personal,plain); assert_(body.fingerprint===digest);
  assert_(body.sourceRequestId==='' || UUID_PATTERN.test(body.sourceRequestId));
  return lock_(()=>{
    const sheet=namedSheet_(SALE_SHEET,SALE_HEADERS,false), events=namedSheet_(SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    const receipts=namedRows_(events,5); const receipt=receipts.find(r=>r[0]===body.eventId);
    if(receipt){assert_(receipt[1]===meta.orderNumber && Number(receipt[2])===meta.revision && receipt[3]===digest,'CONFLICT'); closeLinkedRequest_(body.sourceRequestId); return {saved:true,orderNumber:meta.orderNumber,revision:meta.revision,fingerprint:digest};}
    const rows=namedRows_(sheet,20); const i=rows.findIndex(r=>r[0]===meta.orderNumber || r[0]===hash_(PropertiesService.getScriptProperties().getProperty('WEB_SECRET')+'expired-sale:'+meta.orderNumber));
    const previous=i<0?null:JSON.parse(rows[i][15]);
    // Before accepting a newer revision, repair the previous write's missing receipt.
    // This keeps an earlier retry verifiable even if another device has since advanced the order.
    if(i>=0 && !previous.archived && rows[i][18] && !receipts.some(r=>r[0]===rows[i][18])) {
      events.getRange(events.getLastRow()+1,1,1,5).setNumberFormat('@').setValues([[rows[i][18],rows[i][0],Number(rows[i][19]),rows[i][17],new Date().toISOString()]]); SpreadsheetApp.flush();
    }
    // Repair receipt loss after the main row committed; never rewrite stock or count a second sale.
    const same=i>=0 && rows[i][18]===body.eventId && rows[i][17]===digest && Number(rows[i][19])===meta.revision;
    if(!same){
      if(previous){
        assert_(!previous.archived && meta.revision===previous.revision+1,'CONFLICT');
        assert_(previous.status!=='판매완료' && previous.status!=='주문취소','CONFLICT');
        const next={'구매확정':'입금확인','입금확인':'배송확인','배송확인':'판매완료'};
        assert_(meta.status===previous.status || meta.status===next[previous.status] || (meta.status==='주문취소' && ['구매확정','입금확인'].includes(previous.status)),'CONFLICT');
        ['orderNumber','orderDate','totalQty','totalPrice','isPrepaid','shippingFee','purchasedAt','taxAmount','items'].forEach(k=>assert_(canonical_(meta[k])===canonical_(previous[k]),'CONFLICT'));
        assert_(meta.updatedAt>=previous.updatedAt,'CONFLICT');
        ['paidAt','deliveredAt','completedAt'].forEach(k=>{if(previous[k]>0)assert_(previous[k]===meta[k],'CONFLICT');});
      }else assert_(meta.revision===1 && ['구매확정','입금확인','주문취소'].includes(meta.status),'CONFLICT');
      const props=PropertiesService.getScriptProperties(); const seq=rows.reduce((m,r)=>Math.max(m,Number(r[1])||0),Number(props.getProperty('SALE_SEQUENCE'))||0)+1;
      assert_(Number.isSafeInteger(seq),'NOT_READY'); props.setProperty('SALE_SEQUENCE',String(seq));
      const row=[meta.orderNumber,seq,meta.status,new Date(meta.purchasedAt).toISOString(),meta.completedAt?new Date(meta.completedAt).toISOString():'',meta.totalQty,meta.totalPrice,meta.taxAmount,meta.costTotal,meta.expenseTotal,meta.costConfirmed,meta.profitMode,meta.commissionBps,profit_(meta),retentionUntil_(meta),JSON.stringify(meta),JSON.stringify(stored),digest,body.eventId,meta.revision];
      sheet.getRange(i<0?sheet.getLastRow()+1:i+2,1,1,20).setNumberFormat('@').setValues([row]); SpreadsheetApp.flush();
    }
    if(!namedRows_(events,5).some(r=>r[0]===body.eventId)) {
      events.getRange(events.getLastRow()+1,1,1,5).setNumberFormat('@').setValues([[body.eventId,meta.orderNumber,meta.revision,digest,new Date().toISOString()]]); SpreadsheetApp.flush();
    }
    closeLinkedRequest_(body.sourceRequestId);
    return {saved:true,orderNumber:meta.orderNumber,revision:meta.revision,fingerprint:digest};
  });
}
function closeLinkedRequest_(id) {if(!id)return;if(quoteStorageConfigured_()){closeIndexedQuote_(id);return;}const sheet=sheet_(),rows=rows_(sheet);const q=rows.findIndex(r=>r[0]===id);if(q>=0 && JSON.parse(rows[q][15]).expired!==true)expireQuoteRow_(sheet,rows[q],q);}
function listSales_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0);
  const rows=namedRows_(namedSheet_(SALE_SHEET,SALE_HEADERS,false),20).filter(r=>Number(r[1])>body.afterSequence).sort((a,b)=>Number(a[1])-Number(b[1]));
  const page=rows.slice(0,100).map(r=>{
    const meta=JSON.parse(r[15]); if(meta.archived)return {sequence:Number(r[1]),expired:true};
    const stored=JSON.parse(r[16]),plain=stored.personalData!==undefined;
    const personal=plain?salePersonal_(stored.personalData,meta):envelope_(stored,sellerKey_());
    assert_(saleDigest_(meta,personal,plain)===r[17],'NOT_READY');
    return Object.assign({sequence:Number(r[1]),eventId:r[18],metadata:saleMetadata_(meta),fingerprint:r[17],expired:false},plain?{personalData:personal}:{encryptedPersonal:personal});
  }); return {sales:page,hasMore:rows.length>page.length};
}
function seoulDay_(time) { return new Date(time+9*3600000).toISOString().slice(0,10); }
function salesReport_(body) {
  assert_(['day','month','year'].includes(body.granularity));
  assert_([body.from,body.to].every(v=>typeof v==='string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v+'T00:00:00.000Z')) && new Date(v+'T00:00:00.000Z').toISOString().slice(0,10)===v) && body.from<=body.to);
  const sums={}; namedRows_(namedSheet_(SALE_SHEET,SALE_HEADERS,false),20).forEach(row=>{
    const meta=JSON.parse(row[15]); if(meta.status!=='판매완료')return;
    const day=seoulDay_(meta.completedAt); if(day<body.from||day>body.to)return;
    const period=day.slice(0,body.granularity==='day'?10:body.granularity==='month'?7:4);
    const b=sums[period]||(sums[period]={period:period,orders:0,quantity:0,sales:0,tax:0,cost:0,expenses:0,knownProfit:0,unknownProfitOrders:0});
    b.orders++;b.quantity+=meta.totalQty;b.sales+=meta.totalPrice;b.tax+=meta.taxAmount;b.cost+=meta.costTotal;b.expenses+=meta.expenseTotal;
    const profit=profit_(meta);if(profit===null)b.unknownProfitOrders++;else b.knownProfit+=profit;
    Object.keys(b).filter(k=>k!=='period').forEach(k=>assert_(Number.isSafeInteger(b[k]),'NOT_READY'));
  }); return {report:{granularity:body.granularity,from:body.from,to:body.to,buckets:Object.values(sums).sort((a,b)=>a.period.localeCompare(b.period))}};
}
/** Scheduled disposal. Financial aggregates remain; customer copies and connecting identifiers are removed. */
function purgeExpiredCustomerData() {
  if(PropertiesService.getScriptProperties().getProperty('PRIVACY_SETUP')!=='1')return;
  lock_(()=>{
    if(quoteStorageConfigured_())purgeIndexedQuotes_();
    else {const sheet=sheet_();rows_(sheet).forEach((r,i)=>{if(Date.parse(String(r[2]))+QUOTE_RETENTION_MS<=Date.now() && JSON.parse(r[15]).expired!==true)expireQuoteRow_(sheet,r,i);});}
    const sales=namedSheet_(SALE_SHEET,SALE_HEADERS,false);const rows=namedRows_(sales,20);const events=namedSheet_(SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    rows.forEach((r,i)=>{
      const meta=JSON.parse(r[15]); if(meta.archived || !['판매완료','주문취소'].includes(meta.status) || Date.parse(String(r[14]))>Date.now())return;
      const archive={archived:true,status:meta.status,completedAt:meta.completedAt,totalQty:meta.totalQty,totalPrice:meta.totalPrice,taxAmount:meta.taxAmount,costTotal:meta.costTotal,expenseTotal:meta.expenseTotal,costConfirmed:meta.costConfirmed,profitMode:meta.profitMode,commissionBps:meta.commissionBps};
      const row=r.slice();row[0]=hash_(PropertiesService.getScriptProperties().getProperty('WEB_SECRET')+'expired-sale:'+r[0]);row[3]='';row[15]=JSON.stringify(archive);row[16]='';row[17]='';row[18]='';row[19]=0;
      sales.getRange(i+2,1,1,20).setValues([row]);
      namedRows_(events,5).forEach((e,j)=>{if(e[1]===r[0])events.getRange(j+2,1,1,5).setValues([['','','','','']]);});
    });SpreadsheetApp.flush();
  });
}

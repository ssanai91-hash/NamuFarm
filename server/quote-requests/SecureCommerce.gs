/** API v5: authenticated customer records; v4 envelopes remain readable for migration. */
const SALE_SHEET = '판매기록';
const SALE_HEADERS = ['주문번호','변경순번','상태','구매시각(UTC)','판매완료(UTC)','수량','매출(세액제외)','세액','원가','비용','원가확인','수익방식','수수료비율(bp)','매출이익','보관기한(UTC)','공개판매JSON','고객정보JSON','내용해시','최종변경번호','revision'];
const SALE_EVENT_SHEET = '판매변경이력';
const SALE_HIDDEN_SHEET = '판매목록숨김';
const SALE_HIDDEN_HEADERS = ['주문번호','숨김처리시각(UTC)'];
const SALE_DELETE_SHEET = '판매삭제이력';
const SALE_DELETE_HEADERS = ['주문번호','revision','삭제순번','삭제요청번호','삭제시각(UTC)','처리상태'];
const SALE_EVENT_HEADERS = ['변경번호','주문번호','revision','내용해시','처리시각(UTC)'];
const UUID_PATTERN = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const QUOTE_RETENTION_MS = 30*86400000;
const QUOTE_SENDER_EMAIL = 'ssanai91@gmail.com';
const SOLAPI_ENDPOINT = 'https://api.solapi.com/messages/v4/send-many/detail';
const SOLAPI_LIST_ENDPOINT = 'https://api.solapi.com/messages/v4/list';
function sellerKey_() {
  const raw = PropertiesService.getScriptProperties().getProperty('SELLER_PUBLIC_KEY');
  return raw ? JSON.parse(raw) : null;
}
function secureServiceInfo_() {
  const key = sellerKey_();
  let ready = false;
  try { ready = PropertiesService.getScriptProperties().getProperty('PRIVACY_SETUP') === '1' && quoteStorageReady_();
    if(twoBookActive_()){
      const quote=annualLedgerBook_('quote',seoulDay_(Date.now()).slice(0,4));
      ready=ready && [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].every(name=>!!quote.getSheetByName(name));
    }else{
      sheet_();
      ready=ready && rows_(sheet_()).every(row=>{const raw=JSON.parse(row[15]);return raw.expired===true || !!raw.request || !!raw.encryptedRequest;});
    }
    namedSheet_(SALE_SHEET,SALE_HEADERS,false); } catch (_) {}
  return {ok:true,service:'namufarm-quote-requests',version:5,connectionMode:'device-auth',customerDataReady:ready,
    ledgerStorage:annualLedgersEnabled_()?'annual-v1':'legacy',
    quoteLedgerMonth:'receivedAt',orderLedgerMonth:'paidAt',
    processingBases:['quote_request'],requestFields:['customerEmail','formalQuoteRequested','recipientName','replyChannel'],
    encryptionReady:false,customerDataStorage:'plain',sellerKey:key,retentionDays:30,requestDeletionSync:true,saleDeletionSync:true,
    receiptFormat:'NAMU-YYYYMMDD-NNNN',receiptTimeZone:'Asia/Seoul',connectionFile:true,requestSnapshot:true,
    connectionAuth:'auto-v1',quoteReadMode:twoBookActive_()?'annual-two-tabs':'legacy-storage',legacyDeviceAuth:true,
    quoteEmail:true,quoteEmailSender:QUOTE_SENDER_EMAIL,salesReportEmail:true,
    customerInquiry:1,customerInquiryReady:typeof customerInquiryReady_==='function' && customerInquiryReady_(),
    quoteSms:true,quoteSmsProvider:'SOLAPI',soldNumberReservation:1,soldDisplayNumbers:1,
    commerceStates:['입금확인','배송완료','구매취소']};
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
  const props = PropertiesService.getScriptProperties();
  // All internal sheets live in the administration book. The quote workbook
  // has only 견적요청/견적확정; the order workbook has only month tabs.
  const id=twoBookActive_() ?
    props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID') :
    props.getProperty('SPREADSHEET_ID');
  assert_(id,'NOT_READY');
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
function submitCustomerRequest_(body) {
  // Intake checks the quote store, without scanning sales or historical records.
  assert_(PropertiesService.getScriptProperties().getProperty('PRIVACY_SETUP')==='1' && quoteStorageReady_(),'NOT_READY');
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
    return {request:request,quotePricing:raw.quotePricing?
      quotePricing_(raw.quotePricing,request):null};
  }
  return {encryptedRequest:envelope_(raw.encryptedRequest,sellerKey_())};
}
function commitQuoteRequest_(body,stored) {
  return lock_(() => {
    if(twoBookActive_())return twoTabSubmit_(body,stored);
    if(quoteStorageConfigured_()) return submitIndexedQuote_(body,stored);
    const sheet = sheet_(); const rows = receiptRows_(sheet); const existingIndex = rows.findIndex(row=>row[0]===body.requestId);
    assert_(receiptColumnsReady_(sheet),'NOT_READY');
    if (existingIndex>=0) { const existing=rows[existingIndex]; assert_(existing[11]===body.fingerprint,'CONFLICT');
      return Object.assign(assignReceipt_(sheet,existing,existingIndex,rows),{duplicate:true}); }
    const now = Date.now();
    const props=PropertiesService.getScriptProperties(); const seq=rows.reduce((m,r)=>Math.max(m,Number(r[1])||0),Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
    assert_(Number.isSafeInteger(seq),'NOT_READY'); props.setProperty('LAST_SEQUENCE',String(seq));
    const createdAt=new Date(now).toISOString(),day=receiptDay_(createdAt);
    const floor=rows.reduce((max,r)=>r[16] && receiptDay_(r[2])===day ? Math.max(max,receiptCounter_(r)) : max,0);
    const receipt=reserveReceipt_(props,day,floor);
    const row=[body.requestId,seq,createdAt,...quoteCustomerCells_(stored),'신규','PENDING',body.fingerprint,0,createdAt,'',JSON.stringify(stored),receipt.number,receiptTime_(createdAt)];
    quoteWriteVerified_(sheet,sheet.getLastRow()+1,row);
    return Object.assign(receiptDetails_(row),{duplicate:false});
  });
}
function quoteResponseRow_(row) {
  const expires=Date.parse(String(row[2]))+QUOTE_RETENTION_MS,raw=JSON.parse(row[15]);
  const result={requestId:String(row[0]),sequence:Number(row[1]),createdAt:String(row[2]),expiresAt:expires,status:String(row[9]||'신규')};
  if(row[16])Object.assign(result,receiptDetails_(row));
  if(raw.expired===true || expires<=Date.now())return Object.assign(result,{expired:true});
  const stored=quoteStoredData_(raw);
  if(stored.request)assert_(stored.request.requestId===result.requestId,'NOT_READY');
  return Object.assign(result,stored.request || stored,
    stored.quotePricing?{quotePricing:stored.quotePricing}:{});
}
function listEncryptedRequests_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0);
  if(twoBookActive_())return lock_(()=>{
    const rows=twoTabRecords_().filter(r=>Number(r.row[1])>body.afterSequence);
    const requests=rows.slice(0,100).map(r=>quoteResponseRow_(r.row));
    return {requests:requests,hasMore:rows.length>requests.length};
  });
  if(quoteStorageConfigured_())return lock_(()=>{
    const matches=indexedQuoteRecords_(body.afterSequence,true);
    const requests=matches.slice(0,100).map(({entry,cache})=>quoteResponseRow_(quoteRecord_(entry,cache,false).row));
    return {requests:requests,hasMore:matches.length>requests.length};
  });
  const rows=receiptRows_(sheet_()).filter(r=>Number(r[1])>body.afterSequence).sort((a,b)=>Number(a[1])-Number(b[1]));
  return {requests:rows.slice(0,100).map(quoteResponseRow_),hasMore:rows.length>100};
}
/** A complete, versioned view: partial downloads must never remove local records. */
function snapshotRequests_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence >= 0);
  assert_(body.afterSequence === 0 || /^[a-f0-9]{64}$/.test(body.snapshotId || ''));
  return lock_(() => {
    const rows = twoBookActive_()?twoTabRecords_().map(r=>r.row):
      quoteStorageConfigured_()
        ? indexedQuoteRecords_(-1,true).map(({entry,cache})=>quoteRecord_(entry,cache,false).row)
        : receiptRows_(sheet_());
    const all = rows.map(quoteResponseRow_).sort((a,b) => a.sequence-b.sequence);
    const ids = new Set(); let previous = 0;
    all.forEach(row => {
      assert_(Number.isSafeInteger(row.sequence) && row.sequence > previous && !ids.has(row.requestId), 'NOT_READY');
      previous = row.sequence; ids.add(row.requestId);
    });
    const watermark = Math.max(previous, Number(PropertiesService.getScriptProperties().getProperty('LAST_SEQUENCE')) || 0);
    const snapshotId = hash_(JSON.stringify([watermark,all]));
    assert_(!body.snapshotId || equal_(body.snapshotId,snapshotId), 'BUSY');
    const remaining = all.filter(row => row.sequence > body.afterSequence);
    return {requests:remaining.slice(0,100), hasMore:remaining.length>100,
      snapshotId:snapshotId, total:all.length, watermark:watermark};
  });
}
function closeRequest_(body) {
  assert_(UUID_PATTERN.test(body.requestId));
  return lock_(()=>{
    if(twoBookActive_()){twoTabClose_(body.requestId);return {closed:true,requestId:body.requestId};}
    if(quoteStorageConfigured_()){closeIndexedQuote_(body.requestId);return {closed:true,requestId:body.requestId};}
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
  assert_(['입금확인','배송완료','구매취소','구매확정','배송확인','판매완료','주문취소'].includes(raw.status));
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
  if(['입금확인','배송완료','구매취소'].includes(raw.status)){
    assert_(raw.paidAt>=raw.purchasedAt && raw.paidAt<=raw.updatedAt);
    if(raw.status==='배송완료')assert_(raw.deliveredAt>=raw.paidAt && raw.deliveredAt<=raw.updatedAt && raw.completedAt>=raw.deliveredAt && raw.completedAt<=raw.updatedAt);
  }else{
    const rank=['구매확정','입금확인','배송확인','판매완료'].indexOf(raw.status);
    if(rank>=1)assert_(raw.paidAt>=raw.purchasedAt && raw.paidAt<=raw.updatedAt);
    if(rank>=2)assert_(raw.deliveredAt>=raw.paidAt && raw.deliveredAt<=raw.updatedAt);
    if(rank>=3)assert_(raw.completedAt>=raw.deliveredAt && raw.completedAt<=raw.updatedAt && raw.costConfirmed);
  }
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
  const canceled=['구매취소','주문취소'].includes(meta.status);
  const date=new Date(canceled?meta.updatedAt:(meta.completedAt||meta.purchasedAt)); date.setUTCFullYear(date.getUTCFullYear()+5); return date.toISOString();
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
    assertSoldNumberReserved_(meta.orderNumber);
    const events=namedSheet_(SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    assert_(!saleDeletionRows_().some(row=>row[0]===meta.orderNumber),'CONFLICT');
    const records=saleRecords_(),rows=records.map(r=>r.row);
    const i=rows.findIndex(r=>r[0]===meta.orderNumber || r[0]===hash_(PropertiesService.getScriptProperties().getProperty('WEB_SECRET')+'expired-sale:'+meta.orderNumber));
    reserveSaleSource_(body,records,i<0?null:records[i]);
    const receipts=namedRows_(events,5); const receipt=receipts.find(r=>r[0]===body.eventId);
    if(receipt){assert_(receipt[1]===meta.orderNumber && Number(receipt[2])===meta.revision && receipt[3]===digest,'CONFLICT'); saleRequestSaved_(body); return {saved:true,orderNumber:meta.orderNumber,revision:meta.revision,fingerprint:digest};}
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
        const restoring=['구매취소','주문취소'].includes(previous.status) && meta.status==='입금확인';
        const terminal=['배송완료','구매취소','판매완료','주문취소'].includes(previous.status);
        const noteOnly=terminal && previous.status===meta.status &&
          Object.keys(previous).filter(k=>!['revision','updatedAt'].includes(k)).every(k=>canonical_(previous[k])===canonical_(meta[k]));
        assert_(!terminal || restoring || noteOnly,'CONFLICT');
        const next={'입금확인':'배송완료','구매확정':'배송완료','배송확인':'배송완료'};
        assert_(meta.status===previous.status || restoring || meta.status===next[previous.status] ||
          (previous.status==='구매확정' && meta.status==='입금확인') ||
          (meta.status==='구매취소' && ['입금확인','구매확정'].includes(previous.status)),'CONFLICT');
        ['orderNumber','orderDate','totalQty','totalPrice','isPrepaid','shippingFee','purchasedAt','taxAmount','items'].forEach(k=>assert_(canonical_(meta[k])===canonical_(previous[k]),'CONFLICT'));
        assert_(meta.updatedAt>=previous.updatedAt,'CONFLICT');
        ['paidAt','deliveredAt','completedAt'].forEach(k=>{if(previous[k]>0)assert_(previous[k]===meta[k],'CONFLICT');});
      }else assert_(meta.revision===1 && ['입금확인','구매확정','주문취소'].includes(meta.status),'CONFLICT');
      const props=PropertiesService.getScriptProperties(); const seq=rows.reduce((m,r)=>Math.max(m,Number(r[1])||0),
        saleDeletionRows_().reduce((m,r)=>Math.max(m,Number(r[2])||0),Number(props.getProperty('SALE_SEQUENCE'))||0))+1;
      assert_(Number.isSafeInteger(seq),'NOT_READY'); props.setProperty('SALE_SEQUENCE',String(seq));
      const row=[meta.orderNumber,seq,meta.status,new Date(meta.purchasedAt).toISOString(),meta.completedAt?new Date(meta.completedAt).toISOString():'',meta.totalQty,meta.totalPrice,meta.taxAmount,meta.costTotal,meta.expenseTotal,meta.costConfirmed,meta.profitMode,meta.commissionBps,profit_(meta),retentionUntil_(meta),JSON.stringify(meta),JSON.stringify(stored),digest,body.eventId,meta.revision];
      reserveSaleSource_(body,records,i<0?null:records[i],true);
      const sheet=i<0?newSaleSheet_(meta):records[i].sheet;
      const rowNumber=i<0?sheet.getLastRow()+1:records[i].index+2;
      if(rowNumber>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),Math.max(100,rowNumber-sheet.getMaxRows()));
      sheet.getRange(rowNumber,1,1,20).setNumberFormat('@').setValues([row]); SpreadsheetApp.flush();
    }
    if(!namedRows_(events,5).some(r=>r[0]===body.eventId)) {
      events.getRange(events.getLastRow()+1,1,1,5).setNumberFormat('@').setValues([[body.eventId,meta.orderNumber,meta.revision,digest,new Date().toISOString()]]); SpreadsheetApp.flush();
    }
    saleRequestSaved_(body);
    return {saved:true,orderNumber:meta.orderNumber,revision:meta.revision,fingerprint:digest};
  });
}
function requestStatusTransitionAllowed_(from,status) {
  if(from===status)return true;
  if(status==='견적확정')return from==='신규';
  if(status==='견적발송완료')return from==='견적확정';
  if(status==='입금확인')return ['견적확정','견적발송완료','구매취소','주문취소'].includes(from);
  if(status==='배송완료')return from==='입금확인';
  if(status==='구매취소')return from==='입금확인';
  return false;
}
function updateLinkedRequestStatus_(id,status) {
  if(!id)return;
  // The Android legacy 'purchase confirmed' stage means payment confirmed.
  // The quote register must never expose it as a separate business state.
  if(status==='구매확정')status='입금확인';
  if(status==='배송확인' || status==='판매완료')status='배송완료';
  if(status==='주문취소')status='구매취소';
  assert_(['견적확정','견적발송완료','입금확인','배송완료','구매취소'].includes(status),'INVALID');
  if(twoBookActive_()){twoTabSetStatus_(id,status);return;}
  if(quoteStorageConfigured_()){
    const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
    const entry=entries.find(e=>e.values[QI.id]===id);
    if(!entry || entry.values[QI.phase]!=='COMMITTED')return;
    const record=quoteRecord_(entry,cache,false);
    if(JSON.parse(record.row[15]).expired===true || record.row[9]===status)return;
    assert_(requestStatusTransitionAllowed_(String(record.row[9]||'신규'),status),'CONFLICT');
    const sequence=quoteNextSequence_(entries),next=record.row.slice();
    next[1]=sequence;next[9]=status;quoteWriteVerified_(record.sheet,record.index+2,next);
    entry.values[QI.seq]=sequence;entry.values[QI.status]=status;quoteSaveEntry_(entry);
    if(entry.values[QI.copy]){
      const legacy=quoteFindRow_(sheet_(),id);
      if(legacy){const copy=legacy.row.slice();copy[1]=sequence;copy[9]=status;quoteWriteVerified_(legacy.sheet,legacy.index+2,copy);}
    }
    return;
  }
  const sheet=sheet_(),rows=receiptRows_(sheet),i=rows.findIndex(r=>r[0]===id);
  if(i<0 || JSON.parse(rows[i][15]).expired===true || rows[i][9]===status)return;
  assert_(requestStatusTransitionAllowed_(String(rows[i][9]||'신규'),status),'CONFLICT');
  const props=PropertiesService.getScriptProperties();
  const sequence=rows.reduce((m,r)=>Math.max(m,Number(r[1])||0),Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
  assert_(Number.isSafeInteger(sequence),'NOT_READY');props.setProperty('LAST_SEQUENCE',String(sequence));
  const next=rows[i].slice();next[1]=sequence;next[9]=status;quoteWriteVerified_(sheet,i+2,next);
}
/** Quote-stage pricing belongs exclusively to the quote workbook. */
function quotePricing_(raw,request) {
  assert_(raw && typeof raw==='object' && Object.keys(raw).sort().join(',')==='isPrepaid,items,shippingFee,taxAmount','INVALID');
  assert_(Array.isArray(raw.items) && raw.items.length>=1 && raw.items.length<=50,'INVALID');
  // The seller may adjust size or quantity. The customer's original request is
  // preserved separately in raw.request and is never rewritten.
  const priceItems=raw.items.map(item=>{
    assert_(item && typeof item.species==='string' && item.species.trim() &&
      item.species.length<=200 && typeof item.specification==='string' &&
      item.specification.length<=120 && [...item.species,...item.specification].every(ch=>ch.charCodeAt(0)>=32),'INVALID');
    assert_(Number.isSafeInteger(item.quantity) && item.quantity>=1 && item.quantity<=1000000 &&
      Number.isSafeInteger(item.unitPrice) && item.unitPrice>=0 && item.unitPrice<=2147483647,'INVALID');
    return {species:item.species,specification:item.specification,quantity:item.quantity,unitPrice:item.unitPrice};
  });
  assert_(typeof raw.isPrepaid==='boolean' &&
    Number.isSafeInteger(raw.shippingFee) && raw.shippingFee>=0 && raw.shippingFee<=2147483647 &&
    Number.isSafeInteger(raw.taxAmount) && raw.taxAmount>=0 && raw.taxAmount<=2147483647,'INVALID');
  const sum=priceItems.reduce((n,item)=>n+item.quantity*item.unitPrice,raw.isPrepaid?raw.shippingFee:0);
  assert_(Number.isSafeInteger(sum+raw.taxAmount) && sum+raw.taxAmount<=2147483647,'INVALID');
  return {items:priceItems,isPrepaid:raw.isPrepaid,shippingFee:raw.shippingFee,taxAmount:raw.taxAmount};
}
function saveQuotePricing_(body) {
  assert_(UUID_PATTERN.test(body.requestId),'INVALID');
  return lock_(()=>{
    if(twoBookActive_())return twoTabPriceConfirm_(body);
    let record,entry=null,entries=[];
    if(quoteStorageConfigured_()){
      entries=quoteIndexEntries_();const cache={};
      quoteRepairTransitions_(entries,cache);
      entry=entries.find(e=>e.values[QI.id]===body.requestId);
      assert_(entry && entry.values[QI.phase]==='COMMITTED','INVALID');
      record=quoteRecord_(entry,cache,false);
    }else {
      record=quoteFindRow_(sheet_(),body.requestId);
      assert_(record,'INVALID');
    }
    const original=JSON.parse(record.row[15]),request=quoteStoredData_(original).request;
    assert_(request && ['신규','견적확정'].includes(record.row[9]),'CONFLICT');
    const pricing=quotePricing_(body.quotePricing,request);
    if(original.quotePricing && canonical_(original.quotePricing)===canonical_(pricing) &&
      record.row[9]==='견적확정')return {updated:true,requestId:body.requestId,status:'견적확정'};
    assert_(record.row[9]==='신규' || !original.quotePricing,'CONFLICT');
    const props=PropertiesService.getScriptProperties();
    const next=record.row.slice();
    const sequence=entry?quoteNextSequence_(entries):
      receiptRows_(sheet_()).reduce((n,r)=>Math.max(n,Number(r[1])||0),Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
    assert_(Number.isSafeInteger(sequence),'NOT_READY');
    if(!entry)props.setProperty('LAST_SEQUENCE',String(sequence));
    next[1]=sequence;next[9]='견적확정';
    next[15]=JSON.stringify(Object.assign({},original,{quotePricing:pricing}));
    quoteWriteVerified_(record.sheet,record.index+2,next);
    if(entry){entry.values[QI.seq]=sequence;entry.values[QI.status]='견적확정';quoteSaveEntry_(entry);}
    return {updated:true,requestId:body.requestId,status:'견적확정'};
  });
}
function updateRequestStatus_(body) {
  assert_(UUID_PATTERN.test(body.requestId) && ['견적확정','견적발송완료'].includes(body.status));
  return lock_(()=>{
    updateLinkedRequestStatus_(body.requestId,body.status);
    return {updated:true,requestId:body.requestId,status:body.status};
  });
}
function quoteRequestStatus_(id) {
  assert_(UUID_PATTERN.test(id));
  if(twoBookActive_()){
    const record=twoTabRecord_(id);
    assert_(record && JSON.parse(record.row[15]).expired!==true,'INVALID');
    return String(record.row[9]||'신규');
  }
  if(quoteStorageConfigured_()){
    const entries=quoteIndexEntries_(),cache={},entry=entries.find(e=>e.values[QI.id]===id);
    assert_(entry && entry.values[QI.phase]==='COMMITTED','INVALID');
    const record=quoteRecord_(entry,cache,false);
    assert_(JSON.parse(record.row[15]).expired!==true,'INVALID');
    return String(record.row[9]||'신규');
  }
  const record=quoteFindRow_(sheet_(),id);
  assert_(record && JSON.parse(record.row[15]).expired!==true,'INVALID');
  return String(record.row[9]||'신규');
}
function quoteEmailProfile_() {
  // The web app already runs as USER_DEPLOYING. Avoid Session identity lookups here:
  // Google can withhold identity in non-FULL authorization contexts even though
  // MailApp is allowed to send as the deployment owner.
  return {senderName:'나무팜농원',senderEmail:QUOTE_SENDER_EMAIL,remainingDailyQuota:MailApp.getRemainingDailyQuota()};
}
function authorizeQuoteMail() {
  // Run this once manually in the Apps Script editor after adding script.send_mail.
  // It intentionally sends nothing; invoking MailApp is enough to request/verify authorization.
  const profile=quoteEmailProfile_();
  console.log('Quote mail ready: '+profile.senderEmail+' / quota '+profile.remainingDailyQuota);
  return profile;
}
function sendQuoteEmail_(body) {
  assert_(body && typeof body==='object');
  const requestId=String(body.requestId||'');
  if(requestId)assert_(['신규','견적확정','견적발송완료'].includes(quoteRequestStatus_(requestId)),'CONFLICT');
  const recipient=text_(body.recipientEmail,254,3);
  assert_(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(recipient));
  const senderName=text_(body.senderName||'나무팜농원',50,1);
  const quoteNumber=text_(body.quoteNumber||'',120,1);
  const subject=text_(body.subject,160,1);
  const plainBody=text_(body.bodyText,12000,1);
  const htmlBody=text_(body.bodyHtml,24000,1);
  assert_(!/<\s*(script|iframe|object|embed|form)\b/i.test(htmlBody));

  const attachmentName=body.attachmentName?text_(body.attachmentName,180,5):'';
  const attachmentBase64=String(body.attachmentBase64||'');
  assert_((!attachmentName&&!attachmentBase64)||(attachmentName&&attachmentBase64),'INVALID');
  const options={to:recipient,subject:subject,body:plainBody,htmlBody:htmlBody,name:senderName};
  if(attachmentName){
    assert_(/^[^\\/:*?"<>|]+\.pdf$/i.test(attachmentName),'INVALID');
    const encoded=byteString_(attachmentBase64,1,2*1024*1024);
    const bytes=Utilities.base64Decode(encoded);
    options.attachments=[Utilities.newBlob(bytes,'application/pdf',attachmentName)];
  }

  const profile=quoteEmailProfile_();
  assert_(profile.remainingDailyQuota>0,'NOT_READY');
  MailApp.sendEmail(options);
  return {sent:true,quoteNumber:quoteNumber,recipientEmail:recipient,senderEmail:profile.senderEmail,
    attachmentName:attachmentName,sentAt:new Date().toISOString(),
    remainingDailyQuota:MailApp.getRemainingDailyQuota()};
}

function sendSalesReportEmail_(body) {
  const recipient=text_(body.recipientEmail,254,3);
  assert_(/^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(recipient),'INVALID');
  const subject=text_(body.subject,160,1);
  const plainBody=text_(body.bodyText,12000,1);
  const attachmentName=text_(body.attachmentName,180,6);
  assert_(/^[^\\/:*?"<>|\r\n]+\.xlsx$/i.test(attachmentName),'INVALID');
  const encoded=byteString_(body.attachmentBase64,4,2*1024*1024);
  const bytes=Utilities.base64Decode(encoded);
  assert_(bytes[0]===80 && bytes[1]===75 && bytes[2]===3 && bytes[3]===4,'INVALID');
  const profile=quoteEmailProfile_();
  assert_(profile.remainingDailyQuota>0,'NOT_READY');
  // This is a seller's report attachment; it does not advance quote/order states.
  MailApp.sendEmail({to:recipient,subject:subject,body:plainBody,name:'나무팜농원',
    attachments:[Utilities.newBlob(bytes,'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',attachmentName)]});
  return {sent:true,recipientEmail:recipient,senderEmail:profile.senderEmail,attachmentName:attachmentName,
    sentAt:new Date().toISOString(),remainingDailyQuota:MailApp.getRemainingDailyQuota()};
}

function quoteSmsSettings_() {
  const props=PropertiesService.getScriptProperties();
  const apiKey=String(props.getProperty('SOLAPI_KEY')||'').trim();
  const apiSecret=String(props.getProperty('SOLAPI_SECRET')||'').trim();
  const senderPhone=String(props.getProperty('SOLAPI_FROM')||'').replace(/\D/g,'');
  return {apiKey:apiKey,apiSecret:apiSecret,senderPhone:senderPhone,
    configured:!!apiKey && !!apiSecret && /^0\d{8,10}$/.test(senderPhone)};
}
function quoteSmsProfile_() {
  const settings=quoteSmsSettings_();
  return {provider:'SOLAPI',configured:settings.configured,
    senderPhone:settings.configured?settings.senderPhone:''};
}
function quoteSmsAuthorization_(settings) {
  const date=new Date().toISOString();
  const salt=Utilities.getUuid().replace(/-/g,'');
  const signature=Utilities.computeHmacSha256Signature(date+salt,settings.apiSecret)
    .map(b=>('0'+((b+256)%256).toString(16)).slice(-2)).join('');
  return 'HMAC-SHA256 apiKey='+settings.apiKey+', date='+date+', salt='+salt+', signature='+signature;
}
function quoteSmsReason_(code) {
  const reasons={
    '2000':'문자 발송을 접수했습니다.',
    '3000':'통신사 전달 후 결과를 확인하고 있습니다.',
    '4000':'문자 전송에 성공했습니다.',
    '1030':'문자 발송 잔액이 부족합니다.','2230':'문자 발송 잔액이 부족합니다.',
    '1062':'등록된 발신번호를 확인해주세요.','2062':'등록된 발신번호를 확인해주세요.',
    '3010':'수신번호 형식을 확인해주세요.','3032':'가입되지 않은 번호입니다.',
    '3040':'전송 시간이 초과되었습니다.','3041':'수신 단말기가 사용 중입니다.',
    '3042':'수신 단말기가 음영지역에 있습니다.','3043':'수신 단말기 전원이 꺼져 있습니다.',
    '3044':'수신 단말기 메시지 저장공간이 부족합니다.','3045':'수신 단말기가 일시 정지 상태입니다.',
    '3047':'수신자가 착신을 거절했습니다.','3054':'스팸 차단으로 전송되지 않았습니다.',
    '3055':'스팸 차단으로 전송되지 않았습니다.','3056':'통신망에서 전송에 실패했습니다.',
    '3057':'통신망에서 전송에 실패했습니다.','3058':'전송 경로를 찾지 못했습니다.',
    '3059':'발신번호 문제로 전송되지 않았습니다.','3063':'중복 메시지로 차단되었습니다.'
  };
  return reasons[code] || (code ? '문자 전송에 실패했습니다.' : '문자 발송 결과를 확인하고 있습니다.');
}
function sendQuoteSms_(body) {
  assert_(body && typeof body==='object');
  const requestId=String(body.requestId||'');
  if(requestId)assert_(['신규','견적확정','견적발송완료'].includes(quoteRequestStatus_(requestId)),'CONFLICT');
  const recipientPhone=String(body.recipientPhone||'').replace(/\D/g,'');
  assert_(/^0\d{8,10}$/.test(recipientPhone),'INVALID');
  const message=text_(body.text,1000,1);
  const settings=quoteSmsSettings_();
  assert_(settings.configured,'NOT_READY');

  const response=UrlFetchApp.fetch(SOLAPI_ENDPOINT,{
    method:'post',
    contentType:'application/json',
    headers:{Authorization:quoteSmsAuthorization_(settings)},
    payload:JSON.stringify({messages:[{to:recipientPhone,from:settings.senderPhone,text:message,autoTypeDetect:true}],
      showMessageList:true}),
    muteHttpExceptions:true
  });
  const httpCode=response.getResponseCode();
  let result;
  try { result=JSON.parse(response.getContentText()); } catch (_) { fail_('UNAVAILABLE'); }
  assert_(httpCode>=200 && httpCode<300,'UNAVAILABLE');
  const failed=Array.isArray(result.failedMessageList)?result.failedMessageList:[];
  const list=Array.isArray(result.messageList)?result.messageList:[];
  assert_(failed.length===0 && list.length===1 && String(list[0].statusCode)==='2000','UNAVAILABLE');
  const accepted=list[0];
  return {sent:true,accepted:true,recipientPhone:recipientPhone,senderPhone:settings.senderPhone,
    messageId:String(accepted.messageId||''),state:'pending',statusCode:'2000',
    statusMessage:quoteSmsReason_('2000'),groupId:result.groupInfo&&result.groupInfo.groupId?String(result.groupInfo.groupId):'',
    sentAt:new Date().toISOString()};
}
function quoteSmsStatus_(body) {
  assert_(body && typeof body==='object');
  const messageId=text_(body.messageId,120,5);
  assert_(/^M[A-Za-z0-9_-]+$/.test(messageId),'INVALID');
  const settings=quoteSmsSettings_();
  assert_(settings.configured,'NOT_READY');
  const url=SOLAPI_LIST_ENDPOINT+'?criteria=messageId&cond=eq&value='+encodeURIComponent(messageId)+'&limit=1';
  const response=UrlFetchApp.fetch(url,{
    method:'get',
    headers:{Authorization:quoteSmsAuthorization_(settings)},
    muteHttpExceptions:true
  });
  const httpCode=response.getResponseCode();
  let result;
  try { result=JSON.parse(response.getContentText()); } catch (_) { fail_('UNAVAILABLE'); }
  assert_(httpCode>=200 && httpCode<300 && result && result.messageList,'UNAVAILABLE');
  const row=result.messageList[messageId] || Object.values(result.messageList)[0];
  assert_(row && String(row.messageId||'')===messageId,'INVALID');
  const code=String(row.statusCode||'');
  const providerState=String(row.status||'');
  const success=code==='4000';
  const failed=providerState==='COMPLETE' && !success ||
    /^[12]/.test(code) && code!=='2000' ||
    /^3/.test(code) && code!=='3000';
  const state=success?'success':failed?'failed':'pending';
  return {checked:true,messageId:messageId,state:state,final:state!=='pending',success:success,
    statusCode:code,statusMessage:quoteSmsReason_(code),updatedAt:new Date().toISOString()};
}

/** Durable deletion receipts contain no customer data. PENDING retries finish
 * physical row removal before any client is told the deletion succeeded. */
function saleDeletionRows_() {
  const props=PropertiesService.getScriptProperties();
  const id=twoBookActive_() ? props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID') : props.getProperty('SPREADSHEET_ID');
  const sheet=SpreadsheetApp.openById(id).getSheetByName(SALE_DELETE_SHEET);
  if(!sheet)return [];
  assert_(canonical_(sheet.getRange(1,1,1,6).getValues()[0])===canonical_(SALE_DELETE_HEADERS),'NOT_READY');
  const rows=namedRows_(sheet,6);
  assert_(rows.every(r=>/^[A-Za-z0-9_-]{1,120}$/.test(r[0]) &&
    Number.isSafeInteger(Number(r[1])) && Number(r[1])>0 &&
    Number.isSafeInteger(Number(r[2])) && Number(r[2])>0 &&
    UUID_PATTERN.test(r[3]) && ['PENDING','DONE'].includes(r[5])) &&
    new Set(rows.map(r=>r[0])).size===rows.length,'NOT_READY');
  return rows;
}
function deleteSale_(body) {
  assert_(body && UUID_PATTERN.test(body.eventId) && /^[A-Za-z0-9_-]{1,120}$/.test(body.orderNumber) &&
    Number.isSafeInteger(body.revision) && body.revision>0,'INVALID');
  return lock_(()=>{
    const receipts=saleDeletionRows_();
    const receiptIndex=receipts.findIndex(r=>r[0]===body.orderNumber);
    let receipt=receiptIndex<0?null:receipts[receiptIndex];
    const records=saleRecords_();
    const record=records.find(r=>r.row[0]===body.orderNumber);
    if(receipt){
      assert_(Number(receipt[1])===body.revision,'CONFLICT');
      if(receipt[5]==='DONE')return {deleted:true,orderNumber:body.orderNumber,revision:body.revision,sequence:Number(receipt[2])};
      assert_(receipt[3]===body.eventId,'CONFLICT');
    }else{
      assert_(record && !JSON.parse(record.row[15]).archived,'INVALID');
      assert_(Number(record.row[19])===body.revision,'CONFLICT');
    }
    // Include compatibility copies, so an old month or legacy row cannot reappear.
    const sheets=saleStorageSheets_();
    if(twoBookActive_())sheets.push(namedSheet_(SALE_SHEET,SALE_HEADERS,false));
    const targets=sheets.map(sheet=>({sheet:sheet,positions:namedRows_(sheet,20)
      .map((row,i)=>row[0]===body.orderNumber?i+2:0).filter(Boolean)}));
    const journal=namedSheet_(SALE_DELETE_SHEET,SALE_DELETE_HEADERS,true);
    let receiptRow=receiptIndex+2;
    if(!receipt){
      const props=PropertiesService.getScriptProperties();
      const sequence=records.reduce((m,r)=>Math.max(m,Number(r.row[1])||0),
        receipts.reduce((m,r)=>Math.max(m,Number(r[2])||0),Number(props.getProperty('SALE_SEQUENCE'))||0))+1;
      assert_(Number.isSafeInteger(sequence),'NOT_READY');
      props.setProperty('SALE_SEQUENCE',String(sequence));
      receipt=[body.orderNumber,body.revision,sequence,body.eventId,new Date().toISOString(),'PENDING'];
      receiptRow=journal.getLastRow()+1;
      quoteWriteVerified_(journal,receiptRow,receipt);
    }
    targets.forEach(target=>target.positions.sort((a,b)=>b-a).forEach(row=>target.sheet.deleteRow(row)));
    SpreadsheetApp.flush();
    assert_(targets.every(target=>!namedRows_(target.sheet,20).some(r=>r[0]===body.orderNumber)),'NOT_READY');
    receipt[5]='DONE';
    quoteWriteVerified_(journal,receiptRow,receipt);
    return {deleted:true,orderNumber:body.orderNumber,revision:body.revision,sequence:Number(receipt[2])};
  });
}

/** A sale can leave the seller's work list without erasing the financial ledger. */
function hideSale_(body) {
  assert_(body && /^[A-Za-z0-9_-]{1,120}$/.test(body.orderNumber) &&
      Number.isSafeInteger(body.revision) && body.revision>0,'INVALID');
  return lock_(()=>{
    const records=saleRecords_().map(item=>item.row);
    const matches=records.filter(row=>row[0]===body.orderNumber);
    assert_(matches.length===1,'INVALID');
    const meta=JSON.parse(matches[0][15]);
    assert_(!meta.archived,'INVALID');
    assert_(Number(meta.revision)===body.revision,'CONFLICT');
    const hidden=namedSheet_(SALE_HIDDEN_SHEET,SALE_HIDDEN_HEADERS,true);
    const existing=namedRows_(hidden,2).filter(row=>row[0]===body.orderNumber);
    assert_(existing.length<=1,'NOT_READY');
    if(!existing.length){
      hidden.getRange(hidden.getLastRow()+1,1,1,2).setValues([[body.orderNumber,new Date().toISOString()]]);
      SpreadsheetApp.flush();
    }
    return {hidden:true,orderNumber:body.orderNumber,revision:body.revision};
  });
}
function hiddenSaleNumbers_() {
  const props=PropertiesService.getScriptProperties();
  const id=twoBookActive_() ?
    props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID') :
    props.getProperty('SPREADSHEET_ID');
  const book=SpreadsheetApp.openById(id);
  const sheet=book.getSheetByName(SALE_HIDDEN_SHEET);
  if(!sheet)return new Set();
  assert_(JSON.stringify(sheet.getRange(1,1,1,2).getValues()[0])===JSON.stringify(SALE_HIDDEN_HEADERS),'NOT_READY');
  const rows=namedRows_(sheet,2);
  const numbers=rows.map(r=>String(r[0]));
  assert_(numbers.length===new Set(numbers).size,'NOT_READY');
  return new Set(numbers);
}
function listSales_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0);
  const hidden=hiddenSaleNumbers_();
  const deleted=saleDeletionRows_().filter(r=>r[5]==='DONE');
  const deletedNumbers=new Set(deleted.map(r=>r[0]));
  const entries=saleRecords_().map(r=>r.row).filter(r=>!deletedNumbers.has(r[0])).map(row=>({sequence:Number(row[1]),row:row}));
  deleted.forEach(r=>entries.push({sequence:Number(r[2]),deleted:true,orderNumber:r[0],revision:Number(r[1])}));
  const rows=entries.filter(r=>r.sequence>body.afterSequence).sort((a,b)=>a.sequence-b.sequence);
  const page=rows.slice(0,100).map(entry=>{
    if(entry.deleted)return {sequence:entry.sequence,expired:true,deleted:true,orderNumber:entry.orderNumber,revision:entry.revision};
    const r=entry.row;
    const meta=JSON.parse(r[15]); if(meta.archived)return {sequence:Number(r[1]),expired:true};
    const stored=JSON.parse(r[16]),plain=stored.personalData!==undefined;
    const personal=plain?salePersonal_(stored.personalData,meta):envelope_(stored,sellerKey_());
    assert_(saleDigest_(meta,personal,plain)===r[17],'NOT_READY');
    return Object.assign({sequence:Number(r[1]),eventId:r[18],metadata:saleMetadata_(meta),fingerprint:r[17],expired:false,hidden:hidden.has(String(r[0]))},plain?{personalData:personal}:{encryptedPersonal:personal});
  }); return {sales:page,hasMore:rows.length>page.length};
}
function seoulDay_(time) { return new Date(time+9*3600000).toISOString().slice(0,10); }
function salesReport_(body) {
  assert_(['day','month','year'].includes(body.granularity));
  assert_([body.from,body.to].every(v=>typeof v==='string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && Number.isFinite(Date.parse(v+'T00:00:00.000Z')) && new Date(v+'T00:00:00.000Z').toISOString().slice(0,10)===v) && body.from<=body.to);
  const sums={}; saleRecords_().forEach(({row})=>{
    const meta=JSON.parse(row[15]); if(!['배송완료','판매완료'].includes(meta.status))return;
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
    if(twoBookActive_())twoTabPurgeExpired_();
    else if(quoteStorageConfigured_())purgeIndexedQuotes_();
    else {const sheet=sheet_();rows_(sheet).forEach((r,i)=>{if(Date.parse(String(r[2]))+QUOTE_RETENTION_MS<=Date.now() && JSON.parse(r[15]).expired!==true)expireQuoteRow_(sheet,r,i);});}
    purgeArchivedQuoteCopies_();
    const records=saleRecords_();const events=namedSheet_(SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    records.forEach(({row:r,sheet:sales,index:i})=>{
      const meta=JSON.parse(r[15]); if(meta.archived || !['배송완료','구매취소','판매완료','주문취소'].includes(meta.status) || Date.parse(String(r[14]))>Date.now())return;
      const archive={archived:true,status:meta.status,completedAt:meta.completedAt,totalQty:meta.totalQty,totalPrice:meta.totalPrice,taxAmount:meta.taxAmount,costTotal:meta.costTotal,expenseTotal:meta.expenseTotal,costConfirmed:meta.costConfirmed,profitMode:meta.profitMode,commissionBps:meta.commissionBps};
      const row=r.slice();row[0]=hash_(PropertiesService.getScriptProperties().getProperty('WEB_SECRET')+'expired-sale:'+r[0]);row[3]='';row[15]=JSON.stringify(archive);row[16]='';row[17]='';row[18]='';row[19]=0;
      sales.getRange(i+2,1,1,20).setValues([row]);
      namedRows_(events,5).forEach((e,j)=>{if(e[1]===r[0])events.getRange(j+2,1,1,5).setValues([['','','','','']]);});
    });SpreadsheetApp.flush();
  });
}





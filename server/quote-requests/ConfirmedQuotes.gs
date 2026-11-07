/** Shared confirmed quotes. Existing request JSON/UUIDs and client data stay intact.
 * All helpers below run under the script lock unless the public entry takes it.
 * History is written BEFORE the head. Retry the same operationId after any lost reply.
 */
const CONFIRMED_HISTORY='확정견적이력';
const CONFIRMED_HISTORY_HEADERS=['요청UUID','견적revision','작업UUID','내용지문','확정JSON','저장시각(UTC)','보관기한(UTC)'];
function confirmedRecord_(id,allowExpired){
  assert_(UUID_PATTERN.test(id),'INVALID');
  let record;
  if(twoBookActive_())record=twoTabRecord_(id);
  else if(quoteStorageConfigured_()){
    const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
    const entry=entries.find(e=>e.values[QI.id]===id);
    if(entry && entry.values[QI.phase]==='COMMITTED'){
      record=quoteRecord_(entry,cache,false);record.entry=entry;record.entries=entries;
    }
  }else record=quoteFindRow_(sheet_(),id);
  assert_(record,'INVALID');
  const raw=JSON.parse(record.row[15]);
  if(!allowExpired)assert_(!raw.expired && Date.parse(String(record.row[2]))+QUOTE_RETENTION_MS>Date.now(),'CONFLICT');
  return record;
}
function confirmedDetails_(value,count){
  if(value===undefined)return {};
  assert_(value && typeof value==='object' && !Array.isArray(value),'INVALID');
  const limits={customerName:50,customerPhone:20,customerEmail:254,recipientName:100,region:200,notes:2000,
    validUntil:100,paymentAccount:500,documentNumber:120};
  const result={};
  Object.keys(value).forEach(key=>{
    if(Object.prototype.hasOwnProperty.call(limits,key))result[key]=text_(value[key],limits[key]);
    else if(key==='itemNotes'){
      assert_(Array.isArray(value[key]) && value[key].length===count,'INVALID');
      result[key]=value[key].map(note=>text_(note,500));
    }else if(key==='issuer'){
      assert_(value[key] && typeof value[key]==='object' && !Array.isArray(value[key]),'INVALID');
      result[key]={};
      Object.keys(value[key]).forEach(field=>{
        assert_(['businessName','representative','businessNumber','address','phone','email','businessType','category','fax'].includes(field),'INVALID');
        result[key][field]=text_(value[key][field],500);
      });
    }else if(key==='replyChannel'){
      assert_(['sms','email','both'].includes(value[key]),'INVALID');result[key]=value[key];
    }else if(key==='issuedAt'){
      assert_(Number.isSafeInteger(value[key]) && value[key]>0 && value[key]<=Date.now()+300000,'INVALID');result[key]=value[key];
    }else if(key==='formalQuoteRequested'){
      assert_(typeof value[key]==='boolean','INVALID');result[key]=value[key];
    }else fail_('INVALID');
  });
  if(result.customerPhone)assert_(/^0\d{8,10}$/.test(result.customerPhone.replace(/[\s()-]/g,'')),'INVALID');
  if(result.customerEmail)assert_(/^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(result.customerEmail),'INVALID');
  assert_(JSON.stringify(result).length<=12000,'INVALID');return result;
}
function confirmedFingerprint_(pricing,details){return hash_(canonical_({quotePricing:pricing,quoteDetails:details}));}
function confirmedState_(raw){
  if(raw.quoteVersion)return raw.quoteVersion;
  return {revision:raw.quotePricing?1:0,fingerprint:raw.quotePricing?confirmedFingerprint_(raw.quotePricing,{}):'',
    quoteDetails:{},operationId:'',updatedAt:'',delivery:null,lastDelivered:null,payment:null};
}
function confirmedBusy_(state){return state.delivery && ['reserved','sending','pending','unknown'].includes(state.delivery.state);}
function confirmedWrite_(record,raw,status){
  const encoded=JSON.stringify(raw);assert_(encoded.length<=45000,'NOT_READY');
  const props=PropertiesService.getScriptProperties(),next=record.row.slice();
  const sequence=record.entry?quoteNextSequence_(record.entries):
    Number(props.getProperty('LAST_SEQUENCE')||0)+1;
  assert_(Number.isSafeInteger(sequence) && sequence>Number(record.row[1]),'NOT_READY');
  if(!record.entry)props.setProperty('LAST_SEQUENCE',String(sequence));
  next[1]=sequence;next[9]=status||next[9];next[15]=encoded;
  quoteWriteVerified_(record.sheet,record.index+2,next);record.row=next;
  if(record.entry){record.entry.values[QI.seq]=sequence;record.entry.values[QI.status]=next[9];quoteSaveEntry_(record.entry);}
  return quoteResponseRow_(next);
}
function confirmedCheck_(state,body){
  assert_(Number.isSafeInteger(body.expectedRevision) && typeof body.quoteFingerprint==='string','INVALID');
  assert_(state.revision===body.expectedRevision && state.fingerprint===body.quoteFingerprint,'CONFLICT');
}
function confirmedAmount_(pricing){
  return pricing.items.reduce((sum,item)=>sum+item.quantity*item.unitPrice,pricing.taxAmount+(pricing.isPrepaid?pricing.shippingFee:0));
}
function getConfirmedQuote_(body){return lock_(()=>({quote:quoteResponseRow_(confirmedRecord_(body.requestId).row)}));}
function reviseConfirmedQuote_(body){
  assert_(UUID_PATTERN.test(body.operationId) && Number.isSafeInteger(body.expectedRevision) && body.expectedRevision>=0,'INVALID');
  return lock_(()=>{
    let record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=confirmedState_(raw);
    const request=quoteStoredData_(raw).request;assert_(request,'UPGRADE_REQUIRED');
    assert_(body.quoteDetails!==undefined,'INVALID');
    const pricing=quotePricing_(body.quotePricing,request),details=confirmedDetails_(body.quoteDetails,pricing.items.length);
    const fingerprint=confirmedFingerprint_(pricing,details);
    const history=namedSheet_(CONFIRMED_HISTORY,CONFIRMED_HISTORY_HEADERS,true),rows=namedRows_(history,7);
    const old=rows.find(row=>row[2]===body.operationId);
    if(old){
      assert_(old[0]===body.requestId && old[3]===fingerprint && old[4]===canonical_({quotePricing:pricing,quoteDetails:details}),'CONFLICT');
      if(raw.quoteVersion && state.revision>=Number(old[1]))return {quote:quoteResponseRow_(record.row),appliedRevision:Number(old[1]),duplicate:true};
    }
    assert_(!state.payment && !confirmedBusy_(state) && ['신규','견적확정','견적발송완료'].includes(String(record.row[9])),'CONFLICT');
    if(!old)assert_(state.revision===body.expectedRevision,'CONFLICT');
    // A staged history entry blocks other edits until its original operation is retried.
    assert_(!rows.some(row=>row[0]===body.requestId && Number(row[1])>state.revision && row[2]!==body.operationId),'BUSY');
    const revision=old?Number(old[1]):state.revision+(fingerprint===state.fingerprint?0:1);
    assert_(revision>0 && (revision===state.revision || revision===state.revision+1),'CONFLICT');
    const at=old?String(old[5]):new Date().toISOString(),expires=new Date(Date.parse(record.row[2])+QUOTE_RETENTION_MS).toISOString();
    const nextState=Object.assign({},state,{revision:revision,fingerprint:fingerprint,quoteDetails:details,operationId:body.operationId,updatedAt:at});
    assert_(JSON.stringify(Object.assign({},raw,{quotePricing:pricing,quoteVersion:nextState})).length<=45000,'NOT_READY');
    // Retain the server's old pricing as well as the new revision. Local-only divergent
    // copies must be offered to the owner by clients; never pick one by timestamp.
    if(raw.quotePricing && !rows.some(row=>row[0]===body.requestId && Number(row[1])===state.revision))
      quoteWriteVerified_(history,history.getLastRow()+1,[body.requestId,state.revision,'legacy:'+body.requestId,state.fingerprint,
        canonical_({quotePricing:raw.quotePricing,quoteDetails:state.quoteDetails}),at,expires]);
    if(!old)quoteWriteVerified_(history,history.getLastRow()+1,[body.requestId,revision,body.operationId,fingerprint,
      canonical_({quotePricing:pricing,quoteDetails:details}),at,expires]);
    if(twoBookActive_() && record.row[9]==='신규'){
      twoTabMoveConfirmed_(body.requestId,pricing);record=confirmedRecord_(body.requestId);raw=JSON.parse(record.row[15]);
    }
    raw.quotePricing=pricing;
    raw.quoteVersion=nextState;
    return {quote:confirmedWrite_(record,raw,state.lastDelivered && state.lastDelivered.fingerprint===fingerprint && state.lastDelivered.revision===revision?'견적발송완료':'견적확정'),appliedRevision:revision,duplicate:!!old};
  });
}
function reserveConfirmedDelivery_(body){
  assert_(QUOTE_DELIVERY_ID.test(body.deliveryId) && ['sms','email'].includes(body.channel) && ['server','external'].includes(body.mode),'INVALID');
  if(body.mode==='external')assert_(/^[a-f0-9]{64}$/.test(body.payloadFingerprint),'INVALID');
  return lock_(()=>{
    const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=confirmedState_(raw);
    assert_(raw.quoteVersion,'UPGRADE_REQUIRED');confirmedCheck_(state,body);
    assert_(!state.payment && ['견적확정','견적발송완료'].includes(String(record.row[9])),'CONFLICT');
    if(state.delivery && state.delivery.id===body.deliveryId){
      assert_(state.delivery.channel===body.channel && state.delivery.mode===body.mode,'CONFLICT');
      if(body.mode==='external')assert_(state.delivery.payloadFingerprint===body.payloadFingerprint,'CONFLICT');
      return {quote:quoteResponseRow_(record.row),duplicate:true};
    }
    assert_(!confirmedBusy_(state),'BUSY');
    assert_(!namedRows_(namedSheet_(QUOTE_DELIVERY_SHEET,QUOTE_DELIVERY_HEADERS,true),10).some(row=>row[0]===body.deliveryId),'CONFLICT');
    state.delivery={id:body.deliveryId,revision:state.revision,fingerprint:state.fingerprint,channel:body.channel,mode:body.mode,
      payloadFingerprint:body.mode==='external'?body.payloadFingerprint:'',state:'reserved',startedAt:new Date().toISOString()};
    return {quote:confirmedWrite_(record,raw),duplicate:false};
  });
}
/** External-app delivery requires the seller's explicit attestation. Never called automatically. */
function confirmConfirmedDelivery_(body){
  assert_(QUOTE_DELIVERY_ID.test(body.deliveryId) && body.confirmed===true,'INVALID');
  return lock_(()=>{
    const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=confirmedState_(raw);
    assert_(raw.quoteVersion,'UPGRADE_REQUIRED');confirmedCheck_(state,body);
    const delivery=state.delivery;
    assert_(delivery && delivery.id===body.deliveryId && delivery.revision===state.revision && !state.payment,'CONFLICT');
    if(state.lastDelivered && state.lastDelivered.deliveryId===body.deliveryId)return {quote:quoteResponseRow_(record.row),duplicate:true};
    assert_(delivery.mode==='external' && delivery.state==='reserved','CONFLICT');
    delivery.state='sent';delivery.sentAt=new Date().toISOString();
    const journal=namedSheet_(QUOTE_DELIVERY_SHEET,QUOTE_DELIVERY_HEADERS,true),rows=namedRows_(journal,10),old=rows.find(row=>row[0]===delivery.id);
    const receipt={sent:true,deliveryId:delivery.id,quoteRevision:state.revision,quoteFingerprint:state.fingerprint,
      payloadFingerprint:delivery.payloadFingerprint,evidence:'seller_confirmed',sentAt:delivery.sentAt};
    const fingerprint=hash_(canonical_({requestId:body.requestId,revision:state.revision,channel:delivery.channel,
      quoteFingerprint:state.fingerprint,payloadFingerprint:delivery.payloadFingerprint}));
    if(old)assert_(old[6]===fingerprint,'CONFLICT');
    else quoteWriteVerified_(journal,journal.getLastRow()+1,[delivery.id,'external_'+delivery.channel,body.requestId,'SENT','','',fingerprint,
      delivery.startedAt,delivery.sentAt,JSON.stringify(receipt)]);
    state.lastDelivered={revision:state.revision,fingerprint:state.fingerprint,deliveryId:delivery.id,channel:delivery.channel,
      payloadFingerprint:delivery.payloadFingerprint,evidence:'seller_confirmed',sentAt:delivery.sentAt};
    return {quote:confirmedWrite_(record,raw,'견적발송완료'),duplicate:false};
  });
}
function cancelConfirmedDelivery_(body){
  assert_(QUOTE_DELIVERY_ID.test(body.deliveryId) && body.notSent===true,'INVALID');
  return lock_(()=>{
    const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=confirmedState_(raw);
    assert_(raw.quoteVersion,'UPGRADE_REQUIRED');confirmedCheck_(state,body);
    assert_(state.delivery && state.delivery.id===body.deliveryId && state.delivery.state==='reserved','CONFLICT');
    state.delivery.state='cancelled';return {quote:confirmedWrite_(record,raw)};
  });
}
function confirmedStartSend_(body,channel){
  if(!body.requestId || channel==='report_email')return;
  const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]);
  if(!raw.quoteVersion)return; // Unmigrated clients retain their existing contract.
  const state=raw.quoteVersion;
  assert_(QUOTE_DELIVERY_ID.test(body.deliveryId),'UPGRADE_REQUIRED');confirmedCheck_(state,body);
  const field=channel==='sms'?'customerPhone':'customerEmail',details=state.quoteDetails||{};
  const expected=Object.prototype.hasOwnProperty.call(details,field)?details[field]:raw.request[field];
  const normalize=value=>channel==='sms'?String(value||'').replace(/\D/g,''):String(value||'').trim();
  assert_(expected && normalize(expected)===normalize(channel==='sms'?body.recipientPhone:body.recipientEmail),'CONFLICT');
  assert_(!state.payment && state.delivery && state.delivery.id===body.deliveryId && state.delivery.mode==='server' &&
    state.delivery.channel===channel && state.delivery.state==='reserved','CONFLICT');
  state.delivery.state='sending';state.delivery.payloadFingerprint=quoteDeliveryFingerprint_(body,channel);confirmedWrite_(record,raw);
}
function confirmedFinishSend_(requestId,id,channel,result,deliveryState){
  if(!requestId || !['sms','email'].includes(channel))return;
  const record=confirmedRecord_(requestId),raw=JSON.parse(record.row[15]),state=raw.quoteVersion;
  if(!state)return;
  // A late retry of an older completed send must never replace the current job.
  if(!state.delivery || state.delivery.id!==id)return;
  const next=deliveryState==='REJECTED'?'failed':channel==='sms'?'pending':'sent';
  if(next==='pending' && ['sent','failed'].includes(state.delivery.state))return;
  if(state.delivery.state===next)return;
  state.delivery.state=next;state.delivery.messageId=result.messageId||'';state.delivery.sentAt=result.sentAt||'';
  if(next==='sent')state.lastDelivered={revision:state.delivery.revision,fingerprint:state.delivery.fingerprint,
    deliveryId:id,channel:channel,payloadFingerprint:state.delivery.payloadFingerprint,evidence:'server',sentAt:result.sentAt};
  confirmedWrite_(record,raw,next==='sent'?'견적발송완료':undefined);
}
/** Called only after SOLAPI status was fetched outside the lock. */
function confirmedSmsResult_(body,result){
  if(!body.requestId || !body.deliveryId)return;
  lock_(()=>{
    const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=raw.quoteVersion;
    assert_(state && state.delivery && state.delivery.id===body.deliveryId && state.delivery.messageId===body.messageId,'CONFLICT');
    if(!result.final)return;
    const next=result.success?'sent':'failed';if(state.delivery.state===next)return;
    state.delivery.state=next;
    if(result.success)state.lastDelivered={revision:state.delivery.revision,fingerprint:state.delivery.fingerprint,
      deliveryId:body.deliveryId,channel:'sms',payloadFingerprint:state.delivery.payloadFingerprint,evidence:'server',sentAt:state.delivery.sentAt};
    confirmedWrite_(record,raw,result.success && state.delivery.revision===state.revision?'견적발송완료':undefined);
  });
}
function confirmedPaymentCheck_(raw,state,body){
  confirmedCheck_(state,body);
  assert_(state.revision>0 && state.lastDelivered && state.lastDelivered.revision===state.revision &&
    state.lastDelivered.fingerprint===state.fingerprint && !confirmedBusy_(state),'CONFLICT');
  assert_(Number.isSafeInteger(body.amount) && body.amount===confirmedAmount_(raw.quotePricing),'CONFLICT');
}
function confirmQuotePayment_(body){
  assert_(UUID_PATTERN.test(body.operationId),'INVALID');const number=text_(body.orderNumber,120,1);
  assert_(/^[A-Za-z0-9_-]{1,120}$/.test(number),'INVALID');
  return lock_(()=>{
    const record=confirmedRecord_(body.requestId),raw=JSON.parse(record.row[15]),state=raw.quoteVersion;
    assert_(state,'UPGRADE_REQUIRED');confirmedPaymentCheck_(raw,state,body);
    if(state.payment){assert_(state.payment.operationId===body.operationId && state.payment.orderNumber===number,'CONFLICT');
      return {quote:quoteResponseRow_(record.row),payment:state.payment,duplicate:true};}
    assertSoldNumberReserved_(number);
    const records=saleRecords_(),existing=records.find(r=>r.row[0]===number);
    const sale={sourceRequestId:body.requestId,metadata:{orderNumber:number},expectedRevision:state.revision,quoteFingerprint:state.fingerprint,
      amount:body.amount};
    reserveSaleSource_(sale,records,existing,false);reserveSaleSource_(sale,records,existing,true);
    state.payment={operationId:body.operationId,orderNumber:number,revision:state.revision,fingerprint:state.fingerprint,
      amount:body.amount,confirmedAt:new Date().toISOString()};
    return {quote:confirmedWrite_(record,raw,'입금확인'),payment:state.payment,duplicate:false};
  });
}
/** Shared guard covers old save_sale callers too; status-only checks cannot prevent races. */
function confirmedSaleGuard_(body,existing){
  if(!body.sourceRequestId)return;
  let record;
  try{record=confirmedRecord_(body.sourceRequestId,true);}catch(error){if(existing && error.code==='INVALID')return;throw error;}
  const raw=JSON.parse(record.row[15]),state=raw.quoteVersion;if(!state)return;
  if(body.metadata.items){
    const pricing=raw.quotePricing;
    assert_(body.metadata.totalPrice+body.metadata.taxAmount===confirmedAmount_(pricing) &&
      body.metadata.isPrepaid===pricing.isPrepaid && body.metadata.shippingFee===pricing.shippingFee && body.metadata.taxAmount===pricing.taxAmount &&
      canonical_(body.metadata.items)===canonical_(pricing.items.map(item=>({species:item.species.trim(),quantity:item.quantity,unitPrice:item.unitPrice}))),'CONFLICT');
  }
  if(state.payment){assert_(state.payment.orderNumber===body.metadata.orderNumber,'CONFLICT');return;}
  if(existing)return;
  assert_(body.expectedRevision!==undefined && body.quoteFingerprint!==undefined,'UPGRADE_REQUIRED');
  const amount=body.amount!==undefined?body.amount:body.metadata.totalPrice+body.metadata.taxAmount;
  confirmedPaymentCheck_(raw,state,Object.assign({},body,{amount:amount}));
}
function confirmedSaleSaved_(body){
  if(!body.sourceRequestId)return;
  const record=confirmedRecord_(body.sourceRequestId,true),raw=JSON.parse(record.row[15]),state=raw.quoteVersion;
  if(!state || state.payment)return;
  state.payment={operationId:body.eventId,orderNumber:body.metadata.orderNumber,revision:state.revision,fingerprint:state.fingerprint,
    amount:body.metadata.totalPrice+body.metadata.taxAmount,confirmedAt:new Date().toISOString()};
  confirmedWrite_(record,raw);
}
/** No new customer copy survives the existing retention window or an explicit delete. */
function purgeConfirmedQuoteHistory_(requestId){
  const props=PropertiesService.getScriptProperties(),bookId=twoBookActive_()?props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID'):props.getProperty('SPREADSHEET_ID');
  if(!bookId || !SpreadsheetApp.openById(bookId).getSheetByName(CONFIRMED_HISTORY))return;
  const sheet=namedSheet_(CONFIRMED_HISTORY,CONFIRMED_HISTORY_HEADERS,false);
  namedRows_(sheet,7).forEach((row,index)=>{
    if(row[4] && (requestId?row[0]===requestId:Date.parse(String(row[6]))<=Date.now()))sheet.getRange(index+2,5).clearContent();
  });
}

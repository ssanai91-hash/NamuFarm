/** Private customer inquiries. Independent annual workbooks; no quote/order rows. */
const INQUIRY_TAB = '고객문의';
const INQUIRY_HEADERS = ['문의ID','문의접수번호','변경순번','접수시각(UTC)','접수시각(한국)','문의종류','이름','연락처','이메일','문의내용','상태','답변내용','답변방법','답변완료(UTC)','최종변경(UTC)','revision','최종변경ID','접수내용해시','관리JSON'];
const IC = {id:0,number:1,sequence:2,created:3,seoul:4,category:5,name:6,phone:7,email:8,message:9,status:10,reply:11,channel:12,answered:13,updated:14,revision:15,event:16,hash:17,raw:18};
const INQUIRY_UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

function inquiryBooks_() {
  const map=JSON.parse(PropertiesService.getScriptProperties().getProperty('INQUIRY_BOOKS')||'{}');
  assert_(map && !Array.isArray(map) && typeof map==='object','NOT_READY');
  Object.keys(map).forEach(year=>assert_(/^20\d{2}$/.test(year) && typeof map[year]==='string' && map[year].length>0,'NOT_READY'));
  return map;
}
function inquirySheet_(year,create) {
  assert_(/^20\d{2}$/.test(String(year)),'INVALID');
  const props=PropertiesService.getScriptProperties(),map=inquiryBooks_(); let book;
  if(map[year])book=SpreadsheetApp.openById(map[year]);
  else {
    assert_(create,'NOT_READY');
    book=SpreadsheetApp.create('나무팜_고객문의_'+year);
    // Register before initialization, so interrupted setup reuses the same private file.
    map[year]=book.getId(); props.setProperty('INQUIRY_BOOKS',JSON.stringify(map));
  }
  let sheet=book.getSheetByName(INQUIRY_TAB);
  if(!sheet && create) {
    const blank=book.getSheets().find(s=>s.getLastRow()===0);
    sheet=blank?blank.setName(INQUIRY_TAB):book.insertSheet(INQUIRY_TAB);
  }
  assert_(sheet,'NOT_READY');
  if(create && sheet.getLastRow()===0) {
    sheet.getRange(1,1,1,INQUIRY_HEADERS.length).setValues([INQUIRY_HEADERS]).setBackground('#174e36').setFontColor('#ffffff').setFontWeight('bold');
    sheet.setFrozenRows(1); book.setSpreadsheetTimeZone('Asia/Seoul');
    sheet.setColumnWidth(2,220);sheet.setColumnWidth(10,380);sheet.setColumnWidth(12,380);
    sheet.getRange('H:I').setNumberFormat('@'); SpreadsheetApp.flush();
  }
  assert_(JSON.stringify(sheet.getRange(1,1,1,INQUIRY_HEADERS.length).getValues()[0])===JSON.stringify(INQUIRY_HEADERS),'NOT_READY');
  return sheet;
}
/** Owner runs once in the editor; safe to retry. Does not send messages. */
function setupCustomerInquiries() {
  return lock_(()=>{
    const props=PropertiesService.getScriptProperties();
    assert_((props.getProperty('WEB_SECRET')||'').length>=32,'NOT_READY');
    const year=receiptDay_(new Date().toISOString()).slice(0,4),sheet=inquirySheet_(year,true);
    if(!ScriptApp.getProjectTriggers().some(t=>t.getHandlerFunction()==='purgeExpiredInquiries'))ScriptApp.newTrigger('purgeExpiredInquiries').timeBased().everyDays(1).create();
    props.setProperty('INQUIRY_SETUP','1');
    const result={ready:true,name:'나무팜_고객문의_'+year,url:sheet.getParent().getUrl()};
    console.log(JSON.stringify(result)); return result;
  });
}
function customerInquiryReady_() {
  try {
    if(PropertiesService.getScriptProperties().getProperty('INQUIRY_SETUP')!=='1')return false;
    const years=Object.keys(inquiryBooks_()).sort();if(!years.length)return false;
    inquirySheet_(years[years.length-1],false);return true;
  } catch(_){return false;}
}
function inquiryRecords_() {
  const result=[],ids=new Set();
  Object.keys(inquiryBooks_()).sort().forEach(year=>{
    const sheet=inquirySheet_(year,false);
    const rows=sheet.getLastRow()>1?sheet.getRange(2,1,sheet.getLastRow()-1,INQUIRY_HEADERS.length).getValues():[];
    rows.forEach((row,index)=>{
      assert_(INQUIRY_UUID.test(String(row[IC.id])) && !ids.has(String(row[IC.id])),'NOT_READY');ids.add(String(row[IC.id]));
      assert_(Number.isSafeInteger(Number(row[IC.sequence])) && Number(row[IC.sequence])>0,'NOT_READY');
      result.push({sheet:sheet,row:row,index:index});
    });
  });
  return result;
}
function inquiryRecord_(id) {
  assert_(INQUIRY_UUID.test(id),'INVALID');
  const record=inquiryRecords_().find(r=>r.row[IC.id]===id);assert_(record,'INVALID');return record;
}
function inquiryWrite_(sheet,rowNumber,row) {
  if(rowNumber>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),Math.max(100,rowNumber-sheet.getMaxRows()));
  sheet.getRange(rowNumber,1,1,INQUIRY_HEADERS.length).setNumberFormat('@').setValues([row]);SpreadsheetApp.flush();
  const saved=sheet.getRange(rowNumber,1,1,INQUIRY_HEADERS.length).getValues()[0];
  // Formula protection may consume a leading apostrophe in human-readable cells.
  assert_(row.every((value,i)=>String(saved[i])===String(value) || i>=IC.category && i<=IC.reply && String(value).startsWith("'") && String(saved[i])===String(value).slice(1)),'NOT_READY');
}
function inquirySequence_() {
  const props=PropertiesService.getScriptProperties();
  const stored=Number(props.getProperty('INQUIRY_SEQUENCE')||0);
  assert_(Number.isSafeInteger(stored) && stored>=0,'NOT_READY');
  const floor=inquiryRecords_().reduce((max,r)=>Math.max(max,Number(r.row[IC.sequence])),stored),next=floor+1;
  assert_(Number.isSafeInteger(next),'NOT_READY');props.setProperty('INQUIRY_SEQUENCE',String(next));return next;
}
function inquiryChange_(record,raw,eventId) {
  const row=record.row.slice();row[IC.raw]=JSON.stringify(raw);row[IC.sequence]=inquirySequence_();row[IC.updated]=new Date().toISOString();
  row[IC.revision]=Number(row[IC.revision])+1;row[IC.event]=eventId;
  if(!raw.expired){row[IC.reply]=cell_(raw.reply.text);row[IC.channel]=raw.reply.channel;}
  inquiryWrite_(record.sheet,record.index+2,row);record.row=row;return row;
}
function inquiryReceipt_(row) {return {kind:'customer_inquiry',requestId:String(row[IC.id]),inquiryNumber:String(row[IC.number]),createdAt:String(row[IC.created])};}
function inquiryResponse_(row) {
  const raw=JSON.parse(row[IC.raw]);
  const base=Object.assign(inquiryReceipt_(row),{sequence:Number(row[IC.sequence]),revision:Number(row[IC.revision]),updatedAt:String(row[IC.updated]),status:inquiryStatus_(row),lastEventId:String(row[IC.event]||'')});
  return raw.expired?Object.assign(base,{expired:true}):Object.assign(base,{inquiry:raw.inquiry,reply:raw.reply,delivery:raw.delivery||null,history:raw.history||[],answeredAt:String(row[IC.answered]||'')});
}
function validateInquiry_(raw) {
  assert_(raw && typeof raw==='object' && !Array.isArray(raw),'INVALID');
  assert_(Object.keys(raw).sort().join(',')==='category,consent,customerEmail,customerName,customerPhone,message,requestId,website','INVALID');
  assert_(INQUIRY_UUID.test(raw.requestId) && raw.website==='' && raw.consent===true,'INVALID');
  assert_(['묘목·재배','구매·배송','기타'].includes(raw.category),'INVALID');
  const phone=text_(raw.customerPhone,20,9).replace(/[\s()-]/g,'');assert_(/^0\d{8,10}$/.test(phone),'INVALID');
  const email=text_(raw.customerEmail,254);assert_(!email || /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(email),'INVALID');
  return {requestId:raw.requestId,category:raw.category,customerName:text_(raw.customerName,50,2),customerPhone:phone,customerEmail:email,message:text_(raw.message,2000,2),consent:true,website:''};
}
function submitCustomerInquiry_(body) {
  assert_(body.protocolVersion===1,'UPGRADE_REQUIRED');
  assert_(PropertiesService.getScriptProperties().getProperty('INQUIRY_SETUP')==='1','NOT_READY');
  const input=validateInquiry_(body.inquiry);
  assert_(body.requestId===input.requestId && /^[a-f0-9]{64}$/.test(body.fingerprint) && /^[a-f0-9]{64}$/.test(body.rateKey),'INVALID');
  return lock_(()=>{
    const rows=inquiryRecords_(),existing=rows.find(r=>r.row[IC.id]===input.requestId);
    // Check every registered year before rate limits or allocating a new number.
    if(existing){assert_(existing.row[IC.hash]===body.fingerprint,'CONFLICT');return Object.assign(inquiryReceipt_(existing.row),{duplicate:true});}
    const cache=CacheService.getScriptCache(),secret=PropertiesService.getScriptProperties().getProperty('WEB_SECRET');
    const keys=['inq-rate:'+body.rateKey,'inq-phone:'+hash_(secret+input.customerPhone)],counts=keys.map(key=>Number(cache.get(key)||0));
    assert_(counts.every(n=>Number.isFinite(n) && n<5),'RATE_LIMITED');keys.forEach((key,i)=>cache.put(key,String(counts[i]+1),3600));
    const props=PropertiesService.getScriptProperties(),created=new Date().toISOString(),day=receiptDay_(created),key='INQUIRY_COUNTER:'+day;
    const floor=rows.reduce((max,r)=>String(r.row[IC.number]).startsWith('INQ-'+day+'-')?Math.max(max,Number(String(r.row[IC.number]).split('-')[2])||0):max,0);
    const stored=Number(props.getProperty(key)||0),count=Math.max(stored,floor)+1;assert_(Number.isSafeInteger(count) && count>0,'NOT_READY');props.setProperty(key,String(count));
    const sheet=inquirySheet_(day.slice(0,4),true),raw={inquiry:input,reply:{text:'',channel:input.customerEmail?'email':'sms'},delivery:null,history:[]};
    const row=[input.requestId,'INQ-'+day+'-'+String(count).padStart(4,'0'),inquirySequence_(),created,receiptTime_(created),input.category,cell_(input.customerName),cell_(input.customerPhone),cell_(input.customerEmail),cell_(input.message),'open','',raw.reply.channel,'',created,1,'',body.fingerprint,JSON.stringify(raw)];
    inquiryWrite_(sheet,sheet.getLastRow()+1,row);return Object.assign(inquiryReceipt_(row),{duplicate:false});
  });
}
/** Authenticated app delta feed, across ALL years, including expiry tombstones. */
function listCustomerInquiries_(body) {
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0,'INVALID');
  const limit=body.limit===undefined?100:body.limit;assert_(Number.isSafeInteger(limit) && limit>=1 && limit<=100,'INVALID');
  assert_(customerInquiryReady_(),'NOT_READY');
  return lock_(()=>{
    const all=inquiryRecords_().sort((a,b)=>Number(a.row[IC.sequence])-Number(b.row[IC.sequence]));
    const matches=all.filter(r=>Number(r.row[IC.sequence])>body.afterSequence),page=matches.slice(0,limit);
    return {inquiries:page.map(r=>inquiryResponse_(r.row)),hasMore:matches.length>page.length,nextSequence:page.length?Number(page[page.length-1].row[IC.sequence]):body.afterSequence};
  });
}
/** Draft save never marks an inquiry answered. Revision + event ID protect offline edits. */
function saveInquiryReply_(body) {
  assert_(INQUIRY_UUID.test(body.eventId) && Number.isSafeInteger(body.revision) && body.revision>=1,'INVALID');
  const text=text_(body.replyText,2000),channel=body.replyChannel;assert_(['sms','email'].includes(channel),'INVALID');
  assert_(channel!=='sms' || text.length<=900,'INVALID');
  return lock_(()=>{
    const record=inquiryRecord_(body.requestId),raw=JSON.parse(record.row[IC.raw]);assert_(!raw.expired,'CONFLICT');
    if(record.row[IC.event]===body.eventId){assert_(raw.reply.text===text && raw.reply.channel===channel,'CONFLICT');return {inquiry:inquiryResponse_(record.row),duplicate:true};}
    assert_(Number(record.row[IC.revision])===body.revision,'CONFLICT');
    assert_(!raw.delivery || ['sent','success','failed'].includes(raw.delivery.state),'CONFLICT');
    assert_(channel!=='email' || raw.inquiry.customerEmail,'INVALID');
    if(raw.delivery && !(raw.history||[]).some(h=>h.id===raw.delivery.id)){
      raw.history=raw.history||[];raw.history.push({kind:'reply',id:raw.delivery.id,text:raw.reply.text,channel:raw.reply.channel,at:raw.delivery.startedAt||record.row[IC.updated],state:raw.delivery.state});
    }
    raw.reply={text:text,channel:channel};raw.delivery=null;record.row[IC.status]='open';record.row[IC.answered]='';
    inquiryChange_(record,raw,body.eventId);return {inquiry:inquiryResponse_(record.row),duplicate:false};
  });
}
function inquiryMailBody_(raw,number) {return '[나무팜 고객문의 답변]\n문의번호: '+number+'\n\n'+raw.inquiry.customerName+'님, 문의주셔서 감사합니다.\n\n[문의 내용]\n'+raw.inquiry.message+'\n\n[답변]\n'+raw.reply.text;}
/** Send only the saved draft to the stored customer. Never auto-resend an uncertain send. */
function sendInquiryReply_(body) {
  assert_(INQUIRY_UUID.test(body.deliveryId) && Number.isSafeInteger(body.revision),'INVALID');
  return lock_(()=>{
    const record=inquiryRecord_(body.requestId),raw=JSON.parse(record.row[IC.raw]);assert_(!raw.expired,'CONFLICT');
    if(raw.delivery && raw.delivery.id===body.deliveryId || (raw.history||[]).some(h=>h.kind==='reply' && h.id===body.deliveryId))return {inquiry:inquiryResponse_(record.row),duplicate:true};
    assert_(Number(record.row[IC.revision])===body.revision && raw.reply.text.length>0 && (!raw.delivery || raw.delivery.state==='failed'),'CONFLICT');
    const message=raw.reply.channel==='email'?inquiryMailBody_(raw,record.row[IC.number]):'[나무팜 고객문의 답변]\n문의번호: '+record.row[IC.number]+'\n\n'+raw.reply.text;
    if(raw.reply.channel==='email'){assert_(raw.inquiry.customerEmail && MailApp.getRemainingDailyQuota()>0,'NOT_READY');}
    else assert_(quoteSmsSettings_().configured,'NOT_READY');
    assert_(raw.reply.channel!=='sms' || message.length<=1000,'INVALID');
    raw.history=raw.history||[];
    raw.history.push({kind:'reply',id:body.deliveryId,text:raw.reply.text,channel:raw.reply.channel,at:new Date().toISOString(),state:'sending'});
    raw.delivery={id:body.deliveryId,channel:raw.reply.channel,state:'sending',startedAt:new Date().toISOString()};
    inquiryChange_(record,raw,body.deliveryId);
    try {
      if(raw.reply.channel==='email') {
        MailApp.sendEmail({to:raw.inquiry.customerEmail,subject:'[나무팜] 고객문의 답변 · '+record.row[IC.number],body:message,name:'나무팜농원'});
        raw.delivery.state='sent';raw.delivery.sentAt=new Date().toISOString();record.row[IC.status]='closed';record.row[IC.answered]=raw.delivery.sentAt;
      } else {
        // Existing SOLAPI validation/authorization; no quote request ID/state transition.
        const result=sendQuoteSms_({recipientPhone:raw.inquiry.customerPhone,text:message});
        assert_(result.messageId,'UNAVAILABLE');
        raw.delivery.state='pending';raw.delivery.messageId=result.messageId;raw.delivery.sentAt=result.sentAt;
      }
    } catch(_) {raw.delivery.state='unknown';}
    inquiryHistoryDelivery_(raw);
    inquiryChange_(record,raw,body.deliveryId);return {inquiry:inquiryResponse_(record.row),duplicate:false};
  });
}
function inquirySmsStatus_(body) {
  return lock_(()=>{
    const record=inquiryRecord_(body.requestId),raw=JSON.parse(record.row[IC.raw]);
    assert_(!raw.expired && raw.delivery && raw.delivery.channel==='sms' && raw.delivery.messageId,'INVALID');
    if(['success','failed'].includes(raw.delivery.state))return {inquiry:inquiryResponse_(record.row)};
    const result=quoteSmsStatus_({messageId:raw.delivery.messageId});raw.delivery.state=result.state;raw.delivery.statusCode=result.statusCode;
    if(result.success){record.row[IC.status]='closed';record.row[IC.answered]=record.row[IC.answered]||new Date().toISOString();}
    inquiryHistoryDelivery_(raw);
    inquiryChange_(record,raw,raw.delivery.id);return {inquiry:inquiryResponse_(record.row)};
  });
}
/** Pending questions remain available across years; completed personal data expires in 30 days. */
function purgeExpiredInquiries() {
  if(PropertiesService.getScriptProperties().getProperty('INQUIRY_SETUP')!=='1')return;
  lock_(()=>inquiryRecords_().forEach(record=>{
    const row=record.row;if(inquiryStatus_(row)!=='closed' || !row[IC.answered] || Date.parse(String(row[IC.answered]))+30*86400000>Date.now())return;
    for(let c=IC.name;c<=IC.message;c++)row[c]='';row[IC.reply]='';row[IC.channel]='';row[IC.status]='closed';
    inquiryChange_(record,{expired:true},'');
  }));
}


/** Workflow state is independent of delivery and privacy expiry. Legacy rows are read compatibly. */
function inquiryStatus_(row) {return ['closed','답변 완료','보관종료'].includes(String(row[IC.status]))?'closed':'open';}
function inquiryHistoryDelivery_(raw) {
  const entry=(raw.history||[]).find(h=>h.id===raw.delivery.id);
  if(entry){entry.state=raw.delivery.state;entry.messageId=raw.delivery.messageId||'';entry.statusCode=raw.delivery.statusCode||'';}
}
/** Explicit phone resolution or reopening. Never changes the original question or sends a message. */
function setInquiryStatus_(body) {
  assert_(INQUIRY_UUID.test(body.eventId) && Number.isSafeInteger(body.revision) && ['open','closed'].includes(body.status),'INVALID');
  const note=text_(body.note,500,1);
  return lock_(()=>{
    const record=inquiryRecord_(body.requestId),raw=JSON.parse(record.row[IC.raw]);assert_(!raw.expired,'CONFLICT');
    raw.history=raw.history||[];
    const previous=raw.history.find(h=>h.id===body.eventId);
    if(previous){assert_(previous.kind==='status' && previous.status===body.status && previous.note===note,'CONFLICT');return {inquiry:inquiryResponse_(record.row),duplicate:true};}
    assert_(Number(record.row[IC.revision])===body.revision,'CONFLICT');
    assert_(!raw.delivery || !['sending','pending'].includes(raw.delivery.state),'CONFLICT');
    const at=new Date().toISOString();
    raw.history.push({kind:'status',id:body.eventId,status:body.status,note:note,at:at});
    record.row[IC.status]=body.status;record.row[IC.answered]=body.status==='closed'?at:'';
    inquiryChange_(record,raw,body.eventId);return {inquiry:inquiryResponse_(record.row),duplicate:false};
  });
}

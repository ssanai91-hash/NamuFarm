/**
 * Dedicated, authenticated RFQ endpoint. Deploy separately from the legacy order API.
 * All state is in the private Sheet / Script Properties, never browser code.
 */
const RFQ_SHEET = '웹견적요청';
const RFQ_HEADERS = ['요청번호','접수순번','접수시각(UTC)','성함','연락처','배송지역','희망묘목','품목JSON','요청사항','상태','알림상태','내용해시','알림시도','다음알림(UTC)','오류코드','원본JSON'];
const RFQ_COL = { id:0, sequence:1, created:2, name:3, phone:4, region:5, summary:6, items:7, memo:8, status:9, notification:10, hash:11, attempts:12, next:13, error:14, raw:15 };
// Preserve A:P and UUID identities used by existing Android/PC clients.
const RFQ_RECEIPT_HEADERS = ['견적접수번호','접수시각(한국)'];
// Existing receipts keep their identity; only new receipts use NAMU.
const RFQ_RECEIPT_PATTERN = /^(?:NAMU|NFQ)-(\d{8})-(\d{4,16})$/;

function doPost(e) {
  try {
    const text = e && e.postData && e.postData.contents;
    assert_(typeof text === 'string' && text.length <= 3000000, 'INVALID');
    const body = JSON.parse(text);
    if(body.action!=='send_quote_email')assert_(text.length<=65000,'INVALID');
    let value;
    if (body.action === 'submit_request') {
      const secret = PropertiesService.getScriptProperties().getProperty('WEB_SECRET');
      assert_(secret && secret.length >= 32 && equal_(body.webSecret, secret), 'UNAUTHORIZED');
      value = submitRequest_(body);
      // The durable PENDING outbox is delivered by the existing minute trigger.
      // Return the committed receipt without waiting for FCM or another lock.
    } else if (body.action === 'pair_device') value = pairDevice_(body);
    else if (body.action === 'connect_app') value = connectApp_(body);
    else {
      const device = authenticateDevice_(body.deviceToken);
      if (body.action === 'list_requests') value = listRequests_(body);
      else if (body.action === 'snapshot_requests') value = snapshotRequests_(body);
      else if (body.action === 'update_request_status') value = updateRequestStatus_(body);
      else if (body.action === 'save_quote_pricing') value = saveQuotePricing_(body);
      else if (body.action === 'email_profile') value = quoteEmailProfile_();
      else if (body.action === 'send_quote_email') value = sendQuoteEmail_(body);
      else if (body.action === 'sms_profile') value = quoteSmsProfile_();
      else if (body.action === 'send_quote_sms') value = sendQuoteSms_(body);
      else if (body.action === 'quote_sms_status') value = quoteSmsStatus_(body);
      else if (body.action === 'register_push') value = registerPush_(body, device);
      else if (body.action === 'save_sale') value = saveSale_(body);
      else if (body.action === 'list_sales') value = listSales_(body);
      else if (body.action === 'hide_sale') value = hideSale_(body);
      else if (body.action === 'sales_report') value = salesReport_(body);
      else if (body.action === 'close_request') value = closeRequest_(body);
      else fail_('INVALID_ACTION');
    }
    return json_(Object.assign({ ok: true }, value));
  } catch (error) {
    // Do not leak customer data, sheet identifiers, or credentials in responses/logs.
    const code = ['INVALID','UNAUTHORIZED','CONFLICT','RATE_LIMITED','NOT_READY','BUSY','INVALID_ACTION','UPGRADE_REQUIRED'].indexOf(error.code) >= 0 ? error.code : 'UNAVAILABLE';
    return json_({ ok: false, code: code });
  }
}
function doGet() { return json_(secureServiceInfo_()); }
function json_(value) { return ContentService.createTextOutput(JSON.stringify(value)).setMimeType(ContentService.MimeType.JSON); }
function fail_(code) { const error = new Error(code); error.code = code; throw error; }
function assert_(value, code) { if (!value) fail_(code || 'INVALID'); }
function equal_(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  let mismatch = a.length ^ b.length;
  for (let i = 0; i < b.length; i++) mismatch |= (a.charCodeAt(i) || 0) ^ b.charCodeAt(i);
  return mismatch === 0;
}
function hash_(value) { return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, value, Utilities.Charset.UTF_8).map(x => ((x + 256) % 256).toString(16).padStart(2, '0')).join(''); }
function token_() { return Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, ''); }
function lock_(work) { const lock = LockService.getScriptLock(); if (!lock.tryLock(15000)) fail_('BUSY'); try { return work(); } finally { lock.releaseLock(); } }
function text_(value, max, min) { assert_(typeof value === 'string' && value.trim().length >= (min || 0) && value.trim().length <= max && !/[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(value)); return value.trim(); }
function validateRequest_(raw) {
  assert_(raw && typeof raw === 'object' && raw.website === '');
  const current = raw.processingBasis === 'quote_request' && !Object.prototype.hasOwnProperty.call(raw, 'consent');
  const legacy = raw.processingBasis === undefined && raw.consent === true;
  assert_(current || legacy);
  assert_(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(raw.requestId));
  const phone = text_(raw.customerPhone, 20, 9).replace(/[\s()-]/g, '');
  assert_(/^0\d{8,10}$/.test(phone));
  assert_(Array.isArray(raw.items) && raw.items.length > 0 && raw.items.length <= 20);
  const request = { requestId: raw.requestId.toLowerCase(), customerName: text_(raw.customerName, 50, 2), customerPhone: phone,
    region: text_(raw.region, 100, 2), items: raw.items.map(item => {
      assert_(item && Number.isSafeInteger(item.quantity) && item.quantity > 0 && item.quantity <= 1000000);
      return { species: text_(item.species, 80, 1), specification: text_(item.specification, 120), quantity: item.quantity };
    }), message: text_(raw.message, 1000), ...(current ? { processingBasis: 'quote_request' } : { consent: true }) };
  // Keep v1/v2 canonical JSON byte-for-byte when the new fields were not supplied.
  if (['customerEmail','formalQuoteRequested','recipientName','replyChannel'].some(key => Object.prototype.hasOwnProperty.call(raw, key))) {
    const email = text_(raw.customerEmail === undefined ? '' : raw.customerEmail, 254);
    assert_(!email || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email));
    const formal = raw.formalQuoteRequested === undefined ? false : raw.formalQuoteRequested;
    assert_(typeof formal === 'boolean');
    const recipient = text_(raw.recipientName === undefined ? '' : raw.recipientName, 100);
    assert_(!/[\x00-\x1f]/.test(recipient));
    const channel = raw.replyChannel === undefined ? 'sms' : raw.replyChannel;
    assert_(['sms','email','both'].indexOf(channel) >= 0);
    assert_(channel === 'sms' || email.length > 0);
    Object.assign(request, {customerEmail:email, formalQuoteRequested:formal, recipientName:recipient, replyChannel:channel});
  }
  return request;
}
function sheet_() {
  const id = PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID');
  assert_(id, 'NOT_READY');
  const sheet = SpreadsheetApp.openById(id).getSheetByName(RFQ_SHEET);
  assert_(sheet, 'NOT_READY');
  assert_(JSON.stringify(sheet.getRange(1,1,1,RFQ_HEADERS.length).getValues()[0]) === JSON.stringify(RFQ_HEADERS), 'NOT_READY');
  return sheet;
}
// Prefix formula-like customer strings, preserving their exact original text on read.
function cell_(value) { const text = String(value); return /^[=+\-@']/.test(text) ? "'" + text : text; }
function rows_(sheet) { const count = sheet.getLastRow() - 1; return count > 0 ? sheet.getRange(2,1,count,RFQ_HEADERS.length).getValues() : []; }
function receiptRows_(sheet) { const count=sheet.getLastRow()-1; return count>0 ? sheet.getRange(2,1,count,18).getValues() : []; }
function receiptTime_(createdAt) {
  const time=Date.parse(String(createdAt)); assert_(Number.isFinite(time),'NOT_READY');
  return new Date(time+9*3600000).toISOString().slice(0,19).replace('T',' ');
}
function receiptDay_(createdAt) { return receiptTime_(createdAt).slice(0,10).replace(/-/g,''); }
function receiptColumnsReady_(sheet) {
  return JSON.stringify(sheet.getRange(1,17,1,2).getValues()[0])===JSON.stringify(RFQ_RECEIPT_HEADERS);
}
function receiptCounter_(row) {
  const match=RFQ_RECEIPT_PATTERN.exec(String(row[16]||''));
  assert_(match && match[1]===receiptDay_(row[2]) && Number.isSafeInteger(Number(match[2])) && Number(match[2])>0,'NOT_READY');
  return Number(match[2]);
}
function reserveReceipt_(props,day,floor) {
  const key='RFQ_RECEIPT_COUNTER:'+day, stored=Number(props.getProperty(key)||0);
  assert_(Number.isSafeInteger(stored) && stored>=0,'NOT_READY');
  const next=Math.max(stored,floor)+1; assert_(Number.isSafeInteger(next),'NOT_READY');
  // Reserve before the row write. Failed writes may leave gaps, never reused numbers.
  props.setProperty(key,String(next));
  return {number:'NAMU-'+day+'-'+String(next).padStart(4,'0'),counter:next};
}
function receiptDetails_(row) {
  receiptCounter_(row);
  assert_(row[17]===receiptTime_(row[2]),'NOT_READY');
  return {requestId:String(row[0]),receiptNumber:String(row[16]),createdAt:String(row[2]),createdAtSeoul:String(row[17])};
}
function assignReceipt_(sheet,row,index,allRows) {
  assert_(receiptColumnsReady_(sheet),'NOT_READY');
  if (!row[16]) {
    const day=receiptDay_(row[2]);
    const floor=allRows.reduce((max,r)=>r[16] && receiptDay_(r[2])===day ? Math.max(max,receiptCounter_(r)) : max,0);
    row[16]=reserveReceipt_(PropertiesService.getScriptProperties(),day,floor).number;
    row[17]=receiptTime_(row[2]);
    sheet.getRange(index+2,17,1,2).setNumberFormat('@').setValues([[row[16],row[17]]]); SpreadsheetApp.flush();
  }
  return receiptDetails_(row);
}
/** Called under setup's lock. Backfill only new metadata; never alter original customer records/cursors. */
function setupReceiptNumbers_(sheet,book) {
  const header=sheet.getRange(1,17,1,2).getValues()[0];
  assert_(header.every(v=>v==='') || JSON.stringify(header)===JSON.stringify(RFQ_RECEIPT_HEADERS),'NOT_READY');
  const rows=receiptRows_(sheet), floors={},seen=new Set();
  if(header.every(v=>v===''))assert_(rows.every(row=>!row[16] && !row[17]),'NOT_READY');
  rows.forEach(row=>{const day=receiptDay_(row[2]);
    if(row[16]){assert_(!seen.has(row[16]),'NOT_READY');seen.add(row[16]);floors[day]=Math.max(floors[day]||0,receiptCounter_(row));}
    else assert_(!row[17],'NOT_READY');
  });
  sheet.getRange(1,17,1,2).setValues([RFQ_RECEIPT_HEADERS]).setBackground('#205844').setFontColor('#ffffff').setFontWeight('bold');
  const props=PropertiesService.getScriptProperties();
  rows.map((row,index)=>({row,index})).sort((a,b)=>Date.parse(a.row[2])-Date.parse(b.row[2]) || String(a.row[0]).localeCompare(String(b.row[0]))).forEach(({row,index})=>{
    if(!row[16]){const day=receiptDay_(row[2]),next=reserveReceipt_(props,day,floors[day]||0); row[16]=next.number; floors[day]=next.counter;}
    row[17]=receiptTime_(row[2]);
    sheet.getRange(index+2,17,1,2).setNumberFormat('@').setValues([[row[16],row[17]]]);
  });
  book.setSpreadsheetTimeZone('Asia/Seoul'); sheet.setColumnWidth(17,220); sheet.setColumnWidth(18,210); SpreadsheetApp.flush();
}
function submitRequest_(body) {
  assert_(body.request !== undefined,'UPGRADE_REQUIRED');
  return submitCustomerRequest_(body);
}

/** Run once as the owner. Never replaces or clears an existing Sheet. */
function setupQuoteRequests() {
  lock_(() => {
    const props = PropertiesService.getScriptProperties();
    assert_(props.getProperty('SPREADSHEET_ID') && (props.getProperty('WEB_SECRET') || '').length >= 32, 'NOT_READY');
    const book = SpreadsheetApp.openById(props.getProperty('SPREADSHEET_ID'));
    let sheet = book.getSheetByName(RFQ_SHEET);
    if (!sheet) sheet = book.insertSheet(RFQ_SHEET);
    if (sheet.getLastRow() === 0) {
      sheet.getRange(1,1,1,RFQ_HEADERS.length).setValues([RFQ_HEADERS]).setBackground('#205844').setFontColor('#ffffff').setFontWeight('bold');
      sheet.setFrozenRows(1); sheet.setColumnWidth(1,280); sheet.setColumnWidth(7,300); sheet.setColumnWidth(9,280);
      sheet.getRange('E:E').setNumberFormat('@');
    }
    sheet_();
    setupReceiptNumbers_(sheet,book);
    if (!ScriptApp.getProjectTriggers().some(trigger => trigger.getHandlerFunction() === 'dispatchQuoteNotifications')) ScriptApp.newTrigger('dispatchQuoteNotifications').timeBased().everyMinutes(1).create();
    setupPrivacyStorage_();
  });
}

/** Owner runs this in the editor; copy the returned/logged invite to ONE trusted phone within 15 minutes. */
function createDeviceInvite() {
  const props = PropertiesService.getScriptProperties();
  Object.entries(props.getProperties()).forEach(([key, value]) => {
    if (key.startsWith('invite:') && JSON.parse(value).expires <= Date.now()) props.deleteProperty(key);
  });
  const invite = token_();
  PropertiesService.getScriptProperties().setProperty('invite:' + hash_(invite), JSON.stringify({ expires: Date.now() + 900000 }));
  console.log('나무팜 연결 코드 (15분 유효): ' + invite);
  return invite;
}
function pairDevice_(body) {
  assert_(typeof body.invite === 'string' && /^[a-f0-9]{64}$/.test(body.invite));
  // Client generates and persists the high-entropy credential BEFORE the request.
  // Repeating an uncertain pairing response with the same code/token is idempotent.
  assert_(typeof body.deviceToken === 'string' && /^[a-f0-9]{64}$/.test(body.deviceToken));
  return lock_(() => {
    const props = PropertiesService.getScriptProperties(); const key = 'invite:' + hash_(body.invite);
    const raw = props.getProperty(key); assert_(raw, 'UNAUTHORIZED');
    const invite = JSON.parse(raw); assert_(invite.expires > Date.now(), 'UNAUTHORIZED');
    const hash = hash_(body.deviceToken); assert_(!invite.claimed || invite.claimed === hash, 'UNAUTHORIZED');
    if (!invite.claimed) {
      const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
      assert_(Object.keys(devices).length < 10, 'NOT_READY');
      devices[hash] = { label: text_(body.label || '나무팜 앱', 40, 1), fcmToken: '', createdAt: new Date().toISOString() };
      props.setProperty('DEVICES', JSON.stringify(devices));
      props.setProperty(key, JSON.stringify({ expires: invite.expires, claimed: hash }));
    }
    return { paired: true };
  });
}
function authenticateDevice_(token) {
  assert_(typeof token === 'string' && /^[a-f0-9]{64}$/.test(token), 'UNAUTHORIZED');
  const hash = hash_(token); const devices = JSON.parse(PropertiesService.getScriptProperties().getProperty('DEVICES') || '{}');
  assert_(devices[hash], 'UNAUTHORIZED'); return hash;
}
/** Run once in the owner-only editor. Both apps import the same private file. */
function createAppConnectionFile() {
  const profile = lock_(() => {
    const props = PropertiesService.getScriptProperties();
    let key = props.getProperty('APP_CONNECTION_KEY');
    if (!key) { key = token_(); props.setProperty('APP_CONNECTION_KEY', key); }
    const url = ScriptApp.getService().getUrl();
    assert_(/^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url), 'NOT_READY');
    return {format:'namufarm-connection', version:1, url:url, connectionKey:key};
  });
  const file = Drive.Files.create({name:'나무팜-연결.json', mimeType:'application/json'},
    Utilities.newBlob(JSON.stringify(profile), 'application/json', '나무팜-연결.json'), {fields:'id'});
  // Log only the private Drive file URL, never the connection credential.
  const url = 'https://drive.google.com/file/d/' + file.id + '/view';
  console.log(url);
  return url;
}
function connectApp_(body) {
  assert_(typeof body.deviceToken === 'string' && /^[a-f0-9]{64}$/.test(body.deviceToken));
  return lock_(() => {
    const props = PropertiesService.getScriptProperties(), key = props.getProperty('APP_CONNECTION_KEY');
    assert_(key && equal_(body.connectionKey, key), 'UNAUTHORIZED');
    const devices = JSON.parse(props.getProperty('DEVICES') || '{}'), hash = hash_(body.deviceToken);
    if (!devices[hash]) {
      assert_(Object.keys(devices).length < 10, 'NOT_READY');
      devices[hash] = {label:text_(body.label || '나무팜 앱', 40, 1), fcmToken:'', createdAt:new Date().toISOString()};
      props.setProperty('DEVICES', JSON.stringify(devices));
    }
    return {paired:true};
  });
}
function registerPush_(body, hash) {
  assert_(typeof body.fcmToken === 'string' && body.fcmToken.length <= 3000 && body.fcmToken.length > 20);
  return lock_(() => {
    const props = PropertiesService.getScriptProperties(); const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
    assert_(devices[hash], 'UNAUTHORIZED'); devices[hash].fcmToken = body.fcmToken;
    props.setProperty('DEVICES', JSON.stringify(devices)); return { registered: true };
  });
}
function listRequests_(body) { return body.snapshot === true ? snapshotRequests_(body) : listEncryptedRequests_(body); }

/** Durable outbox; FCM network I/O never holds the request-submission lock. */
function dispatchQuoteNotifications() { dispatchNotifications_(null); }
function dispatchNotifications_(onlyId) {
  const work = lock_(() => {
    const props = PropertiesService.getScriptProperties(); const project = props.getProperty('FIREBASE_PROJECT_ID');
    if (!project || !/^[a-z0-9-]+$/.test(project)) return null;
    const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
    const targets = Object.entries(devices).filter(entry => !!entry[1].fcmToken);
    if (!targets.length) return null;
    const now = Date.now(); const lease = new Date(now + 600000).toISOString();
    const batch = [];
    const records=quoteStorageConfigured_()?indexedQuoteRecords_(-1).filter(({entry})=>entry.values[QI.status]!=='보관종료' && (!onlyId || entry.values[QI.id]===onlyId) && Date.parse(entry.values[QI.created])+QUOTE_RETENTION_MS>now):quoteRows_(sheet_());
    for(const candidate of records){
      if(batch.length >= (onlyId ? 1 : 10))break;
      const {sheet,row,index}=candidate.entry?quoteRecord_(candidate.entry,candidate.cache,false):candidate;
      if ((onlyId && row[0] !== onlyId) || row[10] !== 'PENDING' || Date.parse(String(row[2])) + 30*86400000 <= now || Date.parse(String(row[13])) > now) continue;
      sheet.getRange(index+2,14,1,1).setValues([[lease]]);
      batch.push({ id:String(row[0]), lease:lease });
    }
    SpreadsheetApp.flush();
    return { project:project, targets:targets, batch:batch };
  });
  if (!work) return;
  work.batch.forEach(job => {
    let success = true; let code = ''; const expired = [];
    work.targets.forEach(([hash, device]) => {
      try {
        const response = UrlFetchApp.fetch('https://fcm.googleapis.com/v1/projects/'+work.project+'/messages:send', {
          method:'post', contentType:'application/json', headers:{ Authorization:'Bearer '+ScriptApp.getOAuthToken() }, muteHttpExceptions:true,
          payload:JSON.stringify({ message:{ token:device.fcmToken, data:{ type:'quote_request', requestId:job.id }, android:{ priority:'high', ttl:'86400s' } } })
        });
        if (response.getResponseCode() >= 300) {
          const body = JSON.parse(response.getContentText());
          const unregistered = body.error && (body.error.details || []).some(detail => detail.errorCode === 'UNREGISTERED');
          if (unregistered) expired.push([hash,device.fcmToken]);
          success = false; code = unregistered ? 'TOKEN_EXPIRED' : 'FCM_'+response.getResponseCode();
        }
      } catch (_) { success = false; code = 'NETWORK'; }
    });
    lock_(() => {
      let record;
      if(quoteStorageConfigured_()){const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);const entry=entries.find(e=>e.values[QI.id]===job.id);record=entry?quoteRecord_(entry,cache,false):null;}
      else record=quoteFindRow_(sheet_(),job.id);
      if(!record || record.row[10]!=='PENDING' || record.row[13]!==job.lease)return;
      const {sheet,row,index}=record; const attempts = (Number(row[12]) || 0) + 1;
      const next = new Date(Date.now() + Math.min(21600000, Math.pow(2,Math.min(attempts,12))*60000)).toISOString();
      sheet.getRange(index+2,11,1,5).setValues([[success ? 'SENT' : 'PENDING', row[11], attempts, next, code]]);
      const props = PropertiesService.getScriptProperties(); const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
      expired.forEach(([hash,token]) => { if (devices[hash] && devices[hash].fcmToken === token) devices[hash].fcmToken = ''; });
      if (expired.length) props.setProperty('DEVICES',JSON.stringify(devices));
      SpreadsheetApp.flush();
    });
  });
}

/** Owner-only maintenance: pass a device hash from Script Properties, never a customer number. */
function revokeDevice(deviceHash) {
  lock_(() => {
    const props = PropertiesService.getScriptProperties(); const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
    assert_(typeof deviceHash === 'string' && devices[deviceHash], 'INVALID');
    delete devices[deviceHash]; props.setProperty('DEVICES', JSON.stringify(devices));
  });
}


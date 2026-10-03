/**
 * Dedicated, authenticated RFQ endpoint. Deploy separately from the legacy order API.
 * All state is in the private Sheet / Script Properties, never browser code.
 */
const RFQ_SHEET = '웹견적요청';
const RFQ_HEADERS = ['요청번호','접수순번','접수시각(UTC)','성함','연락처','배송지역','희망묘목','품목JSON','요청사항','상태','알림상태','내용해시','알림시도','다음알림(UTC)','오류코드','원본JSON'];
const RFQ_COL = { id:0, sequence:1, created:2, name:3, phone:4, region:5, summary:6, items:7, memo:8, status:9, notification:10, hash:11, attempts:12, next:13, error:14, raw:15 };

function doPost(e) {
  try {
    const text = e && e.postData && e.postData.contents;
    assert_(typeof text === 'string' && text.length <= 30000, 'INVALID');
    const body = JSON.parse(text);
    let value;
    if (body.action === 'submit_request') {
      const secret = PropertiesService.getScriptProperties().getProperty('WEB_SECRET');
      assert_(secret && secret.length >= 32 && equal_(body.webSecret, secret), 'UNAUTHORIZED');
      value = submitRequest_(body);
      // Receipt is committed first. Push failure must never roll it back.
      try { dispatchNotifications_(value.requestId); } catch (_) { /* Minute trigger retries the durable outbox. */ }
    } else if (body.action === 'pair_device') value = pairDevice_(body);
    else {
      const device = authenticateDevice_(body.deviceToken);
      if (body.action === 'list_requests') value = listRequests_(body);
      else if (body.action === 'register_push') value = registerPush_(body, device);
      else fail_('INVALID_ACTION');
    }
    return json_(Object.assign({ ok: true }, value));
  } catch (error) {
    // Do not leak customer data, sheet identifiers, or credentials in responses/logs.
    const code = ['INVALID','UNAUTHORIZED','CONFLICT','RATE_LIMITED','NOT_READY','BUSY','INVALID_ACTION'].indexOf(error.code) >= 0 ? error.code : 'UNAVAILABLE';
    return json_({ ok: false, code: code });
  }
}
function doGet() { return json_({ ok: true, service: 'namufarm-quote-requests', version: 1 }); }
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
  assert_(raw && typeof raw === 'object' && raw.consent === true && raw.website === '');
  assert_(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(raw.requestId));
  const phone = text_(raw.customerPhone, 20, 9).replace(/[\s()-]/g, '');
  assert_(/^0\d{8,10}$/.test(phone));
  assert_(Array.isArray(raw.items) && raw.items.length > 0 && raw.items.length <= 20);
  return { requestId: raw.requestId.toLowerCase(), customerName: text_(raw.customerName, 50, 2), customerPhone: phone,
    region: text_(raw.region, 100, 2), items: raw.items.map(item => {
      assert_(item && Number.isSafeInteger(item.quantity) && item.quantity > 0 && item.quantity <= 1000000);
      return { species: text_(item.species, 80, 1), specification: text_(item.specification, 120), quantity: item.quantity };
    }), message: text_(raw.message, 1000), consent: true };
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
function submitRequest_(body) {
  const request = validateRequest_(body.request);
  assert_(typeof body.rateKey === 'string' && /^[a-f0-9]{64}$/.test(body.rateKey));
  const digest = hash_(JSON.stringify(request));
  return lock_(() => {
    const sheet = sheet_(); const rows = rows_(sheet);
    const existing = rows.find(row => row[RFQ_COL.id] === request.requestId);
    if (existing) { assert_(existing[RFQ_COL.hash] === digest, 'CONFLICT'); return { requestId: request.requestId, duplicate: true }; }
    const cache = CacheService.getScriptCache(); const cacheKey = 'rfq-rate:' + body.rateKey;
    const now = Date.now(); const prior = JSON.parse(cache.get(cacheKey) || '{"count":0,"until":0}');
    const bucket = prior.until > now ? prior : { count: 0, until: now + 600000 };
    assert_(bucket.count < 5, 'RATE_LIMITED');
    const props = PropertiesService.getScriptProperties();
    const sequence = rows.reduce((max,row) => Math.max(max, Number(row[RFQ_COL.sequence]) || 0), Number(props.getProperty('LAST_SEQUENCE')) || 0) + 1;
    assert_(Number.isSafeInteger(sequence), 'NOT_READY');
    props.setProperty('LAST_SEQUENCE', String(sequence)); // Gaps after failed writes are safe; never reuse a cursor.
    const summary = request.items.map(item => item.species + (item.specification ? ' ('+item.specification+')' : '') + ' '+item.quantity+'주').join('\n');
    const row = [request.requestId, sequence, new Date(now).toISOString(), cell_(request.customerName), cell_(request.customerPhone), cell_(request.region), cell_(summary), JSON.stringify(request.items), cell_(request.message), '신규', 'PENDING', digest, 0, new Date(now).toISOString(), '', JSON.stringify(request)];
    const range = sheet.getRange(sheet.getLastRow()+1,1,1,RFQ_HEADERS.length);
    range.setNumberFormat('@'); range.setValues([row]); SpreadsheetApp.flush();
    // If the response or cache write fails after commit, the same ID still returns success.
    cache.put(cacheKey, JSON.stringify({ count: bucket.count + 1, until: bucket.until }), Math.max(1, Math.ceil((bucket.until-now)/1000)));
    return { requestId: request.requestId, duplicate: false };
  });
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
    if (!ScriptApp.getProjectTriggers().some(trigger => trigger.getHandlerFunction() === 'dispatchQuoteNotifications')) ScriptApp.newTrigger('dispatchQuoteNotifications').timeBased().everyMinutes(1).create();
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
function registerPush_(body, hash) {
  assert_(typeof body.fcmToken === 'string' && body.fcmToken.length <= 3000 && body.fcmToken.length > 20);
  return lock_(() => {
    const props = PropertiesService.getScriptProperties(); const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
    assert_(devices[hash], 'UNAUTHORIZED'); devices[hash].fcmToken = body.fcmToken;
    props.setProperty('DEVICES', JSON.stringify(devices)); return { registered: true };
  });
}
function listRequests_(body) {
  const after = body.afterSequence;
  assert_(Number.isSafeInteger(after) && after >= 0);
  // Sheet rows may be sorted manually; never use row position as cursor.
  const rows = rows_(sheet_()).filter(row => Number(row[RFQ_COL.sequence]) > after).sort((a,b) => Number(a[1])-Number(b[1]));
  const page = rows.slice(0,100).map(row => {
    // Display cells may be escaped or manually formatted. The immutable JSON preserves the exact enquiry.
    const raw = JSON.parse(row[RFQ_COL.raw]);
    assert_(hash_(JSON.stringify(raw)) === row[RFQ_COL.hash] && raw.requestId === row[0], 'NOT_READY');
    const request = validateRequest_(Object.assign({}, raw, { website: '' }));
    return Object.assign(request, { sequence: Number(row[1]), createdAt: String(row[2]) });
  });
  return { requests: page, hasMore: rows.length > page.length };
}

/** Durable outbox; network I/O never holds the request-submission lock. */
function dispatchQuoteNotifications() { dispatchNotifications_(null); }
function dispatchNotifications_(onlyId) {
  const work = lock_(() => {
    const props = PropertiesService.getScriptProperties(); const project = props.getProperty('FIREBASE_PROJECT_ID');
    if (!project || !/^[a-z0-9-]+$/.test(project)) return null;
    const devices = JSON.parse(props.getProperty('DEVICES') || '{}');
    const targets = Object.entries(devices).filter(entry => !!entry[1].fcmToken);
    if (!targets.length) return null;
    const sheet = sheet_(); const now = Date.now(); const lease = new Date(now + 600000).toISOString();
    const batch = [];
    rows_(sheet).forEach((row, offset) => {
      if (batch.length >= (onlyId ? 1 : 10) || (onlyId && row[0] !== onlyId) || row[10] !== 'PENDING' || Date.parse(String(row[13])) > now) return;
      sheet.getRange(offset+2,14,1,1).setValues([[lease]]);
      batch.push({ id:String(row[0]), lease:lease });
    });
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
      const sheet = sheet_(); const rows = rows_(sheet); const index = rows.findIndex(row => row[0] === job.id);
      if (index < 0 || rows[index][10] !== 'PENDING' || rows[index][13] !== job.lease) return;
      const row = rows[index]; const attempts = (Number(row[12]) || 0) + 1;
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

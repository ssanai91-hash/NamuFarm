/**
 * Monthly RFQ storage. All functions ending in _ run inside the script lock
 * unless explicitly read-only. Index reservations contain metadata, never PII.
 * A candidate row has sequence 0; a recoverable COMMITTING index intent must be
 * durable before assigning a visible sequence. Thus an abandoned reservation
 * cannot be published later below a cursor already returned to a client.
 */
const QUOTE_MONTHS = '견적월별파일';
const QUOTE_MONTH_HEADERS = ['월','파일ID','생성토큰','생성상태','생성시각(UTC)'];
const QUOTE_INDEX = '전체견적색인';
const QUOTE_INDEX_HEADERS = ['요청UUID','파일ID','시트ID','접수일(한국)','접수시각(UTC)','견적접수번호','접수시각(한국)','내용해시','변경순번','저장단계','기존시트사본','이관파일ID','이관시트ID','최초예약행','처리상태'];
const QUOTE_SUMMARY = '월간현황';
const QUOTE_SUMMARY_HEADERS = ['접수일','접수건수','신규','보관종료','갱신시각(한국)'];
const QI = {id:0,book:1,sheet:2,day:3,created:4,receipt:5,seoul:6,hash:7,seq:8,phase:9,copy:10,targetBook:11,targetSheet:12,slot:13,status:14};
function quoteStorageConfigured_() { return PropertiesService.getScriptProperties().getProperty('QUOTE_STORAGE_READY') === '1'; }
function quoteStorageReady_() {
  if (!quoteStorageConfigured_()) return !['monthly-v1','yearly-v1'].includes(PropertiesService.getScriptProperties().getProperty('QUOTE_STORAGE_MODE'));
  quoteTables_(); return true;
}
function quoteTables_() {
  return {months:namedSheet_(QUOTE_MONTHS,QUOTE_MONTH_HEADERS,false), index:namedSheet_(QUOTE_INDEX,QUOTE_INDEX_HEADERS,false)};
}
function quoteWriteVerified_(sheet, rowNumber, values) {
  const maximum=sheet.getMaxRows();
  if(rowNumber>maximum)sheet.insertRowsAfter(maximum,Math.max(100,rowNumber-maximum));
  // Plain-text Sheets cells persist booleans as strings after the write cache
  // expires. Encode explicitly so immediate and later reads have the same type.
  const stored=values.map(value=>typeof value==='boolean'?String(value):value);
  sheet.getRange(rowNumber,1,1,stored.length).setNumberFormat('@').setValues([stored]);
  SpreadsheetApp.flush();
  const actual=sheet.getRange(rowNumber,1,1,stored.length).getValues()[0];
  assert_(actual.every((value,i)=>stored.length===18 && i>=3 && i<=8 ? quoteCellEqual_(value,stored[i]) : value===stored[i]),'NOT_READY');
}
function quoteIndexEntries_() {
  const sheet=quoteTables_().index, seen=new Set(), sequences=new Set(), receipts=new Set();
  return namedRows_(sheet,QUOTE_INDEX_HEADERS.length).map((values,i)=>({values:values,index:i})).filter(e=>e.values.some(v=>v!=='')).map(e=>{
    const v=e.values;
    if(v[QI.copy]==='true' || v[QI.copy]==='false')v[QI.copy]=v[QI.copy]==='true';
    assert_(UUID_PATTERN.test(v[QI.id]) && !seen.has(v[QI.id]),'NOT_READY'); seen.add(v[QI.id]);
    assert_(v[QI.day]===receiptTime_(v[QI.created]).slice(0,10) && v[QI.seoul]===receiptTime_(v[QI.created]),'NOT_READY');
    const receipt=RFQ_RECEIPT_PATTERN.exec(v[QI.receipt]);
    assert_(receipt && receipt[1]===v[QI.day].replace(/-/g,'') && Number.isSafeInteger(Number(receipt[2])) && Number(receipt[2])>0 && !receipts.has(v[QI.receipt]),'NOT_READY');receipts.add(v[QI.receipt]);
    assert_(/^[a-f0-9]{64}$/.test(v[QI.hash]) && ['신규','견적확정','견적발송완료','입금확인','배송완료','구매취소','보관종료'].includes(v[QI.status]) && typeof v[QI.copy]==='boolean','NOT_READY');
    assert_(['PREPARED','COMMITTING','COMMITTED','DISPOSING','MIGRATING'].includes(v[QI.phase]),'NOT_READY');
    assert_(Number.isSafeInteger(Number(v[QI.seq])) && Number(v[QI.seq])>=0,'NOT_READY');
    assert_(v[QI.phase]==='PREPARED' ? Number(v[QI.seq])===0 : Number(v[QI.seq])>0,'NOT_READY');
    if(Number(v[QI.seq])>0){assert_(!sequences.has(Number(v[QI.seq])),'NOT_READY');sequences.add(Number(v[QI.seq]));}
    assert_(Number.isSafeInteger(Number(v[QI.slot])) && Number(v[QI.slot])>=2,'NOT_READY');
    assert_(v[QI.book] && Number.isSafeInteger(Number(v[QI.sheet])) && Number(v[QI.sheet])>=0,'NOT_READY');
    return e;
  });
}
function quoteSaveEntry_(entry) { quoteWriteVerified_(quoteTables_().index,entry.index+2,entry.values); }
function quoteAppendEntry_(values) {
  const sheet=quoteTables_().index,entry={values:values,index:sheet.getLastRow()-1};
  quoteSaveEntry_(entry); return entry;
}
function quoteEntryForRow_(sheet,row,index) {
  receiptDetails_(row);
  return [row[0],sheet.getParent().getId(),sheet.getSheetId(),receiptTime_(row[2]).slice(0,10),row[2],row[16],row[17],row[11],Number(row[1]),'COMMITTED',false,'','',index+2,row[9]];
}
function quoteRows_(sheet) { return receiptRows_(sheet).map((row,index)=>({sheet:sheet,row:row,index:index})).filter(r=>r.row.some(v=>v!=='')); }
function quoteOpenSheet_(bookId,sheetId,cache) {
  const key=bookId+':'+sheetId;
  if(cache && cache[key]) return cache[key];
  const book=SpreadsheetApp.openById(bookId), sheet=book.getSheetById(Number(sheetId));
  assert_(sheet && JSON.stringify(sheet.getRange(1,1,1,18).getValues()[0])===JSON.stringify(RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS)),'NOT_READY');
  if(cache)cache[key]=sheet; return sheet;
}
function quoteFindRow_(sheet,id,cache) {
  if(cache && cache.readSnapshots){
    const key=sheet.getParent().getId()+':'+sheet.getSheetId();
    if(!cache.readSnapshots[key]){
      const byId=new Map();quoteRows_(sheet).forEach(r=>{assert_(!byId.has(r.row[0]),'NOT_READY');byId.set(r.row[0],r);});
      cache.readSnapshots[key]=byId;
    }
    return cache.readSnapshots[key].get(id)||null;
  }
  const matches=quoteRows_(sheet).filter(r=>r.row[0]===id);
  assert_(matches.length<=1,'NOT_READY'); return matches[0]||null;
}
function quoteRecord_(entry,cache,allowMissing) {
  const v=entry.values,sheet=quoteOpenSheet_(v[QI.book],v[QI.sheet],cache),record=quoteFindRow_(sheet,v[QI.id],cache);
  assert_(record || allowMissing,'NOT_READY');
  if(record){
    quoteCheckIdentity_(entry,record.row);
    if(v[QI.phase]==='COMMITTED')assert_(Number(record.row[1])===Number(v[QI.seq]) && record.row[9]===v[QI.status],'NOT_READY');
  }
  return record;
}
function quoteCheckIdentity_(entry,row) {
  const v=entry.values;
  assert_(row[0]===v[QI.id] && row[2]===v[QI.created] && row[11]===v[QI.hash] && row[16]===v[QI.receipt] && row[17]===v[QI.seoul],'CONFLICT');
  const raw=JSON.parse(row[15]);
  const stored=raw.expired===true?{}:quoteStoredData_(raw);
  if(stored.request)assert_(stored.request.requestId===row[0],'NOT_READY');
  assert_(quoteCustomerCells_(stored).every((value,i)=>quoteCellEqual_(row[i+3],value)),'NOT_READY');
  receiptDetails_(row);
}
function quoteNextSequence_(entries) {
  const props=PropertiesService.getScriptProperties(),stored=Number(props.getProperty('LAST_SEQUENCE')||0);
  assert_(Number.isSafeInteger(stored) && stored>=0,'NOT_READY');
  const next=entries.reduce((n,e)=>Math.max(n,Number(e.values[QI.seq])),stored)+1;
  assert_(Number.isSafeInteger(next),'NOT_READY'); props.setProperty('LAST_SEQUENCE',String(next)); return next;
}
function quotePublish_(entry,entries,record) {
  const v=entry.values;
  if(v[QI.phase]==='PREPARED'){
    v[QI.seq]=quoteNextSequence_(entries); v[QI.phase]='COMMITTING'; quoteSaveEntry_(entry);
  }
  assert_(v[QI.phase]==='COMMITTING' && [0,Number(v[QI.seq])].includes(Number(record.row[1])),'NOT_READY');
  const row=record.row.slice();row[1]=Number(v[QI.seq]);
  quoteWriteVerified_(record.sheet,record.index+2,row);
  v[QI.phase]='COMMITTED';v[QI.status]=row[9];quoteSaveEntry_(entry);
}
function quoteDisposeEntry_(entry,entries,cache) {
  const v=entry.values,record=quoteRecord_(entry,cache,false);
  if(v[QI.phase]==='COMMITTED' && JSON.parse(record.row[15]).expired===true)return;
  if(v[QI.phase]!=='DISPOSING'){
    assert_(v[QI.phase]==='COMMITTED','NOT_READY');
    v[QI.seq]=quoteNextSequence_(entries);v[QI.phase]='DISPOSING';quoteSaveEntry_(entry);
  }
  const copies=[record];
  if(v[QI.copy]){
    const legacy=quoteFindRow_(sheet_(),v[QI.id]);assert_(legacy,'NOT_READY');quoteCheckIdentity_(entry,legacy.row);copies.push(legacy);
  }
  copies.forEach(r=>{
    const row=r.row.slice();row[1]=Number(v[QI.seq]);for(let c=3;c<=8;c++)row[c]='';
    row[9]='보관종료';row[10]='STOPPED';row[14]='';row[15]='{"expired":true}';
    quoteWriteVerified_(r.sheet,r.index+2,row);
  });
  v[QI.phase]='COMMITTED';v[QI.status]='보관종료';quoteSaveEntry_(entry);
}
function quoteFinishMigration_(entry,cache,entries) {
  const v=entry.values,source=quoteRecord_(entry,cache,false);
  const target=quoteOpenSheet_(v[QI.targetBook],v[QI.targetSheet],cache),existing=quoteFindRow_(target,v[QI.id]);
  if(existing){quoteCheckIdentity_(entry,existing.row);assert_(canonical_(existing.row)===canonical_(source.row),'CONFLICT');}
  else {
    quoteWriteVerified_(target,quoteVacantSlot_(entry,target,entries),source.row);
  }
  const verified=quoteFindRow_(target,v[QI.id]);assert_(verified && canonical_(verified.row)===canonical_(source.row),'NOT_READY');
  v[QI.book]=v[QI.targetBook];v[QI.sheet]=v[QI.targetSheet];v[QI.targetBook]='';v[QI.targetSheet]='';
  v[QI.copy]=true;v[QI.phase]='COMMITTED';v[QI.slot]=verified.index+2;quoteSaveEntry_(entry);
}
function quoteRepairTransitions_(entries,cache) {
  entries.forEach(entry=>{
    const phase=entry.values[QI.phase];
    if(phase==='PREPARED' || phase==='COMMITTING'){
      const record=quoteRecord_(entry,cache,phase==='PREPARED');
      if(record)quotePublish_(entry,entries,record);
    }else if(phase==='DISPOSING')quoteDisposeEntry_(entry,entries,cache);
    else if(phase==='MIGRATING')quoteFinishMigration_(entry,cache,entries);
  });
}
function quoteMonthRows_() {
  const seen=new Set();return namedRows_(quoteTables_().months,5).map((values,index)=>({values:values,index:index})).filter(e=>e.values.some(v=>v!=='')).map(e=>{
    assert_(/^\d{4}-(0[1-9]|1[0-2])$/.test(e.values[0]) && !seen.has(e.values[0]),'NOT_READY');seen.add(e.values[0]);
    assert_(['RESERVED','CREATING','READY'].includes(e.values[3]) && UUID_PATTERN.test(e.values[2]),'NOT_READY');
    assert_(e.values[3]!=='READY' || !!e.values[1],'NOT_READY');return e;
  });
}
function quoteMonthFileProperties_(token) {
  return {namufarmQuoteStore:hash_(PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID')),namufarmQuoteToken:token};
}
function quoteFindCreatedFile_(token) {
  const p=quoteMonthFileProperties_(token);
  const result=Drive.Files.list({q:"trashed = false and mimeType = 'application/vnd.google-apps.spreadsheet' and appProperties has { key='namufarmQuoteStore' and value='"+p.namufarmQuoteStore+"' } and appProperties has { key='namufarmQuoteToken' and value='"+token+"' }",fields:'files(id),nextPageToken',pageSize:2});
  assert_(!result.nextPageToken && (result.files||[]).length<=1,'NOT_READY');return (result.files||[])[0]||null;
}
function quoteEnsureMonth_(month) {
  assert_(/^\d{4}-(0[1-9]|1[0-2])$/.test(month));
  const table=quoteTables_().months;
  let entry=quoteMonthRows_().find(e=>e.values[0]===month);
  if(!entry){entry={values:[month,'',Utilities.getUuid(),'RESERVED',new Date().toISOString()],index:table.getLastRow()-1};quoteWriteVerified_(table,entry.index+2,entry.values);}
  const v=entry.values;
  if(!v[1]){
    if(v[3]==='RESERVED'){
      v[3]='CREATING';quoteWriteVerified_(table,entry.index+2,v);
      // Workspace files do not support pre-generated IDs. Persist the unique
      // token in the creation request itself, so a lost response is discoverable.
      const file=Drive.Files.create({name:'나무팜_견적요청_'+month,mimeType:'application/vnd.google-apps.spreadsheet',appProperties:quoteMonthFileProperties_(v[2])},null,{fields:'id'});
      assert_(file && file.id,'NOT_READY');v[1]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }else{
      const file=quoteFindCreatedFile_(v[2]);
      // Empty search after an uncertain create is NOT permission to create again.
      assert_(file,'NOT_READY');v[1]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }
  }
  const book=SpreadsheetApp.openById(v[1]);book.setSpreadsheetTimeZone('Asia/Seoul');
  quoteEnsureHeaderSheet_(book,QUOTE_SUMMARY,QUOTE_SUMMARY_HEADERS);
  if(v[3]!=='READY'){v[3]='READY';quoteWriteVerified_(table,entry.index+2,v);}return book;
}
function quoteEnsureHeaderSheet_(book,name,headers) {
  let sheet=book.getSheetByName(name);if(!sheet)sheet=book.insertSheet(name);
  if(sheet.getLastRow()===0){quoteWriteVerified_(sheet,1,headers);sheet.setFrozenRows(1);}
  assert_(JSON.stringify(sheet.getRange(1,1,1,headers.length).getValues()[0])===JSON.stringify(headers),'NOT_READY');return sheet;
}
function quoteDaySheet_(day) {
  const book=quoteEnsureMonth_(day.slice(0,7));return quoteEnsureHeaderSheet_(book,day,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS));
}
function quoteSummary_(month,entries) {
  const monthEntry=quoteMonthRows_().find(e=>e.values[0]===month);if(!monthEntry || !monthEntry.values[1])return;
  const book=SpreadsheetApp.openById(monthEntry.values[1]),sheet=quoteEnsureHeaderSheet_(book,QUOTE_SUMMARY,QUOTE_SUMMARY_HEADERS),days={};
  entries.filter(e=>e.values[QI.book]===book.getId() && e.values[QI.phase]==='COMMITTED').forEach(e=>{
    const v=e.values,d=days[v[QI.day]]||(days[v[QI.day]]=[v[QI.day],0,0,0,receiptTime_(new Date().toISOString())]);
    d[1]++;if(v[QI.status]==='보관종료')d[3]++;else d[2]++;
  });
  const values=Object.values(days).sort((a,b)=>a[0].localeCompare(b[0]));
  const count=Math.max(values.length,sheet.getLastRow()-1);
  if(count){while(values.length<count)values.push(['','','','','']);sheet.getRange(2,1,count,5).setValues(values);SpreadsheetApp.flush();}
}
function quoteRefreshSummaryBestEffort_(entry,entries) {
  try{quoteSummary_(entry.values[QI.day].slice(0,7),entries);}catch(_){/* Derived view; scheduled maintenance rebuilds it. */}
}
function quoteAllocateSlot_(sheet,entries) {
  return entries.filter(e=>e.values[QI.book]===sheet.getParent().getId() && Number(e.values[QI.sheet])===sheet.getSheetId()
    || e.values[QI.phase]==='MIGRATING' && e.values[QI.targetBook]===sheet.getParent().getId() && Number(e.values[QI.targetSheet])===sheet.getSheetId())
    .reduce((n,e)=>Math.max(n,Number(e.values[QI.slot])),sheet.getLastRow())+1;
}
function quoteSlotEmpty_(sheet,slot) {
  return slot>sheet.getMaxRows() || sheet.getRange(slot,1,1,18).getValues()[0].every(x=>x==='');
}
function quoteVacantSlot_(entry,sheet,entries) {
  // Sorting may put a different UUID in an unused reservation. Relocate only
  // the empty reservation, never treat the old row number as an identity.
  if(!quoteSlotEmpty_(sheet,Number(entry.values[QI.slot]))){
    entry.values[QI.slot]=quoteAllocateSlot_(sheet,entries);quoteSaveEntry_(entry);
  }
  return Number(entry.values[QI.slot]);
}
function submitIndexedQuote_(body,stored) {
  const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
  let entry=entries.find(e=>e.values[QI.id]===body.requestId);
  if(entry){
    assert_(entry.values[QI.hash]===body.fingerprint,'CONFLICT');
    if(entry.values[QI.phase]==='COMMITTED')return Object.assign(receiptDetails_(quoteRecord_(entry,cache,false).row),{duplicate:true});
    assert_(entry.values[QI.phase]==='PREPARED','NOT_READY');
  }
  const now=Date.now();
  if(!entry){
    const created=new Date(now).toISOString(),day=receiptTime_(created).slice(0,10);
    const mode=PropertiesService.getScriptProperties().getProperty('QUOTE_STORAGE_MODE');
    const sheet=mode==='yearly-v1'?ledgerMonthSheet_('quote',day.slice(0,7)):mode==='monthly-v1'?quoteDaySheet_(day):sheet_();
    const floor=entries.filter(e=>e.values[QI.day]===day).reduce((n,e)=>Math.max(n,Number(e.values[QI.receipt].split('-')[2])),0);
    const receipt=reserveReceipt_(PropertiesService.getScriptProperties(),day.replace(/-/g,''),floor);
    const slot=quoteAllocateSlot_(sheet,entries);
    entry=quoteAppendEntry_([body.requestId,sheet.getParent().getId(),sheet.getSheetId(),day,created,receipt.number,receiptTime_(created),body.fingerprint,0,'PREPARED',false,'','',slot,'신규']);entries.push(entry);
  }
  const v=entry.values,sheet=quoteOpenSheet_(v[QI.book],v[QI.sheet],cache);
  let record=quoteRecord_(entry,cache,true);
  if(!record){
    const slot=quoteVacantSlot_(entry,sheet,entries);
    const expired=Date.parse(v[QI.created])+QUOTE_RETENTION_MS<=now;
    const row=[v[QI.id],0,v[QI.created],...quoteCustomerCells_(expired?{}:stored),expired?'보관종료':'신규',expired?'STOPPED':'PENDING',v[QI.hash],0,v[QI.created],'',JSON.stringify(expired?{expired:true}:stored),v[QI.receipt],v[QI.seoul]];
    quoteWriteVerified_(sheet,slot,row);record=quoteRecord_(entry,cache,false);
  }
  // Derived monthly totals are rebuilt by maintainMonthlyQuoteStorage.
  // A customer only waits for the verified, recoverable receipt commit.
  quotePublish_(entry,entries,record);
  return Object.assign(receiptDetails_(record.row),{duplicate:false});
}
function indexedQuoteRecords_(afterSequence,snapshotReads) {
  const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
  // Enable only after repairs, for the read-only page under the same lock.
  // Each referenced daily sheet is fetched once; mutation paths use live reads.
  if(snapshotReads)cache.readSnapshots={};
  return entries.filter(e=>e.values[QI.phase]==='COMMITTED' && Number(e.values[QI.seq])>afterSequence).sort((a,b)=>Number(a.values[QI.seq])-Number(b.values[QI.seq])).map(e=>({entry:e,cache:cache}));
}
function closeIndexedQuote_(id) {
  const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);const entry=entries.find(e=>e.values[QI.id]===id);
  if(entry){
    if(entry.values[QI.phase]==='PREPARED'){
      // A disposal of an unsaved reservation must prevent a later PII replay.
      const v=entry.values,sheet=quoteOpenSheet_(v[QI.book],v[QI.sheet],cache),slot=quoteVacantSlot_(entry,sheet,entries);
      quoteWriteVerified_(sheet,slot,[v[QI.id],0,v[QI.created],'','','','','','','보관종료','STOPPED',v[QI.hash],0,v[QI.created],'','{"expired":true}',v[QI.receipt],v[QI.seoul]]);
      quotePublish_(entry,entries,quoteRecord_(entry,cache,false));
    }else quoteDisposeEntry_(entry,entries,cache);
    quoteRefreshSummaryBestEffort_(entry,entries);
  }
}
function purgeIndexedQuotes_() {
  const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
  entries.filter(e=>e.values[QI.phase]==='COMMITTED' && Date.parse(e.values[QI.created])+QUOTE_RETENTION_MS<=Date.now()).forEach(e=>quoteDisposeEntry_(e,entries,cache));
}
/** Owner setup: adds metadata tables, indexes existing encrypted rows in place. */
function setupMonthlyQuoteStorage() {
  return lock_(()=>{
    assert_(secureServiceInfo_().customerDataReady,'NOT_READY');
    namedSheet_(QUOTE_MONTHS,QUOTE_MONTH_HEADERS,true);namedSheet_(QUOTE_INDEX,QUOTE_INDEX_HEADERS,true);
    const entries=quoteIndexEntries_(),seen=new Set(),legacy=sheet_();
    quoteRows_(legacy).forEach(r=>{
      assert_(!seen.has(r.row[0]),'NOT_READY');seen.add(r.row[0]);
      const existing=entries.find(e=>e.values[QI.id]===r.row[0]);
      if(existing){
        quoteCheckIdentity_(existing,r.row);
        assert_(existing.values[QI.copy] || existing.values[QI.book]===legacy.getParent().getId() && Number(existing.values[QI.sheet])===legacy.getSheetId(),'NOT_READY');
        return;
      }
      const values=quoteEntryForRow_(legacy,r.row,r.index),entry={values:values};quoteCheckIdentity_(entry,r.row);
      assert_(Number.isSafeInteger(Number(r.row[1])) && Number(r.row[1])>0,'NOT_READY');entries.push(quoteAppendEntry_(values));
    });
    quoteIndexEntries_();
    const props=PropertiesService.getScriptProperties(),maximum=entries.reduce((n,e)=>Math.max(n,Number(e.values[QI.seq])),Number(props.getProperty('LAST_SEQUENCE')||0));
    assert_(Number.isSafeInteger(maximum),'NOT_READY');props.setProperty('LAST_SEQUENCE',String(maximum));props.setProperty('QUOTE_STORAGE_READY','1');
    if(!ScriptApp.getProjectTriggers().some(t=>t.getHandlerFunction()==='maintainMonthlyQuoteStorage'))ScriptApp.newTrigger('maintainMonthlyQuoteStorage').timeBased().everyMinutes(5).create();
    return {indexedRequests:entries.length,writeMode:props.getProperty('QUOTE_STORAGE_MODE')||'legacy'};
  });
}
function enableMonthlyQuoteStorage() {
  return lock_(()=>{assert_(quoteStorageConfigured_(),'NOT_READY');quoteAudit_();PropertiesService.getScriptProperties().setProperty('QUOTE_STORAGE_MODE','monthly-v1');return {writeMode:'monthly-v1'};});
}
function useLegacyQuoteWrites() {
  return lock_(()=>{assert_(quoteStorageConfigured_(),'NOT_READY');const audit=quoteAudit_(true);PropertiesService.getScriptProperties().setProperty('QUOTE_STORAGE_MODE','legacy');return {writeMode:'legacy',monthlyReads:true,unresolvedMonths:audit.unresolvedMonths};});
}
function prepareQuoteMonth(month) { return lock_(()=>{assert_(quoteStorageConfigured_(),'NOT_READY');const book=quoteEnsureMonth_(month);return {month:month,fileId:book.getId()};}); }
/** Owner recovery when an exact ID is known but Drive search is delayed. */
function recoverQuoteMonthFile(month,fileId) {
  return lock_(()=>{
    assert_(quoteStorageConfigured_(),'NOT_READY');const entry=quoteMonthRows_().find(e=>e.values[0]===month);
    assert_(entry && entry.values[3]==='CREATING' && !entry.values[1],'CONFLICT');
    const file=Drive.Files.get(fileId,{fields:'id,mimeType,trashed,appProperties'}),expected=quoteMonthFileProperties_(entry.values[2]);
    assert_(file && !file.trashed && file.mimeType==='application/vnd.google-apps.spreadsheet' && file.appProperties && Object.keys(expected).every(k=>file.appProperties[k]===expected[k]),'CONFLICT');
    const found=quoteFindCreatedFile_(entry.values[2]);assert_(!found || found.id===file.id,'CONFLICT');
    entry.values[1]=file.id;quoteWriteVerified_(quoteTables_().months,entry.index+2,entry.values);
    const book=quoteEnsureMonth_(month);return {month:month,fileId:book.getId()};
  });
}
function maintainMonthlyQuoteStorage() {
  if(!quoteStorageConfigured_())return;
  return lock_(()=>{const entries=quoteIndexEntries_();quoteRepairTransitions_(entries,{});quoteMonthRows_().forEach(m=>{quoteEnsureMonth_(m.values[0]);quoteSummary_(m.values[0],entries);});return {checked:entries.length};});
}
function quoteAudit_(allowUncreatedMonths,allowSimpleCopies) {
  const entries=quoteIndexEntries_(),cache={},byId=new Map(entries.map(e=>[e.values[QI.id],e]));
  // An audit is read-only. Resolve interrupted transitions with maintenance first.
  assert_(entries.every(e=>['PREPARED','COMMITTED'].includes(e.values[QI.phase])),'NOT_READY');
  const locations=[sheet_()].concat(ledgerSheets_('quote'));quoteMonthRows_().forEach(m=>{
    assert_(allowUncreatedMonths || m.values[1] && m.values[3]==='READY','NOT_READY');
    if(!m.values[1])return;
    SpreadsheetApp.openById(m.values[1]).getSheets().filter(s=>/^\d{4}-\d{2}-\d{2}$/.test(s.getName())).forEach(s=>locations.push(s));
  });
  locations.forEach(s=>{
    const seen=new Set();quoteRows_(s).forEach(r=>{
      const e=byId.get(r.row[0]);assert_(e && !seen.has(r.row[0]),'NOT_READY');seen.add(r.row[0]);
      const primary=e.values[QI.book]===s.getParent().getId() && Number(e.values[QI.sheet])===s.getSheetId();
      assert_(primary || (e.values[QI.copy] || allowSimpleCopies) && s.getParent().getId()===PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID') && s.getName()===RFQ_SHEET,'NOT_READY');
      quoteCheckIdentity_(e,r.row);
    });
  });
  entries.forEach(e=>{
    const r=quoteRecord_(e,cache,e.values[QI.phase]==='PREPARED');
    if(r && e.values[QI.phase]==='COMMITTED')assert_(Number(r.row[1])===Number(e.values[QI.seq]),'NOT_READY');
    if(e.values[QI.copy]){const legacy=quoteFindRow_(sheet_(),e.values[QI.id]);assert_(legacy,'NOT_READY');quoteCheckIdentity_(e,legacy.row);assert_(legacy.row[15]===r.row[15],'NOT_READY');}
  });
  return {requests:entries.length,committed:entries.filter(e=>e.values[QI.phase]==='COMMITTED').length,reservations:entries.filter(e=>e.values[QI.phase]==='PREPARED').length,months:quoteMonthRows_().length,unresolvedMonths:quoteMonthRows_().filter(m=>m.values[3]!=='READY').length};
}
function auditMonthlyQuoteStorage() { return lock_(()=>{assert_(quoteStorageConfigured_(),'NOT_READY');return quoteAudit_();}); }
/** Explicit owner-only copy migration. Never invoked by setup or public actions. */
function copyLegacyQuotesToMonthly(limit) {
  assert_(Number.isSafeInteger(limit) && limit>=1 && limit<=20);
  return lock_(()=>{
    assert_(quoteStorageConfigured_(),'NOT_READY');const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
    const legacy=sheet_(),selected=entries.filter(e=>e.values[QI.book]===legacy.getParent().getId() && Number(e.values[QI.sheet])===legacy.getSheetId() && e.values[QI.phase]==='COMMITTED').slice(0,limit);
    selected.forEach(e=>{const target=quoteDaySheet_(e.values[QI.day]);e.values[QI.slot]=quoteAllocateSlot_(target,entries);e.values[QI.targetBook]=target.getParent().getId();e.values[QI.targetSheet]=target.getSheetId();e.values[QI.phase]='MIGRATING';quoteSaveEntry_(e);quoteFinishMigration_(e,cache,entries);quoteRefreshSummaryBestEffort_(e,entries);});
    return {copied:selected.length,sourceRowsRetained:true};
  });
}

// Sheets may consume a literal escape apostrophe while preserving text content.
function quoteCellEqual_(actual,expected) { return actual===expected || typeof expected==='string' && expected.startsWith("'") && actual===expected.slice(1); }

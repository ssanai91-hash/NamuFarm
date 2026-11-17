/** Annual two-tab compaction. No web action enables or executes physical deletion. */
const QUOTE_CLOSED_TAB='견적종료색인';
const QUOTE_CLOSED_HEADERS=['요청UUID','최초내용지문','접수시각(UTC)','접수시각(한국)','견적접수번호','변경순번','종료구분','종료시각(UTC)','원래연도','원래탭','정리단계'];
const QUOTE_DELTA_DAYS=90;
function quoteClosedSheet_(create){
  const p=PropertiesService.getScriptProperties(),id=p.getProperty('LEGACY_SPREADSHEET_ID')||p.getProperty('SPREADSHEET_ID');
  assert_(id,'NOT_READY');
  const book=SpreadsheetApp.openById(id);let sheet=book.getSheetByName(QUOTE_CLOSED_TAB);
  if(!sheet && !create)return null;
  if(!sheet)sheet=book.insertSheet(QUOTE_CLOSED_TAB);
  if(create && sheet.getLastRow()===0){sheet.getRange(1,1,1,11).setValues([QUOTE_CLOSED_HEADERS]);sheet.setFrozenRows(1);}
  assert_(canonical_(sheet.getRange(1,1,1,11).getValues()[0])===canonical_(QUOTE_CLOSED_HEADERS),'NOT_READY');
  return sheet;
}
function quoteClosedCheck_(row){
  assert_(UUID_PATTERN.test(String(row[0])) && /^[a-f0-9]{64}$/.test(String(row[1])) &&
    Number.isSafeInteger(Number(row[5])) && Number(row[5])>0 &&
    ['expired','admin_deleted'].includes(row[6]) && ['VERIFIED','REMOVED'].includes(row[10]) &&
    Number.isFinite(Date.parse(String(row[2]))) && Number.isFinite(Date.parse(String(row[7]))),'NOT_READY');
  assert_(receiptTime_(row[2])===row[3] && /^(NAMU|NFQ)-\d{8}-\d{4,16}$/.test(String(row[4])) &&
    row[8]===receiptTime_(row[2]).slice(0,4) && [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].includes(row[9]),'NOT_READY');
  const receipt=Array(18).fill('');receipt[2]=row[2];receipt[16]=row[4];receiptCounter_(receipt);
  return row;
}
function quoteClosedEntries_(){
  const sheet=quoteClosedSheet_(false);if(!sheet)return [];
  const ids=new Set();return namedRows_(sheet,11).map((row,index)=>{
    quoteClosedCheck_(row);assert_(!ids.has(row[0]),'NOT_READY');ids.add(row[0]);return {sheet,row,index};
  });
}
function quoteClosedFind_(id){
  const sheet=quoteClosedSheet_(false);if(!sheet || sheet.getLastRow()<2)return null;
  const hits=sheet.getRange(2,1,sheet.getLastRow()-1,1).createTextFinder(id).matchEntireCell(true).findAll();
  assert_(hits.length<=1,'NOT_READY');if(!hits.length)return null;
  const index=hits[0].getRow()-2,row=quoteClosedCheck_(sheet.getRange(index+2,1,1,11).getValues()[0]);
  return {sheet,row,index};
}
function quoteClosedMatch_(record,entry){
  const a=record.row,b=entry.row;
  assert_(a[0]===b[0] && a[11]===b[1] && a[2]===b[2] && a[17]===b[3] && a[16]===b[4] &&
    JSON.parse(a[15]).expired===true,'CONFLICT');
}
function quoteClosedRecord_(entry){
  const c=entry.row,row=Array(18).fill('');row[0]=c[0];row[1]=Number(c[5]);row[2]=c[2];row[9]='보관종료';
  row[10]='STOPPED';row[11]=c[1];row[15]=JSON.stringify({expired:true,deleted:c[6]==='admin_deleted',closedAt:c[7]});
  row[16]=c[4];row[17]=c[3];return {row,archived:true};
}
function quoteClosedPut_(record,phase){
  const r=record.row,raw=JSON.parse(r[15]);assert_(raw.expired===true,'NOT_READY');
  const next=[r[0],r[11],r[2],r[17],r[16],String(r[1]),raw.deleted===true?'admin_deleted':'expired',
    raw.closedAt || new Date(Date.parse(String(r[2]))+QUOTE_RETENTION_MS).toISOString(),
    receiptTime_(r[2]).slice(0,4),record.sheet.getName(),phase];
  quoteClosedCheck_(next);
  const old=quoteClosedFind_(r[0]);
  if(old){
    [0,1,2,3,4,8,9].forEach(i=>assert_(old.row[i]===next[i],'CONFLICT'));
    assert_(Number(old.row[5])<=Number(next[5]) && !(old.row[6]==='admin_deleted' && next[6]!=='admin_deleted'),'CONFLICT');
  }
  const sheet=old?old.sheet:quoteClosedSheet_(true),n=old?old.index+2:sheet.getLastRow()+1;
  const maximum=sheet.getMaxRows();if(n>maximum)sheet.insertRowsAfter(maximum,100);
  sheet.getRange(n,1,1,11).setNumberFormat('@').setValues([next]);SpreadsheetApp.flush();
  assert_(canonical_(sheet.getRange(n,1,1,11).getValues()[0])===canonical_(next),'NOT_READY');
  return {sheet,row:next,index:n-2};
}
function quoteClosedDelete_(entry){
  if(entry.row[6]==='admin_deleted')return;
  const next=entry.row.slice();next[5]=String(twoTabNextSeq_([]));next[6]='admin_deleted';next[7]=new Date().toISOString();
  entry.sheet.getRange(entry.index+2,1,1,11).setNumberFormat('@').setValues([next]);SpreadsheetApp.flush();
  assert_(canonical_(entry.sheet.getRange(entry.index+2,1,1,11).getValues()[0])===canonical_(next),'NOT_READY');
}
/** v1 compatibility: one latest, minimal tombstone per UUID, including interrupted compaction. */
function quoteCompatibleRecords_(){
  const records=twoTabRecords_(),byId=new Map(records.map(r=>[r.row[0],r]));
  quoteClosedEntries_().forEach(entry=>{
    const archived=quoteClosedRecord_(entry),old=byId.get(archived.row[0]);
    if(old)quoteClosedMatch_(old,entry);
    if(!old || Number(old.row[1])<Number(archived.row[1]))byId.set(archived.row[0],archived);
  });
  const all=Array.from(byId.values()).sort((a,b)=>Number(a.row[1])-Number(b.row[1]));let last=0;
  all.forEach(r=>{assert_(Number(r.row[1])>last,'NOT_READY');last=Number(r.row[1]);});return all;
}
function quoteSyncV2_(body,snapshot){
  assert_(twoBookActive_(),'NOT_READY');
  assert_(Number.isSafeInteger(body.afterSequence) && body.afterSequence>=0,'INVALID');
  const base=body.fromSequence===undefined?body.afterSequence:body.fromSequence;
  assert_(Number.isSafeInteger(base) && base>=0 && base<=body.afterSequence && (!snapshot || base===0),'INVALID');
  return lock_(()=>{
    const records=quoteCompatibleRecords_(),now=Date.now(),recent=now-QUOTE_DELTA_DAYS*86400000;
    let floor=0,highest=0;
    const all=[];
    records.forEach(record=>{
      const row=quoteResponseRow_(record.row);highest=Math.max(highest,row.sequence);
      if(row.expired){
        const raw=JSON.parse(record.row[15]),closed=raw.closedAt || new Date(Date.parse(row.createdAt)+QUOTE_RETENTION_MS).toISOString();
        if(Date.parse(closed)<recent){floor=Math.max(floor,row.sequence);return;}
        if(snapshot)return;
      }
      if(snapshot || row.sequence>base)all.push(row);
    });
    const watermark=Math.max(highest,Number(PropertiesService.getScriptProperties().getProperty('LAST_SEQUENCE')||0));
    const common={quoteSyncVersion:2,scope:snapshot?'active':'changes',schemaEpoch:1,watermark,minAvailableSequence:floor};
    if(!snapshot && base<floor)return Object.assign(common,{requiresSnapshot:true,requests:[],hasMore:false});
    const token=hash_(JSON.stringify([snapshot,base,watermark,floor,all]));
    assert_(!body.snapshotId || equal_(body.snapshotId,token),'BUSY');
    assert_(body.afterSequence===base || /^[a-f0-9]{64}$/.test(body.snapshotId||''),'INVALID');
    const remaining=all.filter(r=>r.sequence>body.afterSequence);
    return Object.assign(common,{requiresSnapshot:false,snapshotId:token,total:all.length,
      requests:remaining.slice(0,100),hasMore:remaining.length>100});
  });
}
function quoteRequestStates_(body){
  assert_(twoBookActive_() && body.quoteSyncVersion===2,'NOT_READY');
  assert_(Array.isArray(body.requestIds) && body.requestIds.length<=50 &&
    body.requestIds.every(id=>UUID_PATTERN.test(id)) && new Set(body.requestIds).size===body.requestIds.length,'INVALID');
  return lock_(()=>{
    const live=new Map(twoTabRecords_().map(r=>[r.row[0],r]));
    return {quoteSyncVersion:2,schemaEpoch:1,states:body.requestIds.map(id=>{
      const entry=quoteClosedFind_(id),archived=entry?quoteClosedRecord_(entry):null,old=live.get(id);
      if(old && entry)quoteClosedMatch_(old,entry);
      const record=!old || (archived && Number(archived.row[1])>Number(old.row[1]))?archived:old;
      if(!record)return {requestId:id,state:'unknown',sequence:0};
      const r=quoteResponseRow_(record.row);return {requestId:id,state:r.deleted?'admin_deleted':r.expired?'expired':'active',sequence:r.sequence};
    })};
  });
}
/** Entire-grid deletion is deliberately conservative. Unknown structures are skipped. */
function quoteCompactSafe_(sheet,n,blank){
  const width=sheet.getMaxColumns(),range=sheet.getRange(n,1,1,width);
  if(sheet.getFrozenRows()>=n || sheet.getRowGroupDepth(n)>0 || range.getMergedRanges().length ||
    sheet.getFilter() || sheet.getCharts().length || sheet.getDrawings().length || sheet.getBandings().length ||
    sheet.getImages().length || sheet.getNamedRanges().length ||
    sheet.getConditionalFormatRules().length)return false;
  if(sheet.getProtections(SpreadsheetApp.ProtectionType.SHEET).length ||
    sheet.getProtections(SpreadsheetApp.ProtectionType.RANGE).some(p=>{
      const r=p.getRange();return r.getRow()<=n && r.getLastRow()>=n;
    }))return false;
  const values=range.getValues()[0],formulas=range.getFormulas()[0],notes=range.getNotes()[0],validations=range.getDataValidations()[0];
  return values.every((v,i)=>((!blank && i<18)||v==='') && !formulas[i] && !notes[i] && !validations[i]);
}
/** Owner preflight is read only: no setup sheet, trigger, expiry or customer edits. */
function planExpiredQuoteCompaction(){
  assert_(!NAMUFARM_WEB_REQUEST && twoBookActive_(),'UNAUTHORIZED');
  return lock_(()=>{
    const rows=twoTabRecords_(),sheets=new Map(),summary=[];
    annualFileEntries_().filter(e=>e.values[0]==='quote' && e.values[4]==='READY').forEach(e=>{
      const book=SpreadsheetApp.openById(e.values[2]);[TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].forEach(name=>{
        const sheet=book.getSheetByName(name);sheets.set(sheet.getSheetId()+':'+book.getId(),sheet);
      });
    });
    sheets.forEach(sheet=>{
      const counts={year:sheet.getParent().getName(),tab:sheet.getName(),expired:0,blank:0,active:0,skipped:0,unusedGridRows:sheet.getMaxRows()-sheet.getLastRow()};
      namedRows_(sheet,18).forEach((r,i)=>{
        const blank=r.every(v=>v===''),expired=!blank && (JSON.parse(r[15]).expired===true || Date.parse(String(r[2]))+QUOTE_RETENTION_MS<=Date.now());
        if(!blank && !expired){counts.active++;return;}
        if(!quoteCompactSafe_(sheet,i+2,blank)){counts.skipped++;return;}
        counts[blank?'blank':'expired']++;
      });summary.push(counts);
    });
    // Validate pre-existing index identity before proposing a plan.
    quoteCompatibleRecords_();
    const planId=hash_(canonical_({summary,heads:rows.map(r=>[r.row[0],r.row[1],r.row[11]])}));
    return {enabled:PropertiesService.getScriptProperties().getProperty('QUOTE_COMPACTION_ENABLED')==='1',planId,summary};
  });
}
/** Editor only. Paste the fresh planId AFTER taking the documented owner backup. */
function enableExpiredQuoteCompaction(planId){
  assert_(!NAMUFARM_WEB_REQUEST,'UNAUTHORIZED');
  const plan=planExpiredQuoteCompaction();assert_(plan.planId===planId,'CONFLICT');
  PropertiesService.getScriptProperties().setProperty('QUOTE_COMPACTION_ENABLED','1');
  return {enabled:true};
}
function disableExpiredQuoteCompaction(){
  assert_(!NAMUFARM_WEB_REQUEST,'UNAUTHORIZED');
  PropertiesService.getScriptProperties().deleteProperty('QUOTE_COMPACTION_ENABLED');return {enabled:false};
}
function quoteCompactDelete_(sheet,n){
  // Keep at least one non-frozen writable row, including previously trimmed sheets.
  const maximum=sheet.getMaxRows();if(maximum<=Math.max(2,sheet.getFrozenRows()+1))sheet.insertRowsAfter(maximum,1);
  sheet.deleteRows(n,1);SpreadsheetApp.flush();
}
/** Manual small batches only, never called by public intake or existing hourly purge. */
function compactExpiredQuotes(){
  assert_(!NAMUFARM_WEB_REQUEST && twoBookActive_(),'UNAUTHORIZED');
  assert_(PropertiesService.getScriptProperties().getProperty('QUOTE_COMPACTION_ENABLED')==='1','NOT_READY');
  return lock_(()=>{
    const started=Date.now(),records=twoTabRecords_();let removed=0,skipped=0;
    // Work from the bottom. Re-find every UUID after each deletion; never reuse stale row offsets.
    for(const rec of records.slice().reverse()){
      if(removed>=40 || Date.now()-started>45000)break;
      const raw=JSON.parse(rec.row[15]);
      if(!raw.expired && Date.parse(String(rec.row[2]))+QUOTE_RETENTION_MS>Date.now())continue;
      // Preserve unknown customer annotations BEFORE any expiry scrub.
      const current=quoteFindRow_(rec.sheet,rec.row[0]);
      if(!current || !quoteCompactSafe_(rec.sheet,current.index+2,false)){skipped++;continue;}
      if(!raw.expired){twoTabClose_(rec.row[0],false);}
      const source=quoteFindRow_(rec.sheet,rec.row[0]);assert_(source,'NOT_READY');
      const entry=quoteClosedPut_({sheet:rec.sheet,row:source.row},'VERIFIED');
      // Redundant move copies must also be closed before removal. Leave them intact if unsafe.
      let blocked=false;
      for(const copy of rec.requestCopies||[]){
        const found=quoteFindRow_(copy.sheet,rec.row[0]);if(!found)continue;
        if(found.row[11]!==source.row[11] || !quoteCompactSafe_(copy.sheet,found.index+2,false)){blocked=true;skipped++;break;}
        if(removed>=40 || Date.now()-started>45000){blocked=true;break;}
        quoteCompactDelete_(copy.sheet,found.index+2);removed++;
      }
      if(blocked || removed>=40 || Date.now()-started>45000)continue;
      const verified=quoteClosedFind_(rec.row[0]),fresh=quoteFindRow_(rec.sheet,rec.row[0]);
      assert_(verified && canonical_(verified.row)===canonical_(entry.row) && fresh &&
        canonical_(fresh.row)===canonical_(source.row),'CONFLICT');
      if(!quoteCompactSafe_(rec.sheet,fresh.index+2,false)){skipped++;continue;}
      quoteCompactDelete_(rec.sheet,fresh.index+2);
      assert_(!quoteFindRow_(rec.sheet,rec.row[0]),'NOT_READY');
      const done=entry.row.slice();done[10]='REMOVED';entry.sheet.getRange(entry.index+2,1,1,11).setValues([done]);
      SpreadsheetApp.flush();assert_(canonical_(entry.sheet.getRange(entry.index+2,1,1,11).getValues()[0])===canonical_(done),'NOT_READY');removed++;
    }
    // Empty rows from confirmed moves. Only known quote tabs, never owner memo tabs.
    for(const e of annualFileEntries_().filter(e=>e.values[0]==='quote' && e.values[4]==='READY')){
      if(removed>=40 || Date.now()-started>45000)break;
      const book=SpreadsheetApp.openById(e.values[2]);
      for(const name of [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB]){
        const sheet=book.getSheetByName(name);const rows=namedRows_(sheet,18);
        for(let i=rows.length-1;i>=0;i--){
          if(removed>=40 || Date.now()-started>45000)break;
          if(!rows[i].every(v=>v===''))continue;
          if(!quoteCompactSafe_(sheet,i+2,true)){skipped++;continue;}
          quoteCompactDelete_(sheet,i+2);removed++;
        }
        // Trim unused grid tail too, but retain a writable row after the header.
        let tail=sheet.getMaxRows();
        while(tail>Math.max(2,sheet.getLastRow()) && removed<40 && Date.now()-started<=45000){
          if(!quoteCompactSafe_(sheet,tail,true)){skipped++;break;}
          sheet.deleteRows(tail,1);tail--;removed++;
        }
      }
    }
    return {removed,skipped,batchLimit:40,morePossible:true};
  });
}

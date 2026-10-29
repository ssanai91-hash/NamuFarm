/**
 * Two visible quote tabs per year, no monthly tabs or administrative tabs.
 * Active only after the owner-only cutover. Kept separate from legacy/indexed
 * storage so existing copies are never overwritten during preparation.
 */
const TWO_REQUEST_TAB='견적요청';
const TWO_CONFIRMED_TAB='견적확정';
function twoTabSheet_(year,name){
  assert_(name===TWO_REQUEST_TAB || name===TWO_CONFIRMED_TAB,'INVALID');
  const book=annualLedgerBook_('quote',year);
  return quoteEnsureHeaderSheet_(book,name,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS));
}
function twoTabRecords_(){
  const entries=annualFileEntries_().filter(e=>e.values[0]==='quote' && e.values[4]==='READY');
  const records=[],seen=new Map();
  entries.forEach(e=>{
    const book=SpreadsheetApp.openById(e.values[2]);
    [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].forEach(name=>{
      const sheet=book.getSheetByName(name);
      assert_(sheet && canonical_(sheet.getRange(1,1,1,18).getValues()[0])===
        canonical_(RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS)),'NOT_READY');
      namedRows_(sheet,18).forEach((row,index)=>{
        if(row.every(v=>v===''))return;
        assert_(UUID_PATTERN.test(String(row[0])) && Number.isSafeInteger(Number(row[1])),'NOT_READY');
        const id=String(row[0]),previous=seen.get(id);
        // An interrupted confirmed move can leave one old request copy. The
        // confirmed copy has the newer sequence and wins until cleanup.
        if(previous){
          assert_(previous.sheet.getName()!==name &&
            (name===TWO_CONFIRMED_TAB || previous.sheet.getName()===TWO_CONFIRMED_TAB) &&
            previous.row[11]===row[11],'NOT_READY');
          const current={sheet,row,index};
          if(name===TWO_CONFIRMED_TAB){
            // Can also repair a staged pre-cutover confirmed quote that was
            // copied from the request tab. No live quote exists only here.
            previous.sheet.getRange(previous.index+2,1,1,18).clearContent();
            SpreadsheetApp.flush();
            seen.set(id,current);
          }
        }else seen.set(id,{sheet,row,index});
      });
    });
  });
  seen.forEach(record=>records.push(record));
  records.sort((a,b)=>Number(a.row[1])-Number(b.row[1]));
  const sequences=new Set();
  records.forEach(r=>{
    const seq=Number(r.row[1]);
    assert_(seq>0 && !sequences.has(seq),'NOT_READY');
    sequences.add(seq);
  });
  return records;
}
function twoTabRecord_(id){
  return twoTabRecords_().find(record=>record.row[0]===id)||null;
}
function twoTabNextSeq_(records){
  const props=PropertiesService.getScriptProperties();
  const n=records.reduce((m,r)=>Math.max(m,Number(r.row[1])),
    Number(props.getProperty('LAST_SEQUENCE'))||0)+1;
  assert_(Number.isSafeInteger(n),'NOT_READY');
  props.setProperty('LAST_SEQUENCE',String(n));
  return n;
}
function twoTabSubmit_(body,stored){
  const records=twoTabRecords_(),old=records.find(r=>r.row[0]===body.requestId);
  if(old){
    assert_(old.row[11]===body.fingerprint,'CONFLICT');
    return Object.assign(receiptDetails_(old.row),{duplicate:true});
  }
  const now=new Date(),created=now.toISOString(),day=receiptDay_(created);
  const sheet=twoTabSheet_(day.slice(0,4),TWO_REQUEST_TAB);
  const floor=records.reduce((n,r)=>
    r.row[16] && receiptDay_(r.row[2])===day ?
      Math.max(n,receiptCounter_(r.row)) : n,0);
  const receipt=reserveReceipt_(PropertiesService.getScriptProperties(),day,floor);
  const seq=twoTabNextSeq_(records);
  const row=[body.requestId,seq,created,...quoteCustomerCells_(stored),
    '신규','PENDING',body.fingerprint,0,created,'',JSON.stringify(stored),
    receipt.number,receiptTime_(created)];
  quoteWriteVerified_(sheet,sheet.getLastRow()+1,row);
  return Object.assign(receiptDetails_(row),{duplicate:false});
}
function twoTabMoveConfirmed_(id,pricing){
  const records=twoTabRecords_(),old=records.find(r=>r.row[0]===id);
  assert_(old,'INVALID');
  let source=old,confirm=null;
  const year=receiptTime_(source.row[2]).slice(0,4);
  const target=twoTabSheet_(year,TWO_CONFIRMED_TAB);
  confirm=quoteFindRow_(target,id);
  const request=twoTabSheet_(year,TWO_REQUEST_TAB);
  const requestCopy=quoteFindRow_(request,id);
  if(confirm){
    // A saved destination is authoritative after a previous interrupted move.
    assert_(confirm.row[11]===source.row[11],'CONFLICT');
    if(pricing){
      const current=JSON.parse(confirm.row[15]);
      assert_(current.quotePricing && canonical_(current.quotePricing)===canonical_(pricing),'CONFLICT');
    }
  }else{
    assert_(source.sheet.getName()===TWO_REQUEST_TAB && source.row[9]==='신규','CONFLICT');
    const next=source.row.slice(),raw=JSON.parse(next[15]);
    next[1]=twoTabNextSeq_(records);
    next[9]='견적확정';
    if(pricing)raw.quotePricing=pricing;
    next[15]=JSON.stringify(raw);
    quoteWriteVerified_(target,target.getLastRow()+1,next);
    confirm=quoteFindRow_(target,id);
    assert_(confirm && canonical_(confirm.row)===canonical_(next),'NOT_READY');
  }
  // Only remove the request after the confirmed row was checked.
  if(requestCopy){
    request.getRange(requestCopy.index+2,1,1,18).clearContent();
    SpreadsheetApp.flush();
    assert_(!quoteFindRow_(request,id),'NOT_READY');
  }
  return confirm;
}
function twoTabSetStatus_(id,status){
  const rec=twoTabRecord_(id);
  assert_(rec,'INVALID');
  if(status==='견적확정'){
    if(rec.row[9]==='견적확정')return;
    twoTabMoveConfirmed_(id,null);return;
  }
  if(rec.row[9]===status)return;
  assert_(rec.sheet.getName()===TWO_CONFIRMED_TAB &&
    requestStatusTransitionAllowed_(String(rec.row[9]),status),'CONFLICT');
  const next=rec.row.slice();next[1]=twoTabNextSeq_(twoTabRecords_());next[9]=status;
  quoteWriteVerified_(rec.sheet,rec.index+2,next);
}
function twoTabPriceConfirm_(body){
  const rec=twoTabRecord_(body.requestId);
  assert_(rec,'INVALID');
  const raw=JSON.parse(rec.row[15]),request=quoteStoredData_(raw).request;
  assert_(request && ['신규','견적확정'].includes(rec.row[9]),'CONFLICT');
  const pricing=quotePricing_(body.quotePricing,request);
  if(rec.row[9]==='견적확정'){
    if(raw.quotePricing)assert_(canonical_(raw.quotePricing)===canonical_(pricing),'CONFLICT');
    else {
      const next=rec.row.slice();
      next[1]=twoTabNextSeq_(twoTabRecords_());
      next[15]=JSON.stringify(Object.assign({},raw,{quotePricing:pricing}));
      quoteWriteVerified_(rec.sheet,rec.index+2,next);
    }
  }else twoTabMoveConfirmed_(body.requestId,pricing);
  return {updated:true,requestId:body.requestId,status:'견적확정'};
}
function twoTabClose_(id,deleted){
  const rec=twoTabRecord_(id);
  if(!rec || JSON.parse(rec.row[15]).expired===true)return;
  const next=rec.row.slice();next[1]=twoTabNextSeq_(twoTabRecords_());
  for(let i=3;i<=8;i++)next[i]='';
  next[9]='보관종료';next[10]='STOPPED';next[14]='';next[15]=deleted?'{"expired":true,"deleted":true}':'{"expired":true}';
  quoteWriteVerified_(rec.sheet,rec.index+2,next);
}
function twoTabPurgeExpired_(){
  twoTabRecords_().forEach(rec=>{
    if(JSON.parse(rec.row[15]).expired!==true &&
      Date.parse(String(rec.row[2]))+QUOTE_RETENTION_MS<=Date.now())
      twoTabClose_(rec.row[0]);
  });
}

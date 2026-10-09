/** Owner-only recovery for an explicitly supplied, empty management workbook.
 * Set SPREADSHEET_ID in Script Properties before running this from the editor.
 * This helper is never dispatched by doPost, does not erase rows and is safe to retry.
 */
function initializeRecoveredServer() {
  const props=PropertiesService.getScriptProperties();
  const id=props.getProperty('SPREADSHEET_ID');assert_(id,'NOT_READY');
  const book=SpreadsheetApp.openById(id);
  if(props.getProperty('RECOVERY_INITIALIZED_FOR')!==id) {
    assert_(!props.getProperty('RECOVERY_INITIALIZED_FOR'),'CONFLICT');
    // Imported header-only workbook is required. Do not accidentally reset a live store.
    book.getSheets().forEach(sheet=>assert_(sheet.getLastRow()<=1,'CONFLICT'));
    assert_(!props.getProperty('QUOTE_STORAGE_READY') && !props.getProperty('QUOTE_STORAGE_MODE'),'CONFLICT');
    if(!props.getProperty('WEB_SECRET'))props.setProperty('WEB_SECRET',token_());
    // A reset must not place new records below existing PC/phone sync cursors.
    const floor=Date.now();
    for(const key of ['LAST_SEQUENCE','SALE_SEQUENCE']) {
      const existing=Number(props.getProperty(key)||0);
      assert_(Number.isSafeInteger(existing) && existing>=0,'NOT_READY');
      props.setProperty(key,String(Math.max(floor,existing)));
    }
    props.setProperty('RECOVERY_INITIALIZED_FOR',id);
  }
  setupQuoteRequests();
  setupMonthlyQuoteStorage();
  enableMonthlyQuoteStorage();
  const day=receiptTime_(new Date().toISOString()).slice(0,10);
  // First customer must not wait for monthly file/day-sheet creation.
  const daySheet=lock_(()=>quoteDaySheet_(day));
  const info=secureServiceInfo_();assert_(info.customerDataReady,'NOT_READY');
  console.log(JSON.stringify({ready:true,version:info.version,management:book.getUrl(),month:daySheet.getParent().getUrl(),audit:auditMonthlyQuoteStorage()}));
  return {ready:true,managementId:id,monthId:daySheet.getParent().getId()};
}

/** Owner-only cutover: verify existing records before switching to one Sheet.
 * UUIDs, receipts and sync sequences stay unchanged. Retry after an interrupted
 * copy is safe; source files remain available until their normal expiry.
 */
function simplifyQuoteStorage() {
  return lock_(()=>{
    const props=PropertiesService.getScriptProperties(),target=sheet_();
    assert_(receiptColumnsReady_(target),'NOT_READY');
    if(!quoteStorageConfigured_())return {writeMode:props.getProperty('QUOTE_STORAGE_MODE')||'single-v1',requests:receiptRows_(target).length};
    const entries=quoteIndexEntries_(),cache={};quoteRepairTransitions_(entries,cache);
    assert_(entries.every(e=>e.values[QI.phase]==='COMMITTED'),'NOT_READY');
    quoteAudit_(true,true);
    const destination=quoteRows_(target),byId=new Map();
    destination.forEach(r=>{assert_(!byId.has(r.row[0]),'NOT_READY');byId.set(r.row[0],r);});
    const sources=entries.map(e=>({entry:e,record:quoteRecord_(e,cache,false)}));
    sources.forEach(({entry,record})=>{
      const existing=byId.get(record.row[0]);
      if(existing){
        quoteCheckIdentity_(entry,existing.row);
        assert_(canonical_(existing.row)===canonical_(record.row),'CONFLICT');
      } else {
        quoteWriteVerified_(target,target.getLastRow()+1,record.row);
        byId.set(record.row[0],{row:record.row});
      }
    });
    const copied=quoteRows_(target),verified=new Map(copied.map(r=>[r.row[0],r.row]));
    assert_(copied.length===sources.length && verified.size===copied.length,'NOT_READY');
    sources.forEach(({record})=>assert_(canonical_(verified.get(record.row[0]))===canonical_(record.row),'NOT_READY'));
    // Write the routing flag last, after every copied row has been verified.
    props.setProperty('QUOTE_STORAGE_MODE','single-v1');
    props.setProperty('QUOTE_ARCHIVE_PENDING','1');
    props.setProperty('QUOTE_STORAGE_READY','0');
    console.log(JSON.stringify({writeMode:'single-v1',requests:copied.length,verified:true}));
    return {writeMode:'single-v1',requests:copied.length,verified:true};
  });
}

/** Retention/close also removes the pre-cutover copies, outside intake. */
function purgeArchivedQuoteCopies_() {
  const props=PropertiesService.getScriptProperties();
  if(props.getProperty('QUOTE_ARCHIVE_PENDING')!=='1' || quoteStorageConfigured_())return;
  const current=new Map(receiptRows_(sheet_()).map(r=>[r[0],r]));
  const cache={};let pending=false;
  quoteIndexEntries_().forEach(e=>{
    const source=quoteOpenSheet_(e.values[QI.book],e.values[QI.sheet],cache);
    if(source.getParent().getId()===props.getProperty('SPREADSHEET_ID') && source.getName()===RFQ_SHEET)return;
    const record=quoteFindRow_(source,e.values[QI.id],cache),live=current.get(e.values[QI.id]);
    assert_(record && live,'NOT_READY');
    if(JSON.parse(record.row[15]).expired===true)return;
    if(JSON.parse(live[15]).expired!==true){pending=true;return;}
    const row=record.row.slice();for(let c=3;c<=8;c++)row[c]='';
    row[9]='보관종료';row[10]='STOPPED';row[14]='';row[15]='{"expired":true}';
    quoteWriteVerified_(source,record.index+2,row);
  });
  if(!pending)props.deleteProperty('QUOTE_ARCHIVE_PENDING');
}

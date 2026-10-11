/** Separate business ledgers: one quote book and one paid-order book per KST year.
 * Called under the existing script lock. The management book only stores routing
 * metadata; no customer data is duplicated in these routing tables.
 */
const LEDGER_FILES = '연도별장부';
const LEDGER_FILE_HEADERS = ['구분','연도','파일ID','생성토큰','생성상태'];
const ORDER_LOCATIONS = '주문저장위치';
const ORDER_LOCATION_HEADERS = ['주문번호','견적요청UUID','파일ID','시트ID','최초입금확인시각'];
function yearlyLedgersEnabled_() {
  return PropertiesService.getScriptProperties().getProperty('LEDGER_STORAGE_MODE') === 'yearly-v1';
}
function ledgerFiles_() {
  const book=SpreadsheetApp.openById(PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID'));
  const sheet=book.getSheetByName(LEDGER_FILES);
  if(!sheet)return [];
  namedSheet_(LEDGER_FILES,LEDGER_FILE_HEADERS,false);
  const seen=new Set();
  return namedRows_(sheet,5).map((values,index)=>({values,index})).filter(e=>e.values.some(v=>v!=='' )).map(e=>{
    const v=e.values,key=v[0]+':'+v[1];
    assert_(['quote','order'].includes(v[0]) && /^\d{4}$/.test(String(v[1])) && !seen.has(key),'NOT_READY');
    assert_(UUID_PATTERN.test(v[3]) && ['RESERVED','CREATING','READY'].includes(v[4]) && (v[4]!=='READY'||!!v[2]),'NOT_READY');
    seen.add(key);return e;
  });
}
function ledgerHeaders_(kind) { return kind==='quote'?RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS):SALE_HEADERS; }
function ledgerEnsureYear_(kind,year) {
  assert_(['quote','order'].includes(kind) && /^\d{4}$/.test(String(year)));
  const table=namedSheet_(LEDGER_FILES,LEDGER_FILE_HEADERS,false);
  let entry=ledgerFiles_().find(e=>e.values[0]===kind && String(e.values[1])===String(year));
  if(!entry){
    entry={values:[kind,String(year),'',Utilities.getUuid(),'RESERVED'],index:table.getLastRow()-1};
    quoteWriteVerified_(table,entry.index+2,entry.values);
  }
  const v=entry.values;
  if(!v[2]){
    if(v[4]==='RESERVED'){
      v[4]='CREATING';quoteWriteVerified_(table,entry.index+2,v);
      const properties=Object.assign(quoteMonthFileProperties_(v[3]),{namufarmLedgerKind:kind,namufarmLedgerYear:String(year)});
      const file=Drive.Files.create({name:'나무팜_'+(kind==='quote'?'견적':'주문')+'_'+year,mimeType:'application/vnd.google-apps.spreadsheet',appProperties:properties},null,{fields:'id'});
      assert_(file && file.id,'NOT_READY');v[2]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }else{
      // An uncertain create must be recovered by its token, never repeated.
      const file=quoteFindCreatedFile_(v[3]);assert_(file,'NOT_READY');
      v[2]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }
  }
  const book=SpreadsheetApp.openById(v[2]);
  if(v[4]!=='READY'){
    book.setSpreadsheetTimeZone('Asia/Seoul');
    for(let m=1;m<=12;m++)quoteEnsureHeaderSheet_(book,String(m).padStart(2,'0')+'월',ledgerHeaders_(kind));
    v[4]='READY';quoteWriteVerified_(table,entry.index+2,v);
  }
  return book;
}
function ledgerMonthSheet_(kind,month) {
  assert_(/^\d{4}-(0[1-9]|1[0-2])$/.test(month));
  const book=ledgerEnsureYear_(kind,month.slice(0,4));
  const sheet=book.getSheetByName(month.slice(5)+'월');
  // A deleted month is not an empty month: do not silently replace lost records.
  assert_(sheet && canonical_(sheet.getRange(1,1,1,ledgerHeaders_(kind).length).getValues()[0])===canonical_(ledgerHeaders_(kind)),'NOT_READY');
  return sheet;
}
function ledgerSheets_(kind) {
  const sheets=[];
  ledgerFiles_().filter(e=>e.values[0]===kind && e.values[4]==='READY').forEach(e=>{
    const book=SpreadsheetApp.openById(e.values[2]);
    for(let month=1;month<=12;month++){
      const sheet=book.getSheetByName(String(month).padStart(2,'0')+'월'),headers=ledgerHeaders_(kind);
      assert_(sheet && canonical_(sheet.getRange(1,1,1,headers.length).getValues()[0])===canonical_(headers),'NOT_READY');
      sheets.push(sheet);
    }
  });
  return sheets;
}
function saleStorageSheets_() { return [namedSheet_(SALE_SHEET,SALE_HEADERS,false)].concat(ledgerSheets_('order')); }
function saleStorageRows_() {
  const seen=new Set();
  return saleStorageSheets_().flatMap(sheet=>namedRows_(sheet,20).filter(r=>r[0]!=='' )).map(row=>{
    assert_(!seen.has(row[0]),'NOT_READY');seen.add(row[0]);return row;
  });
}
function orderLocations_() {
  const seen=new Set(),sources=new Set();
  return namedRows_(namedSheet_(ORDER_LOCATIONS,ORDER_LOCATION_HEADERS,false),5)
    .map((values,index)=>({values,index})).filter(e=>e.values.some(v=>v!=='' )).map(e=>{
      const v=e.values;
      assert_(typeof v[0]==='string' && v[0] && !seen.has(v[0]) && v[2] && Number.isSafeInteger(Number(v[3])) && Number(v[3])>=0,'NOT_READY');
      assert_(Number.isSafeInteger(Number(v[4])) && Number(v[4])>=0 && (!v[1]||UUID_PATTERN.test(v[1])) && (!v[1]||!sources.has(v[1])),'NOT_READY');
      seen.add(v[0]);if(v[1])sources.add(v[1]);return e;
    });
}
function orderLocationSheet_(entry) {
  const v=entry.values,book=SpreadsheetApp.openById(v[2]),sheet=book.getSheetById(Number(v[3]));
  assert_(sheet && canonical_(sheet.getRange(1,1,1,20).getValues()[0])===canonical_(SALE_HEADERS),'NOT_READY');
  return sheet;
}
function saleWriteSheet_(meta,sourceRequestId) {
  if(!yearlyLedgersEnabled_())return namedSheet_(SALE_SHEET,SALE_HEADERS,false);
  const locations=orderLocations_();
  const expired=hash_(PropertiesService.getScriptProperties().getProperty('WEB_SECRET')+'expired-sale:'+meta.orderNumber);
  const entry=locations.find(e=>e.values[0]===meta.orderNumber || e.values[0]===expired);
  if(entry){
    assert_(entry.values[0]===meta.orderNumber && entry.values[1]===sourceRequestId,'CONFLICT');
    if(Number(entry.values[4])>0)assert_(Number(entry.values[4])===meta.paidAt,'CONFLICT');
    return orderLocationSheet_(entry);
  }
  assert_(!sourceRequestId || !locations.some(e=>e.values[1]===sourceRequestId),'CONFLICT');
  assert_(meta.revision===1 && meta.status==='입금확인' && meta.paidAt>0,'CONFLICT');
  if(sourceRequestId)assert_(quoteRequestStatus_(sourceRequestId)==='견적발송완료','CONFLICT');
  const sheet=ledgerMonthSheet_('order',seoulDay_(meta.paidAt).slice(0,7));
  const table=namedSheet_(ORDER_LOCATIONS,ORDER_LOCATION_HEADERS,false);
  // Reserve the immutable order/source/month together before writing the sale.
  // A failed row write is retried in this same location, even across midnight.
  quoteWriteVerified_(table,table.getLastRow()+1,[meta.orderNumber,sourceRequestId,sheet.getParent().getId(),sheet.getSheetId(),meta.paidAt]);
  return sheet;
}
function archiveOrderLocation_(number,archivedNumber) {
  const book=SpreadsheetApp.openById(PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID'));
  if(!book.getSheetByName(ORDER_LOCATIONS))return;
  const entry=orderLocations_().find(e=>e.values[0]===number);
  if(entry){const v=entry.values.slice();v[0]=archivedNumber;v[1]='';v[4]=0;
    quoteWriteVerified_(namedSheet_(ORDER_LOCATIONS,ORDER_LOCATION_HEADERS,false),entry.index+2,v);}
}
/** Re-enable the existing quote index after a prior single-sheet cutover.
 * Keep the old monthly primary plus its single-sheet copy so normal disposal
 * still reaches both. No customer row is deleted by this setup.
 */
function ledgerPrepareQuoteIndex_() {
  const props=PropertiesService.getScriptProperties();
  if(!quoteStorageConfigured_() && props.getProperty('QUOTE_ARCHIVE_PENDING')==='1'){
    lock_(()=>{
      const entries=quoteIndexEntries_();
      quoteRows_(sheet_()).forEach(record=>{
        const entry=entries.find(e=>e.values[QI.id]===record.row[0]);
        if(!entry)return;
        assert_(entry.values[QI.phase]==='COMMITTED','NOT_READY');quoteCheckIdentity_(entry,record.row);
        assert_(Number(record.row[1])>=Number(entry.values[QI.seq]),'CONFLICT');
        const target=quoteOpenSheet_(entry.values[QI.book],entry.values[QI.sheet]),original=quoteFindRow_(target,record.row[0]);
        assert_(original,'NOT_READY');quoteCheckIdentity_(entry,original.row);
        quoteWriteVerified_(target,original.index+2,record.row);
        entry.values[QI.seq]=Number(record.row[1]);entry.values[QI.status]=record.row[9];
        entry.values[QI.copy]=target.getParent().getId()!==sheet_().getParent().getId() || target.getSheetId()!==sheet_().getSheetId();
        quoteSaveEntry_(entry);
      });
    });
  }
  setupMonthlyQuoteStorage();
  props.deleteProperty('QUOTE_ARCHIVE_PENDING');
}
/** Run once in the Apps Script editor after deploying all .gs files.
 * Existing requests/orders stay in place and remain readable. New writes use
 * the yearly ledgers. Running this again neither moves nor duplicates data.
 */
function setupYearlyLedgers() {
  setupQuoteRequests();ledgerPrepareQuoteIndex_();
  return lock_(()=>{
    namedSheet_(LEDGER_FILES,LEDGER_FILE_HEADERS,true);
    namedSheet_(ORDER_LOCATIONS,ORDER_LOCATION_HEADERS,true);
    const table=namedSheet_(ORDER_LOCATIONS,ORDER_LOCATION_HEADERS,false),entries=orderLocations_();
    // Register old orders without moving their original rows or changing IDs.
    saleStorageSheets_().forEach(sheet=>namedRows_(sheet,20).filter(row=>row[0]!=='' ).forEach(row=>{
      const meta=JSON.parse(row[15]),stored=meta.archived?{}:JSON.parse(row[16]);
      const source=stored.personalData?stored.personalData.order.sourceRequestId:'';
      const existing=entries.find(e=>e.values[0]===row[0]);
      if(existing){assert_(existing.values[2]===sheet.getParent().getId() && Number(existing.values[3])===sheet.getSheetId(),'CONFLICT');return;}
      assert_(!source || !entries.some(e=>e.values[1]===source),'CONFLICT');
      const values=[row[0],source,sheet.getParent().getId(),sheet.getSheetId(),meta.paidAt||0];
      quoteWriteVerified_(table,table.getLastRow()+1,values);entries.push({values});
    }));
    const props=PropertiesService.getScriptProperties(),rows=saleStorageRows_();
    props.setProperty('SALE_SEQUENCE',String(rows.reduce((n,r)=>Math.max(n,Number(r[1])||0),Number(props.getProperty('SALE_SEQUENCE'))||0)));
    const year=seoulDay_(Date.now()).slice(0,4),quotes=ledgerEnsureYear_('quote',year),orders=ledgerEnsureYear_('order',year);
    quoteAudit_();
    props.setProperty('QUOTE_STORAGE_MODE','yearly-v1');props.setProperty('LEDGER_STORAGE_MODE','yearly-v1');
    return {ready:true,writeMode:'yearly-v1',quoteFileId:quotes.getId(),orderFileId:orders.getId(),year};
  });
}

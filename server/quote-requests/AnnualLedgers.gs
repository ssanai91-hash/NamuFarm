/** Annual quote/order workbooks. Called under the existing script lock.
 * Quote month = KST receipt month; order month = first confirmed payment month.
 * Existing records remain at their original location; never move them on update.
 */
const ANNUAL_FILES = '연도별장부파일';
const ANNUAL_FILE_HEADERS = ['구분','연도','파일ID','생성토큰','생성상태','생성시각(UTC)'];
const ANNUAL_QUOTE_INDEX = '연도견적색인';
const SALE_SOURCES = '주문견적연결';
const SALE_SOURCE_HEADERS = ['견적식별해시','주문번호'];
function annualLedgersEnabled_() {
  return PropertiesService.getScriptProperties().getProperty('ANNUAL_LEDGER_MODE') === 'annual-v1';
}
/** Active registry lives in script properties, not a third quote tab. */
function annualFileEntries_() {
  if(twoBookActive_()){
    const data=JSON.parse(PropertiesService.getScriptProperties().getProperty('TWO_BOOK_REGISTRY')||'{}');
    return Object.keys(data).map((key,index)=>{
      const values=data[key],parts=key.split(':');
      assert_(parts.length===2 && ['quote','order'].includes(parts[0]) &&
        /^\d{4}$/.test(parts[1]) && values && values.id,'NOT_READY');
      return {values:[parts[0],parts[1],values.id,'','READY',''],index:index};
    });
  }
  const book=SpreadsheetApp.openById(PropertiesService.getScriptProperties().getProperty('SPREADSHEET_ID'));
  if(!book.getSheetByName(ANNUAL_FILES))return [];
  const seen=new Set();
  return namedRows_(namedSheet_(ANNUAL_FILES,ANNUAL_FILE_HEADERS,false),6)
    .map((values,index)=>({values:values,index:index})).filter(e=>e.values.some(v=>v!==''))
    .map(e=>{const v=e.values,key=v[0]+':'+v[1];
      assert_(['quote','order'].includes(v[0]) && /^\d{4}$/.test(String(v[1])) && !seen.has(key),'NOT_READY');
      assert_(UUID_PATTERN.test(v[3]) && ['RESERVED','CREATING','READY'].includes(v[4]),'NOT_READY');
      assert_(v[4]!=='READY' || !!v[2],'NOT_READY');seen.add(key);return e;
    });
}
function annualLedgerBook_(kind,year) {
  assert_(['quote','order'].includes(kind) && /^\d{4}$/.test(String(year)));
  if(twoBookActive_())return annualActiveBook_(kind,year);
  const table=namedSheet_(ANNUAL_FILES,ANNUAL_FILE_HEADERS,true);
  let entry=annualFileEntries_().find(e=>e.values[0]===kind && String(e.values[1])===String(year));
  if(!entry){
    entry={values:[kind,String(year),'',Utilities.getUuid(),'RESERVED',new Date().toISOString()],index:table.getLastRow()-1};
    quoteWriteVerified_(table,entry.index+2,entry.values);
  }
  const v=entry.values;
  if(!v[2]){
    if(v[4]==='RESERVED'){
      v[4]='CREATING';quoteWriteVerified_(table,entry.index+2,v);
      const file=Drive.Files.create({name:'나무팜_'+(kind==='quote'?'견적':'주문')+'_'+year,
        mimeType:'application/vnd.google-apps.spreadsheet',appProperties:quoteMonthFileProperties_(v[3])},null,{fields:'id'});
      assert_(file && file.id,'NOT_READY');v[2]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }else{
      // A lost create response must not result in another annual workbook.
      const file=quoteFindCreatedFile_(v[3]);assert_(file,'NOT_READY');
      v[2]=file.id;quoteWriteVerified_(table,entry.index+2,v);
    }
  }
  const book=SpreadsheetApp.openById(v[2]);
  if(v[4]!=='READY'){
    book.setSpreadsheetTimeZone('Asia/Seoul');
    annualEnsureTabs_(book,kind);
    v[4]='READY';quoteWriteVerified_(table,entry.index+2,v);
  }
  return book;
}
function annualEnsureTabs_(book,kind){
  if(kind==='quote'){
    [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].forEach(name=>
      quoteEnsureHeaderSheet_(book,name,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS)));
    // Keep only the two business tabs. Never discard a nonempty unknown tab.
    book.getSheets().filter(sheet=>
      ![TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].includes(sheet.getName())).forEach(sheet=>{
      assert_(sheet.getLastRow()===0,'CONFLICT');
      book.deleteSheet(sheet);
    });
  }else{
    for(let month=1;month<=12;month++)
      quoteEnsureHeaderSheet_(book,String(month).padStart(2,'0')+'월',SALE_HEADERS);
    book.getSheets().filter(sheet=>
      !/^((0[1-9])|(1[0-2]))월$/.test(sheet.getName()) &&
      sheet.getLastRow()===0).forEach(sheet=>book.deleteSheet(sheet));
  }
}
function annualActiveBook_(kind,year){
  const props=PropertiesService.getScriptProperties(),key=kind+':'+year;
  const registry=JSON.parse(props.getProperty('TWO_BOOK_REGISTRY')||'{}');
  if(registry[key]){
    const book=SpreadsheetApp.openById(registry[key].id);
    annualEnsureTabs_(book,kind);return book;
  }
  const tokenKey='TWO_BOOK_CREATE_'+kind+'_'+year;
  let token=props.getProperty(tokenKey);
  if(!token){
    token=Utilities.getUuid();props.setProperty(tokenKey,token);
  }
  // Search first on every retry. An uncertain Drive create must never create
  // a second annual workbook.
  const saved=quoteFindCreatedFile_(token);
  const entry=saved || Drive.Files.create({
    name:'나무팜_'+(kind==='quote'?'견적':'주문')+'_'+year,
    mimeType:'application/vnd.google-apps.spreadsheet',
    appProperties:quoteMonthFileProperties_(token)
  },null,{fields:'id'});
  assert_(entry && entry.id,'NOT_READY');
  const book=SpreadsheetApp.openById(entry.id);
  annualEnsureTabs_(book,kind);
  registry[key]={id:entry.id};
  props.setProperty('TWO_BOOK_REGISTRY',JSON.stringify(registry));
  props.deleteProperty(tokenKey);
  return book;
}
function annualQuoteSheet_(day) {
  assert_(/^\d{4}-\d{2}-\d{2}$/.test(day));
  return quoteEnsureHeaderSheet_(annualLedgerBook_('quote',day.slice(0,4)),
    TWO_REQUEST_TAB,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS));
}
function newSaleSheet_(meta) {
  // Unpaid legacy Android purchases keep their existing compatibility store.
  if(!annualLedgersEnabled_() || !meta.paidAt)return namedSheet_(SALE_SHEET,SALE_HEADERS,false);
  const day=seoulDay_(meta.paidAt);
  return quoteEnsureHeaderSheet_(annualLedgerBook_('order',day.slice(0,4)),day.slice(5,7)+'월',SALE_HEADERS);
}
/** Identity is the order number, never a remembered row position. */
function saleRecords_() {
  const sheets=[namedSheet_(SALE_SHEET,SALE_HEADERS,false)];
  annualFileEntries_().filter(e=>e.values[0]==='order').forEach(e=>{
    // A failed first-time creation cannot contain accepted sales yet. A READY
    // book, however, must be accessible in full; fail closed on missing tabs.
    if(e.values[4]!=='READY')return;
    const book=SpreadsheetApp.openById(e.values[2]);
    for(let month=1;month<=12;month++){
      const sheet=book.getSheetByName(String(month).padStart(2,'0')+'월');
      assert_(sheet && canonical_(sheet.getRange(1,1,1,20).getValues()[0])===canonical_(SALE_HEADERS),'NOT_READY');
      sheets.push(sheet);
    }
  });
  const seen=new Set(),records=[];
  sheets.forEach(sheet=>namedRows_(sheet,20).forEach((row,index)=>{
    if(row.every(v=>v===''))return;
    assert_(typeof row[0]==='string' && row[0] && !seen.has(row[0]),'NOT_READY');seen.add(row[0]);
    records.push({sheet:sheet,row:row,index:index});
  }));
  return records;
}
/** Reserve before the sale write, so a failed/replayed payment stays bound to
 * the same quote and order. The binding survives cancellation and completion.
 */
function reserveSaleSource_(body,records,existing,commit) {
  if(!annualLedgersEnabled_())return;
  const source=body.sourceRequestId,number=body.metadata.orderNumber;
  const table=namedSheet_(SALE_SOURCES,SALE_SOURCE_HEADERS,false),bindings=namedRows_(table,2);
  const key=source?hash_('quote:'+source):'';
  const bound=bindings.filter(r=>r[1]===number || key && r[0]===key);
  assert_(bound.length<=1 && (!bound.length || bound[0][0]===key && bound[0][1]===number),'CONFLICT');
  if(existing){
    const stored=JSON.parse(existing.row[16] || '{}');
    if(stored.personalData){
      assert_(stored.personalData.order.sourceRequestId===source,'CONFLICT');
      if(body.personalData)assert_(stored.personalData.order.sourceQuoteNumber===body.personalData.order.sourceQuoteNumber,'CONFLICT');
    }
  }
  if(!source)return;
  records.forEach(r=>{
    if(r.row[0]===number || !r.row[16])return;
    const stored=JSON.parse(r.row[16]);
    assert_(!stored.personalData || stored.personalData.order.sourceRequestId!==source,'CONFLICT');
  });
  if(!existing && !bound.length){
    // Only a sent quote can be converted, but retries remain valid after RFQ expiry.
    assert_(['견적확정','견적발송완료'].includes(quoteRequestStatus_(source)),'CONFLICT');
  }
  if(commit && !bound.length)quoteWriteVerified_(table,table.getLastRow()+1,[key,number]);
}
function saleRequestSaved_(body) {
  if(!body.sourceRequestId)return;
  // Preserve the original confirmed quote for review, while changing its
  // status so Android's quote-only inbox no longer shows a paid sale.
  if(twoBookActive_()){
    updateLinkedRequestStatus_(body.sourceRequestId,body.metadata.status);
    return;
  }
  if(annualLedgersEnabled_() || body.personalData!==undefined)return;
  if(quoteStorageConfigured_())closeIndexedQuote_(body.sourceRequestId);
  else {const record=quoteFindRow_(sheet_(),body.sourceRequestId);if(record)expireQuoteRow_(record.sheet,record.row,record.index);}
}
/** Owner-only activation, never exposed through doPost. No customer rows are
 * copied, removed, or renumbered. Build a fresh location index, then flip one
 * routing property LAST. Failed setup can safely be run again.
 */
function setupAnnualQuoteOrderLedgers() {
  return lock_(()=>{
    const props=PropertiesService.getScriptProperties();assert_(props.getProperty('PRIVACY_SETUP')==='1','NOT_READY');
    const year=seoulDay_(Date.now()).slice(0,4);
    const quote=annualLedgerBook_('quote',year),order=annualLedgerBook_('order',year);
    namedSheet_(SALE_SOURCES,SALE_SOURCE_HEADERS,true);
    if(!annualLedgersEnabled_()){
      let values;
      if(quoteStorageConfigured_()){
        const entries=quoteIndexEntries_();quoteRepairTransitions_(entries,{});
        assert_(entries.every(e=>e.values[QI.phase]==='COMMITTED'),'NOT_READY');
        values=entries.map(e=>e.values);
      }else values=quoteRows_(sheet_()).map(r=>quoteEntryForRow_(r.sheet,r.row,r.index));
      namedSheet_(QUOTE_MONTHS,QUOTE_MONTH_HEADERS,true);
      const index=namedSheet_(ANNUAL_QUOTE_INDEX,QUOTE_INDEX_HEADERS,true);
      values.forEach((v,i)=>quoteWriteVerified_(index,i+2,v));
      for(let i=values.length+2;i<=index.getLastRow();i++)quoteWriteVerified_(index,i,Array(QUOTE_INDEX_HEADERS.length).fill(''));
      const entries=quoteIndexEntries_(index),cache={};
      entries.forEach(e=>quoteRecord_(e,cache,false));
      assert_(entries.length===values.length,'NOT_READY');
      props.setProperty('ANNUAL_LEDGER_MODE','annual-v1');
    }
    return {ready:true,storage:'annual-v1',year:year,quoteFileId:quote.getId(),orderFileId:order.getId(),existingRecordsPreserved:true};
  });
}
/** Owner recovery for a creation whose response was lost and Drive search is
 * not yet consistent. Verify its private creation token before accepting ID.
 */
function recoverAnnualLedgerFile(kind,year,fileId) {
  return lock_(()=>{
    const e=annualFileEntries_().find(e=>e.values[0]===kind && String(e.values[1])===String(year));
    assert_(e && e.values[4]==='CREATING' && !e.values[2],'CONFLICT');
    const file=Drive.Files.get(fileId,{fields:'id,mimeType,trashed,appProperties'});
    assert_(file && !file.trashed && file.mimeType==='application/vnd.google-apps.spreadsheet' &&
      canonical_(file.appProperties)===canonical_(quoteMonthFileProperties_(e.values[3])),'CONFLICT');
    e.values[2]=fileId;quoteWriteVerified_(namedSheet_(ANNUAL_FILES,ANNUAL_FILE_HEADERS,false),e.index+2,e.values);
    return {fileId:annualLedgerBook_(kind,year).getId()};
  });
}

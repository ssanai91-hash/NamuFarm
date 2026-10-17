/**
 * Owner-only two-workbook cutover. Never call from doPost or an installed trigger.
 *
 * Step 1: setupAnnualQuoteOrderLedgers() creates annual quote/order workbooks and
 * enables annual writes while the legacy master remains available.
 * Step 2: cutoverToTwoBooks() copies and verifies all legacy customer and ledger
 * records, then switches the server's two routing properties under one lock.
 *
 * Retain the old workbook as a READ-ONLY backup until the seller verifies both
 * app and desktop. This function never clears, deletes, or renames it.
 */
function twoBookActive_() {
  return PropertiesService.getScriptProperties().getProperty('TWO_BOOK_MODE')==='two-books-v1';
}

/**
 * Owner-only setup for TEST operation: use existing annual quote/order books
 * without copying or deleting any historical requests or sales.
 *
 * Only script properties are switched; SPREADSHEET_ID continues to point to
 * the existing administration workbook and its private indexes/receipts.
 * All NEW requests are written into 견적요청 and finalized into 견적확정;
 * new paid orders go to their 01월~12월 tab in the order workbook.
 *
 * Run once from the existing Apps Script editor after updating
 * TwoBookCutover.gs, AnnualLedgers.gs, and SecureCommerce.gs.
 */
function activateExistingTwoBooksForTesting() {
  return lock_(() => {
    const props = PropertiesService.getScriptProperties();
    if (twoBookActive_()) {
      return {ready:true,alreadyActive:true,
        quoteReadMode:'annual-two-tabs',year:seoulDay_(Date.now()).slice(0,4)};
    }
    assert_(annualLedgersEnabled_() &&
      props.getProperty('PRIVACY_SETUP')==='1','NOT_READY');
    const masterId=props.getProperty('SPREADSHEET_ID');
    assert_(masterId,'NOT_READY');
    const year=seoulDay_(Date.now()).slice(0,4);
    const entries=annualFileEntries_();
    const registry={};
    entries.filter(e=>e.values[4]==='READY').forEach(e=>{
      const kind=e.values[0],y=String(e.values[1]),id=e.values[2];
      assert_((kind==='quote' || kind==='order') &&
        /^\d{4}$/.test(y) && typeof id==='string' && id &&
        !registry[kind+':'+y] && id!==masterId,'NOT_READY');
      const book=SpreadsheetApp.openById(id);
      assert_(book.getName()==='나무팜_'+(kind==='quote'?'견적':'주문')+'_'+y,'NOT_READY');
      const names=kind==='quote' ? [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB] :
        Array.from({length:12},(_,i)=>String(i+1).padStart(2,'0')+'월');
      const headers=kind==='quote' ? RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS) : SALE_HEADERS;
      assert_(book.getSheets().length===names.length,'NOT_READY');
      names.forEach(name=>{
        const sheet=book.getSheetByName(name);
        assert_(sheet &&
          canonical_(sheet.getRange(1,1,1,headers.length).getValues()[0])===
          canonical_(headers),'NOT_READY');
      });
      registry[kind+':'+y]={id:id};
    });
    assert_(registry['quote:'+year] && registry['order:'+year] &&
      registry['quote:'+year].id!==registry['order:'+year].id,'NOT_READY');
    // The mode flag is written LAST; an interrupted setup remains in legacy mode.
    props.setProperty('TWO_BOOK_REGISTRY',JSON.stringify(registry));
    props.setProperty('LEGACY_SPREADSHEET_ID',masterId);
    props.setProperty('ORDER_WORKBOOK_ID',registry['order:'+year].id);
    props.setProperty('TWO_BOOK_MODE','two-books-v1');
    return {ready:true,year:year,quoteReadMode:'annual-two-tabs',
      quoteFileId:registry['quote:'+year].id,
      orderFileId:registry['order:'+year].id,
      existingBusinessRowsUntouched:true};
  });
}

function twoBookCopySheet_(sourceBook,targetBook,name,headers,optional) {
  const original=sourceBook.getSheetByName(name);
  if(!original && optional){
    const target=quoteEnsureHeaderSheet_(targetBook,name,headers);
    assert_(target.getLastRow()===1,'CONFLICT');return;
  }
  assert_(original,'NOT_READY');
  assert_(canonical_(original.getRange(1,1,1,headers.length).getValues()[0])===canonical_(headers),'NOT_READY');
  const originalRows=original.getLastRow()>1?
    original.getRange(2,1,original.getLastRow()-1,headers.length).getValues():[];
  let target=targetBook.getSheetByName(name);
  if(!target){
    target=quoteEnsureHeaderSheet_(targetBook,name,headers);
  }
  assert_(canonical_(target.getRange(1,1,1,headers.length).getValues()[0])===canonical_(headers),'NOT_READY');
  const targetRows=target.getLastRow()>1?
    target.getRange(2,1,target.getLastRow()-1,headers.length).getValues():[];
  // An interrupted or divergent copy fails closed. Never overwrite another
  // operator's edits, and do not infer that an incomplete copy is successful.
  if(targetRows.length)assert_(canonical_(targetRows)===canonical_(originalRows),'CONFLICT');
  else if(originalRows.length){
    if(target.getMaxRows()<originalRows.length+1)
      target.insertRowsAfter(target.getMaxRows(),originalRows.length+1-target.getMaxRows());
    target.getRange(2,1,originalRows.length,headers.length).setValues(originalRows);
    SpreadsheetApp.flush();
  }
  const actual=target.getLastRow()>1?
    target.getRange(2,1,target.getLastRow()-1,headers.length).getValues():[];
  assert_(canonical_(actual)===canonical_(originalRows),'NOT_READY');
}
function twoBookEmptySheet_(book,name,headers) {
  const sheet=quoteEnsureHeaderSheet_(book,name,headers);
  assert_(sheet.getLastRow()===1,'CONFLICT');
}
/** Called from the script editor after setupAnnualQuoteOrderLedgers().
 * The server URL and device connection tokens remain unchanged.
 */
function cutoverToTwoBooks() {
  return lock_(()=>{
    const props=PropertiesService.getScriptProperties();
    if(twoBookActive_())return {
      ready:true,quoteFileId:props.getProperty('SPREADSHEET_ID'),
      orderFileId:props.getProperty('ORDER_WORKBOOK_ID'),alreadyActive:true
    };
    assert_(annualLedgersEnabled_(),'NOT_READY');
    const oldId=props.getProperty('SPREADSHEET_ID');
    const source=SpreadsheetApp.openById(oldId);
    const year=seoulDay_(Date.now()).slice(0,4);
    const quoteBook=annualLedgerBook_('quote',year);
    const orderBook=annualLedgerBook_('order',year);
    assert_(oldId!==quoteBook.getId() && oldId!==orderBook.getId() &&
      quoteBook.getId()!==orderBook.getId(),'CONFLICT');
    // Keep indexes and reservations in the original backup. The new quote
    // workbooks will have exactly the two named business tabs.
    const oldIndex=quoteIndexEntries_(),cache={};
    quoteRepairTransitions_(oldIndex,cache);
    assert_(oldIndex.every(e=>e.values[QI.phase]==='COMMITTED'),'NOT_READY');
    const quoteYears=new Set();
    oldIndex.forEach(entry=>{
      const sourceRecord=quoteRecord_(entry,cache,false);
      const row=sourceRecord.row.slice();
      const quoteYear=entry.values[QI.day].slice(0,4);
      quoteYears.add(quoteYear);
      const tab=row[9]==='신규'?TWO_REQUEST_TAB:TWO_CONFIRMED_TAB;
      const target=quoteEnsureHeaderSheet_(annualLedgerBook_('quote',quoteYear),
        tab,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS));
      const existing=quoteFindRow_(target,row[0]);
      if(existing)assert_(canonical_(existing.row)===canonical_(row),'CONFLICT');
      else quoteWriteVerified_(target,target.getLastRow()+1,row);
      const saved=quoteFindRow_(target,row[0]);
      assert_(saved && canonical_(saved.row)===canonical_(row),'NOT_READY');
    });
    const legacy=saleRecords_();
    legacy.forEach(({row})=>{
      if(!row.some(v=>v!==''))return;
      const metadata=JSON.parse(row[15]);
      const when=metadata.paidAt || metadata.purchasedAt || metadata.completedAt ||
        Date.parse(String(row[3])) || Date.parse(String(row[14]));
      assert_(Number.isFinite(when) && when>0,'NOT_READY');
      const day=seoulDay_(when);
      const monthly=quoteEnsureHeaderSheet_(annualLedgerBook_('order',day.slice(0,4)),
        day.slice(5,7)+'월',SALE_HEADERS);
      const existing=namedRows_(monthly,20).filter(r=>r[0]===row[0]);
      assert_(existing.length<=1,'NOT_READY');
      if(existing.length)assert_(canonical_(existing[0])===canonical_(row),'CONFLICT');
      else {
        const dest=monthly.getLastRow()+1;
        if(dest>monthly.getMaxRows())monthly.insertRowsAfter(monthly.getMaxRows(),100);
        monthly.getRange(dest,1,1,20).setValues([row]);
        SpreadsheetApp.flush();
        assert_(namedRows_(monthly,20).some(r=>canonical_(r)===canonical_(row)),'NOT_READY');
      }
    });
    // House private sales receipts in the order workbook, not in the two
    // quote tabs. The original operating workbook remains an untouched backup.
    twoBookCopySheet_(source,orderBook,SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    twoBookCopySheet_(source,orderBook,SALE_HIDDEN_SHEET,SALE_HIDDEN_HEADERS,true);
    twoBookCopySheet_(source,orderBook,SALE_SOURCES,SALE_SOURCE_HEADERS,false);
    twoBookEmptySheet_(orderBook,SALE_SHEET,SALE_HEADERS);
    // Ensure all original requests and financial records are present in the
    // destination before switching the live pointers.
    const sourceIds=new Set(oldIndex.map(e=>e.values[QI.id]));
    const movedIds=new Set();
    quoteYears.forEach(y=>{
      const book=annualLedgerBook_('quote',y);
      [TWO_REQUEST_TAB,TWO_CONFIRMED_TAB].forEach(tab=>
        namedRows_(book.getSheetByName(tab),18)
          .filter(row=>row.some(v=>v!=='')).forEach(row=>movedIds.add(row[0])));
    });
    assert_([...sourceIds].every(id=>movedIds.has(id)),'NOT_READY');
    const saleIds=new Set(legacy.map(r=>r.row[0]));
    const entries=annualFileEntries_(),registry={};
    entries.forEach(e=>{registry[e.values[0]+':'+e.values[1]]={id:e.values[2]};});
    const migrated=new Set();
    entries.filter(e=>e.values[0]==='order' && e.values[4]==='READY')
      .forEach(e=>{
        const book=SpreadsheetApp.openById(e.values[2]);
        for(let month=1;month<=12;month++)
          namedRows_(book.getSheetByName(String(month).padStart(2,'0')+'월'),20)
            .filter(r=>r.some(v=>v!=='')).forEach(r=>migrated.add(r[0]));
      });
    assert_([...saleIds].every(id=>migrated.has(id)),'NOT_READY');
    // Set registry before the mode bit; only the final mode bit makes this
    // version live. No source sheet is cleared during this procedure.
    props.setProperty('TWO_BOOK_REGISTRY',JSON.stringify(registry));
    props.setProperty('LEGACY_SPREADSHEET_ID',oldId);
    props.setProperty('ORDER_WORKBOOK_ID',orderBook.getId());
    props.setProperty('SPREADSHEET_ID',quoteBook.getId());
    props.setProperty('TWO_BOOK_MODE','two-books-v1');
    return {ready:true,quoteFileId:quoteBook.getId(),
      orderFileId:orderBook.getId(),legacyPreserved:true,
      requests:oldIndex.length,sales:saleIds.size};
  });
}

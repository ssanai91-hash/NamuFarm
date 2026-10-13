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
    const quoteEntries=quoteIndexEntries_(),cache={};
    quoteRepairTransitions_(quoteEntries,cache);
    assert_(quoteEntries.every(e=>e.values[QI.phase]==='COMMITTED'),'NOT_READY');
    // First relocate all quote records to annual month tabs. A retry finds the
    // verified destination by requestId and never duplicates a customer row.
    quoteEntries.forEach(entry=>{
      const record=quoteRecord_(entry,cache,false),sourceRow=record.row.slice();
      const target=annualQuoteSheet_(entry.values[QI.day]);
      const dest=quoteFindRow_(target,entry.values[QI.id]);
      if(dest)assert_(canonical_(dest.row)===canonical_(sourceRow),'CONFLICT');
      else quoteWriteVerified_(target,target.getLastRow()+1,sourceRow);
      const saved=quoteFindRow_(target,entry.values[QI.id]);
      assert_(saved && canonical_(saved.row)===canonical_(sourceRow),'NOT_READY');
      entry.values[QI.book]=target.getParent().getId();
      entry.values[QI.sheet]=target.getSheetId();
      entry.values[QI.slot]=saved.index+2;
      entry.values[QI.copy]=false;
      entry.values[QI.targetBook]='';
      entry.values[QI.targetSheet]='';
      quoteSaveEntry_(entry);
    });
    // Copy historical sales into their original payment-month location.
    // Preserve the exact source row including revision, receipt, customer JSON
    // and calculated financial amounts.
    const oldSales=namedSheet_(SALE_SHEET,SALE_HEADERS,false);
    namedRows_(oldSales,20).filter(r=>r.some(v=>v!=='')).forEach(row=>{
      const metadata=JSON.parse(row[15]);
      assert_(!metadata.archived && metadata.orderNumber===row[0] || metadata.archived,'NOT_READY');
      const when=metadata.paidAt || metadata.purchasedAt ||
        Date.parse(String(row[3])) || Date.now();
      const day=seoulDay_(when);
      const monthly=quoteEnsureHeaderSheet_(annualLedgerBook_('order',day.slice(0,4)),
        day.slice(5,7)+'월',SALE_HEADERS);
      const existing=namedRows_(monthly,20).filter(r=>r[0]===row[0]);
      assert_(existing.length<=1,'NOT_READY');
      if(existing.length)assert_(canonical_(existing[0])===canonical_(row),'CONFLICT');
      else {
        if(monthly.getLastRow()+1>monthly.getMaxRows())
          monthly.insertRowsAfter(monthly.getMaxRows(),100);
        monthly.getRange(monthly.getLastRow()+1,1,1,20).setNumberFormat('@').setValues([row]);
        SpreadsheetApp.flush();
        assert_(namedRows_(monthly,20).some(r=>canonical_(r)===canonical_(row)),'NOT_READY');
      }
    });
    // The quote workbook owns quote indexes and routing metadata; the order
    // workbook owns financial receipts and work-list hide markers.
    [
      [ANNUAL_FILES,ANNUAL_FILE_HEADERS],
      [ANNUAL_QUOTE_INDEX,QUOTE_INDEX_HEADERS],
      [QUOTE_MONTHS,QUOTE_MONTH_HEADERS],
      [SALE_SOURCES,SALE_SOURCE_HEADERS]
    ].forEach(([name,headers])=>twoBookCopySheet_(source,quoteBook,name,headers,false));
    twoBookEmptySheet_(quoteBook,RFQ_SHEET,RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS));
    twoBookCopySheet_(source,orderBook,SALE_EVENT_SHEET,SALE_EVENT_HEADERS,false);
    twoBookCopySheet_(source,orderBook,SALE_HIDDEN_SHEET,SALE_HIDDEN_HEADERS,true);
    twoBookEmptySheet_(orderBook,SALE_SHEET,SALE_HEADERS);

    // Verify a second time from the destination before switching routing.
    assert_(namedRows_(quoteBook.getSheetByName(ANNUAL_QUOTE_INDEX),QUOTE_INDEX_HEADERS.length)
      .filter(r=>r.some(v=>v!=='')).length===quoteEntries.length,'NOT_READY');
    const legacyCount=namedRows_(oldSales,20).filter(r=>r.some(v=>v!=='')).length;
    const imported=namedRows_(orderBook.getSheetByName(SALE_SHEET),20).filter(r=>r.some(v=>v!=='')).length;
    assert_(imported===0 && legacyCount>=0,'NOT_READY');
    const oldSaleNumbers=new Set(namedRows_(oldSales,20).filter(r=>r.some(v=>v!=='')).map(r=>r[0]));
    const annualNumbers=new Set();
    annualFileEntries_().filter(e=>e.values[0]==='order'&&e.values[4]==='READY').forEach(e=>{
      const book=SpreadsheetApp.openById(e.values[2]);
      for(let n=1;n<=12;n++)namedRows_(book.getSheetByName(String(n).padStart(2,'0')+'월'),20)
        .filter(r=>r.some(v=>v!=='')).forEach(r=>annualNumbers.add(r[0]));
    });
    assert_([...oldSaleNumbers].every(id=>annualNumbers.has(id)),'NOT_READY');
    // Only change the live workbook pointers AFTER all data and metadata
    // checks have succeeded. Nothing is removed from the old workbook.
    props.setProperty('LEGACY_SPREADSHEET_ID',oldId);
    props.setProperty('ORDER_WORKBOOK_ID',orderBook.getId());
    props.setProperty('SPREADSHEET_ID',quoteBook.getId());
    props.setProperty('TWO_BOOK_MODE','two-books-v1');
    return {ready:true,quoteFileId:quoteBook.getId(),
      orderFileId:orderBook.getId(),legacyPreserved:true,
      requests:quoteEntries.length,sales:legacyCount};
  });
}

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

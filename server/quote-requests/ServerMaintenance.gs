/** Cheap hints, owner-only maintenance and intake throttling. No infrastructure change. */
let NAMUFARM_WEB_REQUEST=false;
function syncHead_(){
  const props=PropertiesService.getScriptProperties().getProperties(),result={syncHead:1};
  [['requestSequence','LAST_SEQUENCE'],['saleSequence','SALE_SEQUENCE'],['inquirySequence','INQUIRY_SEQUENCE']].forEach(pair=>{
    const value=Number(props[pair[1]]||0);assert_(Number.isSafeInteger(value) && value>=0,'NOT_READY');result[pair[0]]=value;
  });
  return result;
}
/** Called only for a new identity, inside the existing intake lock. Cache is best effort.
 * A retry ticket also exempts a write that failed after its limit was counted.
 */
function quoteIntakeLimit_(body,request){
  const cache=CacheService.getScriptCache(),secret=PropertiesService.getScriptProperties().getProperty('WEB_SECRET');
  const ticket='quote-retry:'+body.requestId,previous=cache.get(ticket);
  if(previous){assert_(previous===body.fingerprint,'CONFLICT');return;}
  const phone=String(request.customerPhone).replace(/\D/g,'');
  const keys=['quote-rate:'+body.rateKey,'quote-phone:'+hash_(secret+phone)];
  const counts=keys.map(key=>Number(cache.get(key)||0));
  assert_(counts.every(count=>Number.isFinite(count) && count<5),'RATE_LIMITED');
  keys.forEach((key,index)=>cache.put(key,String(counts[index]+1),3600));
  cache.put(ticket,body.fingerprint,3600);
}
/** Owner runs in December (or anytime in advance). No existing rows are moved. */
function prepareNextYearLedgers(){
  return lock_(()=>{
    assert_(!NAMUFARM_WEB_REQUEST,'UNAUTHORIZED');
    assert_(annualLedgersEnabled_(),'NOT_READY');
    const year=String(Number(seoulDay_(Date.now()).slice(0,4))+1);
    annualLedgerBook_('quote',year);annualLedgerBook_('order',year);
    if(PropertiesService.getScriptProperties().getProperty('INQUIRY_SETUP')==='1')inquirySheet_(year,true);
    return {ready:true,year:year};
  });
}
/** Owner may repair the current year after a missed December preparation. */
function prepareCurrentYearLedgers(){
  return lock_(()=>{
    assert_(!NAMUFARM_WEB_REQUEST && annualLedgersEnabled_(),'NOT_READY');
    const year=seoulDay_(Date.now()).slice(0,4);
    annualLedgerBook_('quote',year);annualLedgerBook_('order',year);
    if(PropertiesService.getScriptProperties().getProperty('INQUIRY_SETUP')==='1')inquirySheet_(year,true);
    return {ready:true,year:year};
  });
}
/** Redeployment alone does not replace installed triggers. Owner runs once. */
function configureHourlyCustomerCleanup(){
  assert_(!NAMUFARM_WEB_REQUEST,'UNAUTHORIZED');
  const trigger=ScriptApp.newTrigger('purgeExpiredCustomerData').timeBased().everyHours(1).create();
  ScriptApp.getProjectTriggers().filter(item=>item.getHandlerFunction()==='purgeExpiredCustomerData' &&
    item.getUniqueId()!==trigger.getUniqueId()).forEach(item=>ScriptApp.deleteTrigger(item));
  return {configured:true,hours:1};
}

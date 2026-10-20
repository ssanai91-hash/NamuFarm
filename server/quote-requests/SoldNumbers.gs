/** Daily disjoint blocks for local PC registration. Gaps are intentional; numbers never recycle. */
const SOLD_RESERVATION_SHEET='판매번호예약';
const SOLD_RESERVATION_HEADERS=['예약번호','기기해시','날짜','시작순번','끝순번','예약시각'];

function reserveSoldNumbers_(body){
  assert_(UUID_PATTERN.test(body.operationId) && /^[a-f0-9]{64}$/.test(body.deviceToken),'INVALID');
  assert_(typeof body.day==='string' && /^\d{8}$/.test(body.day) &&
    body.day===seoulDay_(Date.now()).replace(/-/g,''),'INVALID');
  assert_(Number.isSafeInteger(body.count) && body.count>=1 && body.count<=100,'INVALID');
  return lock_(()=>{
    const sheet=namedSheet_(SOLD_RESERVATION_SHEET,SOLD_RESERVATION_HEADERS,true);
    const rows=namedRows_(sheet,6),owner=hash_(body.deviceToken);
    const old=rows.find(r=>r[0]===body.operationId);
    if(old){
      assert_(old[1]===owner && old[2]===body.day && Number(old[4])-Number(old[3])+1===body.count,'CONFLICT');
      return {reserved:true,operationId:body.operationId,day:body.day,first:Number(old[3]),last:Number(old[4])};
    }
    const props=PropertiesService.getScriptProperties(),key='SOLD_SEQUENCE_'+body.day;
    let floor=Math.max(Number(props.getProperty(key))||0,
      rows.filter(r=>r[2]===body.day).reduce((n,r)=>Math.max(n,Number(r[4])||0),0));
    const pattern=new RegExp('^SOLD-'+body.day+'-(\\d+)$');
    saleRecords_().forEach(record=>{const m=String(record.row[0]).match(pattern);if(m)floor=Math.max(floor,Number(m[1]));});
    const first=floor+1,last=floor+body.count;
    assert_(Number.isSafeInteger(last),'NOT_READY');
    // Persist the high-water mark first. An interrupted sheet write only leaves a gap.
    props.setProperty(key,String(last));
    const row=[body.operationId,owner,body.day,first,last,new Date().toISOString()];
    if(sheet.getLastRow()+1>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),100);
    sheet.getRange(sheet.getLastRow()+1,1,1,6).setNumberFormat('@').setValues([row]);SpreadsheetApp.flush();
    const saved=namedRows_(sheet,6).find(r=>r[0]===body.operationId);
    assert_(saved && saved[1]===owner && Number(saved[3])===first && Number(saved[4])===last,'NOT_READY');
    return {reserved:true,operationId:body.operationId,day:body.day,first:first,last:last};
  });
}

function assertSoldNumberReserved_(number){
  if(!String(number).startsWith('SOLD-'))return; // Preserve existing PC/Android order identities.
  const match=String(number).match(/^SOLD-(\d{8})-(\d{4,})$/);
  assert_(match,'INVALID');
  const value=Number(match[2]);assert_(Number.isSafeInteger(value) && value>0,'INVALID');
  const rows=namedRows_(namedSheet_(SOLD_RESERVATION_SHEET,SOLD_RESERVATION_HEADERS,false),6);
  assert_(rows.some(r=>r[2]===match[1] && value>=Number(r[3]) && value<=Number(r[4])),'CONFLICT');
}

/** Daily disjoint blocks for local PC registration. Gaps are intentional; numbers never recycle. */
const SOLD_RESERVATION_SHEET='판매번호예약';
const SOLD_RESERVATION_HEADERS=['예약번호','기기해시','날짜','시작순번','끝순번','예약시각'];

function reserveSoldNumbers_(body){
  assert_(UUID_PATTERN.test(body.operationId) && /^[a-f0-9]{64}$/.test(body.deviceToken),'INVALID');
  assert_(typeof body.day==='string' && /^\d{8}$/.test(body.day) &&
    body.day===seoulDay_(Date.now()).replace(/-/g,''),'INVALID');
  assert_(Number.isSafeInteger(body.count) && body.count>=1 && body.count<=100,'INVALID');
  return lock_(()=>reserveSoldNumbersLocked_(body));
}

// Caller holds the script lock; shared with legacy display-number assignment.
function reserveSoldNumbersLocked_(body){
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
}


function assertSoldNumberReserved_(number){
  if(!String(number).startsWith('SOLD-'))return; // Preserve existing PC/Android order identities.
  const match=String(number).match(/^SOLD-(\d{8})-(\d{4,})$/);
  assert_(match,'INVALID');
  const value=Number(match[2]);assert_(Number.isSafeInteger(value) && value>0,'INVALID');
  const rows=namedRows_(namedSheet_(SOLD_RESERVATION_SHEET,SOLD_RESERVATION_HEADERS,false),6);
  assert_(rows.some(r=>r[2]===match[1] && value>=Number(r[3]) && value<=Number(r[4])),'CONFLICT');
}

const SOLD_DISPLAY_SHEET='판매번호연결';
const SOLD_DISPLAY_HEADERS=['기존주문번호','판매번호'];
function soldDisplayNumbers_(body){
  assert_(UUID_PATTERN.test(body.operationId) && /^[a-f0-9]{64}$/.test(body.deviceToken),'INVALID');
  assert_(Array.isArray(body.orders) && body.orders.length>=1 && body.orders.length<=20,'INVALID');
  const seen=new Set(),today=seoulDay_(Date.now()).replace(/-/g,'');
  body.orders.forEach(item=>{
    assert_(item && typeof item.orderNumber==='string' && item.orderNumber.startsWith('PC_') &&
      UUID_PATTERN.test(item.orderNumber.slice(3)) && !seen.has(item.orderNumber),'INVALID');
    assert_(typeof item.day==='string' && /^\d{8}$/.test(item.day) && item.day<=today,'INVALID');
    seen.add(item.orderNumber);
  });
  return lock_(()=>{
    const sheet=namedSheet_(SOLD_DISPLAY_SHEET,SOLD_DISPLAY_HEADERS,true);
    const rows=namedRows_(sheet,2),labels={};
    rows.forEach(r=>{assert_(!labels[r[0]] || labels[r[0]]===r[1],'CONFLICT');labels[r[0]]=r[1];});
    const days=[...new Set(body.orders.map(item=>item.day))];
    days.forEach(day=>{
      const group=body.orders.filter(item=>item.day===day);
      if(group.every(item=>labels[item.orderNumber]))return;
      const hash=hash_(body.operationId+':'+day);
      const id=hash.slice(0,8)+'-'+hash.slice(8,12)+'-4'+hash.slice(13,16)+'-8'+hash.slice(17,20)+'-'+hash.slice(20,32);
      const block=reserveSoldNumbersLocked_({deviceToken:body.deviceToken,operationId:id,day:day,count:group.length});
      group.forEach((item,i)=>{
        if(labels[item.orderNumber])return;
        const number='SOLD-'+day+'-'+String(block.first+i).padStart(4,'0');
        assert_(!Object.keys(labels).some(key=>labels[key]===number),'CONFLICT');
        if(sheet.getLastRow()+1>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),100);
        sheet.getRange(sheet.getLastRow()+1,1,1,2).setNumberFormat('@').setValues([[item.orderNumber,number]]);
        SpreadsheetApp.flush();
        const saved=namedRows_(sheet,2).find(r=>r[0]===item.orderNumber);
        assert_(saved && saved[1]===number,'NOT_READY');
        labels[item.orderNumber]=number;
      });
    });
    return {assigned:true,operationId:body.operationId,numbers:body.orders.map(item=>({orderNumber:item.orderNumber,soldNumber:labels[item.orderNumber]}))};
  });
}

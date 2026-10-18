/** Farm contacts are stored in the existing operations workbook, never in annual quote/order tabs. */
const FARM_DIRECTORY_SHEET = '농원관리';
const FARM_DIRECTORY_HEADERS = ['농원ID','농원명','주소','전화번호','메모','수정시각(한국)','작업ID'];

function farmDirectorySheet_() {
  const props=PropertiesService.getScriptProperties();
  const id=props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID');
  assert_(id,'NOT_READY');
  const book=SpreadsheetApp.openById(id);
  let sheet=book.getSheetByName(FARM_DIRECTORY_SHEET);
  if(!sheet) {
    sheet=book.insertSheet(FARM_DIRECTORY_SHEET);
    sheet.getRange(1,1,1,7).setValues([FARM_DIRECTORY_HEADERS]).setFontWeight('bold');
    sheet.setFrozenRows(1);
    sheet.getRange('D:D').setNumberFormat('@');
    sheet.hideColumns(7);
    SpreadsheetApp.flush();
  }
  assert_(JSON.stringify(sheet.getRange(1,1,1,7).getValues()[0])===
    JSON.stringify(FARM_DIRECTORY_HEADERS),'NOT_READY');
  return sheet;
}
function farmDirectoryFields_(raw) {
  assert_(raw && typeof raw==='object','INVALID');
  const fields={farmName:text_(raw.farmName,100,1),address:text_(raw.address,300),
    phone:text_(raw.phone,40),memo:text_(raw.memo,1000)};
  assert_(!/[\r\n]/.test(fields.farmName+fields.address+fields.phone),'INVALID');
  assert_(!fields.phone || /^[0-9+()\s-]+$/.test(fields.phone),'INVALID');
  return fields;
}
function farmDirectoryRecord_(row) {
  const farmId=Number(row[0]);
  assert_(Number.isInteger(farmId) && farmId>0 && farmId<=2147483647,'NOT_READY');
  const fields=farmDirectoryFields_({farmName:String(row[1]||''),address:String(row[2]||''),
    phone:String(row[3]||''),memo:String(row[4]||'')});
  const updatedAt=String(row[5]||'');
  return Object.assign({farmId:farmId,updatedAt:updatedAt,
    version:hash_(JSON.stringify([farmId,fields.farmName,fields.address,fields.phone,fields.memo,updatedAt]))},fields);
}
function farmDirectoryRows_(sheet) {
  const count=sheet.getLastRow()-1;
  assert_(count<=5000,'NOT_READY');
  const rows=count>0?sheet.getRange(2,1,count,7).getValues():[];
  const entries=rows.map((row,index)=>({row:row,index:index}))
    .filter(entry=>entry.row.some(value=>value!==''))
    .map(entry=>Object.assign(entry,{farm:farmDirectoryRecord_(entry.row)}));
  assert_(new Set(entries.map(entry=>entry.farm.farmId)).size===entries.length,'CONFLICT');
  assert_(new Set(entries.map(entry=>entry.farm.farmName)).size===entries.length,'CONFLICT');
  return entries;
}
function listFarmDirectory_() {
  return lock_(()=>{
    const entries=farmDirectoryRows_(farmDirectorySheet_());
    return {farms:entries.map(entry=>entry.farm).sort((a,b)=>a.farmName.localeCompare(b.farmName,'ko'))};
  });
}
function saveFarmDirectory_(body) {
  const fields=farmDirectoryFields_(body.farm);
  const farmId=body.farmId===0?null:body.farmId;
  assert_(farmId===null || (Number.isInteger(farmId) && farmId>0 && farmId<=2147483647),'INVALID');
  assert_(UUID_PATTERN.test(body.operationId),'INVALID');
  assert_(farmId===null || /^[a-f0-9]{64}$/.test(body.expectedVersion||''),'INVALID');
  return lock_(()=>{
    const sheet=farmDirectorySheet_(),entries=farmDirectoryRows_(sheet);
    const repeated=entries.find(entry=>entry.row[6]===body.operationId);
    if(repeated) {
      assert_((farmId===null || repeated.farm.farmId===farmId) &&
        ['farmName','address','phone','memo'].every(key=>repeated.farm[key]===fields[key]),'CONFLICT');
      return {saved:true,farm:repeated.farm,operationId:body.operationId};
    }
    const current=farmId===null?null:entries.find(entry=>entry.farm.farmId===farmId);
    assert_(farmId===null || (current && equal_(body.expectedVersion,current.farm.version)),'CONFLICT');
    assert_(!entries.some(entry=>entry.farm.farmName===fields.farmName && entry.farm.farmId!==farmId),'CONFLICT');
    let assignedId=farmId;
    if(assignedId===null) {
      const props=PropertiesService.getScriptProperties();
      const stored=Number(props.getProperty('FARM_DIRECTORY_LAST_ID')||0);
      assert_(Number.isInteger(stored) && stored>=0 && stored<2147483647,'NOT_READY');
      assignedId=Math.max(stored,...entries.map(entry=>entry.farm.farmId),0)+1;
      assert_(assignedId<=2147483647,'NOT_READY');
      props.setProperty('FARM_DIRECTORY_LAST_ID',String(assignedId));
    }
    assert_(current || sheet.getLastRow()<5001,'NOT_READY');
    const updatedAt=receiptTime_(new Date().toISOString());
    const row=[assignedId,fields.farmName,fields.address,fields.phone,fields.memo,updatedAt,body.operationId];
    const rowNumber=current?current.index+2:sheet.getLastRow()+1;
    if(rowNumber>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),rowNumber-sheet.getMaxRows());
    sheet.getRange(rowNumber,2,1,6).setNumberFormat('@');
    sheet.getRange(rowNumber,1,1,7).setValues([row.map((value,index)=>index===0?value:cell_(value))]);
    SpreadsheetApp.flush();
    const saved=sheet.getRange(rowNumber,1,1,7).getValues()[0];
    const farm=farmDirectoryRecord_(saved);
    assert_(farm.farmId===assignedId && saved[6]===body.operationId &&
      ['farmName','address','phone','memo'].every(key=>farm[key]===fields[key]),'NOT_READY');
    return {saved:true,farm:farm,operationId:body.operationId};
  });
}
function deleteFarmDirectory_(body) {
  assert_(Number.isInteger(body.farmId) && body.farmId>0,'INVALID');
  assert_(/^[a-f0-9]{64}$/.test(body.expectedVersion||''),'INVALID');
  return lock_(()=>{
    const sheet=farmDirectorySheet_(),entries=farmDirectoryRows_(sheet);
    const current=entries.find(entry=>entry.farm.farmId===body.farmId);
    if(!current)return {deleted:true,farmId:body.farmId};
    assert_(equal_(body.expectedVersion,current.farm.version),'CONFLICT');
    // A nursery with live inventory must not leave orphaned stock references.
    assert_(!inventoryDirectoryRows_(inventoryDirectorySheet_())
      .some(e=>e.stock.farmId===body.farmId&&e.stock.quantity>0),'CONFLICT');
    const props=PropertiesService.getScriptProperties();
    const floor=Math.max(Number(props.getProperty('FARM_DIRECTORY_LAST_ID')||0),...entries.map(entry=>entry.farm.farmId));
    assert_(Number.isInteger(floor) && floor>0 && floor<=2147483647,'NOT_READY');
    props.setProperty('FARM_DIRECTORY_LAST_ID',String(floor));
    // Delete only this contact row. Inventory, quotations and orders are independent.
    sheet.deleteRow(current.index+2);
    SpreadsheetApp.flush();
    assert_(!farmDirectoryRows_(sheet).some(entry=>entry.farm.farmId===body.farmId),'NOT_READY');
    return {deleted:true,farmId:body.farmId};
  });
}

/** Nursery stock in the operations workbook, separate from all quote and order tabs. */
const INVENTORY_DIRECTORY_SHEET='농원별묘목';
const INVENTORY_DIRECTORY_HEADERS=['농원ID','묘목ID','등급','연생','규격','재배형태','수량','도매단가','판매단가','수정시각(한국)','작업ID'];

function inventoryDirectorySheet_() {
  const props=PropertiesService.getScriptProperties();
  const id=props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID');
  assert_(id,'NOT_READY');
  const book=SpreadsheetApp.openById(id);
  let sheet=book.getSheetByName(INVENTORY_DIRECTORY_SHEET);
  if(!sheet){
    sheet=book.insertSheet(INVENTORY_DIRECTORY_SHEET);
    sheet.getRange(1,1,1,11).setValues([INVENTORY_DIRECTORY_HEADERS]).setFontWeight('bold');
    sheet.setFrozenRows(1);sheet.hideColumns(11);
  }
  assert_(canonical_(sheet.getRange(1,1,1,11).getValues()[0])===canonical_(INVENTORY_DIRECTORY_HEADERS),'NOT_READY');
  return sheet;
}
function inventoryDirectoryFields_(raw){
  assert_(raw&&typeof raw==='object','INVALID');
  const f={farmId:raw.farmId,speciesId:raw.speciesId,
    quality:text_(raw.quality,100),age:text_(raw.age,100),
    size:text_(raw.size,100),plantType:text_(raw.plantType,100),
    quantity:raw.quantity,wholesalePrice:raw.wholesalePrice,retailPrice:raw.retailPrice};
  [f.farmId,f.speciesId].forEach(v=>assert_(Number.isInteger(v)&&v>0&&v<=2147483647,'INVALID'));
  [f.quantity,f.wholesalePrice,f.retailPrice].forEach(v=>assert_(Number.isInteger(v)&&v>=0&&v<=2147483647,'INVALID'));
  assert_(!/[\r\n]/.test(f.quality+f.age+f.size+f.plantType),'INVALID');
  return f;
}
function inventoryDirectoryKey_(f){
  return JSON.stringify([f.farmId,f.speciesId,f.quality,f.age,f.size,f.plantType]);
}
function inventoryDirectoryRecord_(row){
  const f=inventoryDirectoryFields_({
    farmId:Number(row[0]),speciesId:Number(row[1]),quality:String(row[2]||''),
    age:String(row[3]||''),size:String(row[4]||''),plantType:String(row[5]||''),
    quantity:Number(row[6]),wholesalePrice:Number(row[7]),retailPrice:Number(row[8])});
  const updatedAt=String(row[9]||'');
  return Object.assign(f,{updatedAt:updatedAt,version:hash_(JSON.stringify([
    f.farmId,f.speciesId,f.quality,f.age,f.size,f.plantType,
    f.quantity,f.wholesalePrice,f.retailPrice,updatedAt]))});
}
function inventoryDirectoryRows_(sheet){
  const count=sheet.getLastRow()-1;
  assert_(count>=0&&count<=15000,'NOT_READY');
  const rows=count?sheet.getRange(2,1,count,11).getValues():[];
  const entries=rows.map((row,index)=>({row,index})).filter(e=>e.row.some(v=>v!==''))
    .map(e=>Object.assign(e,{stock:inventoryDirectoryRecord_(e.row)}));
  assert_(new Set(entries.map(e=>inventoryDirectoryKey_(e.stock))).size===entries.length,'CONFLICT');
  return entries;
}
function listInventoryDirectory_(){
  return lock_(()=>({inventoryVersioning:1,inventory:inventoryDirectoryRows_(inventoryDirectorySheet_()).map(e=>e.stock)}));
}
function saveInventoryDirectory_(body){
  const stock=inventoryDirectoryFields_(body.inventory);
  assert_(UUID_PATTERN.test(body.operationId),'INVALID');
  return lock_(()=>{
    const sheet=inventoryDirectorySheet_(),entries=inventoryDirectoryRows_(sheet);
    const repeated=entries.find(e=>e.row[10]===body.operationId);
    if(repeated){
      assert_(inventoryDirectoryKey_(repeated.stock)===inventoryDirectoryKey_(stock)&&
        ['quantity','wholesalePrice','retailPrice'].every(k=>repeated.stock[k]===stock[k]),'CONFLICT');
      return {saved:true,inventoryRecord:repeated.stock,operationId:body.operationId};
    }
    assert_(farmDirectoryRows_(farmDirectorySheet_()).some(e=>e.farm.farmId===stock.farmId),'INVALID');
    assert_(speciesDirectoryRows_(speciesDirectorySheet_()).some(e=>
      e.species.speciesId===stock.speciesId&&e.species.isActive===1),'INVALID');
    const key=inventoryDirectoryKey_(stock);
    const previous=entries.find(e=>inventoryDirectoryKey_(e.stock)===key);
    // New clients use optimistic concurrency; older Android clients remain compatible.
    if(Object.prototype.hasOwnProperty.call(body,'expectedVersion')) {
      assert_(typeof body.expectedVersion==='string' &&
        (previous ? equal_(body.expectedVersion,previous.stock.version) : body.expectedVersion===''),'CONFLICT');
    }
    assert_(previous||sheet.getLastRow()<15001,'NOT_READY');
    const row=[stock.farmId,stock.speciesId,stock.quality,stock.age,stock.size,stock.plantType,
      stock.quantity,stock.wholesalePrice,stock.retailPrice,receiptTime_(new Date().toISOString()),body.operationId];
    const n=previous?previous.index+2:sheet.getLastRow()+1;
    if(n>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),n-sheet.getMaxRows());
    sheet.getRange(n,3,1,4).setNumberFormat('@');
    sheet.getRange(n,1,1,11).setValues([row.map((v,i)=>[0,1,6,7,8].includes(i)?v:cell_(v))]);
    SpreadsheetApp.flush();
    const saved=inventoryDirectoryRecord_(sheet.getRange(n,1,1,11).getValues()[0]);
    assert_(inventoryDirectoryKey_(saved)===key&&
      ['quantity','wholesalePrice','retailPrice'].every(k=>saved[k]===stock[k]),'NOT_READY');
    return {saved:true,inventoryRecord:saved,operationId:body.operationId};
  });
}

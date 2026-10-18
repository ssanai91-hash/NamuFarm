/** Species master is independent of annual quote/order workbooks.
 * Seeded 186 source rows preserve original species IDs and alternative names.
 */
const SPECIES_DIRECTORY_SHEET='묘목관리';
const SPECIES_DIRECTORY_HEADERS=['묘목ID','묘목명','초성','농원사용명','분류','메모','사용여부','수정시각(한국)','작업ID'];

function speciesDirectorySheet_() {
  const props=PropertiesService.getScriptProperties();
  const book=SpreadsheetApp.openById(props.getProperty('LEGACY_SPREADSHEET_ID')||props.getProperty('SPREADSHEET_ID'));
  let sheet=book.getSheetByName(SPECIES_DIRECTORY_SHEET);
  if(!sheet) {
    sheet=book.insertSheet(SPECIES_DIRECTORY_SHEET);
    sheet.getRange(1,1,1,9).setValues([SPECIES_DIRECTORY_HEADERS]).setFontWeight('bold');
    sheet.setFrozenRows(1);
    sheet.hideColumns(9);
  }
  assert_(canonical_(sheet.getRange(1,1,1,9).getValues()[0])===canonical_(SPECIES_DIRECTORY_HEADERS),'NOT_READY');
  return sheet;
}
function speciesDirectoryFields_(raw) {
  assert_(raw && typeof raw==='object','INVALID');
  const fields={speciesName:text_(raw.speciesName,100,1),chosung:text_(raw.chosung,20),
    farmName:text_(raw.farmName,100),category:text_(raw.category,100),
    memo:text_(raw.memo,1000),isActive:raw.isActive};
  assert_(Number.isInteger(fields.isActive) && [0,1].includes(fields.isActive),'INVALID');
  assert_(!/[\r\n]/.test(fields.speciesName+fields.chosung+fields.farmName+fields.category),'INVALID');
  return fields;
}
function speciesDirectoryRecord_(row) {
  const speciesId=Number(row[0]);
  assert_(Number.isInteger(speciesId) && speciesId>0 && speciesId<=2147483647,'NOT_READY');
  const fields=speciesDirectoryFields_({speciesName:String(row[1]||''),
    chosung:String(row[2]||''),farmName:String(row[3]||''),category:String(row[4]||''),
    memo:String(row[5]||''),isActive:Number(row[6])});
  const updatedAt=String(row[7]||'');
  return Object.assign({speciesId,updatedAt,
    version:hash_(JSON.stringify([speciesId,fields.speciesName,fields.chosung,
      fields.farmName,fields.category,fields.memo,fields.isActive,updatedAt]))},fields);
}
function speciesDirectoryRows_(sheet) {
  const count=sheet.getLastRow()-1;
  assert_(count>=0 && count<=5000,'NOT_READY');
  const rows=count?sheet.getRange(2,1,count,9).getValues():[];
  const records=rows.map((row,index)=>({row,index}))
    .filter(entry=>entry.row.some(value=>value!==''))
    .map(entry=>Object.assign(entry,{species:speciesDirectoryRecord_(entry.row)}));
  assert_(new Set(records.map(r=>r.species.speciesId)).size===records.length &&
    new Set(records.map(r=>r.species.speciesName)).size===records.length,'CONFLICT');
  return records;
}
function listSpeciesDirectory_(){
  return lock_(()=>({species:speciesDirectoryRows_(speciesDirectorySheet_())
    .map(e=>e.species).sort((a,b)=>a.speciesName.localeCompare(b.speciesName,'ko'))}));
}
function saveSpeciesDirectory_(body) {
  const fields=speciesDirectoryFields_(body.species);
  const id=body.speciesId===0?null:body.speciesId;
  assert_(id===null || Number.isInteger(id)&&id>0&&id<=2147483647,'INVALID');
  assert_(UUID_PATTERN.test(body.operationId),'INVALID');
  assert_(id===null || /^[a-f0-9]{64}$/.test(body.expectedVersion||''),'INVALID');
  return lock_(()=>{
    const sheet=speciesDirectorySheet_(),all=speciesDirectoryRows_(sheet);
    const repeat=all.find(e=>e.row[8]===body.operationId);
    if(repeat) {
      assert_((id===null||repeat.species.speciesId===id) &&
        ['speciesName','chosung','farmName','category','memo','isActive']
          .every(k=>repeat.species[k]===fields[k]),'CONFLICT');
      return {saved:true,speciesRecord:repeat.species,operationId:body.operationId};
    }
    const current=id===null?null:all.find(e=>e.species.speciesId===id);
    assert_(id===null || current && equal_(body.expectedVersion,current.species.version),'CONFLICT');
    assert_(!all.some(e=>e.species.speciesName===fields.speciesName && e.species.speciesId!==id),'CONFLICT');
    const speciesId=id===null?Math.max(0,...all.map(e=>e.species.speciesId))+1:id;
    assert_(speciesId>0&&speciesId<=2147483647&& (current||sheet.getLastRow()<5001),'NOT_READY');
    const time=receiptTime_(new Date().toISOString());
    const row=[speciesId,fields.speciesName,fields.chosung,fields.farmName,
      fields.category,fields.memo,fields.isActive,time,body.operationId];
    const rowNumber=current?current.index+2:sheet.getLastRow()+1;
    if(rowNumber>sheet.getMaxRows())sheet.insertRowsAfter(sheet.getMaxRows(),rowNumber-sheet.getMaxRows());
    sheet.getRange(rowNumber,2,1,5).setNumberFormat('@');
    sheet.getRange(rowNumber,1,1,9).setValues([row.map((v,i)=>i===0||i===6?v:cell_(v))]);
    SpreadsheetApp.flush();
    const saved=speciesDirectoryRecord_(sheet.getRange(rowNumber,1,1,9).getValues()[0]);
    assert_(saved.speciesId===speciesId && Object.keys(fields).every(k=>saved[k]===fields[k]),'NOT_READY');
    return {saved:true,speciesRecord:saved,operationId:body.operationId};
  });
}
function deleteSpeciesDirectory_(body) {
  assert_(Number.isInteger(body.speciesId)&&body.speciesId>0,'INVALID');
  assert_(/^[a-f0-9]{64}$/.test(body.expectedVersion||''),'INVALID');
  return lock_(()=>{
    const sheet=speciesDirectorySheet_(),all=speciesDirectoryRows_(sheet);
    const current=all.find(e=>e.species.speciesId===body.speciesId);
    if(!current)return {deleted:true,speciesId:body.speciesId};
    assert_(equal_(body.expectedVersion,current.species.version),'CONFLICT');
    const rowNumber=current.index+2;
    sheet.getRange(rowNumber,7,1,1).setValue(0);
    sheet.getRange(rowNumber,8,1,1).setValue(cell_(receiptTime_(new Date().toISOString())));
    SpreadsheetApp.flush();
    assert_(speciesDirectoryRecord_(sheet.getRange(rowNumber,1,1,9).getValues()[0]).isActive===0,'NOT_READY');
    return {deleted:true,speciesId:body.speciesId};
  });
}

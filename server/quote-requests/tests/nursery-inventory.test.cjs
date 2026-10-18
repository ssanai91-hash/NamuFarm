const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');

function setup(){
  const f=fixture(),c=f.context;
  for(const name of ['FarmManagement.gs','SpeciesManagement.gs','InventoryManagement.gs'])
    vm.runInContext(fs.readFileSync(path.join(__dirname,'..',name),'utf8'),c);
  const farm=c.saveFarmDirectory_({farmId:0,operationId:crypto.randomUUID(),farm:{farmName:'이원농원',address:'',phone:'',memo:''}}).farm;
  const species=c.saveSpeciesDirectory_({speciesId:0,operationId:crypto.randomUUID(),species:{speciesName:'능소화',chosung:'ㄴ',farmName:'',category:'',memo:'',isActive:1}}).speciesRecord;
  return {c,stock:{farmId:farm.farmId,speciesId:species.speciesId,quality:'상묘',age:'1년생',size:'1m',plantType:'실생',quantity:3,wholesalePrice:1000,retailPrice:2000}};
}
test('inventory versions protect two-device updates while lost replies remain retryable',()=>{
  const {c,stock}=setup();
  const create={operationId:crypto.randomUUID(),expectedVersion:'',inventory:stock};
  const saved=c.saveInventoryDirectory_(create).inventoryRecord;
  assert.match(saved.version,/^[a-f0-9]{64}$/);
  assert.equal(c.listInventoryDirectory_().inventoryVersioning,1);
  assert.equal(c.saveInventoryDirectory_(create).inventoryRecord.version,saved.version);
  c.saveInventoryDirectory_({operationId:crypto.randomUUID(),expectedVersion:saved.version,inventory:{...stock,quantity:7}});
  assert.throws(()=>c.saveInventoryDirectory_({operationId:crypto.randomUUID(),expectedVersion:saved.version,inventory:{...stock,quantity:1}}));
  assert.equal(c.listInventoryDirectory_().inventory[0].quantity,7);
});
test('legacy Android inventory writes remain compatible',()=>{
  const {c,stock}=setup();
  c.saveInventoryDirectory_({operationId:crypto.randomUUID(),inventory:stock});
  c.saveInventoryDirectory_({operationId:crypto.randomUUID(),inventory:{...stock,quantity:0}});
  assert.equal(c.listInventoryDirectory_().inventory[0].quantity,0);
});

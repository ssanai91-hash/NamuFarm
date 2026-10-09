const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const {fixture}=require('./fixture.cjs');

test('one private connection file pairs independent PC and phone tokens; retries keep push registration',()=>{
  const f=fixture(),key='c'.repeat(64),pc='d'.repeat(64),phone='e'.repeat(64);
  f.properties.APP_CONNECTION_KEY=key;
  assert.equal(f.post({action:'connect_app',connectionKey:'bad',deviceToken:pc}).code,'UNAUTHORIZED');
  for(const token of [pc,phone])assert.equal(f.post({action:'connect_app',connectionKey:key,deviceToken:token}).paired,true);
  assert.equal(f.post({action:'register_push',deviceToken:phone,fcmToken:'phone-push-token-value-12345'}).registered,true);
  assert.equal(f.post({action:'connect_app',connectionKey:key,deviceToken:phone}).paired,true);
  const devices=Object.values(JSON.parse(f.properties.DEVICES));
  assert.equal(devices.length,2);assert.ok(devices.some(d=>d.fcmToken==='phone-push-token-value-12345'));
  assert.equal(f.post({action:'snapshot_requests',afterSequence:0}).code,'UNAUTHORIZED');
  assert.equal(f.post({action:'snapshot_requests',afterSequence:0,deviceToken:pc}).total,0);
  assert.ok(!JSON.stringify(f.context.doGet()).includes(key));
});

test('owner helper creates a reusable private file without resetting existing connections',()=>{
  const f=fixture(),files=[];
  f.context.ScriptApp.getService=()=>({getUrl:()=> 'https://script.google.com/macros/s/AKtest/exec'});
  f.context.Utilities.newBlob=(value)=>value;
  f.context.Drive.Files.create=(resource,blob)=>{files.push({resource,value:JSON.parse(blob)});return{id:'private-file'};};
  assert.equal(f.context.createAppConnectionFile(),'https://drive.google.com/file/d/private-file/view');
  f.context.createAppConnectionFile();
  assert.equal(files[0].value.connectionKey,files[1].value.connectionKey);
  assert.equal(files[0].value.format,'namufarm-connection');
  assert.equal(files[0].resource.mimeType,'application/json');
});

for(const monthly of [false,true])test(`snapshot paging is consistent and detects changes (monthly=${monthly})`,()=>{
  const f=fixture({monthly});
  for(let n=0;n<101;n++)f.seedEncrypted({...f.request,requestId:crypto.randomUUID()});
  const first=f.context.snapshotRequests_({afterSequence:0});
  assert.equal(first.requests.length,100);assert.equal(first.total,101);assert.equal(first.hasMore,true);
  const next={afterSequence:first.requests.at(-1).sequence,snapshotId:first.snapshotId};
  const last=f.context.snapshotRequests_(next);
  assert.equal(last.snapshotId,first.snapshotId);assert.equal(last.requests.length,1);assert.equal(last.hasMore,false);
  f.seedEncrypted({...f.request,requestId:crypto.randomUUID()});
  assert.throws(()=>f.context.snapshotRequests_(next),e=>e.code==='BUSY');
});

test('cleared Sheet produces an authenticated empty snapshot with a retained watermark',()=>{
  const f=fixture();f.submit();const before=f.context.snapshotRequests_({afterSequence:0});
  f.cells.splice(1);
  const after=f.context.snapshotRequests_({afterSequence:0});
  assert.equal(after.total,0);assert.equal(after.hasMore,false);assert.equal(after.watermark,before.watermark);
  assert.notEqual(after.snapshotId,before.snapshotId);
});

const test=require('node:test'),assert=require('node:assert/strict');
const {fixture}=require('./fixture.cjs');
const KEY='c'.repeat(64), TOKEN='d'.repeat(64);
function setup(){const f=fixture({configure:false});f.properties.APP_CONNECTION_KEY=KEY;return f;}
function fullRegistry(f){const d={};for(let n=0;n<10;n++)d[f.context.hash_(n.toString(16).padStart(64,'0'))]={label:'legacy',fcmToken:'',createdAt:'2026-01-01T00:00:00.000Z'};f.properties.DEVICES=JSON.stringify(d);}
test('repeated shared-key imports bypass a full registry without changing properties or sheets',()=>{
 const f=setup();fullRegistry(f);const before=JSON.stringify(f.properties),rows=JSON.stringify(f.requestRows());
 for(let n=0;n<20;n++)assert.deepEqual(f.post({action:'connect_app',authMode:'shared-key-v1',connectionKey:KEY,
  deviceToken:n.toString(16).padStart(64,'0')}),{ok:true,paired:true,authMode:'shared-key-v1'});
 assert.equal(JSON.stringify(f.properties),before);assert.equal(JSON.stringify(f.requestRows()),rows);
});
test('common-key authorization does not require a registered device, and missing/wrong keys cannot bypass auth',()=>{
 const f=setup();
 assert.equal(f.post({action:'pair_device',invite:f.context.createDeviceInvite(),deviceToken:TOKEN}).ok,true);
 for(const action of ['list_requests','list_sales','email_profile','sms_profile']){
  assert.equal(f.post({action,connectionKey:KEY,afterSequence:0}).ok,true);
  for(const key of ['',null,'wrong','a'.repeat(64)])
   assert.equal(f.post({action,connectionKey:key,deviceToken:TOKEN,afterSequence:0}).code,'UNAUTHORIZED');
 }
 for(const action of ['save_sale','save_quote_pricing','send_quote_sms','send_quote_email','update_request_status','close_request','hide_sale','sales_report','register_push'])
  assert.equal(f.post({action,connectionKey:'wrong',deviceToken:TOKEN}).code,'UNAUTHORIZED');
 assert.equal(f.mail.length,0);assert.equal(f.sms.length,0);
 assert.equal(f.post({action:'list_requests',deviceToken:TOKEN,afterSequence:0}).ok,true);
});
test('legacy connect is idempotent and reports a distinct device limit',()=>{
 const f=setup();fullRegistry(f);
 assert.equal(f.post({action:'connect_app',connectionKey:KEY,deviceToken:TOKEN}).code,'DEVICE_LIMIT');
 const known='0'.repeat(64);
 assert.equal(f.post({action:'connect_app',connectionKey:KEY,deviceToken:known}).paired,true);
 assert.equal(f.post({action:'pair_device',invite:f.context.createDeviceInvite(),deviceToken:TOKEN}).code,'DEVICE_LIMIT');
 assert.equal(f.post({action:'connect_app',authMode:'unsupported',connectionKey:KEY}).code,'UPGRADE_REQUIRED');
});
test('new push registration remains separate and bounded without limiting login',()=>{
 const f=setup();fullRegistry(f);const before=f.properties.DEVICES;
 for(let i=0;i<40;i++){
  f.setTime(new Date(Date.UTC(2026,0,1,0,0,i)).toISOString());
  assert.equal(f.post({action:'register_push',connectionKey:KEY,fcmToken:'push-token-'.repeat(3)+i}).registered,true);
 }
 assert.equal(Object.keys(JSON.parse(f.properties.PUSH_SUBSCRIPTIONS)).length,32);
 assert.equal(f.properties.DEVICES,before);
 assert.equal(f.post({action:'connect_app',authMode:'shared-key-v1',connectionKey:KEY}).paired,true);
 const count=Object.keys(JSON.parse(f.properties.PUSH_SUBSCRIPTIONS)).length;
 assert.equal(f.post({action:'register_push',connectionKey:KEY,fcmToken:'push-token-'.repeat(3)+39}).registered,true);
 assert.equal(Object.keys(JSON.parse(f.properties.PUSH_SUBSCRIPTIONS)).length,count);
});
test('long push subscriptions stay within the property size budget and retain the new token',()=>{
 const f=setup();
 for(let n=0;n<5;n++){
  const token='x'.repeat(2999)+n;
  assert.equal(f.post({action:'register_push',connectionKey:KEY,fcmToken:token}).registered,true);
  assert.ok(Buffer.byteLength(f.properties.PUSH_SUBSCRIPTIONS,'utf8')<=8000);
  assert.ok(Object.values(JSON.parse(f.properties.PUSH_SUBSCRIPTIONS)).some(v=>v.fcmToken===token));
 }
});
test('shared push subscriptions receive notifications without duplicate legacy deliveries',()=>{
 const f=setup(),push='shared-push-token-'.repeat(3);
 f.properties.FIREBASE_PROJECT_ID='test-project';
 f.properties.DEVICES=JSON.stringify({legacy:{fcmToken:push}});
 assert.equal(f.post({action:'register_push',connectionKey:KEY,fcmToken:push}).registered,true);
 assert.equal(f.submit().ok,true);
 f.context.dispatchQuoteNotifications();
 assert.equal(f.sent.length,1);
 assert.equal(f.sent[0].message.token,push);
 assert.deepEqual(Object.keys(f.sent[0].message.data).sort(),['requestId','type']);
});
test('invalid push tokens are pruned in both registries without revoking authorization',()=>{
 const f=setup(),push='expired-push-token-'.repeat(3);
 f.properties.FIREBASE_PROJECT_ID='test-project';
 f.properties.DEVICES=JSON.stringify({legacy:{fcmToken:push}});
 assert.equal(f.post({action:'register_push',connectionKey:KEY,fcmToken:push}).registered,true);
 f.context.UrlFetchApp.fetch=()=>({getResponseCode:()=>404,getContentText:()=>JSON.stringify({error:{details:[{errorCode:'UNREGISTERED'}]}})});
 assert.equal(f.submit().ok,true);f.context.dispatchQuoteNotifications();
 assert.equal(JSON.parse(f.properties.DEVICES).legacy.fcmToken,'');
 assert.equal(Object.keys(JSON.parse(f.properties.PUSH_SUBSCRIPTIONS)).length,0);
 assert.equal(f.post({action:'connect_app',authMode:'shared-key-v1',connectionKey:KEY}).paired,true);
});

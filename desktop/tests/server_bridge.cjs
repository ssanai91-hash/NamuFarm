// Runs the actual Apps Script source in the existing in-memory Sheet fixture.
// Synthetic key/customer data only; never accesses an account or a production Sheet.
const {fixture, pair}=require('../../server/quote-requests/tests/fixture.cjs');
const readline=require('node:readline'),crypto=require('node:crypto');
const f=fixture();
function backup() {
 const salt=crypto.randomBytes(16),iv=crypto.randomBytes(12);
 const key=crypto.pbkdf2Sync('test-password-12345',salt,1300000,32,'sha1');
 const aes=crypto.createCipheriv('aes-256-gcm',key,iv);aes.setAAD(Buffer.from('namufarm-seller-backup-v1'));
 const plain=JSON.stringify({privateKey:pair.privateKey.export({type:'pkcs8',format:'der'}).toString('base64'),publicKey:f.publicKey});
 const ciphertext=Buffer.concat([aes.update(plain),aes.final(),aes.getAuthTag()]);
 return JSON.stringify({version:1,kdf:'PBKDF2-HMAC-SHA1',iterations:1300000,salt:salt.toString('base64'),iv:iv.toString('base64'),ciphertext:ciphertext.toString('base64')});
}
function seed() {
 const now=Date.now()-10000, eventId=crypto.randomUUID();
 const order={orderId:null,orderNumber:'ORDER_TEST',orderDate:'2026-10-05 10:00',status:'구매확정',revision:1,totalQty:2,totalPrice:2100,isPrepaid:true,shippingFee:100,purchasedAt:now,paidAt:0,deliveredAt:0,completedAt:0,updatedAt:now,costTotal:1000,expenseTotal:100,costConfirmed:false,profitMode:'resale',commissionBps:3000,taxAmount:210,itemSummary:'소나무|상묘|1년생|1.0m|실생|2|1000|농원|500',customerName:'김나무',customerPhone:'01012345678',customerEmail:'buyer@example.com',receiverName:'수령인',zipNo:'29000',roadAddress:'충북 옥천군 이원면',detailAddress:'상세주소',note:'배송전 연락',sourceRequestId:'',sourceQuoteNumber:''};
 const meta={...order,items:[{species:'소나무',quantity:2,unitPrice:1000}]};
 for(const k of ['orderId','itemSummary','customerName','customerPhone','customerEmail','receiverName','zipNo','roadAddress','detailAddress','note','sourceRequestId','sourceQuoteNumber'])delete meta[k];
 const encrypted=f.seal({order},'order',eventId);
 f.context.saveSale_({eventId,metadata:meta,encryptedPersonal:encrypted,fingerprint:f.context.saleDigest_(meta,encrypted),sourceRequestId:''});
 return {order};
}
readline.createInterface({input:process.stdin}).on('line',line=>{
 try {
  const request=JSON.parse(line);let result;
  if(request.control==='init')result={backup:backup(),invite:f.context.createDeviceInvite()};
  else if(request.control==='invite')result={invite:f.context.createDeviceInvite()};
  else if(request.control==='info')result=f.context.doGet();
  else if(request.control==='seed')result=seed();
  else if(request.control==='clear_key'){delete f.properties.SELLER_PUBLIC_KEY;result={ok:true};}
  else if(request.control==='submit_plain'){const req={...f.request,requestId:crypto.randomUUID()};const resultId=req.requestId;const receipt=f.post({action:'submit_request',protocolVersion:5,request:req,requestId:resultId,rateKey:'b'.repeat(64),fingerprint:crypto.createHash('sha256').update(JSON.stringify(req)).digest('hex'),webSecret:f.properties.WEB_SECRET});result={...receipt,requestId:resultId};}
  else if(request.control==='submit'){f.submit();result={requestId:f.request.requestId};}
  else if(request.control==='submit_new'){const req={...f.request,requestId:crypto.randomUUID()};f.submit(req);result={requestId:req.requestId};}
  else if(request.control==='fail_write'){f.failWrite();result={ok:true};}
  else if(request.control==='fail_receipt'){f.failReceipt();result={ok:true};}
  else if(request.control==='stored') {
   const row=f.context.listSales_({afterSequence:0}).sales[0];
   result={order:(row.personalData || f.open(row.encryptedPersonal,'order',row.eventId)).order,cells:f.book.get('판매기록').cells,requests:f.cells};
  } else result=f.post(request);
  process.stdout.write(JSON.stringify(result)+'\n');
 } catch (_) {process.stdout.write(JSON.stringify({ok:false,code:'TEST_FAILURE'})+'\n');}
});

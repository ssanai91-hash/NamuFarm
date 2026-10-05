import {test} from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync, createHash} from 'node:crypto';
import {configuredSellerKey, validatedQuoteService} from '../lib/quote-service.ts';
import {sealCustomer} from '../lib/privacy-crypto.ts';

const pair = generateKeyPairSync('rsa', {modulusLength:3072});
const publicKey = pair.publicKey.export({type:'spki',format:'der'}).toString('base64');
const key = {publicKey, keyId:createHash('sha256').update(publicKey).digest('hex')};

test('an operator-pinned key produces the existing encrypted Sheet envelope', async () => {
  const pinned = await configuredSellerKey(JSON.stringify(key));
  assert.deepEqual(pinned, key);
  const envelope = await sealCustomer({customerName:'연결 테스트'}, pinned, 'quote', 'receipt');
  assert.equal(envelope.keyId, key.keyId);
  assert.equal(envelope.algorithm, 'RSA-OAEP-256+A256GCM');
  assert.equal(Buffer.from(envelope.wrappedKey, 'base64').length, 384);
  assert.ok(!JSON.stringify(envelope).includes('연결 테스트'));
});

test('missing, damaged, and mismatched pinned keys cannot be used', async () => {
  for (const value of [undefined, '', '{', 'null', JSON.stringify({...key,keyId:'0'.repeat(64)}), JSON.stringify({...key,publicKey:'invalid'})]) {
    assert.equal(await configuredSellerKey(value), null);
  }
});

test('dynamic key discovery still requires the full encryption and retention contract', () => {
  const service = {ok:true,service:'namufarm-quote-requests',version:4,encryptionReady:true,
    sellerKey:key,retentionDays:30,processingBases:['quote_request'],
    requestFields:['customerEmail','formalQuoteRequested','recipientName','replyChannel']};
  assert.deepEqual(validatedQuoteService(service), service);
  for (const changed of [{encryptionReady:false},{version:3},{retentionDays:90},{processingBases:[]},{requestFields:[]}]) {
    assert.equal(validatedQuoteService({...service,...changed}), null);
  }
});

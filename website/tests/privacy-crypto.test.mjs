import {test} from 'node:test';
import assert from 'node:assert/strict';
import {privateDecrypt,createDecipheriv,generateKeyPairSync} from 'node:crypto';
import {sealCustomer,sha256,privateFingerprint} from '../lib/privacy-crypto.ts';
const pair=generateKeyPairSync('rsa',{modulusLength:3072});
const publicKey=pair.publicKey.export({type:'spki',format:'der'}).toString('base64');
const seller={publicKey,keyId:await sha256(publicKey)};
function open(envelope,reference) {
 const key=privateDecrypt({key:pair.privateKey,oaepHash:'sha256'},Buffer.from(envelope.wrappedKey,'base64'));
 const bytes=Buffer.from(envelope.ciphertext,'base64'),aes=createDecipheriv('aes-256-gcm',key,Buffer.from(envelope.iv,'base64'));
 aes.setAAD(Buffer.from('namufarm:v1:quote:'+reference));aes.setAuthTag(bytes.subarray(-16));
 return JSON.parse(Buffer.concat([aes.update(bytes.subarray(0,-16)),aes.final()]).toString('utf8'));
}
test('actual website WebCrypto ciphertext decrypts with RSA SHA256 / MGF1 SHA256 and AES GCM',async()=>{const customer={customerName:'김나무',customerPhone:'01012345678',customerEmail:'buyer@example.com'};const envelope=await sealCustomer(customer,seller,'quote','test');assert.deepEqual(open(envelope,'test'),customer);assert.ok(!JSON.stringify(envelope).includes('김나무'));assert.equal(Buffer.from(envelope.wrappedKey,'base64').length,384)});
test('request binding rejects ciphertext moved to another customer',async()=>{const envelope=await sealCustomer({customerName:'김나무'},seller,'quote','one');assert.throws(()=>open(envelope,'two'))});
test('fresh random encryption preserves a stable private idempotency fingerprint',async()=>{const data={customerName:'김나무'};const first=await sealCustomer(data,seller,'quote','same'),second=await sealCustomer(data,seller,'quote','same');assert.notEqual(first.iv,second.iv);assert.notEqual(first.ciphertext,second.ciphertext);assert.equal(await privateFingerprint('secret',JSON.stringify(data)),await privateFingerprint('secret',JSON.stringify(data)));assert.notEqual(await privateFingerprint('other',JSON.stringify(data)),await privateFingerprint('secret',JSON.stringify(data)))});
test('mismatched or non-3072 public keys cannot produce accepted customer payloads',async()=>{await assert.rejects(sealCustomer({}, {...seller,keyId:'0'.repeat(64)},'quote','bad'));const small=generateKeyPairSync('rsa',{modulusLength:2048}).publicKey.export({type:'spki',format:'der'}).toString('base64');await assert.rejects(sealCustomer({}, {publicKey:small,keyId:await sha256(small)},'quote','bad'))});

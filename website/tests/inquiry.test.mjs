import test from 'node:test';
import assert from 'node:assert/strict';
import { inquirySchema, readInquiryResponse, validatedInquiryReceipt } from '../lib/inquiry.ts';

const requestId='12345678-0000-4000-8000-000000000001';
test('inquiry input accepts optional email and hyphenated phone; rejects uploads, missing consent and oversized text',()=>{
  const input={requestId,category:'묘목·재배',customerName:'테스트',customerPhone:'010-0000-0000',customerEmail:'',message:'묘목 문의입니다.',consent:true,website:''};
  assert.equal(inquirySchema.parse(input).customerPhone,'01000000000');
  for(const changed of [{consent:false},{message:'a'.repeat(2001)},{attachment:'file'},{website:'spam'},{customerEmail:'a@example.invalid,b@example.invalid'}])assert.equal(inquirySchema.safeParse({...input,...changed}).success,false);
});
test('only inquiry receipts with the expected identity and Korean date confirm success',async()=>{
  const receipt={ok:true,kind:'customer_inquiry',requestId,inquiryNumber:'INQ-20270101-0001',createdAt:'2026-12-31T15:00:00.000Z'};
  assert.ok(validatedInquiryReceipt(receipt,requestId));
  for(const changed of [{kind:'quote'},{requestId:'other'},{inquiryNumber:'NAMU-20270101-0001'},{inquiryNumber:'INQ-20261231-0001'}])assert.equal(validatedInquiryReceipt({...receipt,...changed},requestId),null);
  assert.equal((await readInquiryResponse(Response.json(receipt),requestId)).status,201);
  assert.equal((await readInquiryResponse(Response.json({ok:true}),requestId)).body.receiptState,'unknown');
  assert.equal((await readInquiryResponse(Response.json({ok:false,code:'NOT_READY'}),requestId)).body.receiptState,'unknown');
  assert.equal((await readInquiryResponse(Response.json({ok:false,code:'INVALID_ACTION'}),requestId)).body.receiptState,'not_sent');
});

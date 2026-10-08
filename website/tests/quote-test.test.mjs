import { test } from 'node:test';
import assert from 'node:assert/strict';
import { quoteSchema, requestFingerprint } from '../lib/quote-schema.ts';
import { testRequestMessage, testRequestPrefix } from '../lib/quote-test.ts';

test('test requests keep a stable receipt payload and fit the encrypted request contract', () => {
 const input = {
  requestId: '770dc0ab-976b-41e7-857a-f3e9dd6f9112',
  customerName: '홈페이지 테스트', customerPhone: '01000000000', region: '테스트 지역',
  items: [{ species: '블루베리', specification: '2년생', quantity: 1 }],
  message: testRequestMessage('가'.repeat(900)), processingBasis: 'quote_request', website: '',
  customerEmail: 'test@example.com', formalQuoteRequested: true, recipientName: '테스트 업체', replyChannel: 'email',
 };
 const parsed = quoteSchema.parse(input);
 assert.ok(parsed.message.startsWith(testRequestPrefix));
 assert.ok(parsed.message.length <= 1000);
 const restored = quoteSchema.parse(JSON.parse(JSON.stringify(parsed)));
 const {requestId: firstId, ...first} = parsed;
 const {requestId: restoredId, ...retry} = restored;
 assert.equal(restoredId, firstId);
 assert.equal(requestFingerprint(retry), requestFingerprint(first));
});

test('adding test mode does not rewrite a pending legacy receipt', () => {
 const legacy = {
  requestId: '770dc0ab-976b-41e7-857a-f3e9dd6f9112', customerName: '이전 요청',
  customerPhone: '01000000000', region: '테스트 지역',
  items: [{ species: '감나무', specification: '', quantity: 1 }],
  message: '기존 요청사항', consent: true, website: '',
 };
 assert.deepEqual(quoteSchema.parse(legacy), legacy);
});

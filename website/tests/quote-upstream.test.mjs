import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readQuoteResponse, retainPendingReceipt } from '../lib/quote-upstream.ts';

const id = '00000000-0000-4000-8000-000000000001';
const receipt = { ok: true, requestId: id, receiptNumber: 'NFQ-20261008-0001', createdAt: '2026-10-08T10:00:00.000Z' };

test('Google HTML 404 is a deployment error, not a JSON parsing/network error', async () => {
  const result = await readQuoteResponse(new Response('<html>private provider error</html>', {status:404}), id);
  assert.equal(result.status, 503);
  assert.equal(result.diagnostic, 'UPSTREAM_HTTP_404');
  assert.equal(result.body.receiptState, 'unknown');
  assert.ok(!JSON.stringify(result).includes('private provider error'));
});
test('a login page or malformed JSON never becomes a saved receipt', async () => {
  for (const text of ['<html>Sign in</html>', '{', 'null', '[]']) {
    const result = await readQuoteResponse(new Response(text), id);
    assert.equal(result.status, 502);
    assert.equal(result.body.receiptState, 'unknown');
  }
});
test('only a complete matching receipt confirms storage', async () => {
  assert.deepEqual(await readQuoteResponse(Response.json(receipt), id), {status:201,body:{requestId:id,receiptNumber:receipt.receiptNumber,createdAt:receipt.createdAt}});
  for (const value of [{...receipt,requestId:'another'}, {...receipt,receiptNumber:''}, {...receipt,ok:false}]) {
    assert.notEqual((await readQuoteResponse(Response.json(value), id)).status, 201);
  }
});
test('a conflict requires verification instead of recommending a new UUID', async () => {
  const result = await readQuoteResponse(Response.json({ok:false,code:'CONFLICT'}), id);
  assert.equal(result.status, 409);
  assert.match(result.body.error, /전화로 접수 여부/);
});
test('busy, rate limiting and configuration failures keep the pending request', async () => {
  for (const code of ['BUSY','RATE_LIMITED','UNAUTHORIZED','NOT_READY','UPGRADE_REQUIRED','INVALID']) {
    const result = await readQuoteResponse(Response.json({ok:false,code}), id);
    assert.equal(retainPendingReceipt(true, result.body), true);
    assert.equal(retainPendingReceipt(false, result.body), true);
    assert.notEqual(result.status, 201);
  }
});
test('only the first locally rejected attempt permits editing without prior confirmation', () => {
  assert.equal(retainPendingReceipt(false, {receiptState:'not_sent'}), false);
  assert.equal(retainPendingReceipt(true, {receiptState:'not_sent'}), true);
  assert.equal(retainPendingReceipt(false, {}), true);
});

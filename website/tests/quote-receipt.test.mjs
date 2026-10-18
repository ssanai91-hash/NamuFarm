import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validatedQuoteReceipt, receiptTimeSeoul } from '../lib/quote-receipt.ts';

const id = '770dc0ab-976b-41e7-857a-f3e9dd6f9112';
const receipt = { requestId: id, receiptNumber: 'NFQ-20261007-0001', createdAt: '2026-10-06T15:00:00.000Z' };
test('receipt displays the server date in Seoul at midnight', () => {
  assert.deepEqual(validatedQuoteReceipt(receipt, id), receipt);
  const current = { ...receipt, receiptNumber: 'NAMU-20261007-0001' };
  assert.deepEqual(validatedQuoteReceipt(current, id), current);
  assert.equal(receiptTimeSeoul(receipt.createdAt), '2026-10-07 00:00:00');
});
test('missing, mismatched, or malformed receipt metadata never shows completion', () => {
  for (const value of [null, {requestId:id}, {...receipt,requestId:'other'},
    {...receipt,receiptNumber:'NFQ-20261006-0001'}, {...receipt,receiptNumber:'NFQ-20261007-0000'},
    {...receipt,receiptNumber:'NAMU-20261006-0001'}, {...receipt,receiptNumber:'NAMU-20261007-0000'},
    {...receipt,createdAt:'2026-02-30T15:00:00.000Z'}, {...receipt,createdAt:'invalid'},
    {...receipt,receiptNumber:'<script>'}]) assert.equal(validatedQuoteReceipt(value, id), null);
});

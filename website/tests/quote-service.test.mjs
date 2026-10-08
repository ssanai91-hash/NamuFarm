import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validatedQuoteService } from '../lib/quote-service.ts';
const service = {ok:true, service:'namufarm-quote-requests', version:5, connectionMode:'device-auth',
  customerDataReady:true, retentionDays:30, processingBases:['quote_request'],
  requestFields:['customerEmail','formalQuoteRequested','recipientName','replyChannel']};
test('v5 readiness does not require a seller key or an Android installation', () => {
  assert.deepEqual(validatedQuoteService(service), service);
  assert.ok(validatedQuoteService({...service, sellerKey:null, encryptionReady:false}));
});
test('wrong service, old server and missing retention or request features are rejected', () => {
  for (const changed of [{ok:false},{service:'other'},{version:4},{customerDataReady:false},
    {connectionMode:'anonymous'},{retentionDays:90},{processingBases:[]},{requestFields:[]}]) {
    assert.equal(validatedQuoteService({...service,...changed}), null);
  }
});

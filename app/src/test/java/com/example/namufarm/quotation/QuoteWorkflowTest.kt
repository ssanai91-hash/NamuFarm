package com.example.namufarm.quotation

import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.remote.WebQuoteEnvelope
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuoteWorkflowTest {
    private val request = WebQuoteRequestEntity("00000000-0000-4000-8000-000000000001", 1,
        "2026-10-17", "가상 고객", "01000000000", "옥천", "[]", "요청사항",
        customerEmail = "sample@example.com", receiptNumber = "NAMU-20261017-0001")
    private val draft = SellerQuoteDraft(rows = listOf(SellerQuoteRow("가상 묘목", "", "3", "2000")),
        phone = request.customerPhone, recipient = QuoteRecipient.from(request), prepaid = true, fee = "6000")
    private class MemoryStore : QuoteWorkflowStore {
        val values = linkedMapOf<String, QuoteWorkflowRecord>()
        var failFinalSave = false
        override fun records() = values.values.toList()
        override fun save(record: QuoteWorkflowRecord) {
            if (failFinalSave && record.confirmedAt > 0) error("local write failure")
            // Simulate process persistence rather than retaining aliases to the UI input.
            values[record.request.requestId] = Gson().fromJson(Gson().toJson(record), QuoteWorkflowRecord::class.java)
        }
    }
    @Test fun unsentPendingAndFailedNeverQualify() = runBlocking {
        val store = MemoryStore()
        var confirms = 0
        val workflow = QuoteWorkflow(store, { _, _ -> error("unexpected send") }, { _, _ -> confirms++ })
        for (state in listOf("", "pending", "failed")) {
            store.save(QuoteWorkflowRecord(request, draft, QuoteDeliveryRecord(smsState = state,
                smsFingerprint = quoteWorkflowFingerprint(draft))))
            assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(request, draft) } }
            assertEquals(0, confirms)
            assertTrue(store.records().none { it.confirmedAt > 0 })
        }
    }
    @Test fun mailSuccessDoesNotConfirmAndEditsRequireResending() = runBlocking {
        val store = MemoryStore()
        val calls = mutableListOf<String>()
        val workflow = QuoteWorkflow(store, { action, _ ->
            calls += action; WebQuoteEnvelope(sent = true, recipientEmail = request.customerEmail)
        }, { _, _ -> calls += "confirm" }, { 123L })
        val sent = workflow.sendEmail(request, draft)
        assertTrue(sent.delivery.canComplete(draft))
        assertEquals(0L, sent.confirmedAt)
        val changed = draft.copy(fee = "7000")
        assertFalse(sent.delivery.canComplete(changed))
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(request, changed) } }
        assertEquals(listOf("send_quote_email"), calls)
        workflow.sendEmail(request, changed)
        assertEquals(123L, workflow.complete(request, changed).confirmedAt)
    }
    @Test fun smsRequiresFinal4000AndPendingButtonChecksRatherThanResends() = runBlocking {
        val store = MemoryStore()
        val calls = mutableListOf<String>()
        val workflow = QuoteWorkflow(store, { action, _ ->
            calls += action
            if (action == "send_quote_sms") WebQuoteEnvelope(sent = true, recipientPhone = request.customerPhone,
                messageId = "Mfixture", state = "pending", statusCode = "2000")
            else WebQuoteEnvelope(checked = true, messageId = "Mfixture", state = "success", statusCode = "4000")
        }, { _, _ -> }, { 321L })
        assertFalse(workflow.sendSms(request, draft).delivery.canComplete(draft))
        assertTrue(workflow.sendSms(request, draft).delivery.canComplete(draft))
        assertEquals(listOf("send_quote_sms", "quote_sms_status"), calls)
        assertEquals(0L, store.records().single().confirmedAt)
        assertEquals(321L, workflow.complete(request, draft).confirmedAt)
    }
    @Test fun failedServerConfirmationKeepsExactJournalAndNeverCreatesResult() = runBlocking {
        val store = MemoryStore()
        store.save(QuoteWorkflowRecord(request, draft, QuoteDeliveryRecord(emailSentAt = 1,
            emailFingerprint = quoteWorkflowFingerprint(draft))))
        val workflow = QuoteWorkflow(store, { _, _ -> error("unexpected send") }, { _, _ -> error("network") })
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(request, draft) } }
        val pending = store.records().single()
        assertTrue(pending.confirmationPending)
        assertEquals(0L, pending.confirmedAt)
        assertEquals(draft, pending.draft)
    }
    @Test fun localFailureAfterServerAckCanRetrySameQuoteWithoutAnotherDelivery() = runBlocking {
        val store = MemoryStore()
        store.save(QuoteWorkflowRecord(request, draft, QuoteDeliveryRecord(emailSentAt = 1,
            emailFingerprint = quoteWorkflowFingerprint(draft))))
        var confirms = 0
        val workflow = QuoteWorkflow(store, { _, _ -> error("must not resend") }, { _, received ->
            assertEquals(draft, received); assertTrue(store.records().single().confirmationPending); confirms++
        }, { 123L })
        store.failFinalSave = true
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(request, draft) } }
        assertEquals(0L, store.records().single().confirmedAt)
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(request, draft.copy(fee = "7000")) } }
        store.failFinalSave = false
        assertEquals(123L, workflow.complete(request, draft).confirmedAt)
        assertEquals(2, confirms)
    }
    @Test fun messagesIncludeItemAmountsShippingAccountAndHolder() {
        val body = quoteWorkflowText(request, draft)
        assertTrue(body.contains("NAMU-20261017-0001"))
        assertTrue(body.contains("12,000원"))
        assertTrue(body.contains("328-12-035671"))
        assertTrue(body.contains("오진웅"))
    }
    @Test fun oversizedQuotesFailBeforeNetworkOrJournalWrites() = runBlocking {
        val store = MemoryStore()
        val workflow = QuoteWorkflow(store, { _, _ -> error("must not send") }, { _, _ -> error("must not confirm") })
        val oversized = draft.copy(rows = listOf(SellerQuoteRow("가상 묘목", "", "3", "1000000000")))
        assertThrows(Exception::class.java) { runBlocking { workflow.sendEmail(request, oversized) } }
        assertTrue(store.records().isEmpty())
    }

    @Test fun alreadyConfirmedRequestsCannotEnterAnyWriteAction() = runBlocking {
        val store = MemoryStore()
        val workflow = QuoteWorkflow(store, { _, _ -> error("must not send") }, { _, _ -> error("must not confirm") })
        val confirmed = request.copy(status = "견적확정")
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.sendSms(confirmed, draft) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.sendEmail(confirmed, draft) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.complete(confirmed, draft) } }
        assertTrue(store.records().isEmpty())
    }

}

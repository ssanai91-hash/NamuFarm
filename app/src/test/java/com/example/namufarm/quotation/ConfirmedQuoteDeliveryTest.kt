package com.example.namufarm.quotation

import com.example.namufarm.data.WebQuoteRequestEntity
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfirmedQuoteDeliveryTest {
    private val request = WebQuoteRequestEntity("00000000-0000-4000-8000-000000000023", 1,
        "2026-10-27", "고객", "01000000000", "옥천", "[]", "고객 원문",
        customerEmail = "sample@example.com", receiptNumber = "NAMU-20261027-0023")
    private val original = SellerQuoteDraft(listOf(SellerQuoteRow("묘목", "1년생", "3", "2000")),
        request.customerPhone, QuoteRecipient.from(request))
    private val draft = original.copy(rows = listOf(SellerQuoteRow("수정 묘목", "2년생", "4", "3000", "새 설명")))
    private val record = QuoteWorkflowRecord(request, draft,
        QuoteDeliveryRecord(emailSentAt = 50, emailFingerprint = quoteWorkflowFingerprint(original)),
        confirmedAt = 100, originalDraft = original, revision = 1, revisedAt = 200)
    private val number = "NQ-20261027-1234ABCD"
    private class MemoryStore : QuoteWorkflowStore {
        var value = ""
        var failFinalSave = false
        override fun records() = if (value.isEmpty()) emptyList() else listOf(Gson().fromJson(value, QuoteWorkflowRecord::class.java))
        override fun save(record: QuoteWorkflowRecord) {
            if (failFinalSave && record.deliveryHistory.orEmpty().isNotEmpty()) error("storage failure")
            value = Gson().toJson(record)
        }
    }
    private fun store() = MemoryStore().also { it.save(record) }
    private fun workflow(store: MemoryStore, status: () -> String = { "견적확정" }, mark: suspend (String) -> Unit = {}) =
        QuoteWorkflow(store, { _, _ -> error("Never send or reprice through the server") },
            { _, _ -> error("Never change confirmed prices") }, { 300 },
            latestRequest = { request.copy(status = status(), sequence = 2, quotePricingJson = "{}") },
            markConfirmedDelivered = mark)

    @Test fun preparingExternalAppDoesNotMarkDeliveryOrCallAStatusWrite() = runBlocking {
        val store = store(); var writes = 0
        workflow(store, mark = { writes++ }).prepareConfirmedExternal(record, QuoteDelivery.SMS)
        assertEquals(record, store.records().single()); assertEquals(0, writes)
        assertTrue(store.records().single().revisedUnsent())
    }
    @Test fun sellerConfirmationRecordsRevisionDocumentSnapshotAndPreviousDelivery() = runBlocking {
        val store = store(); var writes = 0
        val sent = workflow(store, mark = {
            assertNotNull(store.records().single().pendingConfirmedDelivery); writes++
        }).completeConfirmedExternal(record, "both", number)
        assertEquals(1, writes); assertEquals(100L, sent.confirmedAt); assertEquals(request, sent.request)
        assertFalse(sent.revisedUnsent()); assertNull(sent.pendingConfirmedDelivery)
        val evidence = sent.deliveryHistory.orEmpty().single()
        assertEquals(1, evidence.revision); assertEquals(number, evidence.documentNumber)
        assertEquals(draft, evidence.draft); assertEquals(record.delivery, evidence.previousDelivery)
        assertTrue(sent.delivery.canComplete(draft)); assertEquals(original, sent.originalDraft)
    }
    @Test fun alreadyDeliveredServerStatusNeverGetsAnotherStatusWrite() = runBlocking {
        val store = store(); var writes = 0
        val sent = workflow(store, status = { "견적발송완료" }, mark = { writes++ })
            .completeConfirmedExternal(record, "sms", number)
        assertEquals(0, writes); assertEquals(1, sent.deliveryHistory.orEmpty().size)
    }
    @Test fun lostAcknowledgementKeepsExactJournalAndRetryDoesNotResend() = runBlocking {
        val store = store(); var status = "견적확정"; var writes = 0
        val workflow = workflow(store, status = { status }, mark = { writes++; status = "견적발송완료"; error("ack lost") })
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.completeConfirmedExternal(record, "sms", number) } }
        val pending = store.records().single(); val evidence = pending.pendingConfirmedDelivery!!
        assertTrue(pending.revisedUnsent()); assertTrue(pending.deliveryHistory.orEmpty().isEmpty())
        assertNotNull(confirmedQuoteEditBlock(pending, request.copy(status = status)))
        val recovered = workflow.retryConfirmedDelivery(pending)
        assertEquals(1, writes); assertEquals(evidence, recovered.deliveryHistory.orEmpty().single())
        assertFalse(recovered.revisedUnsent())
    }
    @Test fun localFailureAfterServerAckCanRecoverWithoutAnotherWrite() = runBlocking {
        val store = store(); var status = "견적확정"; var writes = 0
        val workflow = workflow(store, status = { status }, mark = { writes++; status = "견적발송완료" })
        store.failFinalSave = true
        assertThrows(IllegalStateException::class.java) { runBlocking { workflow.completeConfirmedExternal(record, "email", number) } }
        val pending = store.records().single(); assertNotNull(pending.pendingConfirmedDelivery)
        store.failFinalSave = false
        val recovered = workflow.retryConfirmedDelivery(pending)
        assertEquals(1, writes); assertEquals(1, recovered.deliveryHistory.orEmpty().size)
    }
    @Test fun paymentAndMissingAttestationNeverStartAnAcknowledgement() = runBlocking {
        val store = store(); val before = store.value
        assertThrows(IllegalStateException::class.java) { runBlocking {
            workflow(store, status = { "입금확인" }).completeConfirmedExternal(record, "sms", number)
        } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { workflow(store).completeConfirmedExternal(record, "none", number) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { workflow(store).completeConfirmedExternal(record, "sms", "") } }
        assertEquals(before, store.value)
    }
    @Test fun laterResendAppendsHistoryAndKeepsEarlierEvidence() = runBlocking {
        val store = store(); val workflow = workflow(store, status = { "견적발송완료" })
        val first = workflow.completeConfirmedExternal(record, "sms", number)
        val revised = workflow.reviseConfirmed(first, draft.copy(fee = "7000"), request.copy(status = "견적발송완료"))
        val second = workflow.completeConfirmedExternal(revised, "email", "NQ-20261027-8765ABCD")
        assertEquals(2, second.deliveryHistory.orEmpty().size)
        assertEquals(first.deliveryHistory.orEmpty().single(), second.deliveryHistory.orEmpty().first())
        assertEquals(original, second.originalDraft)
    }
    @Test fun revisedTextAndEmailBodyUseTheSavedDraft() {
        for (channel in listOf(QuoteDelivery.SMS, QuoteDelivery.EMAIL)) {
            val body = quoteWorkflowText(request, draft, channel)
            assertTrue(body.contains("수정 묘목 4주")); assertTrue(body.contains("12,000원")); assertTrue(body.contains("새 설명"))
            assertFalse(body.contains("묘목 3주"))
        }
    }
}

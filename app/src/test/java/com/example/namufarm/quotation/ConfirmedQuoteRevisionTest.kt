package com.example.namufarm.quotation

import com.example.namufarm.data.WebQuoteRequestEntity
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ConfirmedQuoteRevisionTest {
    private val request = WebQuoteRequestEntity("00000000-0000-4000-8000-000000000023", 1,
        "2026-10-27", "고객", "01000000000", "옥천", "[]", "수정하지 않을 고객 원문",
        customerEmail = "sample@example.com", receiptNumber = "NAMU-20261027-0023")
    private val draft = SellerQuoteDraft(listOf(SellerQuoteRow("묘목", "1년생", "3", "2000", "처음 설명")),
        request.customerPhone, QuoteRecipient.from(request), false, "6000")
    private val latest = request.copy(status = "견적확정", sequence = 2, quotePricingJson = "{}")
    private val delivery = QuoteDeliveryRecord(emailSentAt = 90, emailFingerprint = quoteWorkflowFingerprint(draft))
    private class MemoryStore : QuoteWorkflowStore {
        var json: String? = null
        override fun records() = json?.let { listOf(Gson().fromJson(it, QuoteWorkflowRecord::class.java)) }.orEmpty()
        override fun save(record: QuoteWorkflowRecord) { json = Gson().toJson(record) }
    }
    private fun record() = QuoteWorkflowRecord(request, draft, delivery, confirmedAt = 100)
    private fun workflow(store: MemoryStore) = QuoteWorkflow(store, { _, _ -> error("No server calls") },
        { _, _ -> error("No repricing") }, { 200 })

    @Test fun revisionIsLocalAndPreservesOriginalDateRequestAndDelivery() {
        val store = MemoryStore(); store.save(record())
        val revised = workflow(store).reviseConfirmed(record(), draft.copy(fee = "7000"), latest)
        assertEquals(1, revised.revision); assertEquals(200L, revised.revisedAt)
        assertEquals(100L, revised.confirmedAt); assertEquals(request, revised.request)
        assertEquals(delivery, revised.delivery); assertEquals(draft, revised.originalDraft)
        assertTrue(revised.revisedUnsent())
        assertEquals(revised, store.records().single())
    }
    @Test fun secondRevisionNeverOverwritesTheFirstConfirmedDraft() {
        val store = MemoryStore(); store.save(record()); val workflow = workflow(store)
        val first = workflow.reviseConfirmed(record(), draft.copy(fee = "7000"), latest)
        val second = workflow.reviseConfirmed(first, first.draft.copy(prepaid = true), latest)
        assertEquals(2, second.revision); assertEquals(draft, second.originalDraft)
        assertEquals(100L, second.confirmedAt)
    }
    @Test fun legacyJsonHasCompatibleDefaults() {
        val json = Gson().toJsonTree(record()).asJsonObject
        listOf("originalDraft", "revision", "revisedAt", "revisionSentAt").forEach { json.remove(it) }
        val legacy = Gson().fromJson(json, QuoteWorkflowRecord::class.java)
        assertNull(legacy.originalDraft); assertEquals(0, legacy.revision); assertEquals(0L, legacy.revisedAt)
        assertFalse(legacy.revisedUnsent())
    }
    @Test fun paymentShippingAndCancellationBlockSavingWithoutChangingTheStore() {
        for (status in listOf("입금확인", "배송완료", "구매취소", "신규")) {
            val store = MemoryStore(); store.save(record()); val before = store.json
            assertThrows(IllegalStateException::class.java) {
                workflow(store).reviseConfirmed(record(), draft.copy(fee = "7000"), latest.copy(status = status))
            }
            assertEquals(before, store.json)
        }
    }
    @Test fun pendingUnknownSendsAndConfirmationBlockRevision() {
        for (state in listOf("pending", "unknown", "sending"))
            assertNotNull(confirmedQuoteEditBlock(record().copy(delivery = delivery.copy(smsState = state)), latest))
        assertNotNull(confirmedQuoteEditBlock(record().copy(confirmationPending = true), latest))
        assertNotNull(confirmedQuoteEditBlock(record(), null))
    }
    @Test fun customerFieldsAndOriginalRequestCannotBeChanged() {
        val store = MemoryStore(); store.save(record())
        assertThrows(IllegalStateException::class.java) { workflow(store).reviseConfirmed(record(), draft.copy(phone = "01099998888"), latest) }
        assertThrows(IllegalStateException::class.java) {
            workflow(store).reviseConfirmed(record(), draft.copy(recipient = draft.recipient.copy(customerEmail = "other@example.com")), latest)
        }
        assertThrows(IllegalStateException::class.java) { workflow(store).reviseConfirmed(record(), draft, latest.copy(message = "다른 원문")) }
        assertEquals(record(), store.records().single())
    }
    @Test fun staleLocalRecordCannotOverwriteAnAlreadySavedRevision() {
        val store = MemoryStore(); store.save(record()); val workflow = workflow(store)
        val current = workflow.reviseConfirmed(record(), draft.copy(fee = "7000"), latest)
        assertThrows(IllegalStateException::class.java) { workflow.reviseConfirmed(record(), draft.copy(fee = "8000"), latest) }
        assertEquals(current, store.records().single())
    }
    @Test fun serverOnlyRecordDoesNotInventAConfirmationDate() {
        val store = MemoryStore(); val serverOnly = record().copy(request = latest, confirmedAt = 0)
        val revised = workflow(store).reviseConfirmed(serverOnly, draft.copy(fee = "7000"), latest)
        assertEquals(0L, revised.confirmedAt)
        assertEquals(draft, revised.originalDraft)
    }
}

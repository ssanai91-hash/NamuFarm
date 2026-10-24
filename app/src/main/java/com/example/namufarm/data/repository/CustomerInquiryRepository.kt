package com.example.namufarm.data.repository

import androidx.room.withTransaction
import com.example.namufarm.data.*
import com.example.namufarm.data.remote.*
import com.example.namufarm.quotemode.CustomerInquiryConnection
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID

data class CustomerInquiryCache(
    val rows: List<CustomerInquiryRemote>,
    val drafts: Map<String, CustomerInquiryDraftEntity>,
    val operations: Map<String, CustomerInquiryOperationEntity>
)

/** Separate delta cursor and durable operation IDs; never touches quote/order tables. */
class CustomerInquiryRepository(private val db: CustomerInquiryDatabase, private val connection: CustomerInquiryConnection) {
    private val dao = db.customerInquiryDao()
    private val gson = Gson()
    private fun decode(entity: CustomerInquiryEntity): CustomerInquiryRemote =
        gson.fromJson(entity.body, CustomerInquiryRemote::class.java).checked()

    suspend fun local(): CustomerInquiryCache {
        purgeExpired(db)
        return db.withTransaction {
            CustomerInquiryCache(dao.records().map(::decode).filter { it.expired != true }.sortedByDescending { it.createdAt },
                dao.drafts().associateBy { it.requestId }, dao.operations().associateBy { it.requestId })
        }
    }

    private suspend fun row(id: String): CustomerInquiryRemote = db.withTransaction {
        val entity = checkNotNull(dao.record(id)) { "문의가 없습니다. 불러오기를 눌러주세요." }
        var value = decode(entity)
        if (expiredByClock(value)) {
            value = value.copy(expired = true).checked()
            dao.put(entity.copy(body = gson.toJson(value), localExpiry = true))
            dao.deleteDraft(id); dao.deleteOperation(id)
        }
        check(value.expired != true) { "문의 보관 기간이 끝났습니다." }
        value
    }

    private suspend fun bindServer(): CustomerInquirySyncEntity = db.withTransaction {
        val current = dao.syncState()
        check(current == null || current.serverUrl == connection.serverUrl) { "다른 서버의 문의 자료입니다. 기존 연결 주소를 확인해주세요." }
        current ?: CustomerInquirySyncEntity(serverUrl = connection.serverUrl, cursor = 0).also { dao.put(it) }
    }

    private suspend fun call(action: String, fields: Map<String, Any>): WebQuoteEnvelope {
        val source = bindServer().serverUrl
        val response = connection.inquiryAction(action, fields)
        check(connection.serverUrl == source) { "처리 중 서버 주소가 변경됐습니다. 기존 연결로 돌아와 결과를 확인해주세요." }
        return response
    }

    private suspend fun merge(value: CustomerInquiryRemote) {
        val id = checkNotNull(value.requestId)
        val previous = dao.record(id)
        if (previous != null && previous.revision > checkNotNull(value.revision)) return
        val before = previous?.let(::decode)
        if (before?.expired == true && value.expired != true &&
            !(previous?.localExpiry == true && value.revision!! > previous.revision && value.status == "open")) return
        dao.put(CustomerInquiryEntity(id, checkNotNull(value.revision), gson.toJson(value)))
        if (value.expired == true) {
            dao.deleteDraft(id); dao.deleteOperation(id)
        } else {
            val draft = dao.draft(id)
            // A clean local copy follows server edits; unsaved text keeps its old base revision.
            if (draft != null && before != null && draft.baseRevision == before.revision &&
                draft.text == before.reply?.text && draft.channel == before.reply?.channel) {
                dao.put(CustomerInquiryDraftEntity(id, value.reply!!.text!!, value.reply.channel!!, value.revision!!))
            }
        }
    }

    suspend fun refresh(): Int {
        check(connection.configured) { "운영 서버 연결 주소를 확인해주세요." }
        var cursor = bindServer().cursor
        var imported = 0
        repeat(100) {
            val result = call("list_customer_inquiries", mapOf("afterSequence" to cursor, "limit" to 100))
            val values = checkNotNull(result.inquiries) { "문의 목록을 확인할 수 없습니다. 서버를 최신 버전으로 재배포해주세요." }
            check(values.size <= 100 && result.hasMore != null && result.nextSequence != null) { "문의 목록 응답이 올바르지 않습니다." }
            val rows = values.map { checkNotNull(it).checked() }
            val sequences = rows.map { checkNotNull(it.sequence) }
            val next = result.nextSequence
            check(sequences == sequences.distinct().sorted() && sequences.all { it > cursor } &&
                rows.map { it.requestId }.distinct().size == rows.size &&
                next == (sequences.lastOrNull() ?: cursor) && (result.hasMore != true || rows.isNotEmpty())) {
                "문의 변경순번이 올바르지 않습니다. 기존 자료를 유지합니다."
            }
            db.withTransaction {
                rows.forEach { merge(it) }
                val state = checkNotNull(dao.syncState())
                check(state.serverUrl == connection.serverUrl && state.cursor == cursor)
                dao.put(state.copy(cursor = checkNotNull(next)))
            }
            cursor = checkNotNull(next)
            imported += rows.size
            if (result.hasMore == false) return imported
        }
        error("문의가 많아 나누어 가져왔습니다. 불러오기를 다시 눌러주세요.")
    }

    suspend fun remember(draft: CustomerInquiryDraftEntity) = db.withTransaction {
        val current = row(draft.requestId)
        check(dao.operation(draft.requestId) == null && !current.blockedDelivery) { "이전 발송 결과를 먼저 확인해주세요." }
        check(draft.text.length <= 2000 && draft.channel in setOf("sms", "email")) { "답변은 2,000자 이내로 입력해주세요." }
        dao.put(draft)
    }

    private suspend fun draft(current: CustomerInquiryRemote) = dao.draft(current.requestId!!)
        ?: CustomerInquiryDraftEntity(current.requestId, current.reply!!.text!!, current.reply.channel!!, current.revision!!)

    private suspend fun persist(id: String, action: String, fields: Map<String, Any>): CustomerInquiryOperationEntity = db.withTransaction {
        check(row(id).revision == fields["revision"]) { "문의 상태가 변경됐습니다. 다시 불러와 확인해주세요." }
        check(dao.operation(id) == null) { "이전 작업의 결과 확인을 먼저 눌러주세요." }
        CustomerInquiryOperationEntity(id, action, gson.toJson(fields)).also { dao.put(it) }
    }

    private fun fields(operation: CustomerInquiryOperationEntity): Map<String, Any> {
        val objectValue = gson.fromJson(operation.payload, JsonObject::class.java)
        return objectValue.entrySet().associate { (key, value) ->
            key to if (key == "revision") value.asLong else value.asString
        }
    }

    private suspend fun execute(operation: CustomerInquiryOperationEntity): CustomerInquiryRemote {
        val payload = fields(operation)
        val result = call(operation.action, payload)
        val value = checkNotNull(result.inquiry) { "문의 처리 응답을 확인하지 못했습니다. 결과 확인을 눌러주세요." }.checked()
        check(value.requestId == operation.requestId && value.expired != true) { "문의 처리 결과를 확인할 수 없습니다." }
        when (operation.action) {
            "save_inquiry_reply" -> check(value.lastEventId == payload["eventId"] &&
                value.reply?.text == payload["replyText"] && value.reply?.channel == payload["replyChannel"]) { "답변 저장을 확인하지 못했습니다." }
            "send_inquiry_reply" -> check(value.delivery?.id == payload["deliveryId"] ||
                value.history.orEmpty().any { it?.kind == "reply" && it.id == payload["deliveryId"] }) { "발송 결과를 확인하지 못했습니다." }
            "set_inquiry_status" -> check(value.history.orEmpty().any { it?.kind == "status" && it.id == payload["eventId"] }) { "처리 상태를 확인하지 못했습니다." }
        }
        db.withTransaction {
            merge(value)
            if (operation.action == "save_inquiry_reply") {
                val currentDraft = dao.draft(operation.requestId)
                if (currentDraft != null && currentDraft.text == payload["replyText"] && currentDraft.channel == payload["replyChannel"]) {
                    dao.put(currentDraft.copy(baseRevision = value.revision!!))
                }
            }
            dao.deleteOperation(operation.requestId)
        }
        return value
    }

    suspend fun save(id: String, channel: String? = null) {
        bindServer()
        val operation = db.withTransaction {
            val current = row(id)
            check(dao.operation(id) == null && !current.blockedDelivery) { "이전 작업의 결과를 먼저 확인해주세요." }
            val draft = draft(current).let { it.copy(text = it.text.trim(), channel = channel ?: it.channel) }
            check(draft.baseRevision == current.revision) { "다른 기기에서 수정했습니다. 불러온 답변과 내 초안을 비교해주세요." }
            check(draft.channel in setOf("sms", "email") && draft.text.length <= 2000 &&
                (draft.channel != "sms" || draft.text.length <= 900)) { "문자는 900자, 메일은 2,000자까지 보낼 수 있습니다." }
            check(draft.channel != "email" || !current.inquiry?.customerEmail.isNullOrBlank()) { "고객이 이메일을 남기지 않았습니다." }
            dao.put(draft)
            persist(id, "save_inquiry_reply", mapOf("requestId" to id, "revision" to current.revision!!,
                "eventId" to UUID.randomUUID().toString(), "replyText" to draft.text, "replyChannel" to draft.channel))
        }
        execute(operation)
    }

    suspend fun send(id: String, channel: String) {
        val current = row(id)
        check(draft(current).text.isNotBlank()) { "답변을 입력해주세요." }
        // Saving and sending are separate durable operations. A failed save never sends.
        save(id, channel)
        val saved = row(id)
        execute(persist(id, "send_inquiry_reply", mapOf("requestId" to id,
            "revision" to saved.revision!!, "deliveryId" to UUID.randomUUID().toString())))
    }

    suspend fun checkResult(id: String) {
        bindServer()
        val operation = dao.operation(id)
        var current = row(id)
        if (operation != null) {
            try { current = execute(operation) }
            catch (error: WebQuoteResponseException) {
                if (error.responseCode == "CONFLICT") {
                    refresh()
                    current = row(id)
                    val payload = fields(operation)
                    // A rejected stale send cannot later commit. Never create a replacement send here.
                    if (operation.action != "save_inquiry_reply" && current.revision!! > (payload["revision"] as Long)) {
                        val event = payload["deliveryId"] ?: payload["eventId"]
                        val accepted = current.delivery?.id == event || current.history.orEmpty().any { it?.id == event }
                        if (accepted) { dao.deleteOperation(id) }
                        else { dao.deleteOperation(id); throw IllegalStateException("다른 기기에서 변경되어 이전 작업은 거절되었습니다. 최신 내용을 확인해주세요.") }
                    } else throw error
                } else throw error
            }
        }
        val delivery = current.delivery
        if (delivery?.channel == "sms" && !delivery.messageId.isNullOrBlank() && delivery.state !in setOf("success", "failed")) {
            val response = call("inquiry_sms_status", mapOf("requestId" to id))
            val updated = checkNotNull(response.inquiry).checked()
            check(updated.requestId == id)
            db.withTransaction { merge(updated) }
        } else if (current.delivery?.state in setOf("sending", "unknown")) {
            refresh() // No new send request; an unknown provider result must not be resent.
        }
    }

    suspend fun setStatus(id: String, status: String, note: String) {
        bindServer()
        val current = row(id)
        check(status in setOf("open", "closed") && note.trim().length in 1..500) { "처리 메모는 1~500자로 입력해주세요." }
        execute(persist(id, "set_inquiry_status", mapOf("requestId" to id, "revision" to current.revision!!,
            "eventId" to UUID.randomUUID().toString(), "status" to status, "note" to note.trim())))
    }

    /** Explicit user choice after refreshing. A pending send can never be discarded here. */
    suspend fun resolveDraft(id: String, useServer: Boolean) {
        refresh()
        val current = row(id)
        check(!current.blockedDelivery) { "발송 결과를 먼저 확인해주세요." }
        db.withTransaction {
            val pending = dao.operation(id)
            check(pending == null || pending.action == "save_inquiry_reply") { "이전 발송·처리 결과를 먼저 확인해주세요." }
            val draft = draft(current)
            dao.put(if (useServer) CustomerInquiryDraftEntity(id, current.reply!!.text!!, current.reply.channel!!, current.revision!!)
                else draft.copy(baseRevision = current.revision!!))
            dao.deleteOperation(id)
        }
    }

    companion object {
        private fun expiredByClock(value: CustomerInquiryRemote): Boolean = value.expired != true &&
            value.status == "closed" && !value.answeredAt.isNullOrEmpty() &&
            inquiryTimestamp(value.answeredAt) + 30L * 86400000 <= System.currentTimeMillis()

        suspend fun purgeExpired(db: CustomerInquiryDatabase) {
            val dao = db.customerInquiryDao()
            val gson = Gson()
            db.withTransaction {
                for (entity in dao.records()) {
                    val value = gson.fromJson(entity.body, CustomerInquiryRemote::class.java).checked()
                    if (!expiredByClock(value)) continue
                    val expired = value.copy(expired = true).checked()
                    dao.put(entity.copy(body = gson.toJson(expired), localExpiry = true))
                    dao.deleteDraft(entity.requestId); dao.deleteOperation(entity.requestId)
                }
            }
        }
    }
}

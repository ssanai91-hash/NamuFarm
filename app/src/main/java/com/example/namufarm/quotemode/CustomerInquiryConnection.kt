package com.example.namufarm.quotemode

import com.example.namufarm.data.remote.*
import com.google.gson.Gson

/** Reuse the initial app's encrypted device credential and fixed operating endpoint. */
class CustomerInquiryConnection(private val gateway: OperationsMasterGateway) {
    val serverUrl: String get() = OperationsMasterTransport.ENDPOINT
    val configured: Boolean get() = WebQuoteTransport.configured
    private val gson = Gson()

    suspend fun inquiryAction(action: String, fields: Map<String, Any>): WebQuoteEnvelope {
        require(action in setOf("list_customer_inquiries", "save_inquiry_reply",
            "send_inquiry_reply", "set_inquiry_status", "inquiry_sms_status", "delete_customer_inquiry")) {
            "앱이 지원하지 않는 문의 작업입니다: $action"
        }
        val raw = try { gateway.request(action, fields) }
        catch (error: MasterServerException) {
            // Keep the shared repository's conflict/uncertain-operation handling.
            WebQuoteValidator.checkResponse(WebQuoteEnvelope(ok = false, code = error.code))
            throw error
        }
        return gson.fromJson(raw, WebQuoteEnvelope::class.java).also(WebQuoteValidator::checkResponse)
    }
}

package com.example.namufarm.quotation

import com.example.namufarm.data.WebQuoteRequestEntity

internal val CONFIRMED_QUOTE_STATES = setOf("견적확정", "견적발송완료")
/** Paid (구매확정 = 입금확인), delivered or cancelled. These are managed in 매매, not as open quotes. */
internal val CLOSED_QUOTE_STATES = setOf("입금확인", "구매확정", "배송완료", "판매완료", "구매취소", "주문취소")

/** Ignore server pricing/status metadata, never a changed original customer request. */
internal fun sameConfirmedCustomer(a: WebQuoteRequestEntity, b: WebQuoteRequestEntity): Boolean =
    a.copy(sequence = b.sequence, status = b.status, readAt = b.readAt, quotePricingJson = b.quotePricingJson) == b

fun confirmedQuoteEditBlock(record: QuoteWorkflowRecord, latest: WebQuoteRequestEntity?): String? = when {
    latest == null -> "최신 서버 상태를 확인한 뒤 수정할 수 있어요."
    latest.status in CLOSED_QUOTE_STATES -> "입금확인된 견적은 수정할 수 없어요."
    latest.status !in CONFIRMED_QUOTE_STATES -> "현재 서버 상태에서는 확정견적을 수정할 수 없어요."
    !sameConfirmedCustomer(record.request, latest) -> "고객 접수정보가 바뀌었습니다. 최신 목록에서 다시 확인해주세요."
    record.confirmedAt <= 0 && record.request.status !in CONFIRMED_QUOTE_STATES -> "확정한 견적 기록을 확인해주세요."
    record.confirmationPending || record.pendingConfirmedDelivery != null || record.delivery.smsState in setOf("pending", "unknown", "sending") -> "이전 발송·견적완료 결과를 먼저 확인해주세요."
    else -> null
}

fun QuoteWorkflowRecord.revisedUnsent(): Boolean = revision > 0 && revisionSentAt == 0L

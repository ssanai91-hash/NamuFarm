package com.example.namufarm.privacy

import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.OrderStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object PrivacyRetention {
    const val QUOTE_MS = 30L*24*60*60*1000
    fun quoteTime(value: String): Long = SimpleDateFormat(if(value.contains('T')) "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" else if(value.length==10) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm",Locale.KOREA)
        .apply { timeZone=TimeZone.getTimeZone(if(value.contains('T')) "UTC" else "Asia/Seoul");isLenient=false }.parse(value)?.time ?: error("보관기간 기준 날짜를 확인해주세요.")
    fun saleExpires(order: OrderEntity): Long? {
        if(order.status !in listOf(OrderStatus.COMPLETED,OrderStatus.CANCELED)) return null
        val base=if(order.status==OrderStatus.COMPLETED) order.completedAt else order.updatedAt
        if(base<=0) return null // Legacy cancellation date is unknown: do not invent a disposal deadline.
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis=base;add(Calendar.YEAR,5) }.timeInMillis
    }
}

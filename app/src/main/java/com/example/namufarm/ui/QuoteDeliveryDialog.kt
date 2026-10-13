package com.example.namufarm.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.domain.order.OrderSummaryCodec
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.quotation.*
import com.example.namufarm.viewmodel.ManagementViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Connect the approved stateless screen to saved, unsent quote data and the existing send intents. */
@Composable
internal fun QuoteDeliveryDialog(
    viewModel: ManagementViewModel,
    number: String,
    initialAction: QuoteDelivery,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    onQuoteSent: suspend () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val management by viewModel.uiState.collectAsState()
    var quote by remember(number) { mutableStateOf<QuoteEntity?>(null) }
    var delivery by remember(number) {
        mutableStateOf(QuoteDeliveryUiState(
            orderNumber = number,
            customer = QuoteDeliveryCustomer("고객", "", null, false, true, false),
            items = emptyList()
        ))
    }
    var loading by remember(number) { mutableStateOf(true) }
    var busy by remember(number) { mutableStateOf(false) }
    var error by remember(number) { mutableStateOf<String?>(null) }
    var notice by remember(number) { mutableStateOf<String?>(null) }
    var confirmSent by remember(number) { mutableStateOf(false) }
    var lastOpened by remember(number) { mutableStateOf<QuoteDelivery?>(null) }
    var initialHandled by remember(number) { mutableStateOf(false) }

    val editable = quote?.let { saved ->
        saved.sentAt == 0L &&
            management.quoteDocuments.none { it.quoteNumber == number } &&
            management.allOrders.none { it.sourceQuoteNumber == number } &&
            runCatching { OrderSummaryCodec.parseStrict(saved.itemSummary).all { it.source == OrderLineSource.MANUAL } }
                .getOrDefault(false)
    } ?: false

    fun changedFrom(saved: QuoteEntity): Boolean {
        val lines = OrderSummaryCodec.parseStrict(saved.itemSummary)
        return delivery.items.size != lines.size ||
            delivery.items.indices.any { index -> delivery.items[index].unitPrice != lines[index].retailPrice } ||
            delivery.prepaid != saved.isPrepaid ||
            (if (delivery.prepaid) delivery.shippingFee else 0) != saved.shippingFee
    }

    suspend fun savePricingBeforeSend(): QuoteEntity {
        val baseline = quote ?: kotlin.error("견적을 불러오지 못했습니다.")
        val current = viewModel.savedQuote(number) ?: kotlin.error("저장된 견적을 찾을 수 없습니다.")
        check(current == baseline) { "다른 화면에서 견적이 변경되었습니다. 닫았다가 다시 열어주세요." }
        if (!changedFrom(current)) return current
        check(editable) { "이미 발송·발행·주문한 견적이나 재고 견적은 수정할 수 없습니다." }
        val originals = OrderSummaryCodec.parseStrict(current.itemSummary)
        val updatedLines = originals.mapIndexed { index, line ->
            line.copy(retailPrice = delivery.items[index].unitPrice)
        }
        val saved = viewModel.updateQuotePricing(number, updatedLines, delivery.prepaid, delivery.shippingFee)
        quote = saved
        return saved
    }

    suspend fun send(action: QuoteDelivery) {
        if (busy || loading || action == QuoteDelivery.SAVE) return
        busy = true
        error = null
        try {
            // The text must be generated ONLY from the successfully persisted final prices.
            val saved = savePricingBeforeSend()
            val body = viewModel.generateQuoteTextFromEntity(saved)
            check(body.isNotBlank()) { "견적 내용을 확인해주세요." }
            if (action == QuoteDelivery.EMAIL) {
                QuoteReply.openTextEmail(context, delivery.customer.email.orEmpty().trim(), body)
            } else {
                openSmsComposer(context, saved.customerPhone, body)?.let { kotlin.error(it) }
            }
            lastOpened = action
            notice = "문자·메일 앱에서 실제 전송을 마친 뒤 ‘견적발송완료’를 눌러주세요."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "전송 화면을 열지 못했습니다. 다시 시도해주세요."
        } finally {
            busy = false
        }
    }

    LaunchedEffect(number) {
        try {
            val saved = viewModel.savedQuote(number) ?: kotlin.error("저장된 견적을 찾을 수 없습니다.")
            val recipient = viewModel.quoteRecipient(number)
            val lines = OrderSummaryCodec.parseStrict(saved.itemSummary)
            quote = saved
            delivery = QuoteDeliveryUiState(
                orderNumber = number,
                customer = QuoteDeliveryCustomer(
                    name = recipient.recipientName.ifBlank { recipient.customerName }.ifBlank { "고객" },
                    phone = saved.customerPhone,
                    email = recipient.customerEmail,
                    formalQuoteRequested = recipient.formalQuoteRequested,
                    smsRequested = recipient.replyChannel != "email",
                    emailRequested = recipient.replyChannel != "sms"
                ),
                items = lines.mapIndexed { index, line ->
                    QuoteDeliveryItem(
                        id = index.toString(),
                        name = line.speciesName,
                        quantity = line.orderQty,
                        requestedSpecification = listOf(line.quality, line.age, line.size, line.plantType)
                            .filter(String::isNotBlank).joinToString(" / "),
                        unitPrice = line.retailPrice
                    )
                },
                prepaid = saved.isPrepaid,
                shippingFee = saved.shippingFee
            )
            loading = false
            // Preserve direct SMS / email entry, without reopening the external app on recomposition.
            if (!initialHandled) {
                initialHandled = true
                if (initialAction != QuoteDelivery.SAVE) send(initialAction)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "견적을 불러오지 못했습니다."
        } finally {
            loading = false
        }
    }

    QuoteDeliveryScreen(
        state = delivery.copy(
            loading = loading,
            busy = busy,
            editable = editable,
            sent = (quote?.sentAt ?: 0L) > 0L,
            error = error,
            notice = notice
        ),
        onBack = { if (!busy) onDismiss() },
        onMore = { if (!busy) onDetails() },
        onPriceStepChange = { step ->
            if (editable && !busy) delivery = delivery.copy(priceStep = step)
        },
        onUnitPriceChange = { id, price ->
            if (editable && !busy) delivery = delivery.copy(items = delivery.items.map {
                if (it.id == id) it.copy(unitPrice = price) else it
            })
        },
        onPrepaidChange = { prepaid ->
            if (editable && !busy) delivery = delivery.copy(prepaid = prepaid)
        },
        onShippingFeeChange = { fee ->
            if (editable && !busy) delivery = delivery.copy(shippingFee = fee)
        },
        onSendSms = { scope.launch { send(QuoteDelivery.SMS) } },
        onSendEmail = { scope.launch { send(QuoteDelivery.EMAIL) } },
        onMarkDeliveryComplete = {
            if (!busy && !loading && quote?.sentAt == 0L) {
                error = if (quote?.let(::changedFrom) == true)
                    "가격·배송비가 변경되었습니다. 수정된 견적을 먼저 다시 보내주세요." else null
                if (error == null) confirmSent = true
            }
        },
        onEmailChange = { address ->
            delivery = delivery.copy(customer = delivery.customer.copy(email = address))
        }
    )

    if (confirmSent) AlertDialog(
        onDismissRequest = { if (!busy) confirmSent = false },
        title = { Text("고객에게 견적을 보내셨나요?") },
        text = { Text("실제로 전송한 뒤 확인해주세요. 오늘 견적은 최초 발송 시 1건만 집계됩니다.") },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                confirmSent = false
                busy = true
                scope.launch {
                    try {
                        val saved = viewModel.savedQuote(number) ?: kotlin.error("저장된 견적을 찾을 수 없습니다.")
                        check(!changedFrom(saved)) { "변경된 견적을 먼저 다시 보내주세요." }
                        viewModel.markQuoteSent(number)
                        quote = viewModel.savedQuote(number)
                        when (lastOpened) {
                            QuoteDelivery.SMS -> delivery = delivery.copy(smsStatus = QuoteSendingStatus.COMPLETED)
                            QuoteDelivery.EMAIL -> delivery = delivery.copy(emailStatus = QuoteSendingStatus.COMPLETED)
                            else -> Unit
                        }
                        onQuoteSent()
                        notice = "견적 발송을 기록했습니다."
                        error = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "발송 기록 저장에 실패했습니다."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("발송 완료") }
        },
        dismissButton = {
            TextButton(onClick = { confirmSent = false }) { Text("돌아가기") }
        }
    )
}

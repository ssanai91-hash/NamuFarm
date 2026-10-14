package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.SpeakerNotes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.KeyboardType
import java.text.NumberFormat
import java.util.Locale

private val background = Color(0xFFF5F6F3)
private val white = Color.White
private val border = Color(0xFFE3E9E5)
private val green = Color(0xFF2F5D3F)
private val paleGreen = Color(0xFFE3EEE6)
private val blue = Color(0xFF3563A8)
private val paleBlue = Color(0xFFE4ECF8)
private val chipBlue = Color(0xFF2B5287)
private val orange = Color(0xFFC8641F)
private val textPrimary = Color(0xFF1B2A22)
private val textSecondary = Color(0xFF7A8680)

data class QuoteDeliveryCustomer(
    val name: String,
    val phone: String,
    val email: String?,
    val formalQuoteRequested: Boolean,
    val smsRequested: Boolean,
    val emailRequested: Boolean
)

data class QuoteDeliveryItem(
    val id: String,
    val name: String,
    val quantity: Int,
    val requestedSpecification: String,
    val unitPrice: Int
)

enum class QuoteSendingStatus { PENDING, COMPLETED }

data class QuoteDeliveryUiState(
    val orderNumber: String,
    val customer: QuoteDeliveryCustomer,
    val items: List<QuoteDeliveryItem>,
    val priceStep: Int = 1000,
    val prepaid: Boolean = true,
    val shippingFee: Int = 6000,
    val requestNote: String = "",
    val smsStatus: QuoteSendingStatus = QuoteSendingStatus.PENDING,
    val emailStatus: QuoteSendingStatus = QuoteSendingStatus.PENDING,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val editable: Boolean = true,
    val sent: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

private fun won(value: Long) =
    NumberFormat.getNumberInstance(Locale.KOREA).format(value.coerceAtLeast(0)) + "원"

private fun nextPrice(value: Int, delta: Int): Int =
    (value.toLong() + delta).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteDeliveryScreen(
    state: QuoteDeliveryUiState,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onPriceStepChange: (Int) -> Unit,
    onUnitPriceChange: (itemId: String, newPrice: Int) -> Unit,
    onPrepaidChange: (Boolean) -> Unit,
    onShippingFeeChange: (Int) -> Unit,
    onSendSms: () -> Unit,
    onSendEmail: () -> Unit,
    onMarkDeliveryComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onEmailChange: (String) -> Unit = {}
) {
    val itemsTotal = state.items.sumOf {
        it.quantity.coerceAtLeast(0).toLong() * it.unitPrice.coerceAtLeast(0)
    }
    val includedShipping = if (state.prepaid) state.shippingFee.coerceAtLeast(0).toLong() else 0L
    val total = itemsTotal + includedShipping
    val canSend = !state.loading && !state.busy && state.items.isNotEmpty()
    val canEdit = canSend && state.editable && !state.sent

    Scaffold(
        modifier = modifier,
        containerColor = background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로가기", tint = textPrimary)
                    }
                },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("견적 전달", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Text(state.orderNumber, fontSize = 12.sp, color = textSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onMore) {
                        Icon(Icons.Outlined.MoreVert, "더보기", tint = textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background)
            )
        },
        bottomBar = {
            Surface(
                color = white,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                border = BorderStroke(0.5.dp, border)
            ) {
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DeliverySendButton(
                            label = "문자", color = green, status = state.smsStatus,
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Sms, null, tint = white, modifier = Modifier.size(20.dp)) },
                            onClick = onSendSms, enabled = canSend
                        )
                        DeliverySendButton(
                            label = "메일", color = blue, status = state.emailStatus,
                            modifier = Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.MailOutline, null, tint = white, modifier = Modifier.size(20.dp)) },
                            onClick = onSendEmail, enabled = canSend
                        )
                    }
                    Button(
                        onClick = onMarkDeliveryComplete,
                        enabled = canSend && !state.sent,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = orange)
                    ) {
                        Icon(Icons.Outlined.Check, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.sent) "발송 완료로 기록됨" else "견적완료",
                            fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading || state.busy) item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            } }
            state.notice?.let { message -> item {
                Text(message, color = textSecondary, fontSize = 13.sp)
            } }
            item {
                DeliveryCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        DeliveryInfoRow("주문번호") {
                            Text(state.orderNumber, color = textPrimary, fontSize = 16.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DeliveryInfoRow("고객명") {
                            Text(state.customer.name, color = textPrimary, fontSize = 17.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        DeliveryInfoRow("연락처") {
                            Text(displayDeliveryPhone(state.customer.phone),
                                color = textPrimary, fontSize = 17.sp, maxLines = 1)
                        }
                        DeliveryInfoRow("수신방법") {
                            if (state.customer.smsRequested)
                                DeliveryChannelChip("문자", paleGreen, green)
                            if (state.customer.emailRequested)
                                DeliveryChannelChip("메일", paleBlue, chipBlue)
                        }
                        if (state.customer.formalQuoteRequested) {
                            DeliveryInfoRow("견적서") {
                                DeliveryChannelChip("공식견적 요청", paleBlue, chipBlue)
                            }
                        }
                    }
                }
            }
            item {
                DeliveryCard {
                    Column(Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("이메일 수신 주소", color = textSecondary, fontSize = 12.sp)
                        OutlinedTextField(
                            value = state.customer.email.orEmpty(),
                            onValueChange = { onEmailChange(it.take(254)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loading && !state.busy,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            placeholder = { Text("이메일 주소를 입력하세요") }
                        )
                    }
                }
            }
            item {
                DeliveryCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("단가 증감", color = textPrimary, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        listOf(500, 1000, 10000).forEach { amount ->
                            val selected = state.priceStep == amount
                            Surface(
                                onClick = { onPriceStepChange(amount) },
                                enabled = canEdit,
                                shape = RoundedCornerShape(22.dp),
                                color = if (selected) green else white,
                                border = BorderStroke(0.5.dp, if (selected) green else border)
                            ) {
                                Text(if (amount == 10000) "1만원" else won(amount.toLong()),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 9.dp),
                                    color = if (selected) white else textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
            items(state.items, key = { it.id }) { item ->
                DeliveryItemCard(
                    item = item,
                    step = state.priceStep,
                    enabled = canEdit,
                    onPriceChange = { onUnitPriceChange(item.id, it) }
                )
            }
            item {
                DeliveryCard {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("배송비", color = textPrimary, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.background(Color(0xFFEEF1EF), RoundedCornerShape(10.dp))
                            .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                            DeliveryMode("후불", selected = !state.prepaid, enabled = canEdit) { onPrepaidChange(false) }
                            DeliveryMode("선불", selected = state.prepaid, enabled = canEdit) { onPrepaidChange(true) }
                        }
                        if (state.prepaid) {
                            Surface(onClick = {
                                onShippingFeeChange(nextPrice(state.shippingFee, -state.priceStep))
                            }, enabled = canEdit, modifier = Modifier.size(37.dp),
                                shape = CircleShape, color = paleGreen) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("−", color = green, fontSize = 22.sp)
                                }
                            }
                            Text(won(state.shippingFee.toLong()), color = textPrimary,
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Surface(onClick = {
                                onShippingFeeChange(nextPrice(state.shippingFee, state.priceStep))
                            }, enabled = canEdit, modifier = Modifier.size(37.dp),
                                shape = CircleShape, color = paleGreen) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+", color = green, fontSize = 22.sp)
                                }
                            }
                        } else {
                            Text("착불", color = textSecondary, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (!state.editable && !state.loading) item {
                Text("이미 발송·발행·주문된 견적 또는 재고 견적은 가격을 수정할 수 없습니다.",
                    color = textSecondary, fontSize = 12.sp)
            }
            item {
                DeliveryCard {
                    Row(Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.SpeakerNotes, null, tint = textSecondary,
                            modifier = Modifier.size(24.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("요청사항", fontSize = 12.sp, color = textSecondary)
                            Text(state.requestNote.ifBlank { "없음" }, color = textPrimary,
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                Surface(
                    color = Color(0xFFFAF8EC),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(0.5.dp, Color(0xFFECE6C8))
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("최종 견적 금액", fontSize = 15.sp, color = textPrimary,
                                    fontWeight = FontWeight.Medium)
                                Text(if (state.prepaid) "품목 ${won(itemsTotal)} + 배송비 ${won(includedShipping)}"
                                    else "품목 ${won(itemsTotal)} · 배송비 후불(착불), 합계 미포함",
                                    fontSize = 12.sp, color = textSecondary, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Text(won(total), fontSize = 28.sp, fontWeight = FontWeight.Medium,
                                color = green, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFFF3D6))
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Icon(Icons.Outlined.Info, null, tint = Color(0xFF8A5A00),
                                modifier = Modifier.size(18.dp))
                            Text("보낸 뒤 견적완료를 눌러주세요.",
                                fontSize = 12.sp, color = Color(0xFF6B4A0A))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryCard(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = white, border = BorderStroke(0.5.dp, border), content = content)
}


private fun displayDeliveryPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.length == 11 && digits.startsWith("01") ->
            digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7)
        digits.length == 10 && digits.startsWith("01") ->
            digits.substring(0, 3) + "-" + digits.substring(3, 6) + "-" + digits.substring(6)
        else -> raw
    }
}

@Composable
private fun DeliveryInfoRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(84.dp), color = textSecondary, fontSize = 15.sp)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun DeliveryChannelChip(label: String, fill: Color, ink: Color) {
    Surface(shape = RoundedCornerShape(50), color = fill) {
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 8.dp))
    }
}

@Composable
private fun DeliveryPill(value: String, fill: Color, ink: Color) {
    Surface(shape = RoundedCornerShape(50), color = fill) {
        Text(value, color = ink, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable
private fun DeliveryStepButton(increase: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp),
        shape = CircleShape, color = paleGreen) {
        Box(contentAlignment = Alignment.Center) {
            Text(if (increase) "+" else "−",
                fontSize = 21.sp, fontWeight = FontWeight.Medium, color = green)
        }
    }
}

@Composable
private fun DeliveryPrice(value: String, minWidth: Dp = 84.dp) {
    Surface(modifier = Modifier.defaultMinSize(minWidth = minWidth).height(34.dp),
        shape = RoundedCornerShape(10.dp), color = white,
        border = BorderStroke(0.5.dp, border)) {
        Box(Modifier.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
            Text(value, color = textPrimary, fontWeight = FontWeight.Medium,
                fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
private fun DeliveryItemCard(item: QuoteDeliveryItem, step: Int, enabled: Boolean, onPriceChange: (Int) -> Unit) {
    DeliveryCard {
        Column(Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, color = textPrimary, fontSize = 17.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                DeliveryPill("${item.quantity}주", paleGreen, green)
            }
            Text("희망규격: ${item.requestedSpecification}",
                color = textSecondary, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1주 단가", color = textSecondary, fontSize = 14.sp,
                    modifier = Modifier.weight(1f))
                DeliveryStepButton(false, enabled = enabled) {
                    onPriceChange(nextPrice(item.unitPrice, -step))
                }
                DeliveryPrice(won(item.unitPrice.toLong()))
                DeliveryStepButton(true, enabled = enabled) {
                    onPriceChange(nextPrice(item.unitPrice, step))
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = border)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("소계", color = textSecondary, fontSize = 14.sp)
                Text(won(item.quantity.coerceAtLeast(0).toLong() *
                    item.unitPrice.coerceAtLeast(0).toLong()),
                    color = textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DeliveryMode(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp),
        color = if (selected) green else Color.Transparent) {
        Text(label, color = if (selected) white else textSecondary,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp))
    }
}

@Composable
private fun DeliverySendButton(
    label: String,
    status: QuoteSendingStatus,
    color: Color,
    icon: @Composable () -> Unit,
    modifier: Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.height(68.dp),
        shape = RoundedCornerShape(16.dp), color = color) {
        Column(modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                icon()
                Text(label, color = white, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Preview(name = "견적 전달", showBackground = true, widthDp = 390, heightDp = 850)
@Composable
private fun QuoteDeliveryPreview() {
    var state by remember {
        mutableStateOf(
            QuoteDeliveryUiState(
                orderNumber = "NAMU-20261013-0002",
                customer = QuoteDeliveryCustomer(
                    name = "이승열", phone = "010-6538-1792",
                    email = "ssanai91@gmail.com",
                    formalQuoteRequested = true, smsRequested = true, emailRequested = true
                ),
                items = listOf(
                    QuoteDeliveryItem("blueberry", "블루베리", 5, "포트묘 / H0.8~1.0", 1000),
                    QuoteDeliveryItem("silktree", "자귀나무", 10, "H1.5 이상", 1000)
                ),
                shippingFee = 6000, prepaid = true, requestNote = "직접 수령 요청"
            )
        )
    }
    MaterialTheme {
        QuoteDeliveryScreen(
            state = state,
            onBack = {}, onMore = {},
            onPriceStepChange = { state = state.copy(priceStep = it) },
            onUnitPriceChange = { id, price ->
                state = state.copy(items = state.items.map {
                    if (it.id == id) it.copy(unitPrice = price) else it
                })
            },
            onPrepaidChange = { state = state.copy(prepaid = it) },
            onShippingFeeChange = { state = state.copy(shippingFee = it) },
            onSendSms = { state = state.copy(smsStatus = QuoteSendingStatus.COMPLETED) },
            onSendEmail = { state = state.copy(emailStatus = QuoteSendingStatus.COMPLETED) },
            onMarkDeliveryComplete = {}
        )
    }
}

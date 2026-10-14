package com.example.namufarm.ui

import android.widget.Toast
import com.example.namufarm.quotation.QuoteDelivery
import com.example.namufarm.quotation.QuoteIssuerSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.OrderEntity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.ui.components.*
import com.example.namufarm.phone.openSmsComposer

data class ParsedSummaryItem(
    val species: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val qty: Int,
    val retailPrice: Int,
    val farmName: String,
    val wholesalePrice: Int,
    val source: OrderLineSource = OrderLineSource.INVENTORY
)

fun parseSummaryLine(line: String): ParsedSummaryItem? = runCatching {
    val item = com.example.namufarm.domain.order.OrderSummaryCodec.parseStrict(line).single()
    ParsedSummaryItem(
        item.speciesName, item.quality, item.age, item.size, item.plantType,
        item.orderQty, item.retailPrice, item.farmName, item.wholesalePrice, item.source
    )
}.getOrNull()

internal fun isSellingItemInChosung(
    name: String,
    dbChosung: String?,
    target: String
): Boolean {
    if (!dbChosung.isNullOrBlank() && dbChosung == target) return true

    val firstChar = name.firstOrNull() ?: return false
    val charCode = firstChar.code

    if (charCode in 0xAC00..0xD7A3) {
        val chosungIndex = (charCode - 0xAC00) / (21 * 28)
        val chosungArray = arrayOf(
            "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ",
            "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        )

        val calc = chosungArray.getOrNull(chosungIndex) ?: return false
        val normalized = when (calc) {
            "ㄲ" -> "ㄱ"
            "ㄸ" -> "ㄷ"
            "ㅃ" -> "ㅂ"
            "ㅆ" -> "ㅅ"
            "ㅉ" -> "ㅈ"
            else -> calc
        }

        return normalized == target
    }

    return false
}

private fun shouldShowAge(age: String): Boolean {
    val normalized = age
        .trim()
        .replace("년생", "")
        .trim()

    val value = normalized.toFloatOrNull()
    return value == null || value > 0f
}

private fun shouldShowSize(size: String): Boolean {
    val normalized = size
        .trim()
        .replace("m", "")
        .trim()

    val value = normalized.toFloatOrNull()
    return value == null || value > 0f
}

internal fun buildSpecText(
    quality: String? = null,
    age: String = "",
    size: String = "",
    plantType: String = "",
    separator: String = " / "
): String {
    val parts = mutableListOf<String>()

    if (!quality.isNullOrBlank()) parts.add(quality)
    if (age.isNotBlank() && shouldShowAge(age)) parts.add(age)
    if (size.isNotBlank() && shouldShowSize(size)) parts.add(size)
    if (plantType.isNotBlank()) parts.add(plantType)

    return parts.joinToString(separator)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellingModeScreen(viewModel: ManagementViewModel, onNavigateToManagement: () -> Unit, onCallAssist: () -> Unit = {}, onWebInbox: () -> Unit = {}, webUnread: Int = 0, webRequests: List<com.example.namufarm.data.WebQuoteRequestEntity> = emptyList(), webBusy: Boolean = false, onWebSettings: () -> Unit = {}, onRequest: (com.example.namufarm.data.WebQuoteRequestEntity) -> Unit = {},
    onQuoteConfirmed: (String, com.example.namufarm.quotation.SellerQuoteDraft) -> Unit = { _, _ -> },
    onQuoteSentStatus: suspend (String) -> Unit = {},
    onDeleteRequest: (String) -> Unit = {},
    deleteFeedback: com.example.namufarm.viewmodel.RequestDeleteFeedback? = null) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(deleteFeedback?.phase, deleteFeedback?.requestId) {
        if (deleteFeedback?.phase == "success" &&
            uiState.editingRequest?.requestId == deleteFeedback.requestId) {
            viewModel.dismissManualQuote()
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddressSearch by remember { mutableStateOf(false) }

    var showCartDialog by remember { mutableStateOf(false) }
    var showCallLogDialog by remember { mutableStateOf(false) } // 🔥 런처보다 무조건 위에 있어야 함!

    var shippingFee by rememberSaveable { mutableIntStateOf(0) }
    var isPrepaid by rememberSaveable { mutableStateOf(true) } // true: 선불, false: 후불(착불)

    var showTodayQuotesDialog by remember { mutableStateOf(false) }
    var showSales by remember { mutableStateOf(false) }
    var financeOrder by remember { mutableStateOf<OrderEntity?>(null) }
    var showTodayOrdersDialog by remember { mutableStateOf(false) }
    var showQuoteDetailDialog by remember { mutableStateOf(false) }
    var showQuickQuoteDialog by rememberSaveable { mutableStateOf(false) }
    var workbenchSignal by rememberSaveable { mutableIntStateOf(0) }
    var deliveryNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var deliveryAction by rememberSaveable { mutableStateOf(QuoteDelivery.SAVE.name) }

    var showOrderDetailDialog by remember { mutableStateOf(false) }
    var showMoveToManagementConfirm by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }

    var selectedQuote by remember { mutableStateOf<QuoteEntity?>(null) }
    var selectedOrder by remember { mutableStateOf<OrderEntity?>(null) }

    var showDeleteQuoteConfirm by remember { mutableStateOf(false) }
    var showCancelOrderConfirm by remember { mutableStateOf(false) }
    var quoteToDelete by remember { mutableStateOf<QuoteEntity?>(null) }
    var orderToCancel by remember { mutableStateOf<OrderEntity?>(null) }


    val callLogPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.fetchRecentCallLogs(context)
            showCallLogDialog = true // 위에서 이미 선언했으니 이제 완벽하게 인식함!
        } else {
            Toast.makeText(context, "통화 기록 권한을 허용해야 불러올 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
            viewModel.clearMessage()
        }
    }

    fun openRecentCalls() {
        val permission = Manifest.permission.READ_CALL_LOG
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.fetchRecentCallLogs(context)
            showCallLogDialog = true
        } else callLogPermissionLauncher.launch(permission)
    }

    fun sendQuoteSms(phone: String, body: String) {
        openSmsComposer(context, phone, body)?.let(viewModel::showMessage)
    }
    suspend fun markLinkedQuoteSent(number: String) {
        val id = uiState.quotesByRequest.entries.firstOrNull {
            it.value.quoteNumber == number
        }?.key
        if (id != null) onQuoteSentStatus(id)
    }

    SellingHome(
        state = uiState,
        snackbarHostState = snackbarHostState,
        onSync = onWebInbox,
        onManage = onWebSettings,
        onQuickQuote = { viewModel.resetInput(); showQuickQuoteDialog = true },
        onTodayQuotes = { viewModel.loadTodayData(); showTodayQuotesDialog = true },
        onSalesManagement = { showSales = true },
        onSyncOrders = viewModel::syncDataWithGoogleSheet,
        onTodayOrders = { viewModel.loadTodayData(); showTodayOrdersDialog = true },
        onOrder = { selectedOrder = it; showOrderDetailDialog = true },
        onCart = { showCartDialog = true },
        onClearCart = { showClearCartConfirm = true },
        onAddToCart = viewModel::addToCart,
        onCallAssist = onCallAssist,
        onWebInbox = onWebInbox, webUnread = webUnread, webRequests = webRequests, webBusy = webBusy, onRequest = onRequest,
        workbenchSignal = workbenchSignal,
        onSavedQuote = { quote ->
            selectedQuote = quote
            showQuoteDetailDialog = true
        }
    )

    if (showCartDialog) {
        QuoteCartDialog(
            cart = uiState.estimateCart, phone = uiState.customerPhone,
            isPrepaid = isPrepaid, shippingFee = shippingFee, saving = uiState.isSavingQuote,
            snackbarHostState = snackbarHostState,
            onPhone = viewModel::updateCustomerPhone,
            onShipping = { prepaid, fee -> isPrepaid = prepaid; shippingFee = fee },
            onRemove = viewModel::removeCartItem,
            onRecentCalls = ::openRecentCalls,
            onSendSms = { sendQuoteSms(uiState.customerPhone, viewModel.generateQuoteText(isPrepaid, shippingFee)) },
            onSave = {
                viewModel.saveCurrentQuote(isPrepaid, shippingFee) { saved ->
                    showCartDialog = false
                    shippingFee = 0
                    isPrepaid = true
                }
            },
            onDismiss = { showCartDialog = false }, recipient = uiState.quoteRecipient,
            onRecipient = viewModel::updateQuoteRecipient
        )
    }

    if (showCallLogDialog && uiState.recentCallList.isNotEmpty()) {
        Dialog(onDismissRequest = { showCallLogDialog = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                tonalElevation = 2.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "최근 통화",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "견적을 보낼 고객을 선택하세요.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showCallLogDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "닫기")
                        }
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(uiState.recentCallList) { callItem ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateCustomerPhone(callItem.number)
                                        showCallLogDialog = false
                                    },
                                shape = RoundedCornerShape(13.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 11.dp
                                    )
                                ) {
                                    Text(
                                        text = callItem.name?.takeIf { it.isNotBlank() }
                                            ?: callItem.number,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )

                                    if (!callItem.name.isNullOrBlank()) {
                                        Text(
                                            text = callItem.number,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            text = "저장되지 않은 번호",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTodayQuotesDialog) TodayQuotesDialog(
        uiState.todayQuotes,
        onSelect = { selectedQuote = it; showQuoteDetailDialog = true },
        onDismiss = { showTodayQuotesDialog = false }
    )
    if (showTodayOrdersDialog) TodayOrdersDialog(
        uiState.todayOrders.filter { it.orderNumber !in uiState.hiddenOrderNumbers },
        onSelect = { selectedOrder = it; showOrderDetailDialog = true },
        onDismiss = { showTodayOrdersDialog = false },
        onSales={viewModel.loadTodayData();showSales=true}
    )
    if (showQuoteDetailDialog) selectedQuote?.let { selected ->
        val quote = uiState.allQuotes.firstOrNull { it.quoteNumber == selected.quoteNumber } ?: selected
        ConfirmedQuoteSummaryDialog(quote, viewModel) {
            showQuoteDetailDialog = false
        }
    }
    deliveryNumber?.let { number ->
        QuoteDeliveryDialog(viewModel, number, QuoteDelivery.valueOf(deliveryAction),
            onDetails = { selectedQuote = uiState.allQuotes.firstOrNull { it.quoteNumber == number }; deliveryNumber = null; showQuoteDetailDialog = selectedQuote != null },
            onDismiss = { deliveryNumber = null },
            onQuoteSent = { markLinkedQuoteSent(number) })
    }
    if (showOrderDetailDialog) selectedOrder?.let { selected ->
        val order = uiState.allOrders.firstOrNull { it.orderNumber == selected.orderNumber } ?: selected
        OrderDetailsDialog(
            order, snackbarHostState,
            onDelivery = { selectedOrder = order; viewModel.clearJusoResults(); showAddressSearch = true },
            onCancel = { orderToCancel = order; showCancelOrderConfirm = true },
            onDismiss = { showOrderDetailDialog = false },
            onStatus={viewModel.updateOrderStatus(order,it)},onCosts={financeOrder=order}
        )
    }

    if(showSales) SalesDashboardDialog(
        uiState, onReport = viewModel::loadSalesReport,
        onSelect = { selectedOrder = it; showSales = false; showOrderDetailDialog = true },
        onDismiss = { showSales = false },
        onSync = viewModel::syncDataWithGoogleSheet,
        onTodayOrders = { viewModel.loadTodayData(); showSales = false; showTodayOrdersDialog = true }
    )
    financeOrder?.let { selected ->
        val current=uiState.allOrders.find { it.orderNumber==selected.orderNumber } ?: selected
        SaleFinanceDialog(current,onSave={cost,expense,mode,bps,confirmed -> viewModel.updateSaleCosts(current,cost,expense,mode,bps,confirmed);financeOrder=null},onDismiss={financeOrder=null})
    }
    if (showDeleteQuoteConfirm && quoteToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteQuoteConfirm = false },
            title = { Text("견적서 삭제", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("다음 견적서를 삭제하시겠습니까?")
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryLines =
                        quoteToDelete!!.itemSummary.split("\n").filter { it.isNotBlank() }
                    summaryLines.forEach { line ->
                        if (line.contains("|")) {

                            val parsed = parseSummaryLine(line)
                            val species = parsed?.species.orEmpty()
                            val qty = parsed?.qty ?: 0

                            Text(
                                "$species (${qty}주)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                line,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${quoteToDelete!!.customerPhone} | 최종 합계: ${
                            String.format("%,d", quoteToDelete!!.totalPrice)
                        }원",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        quoteToDelete?.let { quote ->
                            val linkedId = uiState.quotesByRequest.entries.firstOrNull {
                                it.value.quoteNumber == quote.quoteNumber
                            }?.key
                            if (linkedId != null) onDeleteRequest(linkedId)
                            else viewModel.deleteQuote(quote)
                        }

                        showDeleteQuoteConfirm = false
                        showQuoteDetailDialog = false
                        selectedQuote = null
                        quoteToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("삭제", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showDeleteQuoteConfirm = false
                    quoteToDelete = null
                }) { Text("취소") }
            }
        )
    }

    if (showMoveToManagementConfirm) {
        AlertDialog(
            onDismissRequest = { showMoveToManagementConfirm = false },
            title = {
                Text(
                    "견적 진행 중",
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text(
                    "견적 바구니를 유지한 채 재고 관리로 이동할까요? 돌아와서 견적 작성을 이어갈 수 있습니다."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCartDialog = false
                        showMoveToManagementConfirm = false
                        onNavigateToManagement()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("관리로 이동", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showMoveToManagementConfirm = false }
                ) {
                    Text("계속 견적", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showCancelOrderConfirm && orderToCancel != null) {
        AlertDialog(
            onDismissRequest = { showCancelOrderConfirm = false },
            title = { Text("주문 취소", fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("주문을 취소하면 차감했던 실제 재고가 한 번 복원됩니다. 수기 견적 품목은 재고 복원에서 제외됩니다.")
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryLines =
                        orderToCancel!!.itemSummary.split("\n").filter { it.isNotBlank() }
                    summaryLines.forEach { line ->
                        if (line.contains("|")) {

                            val parsed = parseSummaryLine(line)
                            val species = parsed?.species.orEmpty()
                            val qty = parsed?.qty ?: 0

                            Text(
                                "$species (${qty}주)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Text(
                                line,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${orderToCancel!!.customerPhone} | 최종 합계: ${
                            String.format("%,d", orderToCancel!!.totalPrice)
                        }원",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        orderToCancel?.let { order ->
                            viewModel.updateOrderStatus(order, "주문취소")
                        }

                        showCancelOrderConfirm = false
                        showOrderDetailDialog = false
                        selectedOrder = null
                        orderToCancel = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("주문취소", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showCancelOrderConfirm = false
                    orderToCancel = null
                }) { Text("닫기") }
            }
        )
    }

    if (showClearCartConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCartConfirm = false },
            title = {
                Text(
                    text = "견적서 초기화",
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Text("현재 장바구니를 비우고 새로 시작할까요?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearCart()
                        viewModel.updateCustomerPhone("")
                        showClearCartConfirm = false
                        showCartDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("비우기", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearCartConfirm = false }
                ) {
                    Text("취소", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAddressSearch && selectedOrder != null) {
        AddressSearchDialog(
            mViewModel = viewModel,
            targetOrder = selectedOrder!!,
            onSaveDelivery = { receiverName, zipNo, roadAddr, detailAddr, note ->
                val currentOrder = selectedOrder ?: return@AddressSearchDialog

                viewModel.updateOrderDeliveryInfo(
                    order = currentOrder,
                    receiverName = receiverName,
                    zipNo = zipNo,
                    roadAddress = roadAddr,
                    detailAddress = detailAddr,
                    note = note
                )

                selectedOrder = currentOrder.copy(
                    receiverName = receiverName,
                    zipNo = zipNo,
                    roadAddress = roadAddr,
                    detailAddress = detailAddr,
                    note = note
                )

                viewModel.clearJusoResults()
                showAddressSearch = false
            },
            onDismiss = {
                viewModel.clearJusoResults()
                showAddressSearch = false
            }
        )
    }

    if (showQuickQuoteDialog || uiState.editingRequest != null) {
        ManualQuoteEditor(uiState.editingRequest, uiState.isSavingQuote, uiState.message,
            onDismiss = { showQuickQuoteDialog = false; viewModel.dismissManualQuote() },
            onDeleteRequest = uiState.editingRequest?.let { request ->
                { onDeleteRequest(request.requestId) }
            },
            onSave = { draft, action ->
                val sourceId = uiState.editingRequest?.requestId
                viewModel.saveManualQuote(draft.validate(action), draft.phone, draft.recipient, draft.prepaid, draft.shipping(),
                    issueOptions = null) { saved ->
                    showQuickQuoteDialog = false; viewModel.dismissManualQuote()
                    workbenchSignal++
                    if (sourceId != null) onQuoteConfirmed(sourceId, draft)
                    if (action == QuoteDelivery.COMPLETE) {
                        // Explicit seller confirmation was collected before saving this quote.
                        scope.launch {
                            try {
                                viewModel.markQuoteSent(saved.quoteNumber)
                                if (sourceId != null) onQuoteSentStatus(sourceId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                viewModel.showMessage("발송완료 기록 실패: ${e.message}")
                                // Keep the saved quote reachable for retry without creating a duplicate.
                                deliveryAction = QuoteDelivery.SAVE.name
                                deliveryNumber = saved.quoteNumber
                            }
                        }
                    } else {
                        deliveryAction = action.name
                        deliveryNumber = saved.quoteNumber
                    }
                }
            })
    }

}

@Composable
fun SellingItemRow(item: SellingDisplayRow, availableQuantity: Int = item.quantity, onAddToCart: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var inputQty by rememberSaveable { mutableIntStateOf(0) }

    val specText = buildSpecText(
        quality = item.quality,
        age = item.age,
        size = item.size,
        plantType = item.plantType,
        separator = " · "
    )

    if (showDialog) {
        NamuDialog(
            title = "수량 선택",
            subtitle = "${item.speciesName} · ${item.farmName}",
            onDismiss = { showDialog = false; inputQty = 0 },
            actions = {
                Button(
                    onClick = { onAddToCart(inputQty); showDialog = false; inputQty = 0 },
                    enabled = inputQty > 0 && inputQty <= availableQuantity,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                ) { Text("견적에 담기") }
            }
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(item.speciesName, style = MaterialTheme.typography.headlineSmall)
                Text(specText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SourceLabel(OrderLineSource.INVENTORY, item.farmName)
                NamuBadge("추가 가능 ${String.format("%,d", availableQuantity)}주")
                Text("판매 단가 ${money(item.retailPrice.toLong())}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = if (inputQty == 0) "" else inputQty.toString(),
                    onValueChange = { value ->
                        if (value.isEmpty()) inputQty = 0
                        else value.toIntOrNull()?.takeIf { it >= 0 }?.let { inputQty = it }
                    },
                    label = { Text("담을 수량 (주)") },
                    placeholder = { Text("0") }, singleLine = true,
                    isError = inputQty > availableQuantity,
                    supportingText = { Text(if (inputQty > availableQuantity) "추가 가능한 수량을 초과했어요" else "예상 금액 ${money(inputQty.toLong() * item.retailPrice)}") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { inputQty = 0 }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("초기화") }
                    listOf(1, 10, 100).forEach { step ->
                        FilledTonalButton(
                            onClick = { inputQty = (inputQty.toLong() + step).coerceAtMost(availableQuantity.toLong()).toInt() },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)
                        ) { Text("+$step") }
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clickable { showDialog = true },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = item.speciesName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (specText.isNotBlank()) {
                        Text(
                            text = specText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${String.format("%,d", item.quantity)}주",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.farmName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "도매 ${String.format("%,d", item.wholesalePrice)}원",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "판매가 · 1주",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format("%,d", item.retailPrice)}원",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun AddressSearchDialog(
    mViewModel: ManagementViewModel,
    targetOrder: OrderEntity,
    onSaveDelivery: (String, String, String, String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by mViewModel.uiState.collectAsState()

    var keyword by remember { mutableStateOf("") }
    var selectedJuso by remember {
        mutableStateOf<com.example.namufarm.data.remote.JusoItem?>(null)
    }
    var detailAddress by remember { mutableStateOf("") }
    var receiverName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(targetOrder.orderNumber) {
        keyword = ""
        selectedJuso = targetOrder.roadAddress.takeIf { it.isNotBlank() }?.let {
            com.example.namufarm.data.remote.JusoItem(it, "", targetOrder.zipNo)
        }
        detailAddress = targetOrder.detailAddress
        receiverName = targetOrder.receiverName
        note = targetOrder.note
        mViewModel.clearJusoResults()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().imePadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "뒤로"
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "배송정보",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = targetOrder.orderNumber,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (selectedJuso == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "주소 검색",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "도로명, 건물명 또는 지번을 입력하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = keyword,
                            onValueChange = {
                                keyword = it
                                mViewModel.searchAddress(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("예: 테헤란로 123") },
                            singleLine = true,
                            shape = RoundedCornerShape(13.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        if (keyword.length >= 2 && uiState.jusoResults.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = "검색 결과가 없습니다.",
                                    modifier = Modifier.padding(18.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 20.dp)
                            ) {
                                items(uiState.jusoResults) { juso ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedJuso = juso },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = juso.roadAddr,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "지번 ${juso.jibunAddr}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "우편번호 ${juso.zipNo}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "배송지 확인",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = selectedJuso!!.roadAddr,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "우편번호 ${selectedJuso!!.zipNo}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = detailAddress,
                            onValueChange = { detailAddress = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("상세주소") },
                            placeholder = { Text("예: 101동 1203호") },
                            shape = RoundedCornerShape(13.dp)
                        )

                        OutlinedTextField(
                            value = receiverName,
                            onValueChange = { receiverName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("받는사람") },
                            shape = RoundedCornerShape(13.dp)
                        )

                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("배송 메모") },
                            placeholder = { Text("예: 경비실 보관") },
                            minLines = 3,
                            maxLines = 4,
                            shape = RoundedCornerShape(13.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedJuso = null
                                    keyword = ""
                                    mViewModel.clearJusoResults()
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("다시 검색")
                            }

                            Button(
                                onClick = {
                                    onSaveDelivery(
                                        receiverName.trim(),
                                        selectedJuso!!.zipNo,
                                        selectedJuso!!.roadAddr,
                                        detailAddress.trim(),
                                        note.trim()
                                    )
                                },
                                enabled = receiverName.isNotBlank(),
                                modifier = Modifier.weight(1.3f).height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("배송정보 저장", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

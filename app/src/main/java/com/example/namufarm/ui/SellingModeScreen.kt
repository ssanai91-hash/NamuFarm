package com.example.namufarm.ui

import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.*
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.namufarm.quotemode.ModeDesign
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.domain.order.ShippingCharge
import com.example.namufarm.viewmodel.ManagementViewModel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check // 혹시 Check로 쓰일 경우 대비
import com.example.namufarm.data.QuoteEntity
import com.example.namufarm.data.OrderEntity
import androidx.compose.foundation.layout.offset
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Refresh // 🚨 동기화 아이콘용
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Cached
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.namufarm.viewmodel.ManagementUiState
import com.example.namufarm.viewmodel.PendingSyncClearStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedSummaryItem(
    val species: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val qty: Int,
    val retailPrice: Int,
    val farmName: String,
    val wholesalePrice: Int
)

/** A plain title: no click modifier, callbacks, or ViewModel access. */
@Composable
internal fun SellingModeTitle() {
    Text(
        text = "나무팜 매매모드",
        fontWeight = FontWeight.ExtraBold,
        color = Color.White
    )
}

@Composable
private fun PendingSyncDialog(
    state: ManagementUiState,
    onSync: () -> Unit,
    onClose: () -> Unit,
    onRequestClear: () -> Unit,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val step = state.pendingSyncClearStep
    if (step == PendingSyncClearStep.NONE) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("전송 대기") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("전송 대기 ${state.pendingSyncCount}건")
                    val oldest = state.oldestPendingAt?.let {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(it))
                    } ?: "없음"
                    Text("가장 오래된 대기 시각: $oldest")
                    state.lastSyncError?.takeIf { it.isNotBlank() }?.let { Text("마지막 동기화 오류: $it") }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onClose) { Text("닫기") }
                        Button(onClick = onSync) { Text("다시 동기화") }
                    }
                    TextButton(onClick = onRequestClear, enabled = state.pendingSyncCount > 0) {
                        Text("문제 해결: 전송 대기 비우기", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        )
    } else {
        val cancelFocus = remember { FocusRequester() }
        val first = step == PendingSyncClearStep.FIRST_CONFIRMATION
        val count = state.pendingSyncDeleteCount
        AlertDialog(
            onDismissRequest = { if (!state.isClearingPendingSync) onCancel() },
            title = { Text(if (first) "전송 대기 비우기" else "전송 대기 최종 확인") },
            text = {
                Text(if (first)
                    "아직 서버에 올라가지 않은 변경 ${count}건이 영구히 삭제됩니다.\n먼저 [다시 동기화]를 시도해 보세요."
                else "전송 대기 ${count}건을 백업한 뒤 영구히 삭제합니다. 계속하시겠습니까?")
            },
            confirmButton = {
                if (first) {
                    TextButton(onClick = onContinue) { Text("계속") }
                } else {
                    Button(
                        onClick = onDelete,
                        enabled = !state.isClearingPendingSync,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("${count}건 삭제") }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel, enabled = !state.isClearingPendingSync,
                    modifier = Modifier.focusRequester(cancelFocus)) { Text("취소") }
                LaunchedEffect(step) { cancelFocus.requestFocus() }
            }
        )
    }
}

fun parseSummaryLine(line: String): ParsedSummaryItem? {
    val parts = line.split("|")

    return when {
        parts.size >= 9 -> {
            ParsedSummaryItem(
                species = parts[0],
                quality = parts[1],
                age = parts[2],
                size = parts[3],
                plantType = parts[4],
                qty = parts[5].toIntOrNull() ?: 0,
                retailPrice = parts[6].toIntOrNull() ?: 0,
                farmName = parts[7],
                wholesalePrice = parts[8].toIntOrNull() ?: 0
            )
        }
        parts.size >= 7 -> {
            // 예전 저장 포맷 호환
            ParsedSummaryItem(
                species = parts[0],
                quality = parts[1],
                age = parts[2],
                size = "",
                plantType = "",
                qty = parts[3].toIntOrNull() ?: 0,
                retailPrice = parts[4].toIntOrNull() ?: 0,
                farmName = parts[5],
                wholesalePrice = parts[6].toIntOrNull() ?: 0
            )
        }
        else -> null
    }
}

private fun isSellingItemInChosung(
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

private fun hasSellingItemsForChosung(
    allItems: List<SellingDisplayRow>,
    chosung: String
): Boolean {
    return allItems.any { item ->
        isSellingItemInChosung(
            name = item.speciesName,
            dbChosung = item.chosung,
            target = chosung
        )
    }
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

private fun buildSpecText(
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
fun SellingModeScreen(viewModel: ManagementViewModel, onNavigateToManagement: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var showAllStock by rememberSaveable { mutableStateOf(true) }
    val displayedStock = if (showAllStock) uiState.allSellingInventory else uiState.filteredSellingInventory

    val scrollState = rememberScrollState()
    val context = LocalContext.current

    var showAddressSearch by remember { mutableStateOf(false) }
    var showPendingSyncDialog by remember { mutableStateOf(false) }

    // 🚨 1. 상태 변수들을 [무조건] 먼저 선언해야 해! (여기가 핵심)
    var showCartDialog by remember { mutableStateOf(false) }
    var showCallLogDialog by remember { mutableStateOf(false) } // 🔥 런처보다 무조건 위에 있어야 함!

    var shippingCharge by remember { mutableStateOf(ShippingCharge()) }
    val shippingFee = shippingCharge.fee
    val isPrepaid = shippingCharge.isPrepaid

    var showTodayQuotesDialog by remember { mutableStateOf(false) }
    var showTodayOrdersDialog by remember { mutableStateOf(false) }
    var showQuoteDetailDialog by remember { mutableStateOf(false) }
    var showQuickQuoteDialog by remember { mutableStateOf(false) }

    var showOrderDetailDialog by remember { mutableStateOf(false) }
    var showMoveToManagementConfirm by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }

    var selectedQuote by remember { mutableStateOf<QuoteEntity?>(null) }
    var selectedOrder by remember { mutableStateOf<OrderEntity?>(null) }

    var showDeleteQuoteConfirm by remember { mutableStateOf(false) }
    var showDeleteOrderConfirm by remember { mutableStateOf(false) }
    var quoteToDelete by remember { mutableStateOf<QuoteEntity?>(null) }
    var orderToDelete by remember { mutableStateOf<OrderEntity?>(null) }
    var showRealDeleteOrderConfirm by remember { mutableStateOf(false) }

    var showNegotiationDialog by remember { mutableStateOf(false) } // 🚨 결재(협상) 창 띄우기용 ㅋㅋㅋ

    // 🚨 2. 변수들이 다 만들어진 [그 다음에] 런처를 달아야 에러가 안 나!
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

    // 🚨 3. 이제 원래 있던 LaunchedEffect들이 이어지면 됨
    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(showTodayQuotesDialog, uiState.todayQuotes.size) {
        if (showTodayQuotesDialog && uiState.todayQuotes.isEmpty()) {
            showTodayQuotesDialog = false
        }
    }

    LaunchedEffect(showTodayOrdersDialog, uiState.todayOrders.size) {
        if (showTodayOrdersDialog && uiState.todayOrders.isEmpty()) {
            showTodayOrdersDialog = false
        }
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val state = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE)

                    if (state == android.telephony.TelephonyManager.EXTRA_STATE_RINGING ||
                        state == android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK) {

                        val number = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER)

                        if (!number.isNullOrBlank()) {
                            viewModel.updateCustomerPhone(number)
                        }
                    }
                }
            }
        }

        val filter = android.content.IntentFilter(android.telephony.TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        context.registerReceiver(receiver, filter)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Scaffold(
        topBar = {
            NamuTopBar("매매", masterSyncLabel(uiState.lastMasterSyncAt), onSync = { viewModel.syncDataWithGoogleSheet() }) {
                if (uiState.pendingSyncCount > 0) TextButton(onClick = { showPendingSyncDialog = true }) {
                    Text("전송 대기 ${uiState.pendingSyncCount}건", fontSize = 13.sp)
                }
            }
        },
        bottomBar = {
            Column(modifier = Modifier.navigationBarsPadding()) {
                // 장바구니 있을 때만 표시
                // 🚨 [수정] 콤팩트 장바구니: 리스트를 숨기고 숫자 카운트로 변경
                if (uiState.estimateCart.isNotEmpty()) {
                    val totalRetail =
                        uiState.estimateCart.sumOf { it.item.retailPrice * it.orderQuantity }

                    Surface(
                        color = Color(0xFF1976D2),
                        shadowElevation = 16.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            // ✅ 타이틀에 주문량 숫자 표시: 장바구니 (N)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "장바구니 (${uiState.estimateCart.size})",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                Text(
                                    text = "${String.format("%,d", totalRetail)}원",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // 하단 버튼 2개 - 이름 변경 및 레이아웃 유지
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 기존 "주문내역 보기 📝" 버튼 찾아서 onClick 수정!
                                Button(
                                    onClick = {
                                        viewModel.updateCustomerPhone("") // 🚨 1. 전화번호 초기화
                                        shippingCharge = ShippingCharge()
                                        showCartDialog = true
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        "주문내역 보기 📝",
                                        color = Color(0xFF1976D2),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                Button(
                                    onClick = { showClearCartConfirm = true },
                                    modifier = Modifier
                                        .weight(0.7f) // 비우기 버튼은 살짝 작게
                                        .height(44.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(
                                            0xFFD32F2F
                                        )
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        "비우기 🗑️", // 🚨 이름 변경
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // 항상 고정 하단 버튼
                // 🚨 [수정] 항상 고정 하단 버튼 구역 (테두리 박스 컨테이너 + 2줄 레이아웃으로 변경!)
                Surface(
                    color = Color.White,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, Color(0xFFE0E0E0)) // 하단 바 전체 구분선
                ) {
                    // 초성 영역과 통일감을 주는 둥근 테두리 박스 추가
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF8F9FA), // 박스 내부 연회색 배경
                        border = BorderStroke(1.dp, Color(0xFFEEEEEE))
                    ) {
                        // 🚨 Row에서 Column으로 변경하여 2줄로 배치!
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 🚨 [1열 - 새로 추가된 상단 버튼 2개]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // ✉️ 견적문자 발송 버튼
                                Button(
                                    onClick = {
                                        // 🚨 2. 입력창 싹 초기화하고 빠른 견적창 띄우기!
                                        viewModel.resetInput()
                                        showQuickQuoteDialog = true
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ModeDesign.quote,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "✉️ 견적문자 발송",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }

                                // 🧾 거래명세서 발송 버튼
                                // 🧾 거래명세서 발송 버튼
                                Button(
                                    onClick = {
                                        // 🚨 누르는 순간 협상창 팝업 온!
                                        showNegotiationDialog = true
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ModeDesign.quote,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "🧾 거래명세서 발송",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            // 🚨 [2열 - 기존 하단 버튼 2개]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 📄 오늘 견적서 버튼
                                Button(
                                    onClick = {
                                        viewModel.loadTodayData()
                                        if (uiState.todayQuotes.isEmpty()) {
                                            viewModel.showMessage("📄 오늘 저장된 견적서가 없습니다.")
                                        } else {
                                            showTodayQuotesDialog = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ModeDesign.neutral,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "📄 오늘 견적서 (${uiState.todayQuotes.size})",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }

                                // 📦 오늘 주문내역 버튼
                                Button(
                                    onClick = {
                                        viewModel.loadTodayData()
                                        if (uiState.todayOrders.isEmpty()) {
                                            viewModel.showMessage("📦 오늘 확정된 주문내역이 없습니다.")
                                        } else {
                                            showTodayOrdersDialog = true
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = ModeDesign.neutral,
                                        contentColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "📦 오늘 주문내역 (${uiState.todayOrders.size})",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFFF2F4F7))
                .verticalScroll(scrollState)
        ) {
            FilterChip(selected = showAllStock, onClick = { showAllStock = true },
                label = { Text("전체 재고 (${uiState.allSellingInventory.size})") },
                modifier = Modifier.padding(horizontal = 8.dp))
            val chosungRows = listOf(
                listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ"),
                listOf("ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
            )

            // 🚨 여기서부터 교체! 기존 Column을 Surface 컨테이너로 변경
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp), // 바깥쪽 배경과 분리되는 여백
                shape = RoundedCornerShape(12.dp), // 둥근 모서리
                color = Color.White,
                border = BorderStroke(1.dp, Color(0xFFE0E0E0)) // 깔끔한 연회색 테두리
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    chosungRows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowItems.forEach { chosung ->
                                val isSelected = !showAllStock && uiState.selectedChosung == chosung
                                val hasItems = hasSellingItemsForChosung(uiState.allSellingInventory, chosung)

                                Button(
                                    onClick = {
                                        if (hasItems) {
                                            showAllStock = false
                                            viewModel.selectChosung(chosung)
                                        }
                                    },
                                    enabled = hasItems,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            isSelected -> Color(0xFF2E7D32)
                                            else -> Color.White
                                        },
                                        contentColor = when {
                                            isSelected -> Color.White
                                            hasItems -> Color(0xFF2E7D32)
                                            else -> Color(0xFFBDBDBD)
                                        },
                                        disabledContainerColor = Color(0xFFF5F5F5),
                                        disabledContentColor = Color(0xFFBDBDBD)
                                    ),
                                    border = BorderStroke(
                                        width = if (hasItems) 1.5.dp else 1.dp,
                                        color = when {
                                            isSelected -> Color(0xFF2E7D32)
                                            hasItems -> Color(0xFF2E7D32)
                                            else -> Color(0xFFE0E0E0)
                                        }
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text(
                                        text = chosung,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 🚨 여기까지 교체!

            Spacer(modifier = Modifier.height(8.dp))
            // ... (이후 if (uiState.filteredSellingInventory.isEmpty()) 부분은 그대로 유지)

            Spacer(modifier = Modifier.height(8.dp))

            if (displayedStock.isEmpty()) {
                Text(
                    if (showAllStock) "등록된 재고가 없습니다. 동기화하거나 농원·묘목에서 재고를 등록해주세요."
                    else "선택한 초성의 재고가 없습니다. 전체 재고를 눌러 확인해주세요.",
                    modifier = Modifier.fillMaxWidth().padding(30.dp),
                    textAlign = TextAlign.Center,
                    color = Color.Gray
                )
            } else {
                displayedStock.forEach { item ->
                    SellingItemRow(item) { qtyToAdd ->
                        viewModel.addToCart(
                            item,
                            qtyToAdd
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    if (showPendingSyncDialog) {
        PendingSyncDialog(
            state = uiState,
            onSync = { viewModel.syncDataWithGoogleSheet() },
            onClose = { viewModel.cancelPendingSyncClear(); showPendingSyncDialog = false },
            onRequestClear = { viewModel.requestPendingSyncClear() },
            onContinue = { viewModel.continuePendingSyncClear() },
            onCancel = { viewModel.cancelPendingSyncClear() },
            onDelete = { viewModel.backupAndClearConfirmedPendingSync(context) }
        )
    }

    // 🚨 1. 견적서 다이얼로그 (문자 전송 및 전화번호 입력 포함)
    if (showCartDialog) {
        Dialog(
            onDismissRequest = { showCartDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false) // 화면 꽉 차게
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 상단 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFE8F5E9))
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📝 나무팜 견적서",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1B5E20)
                        )
                        IconButton(onClick = { showCartDialog = false }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "닫기",
                                tint = Color.Black
                            )
                        }
                    }

                    // 장바구니 리스트 (스크롤 구역)
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        uiState.estimateCart.forEachIndexed { index, cartItem ->
                            val itemPrice = cartItem.item.retailPrice * cartItem.orderQuantity

                            val showAge = shouldShowAge(cartItem.item.age)
                            val showSize = shouldShowSize(cartItem.item.size)

                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = CardDefaults.cardColors(Color.White),
                                border = BorderStroke(0.5.dp, Color(0xFFEEEEEE))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp)
                                ) {
                                    // 🚨 1줄: [묘목명] [품종뱃지] [년생뱃지] [주문량]
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "🌱 ${cartItem.item.speciesName}",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 16.sp,
                                            color = Color.Black
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))

                                        // 품종 뱃지 (년생과 동일한 14sp 적용)
                                        Text(
                                            text = cartItem.item.quality,
                                            fontSize = 11.sp,
                                            color = Color(0xFFE65100),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(Color(0xFFFFF3E0), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )

                                        if (showAge) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = cartItem.item.age,
                                                fontSize = 11.sp,
                                                color = Color(0xFF2E7D32),
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        if (showSize) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = cartItem.item.size,
                                                fontSize = 11.sp,
                                                color = Color(0xFF1565C0),
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(6.dp))

                                        Text(
                                            text = cartItem.item.plantType,
                                            fontSize = 11.sp,
                                            color = Color(0xFF6A1B9A),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(Color(0xFFF3E5F5), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )

                                        Spacer(modifier = Modifier.weight(1f))

                                        // 주문 수량
                                        Text(
                                            text = "${cartItem.orderQuantity}주",
                                            color = Color(0xFF1976D2),
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 17.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 🚨 2줄: [농원명 블루뱃지] 단가: 00원
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = cartItem.item.farmName,
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .background(
                                                    Color(0xFF1976D2),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "단가: ${
                                                String.format(
                                                    "%,d",
                                                    cartItem.item.retailPrice
                                                )
                                            }원",
                                            fontSize = 13.sp,
                                            color = Color.DarkGray,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // 🚨 3줄: 금액: 000원 (왼쪽)  ---------------->  [휴지통 버튼] (오른쪽)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "금액: ${String.format("%,d", itemPrice)}원",
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.Black,
                                            fontSize = 18.sp
                                        )

                                        Spacer(modifier = Modifier.weight(1f))

                                        // 버튼 스타일 휴지통 아이콘
                                        Surface(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clickable { viewModel.removeCartItem(index) },
                                            color = Color(0xFFFFEBEE),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.dp, Color(0xFFFFCDD2))
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "삭제",
                                                    tint = Color(0xFFD32F2F),
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 하단 컨트롤 (택배비, 전화번호, 검색버튼, 전송버튼)
                    Column(
                        modifier = Modifier
                            .background(Color(0xFFFAFAFA))
                            .padding(16.dp)
                    ) {
                        ShippingFeeRow(
                            charge = shippingCharge,
                            onChange = { shippingCharge = it },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // 🚨 [수정] 선불일 때만 총 견적 금액에 택배비 합산!
                        val finalRetail = uiState.estimateCart.sumOf { it.item.retailPrice * it.orderQuantity } + (if(isPrepaid) shippingFee else 0)

                        // 파란 박스: 총액 + 전화번호 입력칸
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFE3F2FD),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "총 견적 금액: ${String.format("%,d", finalRetail)}원",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        textAlign = TextAlign.End
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = uiState.customerPhone,
                                    onValueChange = { viewModel.updateCustomerPhone(it) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        textAlign = TextAlign.Center
                                    ),
                                    placeholder = {
                                        Text(
                                            text = "수신자 전화번호 입력",
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Gray
                                        )
                                    },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF90CAF9),
                                        unfocusedBorderColor = Color(0xFF90CAF9),
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White,
                                        cursorColor = Color.Black
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 버튼 2개를 가로 한 줄
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 🚨 1. 견적서 문자발송 버튼 (스마트 분기 처리)
                            Button(
                                onClick = {
                                    val phoneNum = uiState.customerPhone

                                    if (phoneNum.isNotBlank()) {
                                        // 🚨 괄호 안에 택배비 상태 변수를 쏙 넣어줘!!!
                                        val smsText = viewModel.generateQuoteText(isPrepaid, shippingFee)

                                        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("smsto:$phoneNum")
                                            putExtra("sms_body", smsText)
                                        }
                                        try {
                                            context.startActivity(smsIntent)
                                        } catch (e: Exception) {
                                            Toast.makeText(
                                                context,
                                                "문자 앱을 찾을 수 없습니다.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    // 번호가 비어있다면? -> 통화기록 목록 띄우기! (권한 체크 필수)
                                    else {
                                        val permission = Manifest.permission.READ_CALL_LOG
                                        if (ContextCompat.checkSelfPermission(
                                                context,
                                                permission
                                            ) == PackageManager.PERMISSION_GRANTED
                                        ) {
                                            viewModel.fetchRecentCallLogs(context)
                                            showCallLogDialog = true
                                        } else {
                                            callLogPermissionLauncher.launch(permission)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(
                                        0xFF4CAF50
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "견적서 문자발송",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            // 🚨 2. 견적서 저장 버튼 (번호 필수 검증)
                            Button(
                                onClick = {
                                    val phoneNum = uiState.customerPhone

                                    // 번호가 있어야만 저장 통과!
                                    if (phoneNum.isNotBlank()) {
                                        viewModel.saveCurrentQuote(isPrepaid, shippingFee)
                                        showCartDialog = false
                                    }
                                    // 번호가 없으면 경고 메시지 띄우고 빠꾸!
                                    else {
                                        viewModel.showMessage("⚠️ 견적서를 저장하려면 전화번호를 입력해주세요.")
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(
                                        0xFF1976D2
                                    )
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "견적서 저장",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 🚨 2. 최근 20개 전화번호 목록 선택 다이얼로그 (이름 + 번호 UI 적용!)
    if (showCallLogDialog && uiState.recentCallList.isNotEmpty()) {
        Dialog(onDismissRequest = { showCallLogDialog = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "최근 통화 기록",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = Color(0xFF1B5E20)
                    )
                    Divider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = Color(0xFFEEEEEE)
                    )

                    androidx.compose.foundation.lazy.LazyColumn {
                        items(uiState.recentCallList) { callItem ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateCustomerPhone(callItem.number) // 선택 시 번호만 꽂아줌
                                        showCallLogDialog = false
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp)
                            ) {
                                // 🚨 폰에 이름이 저장되어 있는 경우
                                if (!callItem.name.isNullOrBlank()) {
                                    Text(
                                        text = callItem.name,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = callItem.number,
                                        fontSize = 14.sp,
                                        color = Color.DarkGray
                                    )
                                }
                                // 🚨 저장되지 않은 모르는 번호인 경우
                                else {
                                    Text(
                                        text = callItem.number,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "저장되지 않은 번호",
                                        fontSize = 12.sp,
                                        color = Color(0xFF9E9E9E)
                                    )
                                }
                            }
                            Divider(color = Color(0xFFF5F5F5))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showCallLogDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("닫기", color = Color.Gray, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 🚨 [수정] 오늘 저장된 견적서 목록 팝업 (초경량 1줄 리스트)
    if (showTodayQuotesDialog) {
        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Date())

        Dialog(
            onDismissRequest = { showTodayQuotesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 700.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${todayDate}_총 견적서",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1B5E20),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.todayQuotes) { quote ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedQuote = quote
                                    // 🚨 상세창 열기 전에 기존 저장된 번호를 ViewModel에 동기화!
                                    viewModel.updateCustomerPhone(quote.customerPhone)
                                    showQuoteDetailDialog = true
                                }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    // 🚨 1열: 견적서 번호 (구역 구분용 배경)
                                    Surface(
                                        color = Color(0xFFF5F5F5),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "견적번호 : ${quote.quoteNumber}",
                                            fontSize = 14.sp,
                                            color = Color.Black,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(
                                                horizontal = 8.dp,
                                                vertical = 6.dp
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // 🚨 2열: 전화번호 (있을 때만 렌더링)
                                    if (quote.customerPhone.isNotBlank()) {
                                        Text(
                                            text = "📱 ${quote.customerPhone}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.DarkGray
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }

                                    // 🚨 3열: 묘목명 + 수량 (1줄씩 심플하게 쫙!)
                                    val summaryLines =
                                        quote.itemSummary.split("\n").filter { it.isNotBlank() }
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        summaryLines.forEach { line ->
                                            if (line.contains("|")) {
                                                val item = parseSummaryLine(line) ?: return@forEach

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 2.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = "🌱 ${item.species}",
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black
                                                        )
                                                        Text(
                                                            text = buildSpecText(
                                                                quality = item.quality,
                                                                age = item.age,
                                                                size = item.size,
                                                                plantType = item.plantType
                                                            ),
                                                            fontSize = 11.sp,
                                                            color = Color.Gray
                                                        )
                                                    }

                                                    Text(
                                                        text = "${item.qty}주",
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = Color(0xFF1976D2)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Divider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = Color(0xFFF5F5F5)
                                    )

                                    // 🚨 4열: 배송비 & 최종 합계 (한 줄에 나란히 배치)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.Bottom // 글자 바닥선 맞춤
                                    ) {
                                        // 배송비 텍스트 로직
                                        val shippingText = if (quote.isPrepaid) {
                                            "선불 ${String.format("%,d", quote.shippingFee)}원"
                                        } else {
                                            "후불 (착불)"
                                        }
                                        val shippingColor = if (quote.isPrepaid) Color(0xFF1976D2) else Color(0xFFD32F2F)

                                        Column {
                                            Text(
                                                text = "배송비",
                                                fontSize = 11.sp,
                                                color = Color.Gray,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = shippingText,
                                                fontSize = 13.sp,
                                                color = shippingColor,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }

                                        Text(
                                            text = "최종 합계: ${String.format("%,d", quote.totalPrice)}원",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showTodayQuotesDialog = false },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("닫기", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    // 🚨 [수정] 오늘 확정된 주문내역 목록 팝업 (1줄 압축 버전)
    if (showTodayOrdersDialog) {
        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Date())

        Dialog(
            onDismissRequest = { showTodayOrdersDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 700.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "${todayDate}_주문내역",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF2E7D32),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(uiState.todayOrders) { order ->
                            Card(
                                border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                colors = CardDefaults.cardColors(Color.White),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedOrder = order
                                    showOrderDetailDialog = true
                                }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    // 1열: 주문번호
                                    Surface(
                                        color = Color(0xFFE8F5E9),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "주문번호 : ${order.orderNumber}",
                                                fontSize = 13.sp,
                                                color = Color.Black,
                                                fontWeight = FontWeight.Bold
                                            )

                                            Spacer(modifier = Modifier.weight(1f))

                                            val statusText = if (order.status == "주문취소") "주문취소됨" else "입금확인완료"

                                            Text(
                                                text = statusText,
                                                fontSize = 13.sp,
                                                color = if (order.status == "주문취소") Color(0xFFD32F2F) else Color(0xFF2E7D32),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // 2열: 전화번호 및 주소 (추가됨)
                                    Column {
                                        Text(
                                            text = "📱 ${order.customerPhone}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.DarkGray
                                        )
                                        // 🚨 주소 필드 (추후 DB 연동 대비 UI 배치)
                                        Text(
                                            text = if (order.receiverName.isBlank()) {
                                                "👤 받는사람: 미입력"
                                            } else {
                                                "👤 받는사람: ${order.receiverName}"
                                            },
                                            fontSize = 12.sp,
                                            color = Color.DarkGray
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // 3열: 묘목 정보 (한 줄로 압축!)
                                    val summaryLines =
                                        order.itemSummary.split("\n").filter { it.isNotBlank() }
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        summaryLines.forEach { line ->
                                            val item = parseSummaryLine(line) ?: return@forEach
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "🌱 ${item.species}",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.Black
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                // 상세 정보를 묘목명 바로 옆에 작게 배치
                                                Text(
                                                    text = buildSpecText(
                                                        quality = item.quality,
                                                        age = item.age,
                                                        size = item.size,
                                                        plantType = item.plantType,
                                                        separator = " / "
                                                    ),
                                                    fontSize = 11.sp,
                                                    color = Color.Gray
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                Text(
                                                    text = "${item.qty}주",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color(0xFF2E7D32)
                                                )
                                            }
                                        }
                                    }

                                    Divider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = Color(0xFFF5F5F5)
                                    )

                                    Text(
                                        text = "합계: ${String.format("%,d", order.totalPrice)}원",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.End
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showTodayOrdersDialog = false },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(8.dp)
                    ) { Text("닫기", color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    // 🚨 [수정] 견적서 상세 다이얼로그 (문자전송 사령실 삭제 & UI 다이어트 완료!)
    // 🚨 [수정] 견적서 상세 다이얼로그 (배송비 표시 추가)
    if (showQuoteDetailDialog && selectedQuote != null) {
        val quote = selectedQuote!!

        Dialog(
            onDismissRequest = { showQuoteDetailDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .heightIn(max = 880.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFE8F5E9))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "📝 견적서 상세",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1B5E20)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "견적번호 : ${quote.quoteNumber}",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text(
                            "📱 전화번호: ${quote.customerPhone}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Column(modifier = Modifier.fillMaxWidth()) {
                            val summaryLines =
                                quote.itemSummary.split("\n").filter { it.isNotBlank() }

                            summaryLines.forEach { line ->
                                if (line.contains("|")) {
                                    val item = parseSummaryLine(line) ?: return@forEach

                                    QuoteOrderItemCard(
                                        species = item.species,
                                        quality = item.quality,
                                        specText = buildSpecText(
                                            age = item.age,
                                            size = item.size,
                                            plantType = item.plantType
                                        ),
                                        qty = item.qty,
                                        retailPrice = item.retailPrice,
                                        farmName = item.farmName,
                                        wholesalePrice = item.wholesalePrice,
                                        qtyColor = Color(0xFF1976D2),
                                        retailColor = Color(0xFF1976D2)
                                    )
                                }
                            }
                        }

                        // 🚨 [추가] 배송비 표시 구역
                        Divider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📦 배송비", fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                            // (주의: 데이터베이스 엔티티에 isPrepaid, shippingFee 변수가 있어야 에러가 안 나!)
                            if (quote.isPrepaid) {
                                Text(
                                    text = "선불 ${String.format("%,d", quote.shippingFee)}원",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1976D2)
                                )
                            } else {
                                Text(
                                    text = "후불 (착불)",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFD32F2F)
                                )
                            }
                        }
                        Divider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 12.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                "최종 합계: ${String.format("%,d", quote.totalPrice)}원",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black
                            )
                            Text(
                                "저장일시: ${quote.saveDate}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 하단 3버튼 (입금확인, 견적삭제, 닫기)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                viewModel.convertQuoteToOrder(quote, context)
                                showQuoteDetailDialog = false
                                showTodayOrdersDialog = true
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("입금확인", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                quoteToDelete = quote
                                showDeleteQuoteConfirm = true
                            },
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("견적삭제", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showQuoteDetailDialog = false },
                            modifier = Modifier.weight(0.7f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("닫기", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // 🚨 [수정] 주문서 상세 다이얼로그 (배송비 표시 추가)
    if (showOrderDetailDialog && selectedOrder != null) {
        val order = selectedOrder!!
        Dialog(
            onDismissRequest = { showOrderDetailDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 880.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFE3F2FD))
                            .padding(12.dp), contentAlignment = Alignment.Center
                    ) {
                        // 🚨 [수정] 주문 상세 상단 타이틀 (높이 축소 & 휴지통 디자인 개선)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp)) // 둥근 모서리 추가
                                .padding(horizontal = 14.dp, vertical = 8.dp), // 🚨 위아래 여백을 8.dp로 확 줄임!
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📦 주문내역 상세",
                                fontSize = 17.sp, // 글자 크기도 살짝 컴팩트하게
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF1565C0)
                            )

                            // 🚨 예쁘게 단장한 휴지통 버튼 (배경 + 테두리 추가)
                            Surface(
                                modifier = Modifier
                                    .size(34.dp) // 아담한 사이즈
                                    .clickable {
                                        orderToDelete = order
                                        showRealDeleteOrderConfirm = true
                                    },
                                color = Color(0xFFFFF1F0), // 아주 연한 빨간색 배경
                                shape = RoundedCornerShape(8.dp), // 둥근 버튼
                                border = BorderStroke(1.dp, Color(0xFFFFCDD2)) // 얇은 빨간 테두리
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "주문 영구 삭제",
                                        tint = Color(0xFFD32F2F),
                                        modifier = Modifier.size(18.dp) // 아이콘 크기도 박스에 맞게 축소
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "주문번호 : ${order.orderNumber}",
                                        fontSize = 14.sp,
                                        color = Color.Gray,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.weight(1f))

                                    val statusText = if (order.status == "주문취소") "주문취소" else "입금확인"

                                    Text(
                                        text = statusText,
                                        fontSize = 14.sp,
                                        color = if (order.status == "주문취소") Color(0xFFD32F2F) else Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "📱 고객번호: ${order.customerPhone}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (order.receiverName.isBlank()) {
                                "👤 받는사람: 미입력"
                            } else {
                                "👤 받는사람: ${order.receiverName}"
                            },
                            fontSize = 14.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = if (order.roadAddress.isBlank()) {
                                "📍 배송주소: 미입력"
                            } else {
                                "📍 배송주소: [${order.zipNo}] ${order.roadAddress} ${order.detailAddress}".trim()
                            },
                            fontSize = 14.sp,
                            color = if (order.roadAddress.isBlank()) Color(0xFFD32F2F) else Color.Black,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(modifier = Modifier.fillMaxWidth()) {
                            val summaryLines =
                                order.itemSummary.split("\n").filter { it.isNotBlank() }
                            summaryLines.forEach { line ->
                                val item = parseSummaryLine(line) ?: return@forEach

                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    colors = CardDefaults.cardColors(Color(0xFFF8F9FA)),
                                    border = BorderStroke(0.5.dp, Color(0xFFEEEEEE))
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    "🌱 ${item.species}",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 15.sp
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = buildSpecText(
                                                        quality = item.quality,
                                                        age = item.age,
                                                        size = item.size,
                                                        plantType = item.plantType,
                                                        separator = " | "
                                                    ),
                                                    fontSize = 12.sp,
                                                    color = Color.Gray
                                                )
                                            }
                                            Text(
                                                text = "농원: ${item.farmName}",
                                                fontSize = 11.sp,
                                                color = Color(0xFF1976D2),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                "${item.qty}주",
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color(0xFF2E7D32)
                                            )
                                            Text(
                                                "${String.format("%,d", item.retailPrice)}원",
                                                fontSize = 12.sp,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 🚨 [추가] 배송비 표시 구역
                        Divider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📦 배송비", fontSize = 15.sp, color = Color.DarkGray, fontWeight = FontWeight.Bold)
                            // (주의: 데이터베이스 엔티티에 isPrepaid, shippingFee 변수가 있어야 에러가 안 나!)
                            if (order.isPrepaid) {
                                Text(
                                    text = "선불 ${String.format("%,d", order.shippingFee)}원",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF1976D2)
                                )
                            } else {
                                Text(
                                    text = "후불 (착불)",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFD32F2F)
                                )
                            }
                        }
                        Divider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 12.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                "총 수량: ${order.totalQty}주",
                                fontSize = 14.sp,
                                color = Color(0xFF1976D2),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "최종 합계: ${String.format("%,d", order.totalPrice)}원",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.clearJusoResults()
                                showAddressSearch = true
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("주소입력", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { orderToDelete = order; showDeleteOrderConfirm = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("주문취소", fontSize = 14.sp, fontWeight = FontWeight.Bold) }

                        Button(
                            onClick = { showOrderDetailDialog = false },
                            modifier = Modifier.weight(0.7f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                            shape = RoundedCornerShape(8.dp)
                        ) { Text("닫기", fontSize = 14.sp) }
                    }
                }
            }
        }
    }

    if (showDeleteQuoteConfirm && quoteToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteQuoteConfirm = false },
            title = { Text("🗑️ 견적서 삭제", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("다음 견적서를 삭제하시겠습니까?")
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryLines =
                        quoteToDelete!!.itemSummary.split("\n").filter { it.isNotBlank() }
                    summaryLines.forEach { line ->
                        if (line.contains("|")) {
                            val parts = line.split("|")

                            // 🚨 여기도 직관적인 배열 체크로 교체!
                            val species = if (parts.isNotEmpty()) parts[0] else ""
                            val qty = if (parts.size > 3) parts[3] else "0"

                            Text(
                                "🌱 $species (${qty}주)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                line,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${quoteToDelete!!.customerPhone} | 최종 합계: ${
                            String.format("%,d", quoteToDelete!!.totalPrice)
                        }원",
                        fontSize = 13.sp, color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        quoteToDelete?.let { quote ->
                            // 🚨 견적서는 브로 말대로 영구 삭제가 맞음!
                            viewModel.deleteQuote(quote)
                        }

                        showDeleteQuoteConfirm = false
                        showQuoteDetailDialog = false
                        selectedQuote = null
                        quoteToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("삭제", color = Color.White, fontWeight = FontWeight.Bold) }
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
                    "현재 장바구니에 담긴 견적 내용이 있습니다.\n견적서를 취소하고 관리모드로 이동할까요?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.resetEstimateSession()
                        showCartDialog = false
                        showMoveToManagementConfirm = false
                        onNavigateToManagement()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("취소 후 이동", color = Color.White, fontWeight = FontWeight.Bold)
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

    // 🚨 기존 showDeleteOrderConfirm 블록을 이걸로 교체!
    if (showDeleteOrderConfirm && orderToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteOrderConfirm = false },
            title = { Text("🚫 주문 취소", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("다음 주문을 취소 상태로 변경하시겠습니까?")
                    Spacer(modifier = Modifier.height(8.dp))

                    val summaryLines =
                        orderToDelete!!.itemSummary.split("\n").filter { it.isNotBlank() }
                    summaryLines.forEach { line ->
                        if (line.contains("|")) {
                            val parts = line.split("|")

                            // 🚨 [핵심 해결] 확장함수 다 빼고 직관적인 if문으로 컴파일러 뇌정지 원천 차단!
                            val species = if (parts.isNotEmpty()) parts[0] else ""
                            val qty = if (parts.size > 3) parts[3] else "0"

                            Text(
                                "🌱 $species (${qty}주)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        } else {
                            Text(
                                line,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = Color.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${orderToDelete!!.customerPhone} | 최종 합계: ${
                            String.format("%,d", orderToDelete!!.totalPrice)
                        }원",
                        fontSize = 13.sp, color = Color.Gray
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        orderToDelete?.let { order ->
                            // 🚨 [핵심 변경] 삭제(deleteOrder) 대신 상태변경(updateOrderStatus) 호출!
                            viewModel.updateOrderStatus(order, "주문취소")
                        }

                        showDeleteOrderConfirm = false
                        showOrderDetailDialog = false
                        selectedOrder = null
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("주문취소", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showDeleteOrderConfirm = false
                    orderToDelete = null
                }) { Text("닫기") }
            }
        )
    }

    // 🚨 [추가] 진짜 영구 삭제용 확인 팝업
    if (showRealDeleteOrderConfirm && orderToDelete != null) {
        AlertDialog(
            onDismissRequest = { showRealDeleteOrderConfirm = false },
            title = { Text("🗑️ 주문 영구 삭제", fontWeight = FontWeight.Bold, color = Color.Red) },
            text = {
                Text(
                    "이 작업은 되돌릴 수 없으며,\n구글 시트와 앱에서 이 주문 번호(${orderToDelete!!.orderNumber})가 완전히 사라집니다.\n정말 삭제하시겠습니까?",
                    fontWeight = FontWeight.Bold
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        orderToDelete?.let { order ->
                            // 🚨 뷰모델에 아까 만든 영구삭제 함수 호출!
                            viewModel.deleteOrder(order)
                        }
                        showRealDeleteOrderConfirm = false
                        showOrderDetailDialog = false
                        selectedOrder = null
                        orderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("영구 삭제", color = Color.White, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showRealDeleteOrderConfirm = false
                    orderToDelete = null
                }) { Text("취소") }
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("비우기", color = Color.White, fontWeight = FontWeight.Bold)
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

    // 🚨 3. 수기 견적(빠른 견적) 다이얼로그 추가!
    if (showQuickQuoteDialog) {
        val chosungRows = listOf(
            listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ"),
            listOf("ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
        )
        val qualityOptions = listOf("", "특묘", "상묘", "중묘", "하묘")
        val plantTypes = listOf("", "실생", "접목", "삽목", "근삽")
        val currentAgeInt = uiState.inputAge.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

        Dialog(
            onDismissRequest = { showQuickQuoteDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false) // 꽉 찬 화면
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF2F4F7)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 상단 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1976D2)).padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✍️ 수기 견적 작성 (문자발송용)", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        IconButton(onClick = { showQuickQuoteDialog = false }) {
                            Icon(Icons.Default.Close, "닫기", tint = Color.White)
                        }
                    }

                    Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {

                        // [1] 초성 및 묘목 선택
                        Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("1. 묘목 선택", color = Color(0xFF388E3C), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                Spacer(modifier = Modifier.height(10.dp))

                                // 초성 버튼들
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .border(1.dp, Color(0xFFBCD3B7), RoundedCornerShape(12.dp))
                                        .padding(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    chosungRows.forEach { rowItems ->
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            rowItems.forEach { chosung ->
                                                val isSelected = uiState.selectedChosung == chosung
                                                Button(
                                                    onClick = { viewModel.selectChosung(chosung) },
                                                    modifier = Modifier.weight(1f).height(40.dp),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isSelected) Color(0xFF388E3C) else Color(0xFFF1F8E9)
                                                    ),
                                                    border = BorderStroke(
                                                        if (isSelected) 2.dp else 1.dp,
                                                        if (isSelected) Color(0xFF2E7D32) else Color(0xFF8DB580)
                                                    ),
                                                    contentPadding = PaddingValues(0.dp)
                                                ) { Text(chosung, color = if (isSelected) Color.White else Color(0xFF2E7D32), fontWeight = FontWeight.Bold) }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // 🚨 [수기 견적창 안쪽 묘목 리스트 버튼 부분 교체]
                                val speciesRows = uiState.speciesList.chunked(4)
                                speciesRows.forEach { rowItems ->
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        rowItems.forEach { species ->
                                            val isSelected = uiState.selectedSpecies?.speciesId == species.speciesId
                                            val speciesName = species.speciesName

                                            // 🚨 2. 여기도 똑같이 글자 수별 크기 조절!
                                            val dynamicFontSize = when {
                                                speciesName.length >= 7 -> 9.sp
                                                speciesName.length >= 5 -> 10.sp
                                                else -> 12.sp
                                            }

                                            OutlinedButton(
                                                onClick = { viewModel.selectSpecies(species) },
                                                modifier = Modifier.weight(1f).height(44.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (isSelected) Color(0xFFE8F5E9) else Color.White),
                                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text(
                                                    text = speciesName,
                                                    fontSize = dynamicFontSize,
                                                    maxLines = 1,          // 🚨 강제 1줄
                                                    softWrap = false,      // 🚨 줄바꿈 차단
                                                    letterSpacing = (-0.5).sp, // 🚨 자간 축소
                                                    color = if (isSelected) Color(0xFF2E7D32) else Color.DarkGray,
                                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
                                                )
                                            }
                                        }
                                        if (rowItems.size < 4) {
                                            repeat(4 - rowItems.size) { Spacer(modifier = Modifier.weight(1f)) }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }

                        // [2] 묘목이 선택되었을 때만 열리는 스펙/가격 입력창
                        if (uiState.selectedSpecies != null) {
                            Spacer(modifier = Modifier.height(12.dp))

                            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("2. 스펙 및 단가 입력", color = Color(0xFF0288D1), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // 🚨 1. 년생
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("년생", modifier = Modifier.width(46.dp), fontWeight = FontWeight.Bold)

                                        // 🚨 너비를 46.dp로 늘리고 줄바꿈 원천 차단!
                                        Text(
                                            text = if (currentAgeInt > 0) "${currentAgeInt}년" else "-",
                                            modifier = Modifier.width(46.dp),
                                            color = Color(0xFF673AB7),
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))

                                        OutlinedButton(
                                            onClick = { viewModel.updateAge("") },
                                            modifier = Modifier.weight(1f).height(38.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("선택안함", fontSize = 11.sp, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        Button(
                                            onClick = { viewModel.updateAge("${currentAgeInt + 1}년생") },
                                            modifier = Modifier.weight(1.2f).height(38.dp),
                                            colors = ButtonDefaults.buttonColors(Color(0xFF4CAF50)),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("${currentAgeInt + 1}년생 (+)", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 🚨 2. 크기 (0.0m 공간 확보 & 버튼 압축!)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("크기", modifier = Modifier.width(46.dp), fontWeight = FontWeight.Bold)

                                        // 🚨 0.0m가 짤리지 않게 너비를 46.dp로 넉넉하게!
                                        Text(
                                            text = String.format("%.1fm", uiState.inputSizeMeters),
                                            modifier = Modifier.width(46.dp),
                                            color = Color.Blue,
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))

                                        Button(
                                            onClick = { viewModel.updateSize(0f) },
                                            modifier = Modifier.weight(0.9f).height(38.dp),
                                            colors = ButtonDefaults.buttonColors(Color.Gray),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("초기화", fontSize = 11.sp, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                                        }

                                        // 🚨 버튼 사이 간격을 4.dp -> 2.dp 로 줄여서 공간 확보!
                                        Spacer(modifier = Modifier.width(2.dp))

                                        Button(
                                            onClick = { viewModel.updateSize(uiState.inputSizeMeters + 0.1f) },
                                            modifier = Modifier.weight(1f).height(38.dp),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("+10cm", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                                        }

                                        Spacer(modifier = Modifier.width(2.dp))

                                        Button(
                                            onClick = { viewModel.updateSize(uiState.inputSizeMeters + 1.0f) },
                                            modifier = Modifier.weight(0.9f).height(38.dp),
                                            colors = ButtonDefaults.buttonColors(Color(0xFF0288D1)),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text("+1m", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, letterSpacing = (-0.5).sp)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 타입
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("타입", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                                        plantTypes.forEach { type ->
                                            val label = if (type.isEmpty()) "없음" else type
                                            val isSelected = uiState.inputPlantType == type
                                            Button(
                                                onClick = { viewModel.updatePlantType(type) },
                                                modifier = Modifier.weight(1f).height(36.dp).padding(end = 4.dp),
                                                colors = ButtonDefaults.buttonColors(if (isSelected) Color(0xFF8D6E63) else Color.LightGray),
                                                contentPadding = PaddingValues(0.dp)
                                            ) { Text(label, fontSize = 11.sp) }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 품질
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("품질", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                                        qualityOptions.forEach { q ->
                                            val label = if (q.isEmpty()) "없음" else q
                                            val isSelected = uiState.inputQuality == q
                                            Button(
                                                onClick = { viewModel.updateQuality(q) },
                                                modifier = Modifier.weight(1f).height(36.dp).padding(end = 4.dp),
                                                colors = ButtonDefaults.buttonColors(if (isSelected) Color(0xFF009688) else Color.LightGray),
                                                contentPadding = PaddingValues(0.dp)
                                            ) { Text(label, fontSize = 11.sp) }
                                        }
                                    }

                                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                                    // 🚨 수량 (0 버튼 축소, +1, +10 추가)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("수량(주)", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold)
                                        Text("${uiState.inputQuantity}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.Red, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

                                        Button(onClick = { viewModel.resetQuantityToSoldOut() }, modifier = Modifier.width(42.dp).height(40.dp), colors = ButtonDefaults.buttonColors(Color.Gray), contentPadding = PaddingValues(0.dp)) { Text("0", fontSize = 13.sp) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(onClick = { viewModel.addQuantity(1) }, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+1", fontSize = 13.sp) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(onClick = { viewModel.addQuantity(10) }, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+10", fontSize = 13.sp) }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))

                                    // 🚨 소매가/단가 (0 버튼 축소, +100, +1000 추가 & 잘림 방지용 Padding 제거)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("단가(원)", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold)
                                        Text("${String.format("%,d", uiState.inputRetail)}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.Blue, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)

                                        Button(onClick = { viewModel.resetRetail() }, modifier = Modifier.width(42.dp).height(40.dp), colors = ButtonDefaults.buttonColors(Color.Gray), contentPadding = PaddingValues(0.dp)) { Text("0", fontSize = 13.sp) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(onClick = { viewModel.addRetail(100) }, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+100", fontSize = 13.sp) }
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Button(onClick = { viewModel.addRetail(1000) }, modifier = Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+1,000", fontSize = 13.sp) }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 🚨 하단 2버튼 (담기 계속하기 / 장바구니 확인)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // [담기] 버튼 (왼쪽)
                                Button(
                                    onClick = {
                                        if (uiState.inputQuantity > 0 && uiState.inputRetail > 0) {
                                            val dummyItem = SellingDisplayRow(
                                                farmId = -1,
                                                speciesId = uiState.selectedSpecies!!.speciesId ?: -1,
                                                farmName = "수기견적",
                                                speciesName = uiState.selectedSpecies!!.speciesName,
                                                quality = uiState.inputQuality,
                                                age = uiState.inputAge,
                                                size = if (uiState.inputSizeMeters > 0) "${String.format("%.1f", uiState.inputSizeMeters)}m" else "",
                                                plantType = uiState.inputPlantType,
                                                quantity = 999999,
                                                wholesalePrice = 0,
                                                retailPrice = uiState.inputRetail,
                                                chosung = uiState.selectedSpecies!!.chosung
                                            )

                                            viewModel.addToCart(dummyItem, uiState.inputQuantity)

                                            // 🚨 입력창 싹 초기화 (창은 안 닫히고 다음 묘목 고를 수 있음!)
                                            viewModel.resetInput()
                                            viewModel.updateAge("")
                                            viewModel.updateSize(0f)
                                            viewModel.updatePlantType("")
                                            viewModel.updateQuality("")
                                            viewModel.showMessage("✅ 담기 완료! 다른 묘목을 선택하세요.")
                                        } else {
                                            viewModel.showMessage("⚠️ 수량과 단가를 입력해주세요.")
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.buttonColors(Color(0xFFE91E63)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("🛒 담기", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                }

                                // [장바구니 확인] 버튼 (오른쪽)
                                Button(
                                    onClick = {
                                        showQuickQuoteDialog = false // 수기 창 닫고
                                        showCartDialog = true  // 메인 장바구니 창 띄우기
                                    },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    colors = ButtonDefaults.buttonColors(Color(0xFF1976D2)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("📝 장바구니 확인", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 🚨 [추가] 오현주 님과 협상 전용 팝업창 ㅋㅋㅋㅋ
    if (showNegotiationDialog) {
        AlertDialog(
            onDismissRequest = { showNegotiationDialog = false },
            title = {
                Text("🚨 권한 부족 (접근 제한)", fontWeight = FontWeight.ExtraBold, color = Color(0xFFD32F2F))
            },
            text = {
                Text(
                    "오현주와 협상!! 💬\n\n이 기능을 사용하려면\n먼저 협상을 완료해야 합니다 ㅎㅎ",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showNegotiationDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("협상하러 가기 🏃‍♂️💨", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showNegotiationDialog = false }
                ) {
                    Text("깨갱... (닫기)")
                }
            }
        )
    }
}

// 🚨 개별 묘목 전시 UI & 커스텀 수량 입력 다이얼로그 (브로의 확정 레이아웃 적용!)
@Composable
fun SellingItemRow(item: SellingDisplayRow, onAddToCart: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var inputQty by remember { mutableStateOf(0) }

    val showAge = shouldShowAge(item.age)
    val showSize = shouldShowSize(item.size)
    val dialogSpecText = buildSpecText(
        quality = item.quality,
        age = item.age,
        size = item.size,
        plantType = item.plantType
    )

    if (showDialog) {
        Dialog(
            onDismissRequest = {
                showDialog = false
                inputQty = 0
            }
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "장바구니 담기",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = item.speciesName,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1B5E20)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = dialogSpecText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "현재 재고: ${String.format("%,d", item.quantity)}주",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFD32F2F)
                    )

                    Divider(
                        modifier = Modifier.padding(vertical = 16.dp),
                        color = Color(0xFFEEEEEE)
                    )

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format("%,d", inputQty),
                            fontSize = 40.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF1976D2)
                        )

                        Text(
                            text = " 주",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { inputQty = 0 },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(
                                text = "초기화",
                                color = Color(0xFFD32F2F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        listOf(1, 10, 100).forEach { step ->
                            Button(
                                onClick = {
                                    inputQty =
                                        if (inputQty + step <= item.quantity) {
                                            inputQty + step
                                        } else {
                                            item.quantity
                                        }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = ButtonDefaults.buttonColors(Color(0xFFE8F5E9)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    text = "+$step",
                                    color = Color(0xFF2E7D32),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showDialog = false
                                inputQty = 0
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFFBDBDBD))
                        ) {
                            Text(
                                text = "취소",
                                fontSize = 16.sp,
                                color = Color.DarkGray,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                if (inputQty > 0) {
                                    onAddToCart(inputQty)
                                    showDialog = false
                                    inputQty = 0
                                }
                            },
                            modifier = Modifier
                                .weight(1.5f)
                                .height(54.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1976D2)
                            )
                        ) {
                            Text(
                                text = "장바구니 담기",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { showDialog = true },
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            // 🚨 1행: 묘목명(굵게) + 품질뱃지 + 년생뱃지(옅은녹색) / 우측: 재고량(빨강 굵게)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 묘목명
                Text(
                    text = item.speciesName,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.width(8.dp))

                // 품질 뱃지
                Text(
                    text = item.quality,
                    fontSize = 11.sp,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFFFFF3E0), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                if (showAge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.age,
                        fontSize = 11.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFFE8F5E9), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (showSize) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.size,
                        fontSize = 11.sp,
                        color = Color(0xFF1565C0),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = item.plantType,
                    fontSize = 11.sp,
                    color = Color(0xFF6A1B9A),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFFF3E5F5), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                // 재고량
                Text(
                    text = "재고량: ${String.format("%,d", item.quantity)}주",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFFD32F2F)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 🚨 2행: 농원뱃지 + 도매가(회색) / 우측: 판매가(검정)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 농원 뱃지
                Text(
                    text = item.farmName,
                    fontSize = 10.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFF1976D2), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))

                // 🚨 도매가 (플랫폼 입장에서 눈에 덜 띄게 회색으로 처리!)
                Text(
                    text = "도매가: ${String.format("%,d", item.wholesalePrice)}원",
                    color = Color.Gray, // 기존 파란색에서 회색으로 변경
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.weight(1f))

                // 판매가 (검정색)
                Text(
                    text = "판매가: ${String.format("%,d", item.retailPrice)}원",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun QuoteOrderItemCard(
    species: String,
    quality: String,
    specText: String,
    qty: Int,
    retailPrice: Int,
    farmName: String,
    wholesalePrice: Int,
    qtyColor: Color,
    retailColor: Color
) {
    val itemTotal = qty * retailPrice

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(Color(0xFFFAFAFA)),
        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 1~2행 통합: 왼쪽 정보 / 오른쪽 주문수량
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 1행
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🌱 $species",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = quality,
                            fontSize = 11.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(Color(0xFFFFF3E0), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                        if (specText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = specText,
                                fontSize = 11.sp,
                                color = Color(0xFF455A64),
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .background(Color(0xFFCFD8DC), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2행
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = farmName,
                            fontSize = 10.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color(0xFF1976D2), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "도매가: ${String.format("%,d", wholesalePrice)}원",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "주문수량",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "${qty}주",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = qtyColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 3행: 판매가 오른쪽 정렬
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "판매가: ${String.format("%,d", retailPrice)}원",
                    fontSize = 13.sp,
                    color = retailColor,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 4행: 합계 오른쪽 정렬
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "합계: ${String.format("%,d", itemTotal)}원",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black
                )
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
    var selectedJuso by remember { mutableStateOf<com.example.namufarm.data.remote.JusoItem?>(null) }

    var detailAddress by remember { mutableStateOf("") }
    var receiverName by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(targetOrder.orderNumber) {
        keyword = ""
        selectedJuso = null
        detailAddress = targetOrder.detailAddress
        receiverName = targetOrder.receiverName
        note = targetOrder.note
        mViewModel.clearJusoResults()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.padding(16.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "뒤로")
                    }
                    Text(
                        text = "배송정보 입력",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF1565C0)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedJuso == null) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = {
                            keyword = it
                            mViewModel.searchAddress(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("도로명 또는 지번 입력") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.jusoResults) { juso ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedJuso = juso },
                                colors = CardDefaults.cardColors(Color(0xFFF8F9FA))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "[${juso.zipNo}]",
                                        fontSize = 12.sp,
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = juso.roadAddr,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "지번: ${juso.jibunAddr}",
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF5F5F5)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "[${selectedJuso!!.zipNo}]",
                                fontSize = 12.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = selectedJuso!!.roadAddr,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black
                            )
                            Text(
                                text = selectedJuso!!.jibunAddr,
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = detailAddress,
                        onValueChange = { detailAddress = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("상세주소") },
                        placeholder = { Text("예: 101동 1203호") },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = receiverName,
                        onValueChange = { receiverName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("받는사람 이름") },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // ✅ 비고란 2줄
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("비고 / 요청사항") },
                        placeholder = { Text("예: 경비실 보관, 오전 배송 요청") },
                        minLines = 2,
                        maxLines = 2,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

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
                            modifier = Modifier.weight(1f).height(50.dp)
                        ) {
                            Text("주소 다시검색")
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
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) {
                            Text("저장", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

package com.example.namufarm.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import com.example.namufarm.data.SellingDisplayRow
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
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddressSearch by remember { mutableStateOf(false) }

    // 🚨 1. 상태 변수들을 [무조건] 먼저 선언해야 해! (여기가 핵심)
    var showCartDialog by remember { mutableStateOf(false) }
    var showCallLogDialog by remember { mutableStateOf(false) } // 🔥 런처보다 무조건 위에 있어야 함!

    var shippingFee by remember { mutableIntStateOf(0) }
    var isPrepaid by remember { mutableStateOf(true) } // true: 선불, false: 후불(착불)

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
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
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
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "나무팜",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            text = "판매 · 견적/주문",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    FilledTonalIconButton(
                        onClick = { viewModel.syncDataWithGoogleSheet() },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Google Sheet 동기화"
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    OutlinedButton(
                        onClick = {
                            if (uiState.estimateCart.isNotEmpty()) {
                                showMoveToManagementConfirm = true
                            } else {
                                onNavigateToManagement()
                            }
                        },
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(end = 10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "관리",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.navigationBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.estimateCart.isNotEmpty()) {
                        val totalRetail = uiState.estimateCart.sumOf {
                            it.item.retailPrice * it.orderQuantity
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "견적 바구니",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                        )
                                        Text(
                                            "${uiState.estimateCart.size}개 품목",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(
                                        "${String.format("%,d", totalRetail)}원",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            viewModel.updateCustomerPhone("")
                                            shippingFee = 0
                                            isPrepaid = true
                                            showCartDialog = true
                                        },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                        shape = RoundedCornerShape(11.dp)
                                    ) {
                                        Text("견적 확인", fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { showClearCartConfirm = true },
                                        modifier = Modifier.height(46.dp),
                                        shape = RoundedCornerShape(11.dp),
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
                                        ),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Text("비우기", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        NamuFarmQuickAction(
                            label = "빠른 견적",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.resetInput()
                                showQuickQuoteDialog = true
                            }
                        )
                        NamuFarmQuickAction(
                            label = "오늘 견적 ${uiState.todayQuotes.size}",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.loadTodayData()
                                if (uiState.todayQuotes.isEmpty()) {
                                    viewModel.showMessage("오늘 저장된 견적서가 없습니다.")
                                } else {
                                    showTodayQuotesDialog = true
                                }
                            }
                        )
                        NamuFarmQuickAction(
                            label = "오늘 주문 ${uiState.todayOrders.size}",
                            modifier = Modifier.weight(1f),
                            emphasized = true,
                            onClick = {
                                viewModel.loadTodayData()
                                if (uiState.todayOrders.isEmpty()) {
                                    viewModel.showMessage("오늘 확정된 주문내역이 없습니다.")
                                } else {
                                    showTodayOrdersDialog = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState)
                .padding(top = 10.dp, bottom = 110.dp)
        ) {
            val chosungRows = listOf(
                listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ"),
                listOf("ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "판매 재고",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "초성을 선택하면 판매 가능한 묘목만 보여줍니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${uiState.filteredSellingInventory.size}종",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    chosungRows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { chosung ->
                                val selected = uiState.selectedChosung == chosung
                                val available = hasSellingItemsForChosung(
                                    uiState.allSellingInventory,
                                    chosung
                                )

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clickable(enabled = available) {
                                            viewModel.selectChosung(chosung)
                                        },
                                    shape = RoundedCornerShape(19.dp),
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.primary
                                        available -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            selected -> MaterialTheme.colorScheme.primary
                                            available -> MaterialTheme.colorScheme.outlineVariant
                                            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                        }
                                    )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = chosung,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                selected -> MaterialTheme.colorScheme.onPrimary
                                                available -> MaterialTheme.colorScheme.onSurfaceVariant
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.filteredSellingInventory.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 30.dp, horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "판매 가능한 재고가 없습니다",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "다른 초성을 선택하거나 관리 화면에서 재고를 등록해주세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                uiState.filteredSellingInventory.forEach { item ->
                    SellingItemRow(item) { qtyToAdd ->
                        viewModel.addToCart(item, qtyToAdd)
                    }
                }
            }
        }
    }

    // 🚨 1. 견적서 다이얼로그 (문자 전송 및 전화번호 입력 포함)
    if (showCartDialog) {
        Dialog(
            onDismissRequest = { showCartDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false) // 화면 꽉 차게
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 12.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "견적 작성",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "${uiState.estimateCart.size}개 품목을 확인하고 저장합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                )
                            }
                            IconButton(onClick = { showCartDialog = false }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "닫기",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
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
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "배송비",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isPrepaid && shippingFee > 0) {
                                        Text(
                                            text = "${String.format("%,d", shippingFee)}원",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else if (!isPrepaid) {
                                        Text(
                                            text = "착불",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    if (isPrepaid) {
                                        FilledTonalButton(
                                            onClick = { isPrepaid = true },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("선불", fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { isPrepaid = true },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("선불")
                                        }
                                    }

                                    if (!isPrepaid) {
                                        FilledTonalButton(
                                            onClick = {
                                                isPrepaid = false
                                                shippingFee = 0
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("착불", fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                isPrepaid = false
                                                shippingFee = 0
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("착불")
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            isPrepaid = true
                                            shippingFee += 1000
                                        },
                                        modifier = Modifier.weight(1f).height(42.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("+1,000", fontWeight = FontWeight.Bold)
                                    }

                                    IconButton(
                                        onClick = { shippingFee = 0 },
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "배송비 초기화"
                                        )
                                    }
                                }
                            }
                        }

                        val finalRetail = uiState.estimateCart.sumOf {
                            it.item.retailPrice * it.orderQuantity
                        } + if (isPrepaid) shippingFee else 0

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Text(
                                        text = "최종 견적",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${String.format("%,d", finalRetail)}원",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                OutlinedTextField(
                                    value = uiState.customerPhone,
                                    onValueChange = { viewModel.updateCustomerPhone(it) },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Phone
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    label = { Text("고객 전화번호") },
                                    placeholder = { Text("전화번호 입력 또는 최근 통화에서 선택") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val phoneNum = uiState.customerPhone

                                    if (phoneNum.isNotBlank()) {
                                        val smsText = viewModel.generateQuoteText(
                                            isPrepaid,
                                            shippingFee
                                        )

                                        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("smsto:$phoneNum")
                                            putExtra("sms_body", smsText)
                                        }

                                        try {
                                            context.startActivity(smsIntent)
                                        } catch (e: Exception) {
                                            viewModel.showMessage("문자 앱을 찾을 수 없습니다.")
                                        }
                                    } else {
                                        val permission = Manifest.permission.READ_CALL_LOG
                                        if (
                                            ContextCompat.checkSelfPermission(
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
                                modifier = Modifier.weight(1f).height(52.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "문자 보내기",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = {
                                    if (uiState.customerPhone.isNotBlank()) {
                                        viewModel.saveCurrentQuote(
                                            isPrepaid,
                                            shippingFee
                                        )
                                        showCartDialog = false
                                    } else {
                                        viewModel.showMessage(
                                            "견적서를 저장하려면 전화번호를 입력해주세요."
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1.15f).height(52.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "견적 저장",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.ExtraBold
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

    // 🚨 [수정] 오늘 저장된 견적서 목록 팝업 (초경량 1줄 리스트)
    if (showTodayQuotesDialog) {
        val todayDate = java.text.SimpleDateFormat(
            "yyyy-MM-dd",
            java.util.Locale.KOREA
        ).format(java.util.Date())

        Dialog(
            onDismissRequest = { showTodayQuotesDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 760.dp),
                tonalElevation = 2.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "오늘 견적",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${todayDate} · ${uiState.todayQuotes.size}건",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showTodayQuotesDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "닫기")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(bottom = 4.dp)
                    ) {
                        items(uiState.todayQuotes) { quote ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedQuote = quote
                                        viewModel.updateCustomerPhone(quote.customerPhone)
                                        showQuoteDetailDialog = true
                                    },
                                shape = RoundedCornerShape(15.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = quote.quoteNumber,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${String.format("%,d", quote.totalPrice)}원",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    if (quote.customerPhone.isNotBlank()) {
                                        Text(
                                            text = quote.customerPhone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    val summaryLines = quote.itemSummary
                                        .split("\n")
                                        .filter { it.isNotBlank() }

                                    summaryLines.take(3).forEach { line ->
                                        val item = parseSummaryLine(line) ?: return@forEach

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.species,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = buildSpecText(
                                                        quality = item.quality,
                                                        age = item.age,
                                                        size = item.size,
                                                        plantType = item.plantType,
                                                        separator = " · "
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = "${item.qty}주",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    if (summaryLines.size > 3) {
                                        Text(
                                            text = "외 ${summaryLines.size - 3}개 품목",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    val shippingText =
                                        if (quote.isPrepaid && quote.shippingFee > 0) {
                                            "배송비 선불 ${String.format("%,d", quote.shippingFee)}원"
                                        } else {
                                            "배송비 착불"
                                        }

                                    Text(
                                        text = shippingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showTodayQuotesDialog = false },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("닫기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 🚨 [수정] 오늘 확정된 주문내역 목록 팝업 (1줄 압축 버전)
    if (showTodayOrdersDialog) {
        val todayDate = java.text.SimpleDateFormat(
            "yyyy-MM-dd",
            java.util.Locale.KOREA
        ).format(java.util.Date())

        Dialog(
            onDismissRequest = { showTodayOrdersDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 760.dp),
                tonalElevation = 2.dp,
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "오늘 주문",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${todayDate} · ${uiState.todayOrders.size}건",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showTodayOrdersDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "닫기")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(bottom = 4.dp)
                    ) {
                        items(uiState.todayOrders) { order ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedOrder = order
                                        showOrderDetailDialog = true
                                    },
                                shape = RoundedCornerShape(15.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = order.orderNumber,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.weight(1f)
                                        )

                                        val canceled = order.status == "주문취소"
                                        Text(
                                            text = if (canceled) "주문취소" else "입금확인",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (canceled) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = order.customerPhone.ifBlank { "전화번호 미입력" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = order.receiverName.ifBlank { "주문자 미입력" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = "${String.format("%,d", order.totalPrice)}원",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }

                                    val summaryLines = order.itemSummary
                                        .split("\n")
                                        .filter { it.isNotBlank() }

                                    summaryLines.take(3).forEach { line ->
                                        val item = parseSummaryLine(line) ?: return@forEach
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.species,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${item.qty}주",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    if (summaryLines.size > 3) {
                                        Text(
                                            text = "외 ${summaryLines.size - 3}개 품목",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showTodayOrdersDialog = false },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("닫기", fontWeight = FontWeight.Bold)
                    }
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
                            if (quote.isPrepaid && quote.shippingFee > 0) {
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

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = {
                            if (quote.customerPhone.isBlank()) {
                                viewModel.showMessage("전화번호가 없어 문자를 보낼 수 없습니다.")
                            } else {
                                val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("smsto:${quote.customerPhone}")
                                    putExtra(
                                        "sms_body",
                                        viewModel.generateQuoteTextFromEntity(quote)
                                    )
                                }

                                try {
                                    context.startActivity(smsIntent)
                                } catch (e: Exception) {
                                    viewModel.showMessage("문자 앱을 찾을 수 없습니다.")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "견적 문자 다시 보내기",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            Text(
                                text = "주문 상세",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "주문 기록은 삭제하지 않고 상태로 관리합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                            )
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
                                "주문자: 미입력"
                            } else {
                                "주문자: ${order.receiverName}"
                            },
                            fontSize = 14.sp,
                            color = Color.DarkGray,
                            fontWeight = FontWeight.Bold
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
                            if (order.isPrepaid && order.shippingFee > 0) {
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
                            val parsed = parseSummaryLine(line)
                            val species = parsed?.species.orEmpty()
                            val qty = parsed?.qty ?: 0

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
                            val parsed = parseSummaryLine(line)
                            val species = parsed?.species.orEmpty()
                            val qty = parsed?.qty ?: 0

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
        val currentAgeInt = uiState.inputAge
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull() ?: 0

        Dialog(
            onDismissRequest = { showQuickQuoteDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "빠른 견적",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "재고 등록 없이 묘목과 단가를 직접 입력합니다.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                )
                            }
                            IconButton(onClick = { showQuickQuoteDialog = false }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "닫기",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(11.dp)
                            ) {
                                Text(
                                    text = "묘목 선택",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )

                                chosungRows.forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rowItems.forEach { chosung ->
                                            val selected = uiState.selectedChosung == chosung

                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(38.dp)
                                                    .clickable {
                                                        viewModel.selectChosung(chosung)
                                                    },
                                                shape = RoundedCornerShape(19.dp),
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    }
                                                )
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = chosung,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (selected) {
                                                            MaterialTheme.colorScheme.onPrimary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (uiState.speciesList.isEmpty()) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "선택한 초성의 묘목이 없습니다.",
                                            modifier = Modifier.padding(14.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                } else {
                                    uiState.speciesList.chunked(4).forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowItems.forEach { species ->
                                                val selected =
                                                    uiState.selectedSpecies?.speciesId == species.speciesId

                                                Surface(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 46.dp)
                                                        .clickable {
                                                            viewModel.selectSpecies(species)
                                                        },
                                                    shape = RoundedCornerShape(11.dp),
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (selected) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.outlineVariant
                                                        }
                                                    )
                                                ) {
                                                    Box(
                                                        modifier = Modifier.padding(
                                                            horizontal = 5.dp,
                                                            vertical = 8.dp
                                                        ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = species.speciesName,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            maxLines = 2,
                                                            textAlign = TextAlign.Center,
                                                            fontWeight = if (selected) {
                                                                FontWeight.ExtraBold
                                                            } else {
                                                                FontWeight.Medium
                                                            },
                                                            color = if (selected) {
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurfaceVariant
                                                            }
                                                        )
                                                    }
                                                }
                                            }

                                            repeat(4 - rowItems.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.selectedSpecies != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "규격 · 단가",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = uiState.selectedSpecies!!.speciesName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.width(72.dp).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (currentAgeInt > 0) {
                                                        "${currentAgeInt}년생"
                                                    } else {
                                                        "연생 없음"
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.updateAge("") },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp)
                                        ) {
                                            Text("미지정", style = MaterialTheme.typography.labelMedium)
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.updateAge("${currentAgeInt + 1}년생")
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp)
                                        ) {
                                            Text("+1년", fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.width(72.dp).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = if (uiState.inputSizeMeters > 0f) {
                                                        String.format(
                                                            "%.1fm",
                                                            uiState.inputSizeMeters
                                                        )
                                                    } else {
                                                        "크기 없음"
                                                    },
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { viewModel.updateSize(0f) },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp)
                                        ) {
                                            Text("초기화", style = MaterialTheme.typography.labelMedium)
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.updateSize(
                                                    uiState.inputSizeMeters + 0.1f
                                                )
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp)
                                        ) {
                                            Text("+10cm", fontWeight = FontWeight.Bold)
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                viewModel.updateSize(
                                                    uiState.inputSizeMeters + 1f
                                                )
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 5.dp)
                                        ) {
                                            Text("+1m", fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    Text(
                                        text = "재배 타입",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        plantTypes.forEach { type ->
                                            val selected = uiState.inputPlantType == type
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(40.dp)
                                                    .clickable {
                                                        viewModel.updatePlantType(type)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    }
                                                )
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = if (type.isBlank()) "미지정" else type,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = if (selected) {
                                                            FontWeight.Bold
                                                        } else {
                                                            FontWeight.Medium
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = "품질",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        qualityOptions.forEach { quality ->
                                            val selected = uiState.inputQuality == quality
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(40.dp)
                                                    .clickable {
                                                        viewModel.updateQuality(quality)
                                                    },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (selected) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                border = BorderStroke(
                                                    1.dp,
                                                    if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    }
                                                )
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = if (quality.isBlank()) {
                                                            "미지정"
                                                        } else {
                                                            quality
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = if (selected) {
                                                            FontWeight.Bold
                                                        } else {
                                                            FontWeight.Medium
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "수량",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${String.format("%,d", uiState.inputQuantity)}주",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.resetQuantityToSoldOut() },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("0")
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { viewModel.addQuantity(1) },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("+1")
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { viewModel.addQuantity(10) },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("+10")
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "판매 단가",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "${String.format("%,d", uiState.inputRetail)}원",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.resetRetail() },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("0")
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { viewModel.addRetail(100) },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("+100")
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        FilledTonalButton(
                                            onClick = { viewModel.addRetail(1000) },
                                            modifier = Modifier.height(42.dp),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Text("+1,000")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    showQuickQuoteDialog = false
                                    showCartDialog = true
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "견적 확인",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Button(
                                onClick = {
                                    if (
                                        uiState.selectedSpecies != null &&
                                        uiState.inputQuantity > 0 &&
                                        uiState.inputRetail > 0
                                    ) {
                                        viewModel.addManualQuoteItem(
                                            species = uiState.selectedSpecies!!,
                                            quality = uiState.inputQuality,
                                            age = uiState.inputAge,
                                            size = if (
                                                uiState.inputSizeMeters > 0f
                                            ) {
                                                "${String.format("%.1f", uiState.inputSizeMeters)}m"
                                            } else {
                                                ""
                                            },
                                            plantType = uiState.inputPlantType,
                                            quantity = uiState.inputQuantity,
                                            retailPrice = uiState.inputRetail
                                        )
                                        viewModel.resetInput()
                                        viewModel.updateAge("")
                                        viewModel.updateSize(0f)
                                        viewModel.updatePlantType("")
                                        viewModel.updateQuality("")
                                        viewModel.showMessage(
                                            "견적에 담았습니다. 다음 묘목을 선택하세요."
                                        )
                                    } else {
                                        viewModel.showMessage(
                                            "묘목, 수량, 단가를 확인해주세요."
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1.2f).height(50.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    "견적에 담기",
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun NamuFarmQuickAction(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    if (emphasized) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(11.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(11.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SellingItemRow(item: SellingDisplayRow, onAddToCart: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var inputQty by remember { mutableIntStateOf(0) }

    val specText = buildSpecText(
        quality = item.quality,
        age = item.age,
        size = item.size,
        plantType = item.plantType,
        separator = " · "
    )

    if (showDialog) {
        Dialog(
            onDismissRequest = {
                showDialog = false
                inputQty = 0
            }
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "수량 선택",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = item.speciesName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (specText.isNotBlank()) {
                            Text(
                                text = specText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = item.farmName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "재고 ${String.format("%,d", item.quantity)}주",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${String.format("%,d", item.retailPrice)}원",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format("%,d", inputQty),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "주",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { inputQty = 0 },
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("초기화")
                        }

                        listOf(1, 10, 100).forEach { step ->
                            FilledTonalButton(
                                onClick = {
                                    inputQty = (inputQty + step).coerceAtMost(item.quantity)
                                },
                                modifier = Modifier.weight(1f).height(46.dp),
                                shape = RoundedCornerShape(11.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("+$step", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showDialog = false
                                inputQty = 0
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("취소", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (inputQty > 0) {
                                    onAddToCart(inputQty)
                                    showDialog = false
                                    inputQty = 0
                                }
                            },
                            enabled = inputQty > 0,
                            modifier = Modifier.weight(1.6f).height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("견적에 담기", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                        text = "판매가",
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

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = species,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val detail = listOf(quality, specText)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")

                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = farmName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${qty}주",
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
                Text(
                    text = "도매 ${String.format("%,d", wholesalePrice)}원",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "판매가 ${String.format("%,d", retailPrice)}원",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${String.format("%,d", itemTotal)}원",
                        style = MaterialTheme.typography.titleMedium,
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
        Surface(
            modifier = Modifier.fillMaxSize(),
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
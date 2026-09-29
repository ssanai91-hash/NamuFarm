package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.ui.components.*
import com.example.namufarm.viewmodel.ManagementUiState

/** Presentation only: Room-backed state and business actions stay in the ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SellingHome(
    state: ManagementUiState,
    snackbarHostState: SnackbarHostState,
    onSync: () -> Unit,
    onManage: () -> Unit,
    onQuickQuote: () -> Unit,
    onTodayQuotes: () -> Unit,
    onTodayOrders: () -> Unit,
    onCart: () -> Unit,
    onClearCart: () -> Unit,
    onAddToCart: (SellingDisplayRow, Int) -> Unit,
    onCallAssist: () -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var initial by rememberSaveable { mutableStateOf("전체") }
    val inventory = remember(state.allSellingInventory, query, initial) {
        filterSellingInventory(state.allSellingInventory, query, initial)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(query, initial) { listState.scrollToItem(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Park, null, Modifier.size(28.dp))
                        Column {
                            Text("나무팜", style = MaterialTheme.typography.titleLarge)
                            Text("NAMUFARM", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = onCallAssist) { Icon(Icons.Outlined.PhoneInTalk, "전화 고객 연결") }
                    IconButton(onClick = onSync) { Icon(Icons.Outlined.CloudSync, "재고·주문 동기화") }
                    TextButton(onClick = onManage, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("관리")
                    }
                }
            )
        },
        bottomBar = {
            if (state.estimateCart.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("견적 바구니 · ${state.estimateCart.size}품목", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = onClearCart) { Text("비우기", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Button(onClick = onCart, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
                            Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(money(state.estimateCart.sumOf { it.item.retailPrice.toLong() * it.orderQuantity }), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("견적 확인")
                            Icon(Icons.Outlined.ChevronRight, null)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "work") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeAction("빠른 견적", "수기 작성", Icons.Outlined.EditNote, Modifier.weight(1f), onQuickQuote)
                    HomeAction("오늘 견적", "${state.todayQuotes.size}건", Icons.Outlined.Description, Modifier.weight(1f), onTodayQuotes)
                    HomeAction("오늘 주문", "${state.todayOrders.size}건", Icons.AutoMirrored.Outlined.ReceiptLong, Modifier.weight(1f), onTodayOrders)
                }
            }
            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NamuSectionTitle("판매 재고", "묘목을 선택해 견적에 담아보세요")
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; initial = "전체" },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("묘목명 또는 농원명 검색") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    ChosungPicker(initial, { initial = it }, includeAll = true) { chosung ->
                        state.allSellingInventory.any { isSellingItemInChosung(it.speciesName, it.chosung, chosung) }
                    }
                    Text(
                        "${inventory.map { it.speciesId }.distinct().size}종 · ${inventory.size}개 재고",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (inventory.isEmpty()) {
                item(key = "empty") {
                    NamuEmptyState(
                        if (query.isNotBlank()) "검색 결과가 없어요" else "판매 가능한 재고가 없어요",
                        if (query.isNotBlank()) "묘목명이나 농원명을 다시 확인해주세요." else "다른 초성을 선택하거나 관리에서 재고를 등록해주세요."
                    )
                }
            }
            items(inventory, key = { sellingRowKey(it) }) { item ->
                val inCart = state.estimateCart.filter {
                    it.source == OrderLineSource.INVENTORY && sellingRowKey(it.item) == sellingRowKey(item)
                }.sumOf { it.orderQuantity.toLong() }
                SellingItemRow(item, (item.quantity.toLong() - inCart).coerceAtLeast(0).toInt()) { onAddToCart(item, it) }
            }
            item(key = "offline") {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PhonelinkRing, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("인터넷 없이도 견적을 저장할 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeAction(title: String, value: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun filterSellingInventory(items: List<SellingDisplayRow>, query: String, initial: String): List<SellingDisplayRow> {
    val term = query.trim()
    return items.filter {
        it.quantity > 0 &&
            (initial == "전체" || isSellingItemInChosung(it.speciesName, it.chosung, initial)) &&
            (term.isEmpty() || it.speciesName.contains(term, ignoreCase = true) || it.farmName.contains(term, ignoreCase = true))
    }
}

internal fun sellingRowKey(row: SellingDisplayRow): String =
    listOf(row.farmId.toString(), row.speciesId.toString(), row.quality, row.age, row.size, row.plantType)
        .joinToString("") { "${it.length}:$it" }

package com.example.namufarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.sales.SalePolicy
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.ManagementUiState
import java.math.BigDecimal
import java.util.Date

private fun saleMoney(value: Long) = String.format(java.util.Locale.KOREA,"%,d원",value)

@Composable
internal fun SaleFinanceDialog(order: OrderEntity,onSave:(Long,Long,String,Int,Boolean)->Unit,onDismiss:()->Unit) {
    var cost by remember(order.orderNumber) { mutableStateOf(order.costTotal.toString()) }
    var expense by remember(order.orderNumber) { mutableStateOf(order.expenseTotal.toString()) }
    var mode by remember(order.orderNumber) { mutableStateOf(order.profitMode) }
    var rate by remember(order.orderNumber) { mutableStateOf(BigDecimal(order.commissionBps).movePointLeft(2).toPlainString()) }
    var confirmed by remember(order.orderNumber) { mutableStateOf(order.costConfirmed) }
    val c=cost.toLongOrNull(); val e=expense.toLongOrNull()
    val bps=runCatching { BigDecimal(rate).movePointRight(2).intValueExact() }.getOrNull()
    val valid=c!=null && e!=null && c in 0..SalePolicy.MAX_SAFE && e in 0..SalePolicy.MAX_SAFE && c<=SalePolicy.MAX_SAFE-e && bps!=null && bps in 0..10000
    val profit=if(valid) (if(mode=="commission") order.totalPrice.toLong()*bps!!/10000 else order.totalPrice.toLong()-c!!) - e!! else null
    NamuDialog("원가·비용 확인","매출이익 계산 기준",onDismiss,actions={
        Button(onClick={onSave(if(mode=="commission") 0 else c!!,e!!,mode,bps!!,confirmed)},enabled=valid,modifier=Modifier.fillMaxWidth()) {Text("저장")}
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("매출 ${saleMoney(order.totalPrice.toLong())} · 부가세 ${saleMoney(order.taxAmount)}")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=mode=="resale",onClick={mode="resale"},label={Text("매입 판매")})
                FilterChip(selected=mode=="commission",onClick={mode="commission"},label={Text("판매 수수료")})
            }
            if(mode=="resale") OutlinedTextField(cost,{if(it.all(Char::isDigit) && it.length<=16)cost=it},label={Text("매입 원가 합계 (원)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            else OutlinedTextField(rate,{rate=it},label={Text("판매 수수료율 (%)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(expense,{if(it.all(Char::isDigit) && it.length<=16)expense=it},label={Text("배송·포장 등 비용 합계 (원)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
            Text(if(mode=="commission") "수수료 = 매출 × 수수료율 (원 미만 버림). 매출이익 = 수수료 − 비용." else "매출이익 = 매출 − 매입 원가 − 비용. 원가는 재고의 매입가로 미리 채워두며 실제 금액을 확인해주세요.")
            profit?.let {Text("계산된 매출이익 ${saleMoney(it)}",style=MaterialTheme.typography.titleLarge)}
            Row {Checkbox(checked=confirmed,onCheckedChange={confirmed=it});Text("원가와 비용을 확인했습니다.",Modifier.padding(top=12.dp))}
            Text("부가세와 회사 전체 운영비는 매출이익에 포함하지 않습니다. 판매 완료 전에 금액을 확인해주세요.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun SalesDashboardDialog(
    state: ManagementUiState,
    onReport: (String, String, String) -> Unit,
    onSelect: (OrderEntity) -> Unit,
    onDismiss: () -> Unit,
    onSync: () -> Unit = {},
    onTodayOrders: () -> Unit = {}
) {
    val today = remember { SalePolicy.period(Date().time, "day") }
    var unit by remember { mutableStateOf("month") }
    var from by remember { mutableStateOf(today.take(7) + "-01") }
    var to by remember { mutableStateOf(today) }
    var filter by remember { mutableStateOf("배송 대기") }
    val workOrders = state.allOrders.filter { it.orderNumber !in state.hiddenOrderNumbers }
    val pendingOrders = workOrders.count { it.status in setOf("구매확정", "입금확인") }
    val rows = workOrders.filter { filter == "전체" || it.status in setOf("구매확정", "입금확인") }
        .sortedByDescending { it.orderDate }

    NamuDialog("매출 · 이익 분석", "판매 통계와 전체 주문 조회", onDismiss) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("주문 · 배송 관리", style = MaterialTheme.typography.titleLarge)
                    Text("입금확인된 주문 ${workOrders.size}건 · 배송 대기 ${pendingOrders}건",
                        style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSync, modifier = Modifier.weight(1f)) { Text("주문 동기화") }
                        OutlinedButton(onClick = onTodayOrders, modifier = Modifier.weight(1f)) { Text("오늘 주문") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("배송 대기", "전체").forEach { label ->
                            FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                        }
                    }
                }
            }
            if (rows.isEmpty()) item {
                Text(if (filter == "전체") "등록된 주문이 없습니다." else "배송 대기 중인 주문이 없습니다.")
            }
            items(rows, key = { it.orderNumber }) { order ->
                Card(onClick = { onSelect(order) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${if (order.status == "구매확정") "입금확인" else order.status} · ${order.orderDate}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("${order.customerName} ${order.customerPhone}")
                        Text("${order.totalQty}주 · ${saleMoney(order.totalPrice.toLong() + order.taxAmount)}")
                        if (!order.costConfirmed) Text("원가·비용 확인 전", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("매출 · 이익 분석", style = MaterialTheme.typography.titleLarge)
                Text("배송완료 등 최종 처리된 판매 내역을 기간별로 조회합니다.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("day" to "일", "month" to "월", "year" to "년").forEach { (value, label) ->
                        FilterChip(selected = unit == value, onClick = { unit = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(from, { from = it }, label = { Text("시작일 (예: 2026-10-01)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(to, { to = it }, label = { Text("종료일 (예: 2026-10-31)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { onReport(unit, from, to) }, enabled = !state.salesBusy,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.salesBusy) "전송 대기 반영·조회 중…" else "시트 판매 통계 조회")
                }
                Text("완료된 판매만 집계합니다. 취소·미입금·배송 중 주문은 포함하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                state.message?.let { Text(it) }
            }
            state.salesReport?.let { report ->
                item { Text("${report.from} ~ ${report.to}", style = MaterialTheme.typography.titleMedium) }
                if (report.buckets.isEmpty()) item { Text("이 기간에 완료된 판매가 없습니다.") }
                items(report.buckets, key = { it.period }) { b ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(b.period, style = MaterialTheme.typography.titleMedium)
                            Text("판매 ${b.orders}건 · ${b.quantity}주")
                            Text("매출 ${saleMoney(b.sales)} · 부가세 ${saleMoney(b.tax)}")
                            Text("원가 ${saleMoney(b.cost)} · 비용 ${saleMoney(b.expenses)}")
                            Text("확인된 매출이익 ${saleMoney(b.knownProfit)}")
                            if (b.unknownProfitOrders > 0)
                                Text("원가 미확인 ${b.unknownProfitOrders}건은 매출이익에서 제외했습니다.")
                        }
                    }
                }
            }
        }
    }
}

package com.example.namufarm.ui.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementUiState

@Composable
internal fun StockSpecificationList(state: ManagementUiState, onBack: () -> Unit,
    onEdit: (InventoryDisplayRow) -> Unit, onNew: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FarmSubHeader(state.selectedSpecies?.speciesName.orEmpty(),
            "${state.selectedFarm?.farmName.orEmpty()} · 규격 ${state.inventoryList.size}개 · 합계 ${farmNumber(state.inventoryList.sumOf { it.quantity.toLong() })}주", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("규격을 누르면 수량·가격을 고칠 수 있어요", fontSize = 13.sp, color = NamuColors.muted) }
            if (state.inventoryList.isEmpty()) item { NamuEmptyState("아직 등록된 재고가 없어요") }
            items(state.inventoryList) { row ->
                val low = row.quantity <= NamuColors.LOW_STOCK
                NamuCard(onClick = { onEdit(row) }) {
                    Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stockSpec(row.age, row.size).ifBlank { "규격 없음" }, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("${farmNumber(row.quantity)}주" + if (low) " 남음" else "", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, color = if (low) Color(0xFF9A2A22) else NamuColors.text)
                            }
                            Text(listOf(row.plantType, row.quality).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "타입·품질 없음" },
                                fontSize = 14.sp, color = Color(0xFF4A5A52))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("도매 ${farmNumber(row.wholesalePrice)}원 · 소매", fontSize = 14.sp, color = Color(0xFF4A5A52), modifier = Modifier.weight(1f, fill = false))
                                Text("${farmNumber(row.retailPrice)}원", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        FarmChevron()
                    }
                }
            }
        }
        FarmFooter { NamuPrimaryButton("새 규격 재고 입력", onNew, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add) }
    }
}

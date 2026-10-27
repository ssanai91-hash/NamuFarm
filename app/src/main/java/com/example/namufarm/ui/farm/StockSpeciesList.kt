package com.example.namufarm.ui.farm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementUiState

@Composable
internal fun StockSpeciesList(state: ManagementUiState, initial: String, search: String, onSelect: (SpeciesEntity) -> Unit) {
    val species = state.allSpeciesList.filter { (it.isActive ?: 1) == 1 &&
        namuMatchesInitial(it.speciesName, it.chosung, initial) && namuSearchMatches(it.speciesName, search) }.sortedBy { it.speciesName }
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (species.isEmpty()) NamuEmptyState("조건에 맞는 묘목이 없어요")
        else NamuCard {
            LazyColumn(contentPadding = PaddingValues(horizontal = 4.dp)) {
                itemsIndexed(species, key = { _, item -> item.speciesId ?: item.speciesName }) { index, item ->
                    val rows = state.allSellingInventory.filter { it.farmId == state.selectedFarm?.farmId && it.speciesId == item.speciesId }
                    val total = rows.sumOf { it.quantity.toLong() }
                    val enabled = state.selectedFarm != null
                    val low = enabled && rows.isNotEmpty() && total <= NamuColors.LOW_STOCK
                    val detail = when {
                        !enabled -> "농원을 먼저 선택하세요"
                        rows.isEmpty() -> "재고 없음 · 눌러서 입력"
                        low -> "${rows.size}개 규격 · ${farmNumber(total)}주 남음"
                        else -> "${rows.size}개 규격 · ${farmNumber(total)}주"
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelect(item) }
                        .heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.speciesName, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(detail, fontSize = 14.sp, fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
                                color = if (!enabled || rows.isEmpty()) Color(0xFF9AA59F) else if (low) Color(0xFF9A2A22) else Color(0xFF4A5A52))
                        }
                        FarmChevron()
                    }
                    if (index < species.lastIndex) HorizontalDivider(color = Color(0xFFEDF1EE))
                }
            }
        }
    }
}

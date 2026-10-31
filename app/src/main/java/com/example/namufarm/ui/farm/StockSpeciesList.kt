package com.example.namufarm.ui.farm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val farmId = state.selectedFarm?.farmId
    val stockedIds = state.allSellingInventory.filter { it.farmId == farmId && farmId != null }.map { it.speciesId }.toSet()
    // A farm with stock opens on its stocked species only; '다른 묘목에 재고 입력' shows every species.
    var showAll by rememberSaveable(farmId) { mutableStateOf(false) }
    val filtering = search.isNotBlank() || initial != "전체"
    val matching = state.allSpeciesList.filter { (it.isActive ?: 1) == 1 &&
        namuMatchesInitial(it.speciesName, it.chosung, initial) && namuSearchMatches(it.speciesName, search) }
        .sortedWith(compareBy({ it.speciesId !in stockedIds }, { it.speciesName })) // Stocked species first.
    val stockedOnly = farmId != null && stockedIds.isNotEmpty() && !showAll && !filtering
    val species = if (stockedOnly) matching.filter { it.speciesId in stockedIds } else matching
    val others = matching.size - species.size
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
                        .heightIn(min = 48.dp).padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NamuSpeciesTile(item.speciesName, item.category)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.speciesName, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(detail, fontSize = 14.sp, fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
                                color = if (!enabled || rows.isEmpty()) Color(0xFF9AA59F) else if (low) Color(0xFF9A2A22) else Color(0xFF4A5A52))
                        }
                        FarmChevron()
                    }
                    if (index < species.lastIndex) HorizontalDivider(color = Color(0xFFEDF1EE))
                }
                // No description line above the list (사용자 확정 시안); the toggle lives at the bottom.
                val toggle = if (stockedOnly && others > 0) "다른 묘목에 재고 입력 · ${others}종" to true
                    else if (!stockedOnly && !filtering && farmId != null && stockedIds.isNotEmpty()) "재고 있는 묘목만 보기" to false else null
                if (toggle != null) item(key = "stock-toggle") {
                    OutlinedButton(onClick = { showAll = toggle.second },
                        modifier = Modifier.fillMaxWidth().padding(8.dp).heightIn(min = 50.dp),
                        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, NamuColors.primary)) {
                        Text(toggle.first, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NamuColors.primary)
                    }
                }
            }
        }
    }
}

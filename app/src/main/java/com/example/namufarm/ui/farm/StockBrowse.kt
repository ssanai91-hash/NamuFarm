package com.example.namufarm.ui.farm

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementUiState

/*
 * 재고 탭 농원별·묘목별 보기 (2026-10-31 사용자 확정 시안).
 * Display only: reads the local farm/species/inventory lists. Saving still goes through the existing editor.
 */

internal const val STOCK_BY_FARM = "farm"
internal const val STOCK_BY_SPECIES = "species"

// English initials follow how the letter is read in Korean (JB → 제이 → ㅈ).
private val LATIN_INITIALS = mapOf('A' to "ㅇ", 'B' to "ㅂ", 'C' to "ㅅ", 'D' to "ㄷ", 'E' to "ㅇ", 'F' to "ㅇ", 'G' to "ㅈ",
    'H' to "ㅇ", 'I' to "ㅇ", 'J' to "ㅈ", 'K' to "ㅋ", 'L' to "ㅇ", 'M' to "ㅇ", 'N' to "ㅇ", 'O' to "ㅇ", 'P' to "ㅍ",
    'Q' to "ㅋ", 'R' to "ㅇ", 'S' to "ㅇ", 'T' to "ㅌ", 'U' to "ㅇ", 'V' to "ㅂ", 'W' to "ㄷ", 'X' to "ㅇ", 'Y' to "ㅇ", 'Z' to "ㅈ")

internal fun stockInitialOf(name: String, db: String? = null): String {
    val first = (db?.takeIf { it.isNotBlank() } ?: name).trim().firstOrNull() ?: return "ㅇ"
    if (first.code in 0xAC00..0xD7A3 || first in NAMU_INITIALS.joinToString("")) return namuInitials(first.toString())
    return LATIN_INITIALS[first.uppercaseChar()] ?: "ㅇ"
}

@Composable
internal fun StockModeToggle(mode: String, onMode: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.5.dp, NamuColors.primary), color = Color.White) {
        Row(Modifier.fillMaxWidth().height(46.dp)) {
            listOf(Triple(STOCK_BY_FARM, "농원별", Icons.Outlined.Storefront), Triple(STOCK_BY_SPECIES, "묘목별", Icons.Outlined.Park))
                .forEach { (value, label, icon) -> StockModeButton(label, icon, mode == value, Modifier.weight(1f)) { onMode(value) } }
        }
    }
}

@Composable
private fun StockModeButton(label: String, icon: ImageVector, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), color = if (active) NamuColors.primary else Color.White) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (active) Color.White else NamuColors.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (active) Color.White else NamuColors.primary)
        }
    }
}

/** 14 initials, 7 per row, always fully visible (no search box, no 전체). */
@Composable
internal fun StockChosungGrid(selected: String, available: (String) -> Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        NAMU_INITIALS.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { initial ->
                    val active = initial == selected
                    val enabled = available(initial)
                    Surface(onClick = { onSelect(initial) }, enabled = enabled, modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (!enabled) Color(0xFFE3E8E5) else if (active) NamuColors.primary else Color(0xFFC9D4CE)),
                        color = if (!enabled) Color(0xFFF6F7F6) else if (active) NamuColors.primary else Color.White) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(initial, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                color = if (!enabled) Color(0xFF9AA59F) else if (active) Color.White else Color(0xFF1F573B))
                        }
                    }
                }
            }
        }
    }
}

private class StockEntry(val key: String, val name: String, val count: Int, val total: Long, val payload: Any)

/** Shared list: stocked entries only by default; the bottom button expands to all and back. */
@Composable
private fun StockBrowseList(entries: List<StockEntry>, resetKey: String, unit: String, emptyHint: String,
    moreLabel: (Int) -> String, onlyLabel: String, onClick: (StockEntry) -> Unit) {
    var showAll by rememberSaveable(resetKey) { mutableStateOf(false) }
    val sorted = entries.sortedWith(compareBy({ it.count == 0 }, { it.name }))
    val stocked = sorted.filter { it.count > 0 }
    // Nothing in stock under this initial: show everything so a first stock can be entered.
    val onlyStock = !showAll && stocked.isNotEmpty()
    val shown = if (onlyStock) stocked else sorted
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (shown.isEmpty()) NamuEmptyState("이 초성에는 항목이 없어요")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "list") {
                NamuCard {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        shown.forEachIndexed { index, entry ->
                            val low = entry.count > 0 && entry.total <= NamuColors.LOW_STOCK
                            Row(Modifier.fillMaxWidth().clickable { onClick(entry) }.heightIn(min = 48.dp)
                                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(entry.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    Text(if (entry.count == 0) emptyHint else "${entry.count}$unit · ${farmNumber(entry.total)}주" + if (low) " 남음" else "",
                                        fontSize = 14.sp, fontWeight = if (low) FontWeight.Bold else FontWeight.Normal,
                                        color = if (entry.count == 0) Color(0xFF9AA59F) else if (low) Color(0xFF9A2A22) else Color(0xFF4A5A52))
                                }
                                FarmChevron()
                            }
                            if (index < shown.lastIndex) HorizontalDivider(color = Color(0xFFEDF1EE))
                        }
                    }
                }
            }
            val hidden = sorted.size - shown.size
            if (onlyStock && hidden > 0) item(key = "more") { StockOutlineButton(moreLabel(hidden)) { showAll = true } }
            else if (!onlyStock && stocked.isNotEmpty()) item(key = "only") { StockOutlineButton(onlyLabel) { showAll = false } }
        }
    }
}

@Composable
private fun StockOutlineButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, NamuColors.primary)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NamuColors.primary)
    }
}

/** 농원별: farms under the initial → tap opens that farm's stock. */
@Composable
internal fun StockFarmList(state: ManagementUiState, initial: String, onSelect: (FarmEntity) -> Unit) {
    val entries = state.farmList.filter { stockInitialOf(it.farmName) == initial }.map { farm ->
        val rows = state.allSellingInventory.filter { it.farmId == farm.farmId }
        StockEntry("f${farm.farmId}", farm.farmName, rows.map { it.speciesId }.distinct().size, rows.sumOf { it.quantity.toLong() }, farm)
    }
    StockBrowseList(entries, "farm-$initial", "종", "재고 없음 · 눌러서 입력",
        moreLabel = { "재고 없는 농원도 보기 · ${it}곳" }, onlyLabel = "재고 있는 농원만 보기") { onSelect(it.payload as FarmEntity) }
}

/** 묘목별: species under the initial → tap shows which farms hold it. */
@Composable
internal fun StockSpeciesOverview(state: ManagementUiState, initial: String, onSelect: (SpeciesEntity) -> Unit) {
    val entries = state.allSpeciesList.filter { (it.isActive ?: 1) == 1 && stockInitialOf(it.speciesName, it.chosung) == initial }.map { species ->
        val rows = state.allSellingInventory.filter { it.speciesId == species.speciesId }
        StockEntry("s${species.speciesId}", species.speciesName, rows.map { it.farmId }.distinct().size, rows.sumOf { it.quantity.toLong() }, species)
    }
    StockBrowseList(entries, "species-$initial", "개 농원", "재고 없음 · 눌러서 농원 고르기",
        moreLabel = { "다른 묘목에 재고 입력 · ${it}종" }, onlyLabel = "재고 있는 묘목만 보기") { onSelect(it.payload as SpeciesEntity) }
}

/** 농원별 상세: the farm's stocked species (existing list) under a back header. */
@Composable
internal fun StockFarmPage(state: ManagementUiState, onBack: () -> Unit, onSelectSpecies: (SpeciesEntity) -> Unit) {
    val farmId = state.selectedFarm?.farmId
    val rows = state.allSellingInventory.filter { it.farmId == farmId }
    Column(Modifier.fillMaxSize()) {
        FarmSubHeader(state.selectedFarm?.farmName.orEmpty(),
            if (rows.isEmpty()) "재고 없음 · 묘목을 눌러 입력" else "재고 ${rows.map { it.speciesId }.distinct().size}종 · ${farmNumber(rows.sumOf { it.quantity.toLong() })}주", onBack)
        StockSpeciesList(state, "전체", "", onSelectSpecies)
    }
}

/** 묘목별 상세: farms holding the species, sortable, with call buttons; tap opens the existing editor. */
@Composable
internal fun StockSpeciesFarmsPage(state: ManagementUiState, species: SpeciesEntity, onBack: () -> Unit,
    onEdit: (FarmEntity) -> Unit) {
    val context = LocalContext.current
    var sortByPrice by rememberSaveable(species.speciesId) { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val rows = state.allSellingInventory.filter { it.speciesId == species.speciesId }
        .sortedWith(if (sortByPrice) compareBy<SellingDisplayRow>({ it.wholesalePrice }, { -it.quantity }) else compareBy<SellingDisplayRow>({ -it.quantity }, { it.wholesalePrice }))
    val farms = state.farmList.associateBy { it.farmId }
    Column(Modifier.fillMaxSize()) {
        FarmSubHeader(species.speciesName, if (rows.isEmpty()) "아직 가진 농원이 없어요"
            else "${rows.map { it.farmId }.distinct().size}개 농원 · 총 ${farmNumber(rows.sumOf { it.quantity.toLong() })}주", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isNotEmpty()) item(key = "sort") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StockSortChip("수량 많은 순", !sortByPrice) { sortByPrice = false }
                    StockSortChip("도매가 싼 순", sortByPrice) { sortByPrice = true }
                }
            }
            if (rows.isEmpty()) item { NamuEmptyState("이 묘목을 가진 농원이 없어요") }
            itemsIndexed(rows, key = { index, row -> "${row.farmId}-${row.age}-${row.size}-${row.plantType}-${row.quality}-$index" }) { _, row ->
                val farm = farms[row.farmId]
                val low = row.quantity <= NamuColors.LOW_STOCK
                NamuCard(onClick = { farm?.let(onEdit) }) {
                    Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.farmName, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("${farmNumber(row.quantity)}주" + if (low) " 남음" else "", fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, color = if (low) Color(0xFF9A2A22) else NamuColors.primary)
                            }
                            Text(listOf(stockSpec(row.age, row.size), row.plantType, row.quality).filter { it.isNotBlank() }
                                .joinToString(" · ").ifBlank { "규격 없음" }, fontSize = 14.sp, color = Color(0xFF4A5A52))
                            Text("도매 ${farmNumber(row.wholesalePrice)}원 · 판매 ${farmNumber(row.retailPrice)}원", fontSize = 14.sp, color = NamuColors.text)
                        }
                        val phone = farm?.phone.orEmpty().filter { it.isDigit() }
                        if (phone.isNotEmpty()) Surface(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
                        }, shape = RoundedCornerShape(50), border = BorderStroke(1.5.dp, NamuColors.primary), color = Color.White,
                            modifier = Modifier.size(42.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Call, "${row.farmName} 전화", Modifier.size(19.dp), tint = NamuColors.primary) }
                        }
                    }
                }
            }
        }
        FarmFooter { NamuPrimaryButton("다른 농원에 이 묘목 재고 입력", { picking = true }, Modifier.fillMaxWidth(), icon = Icons.Outlined.Add) }
    }
    if (picking) StockFarmPickSheet(state, onClose = { picking = false }) { picking = false; onEdit(it) }
}

@Composable
private fun StockSortChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = if (active) NamuColors.dark else Color.White,
        border = BorderStroke(1.dp, if (active) NamuColors.dark else Color(0xFFC9D4CE))) {
        Text(label, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold,
            color = if (active) Color.White else NamuColors.text)
    }
}

/** Farm chooser for adding this species' stock at another farm (same initial grid, all farms). */
@Composable
private fun StockFarmPickSheet(state: ManagementUiState, onClose: () -> Unit, onPick: (FarmEntity) -> Unit) {
    var initial by rememberSaveable { mutableStateOf("ㄱ") }
    FarmSheet("재고를 넣을 농원 고르기", onClose) {
        StockChosungGrid(initial, available = { ch -> state.farmList.any { stockInitialOf(it.farmName) == ch } }) { initial = it }
        val farms = state.farmList.filter { stockInitialOf(it.farmName) == initial }.sortedBy { it.farmName }
        if (farms.isEmpty()) Text("이 초성에는 농원이 없어요", fontSize = 15.sp, color = NamuColors.muted)
        farms.forEach { farm ->
            Surface(onClick = { onPick(farm) }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, NamuColors.border), color = Color.White) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(farm.farmName, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    FarmChevron()
                }
            }
        }
    }
}

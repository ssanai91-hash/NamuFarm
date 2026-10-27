package com.example.namufarm.ui.farm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.data.formatSpeciesCategory
import com.example.namufarm.data.parseSpeciesCategory
import com.example.namufarm.data.speciesCategoryError
import com.example.namufarm.data.suggestSpeciesPurposeLabels
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.getChosungFromText
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementViewModel
import com.google.gson.Gson

@Composable
internal fun SpeciesList(viewModel: ManagementViewModel, initial: String, search: String) {
    val state by viewModel.uiState.collectAsState()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var editJson by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = remember(editJson) { editJson?.let { Gson().fromJson(it, SpeciesEntity::class.java) } }
    val species = state.allSpeciesList.filter { (it.isActive ?: 1) == 1 &&
        namuMatchesInitial(it.speciesName, it.chosung, initial) && namuSearchMatches(it.speciesName, search) }.sortedBy { it.speciesName }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        NamuCard(modifier = Modifier.weight(1f, fill = false)) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 4.dp)) {
                if (species.isEmpty()) item { NamuEmptyState("조건에 맞는 묘목이 없어요") }
                itemsIndexed(species, key = { _, item -> item.speciesId ?: item.speciesName }) { index, item ->
                    val rows = state.allSellingInventory.filter { it.speciesId == item.speciesId }
                    val details = buildList {
                        if (!item.category.isNullOrBlank()) add(item.category)
                        if (rows.isEmpty()) add("재고 없음")
                        else { add("농원 ${rows.map { it.farmId }.distinct().size}곳"); add("${farmNumber(rows.sumOf { it.quantity.toLong() })}주") }
                    }.joinToString(" · ")
                    Row(Modifier.fillMaxWidth().clickable { editJson = Gson().toJson(item); showSheet = true }
                        .heightIn(min = 48.dp).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.speciesName, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text(details, fontSize = 14.sp, color = if (rows.isEmpty()) Color(0xFF9AA59F) else Color(0xFF4A5A52))
                        }
                        FarmChevron()
                    }
                    if (index < species.lastIndex) HorizontalDivider(color = Color(0xFFEDF1EE))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        NamuDashedAdd("묘목 추가", { editJson = null; showSheet = true })
    }
    if (showSheet) SpeciesEditSheet(viewModel, editing) { showSheet = false }
}

@Composable
private fun SpeciesEditSheet(viewModel: ManagementViewModel, existing: SpeciesEntity?, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var name by rememberSaveable(existing?.speciesId) { mutableStateOf(existing?.speciesName.orEmpty()) }
    val originalCategory = remember(existing?.speciesId) { parseSpeciesCategory(existing?.category) }
    val blankCategory = existing?.category.isNullOrBlank()
    var selectedPurposes by rememberSaveable(existing?.speciesId) {
        mutableStateOf(if (blankCategory) suggestSpeciesPurposeLabels(existing?.speciesName.orEmpty()) else originalCategory.selectedLabels)
    }
    var legacyValues by rememberSaveable(existing?.speciesId) { mutableStateOf(originalCategory.legacyValues) }
    var purposesTouched by rememberSaveable(existing?.speciesId) { mutableStateOf(false) }
    var memo by rememberSaveable(existing?.speciesId) { mutableStateOf(existing?.memo.orEmpty()) }
    var hideConfirm by remember { mutableStateOf(false) }
    val duplicate = state.allSpeciesList.any { it.speciesId != existing?.speciesId && it.speciesName.trim().equals(name.trim(), ignoreCase = true) }
    val stocks = state.allSellingInventory.filter { existing != null && it.speciesId == existing.speciesId }
    val category = formatSpeciesCategory(selectedPurposes, legacyValues)
    val categoryError = speciesCategoryError(category)
    FarmSheet(if (existing == null) "묘목 추가" else "묘목 정보", onClose) {
        FarmField("묘목명", name, {
            name = it
            if (existing == null && !purposesTouched) selectedPurposes = suggestSpeciesPurposeLabels(it)
        }, required = true, trailing = {
            Text("초성 ${getChosungFromText(name)} · 자동", Modifier.padding(end = 12.dp), fontSize = 13.sp, color = NamuColors.muted)
        })
        if (duplicate) Text("같은 이름의 묘목이 있어요", fontSize = 13.sp, color = NamuColors.danger)
        SpeciesPurposePicker(selectedPurposes, legacyValues,
            suggested = blankCategory && !purposesTouched && selectedPurposes.isNotEmpty(), error = categoryError,
            onToggle = { label ->
                purposesTouched = true
                selectedPurposes = if (label in selectedPurposes) selectedPurposes - label else selectedPurposes + label
            }, onRemoveLegacy = { index ->
                legacyValues = legacyValues.filterIndexed { i, _ -> i != index }
            })
        FarmField("메모", memo, { memo = it }, memo = true)
        if (existing != null) FarmInfo(if (stocks.isEmpty()) "등록된 재고가 없어요" else
            "이 묘목 재고: " + stocks.groupBy { it.farmId }.values.joinToString(" · ") {
                "${it.first().farmName} ${farmNumber(it.sumOf { row -> row.quantity.toLong() })}주"
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NamuSecondaryButton("취소", onClose, Modifier.weight(1f))
            NamuPrimaryButton("저장", {
                if (categoryError != null) return@NamuPrimaryButton
                viewModel.saveSpecies(existing?.speciesId, name.trim(), existing?.nameFarm.orEmpty(), getChosungFromText(name.trim()),
                    category, memo, existing?.isActive ?: 1)
                onClose()
            }, Modifier.weight(1.6f), name.isNotBlank() && !duplicate && categoryError == null)
        }
        if (existing != null) TextButton(onClick = { hideConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = NamuColors.danger)
            Spacer(Modifier.width(6.dp))
            Text("목록에서 숨기기", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = NamuColors.danger)
        }
    }
    if (hideConfirm && existing?.speciesId != null) AlertDialog(onDismissRequest = { hideConfirm = false }, title = { Text("묘목 숨기기") },
        text = { Text("이 묘목을 목록에서 숨길까요? (비활성 처리)") },
        confirmButton = { TextButton(onClick = { viewModel.deleteSpecies(existing.speciesId); hideConfirm = false; onClose() }) {
            Text("숨기기", color = NamuColors.danger)
        } }, dismissButton = { TextButton(onClick = { hideConfirm = false }) { Text("취소") } })
}

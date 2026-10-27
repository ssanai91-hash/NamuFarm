package com.example.namufarm.ui.farm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.InventoryDisplayRow
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementUiState
import com.example.namufarm.viewmodel.ManagementViewModel
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StockEditor(viewModel: ManagementViewModel, original: InventoryDisplayRow?, onBack: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var priceWarning by remember { mutableStateOf(false) }
    var farmWarning by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var beforeDelete by remember { mutableStateOf<ManagementUiState?>(null) }
    var numberField by remember { mutableStateOf<String?>(null) }
    var digits by remember { mutableStateOf("") }
    fun openNumber(field: String, value: Int) { numberField = field; digits = if (value == 0) "" else value.toString() }
    fun cancelSave() {
        beforeDelete?.let {
            viewModel.updateAge(it.inputAge); viewModel.updateSize(it.inputSizeMeters)
            viewModel.updatePlantType(it.inputPlantType); viewModel.updateQuality(it.inputQuality)
            viewModel.setQuantity(it.inputQuantity); viewModel.setWholesale(it.inputWholesale); viewModel.setRetail(it.inputRetail)
        }
        beforeDelete = null; confirmSave = false
    }
    fun changeSize(delta: Float) {
        viewModel.updateSize(Math.round((state.inputSizeMeters + delta).coerceAtLeast(0f) * 10) / 10f)
    }
    val age = state.inputAge.filter(Char::isDigit).toIntOrNull() ?: 0
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        FarmSubHeader(if (original == null) "새 재고 입력" else "재고 수정",
            "${state.selectedSpecies?.speciesName.orEmpty()} · ${state.selectedFarm?.farmName.orEmpty()}", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (original != null) Surface(color = Color(0xFFFDF1DC), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.WarningAmber, null, Modifier.size(20.dp), tint = Color(0xFF6B4300))
                    Text(buildAnnotatedString {
                        append("규격을 바꾸면 ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("새 재고로 추가") }
                        append("돼요 (기존 재고 유지)")
                    }, fontSize = 14.sp, lineHeight = 20.3.sp, color = Color(0xFF6B4300))
                }
            }
            NamuCard {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("규격", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (state.inputAgeReadOnly) Text("${state.inputAge} · PC 입력 규격", fontSize = 15.sp)
                    else {
                    SpecInputRow("년생") {
                        SmallAdjust("−", { viewModel.updateAge(if (age > 1) "${age - 1}년생" else "") }, circle = true)
                        Surface(Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.5.dp, NamuColors.input), color = Color.White) {
                            Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) { Text(state.inputAge.ifBlank { "없음" },
                                fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                        }
                        SmallAdjust("+", { viewModel.updateAge("${age.coerceAtMost(Int.MAX_VALUE - 1) + 1}년생") }, circle = true)
                        SmallAdjust("없음", { viewModel.updateAge("") })
                    }
                    }
                    if (state.inputSizeRaw != null) Text("${state.inputSizeRaw.orEmpty().ifBlank { "없음" }} · PC 입력 규격", fontSize = 15.sp)
                    else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("크기", Modifier.width(58.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF33423A))
                        Spacer(Modifier.width(8.dp))
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmallAdjust("−10cm", { changeSize(-0.1f) })
                            Surface(shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.5.dp, NamuColors.input)) {
                                Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                    Text((state.inputSizeRaw ?: String.format(Locale.KOREA, "%.1fm", state.inputSizeMeters)), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            SmallAdjust("+10cm", { changeSize(0.1f) })
                            SmallAdjust("+1m", { changeSize(1f) })
                        }
                    }
                    }
                    SpecChoice("타입", listOf("", "실생", "접목", "삽목", "근삽"), state.inputPlantType, viewModel::updatePlantType)
                    SpecChoice("품질", listOf("", "특묘", "상묘", "중묘", "하묘"), state.inputQuality, viewModel::updateQuality)
                }
            }
            NamuCard {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("수량·가격", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    NumberAdjust("수량", state.inputQuantity, "주", listOf(1, 10, 100), viewModel::setQuantity) { openNumber("수량", state.inputQuantity) }
                    NumberAdjust("도매가", state.inputWholesale, "원", listOf(100, 1000, 10000), viewModel::setWholesale) { openNumber("도매가", state.inputWholesale) }
                    NumberAdjust("소매가", state.inputRetail, "원", listOf(100, 1000, 10000), viewModel::setRetail) { openNumber("소매가", state.inputRetail) }
                }
            }
        }
        FarmFooter {
            val spec = listOf(stockSpec(state.inputAge, (state.inputSizeRaw ?: String.format(Locale.KOREA, "%.1fm", state.inputSizeMeters))),
                state.inputPlantType, state.inputQuality).filter { it.isNotBlank() }.joinToString(" · ")
            Text(buildAnnotatedString {
                if (spec.isNotBlank()) append("$spec · ")
                withStyle(SpanStyle(color = NamuColors.text, fontWeight = FontWeight.Bold)) { append("${farmNumber(state.inputQuantity)}주") }
            }, fontSize = 14.sp, color = NamuColors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (original == null) NamuSecondaryButton("취소", onBack, Modifier.weight(1f))
                else OutlinedButton(onClick = {
                    beforeDelete = state
                    viewModel.loadExistingRowToInput(original, state.allSpeciesList)
                    viewModel.setQuantity(0)
                    confirmSave = true
                }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, Color(0xFFE2B4AE))) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = NamuColors.danger)
                    Text("재고 삭제", color = NamuColors.danger, fontSize = 15.sp)
                }
                NamuPrimaryButton("저장", {
                    if (state.selectedFarm == null) farmWarning = true
                    else if (state.inputQuantity > 0 && (state.inputWholesale == 0 || state.inputRetail == 0)) priceWarning = true
                    else confirmSave = true
                }, Modifier.weight(1.6f), original != null || state.inputQuantity > 0)
            }
        }
    }
    if (numberField != null) AlertDialog(onDismissRequest = { numberField = null },
        title = { Text("${numberField} 입력") },
        text = { OutlinedTextField(digits, { if (it.length <= 9 && it.all { c -> c in '0'..'9' }) digits = it },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("숫자 입력") }, supportingText = { Text("최대 9자리 · ${farmNumber(digits.toIntOrNull() ?: 0)}") }) },
        confirmButton = { TextButton(onClick = {
            val value = digits.toIntOrNull() ?: 0
            when (numberField) { "수량" -> viewModel.setQuantity(value); "도매가" -> viewModel.setWholesale(value); else -> viewModel.setRetail(value) }
            numberField = null
        }) { Text("확인") } }, dismissButton = { TextButton(onClick = { numberField = null }) { Text("취소") } })
    if (priceWarning) AlertDialog(onDismissRequest = { priceWarning = false }, title = { Text("가격을 입력해 주세요") },
        text = { Text("수량이 있는 재고는 도매가와 소매가를 모두 입력해야 해요.") },
        confirmButton = { TextButton(onClick = { priceWarning = false }) { Text("확인") } })
    if (farmWarning) AlertDialog(onDismissRequest = { farmWarning = false }, title = { Text("농원 선택 필요") },
        text = { Text("재고를 저장하려면 농원을 먼저 선택해주세요.") }, confirmButton = { TextButton(onClick = { farmWarning = false }) { Text("확인") } })
    if (confirmSave) {
        val isDelete = state.inputQuantity == 0
        AlertDialog(onDismissRequest = ::cancelSave, title = { Text(if (isDelete) "해당 재고 삭제" else "재고 저장 확인") },
            text = { Text("${state.selectedFarm?.farmName} · ${state.selectedSpecies?.speciesName}\n" +
                "${state.inputAge} · ${(state.inputSizeRaw ?: String.format(Locale.KOREA, "%.1fm", state.inputSizeMeters))} · ${state.inputPlantType} · ${state.inputQuality}\n" +
                "수량 ${farmNumber(state.inputQuantity)}주\n도매 ${farmNumber(state.inputWholesale)}원 · 소매 ${farmNumber(state.inputRetail)}원\n" +
                if (isDelete) "이 재고를 삭제할까요?" else "이 정보로 저장할까요?") },
            confirmButton = { TextButton(onClick = { beforeDelete = null; confirmSave = false; viewModel.saveInventory(onLocalSaved = onSaved) }) {
                Text(if (isDelete) "삭제 진행" else "저장 진행", color = if (isDelete) NamuColors.danger else NamuColors.primary)
            } }, dismissButton = { TextButton(onClick = ::cancelSave) { Text("취소") } })
    }
}

@Composable
private fun SpecInputRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontSize = 15.sp, color = Color(0xFF33423A), fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
private fun SmallAdjust(label: String, onClick: () -> Unit, circle: Boolean = false) {
    Surface(onClick = onClick, shape = if (circle) CircleShape else RoundedCornerShape(22.dp), color = Color(0xFFE3EFE7)) {
        Box(Modifier.then(if (circle) Modifier.size(44.dp) else Modifier.heightIn(min = 44.dp).padding(horizontal = 7.dp)), contentAlignment = Alignment.Center) {
            Text(label, color = Color(0xFF1F573B), fontSize = if (circle) 20.sp else 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SpecChoice(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    SpecInputRow(label) {
        options.forEach { value ->
            val active = value == selected
            Surface(onClick = { onSelect(value) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
                border = BorderStroke(if (active) 2.dp else 1.dp, if (active) NamuColors.primary else Color(0xFFC9D4CE)),
                color = if (active) Color(0xFFEEF6F0) else Color.White) {
                Box(Modifier.padding(3.dp), contentAlignment = Alignment.Center) {
                    Text(value.ifBlank { "없음" }, fontSize = 15.sp, textAlign = TextAlign.Center,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, color = if (active) Color(0xFF1F573B) else Color(0xFF33423A))
                }
            }
        }
    }
}

@Composable
private fun NumberAdjust(label: String, value: Int, unit: String, increments: List<Int>, onValue: (Int) -> Unit, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(58.dp), fontSize = 15.sp, color = Color(0xFF33423A))
        Spacer(Modifier.width(8.dp))
        Surface(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
            color = Color.White, border = BorderStroke(1.5.dp, NamuColors.input)) {
            Text("${farmNumber(value)} $unit", Modifier.padding(horizontal = 12.dp, vertical = 9.dp), textAlign = TextAlign.End,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 66.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        increments.forEach { delta ->
            OutlinedButton(onClick = { onValue((value.toLong() + delta).coerceAtMost(999999999L).toInt()) },
                modifier = Modifier.weight(1f).heightIn(min = 36.dp), shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp), border = BorderStroke(1.dp, Color(0xFFC9D4CE))) {
                Text(when (delta) { 1000 -> "+1천"; 10000 -> "+1만"; else -> "+$delta" }, fontSize = 14.sp)
            }
        }
        OutlinedButton(onClick = { onValue(0) }, modifier = Modifier.weight(1f).heightIn(min = 36.dp), shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp), border = BorderStroke(1.dp, Color(0xFFECC9C4))) {
            Text("지우기", fontSize = 14.sp, color = Color(0xFF9A2A22))
        }
    }
}

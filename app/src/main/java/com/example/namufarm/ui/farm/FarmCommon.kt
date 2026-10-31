package com.example.namufarm.ui.farm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.*
import com.example.namufarm.viewmodel.ManagementUiState
import java.util.Locale

internal fun farmNumber(value: Int): String = String.format(Locale.KOREA, "%,d", value)
internal fun farmNumber(value: Long): String = String.format(Locale.KOREA, "%,d", value)
internal fun stockInputKey(s: ManagementUiState): String = listOf(s.inputAge, s.inputSizeMeters,
    s.inputPlantType, s.inputQuality, s.inputQuantity, s.inputWholesale, s.inputRetail).joinToString("|")
internal fun stockSpec(age: String, size: String): String = listOf(age.takeIf { it.isNotBlank() && it != "0년생" },
    size.takeIf { it.isNotBlank() && (it.removeSuffix("m").toFloatOrNull() ?: 1f) > 0f }).filterNotNull().joinToString(" · ")

@Composable
internal fun FarmTopArea(state: ManagementUiState, view: Int, onView: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White) {
        Column {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NamuSegmented(listOf(NamuSegment("재고", concept = NamuConcept.STOCK),
                    NamuSegment("농원", state.farmList.size, concept = NamuConcept.FARM),
                    NamuSegment("묘목", state.allSpeciesList.size, concept = NamuConcept.SPECIES)), view, onView)
                content()
            }
            HorizontalDivider(color = NamuColors.border)
        }
    }
}

@Composable
internal fun FarmPicker(state: ManagementUiState, onSelect: (FarmEntity) -> Unit) {
    Text("농원 선택", fontSize = 13.sp, color = NamuColors.muted, fontWeight = FontWeight.Medium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.farmList, key = { it.farmId ?: it.farmName }) { farm ->
            val selected = farm.farmId == state.selectedFarm?.farmId
            Surface(onClick = { onSelect(farm) }, shape = RoundedCornerShape(20.dp),
                color = if (selected) NamuColors.primary else Color.White,
                border = BorderStroke(1.dp, if (selected) NamuColors.primary else Color(0xFFC9D4CE))) {
                Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(farm.farmName, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) Color.White else NamuColors.text)
                    Text("${state.allSellingInventory.count { it.farmId == farm.farmId }}건", fontSize = 13.sp,
                        color = if (selected) Color(0xFFCFE6D7) else NamuColors.muted)
                }
            }
        }
    }
}

@Composable
internal fun FarmSearch(value: String, onChange: (String) -> Unit) {
    TextField(value, onChange, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), singleLine = true,
        placeholder = { Text("묘목 이름 · 초성", fontSize = 16.sp, color = Color(0xFF7B8A83)) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp), tint = NamuColors.muted) },
        shape = RoundedCornerShape(12.dp), colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFF1F4F2), unfocusedContainerColor = Color(0xFFF1F4F2),
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}

@Composable
internal fun FarmSubHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Surface(color = Color.White) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로", tint = NamuColors.text) }
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, fontSize = 13.sp, lineHeight = 16.25.sp, color = NamuColors.muted)
                }
            }
            HorizontalDivider(color = NamuColors.border)
        }
    }
}

@Composable
internal fun FarmFooter(topPadding: Dp = 10.dp, bottomPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding, bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun FarmChevron() { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Color(0xFF9AA59F)) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FarmSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 12.dp).size(width = 40.dp, height = 4.dp)
            .background(Color(0xFFCDD6D1), RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun FarmField(label: String, value: String, onChange: (String) -> Unit, required: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, memo: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(buildAnnotatedString {
            append(label)
            if (required) withStyle(SpanStyle(color = NamuColors.danger)) { append(" *") }
        }, fontSize = 14.sp, color = Color(0xFF4A5A52), fontWeight = FontWeight.Medium)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().heightIn(min = if (memo) 76.dp else 52.dp)
            .border(if (focused) 2.dp else 1.5.dp, if (focused) NamuColors.primary else NamuColors.input, RoundedCornerShape(12.dp)),
            singleLine = !memo, minLines = if (memo) 2 else 1, shape = RoundedCornerShape(12.dp),
            interactionSource = interaction, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = LocalTextStyle.current.copy(fontSize = if (memo) 16.sp else 17.sp), trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
    }
}

@Composable
internal fun FarmInfo(text: String, warning: Boolean = false) {
    val ink = if (warning) Color(0xFF6B4300) else Color(0xFF1F573B)
    Surface(color = if (warning) Color(0xFFFDF1DC) else Color(0xFFEEF5F0), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.Info, null, Modifier.size(20.dp), tint = ink)
            Text(text, fontSize = 14.sp, lineHeight = 20.3.sp, color = ink)
        }
    }
}

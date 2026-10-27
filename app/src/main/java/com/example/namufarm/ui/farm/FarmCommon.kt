package com.example.namufarm.ui.farm

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
                NamuSegmented(listOf(NamuSegment("재고"), NamuSegment("농원", state.farmList.size),
                    NamuSegment("묘목", state.allSpeciesList.size)), view, onView)
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
internal fun FarmFooter(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun FarmChevron() { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Color(0xFF9AA59F)) }

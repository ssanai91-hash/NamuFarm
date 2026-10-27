package com.example.namufarm.ui.farm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fence
import androidx.compose.material.icons.outlined.Grass
import androidx.compose.material.icons.outlined.LocalFlorist
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Yard
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.SPECIES_PURPOSES
import com.example.namufarm.ui.theme.NamuColors

@Composable
internal fun SpeciesPurposeFilter(selectedLabel: String, onSelect: (String) -> Unit) {
    val options = listOf("" to "전체") + SPECIES_PURPOSES.map { it.label to it.label }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first }) { (value, label) ->
            val checked = value == selectedLabel
            Surface(onClick = { onSelect(value) }, modifier = Modifier.semantics { selected = checked },
                shape = RoundedCornerShape(20.dp),
                color = if (checked) NamuColors.primary else Color.White,
                border = BorderStroke(1.dp, if (checked) NamuColors.primary else Color(0xFFC9D4CE))) {
                Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 15.sp, color = if (checked) Color.White else NamuColors.text,
                        fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun SpeciesPurposePicker(selectedLabels: List<String>, legacyValues: List<String>,
    suggested: Boolean, error: String?, onToggle: (String) -> Unit, onRemoveLegacy: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("용도 (여러 개 선택)", fontSize = 14.sp, color = Color(0xFF4A5A52))
        if (suggested) Text("홈페이지 기준으로 미리 골라 두었어요. 저장해야 반영돼요.",
            fontSize = 13.sp, color = NamuColors.muted)
        SPECIES_PURPOSES.chunked(3).forEach { purposes ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                purposes.forEach { purpose ->
                    val checked = purpose.label in selectedLabels
                    val ink = if (checked) Color(0xFF1F573B) else NamuColors.text
                    val icon = when (purpose.id) {
                        "fruit" -> Icons.Outlined.Spa
                        "hedge" -> Icons.Outlined.Fence
                        "flower" -> Icons.Outlined.LocalFlorist
                        "shade" -> Icons.Outlined.Park
                        "garden" -> Icons.Outlined.Yard
                        else -> Icons.Outlined.Grass
                    }
                    Surface(onClick = { onToggle(purpose.label) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            role = Role.Checkbox
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (checked) Color(0xFFEEF6F0) else Color.White,
                        border = BorderStroke(if (checked) 2.dp else 1.dp,
                            if (checked) NamuColors.primary else Color(0xFFC9D4CE))) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(icon, null, Modifier.size(20.dp), tint = ink)
                            Text(purpose.label, Modifier.weight(1f), fontSize = 16.sp, color = ink,
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal)
                            if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = ink)
                        }
                    }
                }
            }
        }
        legacyValues.forEachIndexed { index, value ->
            InputChip(selected = false, onClick = { onRemoveLegacy(index) },
                label = { Text("기존 값: $value", fontSize = 13.sp) },
                trailingIcon = { Icon(Icons.Outlined.Close, "기존 값 $value 지우기", Modifier.size(18.dp)) },
                colors = InputChipDefaults.inputChipColors(containerColor = Color(0xFFF1F4F2),
                    labelColor = NamuColors.muted))
        }
        if (error != null) Text(error, fontSize = 13.sp, color = NamuColors.danger)
    }
}

package com.example.namufarm.ui.farm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.viewmodel.ManagementViewModel
import com.google.gson.Gson

@Composable
internal fun FarmList(viewModel: ManagementViewModel) {
    val state by viewModel.uiState.collectAsState()
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var editJson by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = remember(editJson) { editJson?.let { Gson().fromJson(it, FarmEntity::class.java) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.farmList, key = { it.farmId ?: it.farmName }) { farm ->
            val rows = state.allSellingInventory.filter { it.farmId == farm.farmId }
            NamuCard(onClick = { editJson = Gson().toJson(farm); showSheet = true }) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(farm.farmName, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${farm.address.orEmpty().ifBlank { "주소 없음" }} · ${farm.phone.orEmpty().ifBlank { "전화번호 없음" }}",
                            fontSize = 14.sp, color = Color(0xFF4A5A52), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (rows.isEmpty()) "재고 없음" else "재고 ${rows.size}건 · ${farmNumber(rows.sumOf { it.quantity.toLong() })}주",
                            fontSize = 14.sp, color = if (rows.isEmpty()) Color(0xFF9AA59F) else Color(0xFF1F573B),
                            fontWeight = if (rows.isEmpty()) FontWeight.Normal else FontWeight.Bold)
                        farm.memo?.takeIf { it.isNotBlank() }?.let { memo ->
                            Surface(color = NamuColors.background, shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                Text(memo, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp,
                                    color = NamuColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (!farm.phone.isNullOrBlank()) NamuCallButton(farm.phone, Modifier.size(44.dp))
                }
            }
        }
        item { NamuDashedAdd("농원 추가", { editJson = null; showSheet = true }) }
    }
    if (showSheet) FarmEditSheet(viewModel, editing) { showSheet = false }
}

@Composable
private fun FarmEditSheet(viewModel: ManagementViewModel, existing: FarmEntity?, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    var name by rememberSaveable(existing?.farmId) { mutableStateOf(existing?.farmName.orEmpty()) }
    var phone by rememberSaveable(existing?.farmId) { mutableStateOf(existing?.phone.orEmpty()) }
    var address by rememberSaveable(existing?.farmId) { mutableStateOf(existing?.address.orEmpty()) }
    var memo by rememberSaveable(existing?.farmId) { mutableStateOf(existing?.memo.orEmpty()) }
    var saveConfirm by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    val duplicate = state.farmList.any { it.farmId != existing?.farmId && it.farmName.trim().equals(name.trim(), ignoreCase = true) }
    val inventoryCount = if (existing == null) 0 else state.allSellingInventory.count { it.farmId == existing.farmId }
    FarmSheet(if (existing == null) "농원 추가" else "농원 정보", onClose) {
        FarmField("농원명", name, { name = it }, required = true)
        if (duplicate) Text("같은 이름의 농원이 있어요", fontSize = 13.sp, color = NamuColors.danger)
        FarmField("전화번호", phone, { phone = it }, keyboard = KeyboardType.Phone)
        FarmField("주소", address, { address = it })
        FarmField("메모", memo, { memo = it }, memo = true)
        if (existing != null && inventoryCount > 0) FarmInfo("재고 ${inventoryCount}건이 있어 삭제할 수 없어요. 재고를 먼저 정리하면 삭제할 수 있어요.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NamuSecondaryButton("취소", onClose, Modifier.weight(1f))
            NamuPrimaryButton("저장", { saveConfirm = true }, Modifier.weight(1.6f), name.isNotBlank() && !duplicate)
        }
        if (existing != null) TextButton(onClick = { deleteConfirm = true }, enabled = inventoryCount == 0,
            modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = if (inventoryCount == 0) NamuColors.danger else Color(0xFFA8B3AD))
            Spacer(Modifier.width(6.dp))
            Text("농원 삭제", fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = if (inventoryCount == 0) NamuColors.danger else Color(0xFFA8B3AD))
        }
    }
    if (saveConfirm) AlertDialog(onDismissRequest = { saveConfirm = false }, title = { Text("농원 저장 확인") },
        text = { Text("${name.trim()}\n$phone\n$address\n$memo\n이 정보로 저장할까요?") },
        confirmButton = { TextButton(enabled = name.isNotBlank() && !duplicate, onClick = {
            viewModel.saveFarm(existing?.farmId, name, address, phone, memo); saveConfirm = false; onClose()
        }) { Text("저장") } }, dismissButton = { TextButton(onClick = { saveConfirm = false }) { Text("취소") } })
    if (deleteConfirm && existing?.farmId != null) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text("농원 삭제") },
        text = { Text("이 농원을 삭제할까요? 재고가 없는 농원만 삭제할 수 있어요.") },
        confirmButton = { TextButton(enabled = inventoryCount == 0, onClick = {
            viewModel.deleteFarm(existing.farmId); deleteConfirm = false; onClose()
        }) { Text("삭제", color = NamuColors.danger) } }, dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("취소") } })
}

package com.example.namufarm.ui.farm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.BlockedInventoryWork
import com.example.namufarm.quotemode.components.*

@Composable
internal fun BlockedInventorySheet(items: List<BlockedInventoryWork>, onClose: () -> Unit,
    onSaveFarm: (BlockedInventoryWork) -> Unit, onSaveSpecies: (BlockedInventoryWork) -> Unit,
    onRetry: (BlockedInventoryWork) -> Unit, onUseServer: (BlockedInventoryWork) -> Unit) {
    var confirming by remember { mutableStateOf<BlockedInventoryWork?>(null) }
    FarmSheet("보낼 수 없는 재고", onClose) {
        items.forEach { item ->
            NamuCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val name = item.species?.speciesName ?: "묘목 ${item.key.speciesId}"
                    val farm = item.farm?.farmName ?: "농원 ${item.key.farmId}"
                    val spec = listOf(item.key.age, item.key.size, item.key.plantType, item.key.quality)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text("$name · $farm · $spec · ${farmNumber(item.local?.quantity ?: item.change.quantity)}주")
                    Text(if (item.isUnlinked) "서버에 등록되지 않은 농원(또는 묘목)"
                        else "서버가 받지 않았어요(숨긴 묘목·삭제된 농원 등)", fontSize = 14.sp)
                    if (item.pending.size > 1) Text("같은 규격의 뒤 작업을 포함해 ${item.pending.size}건 대기 중", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.isUnlinked) {
                            NamuSecondaryButton("농원 다시 저장", { onSaveFarm(item) }, Modifier.weight(1f), item.farm != null)
                            NamuSecondaryButton("묘목 다시 저장", { onSaveSpecies(item) }, Modifier.weight(1f), item.species != null)
                        } else {
                            NamuSecondaryButton("다시 보내기", { onRetry(item) }, Modifier.weight(1f))
                            NamuSecondaryButton("서버 기준으로 맞추기", { confirming = item }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    confirming?.let { item -> AlertDialog(onDismissRequest = { confirming = null },
        title = { Text("서버 기준으로 맞추기") },
        text = { Text("이 규격의 전송 대기 ${item.pending.size}건과 휴대폰 재고를 먼저 백업합니다. 그 뒤 대기를 삭제하고 서버 값으로 맞출까요? 서버에 같은 규격이 없으면 휴대폰 재고도 삭제됩니다.") },
        confirmButton = { TextButton(onClick = { confirming = null; onUseServer(item) }) { Text("백업 후 맞추기") } },
        dismissButton = { TextButton(onClick = { confirming = null }) { Text("취소") } }) }
}

package com.example.namufarm.ui.farm

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.namufarm.data.InventoryReviewState
import com.example.namufarm.quotemode.components.*
import com.example.namufarm.viewmodel.ManagementUiState

@Composable
internal fun InventoryReviewSheet(review: InventoryReviewState, state: ManagementUiState, onClose: () -> Unit,
    onResolve: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    FarmSheet(if (review.firstLink) "재고 맞추기" else "재고 충돌 확인", onClose) {
        Text(if (review.firstLink) "휴대폰과 서버(PC)의 재고가 다른 항목이 있어요. 어느 쪽으로 맞출지 골라주세요."
            else "다른 기기(PC)와 재고 수정이 겹쳤어요. 최신 서버 값과 휴대폰 값을 확인해주세요.", fontSize = 15.sp)
        review.differences.forEach { item ->
            NamuCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val species = state.allSpeciesList.firstOrNull { it.speciesId == item.key.speciesId }?.speciesName ?: "묘목 ${item.key.speciesId}"
                    val farm = state.farmList.firstOrNull { it.farmId == item.key.farmId }?.farmName ?: "농원 ${item.key.farmId}"
                    Text("$species · $farm · ${listOf(item.key.age, item.key.size, item.key.plantType, item.key.quality).filter { it.isNotBlank() }.joinToString(" · ")}")
                    Text("휴대폰 ${farmNumber(item.local?.quantity ?: 0)}주 · 도매 ${farmNumber(item.local?.wholesalePrice ?: 0)}원 · 소매 ${farmNumber(item.local?.retailPrice ?: 0)}원", fontSize = 14.sp)
                    Text(if (item.remote == null) "서버 재고 없음" else
                        "서버 ${farmNumber(item.remote.quantity)}주 · 도매 ${farmNumber(item.remote.wholesalePrice)}원 · 소매 ${farmNumber(item.remote.retailPrice)}원", fontSize = 14.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NamuSecondaryButton(if (review.firstLink) "서버 값으로 맞추기" else "서버 값 사용", { confirm = true }, Modifier.weight(1f))
            NamuPrimaryButton(if (review.firstLink) "휴대폰 값 올리기" else "휴대폰 값 다시 올리기", { confirm = false }, Modifier.weight(1f))
        }
    }
    confirm?.let { useServer -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("재고 선택 확인") },
        text = { Text(if (useServer) "표시된 항목을 서버 값으로 맞출까요?" else "표시된 휴대폰 값을 서버에 올릴까요?") },
        confirmButton = { TextButton(onClick = { confirm = null; onResolve(useServer); onClose() }) { Text("계속") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("취소") } }) }
}

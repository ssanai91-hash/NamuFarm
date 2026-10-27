package com.example.namufarm.ui.farm

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.namufarm.data.DataBackupController
import com.example.namufarm.quotemode.components.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun BackupMenu(controller: DataBackupController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf(false) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { scope.launch { controller.export(it) } }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { controller.inspectArchive(it) } }
    }
    Box {
        IconButton(onClick = { menu = true }, enabled = !state.busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "자료 백업 메뉴") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("자료 백업 만들기") }, onClick = { menu = false; warning = true })
            DropdownMenuItem(text = { Text("백업에서 복원") }, onClick = { menu = false; open.launch(arrayOf("application/zip", "application/x-zip-compressed")) })
        }
    }
    if (warning) AlertDialog(onDismissRequest = { warning = false }, title = { Text("자료 백업 만들기") },
        text = { Text("백업 파일에는 고객 전화번호·주소가 들어 있어요. 다른 사람과 공유하지 말고 안전한 곳에 보관하세요.\n백업 중 저장 작업을 잠시 멈추고 완료 후 화면을 다시 엽니다.") },
        confirmButton = { TextButton(onClick = { warning = false; create.launch(controller.filename()) }) { Text("저장 위치 선택") } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text("취소") } })
    if (!state.busy) state.preview?.let { preview ->
        val rows = preview.manifest.databases.first { it.name == "NamuFarm.db" }.rows
        FarmSheet("백업에서 복원", controller::dismissPreview) {
            Text("백업 시각 · " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(preview.manifest.createdAt)))
            Text("농원 ${rows["farm_info"] ?: 0} · 묘목 ${rows["species"] ?: 0} · 재고 ${rows["farm_inventory"] ?: 0}\n" +
                "매매 견적 ${rows["quote_list"] ?: 0} · 주문 ${rows["order_list"] ?: 0} · 전송 대기 ${rows["pending_sync"] ?: 0}", fontSize = 15.sp)
            FarmInfo("현재 휴대폰 자료를 이 백업으로 바꿉니다. 바꾸기 전에 현재 자료를 자동으로 백업해요.", warning = true)
            Text("복원 후 앱을 다시 시작합니다. 동기화가 안 되면 서버 기기 연결을 열어주세요.", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NamuSecondaryButton("취소", controller::dismissPreview, Modifier.weight(1f))
                NamuPrimaryButton("복원", { scope.launch { controller.restore() } }, Modifier.weight(1.6f))
            }
        }
    }
    if (state.busy) Dialog(onDismissRequest = {}) {
        NamuCard {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text("자료를 보존하는 중입니다. 잠시 기다려주세요.")
            }
        }
    }
    LaunchedEffect(state.message) { state.message?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show(); controller.clearMessage()
    } }
}

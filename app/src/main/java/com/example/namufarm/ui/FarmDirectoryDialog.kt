package com.example.namufarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.FarmEntity
import com.example.namufarm.data.remote.FarmDirectoryRecord
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.webrequests.WebRequestConnection
import com.google.gson.Gson
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FarmDirectoryDialog(connection: WebRequestConnection, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var farms by remember { mutableStateOf(emptyList<FarmDirectoryRecord>()) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FarmDirectoryRecord?>(null) }
    var operationId by rememberSaveable { mutableStateOf("") }
    var operationSignature by rememberSaveable { mutableStateOf("") }

    fun refresh() {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try {
                val received = withContext(Dispatchers.IO) { connection.farmDirectory() }
                farms = received; loaded = true
                message = "농원 ${received.size}곳을 가져왔습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "❌ ${e.message ?: "농원 목록을 가져오지 못했습니다."}" }
            finally { busy = false }
        }
    }
    LaunchedEffect(connection) {
        farms = withContext(Dispatchers.IO) { connection.cachedFarms() }
        loaded = true
        message = if (farms.isEmpty()) "처음 사용할 때 새로고침을 눌러 농원을 가져오세요."
            else "로컬에 저장된 농원 ${farms.size}곳입니다."
    }

    NamuDialog("농원 관리", "농원명 · 주소 · 전화번호 · 메모",
        onDismiss = { if (!busy) onDismiss() },
        actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("새로고침")
                }
                Button(onClick = { selected = null; editing = true; message = null
                    operationId = ""; operationSignature = "" },
                    enabled = loaded && !busy, modifier = Modifier.weight(1f)) { Text("농원 추가") }
            }
        }) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("❌")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
            if (loaded && farms.isEmpty() && !busy) Text("등록된 농원이 없습니다. 새로고침하거나 농원을 추가하세요.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(farms, key = { it.farmId }) { farm ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        selected = farm; editing = true; message = null
                        operationId = ""; operationSignature = ""
                    }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(farm.farmName, fontWeight = FontWeight.Bold)
                            if (farm.phone.isNotBlank()) Text(farm.phone)
                            if (farm.address.isNotBlank()) Text(farm.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (farm.memo.isNotBlank()) Text(farm.memo, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (editing) FarmManageDialog(selectedFarm = selected?.asFarm(),
        onDismiss = { if (!busy) { editing = false; message = null } },
        onSave = { id, name, address, phone, memo ->
            if (!busy) {
                val expectedVersion = if (id == null) "" else selected?.takeIf { it.farmId == id }?.version.orEmpty()
                val input = FarmEntity(id, name, address, phone, memo)
                val signature = Gson().toJson(listOf(input, expectedVersion))
                if (signature != operationSignature) {
                    operationSignature = signature; operationId = UUID.randomUUID().toString()
                }
                val token = operationId
                busy = true; message = null
                scope.launch {
                    try {
                        val saved = withContext(Dispatchers.IO) { connection.saveDirectoryFarm(input, expectedVersion, token) }
                        farms = (farms.filter { it.farmId != saved.farmId } + saved).sortedBy { it.farmName }
                        selected = null; editing = false; operationId = ""; operationSignature = ""
                        message = "농원을 저장했습니다."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ ${e.message ?: "농원을 저장하지 못했습니다. 다시 시도해주세요."}" }
                    finally { busy = false }
                }
            }
        },
        onDelete = { id ->
            val target = selected?.takeIf { it.farmId == id }
            if (!busy && target != null) {
                busy = true; message = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { connection.deleteDirectoryFarm(target) }
                        farms = farms.filter { it.farmId != id }
                        selected = null; editing = false; operationId = ""; operationSignature = ""
                        message = "농원을 삭제했습니다."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ ${e.message ?: "농원을 삭제하지 못했습니다."}" }
                    finally { busy = false }
                }
            }
        }, busy = busy, message = message?.takeIf { it.startsWith("❌") }, directoryOnly = true)
}

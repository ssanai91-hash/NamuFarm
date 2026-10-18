package com.example.namufarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.remote.SpeciesDirectoryRecord
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.webrequests.WebRequestConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun SpeciesDirectoryDialog(connection: WebRequestConnection, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var species by remember { mutableStateOf(emptyList<SpeciesDirectoryRecord>()) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SpeciesDirectoryRecord?>(null) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(connection) {
        species = withContext(Dispatchers.IO) { connection.cachedSpecies() }
        message = if (species.isEmpty()) "처음 사용할 때 새로고침으로 묘목을 가져오세요."
            else "저장된 묘목 ${species.size}종 · 인터넷 없이 조회할 수 있습니다."
    }
    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { connection.speciesDirectory() }
                species = loaded.filter { it.isActive == 1 }.sortedBy { it.speciesName }
                message = "묘목 ${species.size}종을 저장했습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "❌ ${e.message ?: "묘목을 가져오지 못했습니다."}" }
            finally { busy = false }
        }
    }
    val matches = remember(species, query) {
        species.filter { it.speciesName.contains(query.trim(), ignoreCase = true) ||
            it.farmName.contains(query.trim(), ignoreCase = true) ||
            it.chosung.contains(query.trim()) }.sortedBy { it.speciesName }
    }
    NamuDialog("묘목 관리", "저장된 묘목을 바로 확인하고 필요할 때만 새로고침합니다.",
        onDismiss = { if (!busy) onDismiss() },
        actions = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text("새로고침") }
                Button(onClick = { selected = null; editing = true },
                    enabled = !busy, modifier = Modifier.weight(1f)) { Text("묘목 추가") }
            }
        }) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message,
                color = if (message.startsWith("❌")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary)
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("묘목명 또는 초성 검색") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Text("묘목 ${matches.size}종", fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(matches, key = { it.speciesId }) { row ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        selected = row; editing = true
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.speciesName, fontWeight = FontWeight.Bold)
                            if (row.farmName.isNotBlank() && row.farmName != row.speciesName)
                                Text("농원사용명: ${row.farmName}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (editing) SpeciesManageDialog(
        selectedSpecies = selected?.asSpecies(),
        onDismiss = { if (!busy) editing = false },
        onSave = { id, name ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val source = selected?.takeIf { it.speciesId == id }
                        val candidate = (source ?: SpeciesDirectoryRecord()).copy(
                            speciesId = id ?: 0, speciesName = name.trim(),
                            chosung = if (source?.speciesName == name.trim()) source.chosung
                                else getChosungFromText(name.trim())
                        )
                        val saved = withContext(Dispatchers.IO) {
                            connection.saveDirectorySpecies(candidate, UUID.randomUUID().toString())
                        }
                        species = (species.filter { it.speciesId != saved.speciesId } + saved)
                            .filter { it.isActive == 1 }.sortedBy { it.speciesName }
                        editing = false; selected = null
                        message = "묘목을 저장했습니다."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ ${e.message ?: "저장하지 못했습니다."}" }
                    finally { busy = false }
                }
            }
        },
        onDelete = { id ->
            val target = selected?.takeIf { it.speciesId == id }
            if (!busy && target != null) {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { connection.deleteDirectorySpecies(target) }
                        species = species.filter { it.speciesId != id }
                        editing = false; selected = null; message = "묘목을 비활성 처리했습니다."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ ${e.message ?: "비활성 처리하지 못했습니다."}" }
                    finally { busy = false }
                }
            }
        }
    )
}

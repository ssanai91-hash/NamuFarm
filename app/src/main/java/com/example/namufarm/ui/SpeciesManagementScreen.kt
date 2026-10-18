package com.example.namufarm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.SpeciesEntity
import com.example.namufarm.data.remote.SpeciesDirectoryRecord
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.ManagementViewModel
import com.example.namufarm.webrequests.WebRequestConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val consonants = listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")

/** Reuses the initial program's large, easy-to-tap chooser and the existing species DB. */
@Composable
internal fun SpeciesChosungDialog(
    species: List<SpeciesEntity>,
    onChoose: (SpeciesEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var consonant by rememberSaveable { mutableStateOf("ㄱ") }
    var search by rememberSaveable { mutableStateOf("") }
    val matched = remember(species, consonant, search) {
        species.filter { item ->
            if (search.trim().isNotEmpty()) {
                item.speciesName.contains(search.trim(), ignoreCase = true) ||
                    item.nameFarm.orEmpty().contains(search.trim(), ignoreCase = true) ||
                    item.chosung.orEmpty().contains(search.trim())
            } else (item.chosung?.takeIf { it.isNotBlank() } ?: getChosungFromText(item.speciesName)) == consonant
        }.sortedBy { it.speciesName }
    }
    NamuDialog("묘목 초성검색", "초성을 누르고 묘목명을 선택하세요.", onDismiss,
        actions = {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
        }) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            consonants.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { letter ->
                        FilledTonalButton(onClick = { consonant = letter; search = "" },
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (consonant == letter) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (consonant == letter) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        ) { Text(letter, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            OutlinedTextField(value = search, onValueChange = { search = it },
                label = { Text("묘목명 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("검색 결과 " + matched.size + "종", style = MaterialTheme.typography.titleSmall)
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(matched.chunked(2)) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { item ->
                            OutlinedButton(onClick = { onChoose(item) },
                                modifier = Modifier.weight(1f).heightIn(min = 55.dp),
                                contentPadding = PaddingValues(6.dp)) {
                                Text(item.speciesName, maxLines = 2)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpeciesManagementScreen(
    viewModel: ManagementViewModel,
    connection: WebRequestConnection,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var showChosung by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SpeciesEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val species = state.allSpeciesList
    val visible = remember(species, search) {
        species.filter { row ->
            row.speciesName.contains(search.trim(), ignoreCase = true) ||
                row.nameFarm.orEmpty().contains(search.trim(), ignoreCase = true) ||
                row.chosung.orEmpty().contains(search.trim())
        }.sortedBy { it.speciesName }
    }
    fun refresh() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { connection.speciesDirectory().count { it.isActive == 1 } }
                viewModel.refreshMasterFromLocal()
                message = "묘목 " + count + "종을 가져왔습니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = "❌ " + (e.message ?: "묘목을 가져오지 못했습니다.") }
            finally { busy = false }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("묘목 관리", fontWeight = FontWeight.Bold) },
            actions = { TextButton(onClick = onBack) { Text("농장") } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primary,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("등록된 묘목 " + species.size + "종", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold)
                    Text("로컬에 저장된 묘목을 바로 조회합니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { showChosung = true }, modifier = Modifier.weight(1f)) {
                    Text("초성검색")
                }
                Button(onClick = { selected = null; editing = true }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text("묘목 추가") }
                OutlinedButton(onClick = { refresh() }, enabled = !busy,
                    modifier = Modifier.weight(1f)) { Text("가져오기") }
            }
            OutlinedTextField(value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("묘목명 또는 초성 찾기") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message,
                color = if (message.startsWith("❌")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall)
            if (visible.isEmpty()) {
                Text("묘목이 없습니다. 첫 이용 시 가져오기를 눌러주세요.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(visible, key = { it.speciesId ?: it.speciesName }) { row ->
                    Surface(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        selected = row; editing = true
                    }, shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(row.speciesName, modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Bold)
                            Text(row.chosung ?: getChosungFromText(row.speciesName),
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
    if (showChosung) SpeciesChosungDialog(species,
        onChoose = { row -> selected = row; editing = true; showChosung = false },
        onDismiss = { showChosung = false })
    if (editing) SpeciesManageDialog(selected, onDismiss = { if (!busy) editing = false },
        onSave = { id, name ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val row = SpeciesDirectoryRecord(
                            speciesId = id ?: 0, speciesName = name,
                            farmName = selected?.nameFarm.orEmpty(),
                            chosung = getChosungFromText(name),
                            category = selected?.category.orEmpty(),
                            memo = selected?.memo.orEmpty(), isActive = 1)
                        withContext(Dispatchers.IO) {
                            connection.saveDirectorySpecies(row, UUID.randomUUID().toString())
                        }
                        viewModel.refreshMasterFromLocal()
                        message = "묘목을 저장했습니다."
                        editing = false; selected = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ " + (e.message ?: "저장하지 못했습니다.") }
                    finally { busy = false }
                }
            }
        },
        onDelete = { id ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val row = requireNotNull(selected).let {
                            SpeciesDirectoryRecord(speciesId = id, speciesName = it.speciesName)
                        }
                        withContext(Dispatchers.IO) { connection.deleteDirectorySpecies(row) }
                        viewModel.refreshMasterFromLocal()
                        message = "묘목을 비활성 처리했습니다."
                        editing = false; selected = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = "❌ " + (e.message ?: "비활성 처리하지 못했습니다.") }
                    finally { busy = false }
                }
            }
        }, busy = busy, message = message.takeIf { it.startsWith("❌") })
}


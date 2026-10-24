package com.example.namufarm.quotemode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.namufarm.data.remote.CustomerInquiryRemote
import com.example.namufarm.data.remote.inquiryLocalTime
import androidx.compose.ui.graphics.Color
import com.example.namufarm.viewmodel.CustomerInquiryState
import com.example.namufarm.viewmodel.CustomerInquiryViewModel

private val Forest800 = Color(0xFF1C4A3B)
private val Forest700 = Color(0xFF2E7D32)
private val Forest50 = Color(0xFFF1F7F4)
private val Ink500 = Color(0xFF69746F)
private val Amber600 = Color(0xFF825B20)
private val Canvas = Color(0xFFF4F6F2)
private val White = Color.White

@Composable
fun CustomerInquiryScreen(viewModel: CustomerInquiryViewModel) {
    val state by viewModel.state.collectAsState()
    var filter by rememberSaveable { mutableStateOf("open") }
    var search by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableIntStateOf(50) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadLocal() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val rows = state.rows.filter { row ->
        (filter == "all" || row.status == filter) && (search.isBlank() ||
            listOf(row.inquiryNumber, row.inquiry?.customerName, row.inquiry?.customerPhone,
                row.inquiry?.message).any { it.orEmpty().contains(search.trim(), ignoreCase = true) })
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("고객문의", Modifier.weight(1f), fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Forest800)
            Button(onClick = viewModel::refresh, enabled = !state.busy) { Text("불러오기", fontSize = 16.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("open" to "미처리", "closed" to "완료", "all" to "전체").forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { filter = key; visible = 50 }, label = { Text(label) })
            }
        }
        OutlinedTextField(value = search, onValueChange = { search = it; visible = 50 }, singleLine = true,
            label = { Text("이름 · 전화 · 문의 검색") }, modifier = Modifier.fillMaxWidth())
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (selected == null) state.message?.let { message ->
            Surface(color = Forest50, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 14.sp)
                    TextButton(onClick = viewModel::clearMessage) { Text("닫기") }
                }
            }
        }
        Text("${rows.size}건", color = Ink500, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (rows.isEmpty()) item {
                Text(if (state.rows.isEmpty()) "불러오기를 눌러 고객문의를 가져오세요." else "조회 조건에 맞는 문의가 없습니다.",
                    Modifier.padding(vertical = 24.dp), color = Ink500)
            }
            items(rows.take(visible), key = { it.requestId!! }) { row ->
                Card(onClick = { selected = row.requestId; viewModel.clearMessage() },
                    colors = CardDefaults.cardColors(containerColor = White), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(row.inquiry?.customerName.orEmpty(), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text(if (row.status == "closed") "완료 · closed" else "미처리 · open", color = Forest700, fontSize = 13.sp)
                        }
                        Text("${row.inquiry?.category} · ${inquiryLocalTime(row.createdAt)}", fontSize = 13.sp, color = Ink500)
                        Text(row.inquiry?.message.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(row.inquiryNumber.orEmpty(), color = Ink500, fontSize = 12.sp)
                        if (row.delivery != null || state.operations.containsKey(row.requestId)) {
                            Text(if (state.operations.containsKey(row.requestId)) "이전 작업 결과 확인 필요" else row.deliveryLabel,
                                color = Amber600, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (rows.size > visible) item {
                OutlinedButton(onClick = { visible += 50 }, modifier = Modifier.fillMaxWidth()) { Text("50건 더 보기") }
            }
        }
    }
    selected?.let { id ->
        val row = state.rows.find { it.requestId == id }
        if (row != null) InquiryDetailDialog(row, state, viewModel) { selected = null }
        else AlertDialog(onDismissRequest = { selected = null }, title = { Text("문의 확인") },
            text = { Text("문의 보관 기간이 끝났거나 원문이 없습니다.") },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("닫기") } })
    }
}

@Composable
private fun InquiryDetailDialog(row: CustomerInquiryRemote, state: CustomerInquiryState,
                                viewModel: CustomerInquiryViewModel, onClose: () -> Unit) {
    val id = row.requestId!!
    val draft = state.draft(row)
    val pending = state.operations[id]
    val editable = !state.busy && pending == null && !row.blockedDelivery
    val conflict = draft.baseRevision != row.revision
    var sendChannel by remember { mutableStateOf<String?>(null) }
    var statusTarget by remember { mutableStateOf<String?>(null) }
    var note by rememberSaveable(id) { mutableStateOf("") }
    var resolveChoice by remember { mutableStateOf<Boolean?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = White,
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f).imePadding()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("문의 확인 · 답변", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Forest800)
                    TextButton(onClick = onClose) { Text("닫기") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = Forest50, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(row.inquiryNumber.orEmpty(), fontWeight = FontWeight.Bold)
                            Text("${row.inquiry?.customerName} · ${row.inquiry?.customerPhone}", fontSize = 17.sp)
                            if (!row.inquiry?.customerEmail.isNullOrBlank()) Text(row.inquiry?.customerEmail.orEmpty())
                            Text("${row.inquiry?.category} · ${inquiryLocalTime(row.createdAt)}", fontSize = 13.sp, color = Ink500)
                            Text("${row.status} · ${row.deliveryLabel}", color = Forest700)
                        }
                    }
                    Text("고객 문의", fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(row.inquiry?.message.orEmpty(), fontSize = 17.sp) }
                    state.message?.let { Text(it, color = Amber600, fontSize = 14.sp) }
                    if (pending != null || row.blockedDelivery) {
                        Text("이전 작업의 결과를 먼저 확인해주세요. 결과가 불명확한 상태에서는 다시 발송하지 않습니다.", fontSize = 14.sp, color = Amber600)
                        OutlinedButton(onClick = { viewModel.check(id) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth()) { Text("결과 확인") }
                    }
                    if (conflict || pending?.action == "save_inquiry_reply") {
                        Text("다른 기기의 답변 또는 미확인 초안이 있습니다. 최신 서버 답변과 비교한 뒤 선택해주세요.", color = Amber600, fontSize = 14.sp)
                        Text("서버 답변: ${row.reply?.text.orEmpty()}", fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { resolveChoice = true }, enabled = !state.busy && !row.blockedDelivery,
                                modifier = Modifier.weight(1f)) { Text("서버 답변 적용") }
                            OutlinedButton(onClick = { resolveChoice = false }, enabled = !state.busy && !row.blockedDelivery,
                                modifier = Modifier.weight(1f)) { Text("내 초안 유지") }
                        }
                    }
                    OutlinedTextField(value = draft.text, onValueChange = { viewModel.edit(id, it) },
                        enabled = editable, label = { Text("답변 내용") }, minLines = 4, maxLines = 8,
                        modifier = Modifier.fillMaxWidth(), supportingText = { Text("${draft.text.length}자 · 문자 900자 / 메일 2,000자") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.channel == "sms", onClick = { viewModel.edit(id, draft.text, "sms") },
                            enabled = editable, label = { Text("문자 초안") })
                        FilterChip(selected = draft.channel == "email", onClick = { viewModel.edit(id, draft.text, "email") },
                            enabled = editable && !row.inquiry?.customerEmail.isNullOrBlank(), label = { Text("메일 초안") })
                        TextButton(onClick = { viewModel.save(id) }, enabled = editable && !conflict) { Text("초안 저장") }
                    }
                    if (row.history.orEmpty().isNotEmpty()) {
                        Text("답변 · 처리 이력", fontWeight = FontWeight.Bold)
                        row.history.orEmpty().asReversed().filterNotNull().forEach { entry ->
                            Surface(color = Canvas, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(inquiryLocalTime(entry.at), fontSize = 12.sp, color = Ink500)
                                    if (entry.kind == "status") Text("${entry.status} · ${entry.note.orEmpty()}")
                                    else {
                                        Text("${if (entry.channel == "sms") "문자" else "메일"} · ${CustomerInquiryRemote.DELIVERY_LABELS[entry.state] ?: entry.state.orEmpty()}", fontSize = 13.sp)
                                        Text(entry.text.orEmpty(), fontSize = 15.sp)
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { note = ""; statusTarget = if (row.status == "closed") "open" else "closed" },
                        enabled = !state.busy && pending == null && row.delivery?.state !in setOf("sending", "pending")) {
                        Text(if (row.status == "closed") "다시 열기" else "전화 상담 완료")
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sendChannel = "sms" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = editable && !conflict && draft.text.isNotBlank() && draft.text.trim().length <= 900) { Text("문자 보내기", fontSize = 16.sp) }
                    Button(onClick = { sendChannel = "email" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = editable && !conflict && draft.text.isNotBlank() && !row.inquiry?.customerEmail.isNullOrBlank()) { Text("메일 보내기", fontSize = 16.sp) }
                }
            }
        }
    }
    sendChannel?.let { channel ->
        AlertDialog(onDismissRequest = { sendChannel = null }, title = { Text(if (channel == "sms") "문자 발송" else "메일 발송") },
            text = { Text("${row.inquiry?.customerName}님\n${if (channel == "sms") row.inquiry?.customerPhone else row.inquiry?.customerEmail}\n\n작성한 답변을 서버에서 발송할까요?") },
            confirmButton = { TextButton(onClick = { sendChannel = null; viewModel.send(id, channel) }) { Text("발송") } },
            dismissButton = { TextButton(onClick = { sendChannel = null }) { Text("취소") } })
    }
    resolveChoice?.let { useServer ->
        AlertDialog(onDismissRequest = { resolveChoice = null }, title = { Text(if (useServer) "서버 답변 적용" else "내 초안 유지") },
            text = { Text(if (useServer) "최신 내역을 불러와 휴대폰 초안을 서버 답변으로 바꿀까요?" else "최신 내역을 불러온 뒤 내 초안을 유지할까요? 이후 저장·발송은 직접 눌러야 합니다.") },
            confirmButton = { TextButton(onClick = { resolveChoice = null; viewModel.resolve(id, useServer) }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { resolveChoice = null }) { Text("취소") } })
    }
    statusTarget?.let { target ->
        AlertDialog(onDismissRequest = { statusTarget = null }, title = { Text(if (target == "closed") "전화 상담 완료" else "문의 다시 열기") },
            text = { OutlinedTextField(value = note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("처리 메모") }, minLines = 2) },
            confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { statusTarget = null; viewModel.status(id, target, note) }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { statusTarget = null }) { Text("취소") } })
    }
}

package com.example.namufarm.quotemode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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

private val Forest800 = InquiryDesign.ink
private val Forest700 = InquiryDesign.green
private val Forest50 = InquiryDesign.mint
private val Ink500 = InquiryDesign.muted
private val Amber600 = InquiryDesign.amber
private val Canvas = InquiryDesign.canvas
private val White = Color.White

@OptIn(ExperimentalMaterial3Api::class)
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
    Scaffold(containerColor = Canvas, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(title = { Text("나무팜 문의처리", fontWeight = FontWeight.ExtraBold) },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ModeDesign.inquiry,
                titleContentColor = Color.White, actionIconContentColor = Color.White),
            actions = {
                TextButton(onClick = viewModel::refresh, enabled = !state.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Icon(Icons.Outlined.CloudSync, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("불러오기", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            })
    }) { padding ->
    Column(Modifier.fillMaxSize().padding(padding).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InquiryChoice("미처리", state.rows.count { it.status == "open" }, filter == "open",
                InquiryDesign.amber, InquiryDesign.honey, Icons.AutoMirrored.Outlined.Chat,
                Modifier.weight(1f)) { filter = "open"; visible = 50 }
            InquiryChoice("완료", state.rows.count { it.status == "closed" }, filter == "closed",
                Forest700, Forest50, Icons.Outlined.CheckCircleOutline,
                Modifier.weight(1f)) { filter = "closed"; visible = 50 }
            InquiryChoice("전체", state.rows.size, filter == "all", InquiryDesign.blue, InquiryDesign.sky,
                Icons.Outlined.FormatListBulleted, Modifier.weight(1f)) { filter = "all"; visible = 50 }
        }
        OutlinedTextField(value = search, onValueChange = { search = it; visible = 50 }, singleLine = true,
            placeholder = { Text("이름 · 전화 · 문의 검색", fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = InquiryDesign.blue) },
            shape = InquiryDesign.shape, colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ModeDesign.inquiry, unfocusedBorderColor = ModeDesign.line,
                focusedContainerColor = Color.White, unfocusedContainerColor = Color.White),
            modifier = Modifier.fillMaxWidth())
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = InquiryDesign.blue, trackColor = InquiryDesign.sky)
        if (selected == null) state.message?.let { message ->
            Surface(color = Forest50, shape = InquiryDesign.shape, border = BorderStroke(1.dp, Forest700)) {
                Row(Modifier.padding(start = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 14.sp)
                    TextButton(onClick = viewModel::clearMessage) { Text("닫기") }
                }
            }
        }
        Text("문의 ${rows.size}건", color = Ink500, fontSize = 14.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (rows.isEmpty()) item {
                InquiryEmpty(state.rows.isEmpty())
            }
            items(rows.take(visible), key = { it.requestId!! }) { row ->
                InquiryListCard(row, state.operations.containsKey(row.requestId)) {
                    selected = row.requestId; viewModel.clearMessage()
                }
            }
            if (rows.size > visible) item {
                OutlinedButton(onClick = { visible += 50 }, modifier = Modifier.fillMaxWidth(),
                    shape = InquiryDesign.shape, border = BorderStroke(1.dp, InquiryDesign.blue)) {
                    Text("50건 더 보기", color = InquiryDesign.blue)
                }
            }
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
    var showOriginal by rememberSaveable(id) { mutableStateOf(false) }
    var showHistory by rememberSaveable(id) { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(12.dp), color = White, border = BorderStroke(1.5.dp, Forest700),
            modifier = Modifier.imePadding().widthIn(max = 560.dp).fillMaxWidth(0.96f).fillMaxHeight(0.96f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Use the available screen height for the editor. Small/keyboard/error layouts
                // retain a scroll fallback so confirmations and recovery controls stay reachable.
                val scrollBody = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.2f ||
                    pending != null || row.blockedDelivery || conflict
                Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("문의 확인 · 답변", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forest800, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { showHistory = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Icon(Icons.Outlined.History, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(3.dp)); Text("이력", fontSize = 13.sp)
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Close, "닫기", tint = Forest800)
                        }
                    }
                    Column(Modifier.weight(1f).then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(color = Forest50, shape = InquiryDesign.shape, border = BorderStroke(1.dp, Forest700)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(InquiryDesign.categoryIcon(row.inquiry?.category), null, tint = Forest700, modifier = Modifier.size(20.dp))
                                    Text(row.inquiry?.customerName.orEmpty(), Modifier.weight(1f), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = FontWeight.Bold, color = Forest800)
                                    InquiryStatus(row.status)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Outlined.Phone, null, tint = Forest700, modifier = Modifier.size(17.dp))
                                    SelectionContainer { Text(row.inquiry?.customerPhone.orEmpty(), fontSize = 15.sp) }
                                }
                                if (!row.inquiry?.customerEmail.isNullOrBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Outlined.Email, null, tint = Forest700, modifier = Modifier.size(17.dp))
                                        SelectionContainer { Text(row.inquiry?.customerEmail.orEmpty(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                Text("${row.inquiryNumber} · ${row.deliveryLabel}", fontSize = 12.sp, color = Ink500)
                            }
                        }
                        Surface(color = InquiryDesign.sky, shape = InquiryDesign.shape, border = BorderStroke(1.dp, InquiryDesign.blue)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = InquiryDesign.blue, modifier = Modifier.size(18.dp))
                                    Text("고객 문의 · ${row.inquiry?.category}", Modifier.weight(1f), fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold, color = InquiryDesign.blue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { showOriginal = true }, modifier = Modifier.height(32.dp),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("원문", fontSize = 12.sp) }
                                }
                                Text(inquiryLocalTime(row.createdAt), fontSize = 12.sp, color = Ink500)
                                SelectionContainer { Text(row.inquiry?.message.orEmpty(), fontSize = 16.sp, color = Forest800,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            }
                        }
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
                        Surface(color = White, shape = InquiryDesign.shape, border = BorderStroke(1.2.dp, Forest700),
                            modifier = Modifier.fillMaxWidth().then(if (scrollBody) Modifier else Modifier.weight(1f))) {
                            Column(Modifier.fillMaxWidth().then(if (scrollBody) Modifier else Modifier.fillMaxHeight()).padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Outlined.Edit, null, tint = Forest700, modifier = Modifier.size(19.dp))
                                    Text("답변 내용", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Forest800)
                                    OutlinedButton(onClick = { viewModel.save(id) }, enabled = editable && !conflict,
                                        border = BorderStroke(1.dp, InquiryDesign.blue), shape = RoundedCornerShape(7.dp),
                                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = InquiryDesign.blue)) {
                                        Icon(Icons.Outlined.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                                        Text("초안 저장", fontSize = 12.sp)
                                    }
                                }
                                OutlinedTextField(value = draft.text, onValueChange = { viewModel.edit(id, it) },
                                    enabled = editable, placeholder = { Text("고객에게 보낼 답변을 입력하세요.", fontSize = 15.sp) },
                                    minLines = 2, maxLines = 4, shape = RoundedCornerShape(7.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                                    colors = inquiryFieldColors(Forest700), modifier = Modifier.fillMaxWidth().then(
                                        if (scrollBody) Modifier.heightIn(min = 92.dp, max = 144.dp) else Modifier.weight(1f)))
                                Text("${draft.text.length}자 · 문자 900자 / 메일 2,000자", fontSize = 11.sp, color = Ink500)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("답변 방법", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Forest800)
                                    InquiryChannel("문자", Icons.Outlined.Sms, draft.channel == "sms", editable,
                                        InquiryDesign.blue, Modifier.weight(1f)) { viewModel.edit(id, draft.text, "sms") }
                                    InquiryChannel("메일", Icons.Outlined.Email, draft.channel == "email",
                                        editable && !row.inquiry?.customerEmail.isNullOrBlank(), InquiryDesign.coral,
                                        Modifier.weight(1f)) { viewModel.edit(id, draft.text, "email") }
                                }
                            }
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = InquiryDesign.blue, trackColor = InquiryDesign.sky)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        InquirySendButton("문자 보내기", Icons.Outlined.Sms, InquiryDesign.blue,
                            editable && !conflict && draft.text.isNotBlank() && draft.text.trim().length <= 900,
                            Modifier.weight(1f)) { sendChannel = "sms" }
                        InquirySendButton("메일 보내기", Icons.Outlined.Email, InquiryDesign.coral,
                            editable && !conflict && draft.text.isNotBlank() && !row.inquiry?.customerEmail.isNullOrBlank(),
                            Modifier.weight(1f)) { sendChannel = "email" }
                    }
                    OutlinedButton(onClick = { note = ""; statusTarget = if (row.status == "closed") "open" else "closed" },
                        enabled = !state.busy && pending == null && row.delivery?.state !in setOf("sending", "pending"),
                        border = BorderStroke(1.2.dp, Forest700), shape = InquiryDesign.shape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Icon(if (row.status == "closed") Icons.Outlined.Refresh else Icons.Outlined.Phone, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (row.status == "closed") "다시 열기" else "전화 상담 완료", fontSize = 15.sp)
                    }
                }
        }
        }
    }
    if (showOriginal) AlertDialog(onDismissRequest = { showOriginal = false }, containerColor = White,
        title = { Text("고객 문의 원문") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${row.inquiry?.customerName.orEmpty()} · ${inquiryLocalTime(row.createdAt)}", fontSize = 13.sp, color = Ink500)
                    Text(row.inquiry?.customerPhone.orEmpty(), fontSize = 14.sp)
                    if (!row.inquiry?.customerEmail.isNullOrBlank()) Text(row.inquiry?.customerEmail.orEmpty(), fontSize = 14.sp)
                    Text(row.inquiry?.message.orEmpty(), fontSize = 17.sp, color = Forest800)
                }
            }
        }, confirmButton = { TextButton(onClick = { showOriginal = false }) { Text("닫기") } })
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, containerColor = White,
        title = { Text("답변 · 처리 이력") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.history.orEmpty().isEmpty()) Text("등록된 답변 이력이 없습니다.", fontSize = 14.sp, color = Ink500)
                row.history.orEmpty().asReversed().filterNotNull().forEach { entry ->
                    Surface(color = Canvas, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, InquiryDesign.line)) {
                        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(inquiryLocalTime(entry.at), fontSize = 12.sp, color = Ink500)
                            if (entry.kind == "status") Text("${InquiryDesign.status(entry.status)} · ${entry.note.orEmpty()}")
                            else {
                                Text("${if (entry.channel == "sms") "문자" else "메일"} · ${CustomerInquiryRemote.DELIVERY_LABELS[entry.state] ?: entry.state.orEmpty()}", fontSize = 13.sp)
                                SelectionContainer { Text(entry.text.orEmpty(), fontSize = 15.sp) }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("닫기") } })
    sendChannel?.let { channel ->
        AlertDialog(onDismissRequest = { sendChannel = null }, containerColor = White, title = { Text(if (channel == "sms") "문자 발송" else "메일 발송") },
            text = { Text("${row.inquiry?.customerName}님\n${if (channel == "sms") row.inquiry?.customerPhone else row.inquiry?.customerEmail}\n\n작성한 답변을 서버에서 발송할까요?") },
            confirmButton = { TextButton(onClick = { sendChannel = null; viewModel.send(id, channel) }) { Text("발송") } },
            dismissButton = { TextButton(onClick = { sendChannel = null }) { Text("취소") } })
    }
    resolveChoice?.let { useServer ->
        AlertDialog(onDismissRequest = { resolveChoice = null }, containerColor = White, title = { Text(if (useServer) "서버 답변 적용" else "내 초안 유지") },
            text = { Text(if (useServer) "최신 내역을 불러와 휴대폰 초안을 서버 답변으로 바꿀까요?" else "최신 내역을 불러온 뒤 내 초안을 유지할까요? 이후 저장·발송은 직접 눌러야 합니다.") },
            confirmButton = { TextButton(onClick = { resolveChoice = null; viewModel.resolve(id, useServer) }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { resolveChoice = null }) { Text("취소") } })
    }
    statusTarget?.let { target ->
        AlertDialog(onDismissRequest = { statusTarget = null }, containerColor = White, title = { Text(if (target == "closed") "전화 상담 완료" else "문의 다시 열기") },
            text = { OutlinedTextField(value = note, onValueChange = { if (it.length <= 500) note = it }, label = { Text("처리 메모") }, minLines = 2) },
            confirmButton = { TextButton(enabled = note.isNotBlank(), onClick = { statusTarget = null; viewModel.status(id, target, note) }) { Text("확인") } },
            dismissButton = { TextButton(onClick = { statusTarget = null }) { Text("취소") } })
    }
}



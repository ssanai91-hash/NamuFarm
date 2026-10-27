package com.example.namufarm.quotemode

import com.example.namufarm.quotemode.components.*
import com.example.namufarm.ui.theme.*
import android.content.Intent
import android.net.Uri
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
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
import com.example.namufarm.data.CustomerInquiryDraftEntity
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
        NamuTopBar("문의", masterSyncLabel(state.lastSyncAt), onSync = { if (!state.busy) viewModel.refresh() })
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
    var preparedDraft by remember { mutableStateOf<CustomerInquiryDraftEntity?>(null) }
    val context = LocalContext.current
    var actionError by remember { mutableStateOf<String?>(null) }
    val green = InquiryReplyDesign.green
    val ink = InquiryReplyDesign.ink
    val muted = InquiryReplyDesign.muted
    val line = InquiryReplyDesign.line
    fun prepareReply(channel: String) {
        preparedDraft = draft
        sendChannel = channel
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(12.dp), color = InquiryReplyDesign.canvas,
            modifier = Modifier.imePadding().widthIn(max = 560.dp).fillMaxWidth(0.96f).fillMaxHeight(0.96f)) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val scrollBody = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.2f ||
                    pending != null || row.blockedDelivery || conflict
                Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("문의 확인 · 답변", Modifier.weight(1f), fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { showHistory = true }, contentPadding = PaddingValues(horizontal = 6.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = green)) {
                            Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(3.dp)); Text("이력", fontSize = 13.sp)
                        }
                        IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Close, "닫기", tint = ink)
                        }
                    }
                    HorizontalDivider(color = line)
                    Column(Modifier.weight(1f).then(if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(color = White, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.inquiry?.customerName.orEmpty(), Modifier.weight(1f), fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Surface(color = if (row.status == "closed") Color(0xFFEAF3EB) else Color(0xFFFFF2DF),
                                        shape = RoundedCornerShape(7.dp)) {
                                        Text(InquiryDesign.status(row.status), Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            color = if (row.status == "closed") green else Amber600,
                                            fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Row(Modifier.clickable {
                                    actionError = runCatching {
                                        context.startActivity(Intent(Intent.ACTION_DIAL,
                                            Uri.fromParts("tel", row.inquiry?.customerPhone.orEmpty(), null)))
                                        null
                                    }.getOrElse { "전화 앱을 열 수 없습니다." }
                                }.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Icon(Icons.Outlined.Phone, null, tint = green, modifier = Modifier.size(20.dp))
                                    Text(PhoneNumberUtils.formatNumber(row.inquiry?.customerPhone.orEmpty(), "KR")
                                        ?: row.inquiry?.customerPhone.orEmpty(), fontSize = 16.sp, color = green)
                                }
                                if (!row.inquiry?.customerEmail.isNullOrBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                        Icon(Icons.Outlined.Email, null, tint = green, modifier = Modifier.size(20.dp))
                                        SelectionContainer { Text(row.inquiry?.customerEmail.orEmpty(), fontSize = 14.sp, color = ink,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                Text("${row.inquiryNumber} · ${row.deliveryLabel}", fontSize = 12.sp, color = muted)
                            }
                        }
                        HorizontalDivider(color = line)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.Chat, null, tint = muted, modifier = Modifier.size(20.dp))
                                Text("고객 문의 · ${row.inquiry?.category}", Modifier.weight(1f), fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { showOriginal = true }, modifier = Modifier.height(36.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = green),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("원문", fontSize = 13.sp) }
                            }
                            Text(inquiryLocalTime(row.createdAt), fontSize = 12.sp, color = muted)
                            Surface(color = White, shape = RoundedCornerShape(14.dp)) {
                                SelectionContainer { Text(row.inquiry?.message.orEmpty(), Modifier.fillMaxWidth().padding(12.dp),
                                    fontSize = 16.sp, color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                        actionError?.let { Text(it, color = Amber600, fontSize = 13.sp) }
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
                        Surface(color = White, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, line),
                            modifier = Modifier.fillMaxWidth().then(if (scrollBody) Modifier else Modifier.weight(1f))) {
                            Column(Modifier.fillMaxWidth().then(if (scrollBody) Modifier else Modifier.fillMaxHeight()).padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Icons.Outlined.Edit, null, tint = green, modifier = Modifier.size(21.dp))
                                    Text("답변 내용", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink)
                                    TextButton(onClick = { viewModel.save(id) }, enabled = editable && !conflict,
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = green)) {
                                        Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                        Text("초안 저장", fontSize = 13.sp)
                                    }
                                }
                                OutlinedTextField(value = draft.text, onValueChange = { viewModel.edit(id, it) },
                                    enabled = editable, placeholder = { Text("답변을 입력하세요.", fontSize = 16.sp) },
                                    minLines = 2, maxLines = 6, shape = RoundedCornerShape(8.dp),
                                    textStyle = LocalTextStyle.current.copy(fontSize = 16.sp),
                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = green, unfocusedBorderColor = line,
                                        focusedContainerColor = White, unfocusedContainerColor = White,
                                        focusedTextColor = ink, unfocusedTextColor = ink, cursorColor = green,
                                        focusedPlaceholderColor = muted, unfocusedPlaceholderColor = muted),
                                    modifier = Modifier.fillMaxWidth().then(
                                        if (scrollBody) Modifier.heightIn(min = 112.dp, max = 180.dp) else Modifier.weight(1f)))
                                Text("${draft.text.length}자 · 전송창에서 인사말·마무리 자동 작성", fontSize = 11.sp, color = muted)
                            }
                        }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = green, trackColor = line)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        InquirySendButton("문자", Icons.Outlined.Sms, green,
                            editable && !conflict && draft.text.isNotBlank(), Modifier.weight(1f)) { prepareReply("sms") }
                        InquirySendButton("메일", Icons.Outlined.Email, green,
                            editable && !conflict && draft.text.isNotBlank() && !row.inquiry?.customerEmail.isNullOrBlank(),
                            Modifier.weight(1f)) { prepareReply("email") }
                        OutlinedButton(onClick = {
                            note = if (row.status == "closed") "문의 다시 열기" else "전화 상담 완료"
                            statusTarget = if (row.status == "closed") "open" else "closed"
                        },
                            enabled = !state.busy && pending == null && !row.blockedDelivery,
                            border = BorderStroke(1.dp, line), shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = muted, containerColor = White),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(if (row.status == "closed") Icons.Outlined.Refresh else Icons.Outlined.Phone, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (row.status == "closed") "다시 열기" else "상담완료", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
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
        val sourceDraft = preparedDraft
        if (sourceDraft != null) InquiryReplyDialog(row, channel, sourceDraft.text,
            enabled = editable && !conflict && draft == sourceDraft,
            onDismiss = { sendChannel = null },
            onSend = { text -> sendChannel = null; viewModel.send(id, channel, text, sourceDraft) })
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




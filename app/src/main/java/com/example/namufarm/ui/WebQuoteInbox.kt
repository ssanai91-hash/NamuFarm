package com.example.namufarm.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.data.WebQuoteItem
import com.example.namufarm.phone.openSmsComposer
import com.example.namufarm.ui.components.*
import com.example.namufarm.viewmodel.WebInboxState
import com.google.gson.Gson

@Composable
internal fun WebQuoteInboxDialog(
    state: WebInboxState, initialRequestId: String?, customerPhone: String, cartItemCount: Int,
    onRefresh: () -> Unit, onPair: (String) -> Unit, onRead: (String) -> Unit,
    onUsePhone: (String) -> Unit, onMessageConsumed: () -> Unit, onDismiss: () -> Unit
) {
    // Keep the selected customer stable if another notification arrives while composing.
    var selectedId by rememberSaveable { mutableStateOf(initialRequestId) }
    var invite by remember { mutableStateOf("") } // Credentials never enter saved-state backups.
    var connecting by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val selected = state.requests.find { it.requestId == selectedId }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(selected?.requestId) { selected?.let { onRead(it.requestId) } }
    LaunchedEffect(state.paired) { if (state.paired) { invite = ""; connecting = false } }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageConsumed() } }
    NamuDialog(
        title = if (selected == null) "홈페이지 요청함" else "견적 요청 상세",
        subtitle = "고객 문의 · 주문 확정 전 재고 변동 없음",
        onDismiss = onDismiss, snackbarHostState = snackbar,
        actions = {
            if (selected != null) {
                WebRequestCustomerActions(selected, customerPhone, cartItemCount, onUsePhone)
                OutlinedButton(onClick = { selectedId = null }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("요청 목록")
                }
            } else {
                Button(onClick = onRefresh, enabled = state.paired && !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (state.busy) "요청 확인 중…" else "새 요청 가져오기")
                }
            }
        }
    ) {
        if (selected != null) {
            WebRequestDetail(selected)
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("web_inbox"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    NamuSectionTitle("고객이 찾는 나무", "전체 ${state.requests.size}건 · 안 읽음 ${state.unread}건")
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                }
                if (selectedId != null) item {
                    Text("선택한 요청을 아직 가져오지 못했어요. 연결 상태를 확인하고 다시 가져와주세요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.configured) item {
                    NamuEmptyState("홈페이지 연결 준비 중", "요청함 주소가 설정된 앱을 설치하면 홈페이지 문의를 받을 수 있어요.", Icons.Outlined.Language)
                } else if (!state.paired || connecting) item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("관리자에게 받은 연결 코드를 입력해주세요.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(invite, { invite = it.trim() }, label = { Text("기기 연결 코드") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Button(onClick = { onPair(invite) }, enabled = invite.length == 64 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("요청함 연결") }
                    }
                }
                if (state.paired) item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.pushConfigured) TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(8.dp)); Text("새 요청 알림 허용") }
                        else Text("푸시 설정 전에는 ‘새 요청 가져오기’로 확인해주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { connecting = !connecting }) { Text("연결 코드 다시 등록") }
                    }
                }
                if (state.requests.isEmpty() && state.paired && !state.busy) item {
                    NamuEmptyState("아직 받은 요청이 없어요", "고객이 홈페이지에서 남긴 묘목, 수량과 연락처가 여기에 모입니다.", Icons.Outlined.Inbox)
                }
                items(state.requests, key = { it.requestId }) { request ->
                    Card(onClick = { selectedId = request.requestId }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(request.customerName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                NamuBadge(if (request.readAt == null) "새 요청" else "확인함")
                            }
                            Text(request.customerPhone, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(requestItems(request).joinToString(" · ") { "${it.species} ${it.quantity}주" }, style = MaterialTheme.typography.bodyMedium)
                            Text(request.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

internal fun requestItems(request: WebQuoteRequestEntity): List<WebQuoteItem> =
    Gson().fromJson(request.itemsJson, Array<WebQuoteItem>::class.java).toList()

@Composable
internal fun WebRequestDetail(request: WebQuoteRequestEntity) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("web_request_detail"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NamuBadge("홈페이지 견적 문의")
        Text(request.customerName, style = MaterialTheme.typography.headlineSmall)
        Text(request.customerPhone, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("배송 희망 지역 · ${request.region}", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        requestItems(request).forEach { item ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.species, style = MaterialTheme.typography.titleMedium)
                    Text(item.specification.ifBlank { "규격 상담 희망" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${String.format(java.util.Locale.KOREA, "%,d", item.quantity)}주", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (request.message.isNotBlank()) { NamuSectionTitle("고객 요청사항"); Text(request.message) }
        Text("재고와 규격을 확인한 뒤 판매 품목을 선택해 견적을 작성해주세요. 홈페이지 문의는 실제 재고나 수기 견적 품목으로 자동 등록되지 않습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("접수번호 ${request.requestId}\n${request.createdAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun WebRequestCustomerActions(request: WebQuoteRequestEntity, customerPhone: String, cartItemCount: Int, onUsePhone: (String) -> Unit) {
    var confirm by rememberSaveable(request.requestId) { mutableStateOf(false) }
    var smsError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Button(onClick = {
        if (customerPhone != request.customerPhone && (customerPhone.isNotBlank() || cartItemCount > 0)) confirm = true
        else onUsePhone(request.customerPhone)
    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Description, null); Spacer(Modifier.width(8.dp)); Text("이 고객 번호로 견적 작성") }
    OutlinedButton(onClick = {
        smsError = openSmsComposer(context, request.customerPhone, "[나무팜] ${request.customerName}님, 묘목 견적 문의를 확인했습니다. 요청하신 품목과 규격을 확인한 후 안내드리겠습니다.")
    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Sms, null); Spacer(Modifier.width(8.dp)); Text("접수 안내 문자 작성") }
    smsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("견적 고객 번호를 변경할까요?") },
        text = { Text("작성 중인 견적의 번호를 ${request.customerPhone}(으)로 변경합니다. 담긴 품목 ${cartItemCount}개는 유지됩니다.") },
        confirmButton = { TextButton(onClick = { confirm = false; onUsePhone(request.customerPhone) }) { Text("번호 변경") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("돌아가기") } })
}

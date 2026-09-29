package com.example.namufarm.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.namufarm.phone.*
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.ui.components.NamuEmptyState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun IncomingCallAssistDialog(
    request: CallAssistRequest?,
    customerPhone: String,
    cartItemCount: Int,
    onUseNumber: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { IncomingCallStore(context) }
    // Freeze the visible caller: a second call must not change an open reply's recipient.
    val caller by rememberSaveable(request?.callerId, stateSaver = listSaver<IncomingCaller?, Any>(
        save = { value -> value?.let { listOf(it.id, it.number, it.receivedAt) } ?: emptyList() },
        restore = { values -> if (values.isEmpty()) null else IncomingCaller(values[0] as String, values[1] as String, values[2] as Long) }
    )) {
        mutableStateOf(if (request == null) store.latest() else store.find(request.callerId))
    }
    var cleared by rememberSaveable(request?.callerId) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var reply by rememberSaveable(request?.callerId) { mutableStateOf(request?.reply == true && caller != null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(store, lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        IncomingCallNotifications.createChannel(context)
        if (caller?.id == store.latest()?.id) IncomingCallNotifications.cancel(context)
        onDispose {
            store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }
    val enabled = remember(revision) { store.enabled }
    val permissionsGranted = remember(revision) { hasCallerPermissions(context) }
    val notificationsGranted = remember(revision) { IncomingCallNotifications.allowed(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val granted = hasCallerPermissions(context)
        store.setEnabled(granted)
        revision++
        if (!granted) scope.launch {
            snackbar.showSnackbar("전화·통화 기록 권한이 필요해요. 허용할 수 없는 기기에서는 번호를 직접 입력해주세요.")
        }
    }

    if (reply && caller != null && !cleared) {
        CallerReplyDialog(caller.number, onBack = { reply = false }, onDismiss = onDismiss)
        return
    }

    NamuDialog(
        title = "전화 고객 연결", subtitle = "수신 번호로 견적과 답장을 준비하세요",
        onDismiss = onDismiss, snackbarHostState = snackbar
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (caller != null && !cleared) {
                IncomingCallerActions(
                    caller, customerPhone, cartItemCount,
                    onUseNumber = { onUseNumber(caller.number) }, onReply = { reply = true },
                    onClear = {
                        if (store.latest()?.id == caller.id) IncomingCallNotifications.cancel(context)
                        store.clear(caller.id)
                        cleared = true
                    }
                )
            } else {
                NamuEmptyState(
                    if (request != null) "이 알림의 번호는 만료됐어요" else "아직 연결할 수신 번호가 없어요",
                    "전화 연결을 켜면 다음 전화의 번호를 여기에서 확인할 수 있어요.",
                    Icons.Outlined.PhoneInTalk
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("전화 수신 연결", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            enabled && !permissionsGranted -> "권한이 해제되어 번호를 받을 수 없어요"
                            enabled && !notificationsGranted -> "번호 감지 켜짐 · 알림은 꺼져 있어요"
                            enabled -> "수신 번호를 알림과 앱에서 확인해요"
                            else -> "현재 꺼져 있어요"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { turnOn ->
                    if (!turnOn) {
                        store.setEnabled(false)
                        IncomingCallNotifications.cancel(context)
                        cleared = true
                    } else {
                        val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
                        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                })
            }
            Text(
                "켜면 전화·통화 기록 권한으로 수신 번호를 확인하고 알림에 표시해요. 최근 번호 1개를 이 휴대폰에 보관하며, 24시간이 지난 번호는 다음 확인 때 지워요. 연결을 끄거나 ‘번호 지우기’를 누르면 삭제돼요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "발신번호 표시제한, 일부 기기·설치 방식에서는 번호를 받을 수 없어요. 알림이 꺼져 있으면 이 화면에서 확인해주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                } catch (_: android.content.ActivityNotFoundException) {
                    scope.launch { snackbar.showSnackbar("휴대폰 설정에서 나무팜의 권한과 알림을 확인해주세요.") }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("휴대폰 권한·알림 설정") }
        }
    }
}

@Composable
internal fun IncomingCallerActions(
    caller: IncomingCaller,
    customerPhone: String,
    cartItemCount: Int,
    onUseNumber: () -> Unit,
    onReply: () -> Unit,
    onClear: () -> Unit
) {
    var confirmReplacement by rememberSaveable(caller.id) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("최근 전화 온 번호", style = MaterialTheme.typography.labelLarge)
            Text(caller.number, style = MaterialTheme.typography.headlineSmall)
            Text(SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(caller.receivedAt)), style = MaterialTheme.typography.bodySmall)
            Text("견적에 적용할 번호를 확인해주세요.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                if (customerPhone.isNotBlank() && normalizePhoneNumber(customerPhone) != caller.number) confirmReplacement = true
                else onUseNumber()
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("이 번호로 견적 작성") }
            OutlinedButton(onClick = onReply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("응답 문자 작성") }
            TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) { Text("번호 지우기") }
        }
    }
    if (confirmReplacement) AlertDialog(
        onDismissRequest = { confirmReplacement = false },
        title = { Text("견적 고객 번호를 바꿀까요?") },
        text = { Text("현재: $customerPhone\n새 번호: ${caller.number}\n\n바구니의 ${cartItemCount}개 품목은 그대로 유지돼요. 같은 견적을 보낼 고객인지 확인해주세요.") },
        confirmButton = { TextButton(onClick = { confirmReplacement = false; onUseNumber() }) { Text("번호 변경") } },
        dismissButton = { TextButton(onClick = { confirmReplacement = false }) { Text("기존 번호 유지") } }
    )
}

@Composable
internal fun CallerReplyDialog(phone: String, onBack: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val templates = remember { linkedMapOf(
        "다시 연락" to "안녕하세요, 나무팜입니다. 지금은 전화를 받기 어려워 확인 후 연락드리겠습니다.",
        "견적 안내" to "안녕하세요, 나무팜입니다. 문의하신 묘목의 재고와 가격을 확인해 견적을 보내드리겠습니다.",
        "문의 접수" to "안녕하세요, 나무팜입니다. 원하시는 묘목명, 규격, 수량을 문자로 남겨주시면 확인 후 안내드리겠습니다."
    ) }
    var body by rememberSaveable(phone) { mutableStateOf(templates.values.first()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    NamuDialog(
        title = "응답 문자", subtitle = "받는 분 · $phone", onDismiss = onDismiss, snackbarHostState = snackbar,
        actions = {
            Button(onClick = {
                openSmsComposer(context, phone, body)?.let { error -> scope.launch { snackbar.showSnackbar(error) } }
            }, enabled = body.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("문자 앱 열기") }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("번호 확인으로 돌아가기") }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("빠른 문구", style = MaterialTheme.typography.titleMedium)
            templates.forEach { (label, text) ->
                OutlinedButton(onClick = { body = text }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("문자 내용") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            Text("문자 앱에서 내용을 확인한 뒤 직접 전송해주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.example.namufarm.phone

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.namufarm.ui.theme.NamuColors
import com.example.namufarm.ui.theme.NamuTheme
import kotlinx.coroutines.delay

/** Separate settings Activity so notifications never navigate away from an unsaved quote. */
class WebsiteCallSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NamuTheme { WebsiteCallSettings(lifecycle = lifecycle, onClose = { finish() }) } }
    }

    override fun onResume() {
        super.onResume()
        WebsiteCallService.resumeIfEnabled(this)
    }
}

@Composable
private fun WebsiteCallSettings(lifecycle: Lifecycle, onClose: () -> Unit) {
    val context = LocalContext.current
    val config = remember { WebsiteCallConfig(context) }
    val receipts = remember { WebsiteSmsReceipts(context) }
    var revision by remember { mutableIntStateOf(0) }
    var feedback by rememberSaveable { mutableStateOf("") }
    val running by WebsiteCallService.running.collectAsState()
    DisposableEffect(lifecycle) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        config.preferences.registerOnSharedPreferenceChangeListener(listener)
        receipts.preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            config.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            receipts.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) revision++ }
    }
    val enabled = remember(revision) { config.enabled }
    val permissions = remember(revision) { hasWebsiteCallPermissions(context) }
    val overlay = remember(revision) { Settings.canDrawOverlays(context) }
    val notifications = remember(revision) { websiteCallNotificationsAllowed(context) }
    val ready = remember(revision) { websiteCallReady(context) }
    val subscriptions = remember(revision) { websiteSmsSubscriptions(context) }
    val selected = remember(revision) { config.smsSubscriptionId }
    val simReady = remember(revision) { websiteSmsSubscription(context) != null }
    val message = remember(revision) { websiteCallMessage(context) }
    val problem = remember(revision) { config.problem }
    val last = remember(revision) { receipts.lastSummary() }
    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        if (!hasWebsiteCallPermissions(context)) feedback = "허용되지 않은 권한이 있습니다. 앱 권한 설정에서 확인해주세요."
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { feedback = "설정 화면을 열 수 없습니다. 휴대전화 설정에서 나무팜을 찾아주세요." }
    }

    Scaffold(modifier = Modifier.fillMaxSize().systemBarsPadding(), containerColor = NamuColors.background,
        topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("통화 중 홈페이지 안내", Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onClose) { Text("닫기", fontSize = 16.sp) }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("전화가 오면 작은 창이 뜹니다.\n통화가 연결된 뒤 [문자 전송]을 누르면 상대 번호로 아래 안내 문자가 갑니다.", fontSize = 16.sp)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("전화 안내", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(when {
                            running && enabled -> "켜짐 · 전화 수신 대기 중"
                            enabled -> "켜기 요청됨 · 실행 상태를 확인해주세요"
                            else -> "꺼짐"
                        }, fontSize = 15.sp)
                    }
                    Switch(checked = enabled, enabled = enabled || (ready && simReady), onCheckedChange = { value ->
                        try {
                            config.enabled = value
                            if (value) WebsiteCallService.resumeIfEnabled(context)
                            else { context.stopService(Intent(context, WebsiteCallService::class.java)); config.problem = "" }
                            feedback = ""
                        } catch (_: Exception) { feedback = "전화 안내 설정을 저장하지 못했습니다." }
                        revision++
                    })
                }
            }
            Text("처음 한 번만 권한을 허용해주세요.", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("전화·통화 기록·문자·알림: ${if (permissions) "허용됨" else "확인 필요"}\n" +
                "다른 앱 위 표시: ${if (overlay) "허용됨" else "확인 필요"}\n" +
                "앱 알림: ${if (notifications) "켜짐" else "꺼짐"}", fontSize = 15.sp)
            OutlinedButton(onClick = { requestPermissions.launch(websiteCallPermissions()) }, modifier = Modifier.fillMaxWidth()) {
                Text("전화·문자·알림 권한 허용", fontSize = 16.sp)
            }
            OutlinedButton(onClick = {
                openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("다른 앱 위 표시 허용", fontSize = 16.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.weight(1f)) { Text("앱 권한 설정", fontSize = 15.sp) }
                OutlinedButton(onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 26)
                        openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    else openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.weight(1f)) { Text("알림 설정", fontSize = 15.sp) }
            }
            if (feedback.isNotBlank()) Text(feedback, color = NamuColors.danger, fontSize = 15.sp)
            if (problem.isNotBlank()) Text(problem, color = NamuColors.danger, fontSize = 15.sp)
            HorizontalDivider()
            Text("문자를 보낼 SIM", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("듀얼 SIM은 발신할 번호를 여기서 선택해주세요.", fontSize = 15.sp)
            val choices = listOf(SubscriptionManager.INVALID_SUBSCRIPTION_ID to "기본 문자 SIM 사용") +
                subscriptions.map { it.subscriptionId to "SIM ${it.simSlotIndex + 1} · ${it.displayName}" }
            choices.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().selectable(selected = selected == id, role = Role.RadioButton, onClick = {
                    runCatching { config.smsSubscriptionId = id }
                        .onFailure { feedback = "SIM 설정을 저장하지 못했습니다." }
                    revision++
                }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == id, onClick = null)
                    Text(label, Modifier.padding(start = 8.dp), fontSize = 16.sp)
                }
            }
            if (!simReady) Text("발신 가능한 SIM이 확인되지 않았습니다. 전화 권한과 SIM 선택을 확인해주세요.", fontSize = 15.sp, color = NamuColors.danger)
            HorizontalDivider()
            Text("보낼 문자", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                SelectionContainer { Text(message, Modifier.padding(16.dp), fontSize = 16.sp) }
            }
            Text("대표 이름은 기존 공급자 정보에서 가져옵니다. 휴대전화 문자 요금제가 적용되며 긴 문자는 여러 건으로 나뉠 수 있어요.", fontSize = 14.sp)
            last?.let { Text("마지막 안내 문자\n$it", fontSize = 15.sp) }
            Text("기능을 켜면 통화 감지 알림이 계속 표시됩니다. 작은 창의 제목을 끌어 위치를 옮길 수 있어요.\n\n" +
                "잠금 상태·번호 미확인·통화 대기 상태에서는 보내지 않습니다. 작성 중인 견적의 고객 번호는 바꾸지 않아요.\n\n" +
                "권한을 허용할 수 없거나 절전 제한·강제 종료로 감지가 멈추면 앱을 다시 열어주세요. 전화 안내를 다시 켰을 때 진행 중인 통화는 건너뛰고 다음 수신부터 안내합니다.", fontSize = 14.sp)
        }
    }
}

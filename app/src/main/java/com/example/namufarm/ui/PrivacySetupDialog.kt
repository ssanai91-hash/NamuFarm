package com.example.namufarm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.WebInboxState

@Composable
internal fun PrivacySetupDialog(state: WebInboxState,onBackup:(Uri,CharArray)->Unit,onRestore:(Uri,CharArray)->Unit,onSecure:()->Unit,onDismiss:()->Unit) {
    var password by remember { mutableStateOf("") } // Never saved in instance state, preferences, or the exported file.
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri!=null) {onBackup(uri,password.toCharArray());password=""} }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) {onRestore(uri,password.toCharArray());password=""} }
    NamuDialog("고객정보 보안 설정","휴대폰 변경에 대비한 백업",onDismiss,actions={
        Button(onClick=onSecure,enabled=state.paired && state.backedUp && !state.busy,modifier=Modifier.fillMaxWidth()) { Text(if(state.busy) "처리 중…" else "서버 보안 연결") }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("고객정보는 시트에 암호화해 저장하고, 이 앱에서만 열어봅니다.")
            Text("처음 연결할 때 보안 백업을 먼저 저장해주세요. 휴대폰을 바꾸거나 앱을 삭제하면 이 파일과 비밀번호가 필요합니다. 파일과 비밀번호를 잃으면 기존 고객정보를 복구할 수 없습니다.")
            OutlinedTextField(password,{password=it},label={Text("보안 백업 비밀번호 (12자 이상)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            Button(onClick={save.launch("NamuFarm-Security-Backup.json")},enabled=password.length>=12 && !state.busy,modifier=Modifier.fillMaxWidth()) {Text("보안 백업 저장")}
            OutlinedButton(onClick={open.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=password.length>=12 && !state.busy,modifier=Modifier.fillMaxWidth()) {Text("다른 휴대폰의 보안 백업 복원")}
            Text(if(state.backedUp) "보안 백업 저장 완료" else "보안 백업을 저장한 뒤 서버 보안 연결을 눌러주세요.")
            state.message?.let { Text(it) }
            Text("백업에는 보안키가 들어있습니다. 고객에게 보내지 마세요. 서버에 전송을 마친 거래만 새 휴대폰에서 다시 가져올 수 있으니, 기기를 바꾸기 전에 전송 대기 내역을 동기화해주세요.",style=MaterialTheme.typography.bodySmall)
        }
    }
}

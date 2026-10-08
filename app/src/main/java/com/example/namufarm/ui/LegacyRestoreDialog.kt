package com.example.namufarm.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.WebInboxState

@Composable
internal fun LegacyRestoreDialog(state: WebInboxState, onRestore: (Uri, CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { onRestore(uri, password.toCharArray()); password = "" }
    }
    NamuDialog("이전 자료 열기", "새 견적을 받는 데에는 필요하지 않아요", onDismiss, actions = {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
    }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("새 견적은 ‘요청 동기화’로 서버에서 바로 가져옵니다. 별도 보관한 이전 자료 ${state.deferredCount}건이 필요한 경우에만 당시 저장한 백업을 선택해주세요. 백업이 없어도 새 견적 작성에는 지장이 없습니다.")
            OutlinedTextField(password, { password = it }, label = { Text("이전 백업 비밀번호") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = password.length >= 12 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("이전 백업 선택") }
            state.message?.let { Text(it) }
        }
    }
}


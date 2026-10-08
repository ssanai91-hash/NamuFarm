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
    NamuDialog("이전 자료 복원", "구버전에서 암호화한 자료가 있을 때만 사용", onDismiss, actions = {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
    }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("새 견적과 주문은 연결 후 바로 사용할 수 있습니다. 이전 버전의 자료를 열라는 안내가 나온 경우에만 당시 저장한 백업을 선택해주세요.")
            OutlinedTextField(password, { password = it }, label = { Text("이전 백업 비밀번호") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = password.length >= 12 && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("이전 백업 선택") }
            state.message?.let { Text(it) }
        }
    }
}

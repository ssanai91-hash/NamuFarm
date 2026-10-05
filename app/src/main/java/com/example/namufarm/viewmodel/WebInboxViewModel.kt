package com.example.namufarm.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.webrequests.WebRequestConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class WebInboxState(
    val requests: List<WebQuoteRequestEntity> = emptyList(), val busy: Boolean = false,
    val configured: Boolean = false, val paired: Boolean = false,
    val securityReady: Boolean = false, val backedUp: Boolean = false,
    val pushConfigured: Boolean = false, val message: String? = null
) { val unread: Int get() = requests.count { it.readAt == null } }

class WebInboxViewModel(private val connection: WebRequestConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(WebInboxState(configured = connection.configured, paired = connection.paired, pushConfigured = connection.pushConfigured))
    val state = mutableState.asStateFlow()
    private val working = AtomicBoolean(false)
    init { viewModelScope.launch(Dispatchers.IO) { reloadLocal() } }
    private fun reloadLocal() { mutableState.update { it.copy(requests = connection.localRequests(), paired = connection.paired,securityReady=connection.securityReady,backedUp=connection.backedUp) } }
    fun refresh() = operate {
        reloadLocal()
        val count = connection.refresh()
        reloadLocal()
        mutableState.update { it.copy(message = if (count > 0) "새 요청 ${count}건을 가져왔어요." else "요청함이 최신 상태입니다.") }
    }
    fun pair(invite: String) = operate {
        connection.pair(invite)
        reloadLocal()
        if(connection.securityReady) connection.refresh()
        reloadLocal()
        mutableState.update { it.copy(message = "기기를 연결했어요. 고객정보 보안 설정을 완료해주세요.") }
    }
    fun backup(uri: android.net.Uri, password: CharArray) = operate { connection.saveBackup(uri,password); reloadLocal();showMessage("보안 백업을 저장했어요. 비밀번호와 파일을 따로 안전하게 보관해주세요.") }
    fun restore(uri: android.net.Uri, password: CharArray) = operate { connection.restoreBackup(uri,password);reloadLocal();showMessage("보안 백업을 복원했어요. 서버 보안 연결을 확인해주세요.") }
    fun configureSecurity() = operate { connection.configureSecurity();reloadLocal();connection.refresh();reloadLocal();showMessage("고객정보 보안 연결이 완료됐어요.") }
    fun close(id: String) = operate { connection.close(id);reloadLocal();showMessage("상담을 종료하고 고객 문의와 연결된 견적 사본을 삭제했어요.") }
    fun read(id: String) { viewModelScope.launch(Dispatchers.IO) { connection.read(id); reloadLocal() } }
    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }
    private fun operate(block: suspend () -> Unit) {
        if (!working.compareAndSet(false, true)) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reloadLocal(); mutableState.update { it.copy(message = e.message ?: "연결을 확인해주세요. 저장된 요청은 그대로 유지됩니다.") } }
            finally { working.set(false); mutableState.update { it.copy(busy = false) } }
        }
    }
}
class WebInboxViewModelFactory(private val connection: WebRequestConnection) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WebInboxViewModel(connection) as T
}

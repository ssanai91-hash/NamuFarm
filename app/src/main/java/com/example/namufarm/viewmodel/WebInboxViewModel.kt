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
    val pushConfigured: Boolean = false, val message: String? = null
) { val unread: Int get() = requests.count { it.readAt == null } }

class WebInboxViewModel(private val connection: WebRequestConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(WebInboxState(configured = connection.configured, paired = connection.paired, pushConfigured = connection.pushConfigured))
    val state = mutableState.asStateFlow()
    private val working = AtomicBoolean(false)
    init { viewModelScope.launch(Dispatchers.IO) { reloadLocal() } }
    private fun reloadLocal() { mutableState.update { it.copy(requests = connection.localRequests(), paired = connection.paired) } }
    fun refresh() = operate {
        reloadLocal()
        val count = connection.refresh()
        reloadLocal()
        mutableState.update { it.copy(message = if (count > 0) "새 요청 ${count}건을 가져왔어요." else "요청함이 최신 상태입니다.") }
    }
    fun pair(invite: String) = operate {
        connection.pair(invite)
        reloadLocal()
        connection.refresh()
        reloadLocal()
        mutableState.update { it.copy(message = "홈페이지 요청함이 연결됐어요.") }
    }
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

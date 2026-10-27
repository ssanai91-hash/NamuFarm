package com.example.namufarm.quotemode

import android.content.Context
import com.example.namufarm.ui.theme.NamuMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.AndroidQuoteWorkflowStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InitialDeleteFeedback(val phase: String, val requestId: String, val error: String? = null)
data class InitialQuoteUiState(
    val requests: List<WebQuoteRequestEntity> = emptyList(),
    val editingRequest: WebQuoteRequestEntity? = null,
    val busy: Boolean = false, val message: NamuMessage? = null,
    val deferredCount: Int = 0, val deleteFeedback: InitialDeleteFeedback? = null
)

class InitialQuoteViewModel(
    val connection: InitialQuoteConnection, private val store: AndroidQuoteWorkflowStore
) : ViewModel() {
    private val mutable = MutableStateFlow(InitialQuoteUiState(busy = true))
    val uiState = mutable.asStateFlow()
    init {
        viewModelScope.launch(Dispatchers.IO) {
            var firstCache = true
            try {
                connection.observeCache().collect { cache ->
                    val initial = firstCache
                    firstCache = false
                    mutable.update { it.copy(requests = cache.requests, deferredCount = cache.deferredCount,
                        busy = if (initial) false else it.busy) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(busy = false,
                    message = NamuMessage("저장된 견적 확인이 필요합니다: " + e.message, true)) }
            }
        }
    }
    fun refresh() {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = NamuMessage("견적 목록을 동기화하는 중…")) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connection.refresh()
                mutable.update { it.copy(message = NamuMessage("견적 목록을 동기화했습니다.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(message = NamuMessage(e.message ?: "기존 자료를 유지합니다. 다시 가져와주세요.", true)) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun read(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { connection.read(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = NamuMessage("읽음 표시를 저장하지 못했습니다: " + e.message, true)) } }
        }
    }
    fun close(id: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, deleteFeedback = InitialDeleteFeedback("deleting", id)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = connection.latestRequest(id)
                check(current?.status == "신규") { "이미 처리된 요청입니다. 가져오기 후 다시 확인해주세요." }
                connection.close(id); store.remove(id)
                mutable.update { it.copy(editingRequest = null, deleteFeedback = InitialDeleteFeedback("success", id)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(deleteFeedback = InitialDeleteFeedback("error", id, e.message)) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun useWebRequestForQuote(request: WebQuoteRequestEntity) {
        mutable.update { it.copy(editingRequest = request, deleteFeedback = null) }
    }
    fun dismissManualQuote() { mutable.update { it.copy(editingRequest = null) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
}

class InitialQuoteViewModelFactory(
    private val connection: InitialQuoteConnection, context: Context
) : ViewModelProvider.Factory {
    private val store = AndroidQuoteWorkflowStore(context.applicationContext)
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        InitialQuoteViewModel(connection, store) as T
}

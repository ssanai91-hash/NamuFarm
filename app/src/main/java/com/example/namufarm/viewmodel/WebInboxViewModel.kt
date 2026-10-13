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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** UI-only feedback. A record is only 'complete' after the server confirms it. */
data class RequestDeleteFeedback(val requestId: String, val phase: String, val error: String? = null)

data class WebInboxState(
    val requests: List<WebQuoteRequestEntity> = emptyList(), val busy: Boolean = false,
    val configured: Boolean = false, val paired: Boolean = false,
    val pushConfigured: Boolean = false, val message: String? = null,
    val deferredCount: Int = 0,
    val deleteFeedback: RequestDeleteFeedback? = null
) { val unread: Int get() = requests.count { it.readAt == null } }

class WebInboxViewModel(private val connection: WebRequestConnection) : ViewModel() {
    private val mutableState = MutableStateFlow(WebInboxState(configured = connection.configured, paired = connection.paired, pushConfigured = connection.pushConfigured))
    val state = mutableState.asStateFlow()
    private val working = AtomicBoolean(false)
    private val statusMutex = Mutex()
    init { viewModelScope.launch(Dispatchers.IO) { reloadLocal() } }
    private fun reloadLocal() { mutableState.update { it.copy(configured = connection.configured, requests = connection.localRequests(), paired = connection.paired, deferredCount = connection.deferredCount) } }
    fun refresh() = operate {
        reloadLocal()
        val count = connection.refresh()
        reloadLocal()
        mutableState.update {
            it.copy(message = if (it.requests.isEmpty() && it.deferredCount == 0)
                "현재 받아온 견적이 없어요. 홈페이지와 같은 구글시트 서버에 연결됐는지 설정을 확인해주세요."
                else inboxRefreshMessage(count, it.deferredCount))
        }
    }
    fun connectFile(uri: android.net.Uri) = operate {
        connection.connectFile(uri)
        reloadLocal()
        try {
            connection.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A valid connection file may succeed even when the next snapshot
            // contains an unreadable row. Keep the connection and local data.
            throw IllegalStateException(
                "서버 연결은 완료됐지만 견적 내려받기에 실패했어요: ${e.message ?: "서버 응답을 확인해주세요."}", e
            )
        }
        reloadLocal()
        showMessage("연결과 내려받기를 마쳤어요. 저장된 자료는 오프라인에서도 볼 수 있어요.")
    }
    fun pair(invite: String) = operate {
        connection.pair(invite)
        reloadLocal()
        connection.refresh()
        reloadLocal()
        mutableState.update { it.copy(message = "연결이 완료됐어요. 견적 요청을 확인해주세요.") }
    }
    fun restore(uri: android.net.Uri, password: CharArray) = operate {
        connection.restoreBackup(uri,password)
        val restored = connection.restoreLegacy()
        connection.refresh()
        reloadLocal()
        showMessage(if (restored > 0) "이전 요청 ${restored}건을 불러왔어요." else "이 백업으로 새로 열 수 있는 이전 요청이 없습니다. 새 견적은 계속 사용할 수 있어요.")
    }
    fun confirm(id: String, draft: com.example.namufarm.quotation.SellerQuoteDraft) {
        // A status update must not be discarded just because a download is running.
        viewModelScope.launch(Dispatchers.IO) {
            statusMutex.withLock {
                try {
                    connection.confirmQuote(id, draft)
                    connection.refresh()
                    reloadLocal()
                    showMessage("견적확정 상태를 구글시트에 반영했어요.")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    showMessage("견적은 휴대폰에 저장됐지만 시트 상태 반영은 실패했어요: ${e.message}")
                }
            }
        }
    }
    suspend fun markSentNow(id: String) = withContext(Dispatchers.IO) {
        statusMutex.withLock {
            // Wait for any earlier quote confirmation before publishing sent status.
            connection.markQuoteSent(id)
            connection.refresh()
            reloadLocal()
        }
    }
    fun close(id: String) {
        if (mutableState.value.deleteFeedback?.phase == "deleting") return
        // Show progress immediately, before any network/lock delay.
        mutableState.update { it.copy(deleteFeedback = RequestDeleteFeedback(id, "deleting")) }
        viewModelScope.launch(Dispatchers.IO) {
            statusMutex.withLock {
                try {
                    connection.close(id)
                    reloadLocal()
                    mutableState.update { it.copy(deleteFeedback = RequestDeleteFeedback(id, "success")) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    mutableState.update {
                        it.copy(deleteFeedback = RequestDeleteFeedback(id, "error",
                            e.message ?: "서버 응답을 확인하지 못했습니다."))
                    }
                }
            }
        }
    }
    fun acknowledgeDelete() {
        mutableState.update { it.copy(deleteFeedback = null) }
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

internal fun inboxRefreshMessage(imported: Int, deferred: Int): String {
    val result = if (imported > 0) "새 요청 ${imported}건을 가져왔어요." else "새 요청을 모두 확인했어요."
    return if (deferred > 0) "$result 이전 자료 ${deferred}건은 별도 보관 중이며, 새 견적은 바로 사용할 수 있어요." else result
}
class WebInboxViewModelFactory(private val connection: WebRequestConnection) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = WebInboxViewModel(connection) as T
}



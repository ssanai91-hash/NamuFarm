package com.example.namufarm.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.namufarm.data.CustomerInquiryDraftEntity
import com.example.namufarm.data.CustomerInquiryOperationEntity
import com.example.namufarm.data.remote.CustomerInquiryRemote
import com.example.namufarm.data.repository.CustomerInquiryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CustomerInquiryState(
    val rows: List<CustomerInquiryRemote> = emptyList(),
    val drafts: Map<String, CustomerInquiryDraftEntity> = emptyMap(),
    val operations: Map<String, CustomerInquiryOperationEntity> = emptyMap(),
    val lastSyncAt: Long? = null,
    val busy: Boolean = false,
    val message: String? = null
) {
    fun draft(row: CustomerInquiryRemote): CustomerInquiryDraftEntity = drafts[row.requestId]
        ?: CustomerInquiryDraftEntity(row.requestId!!, row.reply!!.text!!, row.reply.channel!!, row.revision!!)
}

class CustomerInquiryViewModel(private val repository: CustomerInquiryRepository) : ViewModel() {
    private val gate = Mutex()
    private val mutable = MutableStateFlow(CustomerInquiryState())
    val state = mutable.asStateFlow()
    init { reloadLocal() }

    private suspend fun reload() {
        val cache = repository.local()
        mutable.update { it.copy(rows = cache.rows, drafts = cache.drafts, operations = cache.operations) }
    }

    fun reloadLocal() {
        viewModelScope.launch {
            gate.withLock {
                try { withContext(Dispatchers.IO) { reload() } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { message(error.message ?: "저장된 문의를 확인할 수 없습니다.") }
            }
        }
    }

    fun edit(id: String, text: String, channel: String? = null) {
        val current = mutable.value
        val row = current.rows.find { it.requestId == id } ?: return
        if (current.busy || current.operations.containsKey(id) || row.blockedDelivery || text.length > 2000) return
        val draft = current.draft(row).let { it.copy(text = text, channel = channel ?: it.channel) }
        mutable.update { it.copy(drafts = it.drafts + (id to draft)) }
        // Main-thread launches preserve edit order; each write commits before a send can run.
        viewModelScope.launch {
            gate.withLock {
                try { withContext(Dispatchers.IO) { repository.remember(draft) } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { message(error.message ?: "답변 초안을 저장하지 못했습니다.") }
            }
        }
    }

    private fun operate(block: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            gate.withLock {
                try {
                    withContext(Dispatchers.IO) { val result = block(); reload(); message(result) }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    withContext(Dispatchers.IO) {
                        try { reload() } catch (_: Exception) { /* Keep the visible local draft. */ }
                    }
                    message(error.message ?: "서버 응답을 확인하지 못했습니다. 저장된 초안과 작업 기록은 유지됩니다.")
                } finally { mutable.update { it.copy(busy = false) } }
            }
        }
    }

    fun refresh() = operate {
        val count = repository.refresh()
        mutable.update { it.copy(lastSyncAt = System.currentTimeMillis()) }
        "문의 변경사항 ${count}건을 반영했습니다."
    }
    fun save(id: String) = operate { repository.save(id); "초안을 서버에 저장했습니다. 나피에서 불러올 수 있습니다." }
    fun send(id: String, channel: String, preparedText: String, sourceDraft: CustomerInquiryDraftEntity) = operate {
        repository.send(id, channel, preparedText, sourceDraft)
        "서버 발송 요청을 처리했습니다. 아래 발송 상태를 확인해주세요."
    }
    fun check(id: String) = operate { repository.checkResult(id); "현재 발송·처리 결과를 반영했습니다." }
    fun resolve(id: String, useServer: Boolean) = operate {
        repository.resolveDraft(id, useServer)
        if (useServer) "최신 서버 답변을 적용했습니다." else "내 초안을 유지했습니다. 내용을 확인한 뒤 저장하거나 보내주세요."
    }
    fun status(id: String, status: String, note: String) = operate {
        repository.setStatus(id, status, note)
        if (status == "closed") "문의 처리를 완료했습니다." else "문의를 다시 열었습니다."
    }
    fun delete(id: String) = operate {
        repository.delete(id)
        "문의를 삭제했습니다. 다른 휴대폰과 나피에서는 불러오기 때 함께 지워집니다."
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    private fun message(text: String) { mutable.update { it.copy(message = text) } }
}

class CustomerInquiryViewModelFactory(private val repository: CustomerInquiryRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CustomerInquiryViewModel(repository) as T
}


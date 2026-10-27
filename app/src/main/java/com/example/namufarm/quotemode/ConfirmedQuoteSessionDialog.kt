package com.example.namufarm.quotemode

import androidx.compose.runtime.*
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Refresh permission before editing; saving a revision itself has no network operation. */
@Composable
internal fun ConfirmedQuoteSessionDialog(saved: QuoteWorkflowRecord, workflow: QuoteWorkflow,
    connection: InitialQuoteConnection, onChanged: (QuoteWorkflowRecord) -> Unit, onDismiss: () -> Unit) {
    var current by remember(saved.request.requestId) { mutableStateOf(saved) }
    var latest by remember(saved.request.requestId) { mutableStateOf<WebQuoteRequestEntity?>(null) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(saved.request.requestId) {
        try { latest = withContext(Dispatchers.IO) { connection.latestRequest(saved.request.requestId) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "최신 서버 상태를 확인하지 못했습니다." }
    }
    if (editing) ManualQuoteEditor(current.request, busy, error,
        onDismiss = { if (!busy) { editing = false; error = null } }, initialDraft = current.draft,
        draftKey = "confirmed:${current.request.requestId}:${current.revision}", messageIsError = error != null,
        confirmedEdit = true, onSave = { draft, _ ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val revised = withContext(Dispatchers.IO) { workflow.reviseConfirmed(current, draft, checkNotNull(latest)) }
                        current = revised; onChanged(revised); editing = false; error = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "수정한 견적을 저장하지 못했습니다." }
                    finally { busy = false }
                }
            }
        })
    else InitialConfirmedQuoteDialog(current.request, current.draft, current.confirmedAt, current.delivery,
        onDismiss = onDismiss, record = current, editBlock = error ?: confirmedQuoteEditBlock(current, latest),
        onEdit = { if (confirmedQuoteEditBlock(current, latest) == null) { editing = true; error = null } })
}

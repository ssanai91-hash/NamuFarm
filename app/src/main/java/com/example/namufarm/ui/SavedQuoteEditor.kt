package com.example.namufarm.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.components.NamuDialog
import com.example.namufarm.viewmodel.ManagementViewModel
import kotlinx.coroutines.CancellationException

@Composable
internal fun SavedQuoteEditor(viewModel: ManagementViewModel, number: String, request: WebQuoteRequestEntity?,
    onSaved: (String, QuoteDelivery) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var initial by remember(number) { mutableStateOf<SellerQuoteDraft?>(null) }
    var error by remember(number) { mutableStateOf<String?>(null) }
    LaunchedEffect(number) {
        try {
            val quote = viewModel.savedQuote(number) ?: kotlin.error("저장된 견적을 찾을 수 없습니다.")
            check(quote.sentAt == 0L && viewModel.quoteDocument(number) == null) { "이미 발송하거나 공식 발행한 견적입니다. 다시 열어 확인해주세요." }
            initial = SellerQuoteDraft.from(quote, viewModel.quoteRecipient(number), QuoteIssuerSettings(context).load())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "견적을 불러오지 못했습니다." }
    }
    initial?.let { draft ->
        ManualQuoteEditor(request, state.isSavingQuote, state.message, onDismiss, { changed, action ->
            viewModel.saveManualQuote(changed.validate(action), changed.phone, changed.recipient, changed.prepaid, changed.shipping(),
                issueOptions = changed.options.takeIf { changed.recipient.formalQuoteRequested }, existingQuoteNumber = number) {
                if (changed.recipient.formalQuoteRequested) runCatching { QuoteIssuerSettings(context).save(changed.options.issuer) }
                onSaved(number, action)
            }
        }, initialDraft = draft, draftKey = number)
    } ?: NamuDialog("저장한 견적 이어 쓰기", "기존 가격과 고객 정보를 불러옵니다", onDismiss) { Text(error ?: "불러오는 중…") }
}

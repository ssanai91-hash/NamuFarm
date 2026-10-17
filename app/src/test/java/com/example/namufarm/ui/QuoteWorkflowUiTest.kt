package com.example.namufarm.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.namufarm.data.WebQuoteRequestEntity
import com.example.namufarm.quotation.*
import com.example.namufarm.ui.theme.NamuFarmTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuoteWorkflowUiTest {
    @get:Rule val compose = createComposeRule()
    private val request = WebQuoteRequestEntity("00000000-0000-4000-8000-000000000001", 1,
        "2026-10-17", "가상 고객", "01000000000", "옥천", "[{\"species\":\"감나무\",\"specification\":\"\",\"quantity\":3}]", "요청사항",
        customerEmail = "sample@example.com", receiptNumber = "NAMU-20261017-0001")
    @Test fun unsentCompletionShowsAlertWithoutSavingOrClaimingDelivery() {
        var action: QuoteDelivery? = null
        compose.setContent { NamuFarmTheme {
            ManualQuoteEditor(request, false, null, {}, { _, selected -> action = selected },
                verifiedDelivery = QuoteDeliveryRecord(), verifyDelivery = true)
        } }
        compose.onNodeWithText("견적완료").performClick()
        compose.onNodeWithText("고객 전달 확인").assertIsDisplayed()
        assertNull(action)
        compose.onNodeWithText("확인", useUnmergedTree = true).performClick()
        compose.onNodeWithText("문자", useUnmergedTree = true).performClick()
        assertEquals(QuoteDelivery.SMS, action)
    }
    @Test fun confirmedResultHasCloseOnlyAndNoTransmissionOrPricingActions() {
        val draft = SellerQuoteDraft.from(request, QuoteIssuer())
        var closed = false
        compose.setContent { NamuFarmTheme {
            QuoteWorkflowResultDialog(QuoteWorkflowRecord(request, draft,
                QuoteDeliveryRecord(emailSentAt = 123, emailFingerprint = quoteWorkflowFingerprint(draft)), confirmedAt = 456)) { closed = true }
        } }
        compose.onNodeWithText("확정견적 확인").assertIsDisplayed()
        compose.onNodeWithText("문자").assertDoesNotExist()
        compose.onNodeWithText("메일").assertDoesNotExist()
        compose.onNodeWithText("견적완료").assertDoesNotExist()
        compose.onNodeWithText("닫기").performClick()
        assertTrue(closed)
    }
}

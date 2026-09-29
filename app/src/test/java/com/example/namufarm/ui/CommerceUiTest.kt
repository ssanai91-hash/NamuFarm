package com.example.namufarm.ui

import androidx.compose.material3.SnackbarHostState
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.namufarm.data.OrderEntity
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.ui.components.shippingLabel
import com.example.namufarm.ui.theme.NamuFarmTheme
import com.example.namufarm.viewmodel.CartItem
import com.example.namufarm.viewmodel.ManagementUiState
import com.example.namufarm.phone.IncomingCaller
import com.example.namufarm.phone.IncomingCallStore
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ko-rKR-w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommerceUiTest {
    @get:Rule val compose = createComposeRule()

    private val pine = SellingDisplayRow(1, 1, "이원농원", "소나무", "상묘", "3년생", "1.2m", "실생", 320, 7000, 12000, "ㅅ")
    private val maple = pine.copy(speciesId = 2, speciesName = "단풍나무", chosung = "ㄷ", farmName = "옥천농원", retailPrice = 8000)
    private val manual = pine.copy(farmId = -1, speciesId = 3, farmName = "수기견적", speciesName = "감나무", quantity = 0, retailPrice = 5000, wholesalePrice = 0)
    private val order = OrderEntity(
        orderNumber = "ORDER_20260929_sample", orderDate = "2026-09-29 10:30", customerPhone = "010-1234-5678", receiverName = "김나무",
        totalQty = 12, totalPrice = 130000, isPrepaid = true, shippingFee = 0,
        roadAddress = "상세화면에 노출하면 안 되는 배송주소",
        itemSummary = "소나무|상묘|3년생|1.2m|실생|10|12000|이원농원|7000\n감나무|||||2|5000|수기견적|0"
    )

    @Test fun homeSearchFindsFarmAndKeepsCartVisible() {
        val state = ManagementUiState(allSellingInventory = listOf(pine, maple), estimateCart = listOf(CartItem(pine, 10)))
        compose.setContent { NamuFarmTheme {
            SellingHome(state, SnackbarHostState(), {}, {}, {}, {}, {}, {}, {}, { _, _ -> })
        } }
        capture("selling-home", compose.onRoot())
        compose.onNodeWithText("묘목명 또는 농원명 검색").performTextInput("옥천")
        compose.onNodeWithText("단풍나무").assertIsDisplayed()
        compose.onNodeWithText("소나무").assertDoesNotExist()
        compose.onNodeWithText("견적 확인").assertIsDisplayed()
        compose.onNodeWithContentDescription("검색어 지우기").performClick()
        compose.onNodeWithText("소나무").assertExists()
    }

    @Test fun quantityPickerRejectsMoreThanRemainingStock() {
        var added = 0
        compose.setContent { NamuFarmTheme { SellingItemRow(pine, availableQuantity = 3) { added = it } } }
        compose.onNodeWithText("소나무").performClick()
        compose.onNodeWithText("담을 수량 (주)").performTextInput("4")
        compose.onNodeWithText("견적에 담기").assertIsNotEnabled()
        compose.onNodeWithText("담을 수량 (주)").performTextReplacement("3")
        compose.onNodeWithText("견적에 담기").performClick()
        assertEquals(3, added)
    }

    @Test fun orderUsesCustomerIdentityAndNeverDisplaysAddressOrDeleteAction() {
        compose.setContent { NamuFarmTheme { OrderDetailsDialog(order, SnackbarHostState(), {}, {}, {}) } }
        compose.onNodeWithText(order.customerPhone).assertIsDisplayed()
        compose.onNodeWithText("김나무").assertIsDisplayed()
        compose.onNodeWithText(order.roadAddress).assertDoesNotExist()
        compose.onNodeWithText("주문 삭제").assertDoesNotExist()
        compose.onNodeWithText("배송정보 관리").assertIsDisplayed()
        capture("order-detail", compose.onNodeWithTag("namu_dialog"))
        compose.onNodeWithTag("commerce_detail").performScrollToNode(hasText("수기 견적"))
        compose.onNodeWithText("실제 재고 차감·복원 없음").assertExists()
        compose.onNodeWithTag("commerce_detail").performScrollToNode(hasText("배송 선불 · 0원"))
        compose.onNodeWithText("배송 선불 · 0원").assertIsDisplayed()
    }

    @Test fun canceledOrderCannotBeCanceledAgain() {
        var canceled = false
        compose.setContent { NamuFarmTheme { OrderDetailsDialog(order.copy(status = "주문취소"), SnackbarHostState(), {}, { canceled = true }, {}) } }
        compose.onNodeWithText("취소된 주문입니다").assertIsNotEnabled()
        assertFalse(canceled)
    }

    @Test fun cartShowsManualSourceAndKeepsSaveAvailableOnSmallScreen() {
        compose.setContent { NamuFarmTheme {
            QuoteCartDialog(
                cart = listOf(CartItem(manual, 2, OrderLineSource.MANUAL)), phone = "010-1234-5678",
                isPrepaid = false, shippingFee = 0, saving = false, snackbarHostState = SnackbarHostState(),
                onPhone = {}, onShipping = { _, _ -> }, onRemove = {}, onRecentCalls = {}, onSendSms = {}, onSave = {}, onDismiss = {}
            )
        } }
        compose.onNodeWithText("수기 견적").assertIsDisplayed()
        compose.onNodeWithText("실제 재고 차감·복원 없음").assertIsDisplayed()
        compose.onNodeWithText("견적 저장").assertIsDisplayed().assertIsEnabled()
        capture("quote-cart", compose.onNodeWithTag("namu_dialog"))
    }

    @Test fun emptyHistoryHasGuidanceInsteadOfClosingImmediately() {
        compose.setContent { NamuFarmTheme { TodayQuotesDialog(emptyList(), {}, {}) } }
        compose.onNodeWithText("오늘 저장한 견적이 없어요").assertIsDisplayed()
        compose.onNodeWithContentDescription("닫기").assertIsDisplayed()
    }

    @Test fun filtersExcludeSoldOutAndNormalizeDoubleInitials() {
        val double = pine.copy(speciesName = "뽕나무", chosung = null)
        assertEquals(listOf(double), filterSellingInventory(listOf(double, maple, pine.copy(quantity = 0)), "", "ㅂ"))
        assertEquals(listOf(maple), filterSellingInventory(listOf(pine, maple), " 옥천 ", "전체"))
        assertNotEquals(sellingRowKey(pine), sellingRowKey(pine.copy(size = "1.3m")))
    }

    @Test fun freePrepaidDeliveryIsNotCollectOnDelivery() {
        assertEquals("선불 · 0원", shippingLabel(true, 0))
        assertEquals("선불 · 3,000원", shippingLabel(true, 3000))
        assertEquals("착불", shippingLabel(false, 0))
    }

    @Test fun callerCannotReplaceAnExistingQuoteRecipientWithoutConfirmation() {
        val caller = IncomingCaller("call-a", "01012345678", 1_790_726_400_000L)
        var recipient = "01099998888"
        compose.setContent { NamuFarmTheme {
            IncomingCallerActions(caller, recipient, 2, { recipient = caller.number }, {}, {})
        } }
        compose.onNodeWithText("이 번호로 견적 작성").performClick()
        assertEquals("01099998888", recipient)
        compose.onNodeWithText("기존 번호 유지").performClick()
        assertEquals("01099998888", recipient)
        compose.onNodeWithText("이 번호로 견적 작성").performClick()
        compose.onNodeWithText("번호 변경").performClick()
        assertEquals(caller.number, recipient)
    }

    @Test fun replyingDoesNotChangeTheQuoteCustomer() {
        var selected = false
        var reply = false
        compose.setContent { NamuFarmTheme {
            IncomingCallerActions(IncomingCaller("call-b", "01012345678", 1_790_726_400_000L), "01099998888", 3, { selected = true }, { reply = true }, {})
        } }
        compose.onNodeWithText("응답 문자 작성").performClick()
        assertTrue(reply)
        assertFalse(selected)
    }

    @Test fun callDialogKeepsItsRecipientWhenAnotherCallArrives() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = IncomingCallStore(context)
        store.setEnabled(true)
        store.recordRinging("01012345678")
        var selected: String? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NamuFarmTheme {
            IncomingCallAssistDialog(null, "", 0, { selected = it }, {})
        } }
        compose.onNodeWithText("01012345678").assertIsDisplayed()
        capture("incoming-caller", compose.onNodeWithTag("namu_dialog"))
        compose.runOnIdle { store.recordRinging("01055556666") }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("01012345678").assertIsDisplayed()
        compose.onNodeWithText("01055556666").assertDoesNotExist()
        compose.onNodeWithText("이 번호로 견적 작성").performClick()
        assertEquals("01012345678", selected)
        store.setEnabled(false)
    }

    private fun capture(name: String, node: SemanticsNodeInteraction) {
        val directory = File("build/outputs/ui-previews").apply { mkdirs() }
        // captureToImage waits for a device Window redraw that Robolectric does not provide.
        // Roborazzi records the real Compose render tree with Robolectric native graphics.
        val previous = System.getProperty("roborazzi.test.record")
        System.setProperty("roborazzi.test.record", "true")
        try {
            node.captureRoboImage(File(directory, "$name.png"))
        } finally {
            if (previous == null) System.clearProperty("roborazzi.test.record")
            else System.setProperty("roborazzi.test.record", previous)
        }
    }
}

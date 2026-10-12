package com.example.namufarm.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.namufarm.data.SellingDisplayRow
import com.example.namufarm.ui.theme.NamuFarmTheme
import com.example.namufarm.viewmodel.CartItem
import com.example.namufarm.viewmodel.ManagementUiState

// Android Studio previews only. No repository, network or persistent sample data.
@Preview(name = "판매 · 360dp", widthDp = 360, heightDp = 800, showBackground = true)
@Preview(name = "판매 · 큰 글꼴", widthDp = 412, heightDp = 892, fontScale = 1.3f, showBackground = true)
@Composable
private fun SellingHomePreview() {
    val pine = SellingDisplayRow(1, 1, "이원농원", "소나무", "상묘", "3년생", "1.2m", "실생", 320, 7000, 12000, "ㅅ")
    NamuFarmTheme {
        SellingHome(
            state = ManagementUiState(allSellingInventory = listOf(pine), estimateCart = listOf(CartItem(pine, 10))),
            snackbarHostState = SnackbarHostState(),
            onSync = {}, onManage = {}, onQuickQuote = {}, onTodayQuotes = {},
            onSalesManagement = {}, onSyncOrders = {}, onTodayOrders = {}, onOrder = { _ -> },
            onCart = {}, onClearCart = {}, onAddToCart = { _, _ -> }
        )
    }
}

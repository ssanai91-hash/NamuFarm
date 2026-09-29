package com.example.namufarm.viewmodel

import android.content.Context
import com.example.namufarm.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.namufarm.data.*
import com.example.namufarm.data.repository.NamuFarmRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update // 🚨 핵심 방패 추가!
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.namufarm.data.remote.JusoItem
import com.example.namufarm.domain.order.OrderLine
import com.example.namufarm.domain.order.OrderAmounts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import com.example.namufarm.domain.order.OrderLineSource
import com.example.namufarm.domain.order.OrderSummaryCodec

data class CartItem(
    val item: SellingDisplayRow,
    val orderQuantity: Int,
    val source: OrderLineSource = OrderLineSource.INVENTORY
)
data class CallLogItem(val name: String?, val number: String)

data class ManagementUiState(
    val allSpeciesList: List<SpeciesEntity> = emptyList(),
    val speciesList: List<SpeciesEntity> = emptyList(),
    val selectedChosung: String = "ㄱ",
    val message: String? = null,

    val jusoResults: List<JusoItem> = emptyList(),
    val allSellingInventory: List<SellingDisplayRow> = emptyList(),
    val filteredSellingInventory: List<SellingDisplayRow> = emptyList(),
    val estimateCart: List<CartItem> = emptyList(),

    val customerPhone: String = "",
    val recentCallList: List<CallLogItem> = emptyList(),
    val todayQuotes: List<QuoteEntity> = emptyList(),
    val todayOrders: List<OrderEntity> = emptyList(),
    val isSavingQuote: Boolean = false,
    val confirmingQuoteNumber: String? = null,

    val farmList: List<FarmEntity> = emptyList(),
    val selectedFarm: FarmEntity? = null,
    val selectedSpecies: SpeciesEntity? = null,
    val inventoryList: List<InventoryDisplayRow> = emptyList(),
    val isSpeciesEnabled: Boolean = false,
    val isInputEnabled: Boolean = false,
    val isInputActive: Boolean = false,

    // 🚨 V2 규격: 묘목 크기와 재배 타입 상태 추가!
    val inputAge: String = "1년생",
    val inputSizeMeters: Float = 0f,     // 🚨 추가
    val inputPlantType: String = "실생",  // 🚨 추가

    val inputQuality: String = "상묘",
    val inputQuantity: Int = 0,
    val inputWholesale: Int = 0,
    val inputRetail: Int = 0
)

class ManagementViewModel(
    private val repository: NamuFarmRepository
) : ViewModel()
{
    private val _uiState = MutableStateFlow(ManagementUiState())
    val uiState: StateFlow<ManagementUiState> = _uiState.asStateFlow()

    private val quoteSaveInProgress = AtomicBoolean(false)
    private val orderConfirmInProgress = AtomicBoolean(false)

    private fun launchOperation(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "❌ 처리 실패: ${e.message}") }
            }
        }

    init {
        loadInitialData()
        loadTodayData()
    }

    private fun loadInitialData() {
        launchOperation {
            var allSpecies = repository.getAllSpecies()
            var farms = repository.getAllFarms()
            var allInventory = repository.getAllSellingInventory()

            if (farms.isEmpty() || allSpecies.isEmpty()) {
                _uiState.update {
                    it.copy(message = "🔄 최초 실행: 전체 농원/묘목 마스터 데이터 가져오는 중...")
                }

                val farmSyncCount = repository.syncFarmMaster()
                val speciesSyncCount = repository.syncSpeciesMaster()

                allSpecies = repository.getAllSpecies()
                farms = repository.getAllFarms()
                allInventory = repository.getAllSellingInventory()

                _uiState.update {
                    it.copy(
                        message = if (farmSyncCount < 0 || speciesSyncCount < 0) {
                            "⚠️ 최초 마스터 동기화 일부 실패. 네트워크 확인 후 다시 동기화하세요."
                        } else {
                            "✅ 마스터 데이터 초기 세팅 완료!"
                        }
                    )
                }
            }

            val firstChosung = findFirstAvailableSellingChosung(allInventory)

            _uiState.update {
                it.copy(
                    farmList = farms,
                    allSpeciesList = allSpecies,
                    allSellingInventory = allInventory
                )
            }

            selectChosung(firstChosung)
        }
    }


    private fun todayDatePrefix(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Date())

    private fun refreshLocalState() {
        val farms = repository.getAllFarms()
        val allSpecies = repository.getAllSpecies()
        val allInventory = repository.getAllSellingInventory()
        val quotes = repository.getQuotesForDate(todayDatePrefix())
        val orders = repository.getOrdersForDate(todayDatePrefix())

        _uiState.update { current ->
            val activeChosung = if (
                hasSellingItemsForChosung(allInventory, current.selectedChosung)
            ) {
                current.selectedChosung
            } else {
                findFirstAvailableSellingChosung(allInventory)
            }

            current.copy(
                farmList = farms,
                allSpeciesList = allSpecies,
                speciesList = allSpecies.filter {
                    checkChosung(it.speciesName, it.chosung, activeChosung)
                },
                selectedChosung = activeChosung,
                allSellingInventory = allInventory,
                filteredSellingInventory = allInventory.filter {
                    checkChosung(it.speciesName, it.chosung, activeChosung)
                },
                todayQuotes = quotes,
                todayOrders = orders,
                selectedFarm = farms.find { it.farmId == current.selectedFarm?.farmId },
                selectedSpecies = allSpecies.find { it.speciesId == current.selectedSpecies?.speciesId }
            )
        }
    }

    private fun buildFilteredSellingInventory(
        allSellingInventory: List<SellingDisplayRow>,
        chosung: String
    ): List<SellingDisplayRow> {
        return allSellingInventory.filter { item ->
            checkChosung(item.speciesName, item.chosung, chosung)
        }
    }

    fun selectChosung(chosung: String) {
        _uiState.update { currentState ->
            val allSpecies = currentState.allSpeciesList
            val allSelling = currentState.allSellingInventory

            val filteredSpecies = allSpecies.filter { species -> checkChosung(species.speciesName, species.chosung, chosung) }
            val filteredSelling = allSelling.filter { item -> checkChosung(item.speciesName, item.chosung, chosung) }

            currentState.copy(
                selectedChosung = chosung,
                speciesList = filteredSpecies,
                filteredSellingInventory = filteredSelling
            )
        }
    }

    private fun checkChosung(name: String, dbChosung: String?, target: String): Boolean {
        if (!dbChosung.isNullOrBlank() && dbChosung == target) return true
        val firstChar = name.firstOrNull() ?: return false
        val charCode = firstChar.code

        if (charCode in 0xAC00..0xD7A3) {
            val chosungIndex = (charCode - 0xAC00) / (21 * 28)
            val chosungArray = arrayOf("ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
            val calc = chosungArray.getOrNull(chosungIndex) ?: ""
            val norm = when (calc) {
                "ㄲ" -> "ㄱ"
                "ㄸ" -> "ㄷ"
                "ㅃ" -> "ㅂ"
                "ㅆ" -> "ㅅ"
                "ㅉ" -> "ㅈ"
                else -> calc
            }
            return norm == target
        }
        return false
    }

    private fun hasSellingItemsForChosung(
        allSelling: List<SellingDisplayRow>,
        chosung: String
    ): Boolean {
        return allSelling.any { item ->
            checkChosung(item.speciesName, item.chosung, chosung)
        }
    }

    private fun shouldShowAge(age: String): Boolean {
        val normalized = age
            .trim()
            .replace("년생", "")
            .trim()

        val value = normalized.toFloatOrNull()
        return value == null || value > 0f
    }

    private fun shouldShowSize(size: String): Boolean {
        val normalized = size
            .trim()
            .replace("m", "")
            .trim()

        val value = normalized.toFloatOrNull()
        return value == null || value > 0f
    }

    private fun buildSpecText(
        quality: String? = null,
        age: String = "",
        size: String = "",
        plantType: String = "",
        separator: String = " / "
    ): String {
        val parts = mutableListOf<String>()

        if (!quality.isNullOrBlank()) parts.add(quality)
        if (age.isNotBlank() && shouldShowAge(age)) parts.add(age)
        if (size.isNotBlank() && shouldShowSize(size)) parts.add(size)
        if (plantType.isNotBlank()) parts.add(plantType)

        return parts.joinToString(separator)
    }

    private fun findFirstAvailableSellingChosung(
        allSelling: List<SellingDisplayRow>
    ): String {
        val orderedChosung = listOf(
            "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ",
            "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
        )

        return orderedChosung.firstOrNull { chosung ->
            hasSellingItemsForChosung(allSelling, chosung)
        } ?: "ㄱ"
    }

    // 판매 / 견적 / 통화기록
    fun addToCart(item: SellingDisplayRow, qty: Int) {
        if (qty <= 0 || qty > item.quantity) {
            showMessage("⚠️ 재고 수량 범위에서 입력해주세요.")
            return
        }
        _uiState.update { currentState ->
            val currentCart = currentState.estimateCart.toMutableList()
            val existingIndex = currentCart.indexOfFirst {
                it.item.farmId == item.farmId &&
                        it.item.speciesId == item.speciesId &&
                        it.item.age == item.age &&
                        it.item.quality == item.quality &&
                        it.item.size == item.size &&
                        it.item.plantType == item.plantType}

            if (existingIndex != -1) {
                val newQty = currentCart[existingIndex].orderQuantity.toLong() + qty
                if (qty > 0 && newQty <= item.quantity) {
                    currentCart[existingIndex] = currentCart[existingIndex].copy(orderQuantity = newQty.toInt())
                    currentState.copy(estimateCart = currentCart, message = "✅ 장바구니 담김")
                } else {
                    currentState.copy(message = "⚠️ 재고 수량(${item.quantity}주)을 초과할 수 없습니다.")
                }
            } else {
                currentCart.add(CartItem(item, qty))
                currentState.copy(estimateCart = currentCart, message = "✅ 장바구니 담김")
            }
        }
    }

    fun addManualQuoteItem(
        species: SpeciesEntity,
        quality: String,
        age: String,
        size: String,
        plantType: String,
        quantity: Int,
        retailPrice: Int
    ) {
        if (quantity <= 0 || retailPrice <= 0) {
            _uiState.update {
                it.copy(message = "⚠️ 수량과 판매 단가를 확인해주세요.")
            }
            return
        }

        val manualItem = SellingDisplayRow(
            farmId = -1,
            speciesId = species.speciesId ?: -1,
            farmName = OrderSummaryCodec.MANUAL_FARM_LABEL,
            speciesName = species.speciesName,
            quality = quality,
            age = age,
            size = size,
            plantType = plantType,
            quantity = quantity,
            wholesalePrice = 0,
            retailPrice = retailPrice,
            chosung = species.chosung
        )

        _uiState.update { current ->
            val cart = current.estimateCart.toMutableList()
            val existingIndex = cart.indexOfFirst {
                it.source == OrderLineSource.MANUAL &&
                    it.item.speciesId == manualItem.speciesId &&
                    it.item.speciesName == manualItem.speciesName &&
                    it.item.quality == manualItem.quality &&
                    it.item.age == manualItem.age &&
                    it.item.size == manualItem.size &&
                    it.item.plantType == manualItem.plantType &&
                    it.item.retailPrice == manualItem.retailPrice
            }

            if (existingIndex >= 0) {
                val existing = cart[existingIndex]
                val newQuantity = existing.orderQuantity.toLong() + quantity
                if (newQuantity > Int.MAX_VALUE) return@update current.copy(message = "수량 범위를 초과했습니다.")
                cart[existingIndex] = existing.copy(orderQuantity = newQuantity.toInt())
            } else {
                cart.add(
                    CartItem(
                        item = manualItem,
                        orderQuantity = quantity,
                        source = OrderLineSource.MANUAL
                    )
                )
            }

            current.copy(
                estimateCart = cart,
                message = "✅ 수기 견적 품목을 담았습니다."
            )
        }
    }

    fun removeCartItem(index: Int) {
        _uiState.update { currentState ->
            val currentCart = currentState.estimateCart.toMutableList()
            if (index in currentCart.indices) currentCart.removeAt(index)
            currentState.copy(estimateCart = currentCart)
        }
    }

    fun clearCart() { _uiState.update { it.copy(estimateCart = emptyList()) } }
    fun clearMessage() { _uiState.update { it.copy(message = null) } }
    fun updateCustomerPhone(phone: String) { _uiState.update { it.copy(customerPhone = phone) } }

    fun fetchRecentCallLogs(context: android.content.Context) {
        launchOperation {
            try {
                val cursor = context.contentResolver.query(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    arrayOf(android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.CACHED_NAME),
                    null, null, "${android.provider.CallLog.Calls.DATE} DESC"
                )

                val callLogs = mutableListOf<CallLogItem>()
                val seenNumbers = mutableSetOf<String>()

                cursor?.use {
                    val numberIndex = it.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                    val nameIndex = it.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)

                    if (numberIndex != -1) {
                        while (it.moveToNext() && callLogs.size < 20) {
                            val num = it.getString(numberIndex) ?: ""
                            val name = if (nameIndex != -1) it.getString(nameIndex) else null
                            if (num.isNotBlank() && !seenNumbers.contains(num)) {
                                seenNumbers.add(num)
                                callLogs.add(CallLogItem(name, num))
                            }
                        }
                    }
                }

                if (callLogs.isNotEmpty()) _uiState.update { it.copy(recentCallList = callLogs) }
                else _uiState.update { it.copy(message = "⚠️ 통화 기록이 없습니다.") }
            } catch (e: SecurityException) {
                _uiState.update { it.copy(message = "🚫 통화 기록 권한이 거부되었습니다.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "❌ 오류: ${e.message}") }
            }
        }
    }

    // 견적 저장
    fun saveCurrentQuote(isPrepaid: Boolean = false, shippingFee: Int = 0, onSaved: () -> Unit = {}) {
        val state = _uiState.value
        if (state.estimateCart.isEmpty()) return
        if (!quoteSaveInProgress.compareAndSet(false, true)) return
        _uiState.update { it.copy(isSavingQuote = true) }

        val now = java.util.Date()
        val datePart = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(now)
        val displayDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(now)

        launchOperation {
            try {
                val quoteNumber = "QUOTE_${datePart}_${UUID.randomUUID()}"

                val detailSummary = OrderSummaryCodec.encode(
                    state.estimateCart.map { cartItem ->
                        OrderLine(
                            speciesName = cartItem.item.speciesName,
                            quality = cartItem.item.quality,
                            age = cartItem.item.age,
                            size = cartItem.item.size,
                            plantType = cartItem.item.plantType,
                            orderQty = cartItem.orderQuantity,
                            retailPrice = cartItem.item.retailPrice,
                            farmName = cartItem.item.farmName,
                            wholesalePrice = cartItem.item.wholesalePrice,
                            source = cartItem.source
                        )
                    }
                )

                val amounts = OrderAmounts.calculate(OrderSummaryCodec.parseStrict(detailSummary), isPrepaid, shippingFee)

                repository.insertQuote(
                    QuoteEntity(
                        quoteNumber = quoteNumber,
                        saveDate = displayDate,
                        customerPhone = state.customerPhone,
                        totalQty = amounts.quantity,
                        totalPrice = amounts.price,
                        itemSummary = detailSummary,
                        isPrepaid = isPrepaid,
                        shippingFee = shippingFee
                    )
                )

                loadTodayData()
                _uiState.update {
                    it.copy(
                        estimateCart = if (it.estimateCart == state.estimateCart) emptyList() else it.estimateCart,
                        message = "💾 견적 저장완료: $quoteNumber"
                    )
                }
                withContext(Dispatchers.Main) { onSaved() }
            } finally {
                quoteSaveInProgress.set(false)
                _uiState.update { it.copy(isSavingQuote = false) }
            }
        }
    }

    // 견적 → 주문 확정
    fun convertQuoteToOrder(quote: QuoteEntity, onConfirmed: (OrderEntity) -> Unit = {}) {
        if (!orderConfirmInProgress.compareAndSet(false, true)) return
        _uiState.update { it.copy(confirmingQuoteNumber = quote.quoteNumber) }
        launchOperation {
            try {
                val now = java.util.Date()
                val datePart = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(now)
                val displayDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(now)

                val orderNumber = "ORDER_${datePart}_${UUID.randomUUID()}"

                val newOrder = OrderEntity(
                    orderNumber = orderNumber,
                    orderDate = displayDate,
                    customerPhone = quote.customerPhone,
                    totalQty = quote.totalQty,
                    totalPrice = quote.totalPrice,
                    itemSummary = quote.itemSummary,
                    receiverName = "",
                    zipNo = "",
                    roadAddress = "",
                    detailAddress = "",
                    note = "",
                    status = OrderStatus.CONFIRMED,
                    isPrepaid = quote.isPrepaid,
                    shippingFee = quote.shippingFee
                )

                val result = repository.confirmOrder(quote, newOrder)

                refreshLocalState()

                _uiState.update {
                    it.copy(
                        message = if (result.cloudSynced) {
                            "✅ 로컬 주문확정 + 시트 연동 완료"
                        } else {
                            "⚠️ 로컬 주문확정 완료, 시트 연동 대기"
                        }
                    )
                }
                withContext(Dispatchers.Main) { onConfirmed(newOrder) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("NamuFarm", "주문확정 실패", e)
                refreshLocalState()
                _uiState.update {
                    it.copy(message = "❌ 주문확정 실패: ${e.message}")
                }
            } finally {
                orderConfirmInProgress.set(false)
                _uiState.update { it.copy(confirmingQuoteNumber = null) }
            }
        }
    }

    fun loadTodayData() {
        launchOperation {
            val quotes = repository.getQuotesForDate(todayDatePrefix())
            val orders = repository.getOrdersForDate(todayDatePrefix())
            _uiState.update { it.copy(todayQuotes = quotes, todayOrders = orders) }
        }
    }

    fun deleteQuote(quote: QuoteEntity) {
        launchOperation {
            repository.deleteQuote(quote)
            refreshLocalState()
            _uiState.update { it.copy(message = "🗑️ 견적서가 삭제되었습니다.") }
        }
    }

    // 주문 상태 업데이트: 현재 업무 상태는 입금확인 / 주문취소 두 가지만 허용
    fun updateOrderStatus(order: OrderEntity, newStatus: String) {
        if (!OrderStatus.isValid(newStatus)) {
            _uiState.update {
                it.copy(message = "❌ 지원하지 않는 주문 상태입니다: $newStatus")
            }
            return
        }

        if (newStatus == OrderStatus.CONFIRMED) {
            _uiState.update {
                it.copy(
                    message = if (order.status == OrderStatus.CONFIRMED) {
                        "ℹ️ 이미 입금확인 상태입니다."
                    } else {
                        "⚠️ 취소된 주문을 입금확인으로 되돌리는 기능은 지원하지 않습니다."
                    }
                )
            }
            return
        }

        launchOperation {
            val result = repository.cancelOrder(order.orderNumber)
            refreshLocalState()

            _uiState.update {
                it.copy(
                    message = when {
                        !result.success ->
                            "❌ 주문취소 실패: ${result.errorMessage}"
                        result.alreadyCanceled ->
                            "ℹ️ 이미 주문취소 상태입니다."
                        !result.cancelCloudSynced ->
                            "⚠️ 주문취소 + 재고 ${result.restoredQuantity}주 로컬 복원 완료, 시트 반영 대기"
                        result.inventoryPending > 0 ->
                            "⚠️ 주문취소 완료, 재고 ${result.restoredQuantity}주 복원. 시트 재고 ${result.inventoryPending}건 반영 대기"
                        else ->
                            "✅ 주문취소 완료 + 재고 ${result.restoredQuantity}주 자동 복원"
                    }
                )
            }
        }
    }
    // 🚨 1. 농원 선택 시, 이미 선택된 묘목이 있다면 재고 목록을 바로 불러오도록 수정!
    fun selectFarm(farm: FarmEntity) {
        _uiState.update { it.copy(selectedFarm = farm, isInputEnabled = false, isInputActive = false, inventoryList = emptyList()) }

        val currentSpecies = _uiState.value.selectedSpecies
        if (currentSpecies != null) {
            _uiState.update { it.copy(isInputEnabled = true) }
            loadInventory(farm.farmId!!, currentSpecies.speciesId!!)
        }
        selectChosung("ㄱ")
    }

    // 🚨 2. 묘목 선택 시, 농원이 없어도 무조건 선택되도록 제한(return) 해제!
    fun selectSpecies(species: SpeciesEntity) {
        _uiState.update { it.copy(selectedSpecies = species, isInputEnabled = true, isInputActive = false) }

        val farmId = _uiState.value.selectedFarm?.farmId
        val specId = species.speciesId

        // 농원과 묘목이 모두 선택되었을 때만 재고 리스트를 불러옴
        if (farmId != null && specId != null) {
            loadInventory(farmId, specId)
        } else {
            _uiState.update { it.copy(inventoryList = emptyList()) }
        }
    }

    private fun loadInventory(farmId: Int, speciesId: Int) {
        launchOperation {
            val rows = repository.getInventoryRows(farmId, speciesId)
            _uiState.update {
                if (it.selectedFarm?.farmId == farmId && it.selectedSpecies?.speciesId == speciesId) {
                    it.copy(inventoryList = rows)
                } else it
            }
        }
    }

    fun activateNewInput() { _uiState.update { it.copy(inputQuantity = 0, inputWholesale = 0, inputRetail = 0, isInputActive = true) } }

    // 🚨 새로 추가된 크기 및 타입 업데이트 함수
    fun updateSize(meters: Float) { _uiState.update { it.copy(inputSizeMeters = meters) } }
    fun updatePlantType(type: String) { _uiState.update { it.copy(inputPlantType = type) } }

    fun updateAge(age: String) { _uiState.update { it.copy(inputAge = age) } }
    fun updateQuality(q: String) { _uiState.update { it.copy(inputQuality = q) } }
    fun addQuantity(amount: Int) { _uiState.update { it.copy(inputQuantity = (it.inputQuantity.toLong() + amount).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) } }
    fun addWholesale(amount: Int) { _uiState.update { it.copy(inputWholesale = (it.inputWholesale.toLong() + amount).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) } }
    fun addRetail(amount: Int) { _uiState.update { it.copy(inputRetail = (it.inputRetail.toLong() + amount).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()) } }
    fun resetInput() { _uiState.update { it.copy(inputQuantity = 0, inputWholesale = 0, inputRetail = 0) } }

    fun setQuantity(value: Int) { if (value >= 0) _uiState.update { it.copy(inputQuantity = value) } }
    fun setWholesale(value: Int) { if (value >= 0) _uiState.update { it.copy(inputWholesale = value) } }
    fun setRetail(value: Int) { if (value >= 0) _uiState.update { it.copy(inputRetail = value) } }
    fun resetQuantityToSoldOut() { _uiState.update { it.copy(inputQuantity = 0, inputWholesale = 0, inputRetail = 0) } }
    fun resetWholesale() { _uiState.update { it.copy(inputWholesale = 0) } }
    fun resetRetail() { _uiState.update { it.copy(inputRetail = 0) } }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(message = msg) }
    }

    // 🚨 1. 수정됨: V2 규격에 맞춰 size와 plantType 로드 기능 추가
    fun loadExistingRowToInput(row: InventoryDisplayRow, allSpecies: List<SpeciesEntity>) {
        val spec = allSpecies.find { it.speciesId == row.speciesId }

        // "1.5m" -> 1.5f 로 변환 (에러 대비 0f 기본값)
        val sizeVal = row.size.replace("m", "").toFloatOrNull() ?: 0f

        _uiState.update { it.copy(
            selectedSpecies = spec,
            inputAge = row.age,
            inputSizeMeters = sizeVal,      // 🚨 추가
            inputPlantType = row.plantType, // 🚨 추가
            inputQuality = row.quality,
            inputQuantity = row.quantity,
            inputWholesale = row.wholesalePrice,
            inputRetail = row.retailPrice,
            isInputActive = true
        ) }
    }

    fun saveInventory() {
        val state = _uiState.value
        val farmId = state.selectedFarm?.farmId
        val farmName = state.selectedFarm?.farmName
        val speciesId = state.selectedSpecies?.speciesId
        val speciesName = state.selectedSpecies?.speciesName

        if (farmId == null || speciesId == null || farmName == null || speciesName == null) {
            return
        }

        launchOperation {
            val sizeStr = "${String.format(Locale.US, "%.1f", state.inputSizeMeters)}m"
            val isDelete = state.inputQuantity <= 0

            val targetEntity = FarmInventoryEntity(
                farmId = farmId,
                speciesId = speciesId,
                quality = state.inputQuality,
                age = state.inputAge,
                size = sizeStr,
                plantType = state.inputPlantType,
                quantity = state.inputQuantity,
                wholesalePrice = state.inputWholesale,
                retailPrice = state.inputRetail
            )

            val result = repository.saveInventory(
                entity = targetEntity,
                farmName = farmName,
                speciesName = speciesName
            )

            val newInventoryList = repository.getInventoryRows(farmId, speciesId)
            val newAllSellingInventory = repository.getAllSellingInventory()
            val selectedChosung = _uiState.value.selectedChosung
            val newFilteredSellingInventory = buildFilteredSellingInventory(
                newAllSellingInventory,
                selectedChosung
            )

            _uiState.update {
                it.copy(
                    inputQuantity = 0,
                    inputWholesale = 0,
                    inputRetail = 0,
                    inventoryList = newInventoryList,
                    allSellingInventory = newAllSellingInventory,
                    filteredSellingInventory = newFilteredSellingInventory,
                    message = when {
                        result.cloudSynced && isDelete -> "✅ 로컬 삭제 + 시트 동기화 완료"
                        result.cloudSynced -> "✅ 로컬 저장 + 시트 동기화 완료"
                        isDelete -> "⚠️ 로컬 삭제 완료, 시트 반영 대기"
                        else -> "⚠️ 로컬 저장 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }

    // 🚨 2. 추가됨: 동기화 버튼을 눌렀을 때 실행될 함수!
    fun syncDataWithGoogleSheet() {
        launchOperation {
            _uiState.update { it.copy(message = "🔄 미동기화 항목 재전송 중...") }

            val result = repository.syncAll()
            refreshLocalState()

            if (result.pendingFailureCount > 0) {
                _uiState.update {
                    it.copy(
                        message = "⚠️ Pull 중단: ${result.errorMessage ?: "미동기화 항목을 다시 전송해주세요."}"
                    )
                }
                return@launchOperation
            }

            val inventoryResult = result.inventoryResult
            val orderCount = result.orderSyncCount ?: -1

            _uiState.update {
                it.copy(
                    message = if (
                        inventoryResult == null ||
                        inventoryResult.errorMessage != null ||
                        orderCount < 0
                    ) {
                        "⚠️ 재고/주문 동기화 실패: ${inventoryResult?.errorMessage ?: "기존 데이터를 보존했습니다."}"
                    } else {
                        "✅ 동기화 완료 (재전송 ${result.resentSuccess}건, 재고 ${inventoryResult.upserted}건, 주문 ${orderCount}건)"
                    }
                )
            }
        }
    }

    fun syncFarmMasterFromGoogleSheet() {
        launchOperation {
            _uiState.update { it.copy(message = "🏡 농원목록 동기화 중...") }
            val result = repository.syncFarmMaster()
            refreshLocalState()
            _uiState.update {
                it.copy(
                    message = if (result < 0) {
                        "❌ 농원목록 동기화 실패"
                    } else {
                        "✅ 농원목록 동기화 완료 (${result}건)"
                    }
                )
            }
        }
    }

    fun syncSpeciesMasterFromGoogleSheet() {
        launchOperation {
            _uiState.update { it.copy(message = "🌱 묘목목록 동기화 중...") }
            val result = repository.syncSpeciesMaster()
            refreshLocalState()
            _uiState.update {
                it.copy(
                    message = if (result < 0) {
                        "❌ 묘목목록 동기화 실패"
                    } else {
                        "✅ 묘목목록 동기화 완료 (${result}건)"
                    }
                )
            }
        }
    }

    fun resetEstimateSession() {
        _uiState.update {
            it.copy(
                estimateCart = emptyList(),
                customerPhone = "",
                message = null
            )
        }
    }

    // 🚨 [수정] 빈칸인 스펙은 문자에서 아예 지워지도록 스마트 문자열(buildSpecText) 적용!
    private fun appendPaymentAccountText(sb: StringBuilder) {
        val paymentText = BuildConfig.PAYMENT_ACCOUNT_TEXT.trim()
        if (paymentText.isNotBlank()) {
            sb.append("[입금 계좌]\n")
            sb.append(paymentText)
            sb.append("\n\n")
        }
    }

    fun generateQuoteText(
        isPrepaid: Boolean = true,
        shippingFee: Int = 0
    ): String {
        val cart = _uiState.value.estimateCart
        if (cart.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[나무팜 견적서]\n\n")

        var totalRetail = 0L

        cart.forEach { cartItem ->
            val lineTotal = cartItem.item.retailPrice.toLong() * cartItem.orderQuantity
            totalRetail += lineTotal

            val specText = buildSpecText(
                quality = cartItem.item.quality,
                age = cartItem.item.age,
                size = cartItem.item.size,
                plantType = cartItem.item.plantType,
                separator = " / "
            )

            sb.append("🌱 ${cartItem.item.speciesName}")
            if (specText.isNotBlank()) sb.append(" ($specText)")
            sb.append("\n")
            sb.append(" - 수량: ${String.format("%,d", cartItem.orderQuantity)}주\n")
            sb.append(" - 단가: ${String.format("%,d", cartItem.item.retailPrice)}원\n")
            sb.append(" - 금액: ${String.format("%,d", lineTotal)}원\n\n")
        }

        sb.append("================\n")

        if (isPrepaid && shippingFee > 0) {
            sb.append("상품 합계: ${String.format("%,d", totalRetail)}원\n")
            sb.append("배송비(선불): ${String.format("%,d", shippingFee)}원\n")
            sb.append(
                "총 견적 금액: ${String.format("%,d", totalRetail + shippingFee)}원\n\n"
            )
        } else {
            sb.append("배송비: 후불(착불)\n")
            sb.append("총 견적 금액: ${String.format("%,d", totalRetail)}원\n\n")
        }

        appendPaymentAccountText(sb)
        sb.append("입금 후 주소와 성함을 보내주세요.\n\n")
        sb.append("감사합니다.")

        return sb.toString()
    }

    // 🚨 [수정] DB에 저장된 QuoteEntity를 읽어서 문자 보낼 때도 택배비 내역 추가!
    fun generateQuoteTextFromEntity(quote: QuoteEntity): String {
        val sb = StringBuilder()
        sb.append("[나무팜 견적서]\n\n")

        val lines = try {
            OrderSummaryCodec.parseStrict(quote.itemSummary)
        } catch (e: Exception) {
            showMessage("견적 품목을 읽을 수 없습니다: ${e.message}")
            return ""
        }
        lines.forEach { line ->
            val specText = buildSpecText(
                quality = line.quality,
                age = line.age,
                size = line.size,
                plantType = line.plantType,
                separator = " / "
            )
            val lineTotal = line.orderQty.toLong() * line.retailPrice

            sb.append("🌱 ${line.speciesName}")
            if (specText.isNotBlank()) sb.append(" ($specText)")
            sb.append("\n")
            sb.append(" - 수량: ${String.format("%,d", line.orderQty)}주\n")
            sb.append(" - 단가: ${String.format("%,d", line.retailPrice)}원\n")
            sb.append(" - 금액: ${String.format("%,d", lineTotal)}원\n\n")
        }

        sb.append("================\n")

        if (quote.isPrepaid && quote.shippingFee > 0) {
            val itemTotal = quote.totalPrice - quote.shippingFee
            sb.append("상품 합계: ${String.format("%,d", itemTotal)}원\n")
            sb.append("배송비(선불): ${String.format("%,d", quote.shippingFee)}원\n")
        } else {
            sb.append("배송비: 후불(착불)\n")
        }

        sb.append("총 견적 금액: ${String.format("%,d", quote.totalPrice)}원\n\n")
        appendPaymentAccountText(sb)
        sb.append("감사합니다.")

        return sb.toString()
    }

    // 주소 검색 API 설정
    private val JUSO_API_KEY = BuildConfig.JUSO_API_KEY

    fun clearJusoResults() {
        _uiState.update { it.copy(jusoResults = emptyList()) }
    }

    fun searchAddress(keyword: String) {
        if (keyword.length < 2) {
            _uiState.update { it.copy(jusoResults = emptyList()) }
            return
        }

        launchOperation {
            try {
                val results = repository.searchAddress(JUSO_API_KEY, keyword)
                _uiState.update {
                    it.copy(jusoResults = results)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Juso", "주소 검색 실패", e)
                _uiState.update {
                    it.copy(
                        jusoResults = emptyList(),
                        message = "주소 검색 오류: ${e.message}"
                    )
                }
            }
        }
    }
    fun updateOrderDeliveryInfo(
        order: OrderEntity,
        receiverName: String,
        zipNo: String,
        roadAddress: String,
        detailAddress: String,
        note: String
    ) {
        launchOperation {
            if (receiverName.isBlank()) {
                _uiState.update { it.copy(message = "받는사람 이름을 입력해주세요.") }
                return@launchOperation
            }

            if (roadAddress.isBlank()) {
                _uiState.update { it.copy(message = "기본 주소를 선택해주세요.") }
                return@launchOperation
            }

            val result = repository.updateOrderDeliveryInfo(
                orderNumber = order.orderNumber,
                receiverName = receiverName,
                zipNo = zipNo,
                roadAddress = roadAddress,
                detailAddress = detailAddress,
                note = note
            )

            refreshLocalState()
            _uiState.update {
                it.copy(
                    message = if (result.cloudSynced) {
                        "✅ 로컬 배송정보 + 시트 저장 완료"
                    } else {
                        "⚠️ 로컬 배송정보 저장 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }

    fun saveFarm(
        farmId: Int?,
        farmName: String,
        address: String,
        phone: String,
        memo: String
    ) {
        launchOperation {
            val result = repository.saveFarm(farmId, farmName, address, phone, memo)

            refreshLocalState()
            val farms = repository.getAllFarms()

            _uiState.update {
                it.copy(
                    farmList = farms,
                    selectedFarm = farms.find { farm ->
                        farm.farmId == result.farm.farmId
                    },
                    message = if (result.cloudSynced) {
                        "✅ 농원 저장 + 시트 연동 완료"
                    } else {
                        "⚠️ 농원 로컬 저장 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }

    fun deleteFarm(farmId: Int) {
        launchOperation {
            val result = repository.deleteFarm(farmId)
            refreshLocalState()

            _uiState.update {
                it.copy(
                    selectedFarm = null,
                    selectedSpecies = null,
                    inventoryList = emptyList(),
                    speciesList = emptyList(),
                    isSpeciesEnabled = false,
                    isInputEnabled = false,
                    isInputActive = false,
                    message = if (result.cloudSynced) {
                        "🗑️ 농원 및 재고 삭제 + 시트 반영 완료"
                    } else {
                        "⚠️ 농원 및 재고 로컬 삭제 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }

    fun saveSpecies(
        speciesId: Int?,
        speciesName: String,
        nameFarm: String,
        chosung: String,
        category: String,
        memo: String,
        isActive: Int = 1
    ) {
        launchOperation {
            val result = repository.saveSpecies(
                speciesId = speciesId,
                speciesName = speciesName,
                nameFarm = nameFarm,
                chosung = chosung,
                category = category,
                memo = memo,
                isActive = isActive
            )

            refreshLocalState()
            _uiState.update {
                it.copy(
                    message = if (result.cloudSynced) {
                        "✅ 묘목 저장 + 시트 연동 완료"
                    } else {
                        "⚠️ 묘목 로컬 저장 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }

    fun deleteSpecies(speciesId: Int) {
        launchOperation {
            val result = repository.deleteSpecies(speciesId)
            refreshLocalState()

            _uiState.update {
                it.copy(
                    message = if (result.cloudSynced) {
                        "🗑️ 묘목 비활성화 + 시트 반영 완료"
                    } else {
                        "⚠️ 묘목 로컬 비활성화 완료, 시트 반영 대기"
                    }
                )
            }
        }
    }
    // 🚨 [추가] 꽉 막힌 동기화 큐를 강제로 날려버리는 관리자 비상용 함수
}

// 🚨 3. 수정됨: Factory 클래스에도 SyncManager 파라미터 추가
class ManagementViewModelFactory(
    private val repository: NamuFarmRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ManagementViewModel(repository) as T
    }
}

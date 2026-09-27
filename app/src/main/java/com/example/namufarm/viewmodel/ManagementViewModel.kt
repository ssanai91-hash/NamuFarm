package com.example.namufarm.viewmodel

import android.content.Context
import com.example.namufarm.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.namufarm.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update // 🚨 핵심 방패 추가!
import kotlinx.coroutines.launch
import com.example.namufarm.data.SyncManager
import com.example.namufarm.data.remote.JusoItem
import com.example.namufarm.data.remote.RetrofitClient
import com.example.namufarm.data.remote.SheetOrderItemDto
import com.google.gson.Gson

data class CartItem(val item: SellingDisplayRow, val orderQuantity: Int)
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
    private val dao: ManagementDao,
    private val syncManager: SyncManager
) : ViewModel()
{
    private val _uiState = MutableStateFlow(ManagementUiState())
    val uiState: StateFlow<ManagementUiState> = _uiState.asStateFlow()

    init {
        loadInitialData()
        loadTodayData()
    }

    private fun loadInitialData() {
        viewModelScope.launch(Dispatchers.IO) {
            var allSpecies = dao.getAllSpecies()
            var farms = dao.getAllFarms()
            var allInventory = dao.getAllSellingInventory()

            // 🚨 [핵심 추가] 폰 DB가 완전 텅 비어있다면? (최초 설치 or DB 파괴 직후)
            // 구글 시트에서 전체 농원/묘목 마스터 데이터를 최초 1회 자동으로 싹 긁어온다!
            if (farms.isEmpty() || allSpecies.isEmpty()) {
                _uiState.update { it.copy(message = "🔄 최초 실행: 전체 농원/묘목 마스터 데이터 가져오는 중...") }

                // 마스터 데이터 강제 1회 다운로드
                val farmSyncCount = syncManager.syncFarmsWithGoogleSheet()
                val speciesSyncCount = syncManager.syncSpeciesWithGoogleSheet()

                // 다운로드 끝났으니 DB에서 다시 읽어오기
                allSpecies = dao.getAllSpecies()
                farms = dao.getAllFarms()
                allInventory = dao.getAllSellingInventory()

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

            android.util.Log.d("NamuFarm", "farms.size = ${farms.size}")
            android.util.Log.d("NamuFarm", "species.size = ${allSpecies.size}")
            android.util.Log.d("NamuFarm", "inventory.size = ${allInventory.size}")

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

    private data class LocalOrderLine(
        val speciesName: String,
        val quality: String,
        val age: String,
        val size: String,
        val plantType: String,
        val orderQty: Int,
        val retailPrice: Int,
        val farmName: String,
        val wholesalePrice: Int
    )

    private fun parseLocalOrderLine(line: String): LocalOrderLine? {
        val parts = line.split("|")

        return when {
            parts.size >= 9 -> {
                LocalOrderLine(
                    speciesName = parts[0],
                    quality = parts[1],
                    age = parts[2],
                    size = parts[3],
                    plantType = parts[4],
                    orderQty = parts[5].toIntOrNull() ?: 0,
                    retailPrice = parts[6].toIntOrNull() ?: 0,
                    farmName = parts[7],
                    wholesalePrice = parts[8].toIntOrNull() ?: 0
                )
            }
            else -> null
        }
    }

    private val gson = Gson()

    private fun todayDatePrefix(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
            .format(java.util.Date())

    private fun enqueuePendingSync(type: String, payload: Any): Int {
        return dao.insertPendingSync(
            PendingSyncEntity(
                syncType = type,
                payloadJson = gson.toJson(payload)
            )
        ).toInt()
    }

    private fun refreshLocalState() {
        val farms = dao.getAllFarms()
        val allSpecies = dao.getAllSpecies()
        val allInventory = dao.getAllSellingInventory()
        val quotes = dao.getQuotesForDate(todayDatePrefix())
        val orders = dao.getOrdersForDate(todayDatePrefix())

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
                todayOrders = orders
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

    private fun deductLocalInventoryFromSummary(summary: String) {
        val lines = summary.split("\n").filter { it.isNotBlank() }

        lines.forEach { line ->
            val item = parseLocalOrderLine(line) ?: return@forEach

            val farm = dao.getFarmByName(item.farmName.trim()) ?: return@forEach
            val species = dao.getSpeciesByName(item.speciesName.trim()) ?: return@forEach

            val farmId = farm.farmId ?: return@forEach
            val speciesId = species.speciesId ?: return@forEach

            val current = dao.getInventoryEntity(
                farmId = farmId,
                speciesId = speciesId,
                quality = item.quality,
                age = item.age,
                size = item.size,
                plantType = item.plantType
            ) ?: return@forEach

            val newQty = current.quantity - item.orderQty

            if (newQty <= 0) {
                dao.deleteInventory(
                    farmId = farmId,
                    speciesId = speciesId,
                    quality = item.quality,
                    age = item.age,
                    size = item.size,
                    plantType = item.plantType
                )
            } else {
                dao.upsertInventory(
                    current.copy(quantity = newQty)
                )
            }
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

    // --- 🛒 [매매 모드 & 통화기록 로직] ---
    fun addToCart(item: SellingDisplayRow, qty: Int) {
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
                val newQty = currentCart[existingIndex].orderQuantity + qty
                if (newQty <= item.quantity) {
                    currentCart[existingIndex] = currentCart[existingIndex].copy(orderQuantity = newQty)
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
        viewModelScope.launch(Dispatchers.IO) {
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
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "❌ 오류: ${e.message}") }
            }
        }
    }

    // 🚨 1. 견적서 저장 함수 수정 (파라미터 추가 및 총액 합산 로직)
    fun saveCurrentQuote(isPrepaid: Boolean = false, shippingFee: Int = 0) {
        val state = _uiState.value
        if (state.estimateCart.isEmpty()) return

        val now = java.util.Date()
        val datePart = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(now)
        val displayDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(now)

        viewModelScope.launch(Dispatchers.IO) {
            val lastId = dao.getLastQuoteId() ?: 0
            val quoteNumber = "QUOTE_${datePart}_${String.format("%03d", lastId + 1)}"

            val detailSummary = state.estimateCart.joinToString(separator = "\n") { cartItem ->
                "${cartItem.item.speciesName}|" +
                        "${cartItem.item.quality}|" +
                        "${cartItem.item.age}|" +
                        "${cartItem.item.size}|" +
                        "${cartItem.item.plantType}|" +
                        "${cartItem.orderQuantity}|" +
                        "${cartItem.item.retailPrice}|" +
                        "${cartItem.item.farmName}|" +
                        "${cartItem.item.wholesalePrice}"
            }

            // 🚨 카트 총액에 선불 택배비를 합산!
            val cartTotal = state.estimateCart.sumOf { it.item.retailPrice * it.orderQuantity }
            val finalTotalPrice = cartTotal + if (isPrepaid) shippingFee else 0

            dao.insertQuote(
                QuoteEntity(
                    quoteNumber = quoteNumber,
                    saveDate = displayDate,
                    customerPhone = state.customerPhone,
                    totalQty = state.estimateCart.sumOf { it.orderQuantity },
                    totalPrice = finalTotalPrice, // 🚨 수정됨: 택배비 포함된 총액
                    itemSummary = detailSummary,
                    isPrepaid = isPrepaid,        // 🚨 추가됨: 선불 여부 (DB Entity에 이 변수가 있어야 함!)
                    shippingFee = shippingFee     // 🚨 추가됨: 택배비 금액 (DB Entity에 이 변수가 있어야 함!)
                )
            )

            loadTodayData()
            clearCart()
            _uiState.update { it.copy(message = "💾 견적 저장완료: $quoteNumber") }
        }
    }

    // 🚨 중복 방지용 전역 카운터 함수 추가! (ViewModel 클래스 안에 넣어줘)
    private fun getNextOrderSequence(context: android.content.Context): Int {
        val pref = context.getSharedPreferences("NamuFarmPrefs", android.content.Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(java.util.Date())
        val lastDate = pref.getString("last_order_date", "")

        var currentSeq = pref.getInt("last_order_seq", 0)

        // 날짜가 바뀌었으면 카운터 리셋, 같으면 계속 증가
        if (today != lastDate) {
            currentSeq = 1
        } else {
            currentSeq += 1
        }

        // 변경된 값 저장
        pref.edit().apply {
            putString("last_order_date", today)
            putInt("last_order_seq", currentSeq)
            apply()
        }

        return currentSeq
    }

    // 🚨 주문 확정 로직 업데이트 (context 파라미터 추가!)
    fun convertQuoteToOrder(quote: QuoteEntity, context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = java.util.Date()
            val datePart = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.KOREA).format(now)
            val displayDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.KOREA).format(now)

            val currentSeq = getNextOrderSequence(context)
            val orderNumber = "ORDER_${datePart}_${String.format("%03d", currentSeq)}"

            // 🚨 2. 견적서를 주문서로 바꿀 때 택배비 정보도 같이 넘겨줌!
            val newOrder = OrderEntity(
                orderNumber = orderNumber,
                orderDate = displayDate,
                customerPhone = quote.customerPhone,
                totalQty = quote.totalQty,
                totalPrice = quote.totalPrice, // 이미 견적서에 택배비가 포함되어 있으므로 그대로 사용
                itemSummary = quote.itemSummary,
                receiverName = "",
                zipNo = "",
                roadAddress = "",
                detailAddress = "",
                note = "",
                status = "입금확인",
                isPrepaid = quote.isPrepaid,     // 🚨 추가됨: 견적서의 택배비 설정 그대로 복사
                shippingFee = quote.shippingFee  // 🚨 추가됨: 견적서의 택배비 금액 그대로 복사
            )

            // 주문 품목 패킷 준비
            val summaryLines = quote.itemSummary.split("\n").filter { it.isNotBlank() }
            val itemsToDeduct = summaryLines.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 9) {
                    SheetOrderItemDto(
                        species_name = parts[0],
                        quality = parts[1],
                        age = parts[2],
                        size = parts[3],
                        plant_type = parts[4],
                        order_qty = parts[5].toIntOrNull() ?: 0,
                        retail_price = parts[6].toIntOrNull() ?: 0,
                        farm_name = parts[7]
                    )
                } else {
                    null
                }
            }

            // 1) 로컬 DB 먼저 주문 확정
            dao.insertOrder(newOrder)
            dao.deleteQuote(quote)
            deductLocalInventoryFromSummary(quote.itemSummary)

            val quotes = dao.getQuotesForDate(todayDatePrefix())
            val orders = dao.getOrdersForDate(todayDatePrefix())
            val allSellingInventory = dao.getAllSellingInventory()
            val selectedChosung = _uiState.value.selectedChosung
            val filteredSellingInventory = buildFilteredSellingInventory(
                allSellingInventory = allSellingInventory,
                chosung = selectedChosung
            )

            _uiState.update {
                it.copy(
                    todayQuotes = quotes,
                    todayOrders = orders,
                    allSellingInventory = allSellingInventory,
                    filteredSellingInventory = filteredSellingInventory,
                    message = "💾 로컬 주문확정 완료"
                )
            }

            // 2) pending 큐 등록
            val pendingId = enqueuePendingSync(
                PendingSyncType.ORDER,
                PendingOrderSyncPayload(
                    order = newOrder,
                    items = itemsToDeduct
                )
            )

            // 3) 구글시트 업로드는 1번만
            val isCloudSuccess = syncManager.uploadOrderToSheet(
                orderEntity = newOrder,
                itemsToDeduct = itemsToDeduct
            )

            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
            }

            _uiState.update {
                it.copy(
                    message = if (isCloudSuccess) {
                        "✅ 로컬 주문확정 + 시트 연동 완료"
                    } else {
                        "⚠️ 로컬 주문확정 완료, 시트 연동 실패"
                    }
                )
            }
        }
    }

    fun loadTodayData() {
        viewModelScope.launch(Dispatchers.IO) {
            val quotes = dao.getQuotesForDate(todayDatePrefix())
            val orders = dao.getOrdersForDate(todayDatePrefix())
            _uiState.update { it.copy(todayQuotes = quotes, todayOrders = orders) }
        }
    }

    fun deleteQuote(quote: QuoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteQuote(quote)
            val quotes = dao.getQuotesForDate(todayDatePrefix())
            val orders = dao.getOrdersForDate(todayDatePrefix())
            _uiState.update {
                it.copy(
                    todayQuotes = quotes,
                    todayOrders = orders,
                    message = "🗑️ 견적서가 삭제되었습니다."
                )
            }
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.updateOrderStatus(
                    orderNumber = order.orderNumber,
                    status = newStatus
                )

                if (newStatus == OrderStatus.CANCELED) {
                    val pendingId = enqueuePendingSync(
                        PendingSyncType.CANCEL_ORDER,
                        PendingCancelOrderSyncPayload(order.orderNumber)
                    )

                    val isCloudSuccess = syncManager.cancelOrderOnSheet(order.orderNumber)

                    if (isCloudSuccess) {
                        dao.deletePendingSync(pendingId)
                    }
                }

                refreshLocalState()

                _uiState.update {
                    it.copy(
                        message = if (newStatus == OrderStatus.CANCELED) {
                            "✅ 주문이 취소 상태로 변경되었습니다."
                        } else {
                            "✅ 주문 상태가 [${OrderStatus.CONFIRMED}]로 변경되었습니다."
                        }
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("NamuFarm", "주문 상태 변경 실패", e)
                _uiState.update {
                    it.copy(message = "❌ 주문 상태 변경 실패: ${e.message}")
                }
            }
        }
    }
    /**
     * 주문 영구 삭제는 업무 규칙상 지원하지 않는다.
     * 주문 기록은 보존하고 상태를 '주문취소'로 관리한다.
     * UI의 영구삭제 버튼은 다음 UI 정리 단계에서 제거한다.
     */
    fun deleteOrder(order: OrderEntity) {
        _uiState.update {
            it.copy(
                message = "ℹ️ 주문 영구삭제는 사용하지 않습니다. 주문취소로 상태를 관리해주세요."
            )
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
        viewModelScope.launch(Dispatchers.IO) {
            val rows = dao.getInventoryRows(farmId, speciesId)
            _uiState.update { it.copy(inventoryList = rows) }
        }
    }

    fun activateNewInput() { _uiState.update { it.copy(inputQuantity = 0, inputWholesale = 0, inputRetail = 0, isInputActive = true) } }

    // 🚨 새로 추가된 크기 및 타입 업데이트 함수
    fun updateSize(meters: Float) { _uiState.update { it.copy(inputSizeMeters = meters) } }
    fun updatePlantType(type: String) { _uiState.update { it.copy(inputPlantType = type) } }

    fun updateAge(age: String) { _uiState.update { it.copy(inputAge = age) } }
    fun updateQuality(q: String) { _uiState.update { it.copy(inputQuality = q) } }
    fun addQuantity(amount: Int) { _uiState.update { it.copy(inputQuantity = it.inputQuantity + amount) } }
    fun addWholesale(amount: Int) { _uiState.update { it.copy(inputWholesale = it.inputWholesale + amount) } }
    fun addRetail(amount: Int) { _uiState.update { it.copy(inputRetail = it.inputRetail + amount) } }
    fun resetInput() { _uiState.update { it.copy(inputQuantity = 0, inputWholesale = 0, inputRetail = 0) } }
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

        if (farmId == null || speciesId == null || farmName == null || speciesName == null) return

        viewModelScope.launch(Dispatchers.IO) {
            val sizeStr = "${String.format("%.1f", state.inputSizeMeters)}m"
            val isDelete = state.inputQuantity <= 0
            val action = if (isDelete) "delete" else "save"

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

            // 1) 로컬 먼저 저장
            if (isDelete) {
                dao.deleteInventory(
                    farmId = farmId,
                    speciesId = speciesId,
                    quality = state.inputQuality,
                    age = state.inputAge,
                    size = sizeStr,
                    plantType = state.inputPlantType
                )
            } else {
                dao.upsertInventory(targetEntity)
            }

            val newInventoryList = dao.getInventoryRows(farmId, speciesId)
            val newAllSellingInventory = dao.getAllSellingInventory()
            val selectedChosung = _uiState.value.selectedChosung
            val newFilteredSellingInventory = buildFilteredSellingInventory(
                allSellingInventory = newAllSellingInventory,
                chosung = selectedChosung
            )

            _uiState.update {
                it.copy(
                    inputQuantity = 0,
                    inputWholesale = 0,
                    inputRetail = 0,
                    inventoryList = newInventoryList,
                    allSellingInventory = newAllSellingInventory,
                    filteredSellingInventory = newFilteredSellingInventory,
                    message = if (isDelete) "💾 로컬 삭제 완료" else "💾 로컬 저장 완료"
                )
            }

            // 2) pending 큐 적재
            val pendingId = enqueuePendingSync(
                PendingSyncType.INVENTORY,
                PendingInventorySyncPayload(
                    action = action,
                    entity = targetEntity,
                    farmName = farmName,
                    speciesName = speciesName
                )
            )

            // 3) 시트 업로드는 1번만
            val isCloudSuccess = syncManager.uploadToSheet(
                action = action,
                entity = targetEntity,
                farmName = farmName,
                speciesName = speciesName
            )

            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
            }

            _uiState.update {
                it.copy(
                    message = if (isCloudSuccess) {
                        if (isDelete) "✅ 로컬 삭제 + 시트 동기화 완료"
                        else "✅ 로컬 저장 + 시트 동기화 완료"
                    } else {
                        if (isDelete) "⚠️ 로컬 삭제 완료, 시트 동기화 실패"
                        else "⚠️ 로컬 저장 완료, 시트 동기화 실패"
                    }
                )
            }
        }
    }

    // 🚨 2. 추가됨: 동기화 버튼을 눌렀을 때 실행될 함수!
    fun syncDataWithGoogleSheet() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = "🔄 미동기화 항목 재전송 중...") }

            val pendingList = dao.getAllPendingSync()

            var resentSuccess = 0
            var resentFail = 0

            pendingList.forEach { pending ->
                val result = syncManager.processPendingSync(pending)

                if (result.success) {
                    pending.syncId?.let { dao.deletePendingSync(it) }
                    resentSuccess++
                } else {
                    pending.syncId?.let {
                        dao.markPendingSyncFailed(
                            syncId = it,
                            error = result.errorMessage ?: "알 수 없는 오류"
                        )
                    }
                    resentFail++
                }
            }

            refreshLocalState()

            if (resentFail > 0) {
                _uiState.update {
                    it.copy(
                        message = "⚠️ 미동기화 ${resentFail}건 남아 있어 Pull 생략. 네트워크 확인 후 다시 동기화하세요."
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    message = if (resentSuccess > 0) {
                        "☁️ 재전송 ${resentSuccess}건 완료. 재고/주문 최신 데이터 가져오는 중..."
                    } else {
                        "☁️ 미동기화 없음. 재고/주문 최신 데이터 가져오는 중..."
                    }
                )
            }

            val inventorySyncResult = syncManager.syncWithGoogleSheet()
            val orderSyncCount = syncManager.syncOrdersWithGoogleSheet()

            refreshLocalState()

            _uiState.update {
                it.copy(
                    message = if (inventorySyncResult.errorMessage != null || orderSyncCount < 0) {
                        "⚠️ 재고/주문 동기화 일부 실패"
                    } else {
                        "✅ 동기화 완료 (재고: ${inventorySyncResult.upserted}건, 주문: ${orderSyncCount}건)"
                    }
                )
            }
        }
    }

    fun syncFarmMasterFromGoogleSheet() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = "🏡 농원목록 동기화 중...") }

            val result = syncManager.syncFarmsWithGoogleSheet()
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
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(message = "🌱 묘목목록 동기화 중...") }

            val result = syncManager.syncSpeciesWithGoogleSheet()
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

    private fun buildDetailedItemSummary(cart: List<CartItem>): String {
        return cart.joinToString(separator = "\n") { cartItem ->
            "${cartItem.item.speciesName} ${cartItem.item.age} ${cartItem.item.quality} ${cartItem.orderQuantity}주"
        }
    }

    // 🚨 [수정] 빈칸인 스펙은 문자에서 아예 지워지도록 스마트 문자열(buildSpecText) 적용!
    fun generateQuoteText(isPrepaid: Boolean = true, shippingFee: Int = 0): String {
        val cart = _uiState.value.estimateCart
        if (cart.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("[나무팜 견적서]\n\n")
        var totalRetail = 0
        cart.forEach { cartItem ->
            val price = cartItem.item.retailPrice * cartItem.orderQuantity
            totalRetail += price

            // 🚨 바로 여기! 무식한 슬래시(/) 조합을 빼고, 비어있는 값은 알아서 날려주는 buildSpecText 적용!
            val specText = buildSpecText(
                quality = cartItem.item.quality,
                age = cartItem.item.age,
                size = cartItem.item.size,
                plantType = cartItem.item.plantType,
                separator = " / "
            )

            sb.append("🌱 ${cartItem.item.speciesName} ($specText)\n")
            sb.append(" - 수량: ${String.format("%,d", cartItem.orderQuantity)}주\n")
            sb.append(" - 단가: ${String.format("%,d", cartItem.item.retailPrice)}원\n")
            sb.append(" - 금액: ${String.format("%,d", price)}원\n\n")
        }
        sb.append("================\n")

        // 택배비 선불/후불 분기 처리
        if (isPrepaid && shippingFee > 0) {
            sb.append("상품 합계: ${String.format("%,d", totalRetail)}원\n")
            sb.append("배송비(선불): ${String.format("%,d", shippingFee)}원\n")
            sb.append("총 견적 금액: ${String.format("%,d", totalRetail + shippingFee)}원\n\n")
        } else {
            sb.append("배송비: 후불(착불)\n")
            sb.append("총 견적 금액: ${String.format("%,d", totalRetail)}원\n\n")
        }

        sb.append("[입금 계좌]\n")
        sb.append("농협 328-12-035671 오진웅\n\n")
        sb.append("입금하시고 주소와 성함 문자주세요.\n\n")
        sb.append("감사합니다. ^^")

        return sb.toString()
    }

    // 🚨 [수정] DB에 저장된 QuoteEntity를 읽어서 문자 보낼 때도 택배비 내역 추가!
    fun generateQuoteTextFromEntity(quote: QuoteEntity): String {
        val sb = StringBuilder()
        sb.append("[나무팜 견적서]\n\n")

        val lines = quote.itemSummary.split("\n").filter { it.isNotBlank() }
        lines.forEach { line ->
            val parts = line.split("|")

            if (parts.size >= 9) {
                val species = parts[0]
                val quality = parts[1]
                val age = parts[2]
                val size = parts[3]
                val plantType = parts[4]
                val qty = parts[5].toIntOrNull() ?: 0
                val price = parts[6].toIntOrNull() ?: 0

                sb.append("🌱 $species ($quality / $age / $size / $plantType)\n")
                sb.append(" - 수량: ${String.format("%,d", qty)}주\n")
                sb.append(" - 단가: ${String.format("%,d", price)}원\n")
                sb.append(" - 금액: ${String.format("%,d", qty * price)}원\n\n")
            } else if (parts.size >= 7) {
                // 예전 저장 포맷도 호환
                val species = parts[0]
                val quality = parts[1]
                val age = parts[2]
                val qty = parts[3].toIntOrNull() ?: 0
                val price = parts[4].toIntOrNull() ?: 0

                sb.append("🌱 $species ($quality / $age)\n")
                sb.append(" - 수량: ${String.format("%,d", qty)}주\n")
                sb.append(" - 단가: ${String.format("%,d", price)}원\n")
                sb.append(" - 금액: ${String.format("%,d", qty * price)}원\n\n")
            }
        }

        sb.append("================\n")

        // 🚨 택배비 선불/후불 분기 처리
        if (quote.isPrepaid && quote.shippingFee > 0) {
            val itemTotal = quote.totalPrice - quote.shippingFee
            sb.append("상품 합계: ${String.format("%,d", itemTotal)}원\n")
            sb.append("배송비(선불): ${String.format("%,d", quote.shippingFee)}원\n")
        } else {
            sb.append("배송비: 후불(착불)\n")
        }

        sb.append("총 견적 금액: ${String.format("%,d", quote.totalPrice)}원\n\n")
        sb.append("[입금 계좌]\n")
        sb.append("농협 328-12-035671 오진웅\n\n")
        sb.append("감사합니다. ^^")

        return sb.toString()
    }

    // 🚨 브로가 발급받은 승인키 (상수로 정의)
    private val JUSO_API_KEY = BuildConfig.JUSO_API_KEY

    fun clearJusoResults() {
        _uiState.update { it.copy(jusoResults = emptyList()) }
    }

    fun searchAddress(keyword: String) {
        if (keyword.length < 2) {
            _uiState.update { it.copy(jusoResults = emptyList()) }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.jusoApi.searchAddress(JUSO_API_KEY, keyword)
                val common = response.results.common

                if (common.errorCode == "0") {
                    _uiState.update {
                        it.copy(
                            jusoResults = response.results.juso ?: emptyList()
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            jusoResults = emptyList()
                        )
                    }
                }
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
        viewModelScope.launch(Dispatchers.IO) {
            if (receiverName.isBlank()) {
                _uiState.update { it.copy(message = "받는사람 이름을 입력해주세요.") }
                return@launch
            }

            if (roadAddress.isBlank()) {
                _uiState.update { it.copy(message = "기본 주소를 선택해주세요.") }
                return@launch
            }

            // 1) 로컬 먼저 저장
            dao.updateOrderDeliveryInfo(
                orderNumber = order.orderNumber,
                receiverName = receiverName,
                zipNo = zipNo,
                roadAddress = roadAddress,
                detailAddress = detailAddress,
                note = note
            )

            val pendingId = enqueuePendingSync(
                PendingSyncType.DELIVERY,
                PendingDeliverySyncPayload(
                    orderNumber = order.orderNumber,
                    receiverName = receiverName,
                    zipNo = zipNo,
                    roadAddress = roadAddress,
                    detailAddress = detailAddress,
                    note = note
                )
            )

            refreshLocalState()
            _uiState.update { it.copy(message = "💾 로컬 배송정보 저장 완료") }

            // 2) 시트 즉시 시도
            val isCloudSuccess = syncManager.updateOrderDeliveryOnSheet(
                orderNumber = order.orderNumber,
                receiverName = receiverName,
                zipNo = zipNo,
                roadAddress = roadAddress,
                detailAddress = detailAddress,
                note = note
            )

            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
                _uiState.update { it.copy(message = "✅ 로컬 배송정보 + 시트 저장 완료") }
            } else {
                _uiState.update { it.copy(message = "⚠️ 로컬 배송정보 저장 완료, 시트 반영 대기") }
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
        viewModelScope.launch(Dispatchers.IO) {
            val savedId = dao.upsertFarm(
                FarmEntity(
                    farmId = farmId,
                    farmName = farmName,
                    address = address.ifBlank { null },
                    phone = phone.ifBlank { null },
                    memo = memo.ifBlank { null }
                )
            ).toInt()

            val targetFarm = FarmEntity(
                farmId = farmId ?: savedId,
                farmName = farmName,
                address = address.ifBlank { null },
                phone = phone.ifBlank { null },
                memo = memo.ifBlank { null }
            )

            val farms = dao.getAllFarms()

            _uiState.update {
                it.copy(
                    farmList = farms,
                    selectedFarm = farms.find { farm -> farm.farmId == (farmId ?: savedId) },
                    message = "🏡 로컬 농원 저장 완료"
                )
            }

            val pendingId = enqueuePendingSync(
                PendingSyncType.FARM_SAVE,
                PendingFarmSyncPayload(targetFarm)
            )

            val isCloudSuccess = syncManager.uploadFarmToSheet(targetFarm)
            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
                _uiState.update { it.copy(message = "✅ 농원 저장 + 시트 연동 완료") }
            } else {
                _uiState.update { it.copy(message = "⚠️ 농원 로컬 저장 완료, 시트 연동 실패") }
            }
        }
    }

    fun deleteFarm(farmId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteInventoryByFarmId(farmId)
            dao.deleteFarmById(farmId)

            val pendingId = enqueuePendingSync(
                PendingSyncType.FARM_DELETE,
                PendingDeleteFarmSyncPayload(farmId)
            )

            val isCloudSuccess = syncManager.deleteFarmOnSheet(farmId)
            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
            }

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
                    message = if (isCloudSuccess) {
                        "🗑️ 농원 및 재고 삭제 + 시트 반영 완료"
                    } else {
                        "⚠️ 농원 및 재고 로컬 삭제 완료, 시트 연동 실패"
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
        viewModelScope.launch(Dispatchers.IO) {
            val savedId = dao.upsertSpecies(
                SpeciesEntity(
                    speciesId = speciesId,
                    speciesName = speciesName,
                    nameFarm = nameFarm.ifBlank { null },
                    chosung = chosung.ifBlank { null },
                    category = category.ifBlank { null },
                    memo = memo.ifBlank { null },
                    isActive = isActive
                )
            ).toInt()

            val targetSpecies = SpeciesEntity(
                speciesId = speciesId ?: savedId,
                speciesName = speciesName,
                nameFarm = nameFarm.ifBlank { null },
                chosung = chosung.ifBlank { null },
                category = category.ifBlank { null },
                memo = memo.ifBlank { null },
                isActive = isActive
            )

            refreshLocalState()
            _uiState.update { it.copy(message = "🌱 로컬 묘목 저장 완료") }

            val pendingId = enqueuePendingSync(
                PendingSyncType.SPECIES_SAVE,
                PendingSpeciesSyncPayload(targetSpecies)
            )

            val isCloudSuccess = syncManager.uploadSpeciesToSheet(targetSpecies)
            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
                _uiState.update { it.copy(message = "✅ 묘목 저장 + 시트 연동 완료") }
            } else {
                _uiState.update { it.copy(message = "⚠️ 묘목 로컬 저장 완료, 시트 연동 실패") }
            }
        }
    }

    fun deleteSpecies(speciesId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deactivateSpecies(speciesId)

            val pendingId = enqueuePendingSync(
                PendingSyncType.SPECIES_DELETE,
                PendingDeleteSpeciesSyncPayload(speciesId)
            )

            val isCloudSuccess = syncManager.deleteSpeciesOnSheet(speciesId)
            if (isCloudSuccess) {
                dao.deletePendingSync(pendingId)
            }

            refreshLocalState()

            _uiState.update {
                it.copy(
                    message = if (isCloudSuccess) {
                        "🗑️ 묘목 비활성화 + 시트 반영 완료"
                    } else {
                        "⚠️ 묘목 로컬 비활성화 완료, 시트 연동 실패"
                    }
                )
            }
        }
    }
    // 🚨 [추가] 꽉 막힌 동기화 큐를 강제로 날려버리는 관리자 비상용 함수
    fun forceResetSyncQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.clearAllPendingSync()
            _uiState.update { it.copy(message = "🗑️ 동기화 큐가 강제 초기화되었습니다. 이제 동기화가 가능합니다.") }
        }
    }
}

// 🚨 3. 수정됨: Factory 클래스에도 SyncManager 파라미터 추가
class ManagementViewModelFactory(
    private val dao: ManagementDao,
    private val syncManager: SyncManager // 👈 공장에도 엔진 부품 공급
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ManagementViewModel(dao, syncManager) as T // 👈 조립 완료
    }
}
package com.example.namufarm.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object OrderStatus {
    const val PURCHASED = "구매확정"
    const val CONFIRMED = "입금확인"
    const val DELIVERED = "배송확인"
    const val COMPLETED = "판매완료"
    const val CANCELED = "주문취소"

    fun isValid(value: String): Boolean =
        value in listOf(PURCHASED, CONFIRMED, DELIVERED, COMPLETED, CANCELED)

    fun next(value: String): String? = when (value) { PURCHASED -> CONFIRMED; CONFIRMED -> DELIVERED; DELIVERED -> COMPLETED; else -> null }
    fun canCancel(value: String): Boolean = value == PURCHASED || value == CONFIRMED
}

// 1. 농원 정보 테이블
@Entity(tableName = "farm_info")
data class FarmEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "farm_id") val farmId: Int? = null,

    @ColumnInfo(name = "farm_name") val farmName: String,
    @ColumnInfo(name = "address") val address: String? = null,
    @ColumnInfo(name = "phone") val phone: String? = null,
    @ColumnInfo(name = "memo") val memo: String? = null
)

// 2. 묘목 정보 테이블
@Entity(tableName = "species")
data class SpeciesEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "species_id") val speciesId: Int? = null,

    @ColumnInfo(name = "species_name") val speciesName: String,
    @ColumnInfo(name = "name_farm") val nameFarm: String? = null,
    @ColumnInfo(name = "chosung") val chosung: String? = null,
    @ColumnInfo(name = "category") val category: String? = null,
    @ColumnInfo(name = "memo") val memo: String? = null,

    // 🚨 MFC DB와 100% 일치
    @ColumnInfo(name = "is_active", defaultValue = "1") val isActive: Int? = 1
)

// 3. 재고 관리 테이블 (크기, 타입 추가 및 복합키 반영)
@Entity(
    tableName = "farm_inventory",
    primaryKeys = ["farm_id", "species_id", "quality", "age", "size", "plant_type"]
)
data class FarmInventoryEntity(
    @ColumnInfo(name = "farm_id") val farmId: Int,
    @ColumnInfo(name = "species_id") val speciesId: Int,
    @ColumnInfo(name = "quality") val quality: String,
    @ColumnInfo(name = "age") val age: String,

    @ColumnInfo(name = "size") val size: String,
    @ColumnInfo(name = "plant_type") val plantType: String,

    @ColumnInfo(name = "quantity") val quantity: Int,
    @ColumnInfo(name = "wholesale_price") val wholesalePrice: Int,
    @ColumnInfo(name = "retail_price") val retailPrice: Int
)

// 4. UI 전시용 가공 객체 (관리 모드용)
data class InventoryDisplayRow(
    val speciesId: Int,
    val speciesName: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val quantity: Int,
    val wholesalePrice: Int,
    val retailPrice: Int
)

// 5. 매매 모드 전용 가공 객체
data class SellingDisplayRow(
    val farmId: Int,
    val speciesId: Int,
    val farmName: String,
    val speciesName: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val quantity: Int,
    val wholesalePrice: Int,
    val retailPrice: Int,
    val chosung: String?
)

// 6. 견적서 저장 테이블
@Entity(tableName = "quote_list")
data class QuoteEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "quote_id") val quoteId: Int? = null,

    // 🚨 명확하게 컬럼명 지정
    @ColumnInfo(name = "is_prepaid") val isPrepaid: Boolean = false,
    @ColumnInfo(name = "shipping_fee") val shippingFee: Int = 0,
    @ColumnInfo(name = "quote_number") val quoteNumber: String,
    @ColumnInfo(name = "save_date") val saveDate: String,
    @ColumnInfo(name = "customer_phone") val customerPhone: String,
    @ColumnInfo(name = "total_qty") val totalQty: Int,
    @ColumnInfo(name = "total_price") val totalPrice: Int,
    @ColumnInfo(name = "item_summary") val itemSummary: String,
    @ColumnInfo(name = "sent_at", defaultValue = "0") val sentAt: Long = 0
)

// 7. 🚨 주문서 저장 테이블 (배송정보 및 상태 필드 추가!)
@Entity(tableName = "order_list")
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "order_id") val orderId: Int? = null,

    // 🚨 명확하게 컬럼명 지정
    @ColumnInfo(name = "is_prepaid") val isPrepaid: Boolean = false,
    @ColumnInfo(name = "shipping_fee") val shippingFee: Int = 0,

    @ColumnInfo(name = "order_number") val orderNumber: String,

    // 🚨 [추가된 부분] 로컬에서도 삭제 대신 상태로 관리!
    @ColumnInfo(name = "status", defaultValue = "'입금확인'") val status: String = "입금확인",

    @ColumnInfo(name = "order_date") val orderDate: String,
    @ColumnInfo(name = "customer_phone") val customerPhone: String,
    @ColumnInfo(name = "total_qty") val totalQty: Int,
    @ColumnInfo(name = "total_price") val totalPrice: Int,
    @ColumnInfo(name = "item_summary") val itemSummary: String,

    @ColumnInfo(name = "receiver_name", defaultValue = "''") val receiverName: String = "",
    @ColumnInfo(name = "zip_no", defaultValue = "''") val zipNo: String = "",
    @ColumnInfo(name = "road_address", defaultValue = "''") val roadAddress: String = "",
    @ColumnInfo(name = "detail_address", defaultValue = "''") val detailAddress: String = "",
    @ColumnInfo(name = "note", defaultValue = "''") val note: String = "",
    @ColumnInfo(name = "customer_name", defaultValue = "''") val customerName: String = "",
    @ColumnInfo(name = "customer_email", defaultValue = "''") val customerEmail: String = "",
    @ColumnInfo(name = "source_request_id", defaultValue = "''") val sourceRequestId: String = "",
    @ColumnInfo(name = "source_quote_number", defaultValue = "''") val sourceQuoteNumber: String = "",
    @ColumnInfo(name = "revision", defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(name = "purchased_at", defaultValue = "0") val purchasedAt: Long = 0,
    @ColumnInfo(name = "paid_at", defaultValue = "0") val paidAt: Long = 0,
    @ColumnInfo(name = "delivered_at", defaultValue = "0") val deliveredAt: Long = 0,
    @ColumnInfo(name = "completed_at", defaultValue = "0") val completedAt: Long = 0,
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(name = "cost_total", defaultValue = "0") val costTotal: Long = 0,
    @ColumnInfo(name = "expense_total", defaultValue = "0") val expenseTotal: Long = 0,
    @ColumnInfo(name = "cost_confirmed", defaultValue = "0") val costConfirmed: Boolean = false,
    @ColumnInfo(name = "profit_mode", defaultValue = "'resale'") val profitMode: String = "resale",
    @ColumnInfo(name = "commission_bps", defaultValue = "3000") val commissionBps: Int = 3000,
    @ColumnInfo(name = "tax_amount", defaultValue = "0") val taxAmount: Long = 0
)

// --- 🌐 구글 시트 연동용 DTO (Data Transfer Object) ---
// 구글 Apps Script에서 쏴주는 JSON Key 이름과 100% 똑같이 맞춰야 해!

data class SheetInventoryDto(
    val farm_name: String,
    val species_name: String,
    val quality: String,
    val age: String,
    val size: String,
    val plant_type: String,
    val quantity: Int,
    val wholesale_price: Int,
    val retail_price: Int
)

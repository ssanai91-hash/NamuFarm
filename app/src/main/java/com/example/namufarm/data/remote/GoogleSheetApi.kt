package com.example.namufarm.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import com.google.gson.annotations.SerializedName

interface GoogleSheetApi {

    // ===== Pull =====
    @GET("exec")
    suspend fun getFarmData(
        @Query("action") action: String = "get_farms"
    ): List<SheetFarmDto>

    @GET("exec")
    suspend fun getSpeciesData(
        @Query("action") action: String = "get_species"
    ): List<SheetSpeciesDto>

    @GET("exec")
    suspend fun getInventoryData(
        @Query("action") action: String = "get_inventory"
    ): List<SheetInventoryDto>

    @GET("exec")
    suspend fun getOrdersData(
        @Query("action") action: String = "get_orders"
    ): List<SheetOrderRemoteDto>

    // ===== Push =====
    @POST("exec")
    suspend fun uploadFarm(
        @Body request: FarmUploadRequest
    ): SyncResponse

    @POST("exec")
    suspend fun deleteFarm(
        @Body request: FarmDeleteRequest
    ): SyncResponse

    @POST("exec")
    suspend fun uploadSpecies(
        @Body request: SpeciesUploadRequest
    ): SyncResponse

    @POST("exec")
    suspend fun deleteSpecies(
        @Body request: SpeciesDeleteRequest
    ): SyncResponse

    @POST("exec")
    suspend fun uploadInventory(
        @Body request: SheetUploadRequest
    ): SyncResponse

    @POST("exec")
    suspend fun uploadOrder(
        @Body request: OrderUploadRequest
    ): SyncResponse

    @POST("exec")
    suspend fun cancelOrder(
        @Body request: CancelOrderRequest
    ): SyncResponse

    @POST("exec")
    suspend fun updateOrderDelivery(
        @Body request: UpdateOrderDeliveryRequest
    ): SyncResponse

}

data class SyncResponse(
    val result: String
)

// ===== 농원 =====
data class SheetFarmDto(
    val farm_id: Int,
    val farm_name: String,
    val address: String,
    val phone: String,
    val memo: String
)

data class FarmUploadRequest(
    val action: String = "save_farm",
    val data: SheetFarmDto
)

data class FarmDeleteRequest(
    val action: String = "delete_farm",
    val farm_id: Int
)

// ===== 묘목 =====
data class SheetSpeciesDto(
    val species_id: Int,
    val species_name: String,
    val name_farm: String,
    val chosung: String,
    val category: String,
    val memo: String,
    val is_active: Int
)

data class SpeciesUploadRequest(
    val action: String = "save_species",
    val data: SheetSpeciesDto
)

data class SpeciesDeleteRequest(
    val action: String = "delete_species",
    val species_id: Int
)

// ===== 재고 =====
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

data class SheetUploadRequest(
    val action: String,
    val data: SheetInventoryDto
)

// ===== 주문 =====
data class SheetOrderDto(
    val order_number: String,
    val date: String,
    val customer_phone: String,
    val total_qty: Int,
    val total_price: Int,

    // 사람이 읽는 기존 필드와 앱 복원용 원본 필드를 같이 보낸다.
    val summary: String,
    val raw_summary: String,
    val status: String,

    val is_prepaid: Boolean,
    val shipping_fee: Int,

    val receiver_name: String,
    val zip_no: String,
    val road_address: String,
    val detail_address: String,

    // 기존 Apps Script 호환용 합쳐진 주소
    val address: String,
    val note: String
)

data class SheetOrderItemDto(
    val farm_name: String,
    val species_name: String,
    val quality: String,
    val age: String,
    val size: String,
    val plant_type: String,
    val order_qty: Int,
    val retail_price: Int
)

data class OrderUploadRequest(
    val action: String = "order",
    val order_data: SheetOrderDto,
    val items: List<SheetOrderItemDto>
)

data class CancelOrderRequest(
    val action: String = "cancel_order",
    val order_number: String
)

data class UpdateOrderDeliveryRequest(
    val action: String = "update_delivery",
    val order_number: String,
    val receiver_name: String,
    val zip_no: String,
    val road_address: String,
    val detail_address: String,
    val address: String,
    val note: String
)

data class SheetOrderRemoteDto(
    val order_number: String?,
    val date: String?,
    val customer_phone: String?,
    val total_qty: Int?,
    val total_price: Int?,
    val summary: String?,
    val receiver_name: String?,
    val address: String?,
    val note: String?,
    val status: String?,

    // 새 Apps Script가 지원하면 완전 왕복에 사용한다.
    // 기존 응답에는 필드가 없어도 모두 nullable이라 호환된다.
    @SerializedName(value = "raw_summary", alternate = ["item_summary"])
    val raw_summary: String? = null,
    val is_prepaid: Boolean? = null,
    val shipping_fee: Int? = null,
    val zip_no: String? = null,
    val road_address: String? = null,
    val detail_address: String? = null
)

package com.example.namufarm.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

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
    val summary: String,
    val receiver_name: String,
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
    val status: String?
)


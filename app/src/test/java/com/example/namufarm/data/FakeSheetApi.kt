package com.example.namufarm.data

import com.example.namufarm.data.remote.*
import com.example.namufarm.data.remote.SheetInventoryDto
import java.io.IOException
import java.util.Collections

internal class FakeSheetApi : GoogleSheetApi {
    var online = false
    var failAction: String? = null
    var failOrderPull = false
    var loseOrderResponse = false
    var beforeInventoryPull: (suspend () -> Unit)? = null
    var beforeOrderPost: (suspend () -> Unit)? = null
    val events = Collections.synchronizedList(mutableListOf<String>())
    val orderUploads = mutableListOf<OrderUploadRequest>()
    var inventory = listOf<SheetInventoryDto>()
    var orders = listOf<SheetOrderRemoteDto>()
    var farms = listOf(SheetFarmDto(1, "농원", "", "", ""))
    var species = listOf(SheetSpeciesDto(1, "소나무", "", "ㅅ", "", "", 1))

    private fun read(action: String) {
        events += action
        if (!online) throw IOException("offline")
    }
    private fun write(action: String): SyncResponse {
        read(action)
        return SyncResponse(if (failAction == action) "error" else "success")
    }
    override suspend fun getFarmData(action: String): List<SheetFarmDto> { read(action); return farms }
    override suspend fun getSpeciesData(action: String): List<SheetSpeciesDto> { read(action); return species }
    override suspend fun getInventoryData(action: String): List<SheetInventoryDto> {
        read(action)
        val captured = inventory
        beforeInventoryPull?.invoke()
        return captured
    }
    override suspend fun getOrdersData(action: String): List<SheetOrderRemoteDto> {
        read(action)
        if (failOrderPull) throw IOException("order pull failed")
        return orders
    }
    override suspend fun uploadFarm(request: FarmUploadRequest) = write(request.action)
    override suspend fun deleteFarm(request: FarmDeleteRequest) = write(request.action)
    override suspend fun uploadSpecies(request: SpeciesUploadRequest) = write(request.action)
    override suspend fun deleteSpecies(request: SpeciesDeleteRequest) = write(request.action)
    override suspend fun uploadInventory(request: SheetUploadRequest): SyncResponse {
        val response = write(request.action)
        if (response.result == "success") inventory = if (request.action == "delete") emptyList() else listOf(request.data)
        return response
    }
    override suspend fun uploadOrder(request: OrderUploadRequest): SyncResponse {
        val response = write(request.action)
        beforeOrderPost?.invoke()
        if (response.result == "success") {
            orderUploads += request
            val o = request.order_data
            orders = orders + SheetOrderRemoteDto(
                o.order_number, o.date, o.customer_phone, o.total_qty, o.total_price,
                o.summary, o.receiver_name, o.address, o.note, o.status,
                o.raw_summary, o.is_prepaid, o.shipping_fee, o.zip_no, o.road_address, o.detail_address
            )
            inventory = inventory.map { row ->
                row.copy(quantity = checkNotNull(row.quantity) - request.items.filter {
                    it.farm_name == row.farm_name && it.species_name == row.species_name &&
                        it.quality == row.quality && it.age == row.age && it.size == row.size && it.plant_type == row.plant_type
                }.sumOf { it.order_qty })
            }
            if (loseOrderResponse) { loseOrderResponse = false; throw IOException("response lost after commit") }
        }
        return response
    }
    override suspend fun cancelOrder(request: CancelOrderRequest): SyncResponse {
        val response = write(request.action)
        if (response.result == "success") orders = orders.map {
            if (it.order_number == request.order_number) it.copy(status = OrderStatus.CANCELED) else it
        }
        return response
    }
    override suspend fun updateOrderDelivery(request: UpdateOrderDeliveryRequest) = write(request.action)
}

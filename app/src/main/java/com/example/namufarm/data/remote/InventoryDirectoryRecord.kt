package com.example.namufarm.data.remote

import com.example.namufarm.data.FarmInventoryEntity

/** Operations workbook stock row, keyed by farm, species, quality, age, size and type. */
data class InventoryDirectoryRecord(
    val farmId: Int = 0, val speciesId: Int = 0,
    val quality: String = "", val age: String = "", val size: String = "", val plantType: String = "",
    val quantity: Int = 0, val wholesalePrice: Int = 0, val retailPrice: Int = 0,
    val updatedAt: String = ""
) {
    fun checked(): InventoryDirectoryRecord {
        check(farmId > 0 && speciesId > 0 &&
            listOf(quality, age, size, plantType).all { it.isNotBlank() && it.length <= 100 } &&
            quantity >= 0 && wholesalePrice >= 0 && retailPrice >= 0) {
            "농원별 묘목 재고 형식을 확인해주세요."
        }
        return this
    }
    fun asEntity() = FarmInventoryEntity(farmId, speciesId, quality, age, size, plantType,
        quantity, wholesalePrice, retailPrice)
}

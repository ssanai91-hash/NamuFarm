package com.example.namufarm.data.remote

import com.example.namufarm.data.FarmEntity

/** Operations-sheet contact entry; independent of legacy inventory master IDs in Room. */
data class FarmDirectoryRecord(
    val farmId: Int = 0, val farmName: String = "", val address: String = "",
    val phone: String = "", val memo: String = "", val updatedAt: String = "", val version: String = ""
) {
    fun checked(): FarmDirectoryRecord {
        check(farmId > 0 && farmName.isNotBlank() && farmName.length <= 100 &&
            address.length <= 300 && phone.length <= 40 && memo.length <= 1000 &&
            Regex("[a-f0-9]{64}").matches(version)) { "농원 정보 형식을 확인해주세요." }
        return this
    }
    fun asFarm() = FarmEntity(farmId, farmName, address, phone, memo)
}

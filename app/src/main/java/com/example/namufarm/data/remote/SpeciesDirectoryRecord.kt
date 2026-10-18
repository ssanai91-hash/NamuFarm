package com.example.namufarm.data.remote

import com.example.namufarm.data.SpeciesEntity

/** Server-managed species master; entries are cached in Room for offline reads. */
data class SpeciesDirectoryRecord(
    val speciesId: Int = 0, val speciesName: String = "",
    val farmName: String = "", val chosung: String = "", val category: String = "",
    val memo: String = "", val isActive: Int = 1,
    val updatedAt: String = "", val version: String = ""
) {
    fun checked(): SpeciesDirectoryRecord {
        check(speciesId > 0 && speciesName.isNotBlank() && speciesName.length <= 100 &&
            farmName.length <= 100 && chosung.length <= 20 && category.length <= 100 &&
            memo.length <= 1000 && isActive in 0..1 &&
            Regex("[a-f0-9]{64}").matches(version)) {
            "묘목 자료 형식에 오류가 있습니다."
        }
        return this
    }
    fun asSpecies() = SpeciesEntity(speciesId, speciesName, farmName, chosung, category, memo, isActive)
}

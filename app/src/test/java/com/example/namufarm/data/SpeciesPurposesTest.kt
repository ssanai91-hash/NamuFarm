package com.example.namufarm.data

import org.junit.Assert.*
import org.junit.Test

class SpeciesPurposesTest {
    @Test fun separatorsAndOptionalTreeSuffixMatchTheWebsiteNames() {
        assertEquals(listOf("울타리", "정원"), suggestSpeciesPurposeLabels(" 에메랄드 그린 "))
        assertEquals(suggestSpeciesPurposeLabels("에메랄드그린"), suggestSpeciesPurposeLabels("에메랄드_그린"))
        assertEquals(suggestSpeciesPurposeLabels("홍가시"), suggestSpeciesPurposeLabels("홍가시나무"))
        assertEquals(suggestSpeciesPurposeLabels("석류나무"), suggestSpeciesPurposeLabels("석류"))
        assertEquals(emptyList<String>(), suggestSpeciesPurposeLabels("등록되지 않은 묘목"))
    }

    @Test fun knownLabelsHaveOneFixedOrderAndLegacyTokensAreRetained() {
        val parts = parseSpeciesCategory(" 정원, 생울타리 , 울타리, 유실수, 정원, 생울타리 ")
        assertEquals(listOf("울타리", "정원"), parts.selectedLabels)
        assertEquals(listOf("생울타리", "유실수", "생울타리"), parts.legacyValues)
        assertEquals("울타리, 정원, 생울타리, 유실수, 생울타리",
            formatSpeciesCategory(parts.selectedLabels.reversed(), parts.legacyValues))
    }

    @Test fun removingOneLegacyChipDoesNotDropTheOtherValues() {
        val parts = parseSpeciesCategory("생울타리, 유실수")
        assertEquals("꽃, 유실수", formatSpeciesCategory(listOf("꽃"), parts.legacyValues.filterIndexed { i, _ -> i != 0 }))
        assertEquals("", formatSpeciesCategory(emptyList(), emptyList()))
    }

    @Test fun categoryLimitUsesTheServerUtf16LengthAndForbidsNewlines() {
        assertNull(speciesCategoryError("가".repeat(100)))
        assertEquals("분류가 너무 깁니다. 기존 값을 정리해주세요.", speciesCategoryError("가".repeat(101)))
        assertNotNull(speciesCategoryError(String(Character.toChars(0x20000)).repeat(51)))
        assertNotNull(speciesCategoryError("유실수\n기타"))
        assertNotNull(speciesCategoryError("유실수\r기타"))
    }

    @Test fun filtersOnlyUseSavedLabelsAndDoNotInferLegacyValues() {
        assertTrue(speciesMatchesPurpose("정원, 울타리, 생울타리", "울타리"))
        assertFalse(speciesMatchesPurpose("생울타리", "울타리"))
        assertFalse(speciesMatchesPurpose("", "정원"))
        assertTrue(speciesMatchesPurpose("생울타리", ""))
    }
}

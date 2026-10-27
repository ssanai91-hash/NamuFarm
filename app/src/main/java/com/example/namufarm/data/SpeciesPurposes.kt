// website/lib/species-purposes.ts 와 동일하게 유지
package com.example.namufarm.data

data class SpeciesPurpose(val id: String, val label: String, val names: String)

val SPECIES_PURPOSES = listOf(
    SpeciesPurpose("fruit", "열매", "감 개암 구기자 다래 대추 마키베리 매실 모과 무화과 밤 배 보리수 복분자 복숭아 블랙초코베리 블랙커런트 블루베리 비파 뽕 사과 살구 석류나무 슈퍼블랙베리 앵두나무 오미자 으름 일세다래 자두 체리 키위 포도 포포나무 플루오트 피칸 호두"),
    SpeciesPurpose("hedge", "울타리", "개나리 남천 눈주목 무늬호랑가시 미측백나무 사철나무 에메랄드_그린 은테_사철 조팝나무 주목 쥐똥나무 직립꽝꽝이 철쭉 측백나무 탱자 피라칸타스 해당화 홍가시 화살나무 황금꽝꽝이 황금주목 황금측백 회양목"),
    SpeciesPurpose("flower", "꽃", "개나리 꽃잔디 능소화 라일락 매실 명자나무 목련 무궁화 미니백일홍 미스김라일락 박태기 백일홍 벚나무 별목련 복숭아 불두화 산수국 산수유 수국 수사해당화 아나벨수국 앵두나무 이팝나무 자귀나무 조팝나무 철쭉 치자나무 칠자화 팜콘수국 피라미드_수국 해당화 홍도화 히아신스"),
    SpeciesPurpose("shade", "그늘", "계수나무 느티나무 단풍나무 메타세쿼이아 목백합 벚나무 상수리나무 소나무 은행나무 자귀나무 자작나무 층층나무 팽나무 회화나무"),
    SpeciesPurpose("garden", "정원", "구상나무 금송 남천 단풍나무 라일락 목련 무궁화 반송 산딸나무 소사나무 수국 아나벨수국 에메랄드_그린 옥향 은청가문비 자작나무 주목 철쭉 코니카가문비 편백나무 홍가시 화살나무 회양목"),
    SpeciesPurpose("lawn", "잔디", "잔디 양잔디 꽃잔디"),
)

/** Match only the requested separators and one optional trailing '나무'. */
fun normalizeSpeciesPurposeName(name: String): String =
    name.filterNot { it.isWhitespace() || it == '_' }.removeSuffix("나무")

private val purposeNameSets = SPECIES_PURPOSES.associate { purpose ->
    purpose.id to purpose.names.split(' ').map(::normalizeSpeciesPurposeName).toSet()
}

/** Suggestions are editor defaults only. This function never writes category data. */
fun suggestSpeciesPurposeLabels(name: String): List<String> {
    val normalized = normalizeSpeciesPurposeName(name)
    return SPECIES_PURPOSES.filter { normalized in purposeNameSets.getValue(it.id) }.map { it.label }
}

data class SpeciesCategoryParts(val selectedLabels: List<String>, val legacyValues: List<String>)

fun parseSpeciesCategory(category: String?): SpeciesCategoryParts {
    val values = category.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val labels = SPECIES_PURPOSES.map { it.label }
    return SpeciesCategoryParts(labels.filter { it in values }, values.filter { it !in labels })
}

/** Keep unknown tokens, including duplicates, until the user explicitly removes them. */
fun formatSpeciesCategory(selectedLabels: Collection<String>, legacyValues: List<String>): String =
    (SPECIES_PURPOSES.map { it.label }.filter { it in selectedLabels } + legacyValues).joinToString(", ")

fun speciesCategoryError(category: String): String? = when {
    category.length > 100 -> "분류가 너무 깁니다. 기존 값을 정리해주세요."
    '\n' in category || '\r' in category -> "분류에는 줄바꿈을 넣을 수 없어요."
    else -> null
}

/** Filter by saved labels only; suggestions do not classify unsaved rows. */
fun speciesMatchesPurpose(category: String?, label: String): Boolean =
    label.isEmpty() || label in parseSpeciesCategory(category).selectedLabels

package com.example.namufarm.domain.order

data class OrderLine(
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

/**
 * DB/Sheet에 저장되는 주문 품목 summary의 단일 파서.
 *
 * 현재 포맷:
 * species|quality|age|size|plantType|qty|retail|farm|wholesale
 *
 * 초기 7필드 포맷도 읽을 수 있게 유지한다.
 */
object OrderSummaryCodec {

    fun parseStrict(summary: String): List<OrderLine> {
        val rawLines = summary.lines().filter { it.isNotBlank() }
        require(rawLines.isNotEmpty()) { "주문 품목 정보가 비어 있습니다." }

        return rawLines.mapIndexed { index, raw ->
            val parts = raw.split("|")

            when {
                parts.size >= 9 -> OrderLine(
                    speciesName = parts[0].trim(),
                    quality = parts[1].trim(),
                    age = parts[2].trim(),
                    size = parts[3].trim(),
                    plantType = parts[4].trim(),
                    orderQty = parts[5].toIntOrNull()
                        ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                    retailPrice = parts[6].toIntOrNull() ?: 0,
                    farmName = parts[7].trim(),
                    wholesalePrice = parts[8].toIntOrNull() ?: 0
                )

                parts.size >= 7 -> OrderLine(
                    speciesName = parts[0].trim(),
                    quality = parts[1].trim(),
                    age = parts[2].trim(),
                    size = "0.0m",
                    plantType = "실생",
                    orderQty = parts[3].toIntOrNull()
                        ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                    retailPrice = parts[4].toIntOrNull() ?: 0,
                    farmName = parts[5].trim(),
                    wholesalePrice = parts[6].toIntOrNull() ?: 0
                )

                else -> error(
                    (index + 1).toString() + "번째 주문 품목 형식을 읽을 수 없습니다."
                )
            }
        }
    }
}

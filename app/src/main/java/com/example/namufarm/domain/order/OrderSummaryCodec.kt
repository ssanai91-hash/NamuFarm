package com.example.namufarm.domain.order

enum class OrderLineSource {
    INVENTORY,
    MANUAL
}

data class OrderLine(
    val speciesName: String,
    val quality: String,
    val age: String,
    val size: String,
    val plantType: String,
    val orderQty: Int,
    val retailPrice: Int,
    val farmName: String,
    val wholesalePrice: Int,
    val source: OrderLineSource = OrderLineSource.INVENTORY
) {
    val isInventoryBacked: Boolean
        get() = source == OrderLineSource.INVENTORY
}

/**
 * DB/Sheet에 저장되는 주문 품목 summary의 단일 코덱.
 *
 * 현재 저장 포맷(서버 호환 유지):
 * species|quality|age|size|plantType|qty|retail|farm|wholesale
 *
 * 빠른 견적 품목은 farm 값에 예약 라벨인 "수기견적"을 사용해 구분한다.
 * 파서는 향후 확장을 위해 선택적인 10번째 source 필드도 읽을 수 있다.
 *
 * 기존 9필드/7필드 데이터도 계속 읽을 수 있다.
 */
object OrderSummaryCodec {

    const val MANUAL_FARM_LABEL = "수기견적"

    fun encode(lines: List<OrderLine>): String =
        lines.joinToString(separator = "\n") { line ->
            listOf(
                line.speciesName,
                line.quality,
                line.age,
                line.size,
                line.plantType,
                line.orderQty.toString(),
                line.retailPrice.toString(),
                if (line.source == OrderLineSource.MANUAL) {
                    MANUAL_FARM_LABEL
                } else {
                    line.farmName
                },
                line.wholesalePrice.toString()
            ).joinToString("|")
        }

    fun parseStrict(summary: String): List<OrderLine> {
        val rawLines = summary.lines().filter { it.isNotBlank() }
        require(rawLines.isNotEmpty()) { "주문 품목 정보가 비어 있습니다." }

        return rawLines.mapIndexed { index, raw ->
            val parts = raw.split("|")

            when {
                parts.size >= 9 -> {
                    val farmName = parts[7].trim()
                    val source = when {
                        parts.size >= 10 ->
                            runCatching {
                                OrderLineSource.valueOf(parts[9].trim().uppercase())
                            }.getOrDefault(
                                if (farmName == MANUAL_FARM_LABEL) {
                                    OrderLineSource.MANUAL
                                } else {
                                    OrderLineSource.INVENTORY
                                }
                            )

                        farmName == MANUAL_FARM_LABEL -> OrderLineSource.MANUAL
                        else -> OrderLineSource.INVENTORY
                    }

                    OrderLine(
                        speciesName = parts[0].trim(),
                        quality = parts[1].trim(),
                        age = parts[2].trim(),
                        size = parts[3].trim(),
                        plantType = parts[4].trim(),
                        orderQty = parts[5].toIntOrNull()
                            ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                        retailPrice = parts[6].toIntOrNull() ?: 0,
                        farmName = farmName,
                        wholesalePrice = parts[8].toIntOrNull() ?: 0,
                        source = source
                    )
                }

                parts.size >= 7 -> {
                    val farmName = parts[5].trim()

                    OrderLine(
                        speciesName = parts[0].trim(),
                        quality = parts[1].trim(),
                        age = parts[2].trim(),
                        size = "0.0m",
                        plantType = "실생",
                        orderQty = parts[3].toIntOrNull()
                            ?: error((index + 1).toString() + "번째 품목 수량 오류"),
                        retailPrice = parts[4].toIntOrNull() ?: 0,
                        farmName = farmName,
                        wholesalePrice = parts[6].toIntOrNull() ?: 0,
                        source = if (farmName == MANUAL_FARM_LABEL) {
                            OrderLineSource.MANUAL
                        } else {
                            OrderLineSource.INVENTORY
                        }
                    )
                }

                else -> error(
                    (index + 1).toString() + "번째 주문 품목 형식을 읽을 수 없습니다."
                )
            }
        }
    }
}

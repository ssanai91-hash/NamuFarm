package com.example.namufarm.domain.order

enum class OrderLineSource { INVENTORY, MANUAL }

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
    val isInventoryBacked: Boolean get() = source == OrderLineSource.INVENTORY
}

/** Keeps the nine-field wire format, and reads legacy seven-field / explicit-source ten-field data. */
object OrderSummaryCodec {
    const val MANUAL_FARM_LABEL = "수기견적"

    fun validateText(value: String) {
        require(value.none { it == '|' || it == '\n' || it == '\r' }) {
            "품목 정보에 | 또는 줄바꿈을 사용할 수 없습니다."
        }
    }

    private fun validate(line: OrderLine) {
        listOf(line.speciesName, line.quality, line.age, line.size, line.plantType, line.farmName)
            .forEach(::validateText)
        require(line.speciesName.isNotBlank()) { "묘목명이 비어 있습니다." }
        require(line.orderQty > 0) { "주문 수량은 1 이상이어야 합니다." }
        require(line.retailPrice >= 0 && line.wholesalePrice >= 0) { "단가는 음수일 수 없습니다." }
        if (line.isInventoryBacked) {
            require(line.farmName.isNotBlank() && line.farmName.trim() != MANUAL_FARM_LABEL) {
                "실제 재고의 농원명이 없거나 수기견적 예약 이름입니다."
            }
        }
    }

    fun encode(lines: List<OrderLine>): String {
        require(lines.isNotEmpty()) { "주문 품목 정보가 비어 있습니다." }
        return lines.joinToString("\n") { line ->
            validate(line)
            listOf(
                line.speciesName.trim(), line.quality.trim(), line.age.trim(),
                line.size.trim(), line.plantType.trim(), line.orderQty.toString(),
                line.retailPrice.toString(),
                if (line.isInventoryBacked) line.farmName.trim() else MANUAL_FARM_LABEL,
                line.wholesalePrice.toString()
            ).joinToString("|")
        }
    }

    fun parseStrict(summary: String): List<OrderLine> {
        val rawLines = summary.lines().filter { it.isNotBlank() }
        require(rawLines.isNotEmpty()) { "주문 품목 정보가 비어 있습니다." }
        return rawLines.mapIndexed { index, raw ->
            val parts = raw.split('|').map { it.trim() }
            check(parts.size == 7 || parts.size == 9 || parts.size == 10) {
                "${index + 1}번째 주문 품목 형식을 읽을 수 없습니다."
            }
            fun number(position: Int): Int = parts[position].toIntOrNull()
                ?: error("${index + 1}번째 품목 수량/단가 오류")
            val legacy = parts.size == 7
            val farm = parts[if (legacy) 5 else 7]
            val source = if (parts.size == 10) {
                OrderLineSource.entries.firstOrNull { it.name == parts[9].uppercase() }
                    ?: error("${index + 1}번째 품목 출처 오류")
            } else if (farm == MANUAL_FARM_LABEL) OrderLineSource.MANUAL else OrderLineSource.INVENTORY

            OrderLine(
                speciesName = parts[0], quality = parts[1], age = parts[2],
                size = if (legacy) "0.0m" else parts[3],
                plantType = if (legacy) "실생" else parts[4],
                orderQty = number(if (legacy) 3 else 5),
                retailPrice = number(if (legacy) 4 else 6),
                farmName = farm, wholesalePrice = number(if (legacy) 6 else 8), source = source
            ).also(::validate)
        }
    }
}

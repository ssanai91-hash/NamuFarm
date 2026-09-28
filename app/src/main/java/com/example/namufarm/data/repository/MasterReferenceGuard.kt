package com.example.namufarm.data.repository

import com.example.namufarm.data.local.LocalDataSource
import com.example.namufarm.domain.order.OrderSummaryCodec

/** The legacy wire format identifies stock by names. Protect those references until IDs migrate. */
internal object MasterReferenceGuard {
    fun farm(local: LocalDataSource, id: Int, deleting: Boolean = false) {
        val farm = local.getAllFarms().singleOrNull { it.farmId == id } ?: return
        if (!deleting) check(local.getAllInventoryEntities().none { it.farmId == id }) {
            "재고와 연결된 농원명은 변경할 수 없습니다. 주소·연락처는 수정할 수 있습니다."
        }
        check(local.getOpenCommerceSummaries().none { summary ->
            OrderSummaryCodec.parseStrict(summary).any { it.isInventoryBacked && it.farmName == farm.farmName }
        }) { "견적/취소 가능한 주문이 연결된 농원은 이름 변경·삭제할 수 없습니다." }
    }

    fun species(local: LocalDataSource, id: Int) {
        val species = local.getAllSpeciesIncludingInactive().singleOrNull { it.speciesId == id } ?: return
        check(local.getAllInventoryEntities().none { it.speciesId == id }) {
            "재고와 연결된 묘목명은 변경할 수 없습니다."
        }
        check(local.getOpenCommerceSummaries().none { summary ->
            OrderSummaryCodec.parseStrict(summary).any { it.isInventoryBacked && it.speciesName == species.speciesName }
        }) { "견적/취소 가능한 주문이 연결된 묘목명은 변경할 수 없습니다." }
    }
}

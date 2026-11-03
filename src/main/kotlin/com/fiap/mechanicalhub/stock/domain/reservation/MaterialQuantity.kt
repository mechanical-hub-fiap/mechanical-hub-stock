package com.fiap.mechanicalhub.stock.domain.reservation

import java.util.UUID

data class MaterialQuantity(val materialId: UUID, val quantity: Int) {
    init {
        require(quantity > 0) { "A quantidade deve ser maior que zero" }
    }
}

/**
 * Soma as quantidades por material (necessidade da ordem a partir dos reparos, ou
 * reservedMaterials a partir das movimentacoes RESERVED), em ordem crescente de materialId.
 */
fun List<MaterialQuantity>.sumByMaterial(): List<MaterialQuantity> =
    groupBy { it.materialId }
        .map { (materialId, items) -> MaterialQuantity(materialId, items.sumOf { it.quantity }) }
        .sortedBy { it.materialId }

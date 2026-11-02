package com.fiap.mechanicalhub.stock.domain.reservation

import java.util.UUID

/** Resultado da reserva de um material: o que foi reservado e o que faltou (vira pendencia). */
data class StockReservation(val materialId: UUID, val reserved: Int, val missing: Int)

/** Reserva min(disponivel, necessario); o faltante vira pendencia. */
fun reserveStock(requirement: MaterialQuantity, available: Int): StockReservation {
    val reserved = minOf(available, requirement.quantity)
    return StockReservation(
        materialId = requirement.materialId,
        reserved = reserved,
        missing = requirement.quantity - reserved,
    )
}

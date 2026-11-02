package com.fiap.mechanicalhub.stock.application.inventory

import java.util.UUID

/** Resultado de POST /stock/entry; tambem e o corpo armazenado para repeticao idempotente. */
data class StockEntryResult(
    val materialId: UUID,
    val quantity: Int,
    val available: Int,
    val reservedOrderIds: List<UUID>,
)

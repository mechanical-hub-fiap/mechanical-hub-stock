package com.fiap.mechanicalhub.stock.application.reservation

import java.util.UUID

/** Material faltante de uma reserva e a quantidade que ficou pendente. */
data class ShortageItem(val materialId: UUID, val materialName: String, val missingQuantity: Int)

/** Porta de notificacao dos administradores do estoque. */
fun interface StockShortageNotifier {
    fun notifyShortage(orderId: UUID, transactionId: UUID, shortages: List<ShortageItem>)
}

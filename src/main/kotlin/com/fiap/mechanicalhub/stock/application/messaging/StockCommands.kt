package com.fiap.mechanicalhub.stock.application.messaging

import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import java.util.UUID

/** Mensagem com JSON ou contrato invalido: erro permanente, vai direto a DLQ. */
class InvalidCommandException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Comando recebido de mechanical-hub-stock-commands.fifo. */
sealed interface StockCommand {
    /** Chave de deduplicacao no inbox: {transactionId}:{STEP}. */
    val idempotencyKey: String
    val transactionId: UUID
    val orderId: UUID
}

data class ReserveStockCommand(
    override val idempotencyKey: String,
    override val transactionId: UUID,
    override val orderId: UUID,
    val repairIds: List<UUID>,
) : StockCommand

data class ConsumeStockCommand(
    override val idempotencyKey: String,
    override val transactionId: UUID,
    override val orderId: UUID,
    val reservedMaterials: List<MaterialQuantity>,
) : StockCommand

data class RestoreStockCommand(
    override val idempotencyKey: String,
    override val transactionId: UUID,
    override val orderId: UUID,
    val reservedMaterials: List<MaterialQuantity>,
) : StockCommand

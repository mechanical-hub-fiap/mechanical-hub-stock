package com.fiap.mechanicalhub.stock.application.messaging

import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import java.util.UUID

/** Evento de saida do servico; o envelope (eventId, version, timestamp) e montado ao gravar no outbox. */
sealed interface StockEvent {
    val type: String
    val transactionId: UUID
    val orderId: UUID

    /** Campos especificos do evento, alem do envelope. */
    fun payload(): Map<String, Any> = emptyMap()
}

data class StockReserved(
    override val transactionId: UUID,
    override val orderId: UUID,
    val reservedMaterials: List<MaterialQuantity>,
) : StockEvent {
    override val type = "stockReserved"

    override fun payload(): Map<String, Any> = mapOf("reservedMaterials" to reservedMaterials)
}

data class StockPending(override val transactionId: UUID, override val orderId: UUID) : StockEvent {
    override val type = "stockPending"
}

data class StockConsumed(override val transactionId: UUID, override val orderId: UUID) : StockEvent {
    override val type = "stockConsumed"
}

data class StockRestored(override val transactionId: UUID, override val orderId: UUID) : StockEvent {
    override val type = "stockRestored"
}

/** Grava eventos no outbox; deve ser chamado dentro da transacao do caso de uso. */
interface OutboxPublisher {
    /** Retorna o envelope JSON gravado (util para guardar como response_payload no inbox). */
    fun publish(event: StockEvent): String

    /** Regrava no outbox uma resposta ja publicada (reentrega de comando duplicado), com novo eventId. */
    fun republish(payload: String)
}

package com.fiap.mechanicalhub.stock.application.reservation

import java.util.UUID

/** Erro transitorio de concorrencia (deadlock ou lock_timeout): REST responde 503, a fila tenta de novo. */
class TransientStockException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Porta dos locks de concorrencia. Todas as operacoes exigem transacao ativa. */
interface StockLocks {
    /** SET LOCAL lock_timeout = '5s'. Chamar no inicio da transacao do consumidor e do endpoint. */
    fun applyLockTimeout()

    /** Trava as linhas de estoque dos materiais em ordem crescente de material_id (SELECT ... FOR UPDATE). */
    fun lockMaterials(materialIds: Collection<UUID>)

    /** pg_advisory_xact_lock por ordem; liberado no fim da transacao. */
    fun lockOrder(orderId: UUID)

    /** Conta as pendencias da ordem; so chamar depois de lockOrder. */
    fun countPendingItems(orderId: UUID): Int
}

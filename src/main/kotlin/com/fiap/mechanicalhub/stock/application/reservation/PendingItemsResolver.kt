package com.fiap.mechanicalhub.stock.application.reservation

import com.fiap.mechanicalhub.stock.application.inventory.StockRepository
import com.fiap.mechanicalhub.stock.application.messaging.OutboxPublisher
import com.fiap.mechanicalhub.stock.application.messaging.StockReserved
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.PendingResolution
import com.fiap.mechanicalhub.stock.domain.reservation.resolvePendingItems
import com.fiap.mechanicalhub.stock.logging.FlowFields
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.LogLevel
import com.fiap.mechanicalhub.stock.logging.Stage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

data class PendingResolutionOutcome(val availableAfter: Int, val completedOrders: List<UUID>)

/**
 * Rotina unica de resolucao de pendencias por antiguidade, usada pela entrada de estoque e pela devolucao.
 * Exige transacao ativa e as linhas de estoque do material ja travadas.
 */
@Component
class PendingItemsResolver(
    private val stock: StockRepository,
    private val locks: StockLocks,
    private val outbox: OutboxPublisher,
) {
    fun resolve(materialId: UUID): PendingResolutionOutcome {
        val resolution =
            resolvePendingItems(
                stock.pendingItems(materialId),
                stock.quantity(materialId, StockStatusEnum.AVAILABLE),
            )
        val completedOrders = mutableListOf<UUID>()
        resolution.resolutions.forEach { item ->
            val orderId = item.item.orderId
            locks.lockOrder(orderId)
            stock.addQuantity(materialId, StockStatusEnum.AVAILABLE, -item.reserved)
            stock.addQuantity(materialId, StockStatusEnum.RESERVED, item.reserved)
            stock.recordMovement(materialId, orderId, StockMovementTypeEnum.RESERVED, item.reserved)
            if (item.resolved) {
                stock.deletePendingItem(item.item.id)
                if (locks.countPendingItems(orderId) == 0) {
                    completedOrders += orderId
                    outbox.publish(
                        StockReserved(item.item.sagaTransactionId, orderId, stock.reservedMaterials(orderId)),
                    )
                    logResolution(item, Stage.END, RESOLVED_MESSAGE)
                } else {
                    logResolution(item, null, "Pendencia do material resolvida, ordem aguarda outros materiais")
                }
            } else {
                stock.reducePendingItem(item.item.id, item.remaining)
                logResolution(item, null, "Pendencia de estoque parcialmente atendida")
            }
        }
        return PendingResolutionOutcome(resolution.availableAfter, completedOrders.distinct())
    }

    /**
     * A pendencia pertence a outra ordem (a que esperava o material), entao a linha sai com o `orderId` dela e o
     * `status` `RESERVE_STOCK`: no filtro por essa ordem aparece quando e por que a reserva foi concluida.
     */
    private fun logResolution(resolution: PendingResolution, stage: Stage?, msg: String) {
        FlowLog.write(
            log,
            LogLevel.INFO,
            FlowFields(
                orderId = resolution.item.orderId,
                status = STATUS,
                msg = msg,
                stage = stage,
                context =
                    listOf(
                        "materialId" to resolution.item.materialId,
                        "reservedQty" to resolution.reserved,
                        "remainingQty" to resolution.remaining,
                        "transactionId" to resolution.item.sagaTransactionId,
                        "trigger" to FlowLog.current()?.status,
                        "triggerOrderId" to FlowLog.current()?.orderId,
                    ),
            ),
        )
    }

    private companion object {
        const val STATUS = "RESERVE_STOCK"
        const val RESOLVED_MESSAGE = "Pendencias de estoque resolvidas, evento stockReserved gravado no outbox"
        val log = LoggerFactory.getLogger(PendingItemsResolver::class.java)
    }
}

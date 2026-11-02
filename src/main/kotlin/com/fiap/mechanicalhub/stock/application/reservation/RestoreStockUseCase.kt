package com.fiap.mechanicalhub.stock.application.reservation

import com.fiap.mechanicalhub.stock.application.inventory.StockRepository
import com.fiap.mechanicalhub.stock.application.messaging.OutboxPublisher
import com.fiap.mechanicalhub.stock.application.messaging.RestoreStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.StockRestored
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.sumByMaterial
import com.fiap.mechanicalhub.stock.logging.FlowLog
import org.springframework.stereotype.Service

/**
 * restoreStock: devolve RESERVED para AVAILABLE (RETURN) e redistribui o saldo para as pendencias por antiguidade.
 * Roda na transacao do processador.
 */
@Service
class RestoreStockUseCase(
    private val stock: StockRepository,
    private val locks: StockLocks,
    private val outbox: OutboxPublisher,
    private val resolver: PendingItemsResolver,
) {
    fun execute(command: RestoreStockCommand): String {
        val materials = command.reservedMaterials.sumByMaterial()
        locks.lockOrder(command.orderId)
        // Todas as linhas de material sao travadas antes de qualquer lock de outra ordem (resolucao de pendencias).
        locks.lockMaterials(materials.map { it.materialId })
        requireReserved(stock, materials)
        materials.forEach { material ->
            stock.addQuantity(material.materialId, StockStatusEnum.RESERVED, -material.quantity)
            stock.addQuantity(material.materialId, StockStatusEnum.AVAILABLE, material.quantity)
            stock.recordMovement(material.materialId, command.orderId, StockMovementTypeEnum.RETURN, material.quantity)
        }
        val response = outbox.publish(StockRestored(command.transactionId, command.orderId))
        val reservedForOrders = materials.flatMap { resolver.resolve(it.materialId).completedOrders }.distinct()
        FlowLog.detail(
            "materialIds" to materials.map { it.materialId },
            "quantities" to materials.map { it.quantity },
            "pendingResolvedOrderIds" to reservedForOrders,
        )
        FlowLog.endMessage("Materiais devolvidos ao estoque, evento stockRestored gravado no outbox")
        return response
    }
}

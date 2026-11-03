package com.fiap.mechanicalhub.stock.application.reservation

import com.fiap.mechanicalhub.stock.application.inventory.StockRepository
import com.fiap.mechanicalhub.stock.application.messaging.ConsumeStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.OutboxPublisher
import com.fiap.mechanicalhub.stock.application.messaging.StockConsumed
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import com.fiap.mechanicalhub.stock.domain.reservation.sumByMaterial
import com.fiap.mechanicalhub.stock.logging.FlowLog
import org.springframework.stereotype.Service

/** consumeStock: baixa do RESERVED (OUT); o AVAILABLE nao muda. Roda na transacao do processador. */
@Service
class ConsumeStockUseCase(
    private val stock: StockRepository,
    private val locks: StockLocks,
    private val outbox: OutboxPublisher,
) {
    fun execute(command: ConsumeStockCommand): String {
        val materials = command.reservedMaterials.sumByMaterial()
        locks.lockMaterials(materials.map { it.materialId })
        requireReserved(stock, materials)
        materials.forEach { material ->
            stock.addQuantity(material.materialId, StockStatusEnum.RESERVED, -material.quantity)
            stock.recordMovement(material.materialId, command.orderId, StockMovementTypeEnum.OUT, material.quantity)
        }
        FlowLog.detail("materialIds" to materials.map { it.materialId }, "quantities" to materials.map { it.quantity })
        FlowLog.endMessage("Materiais baixados do estoque reservado, evento stockConsumed gravado no outbox")
        return outbox.publish(StockConsumed(command.transactionId, command.orderId))
    }
}

/** RESERVED menor que a quantidade a baixar ou devolver e erro permanente. */
internal fun requireReserved(stock: StockRepository, materials: List<MaterialQuantity>) {
    materials.forEach { material ->
        val reserved = stock.quantity(material.materialId, StockStatusEnum.RESERVED)
        if (reserved < material.quantity) {
            throw BusinessRuleException(
                "RESERVED insuficiente para o material ${material.materialId}: $reserved < ${material.quantity}",
            )
        }
    }
}

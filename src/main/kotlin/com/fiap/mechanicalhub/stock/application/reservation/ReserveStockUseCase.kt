package com.fiap.mechanicalhub.stock.application.reservation

import com.fiap.mechanicalhub.stock.application.inventory.StockRepository
import com.fiap.mechanicalhub.stock.application.messaging.OutboxPublisher
import com.fiap.mechanicalhub.stock.application.messaging.ReserveStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.StockPending
import com.fiap.mechanicalhub.stock.application.messaging.StockReserved
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.domain.reservation.sumByMaterial
import com.fiap.mechanicalhub.stock.logging.FlowFields
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.LogLevel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/** reserveStock: reserva o disponivel e grava o faltante como pendencia (transacao do processador). */
@Service
class ReserveStockUseCase(
    private val stock: StockRepository,
    private val locks: StockLocks,
    private val outbox: OutboxPublisher,
    private val notifier: StockShortageNotifier,
) {
    private val log = LoggerFactory.getLogger(ReserveStockUseCase::class.java)

    /** Retorna o envelope da resposta publicada no outbox. */
    fun execute(command: ReserveStockCommand): String {
        val required = stock.requiredMaterials(command.repairIds).sumByMaterial()
        locks.lockMaterials(required.map { it.materialId })
        val shortages = mutableListOf<ShortageItem>()
        required.forEach { requirement ->
            val available = stock.quantity(requirement.materialId, StockStatusEnum.AVAILABLE)
            val reservation = reserveStock(requirement, available)
            FlowLog.note(
                log,
                LogLevel.DEBUG,
                "Material avaliado para a reserva",
                "materialId" to requirement.materialId,
                "requestedQty" to requirement.quantity,
                "availableQty" to available,
                "reservedQty" to reservation.reserved,
                "missingQty" to reservation.missing,
            )
            if (reservation.reserved > 0) {
                stock.addQuantity(requirement.materialId, StockStatusEnum.AVAILABLE, -reservation.reserved)
                stock.addQuantity(requirement.materialId, StockStatusEnum.RESERVED, reservation.reserved)
                stock.recordMovement(
                    requirement.materialId,
                    command.orderId,
                    StockMovementTypeEnum.RESERVED,
                    reservation.reserved,
                )
            }
            if (reservation.missing > 0) {
                shortages +=
                    ShortageItem(
                        requirement.materialId,
                        stock.materialName(requirement.materialId),
                        reservation.missing,
                    )
                stock.insertPendingItem(
                    command.orderId,
                    command.transactionId,
                    requirement.materialId,
                    reservation.missing,
                )
            }
        }
        val event =
            if (shortages.isNotEmpty()) {
                StockPending(command.transactionId, command.orderId)
            } else {
                StockReserved(command.transactionId, command.orderId, stock.reservedMaterials(command.orderId))
            }
        val response = outbox.publish(event)
        logOutcome(required.map { it.materialId }, shortages)
        if (shortages.isNotEmpty()) notifyAfterCommit(command, shortages)
        return response
    }

    /** Contexto do `END` da etapa `RESERVE_STOCK` (logging-standard §2.1). */
    private fun logOutcome(materialIds: List<UUID>, shortages: List<ShortageItem>) {
        FlowLog.detail("materialIds" to materialIds)
        if (shortages.isEmpty()) {
            FlowLog.detail("outcome" to "RESERVED")
            FlowLog.endMessage("Materiais reservados, evento stockReserved gravado no outbox")
        } else {
            FlowLog.detail(
                "outcome" to "PENDING",
                "pendingMaterialIds" to shortages.map { it.materialId },
                "missingQty" to shortages.map { it.missingQuantity },
            )
            FlowLog.endMessage("Material insuficiente, ordem com pendencia de estoque; evento stockPending gravado")
        }
    }

    /** Alerta aos administradores so depois do COMMIT; falha no envio gera apenas log. */
    @Suppress("TooGenericExceptionCaught")
    private fun notifyAfterCommit(command: ReserveStockCommand, shortages: List<ShortageItem>) {
        val send = {
            try {
                notifier.notifyShortage(command.orderId, command.transactionId, shortages)
            } catch (e: Exception) {
                val msg = "Falha ao enviar o alerta de falta de material"
                FlowLog.write(log, LogLevel.ERROR, FlowFields(command.orderId, STATUS, msg, error = e))
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = send()
                },
            )
        } else {
            send()
        }
    }

    private companion object {
        const val STATUS = "RESERVE_STOCK"
    }
}

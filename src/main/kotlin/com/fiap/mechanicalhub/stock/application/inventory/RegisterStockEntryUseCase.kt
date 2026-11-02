package com.fiap.mechanicalhub.stock.application.inventory

import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyKeyReusedException
import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyStore
import com.fiap.mechanicalhub.stock.application.idempotency.StoredEntryResponse
import com.fiap.mechanicalhub.stock.application.reservation.PendingItemsResolver
import com.fiap.mechanicalhub.stock.application.reservation.StockLocks
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.logging.FlowLog
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

data class RegisterStockEntryCommand(val idempotencyKey: String, val materialId: UUID, val quantity: Int)

data class RegisterStockEntryOutcome(val status: Int, val result: StockEntryResult)

@Service
class RegisterStockEntryUseCase(
    private val stock: StockRepository,
    private val locks: StockLocks,
    private val idempotency: IdempotencyStore,
    private val resolver: PendingItemsResolver,
) {
    @Transactional
    fun execute(command: RegisterStockEntryCommand): RegisterStockEntryOutcome {
        locks.applyLockTimeout()
        val requestHash = hashOf(command)
        val stored = idempotency.findLocking(command.idempotencyKey)
        if (stored != null) {
            if (stored.requestHash != requestHash) throw IdempotencyKeyReusedException(command.idempotencyKey)
            FlowLog.detail("outcome" to "REPLAY")
            FlowLog.endMessage("Entrada ja registrada com a mesma Idempotency-Key, resposta original devolvida")
            return RegisterStockEntryOutcome(stored.status, stored.result)
        }
        if (!stock.materialExists(command.materialId)) throw MaterialNotFoundException(command.materialId)

        val result = registerEntry(command)
        idempotency.save(command.idempotencyKey, StoredEntryResponse(requestHash, CREATED, result))
        return RegisterStockEntryOutcome(CREATED, result)
    }

    private fun registerEntry(command: RegisterStockEntryCommand): StockEntryResult {
        locks.lockMaterials(listOf(command.materialId))
        stock.addQuantity(command.materialId, StockStatusEnum.AVAILABLE, command.quantity)
        stock.recordMovement(command.materialId, null, StockMovementTypeEnum.ENTRY, command.quantity)

        val resolution = resolver.resolve(command.materialId)
        return StockEntryResult(
            materialId = command.materialId,
            quantity = command.quantity,
            available = resolution.availableAfter,
            reservedOrderIds = resolution.completedOrders,
        )
    }

    private fun hashOf(command: RegisterStockEntryCommand): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${command.materialId}:${command.quantity}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val CREATED = 201
    }
}

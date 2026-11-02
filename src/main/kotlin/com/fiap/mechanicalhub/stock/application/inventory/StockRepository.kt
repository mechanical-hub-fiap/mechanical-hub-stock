package com.fiap.mechanicalhub.stock.application.inventory

import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import com.fiap.mechanicalhub.stock.domain.reservation.PendingItem
import java.time.Instant
import java.util.UUID

data class MaterialBalance(val materialId: UUID, val name: String, val available: Int, val reserved: Int)

data class StockMovementView(
    val id: UUID,
    val orderId: UUID?,
    val type: StockMovementTypeEnum,
    val quantity: Int,
    val createdAt: Instant,
)

/** Porta de persistencia do estoque. Escritas exigem transacao ativa. */
@Suppress("TooManyFunctions")
interface StockRepository {
    fun materialExists(materialId: UUID): Boolean

    fun quantity(materialId: UUID, status: StockStatusEnum): Int

    fun addQuantity(materialId: UUID, status: StockStatusEnum, delta: Int)

    fun recordMovement(materialId: UUID, orderId: UUID?, type: StockMovementTypeEnum, quantity: Int)

    fun pendingItems(materialId: UUID): List<PendingItem>

    fun reducePendingItem(itemId: UUID, newQuantity: Int)

    fun deletePendingItem(itemId: UUID)

    /** Soma de service_materials dos reparos, por material. */
    fun requiredMaterials(repairIds: Collection<UUID>): List<MaterialQuantity>

    fun insertPendingItem(orderId: UUID, sagaTransactionId: UUID, materialId: UUID, quantity: Int)

    /** Soma das movimentacoes RESERVED da ordem, por material. */
    fun reservedMaterials(orderId: UUID): List<MaterialQuantity>

    fun balances(): List<MaterialBalance>

    fun materialName(materialId: UUID): String

    fun movements(materialId: UUID): List<StockMovementView>
}

class MaterialNotFoundException(val materialId: UUID) : RuntimeException("Material $materialId nao encontrado")

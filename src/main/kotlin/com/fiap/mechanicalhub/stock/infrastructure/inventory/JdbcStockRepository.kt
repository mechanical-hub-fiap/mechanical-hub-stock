package com.fiap.mechanicalhub.stock.infrastructure.inventory

import com.fiap.mechanicalhub.stock.application.inventory.MaterialBalance
import com.fiap.mechanicalhub.stock.application.inventory.StockMovementView
import com.fiap.mechanicalhub.stock.application.inventory.StockRepository
import com.fiap.mechanicalhub.stock.domain.inventory.StockMovementTypeEnum
import com.fiap.mechanicalhub.stock.domain.inventory.StockStatusEnum
import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import com.fiap.mechanicalhub.stock.domain.reservation.PendingItem
import com.fiap.mechanicalhub.stock.domain.reservation.sumByMaterial
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
@Suppress("TooManyFunctions")
class JdbcStockRepository(private val jdbc: JdbcTemplate) : StockRepository {
    override fun materialExists(materialId: UUID): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM materials WHERE id = ? AND deleted_at IS NULL)",
            Boolean::class.java,
            materialId,
        ) == true

    override fun quantity(materialId: UUID, status: StockStatusEnum): Int =
        jdbc.queryForObject(
            "SELECT quantity FROM stock WHERE material_id = ? AND status = ?",
            Int::class.java,
            materialId,
            status.name,
        ) ?: 0

    override fun addQuantity(materialId: UUID, status: StockStatusEnum, delta: Int) {
        jdbc.update(
            "UPDATE stock SET quantity = quantity + ?, updated_at = now() WHERE material_id = ? AND status = ?",
            delta,
            materialId,
            status.name,
        )
    }

    override fun recordMovement(materialId: UUID, orderId: UUID?, type: StockMovementTypeEnum, quantity: Int) {
        jdbc.update(
            "INSERT INTO stock_movements (material_id, service_order_id, movement_type, quantity) VALUES (?, ?, ?, ?)",
            materialId,
            orderId,
            type.name,
            quantity,
        )
    }

    override fun pendingItems(materialId: UUID): List<PendingItem> =
        jdbc.query(
            "SELECT id, service_order_id, saga_transaction_id, material_id, quantity, created_at " +
                "FROM stock_pending_items " +
                "WHERE material_id = ? ORDER BY created_at, id",
            { rs, _ ->
                PendingItem(
                    id = rs.getObject("id", UUID::class.java),
                    orderId = rs.getObject("service_order_id", UUID::class.java),
                    materialId = rs.getObject("material_id", UUID::class.java),
                    quantity = rs.getInt("quantity"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    sagaTransactionId = rs.getObject("saga_transaction_id", UUID::class.java),
                )
            },
            materialId,
        )

    override fun reservedMaterials(orderId: UUID): List<MaterialQuantity> =
        jdbc
            .query(
                "SELECT material_id, quantity FROM stock_movements " +
                    "WHERE service_order_id = ? AND movement_type = 'RESERVED'",
                { rs, _ -> MaterialQuantity(rs.getObject("material_id", UUID::class.java), rs.getInt("quantity")) },
                orderId,
            ).sumByMaterial()

    override fun reducePendingItem(itemId: UUID, newQuantity: Int) {
        jdbc.update("UPDATE stock_pending_items SET quantity = ? WHERE id = ?", newQuantity, itemId)
    }

    override fun deletePendingItem(itemId: UUID) {
        jdbc.update("DELETE FROM stock_pending_items WHERE id = ?", itemId)
    }

    override fun requiredMaterials(repairIds: Collection<UUID>): List<MaterialQuantity> =
        if (repairIds.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .query(
                    "SELECT material_id, quantity FROM service_materials WHERE service_id = ANY (?)",
                    { rs, _ -> MaterialQuantity(rs.getObject("material_id", UUID::class.java), rs.getInt("quantity")) },
                    repairIds.toTypedArray(),
                ).sumByMaterial()
        }

    override fun insertPendingItem(orderId: UUID, sagaTransactionId: UUID, materialId: UUID, quantity: Int) {
        jdbc.update(
            "INSERT INTO stock_pending_items (service_order_id, saga_transaction_id, material_id, quantity) " +
                "VALUES (?, ?, ?, ?)",
            orderId,
            sagaTransactionId,
            materialId,
            quantity,
        )
    }

    override fun materialName(materialId: UUID): String =
        jdbc.queryForObject("SELECT name FROM materials WHERE id = ?", String::class.java, materialId)
            ?: materialId.toString()

    override fun balances(): List<MaterialBalance> =
        jdbc.query(
            """
            SELECT m.id, m.name,
                   COALESCE(SUM(s.quantity) FILTER (WHERE s.status = 'AVAILABLE'), 0) AS available,
                   COALESCE(SUM(s.quantity) FILTER (WHERE s.status = 'RESERVED'), 0) AS reserved
              FROM materials m LEFT JOIN stock s ON s.material_id = m.id
             WHERE m.deleted_at IS NULL
             GROUP BY m.id, m.name
             ORDER BY m.name, m.id
            """.trimIndent(),
            { rs, _ ->
                MaterialBalance(
                    materialId = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    available = rs.getInt("available"),
                    reserved = rs.getInt("reserved"),
                )
            },
        )

    override fun movements(materialId: UUID): List<StockMovementView> =
        jdbc.query(
            "SELECT id, service_order_id, movement_type, quantity, created_at FROM stock_movements " +
                "WHERE material_id = ? ORDER BY created_at, id",
            { rs, _ ->
                StockMovementView(
                    id = rs.getObject("id", UUID::class.java),
                    orderId = rs.getObject("service_order_id", UUID::class.java),
                    type = StockMovementTypeEnum.valueOf(rs.getString("movement_type")),
                    quantity = rs.getInt("quantity"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            materialId,
        )
}

package com.fiap.mechanicalhub.stock.infrastructure.reservation

import com.fiap.mechanicalhub.stock.application.reservation.StockLocks
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataAccessException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JdbcStockLocks(private val jdbc: JdbcTemplate) : StockLocks {
    override fun applyLockTimeout() {
        translate { jdbc.execute("SET LOCAL lock_timeout = '5s'") }
    }

    override fun lockMaterials(materialIds: Collection<UUID>) {
        val sorted = materialIds.distinct().sorted()
        if (sorted.isEmpty()) return
        translate {
            jdbc.queryForList(
                "SELECT id FROM stock WHERE material_id = ANY (?) ORDER BY material_id FOR UPDATE",
                UUID::class.java,
                sorted.toTypedArray(),
            )
        }
    }

    override fun lockOrder(orderId: UUID) {
        translate { jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", orderLockKey(orderId)) }
    }

    override fun countPendingItems(orderId: UUID): Int =
        translate {
            jdbc.queryForObject(
                "SELECT count(*) FROM stock_pending_items WHERE service_order_id = ?",
                Int::class.java,
                orderId,
            )!!
        }

    private fun <T> translate(block: () -> T): T =
        try {
            block()
        } catch (e: PessimisticLockingFailureException) {
            throw TransientStockException("Deadlock ou lock_timeout", e)
        } catch (e: CannotAcquireLockException) {
            throw TransientStockException("Deadlock ou lock_timeout", e)
        } catch (e: DataAccessException) {
            if (e.mostSpecificCause.message?.contains("lock timeout") == true) {
                throw TransientStockException("Deadlock ou lock_timeout", e)
            }
            throw e
        }

    companion object {
        fun orderLockKey(orderId: UUID): Long = orderId.mostSignificantBits xor orderId.leastSignificantBits
    }
}

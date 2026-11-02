package com.fiap.mechanicalhub.stock

import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryCommand
import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryUseCase
import com.fiap.mechanicalhub.stock.application.messaging.StockCommandProcessor
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.infrastructure.messaging.StockCommandParser
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StockConcurrencyTests {
    @Autowired
    private lateinit var processor: StockCommandProcessor

    @Autowired
    private lateinit var parser: StockCommandParser

    @Autowired
    private lateinit var entryUseCase: RegisterStockEntryUseCase

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun material(available: Int, reserved: Int = 0): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO materials (id, name) VALUES (?, ?)", id, "m-$id")
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'AVAILABLE', ?)", id, available)
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'RESERVED', ?)", id, reserved)
        return id
    }

    private fun repair(vararg materials: Pair<UUID, Int>): UUID {
        val id = UUID.randomUUID()
        materials.forEach { (materialId, quantity) ->
            jdbc.update(
                "INSERT INTO service_materials (service_id, material_id, quantity) VALUES (?, ?, ?)",
                id,
                materialId,
                quantity,
            )
        }
        return id
    }

    private fun pending(orderId: UUID, materialId: UUID, quantity: Int, secondsAhead: Int = 0) =
        jdbc.update(
            "INSERT INTO stock_pending_items (service_order_id, saga_transaction_id, material_id, quantity, created_at) " +
                "VALUES (?, ?, ?, ?, now() + (? * interval '1 second'))",
            orderId,
            UUID.randomUUID(),
            materialId,
            quantity,
            secondsAhead,
        )

    private fun qty(materialId: UUID, status: String) =
        jdbc.queryForObject(
            "SELECT quantity FROM stock WHERE material_id = ? AND status = ?",
            Int::class.java,
            materialId,
            status,
        )!!

    private fun pendingTotal(materialId: UUID) =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity), 0) FROM stock_pending_items WHERE material_id = ?",
            Int::class.java,
            materialId,
        )!!

    private fun countEvents(orderId: UUID, type: String) =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
            Int::class.java,
            orderId,
            type,
        )!!

    private fun assertInvariant(vararg materials: UUID) =
        materials.forEach {
            if (pendingTotal(it) > 0) assertEquals(0, qty(it, "AVAILABLE"), "pendencia exige AVAILABLE = 0")
        }

    private fun <T> retrying(block: () -> T): T {
        repeat(20) {
            try {
                return block()
            } catch (_: TransientStockException) {
            } catch (_: PessimisticLockingFailureException) {
            }
        }
        return block()
    }

    private fun runConcurrently(vararg tasks: () -> Unit) {
        val pool = Executors.newFixedThreadPool(tasks.size)
        val start = CountDownLatch(1)
        try {
            val futures =
                tasks.map { task ->
                    pool.submit {
                        start.await()
                        retrying(task)
                    }
                }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun reserve(orderId: UUID, vararg repairs: UUID) {
        val transactionId = UUID.randomUUID()
        processor.process(
            parser.parse(
                """{"transactionId":"$transactionId","orderId":"$orderId","type":"reserveStock",
                    "idempotencyKey":"$transactionId:RESERVE_STOCK",
                    "repairs":[${repairs.joinToString(",") { """{"repairId":"$it"}""" }}]}""",
            ),
        )
    }

    private fun entry(materialId: UUID, quantity: Int) =
        entryUseCase.execute(RegisterStockEntryCommand(UUID.randomUUID().toString(), materialId, quantity))

    @Test
    fun `given simultaneous reservations of the same material then total reserved plus pending matches demand`() {
        val a = material(10)
        val orders = List(5) { UUID.randomUUID() }
        val repairs = orders.map { repair(a to 4) }

        runConcurrently(*orders.mapIndexed { i, order -> { reserve(order, repairs[i]) } }.toTypedArray())

        assertEquals(10, qty(a, "RESERVED"))
        assertEquals(0, qty(a, "AVAILABLE"))
        assertEquals(10, pendingTotal(a))
        assertInvariant(a)
    }

    @Test
    fun `given two entries of different materials that complete the same order then a single stockReserved is published`() {
        val a = material(0)
        val b = material(0)
        val order = UUID.randomUUID()
        pending(order, a, 2)
        pending(order, b, 3)

        runConcurrently({ entry(a, 2) }, { entry(b, 3) })

        assertEquals(1, countEvents(order, "stockReserved"))
        assertEquals(0, pendingTotal(a) + pendingTotal(b))
        assertEquals(2, qty(a, "RESERVED"))
        assertEquals(3, qty(b, "RESERVED"))
        assertInvariant(a, b)
    }

    @Test
    fun `given an entry concurrent with a restore then the waiting order is served once and the invariant holds`() {
        val a = material(available = 0, reserved = 4)
        val holder = UUID.randomUUID()
        val waiting = UUID.randomUUID()
        pending(waiting, a, 3)
        val restoreTransaction = UUID.randomUUID()
        val restore =
            parser.parse(
                """{"transactionId":"$restoreTransaction","orderId":"$holder","type":"restoreStock",
                    "idempotencyKey":"$restoreTransaction:RESTORE_STOCK",
                    "reservedMaterials":[{"materialId":"$a","quantity":4}]}""",
            )

        runConcurrently({ entry(a, 2) }, { processor.process(restore) })

        assertEquals(1, countEvents(waiting, "stockReserved"))
        assertEquals(1, countEvents(holder, "stockRestored"))
        assertEquals(0, pendingTotal(a))
        assertEquals(3, qty(a, "RESERVED"))
        assertEquals(3, qty(a, "AVAILABLE"))
        assertInvariant(a)
    }
}

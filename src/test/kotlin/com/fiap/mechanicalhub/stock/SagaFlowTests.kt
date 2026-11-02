package com.fiap.mechanicalhub.stock

import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryCommand
import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryUseCase
import com.fiap.mechanicalhub.stock.application.messaging.StockCommandProcessor
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.infrastructure.messaging.StockCommandParser
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

/** Fluxos da saga vistos pelo estoque, com os comandos que o orquestrador envia. */
@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SagaFlowTests {
    @Autowired
    private lateinit var processor: StockCommandProcessor

    @Autowired
    private lateinit var parser: StockCommandParser

    @Autowired
    private lateinit var entry: RegisterStockEntryUseCase

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun material(available: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO materials (id, name) VALUES (?, ?)", id, "flow-$id")
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'AVAILABLE', ?)", id, available)
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'RESERVED', 0)", id)
        return id
    }

    private fun repair(material: UUID, quantity: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO service_materials (service_id, material_id, quantity) VALUES (?, ?, ?)", id, material, quantity)
        return id
    }

    private fun qty(materialId: UUID, status: String) =
        jdbc.queryForObject(
            "SELECT quantity FROM stock WHERE material_id = ? AND status = ?",
            Int::class.java,
            materialId,
            status,
        )!!

    private fun events(orderId: UUID) =
        jdbc.queryForList(
            "SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence",
            String::class.java,
            orderId,
        )

    private fun reserve(order: UUID, transaction: UUID, repair: UUID) =
        processor.process(
            parser.parse(
                """{"transactionId":"$transaction","orderId":"$order","type":"reserveStock",
                    "idempotencyKey":"$transaction:RESERVE_STOCK","repairs":[{"repairId":"$repair"}]}""",
            ),
        )

    private fun materialsCommand(type: String, step: String, order: UUID, material: UUID, quantity: Int) =
        processor.process(
            parser.parse(
                """{"transactionId":"${UUID.randomUUID()}","orderId":"$order","type":"$type",
                    "idempotencyKey":"${UUID.randomUUID()}:$step",
                    "reservedMaterials":[{"materialId":"$material","quantity":$quantity}]}""",
            ),
        )

    @Test
    fun `given the happy path when reserving and consuming then the stock is written off from RESERVED`() {
        val m = material(10)
        val order = UUID.randomUUID()

        reserve(order, UUID.randomUUID(), repair(m, 4))
        materialsCommand("consumeStock", "CONSUME_STOCK", order, m, 4)

        assertEquals(listOf("stockReserved", "stockConsumed"), events(order))
        assertEquals(6, qty(m, "AVAILABLE"))
        assertEquals(0, qty(m, "RESERVED"))
    }

    @Test
    fun `given a pending order when a stock entry arrives then stockReserved is published for the order`() {
        val m = material(1)
        val order = UUID.randomUUID()

        reserve(order, UUID.randomUUID(), repair(m, 3))
        assertEquals(listOf("stockPending"), events(order))

        val outcome = entry.execute(RegisterStockEntryCommand(UUID.randomUUID().toString(), m, 5))

        assertEquals(listOf(order), outcome.result.reservedOrderIds)
        assertEquals(listOf("stockPending", "stockReserved"), events(order))
        assertEquals(3, qty(m, "RESERVED"))
        assertEquals(3, qty(m, "AVAILABLE"))
    }

    @Test
    fun `given a refused budget when restoring then everything returns to AVAILABLE and stockRestored is published`() {
        val m = material(10)
        val order = UUID.randomUUID()

        reserve(order, UUID.randomUUID(), repair(m, 4))
        materialsCommand("restoreStock", "RESTORE_STOCK", order, m, 4)

        assertEquals(listOf("stockReserved", "stockRestored"), events(order))
        assertEquals(10, qty(m, "AVAILABLE"))
        assertEquals(0, qty(m, "RESERVED"))
    }

    @Test
    fun `given an unconfirmed payment when restoring then the stock is redistributed to the pending order`() {
        val m = material(4)
        val paid = UUID.randomUUID()
        val waiting = UUID.randomUUID()

        reserve(paid, UUID.randomUUID(), repair(m, 4))
        reserve(waiting, UUID.randomUUID(), repair(m, 4))
        assertEquals(listOf("stockPending"), events(waiting))

        materialsCommand("restoreStock", "RESTORE_STOCK", paid, m, 4)

        assertEquals(listOf("stockReserved", "stockRestored"), events(paid))
        assertEquals(listOf("stockPending", "stockReserved"), events(waiting))
        assertEquals(4, qty(m, "RESERVED"))
        assertEquals(0, qty(m, "AVAILABLE"))
    }
}

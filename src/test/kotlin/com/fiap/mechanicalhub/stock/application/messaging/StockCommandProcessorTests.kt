package com.fiap.mechanicalhub.stock.application.messaging

import com.fiap.mechanicalhub.stock.TestcontainersConfiguration
import com.fiap.mechanicalhub.stock.application.reservation.BusinessRuleException
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.infrastructure.messaging.RetentionJobs
import com.fiap.mechanicalhub.stock.infrastructure.messaging.StockCommandParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StockCommandProcessorTests {
    @Autowired
    private lateinit var processor: StockCommandProcessor

    @Autowired
    private lateinit var parser: StockCommandParser

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var retention: RetentionJobs

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM outbox_events")
    }

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

    private fun reserve(orderId: UUID, transactionId: UUID, vararg repairs: UUID) =
        processor.process(
            parser.parse(
                """{"transactionId":"$transactionId","orderId":"$orderId","type":"reserveStock",
                    "idempotencyKey":"$transactionId:RESERVE_STOCK",
                    "repairs":[${repairs.joinToString(",") { """{"repairId":"$it"}""" }}]}""",
            ),
        )

    private fun materialsCommand(type: String, step: String, orderId: UUID, transactionId: UUID, vararg m: Pair<UUID, Int>) =
        parser.parse(
            """{"transactionId":"$transactionId","orderId":"$orderId","type":"$type",
                "idempotencyKey":"$transactionId:$step",
                "reservedMaterials":[${m.joinToString(",") { """{"materialId":"${it.first}","quantity":${it.second}}""" }}]}""",
        )

    @Test
    fun `given enough stock when reserving then it reserves, sums repairs and answers stockReserved`() {
        val a = material(10)
        val b = material(10)
        val order = UUID.randomUUID()
        val r1 = repair(a to 2, b to 1)
        val r2 = repair(a to 3)

        assertEquals(CommandOutcome.PROCESSED, reserve(order, UUID.randomUUID(), r1, r2))

        assertEquals(5, qty(a, "RESERVED"))
        assertEquals(5, qty(a, "AVAILABLE"))
        assertEquals(1, qty(b, "RESERVED"))
        assertEquals(listOf("stockReserved"), events(order))
        val reservedA =
            jdbc.queryForObject(
                "SELECT (e->>'quantity')::int FROM outbox_events, jsonb_array_elements(payload->'reservedMaterials') e " +
                    "WHERE aggregate_id = ? AND e->>'materialId' = ?",
                Int::class.java,
                order,
                a.toString(),
            )
        assertEquals(5, reservedA)
    }

    @Test
    fun `given insufficient stock when reserving then it reserves what exists, records the pending item and answers stockPending`() {
        val a = material(3)
        val order = UUID.randomUUID()
        val transaction = UUID.randomUUID()

        reserve(order, transaction, repair(a to 5))

        assertEquals(3, qty(a, "RESERVED"))
        assertEquals(0, qty(a, "AVAILABLE"))
        assertEquals(listOf("stockPending"), events(order))
        assertEquals(
            2,
            jdbc.queryForObject("SELECT quantity FROM stock_pending_items WHERE service_order_id = ?", Int::class.java, order),
        )
        assertEquals(
            transaction,
            jdbc.queryForObject("SELECT saga_transaction_id FROM stock_pending_items WHERE service_order_id = ?", UUID::class.java, order),
        )
    }

    @Test
    fun `given a duplicated command when processing then the effect is not repeated and the response is rewritten`() {
        val a = material(10)
        val order = UUID.randomUUID()
        val transaction = UUID.randomUUID()
        val repair = repair(a to 2)

        assertEquals(CommandOutcome.PROCESSED, reserve(order, transaction, repair))
        assertEquals(CommandOutcome.DUPLICATE, reserve(order, transaction, repair))

        assertEquals(2, qty(a, "RESERVED"))
        assertEquals(listOf("stockReserved", "stockReserved"), events(order))
        val ids = jdbc.queryForList("SELECT event_id FROM outbox_events WHERE aggregate_id = ?", UUID::class.java, order)
        assertNotEquals(ids[0], ids[1])
        val payloadIds =
            jdbc.queryForList(
                "SELECT payload->>'eventId' FROM outbox_events WHERE aggregate_id = ? ORDER BY sequence",
                String::class.java,
                order,
            )
        assertEquals(ids.map { it.toString() }.sorted(), payloadIds.filterNotNull().sorted())
    }

    @Test
    fun `given reserved stock when consuming then it writes off RESERVED with OUT and keeps AVAILABLE`() {
        val a = material(10, 4)
        val order = UUID.randomUUID()

        processor.process(materialsCommand("consumeStock", "CONSUME_STOCK", order, UUID.randomUUID(), a to 3))

        assertEquals(1, qty(a, "RESERVED"))
        assertEquals(10, qty(a, "AVAILABLE"))
        assertEquals(listOf("stockConsumed"), events(order))
        assertEquals(
            3,
            jdbc.queryForObject(
                "SELECT quantity FROM stock_movements WHERE service_order_id = ? AND movement_type = 'OUT'",
                Int::class.java,
                order,
            ),
        )
    }

    @Test
    fun `given a duplicated consume when processing then the write-off happens once`() {
        val a = material(10, 4)
        val order = UUID.randomUUID()
        val command = materialsCommand("consumeStock", "CONSUME_STOCK", order, UUID.randomUUID(), a to 3)

        assertEquals(CommandOutcome.PROCESSED, processor.process(command))
        assertEquals(CommandOutcome.DUPLICATE, processor.process(command))

        assertEquals(1, qty(a, "RESERVED"))
        assertEquals(10, qty(a, "AVAILABLE"))
        assertEquals(listOf("stockConsumed", "stockConsumed"), events(order))
    }

    @Test
    fun `given insufficient RESERVED when consuming or restoring then it fails permanently and changes nothing`() {
        val a = material(10, 1)
        val order = UUID.randomUUID()

        assertFailsWith<BusinessRuleException> {
            processor.process(materialsCommand("consumeStock", "CONSUME_STOCK", order, UUID.randomUUID(), a to 2))
        }
        assertFailsWith<BusinessRuleException> {
            processor.process(materialsCommand("restoreStock", "RESTORE_STOCK", order, UUID.randomUUID(), a to 2))
        }

        assertEquals(1, qty(a, "RESERVED"))
        assertEquals(10, qty(a, "AVAILABLE"))
        assertEquals(emptyList(), events(order))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM inbox_events WHERE message_key LIKE '%:RESTORE_STOCK' AND response_payload IS NULL",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `given a pending order when another order is restored then the stock goes to the oldest pending order`() {
        val a = material(4)
        val holder = UUID.randomUUID()
        val waiting = UUID.randomUUID()
        val waitingTransaction = UUID.randomUUID()
        reserve(holder, UUID.randomUUID(), repair(a to 4))
        reserve(waiting, waitingTransaction, repair(a to 3))
        assertEquals(listOf("stockPending"), events(waiting))

        val restoreTransaction = UUID.randomUUID()
        processor.process(materialsCommand("restoreStock", "RESTORE_STOCK", holder, restoreTransaction, a to 4))

        assertEquals(listOf("stockReserved", "stockRestored"), events(holder))
        assertEquals(listOf("stockPending", "stockReserved"), events(waiting))
        assertEquals(3, qty(a, "RESERVED"))
        assertEquals(1, qty(a, "AVAILABLE"))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM stock_pending_items WHERE service_order_id = ?", Int::class.java, waiting),
        )
        val stockReserved =
            jdbc.queryForObject(
                "SELECT payload->>'transactionId' FROM outbox_events WHERE aggregate_id = ? AND event_type = 'stockReserved'",
                String::class.java,
                waiting,
            )
        assertEquals(waitingTransaction.toString(), stockReserved)
    }

    @Test
    fun `given old inbox and idempotency rows when running the retention jobs then only expired rows are removed`() {
        jdbc.update("INSERT INTO inbox_events (message_key, processed_at) VALUES ('old-inbox', now() - interval '31 days')")
        jdbc.update("INSERT INTO inbox_events (message_key, processed_at) VALUES ('new-inbox', now() - interval '29 days')")
        jdbc.update(
            "INSERT INTO idempotency_requests (idempotency_key, request_hash, response_status, created_at) VALUES ('old-idem', 'h', 201, now() - interval '8 days')",
        )
        jdbc.update(
            "INSERT INTO idempotency_requests (idempotency_key, request_hash, response_status, created_at) VALUES ('new-idem', 'h', 201, now() - interval '6 days')",
        )

        retention.purge()

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE message_key = 'old-inbox'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inbox_events WHERE message_key = 'new-inbox'", Int::class.java))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE idempotency_key = 'old-idem'", Int::class.java),
        )
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM idempotency_requests WHERE idempotency_key = 'new-idem'", Int::class.java),
        )
    }
}

package com.fiap.mechanicalhub.stock.infrastructure.reservation

import com.fiap.mechanicalhub.stock.TestcontainersConfiguration
import com.fiap.mechanicalhub.stock.application.messaging.StockCommandProcessor
import com.fiap.mechanicalhub.stock.application.reservation.ShortageItem
import com.fiap.mechanicalhub.stock.application.reservation.StockShortageNotifier
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.infrastructure.messaging.StockCommandParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
@Import(TestcontainersConfiguration::class, StockShortageAlertTests.RecordingConfig::class)
@SpringBootTest
class StockShortageAlertTests {
    class Recorder : StockShortageNotifier {
        val calls = CopyOnWriteArrayList<Triple<UUID, UUID, List<ShortageItem>>>()
        var fail = false

        override fun notifyShortage(orderId: UUID, transactionId: UUID, shortages: List<ShortageItem>) {
            calls += Triple(orderId, transactionId, shortages)
            if (fail) error("smtp down")
        }
    }

    @TestConfiguration
    class RecordingConfig {
        @Bean
        @Primary
        fun recorder() = Recorder()
    }

    @Autowired
    private lateinit var recorder: Recorder

    @Autowired
    private lateinit var processor: StockCommandProcessor

    @Autowired
    private lateinit var parser: StockCommandParser

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun reset() {
        recorder.calls.clear()
        recorder.fail = false
    }

    private fun scenario(available: Int, needed: Int): Triple<UUID, UUID, UUID> {
        val material = UUID.randomUUID()
        jdbc.update("INSERT INTO materials (id, name) VALUES (?, ?)", material, "mat-$material")
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'AVAILABLE', ?)", material, available)
        jdbc.update("INSERT INTO stock (material_id, status, quantity) VALUES (?, 'RESERVED', 0)", material)
        val repair = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO service_materials (service_id, material_id, quantity) VALUES (?, ?, ?)",
            repair,
            material,
            needed,
        )
        return Triple(material, repair, UUID.randomUUID())
    }

    private fun reserve(repair: UUID, order: UUID, transaction: UUID) =
        processor.process(
            parser.parse(
                """{"transactionId":"$transaction","orderId":"$order","type":"reserveStock",
                    "idempotencyKey":"$transaction:RESERVE_STOCK","repairs":[{"repairId":"$repair"}]}""",
            ),
        )

    @Test
    fun `given a shortage when reserving then the alert lists the missing materials after the commit`() {
        val (material, repair, order) = scenario(available = 1, needed = 4)

        reserve(repair, order, UUID.randomUUID())

        assertEquals(1, recorder.calls.size)
        val (alertOrder, _, shortages) = recorder.calls.single()
        assertEquals(order, alertOrder)
        assertEquals(listOf(ShortageItem(material, "mat-$material", 3)), shortages)
    }

    @Test
    fun `given enough stock when reserving then no alert is sent`() {
        val (_, repair, order) = scenario(available = 10, needed = 4)

        reserve(repair, order, UUID.randomUUID())

        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `given a notifier failure when reserving then the command still commits`() {
        recorder.fail = true
        val (material, repair, order) = scenario(available = 0, needed = 2)

        reserve(repair, order, UUID.randomUUID())

        assertEquals(1, recorder.calls.size)
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT quantity FROM stock_pending_items WHERE material_id = ? AND service_order_id = ?",
                Int::class.java,
                material,
                order,
            ),
        )
    }
}

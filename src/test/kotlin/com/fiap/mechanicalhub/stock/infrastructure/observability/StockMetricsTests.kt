package com.fiap.mechanicalhub.stock.infrastructure.observability

import com.fiap.mechanicalhub.stock.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class StockMetricsTests {
    @Autowired
    private lateinit var registry: MeterRegistry

    @Autowired
    private lateinit var metrics: StockMetrics

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `given pending outbox events when gauges are read then the oldest age is positive`() {
        jdbc.update("DELETE FROM outbox_events")
        assertEquals(0.0, registry.get("outbox_oldest_pending_age_seconds").gauge().value())
        val eventId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO outbox_events (event_id, aggregate_id, event_type, payload, created_at) " +
                "VALUES (?, ?, 'stockPending', '{}'::jsonb, now() - interval '30 seconds')",
            eventId,
            UUID.randomUUID(),
        )
        assertTrue(registry.get("outbox_oldest_pending_age_seconds").gauge().value() >= 30.0)
        jdbc.update("DELETE FROM outbox_events WHERE event_id = ?", eventId)
    }

    @Test
    fun `given seeded stock when balances are refreshed then each material and status has a gauge`() {
        metrics.refreshBalances()

        val rows = jdbc.queryForList("SELECT material_id::text AS id, status, quantity FROM stock")
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            val gauge =
                registry
                    .get("stock_balance")
                    .tag("material_id", row["id"] as String)
                    .tag("status", row["status"] as String)
                    .gauge()
            assertEquals((row["quantity"] as Number).toDouble(), gauge.value())
        }
        assertTrue(metrics.pendingItems() >= 0.0)
    }
}

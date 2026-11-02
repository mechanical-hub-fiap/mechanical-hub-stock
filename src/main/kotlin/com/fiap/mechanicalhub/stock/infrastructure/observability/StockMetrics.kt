package com.fiap.mechanicalhub.stock.infrastructure.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Metricas de negocio expostas em /actuator/prometheus:
 * - outbox_oldest_pending_age_seconds: idade do evento PENDING mais antigo (0 sem pendentes);
 * - stock_pending_items_total: itens em stock_pending_items;
 * - stock_balance{material_id, status}: saldo AVAILABLE/RESERVED por material (atualizado a cada 15 s).
 */
@Component
class StockMetrics(
    private val jdbc: JdbcTemplate,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(StockMetrics::class.java)
    private val balances =
        MultiGauge
            .builder("stock_balance")
            .description("Saldo por material e status")
            .register(registry)

    init {
        Gauge
            .builder("outbox_oldest_pending_age_seconds") { oldestPendingAgeSeconds() }
            .description("Idade em segundos do evento PENDING mais antigo do outbox")
            .register(registry)
        Gauge
            .builder("stock_pending_items_total") { pendingItems() }
            .description("Quantidade de itens em stock_pending_items")
            .register(registry)
    }

    fun oldestPendingAgeSeconds(): Double =
        queryOrZero {
            jdbc.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)::float8 " +
                    "FROM outbox_events WHERE status = 'PENDING'",
                Double::class.java,
            )
        }

    fun pendingItems(): Double =
        queryOrZero { jdbc.queryForObject("SELECT COUNT(*)::float8 FROM stock_pending_items", Double::class.java) }

    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = INITIAL_DELAY_MS)
    fun refreshBalances() {
        try {
            val rows =
                jdbc.query(
                    "SELECT material_id, status, quantity FROM stock",
                    { rs, _ ->
                        MultiGauge.Row.of(
                            Tags.of("material_id", rs.getString("material_id"), "status", rs.getString("status")),
                            rs.getInt("quantity"),
                        )
                    },
                )
            balances.register(rows, true)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("Falha ao atualizar as metricas de saldo", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun queryOrZero(query: () -> Double?): Double =
        try {
            query() ?: 0.0
        } catch (e: Exception) {
            log.warn("Falha ao consultar metrica", e)
            0.0
        }

    companion object {
        private const val REFRESH_MS = 15_000L
        private const val INITIAL_DELAY_MS = 1_000L
    }
}

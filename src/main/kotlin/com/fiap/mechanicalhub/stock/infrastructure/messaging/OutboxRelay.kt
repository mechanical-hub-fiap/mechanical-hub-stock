package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.infrastructure.observability.LogContext
import com.fiap.mechanicalhub.stock.infrastructure.observability.withLogContext
import com.fiap.mechanicalhub.stock.logging.FlowFields
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.LogLevel
import com.fiap.mechanicalhub.stock.logging.Stage
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import javax.sql.DataSource

data class OutboxRow(
    val sequence: Long,
    val eventId: UUID,
    val orderId: UUID,
    val payload: String,
    val eventType: String = "",
)

fun interface OutboxSender {
    fun send(row: OutboxRow)
}

@Component
class SqsOutboxSender(
    private val sqs: SqsTemplate,
    @Value("\${stock.queues.orchestrator-events}") private val queue: String,
) : OutboxSender {
    override fun send(row: OutboxRow) {
        sqs.send<String> {
            it
                .queue(queue)
                .payload(row.payload)
                .messageGroupId(row.orderId.toString())
                .messageDeduplicationId(row.eventId.toString())
        }
    }
}

/**
 * Relay do outbox: a cada 1 s, lote de ate 100 eventos PENDING por sequence. Apenas uma instancia
 * atua por vez (pg_try_advisory_lock). Erro de envio interrompe o lote e incrementa attempts.
 */
@Component
@ConditionalOnProperty("stock.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelay(
    private val jdbc: JdbcTemplate,
    private val dataSource: DataSource,
    private val sender: OutboxSender,
    @Value("\${stock.outbox.relay.batch-size:100}") private val batchSize: Int,
) {
    private val log = LoggerFactory.getLogger(OutboxRelay::class.java)

    @Scheduled(fixedDelayString = "\${stock.outbox.relay.interval-ms:1000}")
    fun relay(): Int {
        dataSource.connection.use { leader ->
            val acquired =
                leader.prepareStatement("SELECT pg_try_advisory_lock(?)").use { st ->
                    st.setLong(1, LEADER_LOCK_KEY)
                    st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
                }
            if (!acquired) return 0
            try {
                return sendBatch()
            } finally {
                leader.prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
                    st.setLong(1, LEADER_LOCK_KEY)
                    st.execute()
                }
            }
        }
    }

    private fun sendBatch(): Int {
        val rows =
            jdbc.query(
                "SELECT sequence, event_id, aggregate_id, event_type, payload::text AS payload FROM outbox_events " +
                    "WHERE status = 'PENDING' ORDER BY sequence LIMIT ?",
                { rs, _ ->
                    OutboxRow(
                        rs.getLong("sequence"),
                        rs.getObject("event_id", UUID::class.java),
                        rs.getObject("aggregate_id", UUID::class.java),
                        rs.getString("payload"),
                        rs.getString("event_type"),
                    )
                },
                batchSize,
            )
        var sent = 0
        for (row in rows) {
            try {
                withLogContext(
                    LogContext.ORDER_ID to row.orderId.toString(),
                    LogContext.EVENT_ID to row.eventId.toString(),
                ) {
                    sender.send(row)
                    logRow(row, LogLevel.INFO, "Evento ${row.eventType} publicado na fila do orquestrador", null)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") ex: Exception,
            ) {
                withLogContext(
                    LogContext.ORDER_ID to row.orderId.toString(),
                    LogContext.EVENT_ID to row.eventId.toString(),
                ) {
                    val msg = "Falha ao publicar evento ${row.eventType}, nova tentativa no proximo ciclo"
                    logRow(row, LogLevel.WARN, msg, ex)
                }
                jdbc.update(
                    "UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE sequence = ?",
                    ex.message ?: ex.javaClass.name,
                    row.sequence,
                )
                break
            }
            jdbc.update(
                "UPDATE outbox_events SET status = 'SENT', sent_at = now() WHERE sequence = ?",
                row.sequence,
            )
            sent++
        }
        return sent
    }

    /** Publicacao pelo relay (logging-standard §4), com o `status` da etapa que gerou o evento. */
    private fun logRow(row: OutboxRow, level: LogLevel, msg: String, error: Exception?) {
        val fields =
            FlowFields(
                orderId = row.orderId,
                status = EVENT_STATUS[row.eventType],
                msg = msg,
                stage = if (error != null) Stage.FAIL else null,
                context = listOf("eventType" to row.eventType),
                error = error,
            )
        FlowLog.write(log, level, fields)
    }

    companion object {
        const val LEADER_LOCK_KEY = 7_304_201L
        private val EVENT_STATUS =
            mapOf(
                "stockReserved" to "RESERVE_STOCK",
                "stockPending" to "RESERVE_STOCK",
                "stockConsumed" to "CONSUME_STOCK",
                "stockRestored" to "RESTORE_STOCK",
            )
    }
}

package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.application.messaging.OutboxPublisher
import com.fiap.mechanicalhub.stock.application.messaging.StockEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.util.UUID

@Repository
class JdbcOutboxPublisher(private val jdbc: JdbcTemplate, private val json: JsonMapper) : OutboxPublisher {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun publish(event: StockEvent): String {
        val eventId = UUID.randomUUID()
        val envelope =
            linkedMapOf<String, Any>(
                "eventId" to eventId,
                "transactionId" to event.transactionId,
                "orderId" to event.orderId,
                "type" to event.type,
                "version" to 1,
                "timestamp" to Instant.now().toString(),
            ) + event.payload()
        val body = json.writeValueAsString(envelope)
        jdbc.update(
            "INSERT INTO outbox_events (event_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
            eventId,
            event.orderId,
            event.type,
            body,
        )
        return body
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun republish(payload: String) {
        val envelope = json.readTree(payload) as ObjectNode
        val eventId = UUID.randomUUID()
        envelope.put("eventId", eventId.toString())
        jdbc.update(
            "INSERT INTO outbox_events (event_id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
            eventId,
            UUID.fromString(envelope.get("orderId").asString()),
            envelope.get("type").asString(),
            json.writeValueAsString(envelope),
        )
    }
}

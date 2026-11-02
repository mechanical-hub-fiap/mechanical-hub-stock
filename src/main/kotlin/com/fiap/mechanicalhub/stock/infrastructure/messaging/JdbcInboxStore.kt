package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.application.messaging.InboxStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcInboxStore(private val jdbc: JdbcTemplate) : InboxStore {
    override fun claim(key: String): Boolean =
        jdbc.update("INSERT INTO inbox_events (message_key) VALUES (?) ON CONFLICT (message_key) DO NOTHING", key) == 1

    override fun saveResponse(key: String, responsePayload: String) {
        jdbc.update("UPDATE inbox_events SET response_payload = ?::jsonb WHERE message_key = ?", responsePayload, key)
    }

    override fun responsePayload(key: String): String? =
        jdbc
            .queryForList(
                "SELECT response_payload::text FROM inbox_events WHERE message_key = ?",
                String::class.java,
                key,
            ).firstOrNull()
}

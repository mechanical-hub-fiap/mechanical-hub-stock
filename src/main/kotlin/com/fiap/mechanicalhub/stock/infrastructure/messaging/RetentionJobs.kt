package com.fiap.mechanicalhub.stock.infrastructure.messaging

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Jobs diarios de retencao: inbox_events (30 dias) e idempotency_requests (7 dias). */
@Component
@ConditionalOnProperty("stock.retention.enabled", havingValue = "true", matchIfMissing = true)
class RetentionJobs(private val jdbc: JdbcTemplate) {
    private val log = LoggerFactory.getLogger(RetentionJobs::class.java)

    @Scheduled(cron = "\${stock.retention.cron:0 0 3 * * *}")
    fun purge() {
        purgeInbox()
        purgeIdempotencyRequests()
    }

    fun purgeInbox(): Int =
        jdbc
            .update("DELETE FROM inbox_events WHERE processed_at < now() - interval '30 days'")
            .also { log.info("inbox_events removidos: {}", it) }

    fun purgeIdempotencyRequests(): Int =
        jdbc
            .update("DELETE FROM idempotency_requests WHERE created_at < now() - interval '7 days'")
            .also { log.info("idempotency_requests removidos: {}", it) }
}

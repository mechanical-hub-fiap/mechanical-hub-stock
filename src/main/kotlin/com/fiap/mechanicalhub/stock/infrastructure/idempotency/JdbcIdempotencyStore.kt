package com.fiap.mechanicalhub.stock.infrastructure.idempotency

import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyStore
import com.fiap.mechanicalhub.stock.application.idempotency.StoredEntryResponse
import com.fiap.mechanicalhub.stock.application.inventory.StockEntryResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper

@Repository
class JdbcIdempotencyStore(private val jdbc: JdbcTemplate, private val json: JsonMapper) : IdempotencyStore {
    override fun findLocking(key: String): StoredEntryResponse? {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key)
        return jdbc
            .query(
                "SELECT request_hash, response_status, response_body " +
                    "FROM idempotency_requests WHERE idempotency_key = ?",
                { rs, _ ->
                    StoredEntryResponse(
                        requestHash = rs.getString("request_hash"),
                        status = rs.getInt("response_status"),
                        result = json.readValue(rs.getString("response_body"), StockEntryResult::class.java),
                    )
                },
                key,
            ).firstOrNull()
    }

    override fun save(key: String, response: StoredEntryResponse) {
        jdbc.update(
            "INSERT INTO idempotency_requests (idempotency_key, request_hash, response_status, response_body) " +
                "VALUES (?, ?, ?, ?)",
            key,
            response.requestHash,
            response.status,
            json.writeValueAsString(response.result),
        )
    }
}

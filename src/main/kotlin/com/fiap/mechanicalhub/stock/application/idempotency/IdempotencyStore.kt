package com.fiap.mechanicalhub.stock.application.idempotency

import com.fiap.mechanicalhub.stock.application.inventory.StockEntryResult

data class StoredEntryResponse(val requestHash: String, val status: Int, val result: StockEntryResult)

/** Porta de idempotency_requests. */
interface IdempotencyStore {
    /** Busca a chave serializando requisicoes concorrentes com a mesma chave ate o fim da transacao. */
    fun findLocking(key: String): StoredEntryResponse?

    fun save(key: String, response: StoredEntryResponse)
}

class IdempotencyKeyReusedException(key: String) :
    RuntimeException("Idempotency-Key $key ja foi usada com outro corpo")

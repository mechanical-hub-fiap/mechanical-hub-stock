package com.fiap.mechanicalhub.stock.application.messaging

/** Porta de inbox_events (deduplicacao de comandos). Exige transacao ativa. */
interface InboxStore {
    /** Registra a chave; false se ja existia (duplicata). Espera o commit de quem tem a mesma chave. */
    fun claim(key: String): Boolean

    fun saveResponse(key: String, responsePayload: String)

    fun responsePayload(key: String): String?
}

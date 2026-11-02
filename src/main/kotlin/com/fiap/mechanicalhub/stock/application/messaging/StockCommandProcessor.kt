package com.fiap.mechanicalhub.stock.application.messaging

import com.fiap.mechanicalhub.stock.application.reservation.ConsumeStockUseCase
import com.fiap.mechanicalhub.stock.application.reservation.ReserveStockUseCase
import com.fiap.mechanicalhub.stock.application.reservation.RestoreStockUseCase
import com.fiap.mechanicalhub.stock.application.reservation.StockLocks
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class CommandOutcome { PROCESSED, DUPLICATE }

/**
 * Processa um comando em uma unica transacao: deduplicacao por inbox_events, efeito no estoque e resposta no outbox.
 * O chamador so apaga a mensagem da fila depois do retorno (COMMIT).
 */
@Service
class StockCommandProcessor(
    private val inbox: InboxStore,
    private val locks: StockLocks,
    private val outbox: OutboxPublisher,
    private val reserveStock: ReserveStockUseCase,
    private val consumeStock: ConsumeStockUseCase,
    private val restoreStock: RestoreStockUseCase,
) {
    @Transactional
    fun process(command: StockCommand): CommandOutcome {
        locks.applyLockTimeout()
        if (!inbox.claim(command.idempotencyKey)) {
            inbox.responsePayload(command.idempotencyKey)?.let { outbox.republish(it) }
            return CommandOutcome.DUPLICATE
        }
        val response =
            when (command) {
                is ReserveStockCommand -> reserveStock.execute(command)
                is ConsumeStockCommand -> consumeStock.execute(command)
                is RestoreStockCommand -> restoreStock.execute(command)
            }
        inbox.saveResponse(command.idempotencyKey, response)
        return CommandOutcome.PROCESSED
    }
}

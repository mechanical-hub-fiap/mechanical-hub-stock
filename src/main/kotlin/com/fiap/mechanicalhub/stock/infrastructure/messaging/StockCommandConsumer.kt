package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.application.messaging.CommandOutcome
import com.fiap.mechanicalhub.stock.application.messaging.InvalidCommandException
import com.fiap.mechanicalhub.stock.application.messaging.StockCommandProcessor
import com.fiap.mechanicalhub.stock.application.reservation.BusinessRuleException
import com.fiap.mechanicalhub.stock.infrastructure.observability.LogContext
import com.fiap.mechanicalhub.stock.infrastructure.observability.withLogContext
import com.fiap.mechanicalhub.stock.logging.FlowFields
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.FlowStep
import com.fiap.mechanicalhub.stock.logging.LogLevel
import com.fiap.mechanicalhub.stock.logging.Stage
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/** Mensagem recebida da fila de comandos, independente do cliente SQS. */
data class ReceivedCommand(
    val body: String,
    val messageId: String,
    val groupId: String,
    val receiveCount: Int,
    val attributes: Map<String, String> = emptyMap(),
)

/** Envio de uma mensagem com erro permanente a DLQ de comandos. */
fun interface DeadLetterSender {
    fun send(body: String, groupId: String, deduplicationId: String, attributes: Map<String, String>)
}

@Component
class SqsDeadLetterSender(
    private val sqs: SqsTemplate,
    @Value("\${stock.queues.commands-dlq}") private val queue: String,
) : DeadLetterSender {
    override fun send(body: String, groupId: String, deduplicationId: String, attributes: Map<String, String>) {
        sqs.send<String> { to ->
            to
                .queue(queue)
                .payload(body)
                .messageGroupId(groupId)
                .messageDeduplicationId(deduplicationId)
            attributes.forEach { (name, value) -> to.header(name, value) }
        }
    }
}

/**
 * Consome comandos com retry bloqueante: erro transitorio nao apaga a mensagem e ajusta o visibility timeout pela
 * ApproximateReceiveCount (1 s a 8 s); erro permanente vai a DLQ e a mensagem e apagada. A mensagem so e apagada
 * depois do COMMIT da transacao do processador.
 * Cada comando e uma etapa do log (logging-standard §4): `START` antes do processador, `END` depois do COMMIT e
 * `FAIL` com WARN (vai ser reprocessado) ou ERROR (vai para a DLQ).
 */
@Component
class StockCommandConsumer(
    private val parser: StockCommandParser,
    private val processor: StockCommandProcessor,
    private val deadLetters: DeadLetterSender,
) {
    private val log = LoggerFactory.getLogger(StockCommandConsumer::class.java)

    fun consume(message: ReceivedCommand, acknowledge: () -> Unit, changeVisibility: (Int) -> Unit) {
        val orderId = parser.fieldOrNull(message.body, "orderId") ?: message.groupId.ifBlank { null }
        withLogContext(
            LogContext.ORDER_ID to orderId,
            LogContext.EVENT_ID to parser.eventIdOrNull(message.body),
        ) {
            val status = parser.statusOrNull(message.body)
            val step = FlowLog.begin(log, orderId, status, message.receiveCount)
            step.start(status?.let { START_MESSAGES[it] } ?: "Iniciando comando de estoque")
            handle(step, message, acknowledge, changeVisibility)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun handle(
        step: FlowStep,
        message: ReceivedCommand,
        acknowledge: () -> Unit,
        changeVisibility: (Int) -> Unit,
    ) {
        try {
            val command = parser.parse(message.body)
            withLogContext(LogContext.TRANSACTION_ID to command.transactionId.toString()) {
                if (processor.process(command) == CommandOutcome.DUPLICATE) {
                    // Nao e erro (failure_handling §1): a resposta original foi regravada no outbox.
                    step.detail(listOf("outcome" to CommandOutcome.DUPLICATE))
                    step.endMessage("Comando duplicado, resposta original regravada no outbox")
                }
            }
        } catch (e: Exception) {
            if (e.isPermanent()) {
                step.errorFail("Falha permanente no comando, sera enviado para a DLQ", e)
                deadLetter(step, message, e, acknowledge, changeVisibility)
            } else {
                retryLater(step, message, e, changeVisibility)
            }
            return
        }
        step.end(step.status?.let { END_MESSAGES[it] } ?: "Comando de estoque processado")
        acknowledge()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deadLetter(
        step: FlowStep,
        message: ReceivedCommand,
        error: Exception,
        acknowledge: () -> Unit,
        changeVisibility: (Int) -> Unit,
    ) {
        try {
            deadLetters.send(
                message.body,
                message.groupId,
                parser.eventIdOrNull(message.body) ?: message.messageId.ifBlank { UUID.randomUUID().toString() },
                message.attributes.entries
                    .take(MAX_COPIED_ATTRIBUTES)
                    .associate { it.key to it.value } +
                    mapOf(
                        "errorClass" to error.javaClass.name,
                        "errorMessage" to (error.message ?: "").take(MAX_ERROR_MESSAGE),
                    ),
            )
        } catch (e: Exception) {
            val retryIn = backoffSeconds(message.receiveCount)
            logLine(step, LogLevel.WARN, "Falha ao enviar o comando a DLQ, nova tentativa em ${retryIn}s", e)
            changeVisibility(retryIn)
            return
        }
        logLine(step, LogLevel.ERROR, "Comando enviado para a DLQ", error)
        acknowledge()
    }

    private fun retryLater(
        step: FlowStep,
        message: ReceivedCommand,
        error: Exception,
        changeVisibility: (Int) -> Unit,
    ) {
        val seconds = backoffSeconds(message.receiveCount)
        step.warnFail("Falha transitoria no comando, sera reprocessado", error, "retryInSeconds" to seconds)
        changeVisibility(seconds)
    }

    /** Linha `FAIL` do envio a DLQ, com o mesmo `orderId`, `status` e `attempt` da etapa. */
    private fun logLine(step: FlowStep, level: LogLevel, msg: String, error: Exception) {
        FlowLog.write(
            log,
            level,
            FlowFields(step.orderId, step.status, msg, Stage.FAIL, step.attempt, error = error),
        )
    }

    private fun Exception.isPermanent() = this is InvalidCommandException || this is BusinessRuleException

    companion object {
        private val BACKOFF_SECONDS = listOf(1, 2, 4, 8, 8)
        private const val MAX_COPIED_ATTRIBUTES = 8
        private const val MAX_ERROR_MESSAGE = 256
        private val START_MESSAGES =
            mapOf(
                "RESERVE_STOCK" to "Iniciando reserva de estoque",
                "CONSUME_STOCK" to "Iniciando baixa do estoque reservado",
                "RESTORE_STOCK" to "Iniciando devolucao do estoque reservado",
            )
        private val END_MESSAGES =
            mapOf(
                "RESERVE_STOCK" to "Reserva de estoque processada",
                "CONSUME_STOCK" to "Estoque reservado baixado",
                "RESTORE_STOCK" to "Estoque reservado devolvido",
            )

        /** Visibility timeout da proxima tentativa: 1 s, 2 s, 4 s, 8 s, 8 s conforme o numero de recebimentos. */
        fun backoffSeconds(receiveCount: Int): Int =
            BACKOFF_SECONDS[(receiveCount - 1).coerceIn(0, BACKOFF_SECONDS.lastIndex)]
    }
}

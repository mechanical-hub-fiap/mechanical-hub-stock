package com.fiap.mechanicalhub.stock.infrastructure.messaging

import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Adaptador do @SqsListener: long polling de 20 s, visibility timeout de 30 s e confirmacao manual. */
@Component
@ConditionalOnProperty("stock.consumer.enabled", havingValue = "true", matchIfMissing = true)
class StockCommandListener(private val consumer: StockCommandConsumer) {
    @SqsListener(
        value = ["\${stock.queues.commands}"],
        acknowledgementMode = "MANUAL",
        pollTimeoutSeconds = "20",
        messageVisibilitySeconds = "30",
    )
    fun onMessage(message: Message<String>, acknowledgement: Acknowledgement, visibility: Visibility) {
        val headers = message.headers
        consumer.consume(
            ReceivedCommand(
                body = message.payload,
                messageId = headers[SqsHeaders.MessageSystemAttributes.MESSAGE_ID]?.toString() ?: headers.id.toString(),
                groupId = headers[SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER]?.toString().orEmpty(),
                receiveCount =
                    headers[SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT]
                        ?.toString()
                        ?.toIntOrNull() ?: 1,
                attributes = traceAttributes(headers),
            ),
            acknowledge = { acknowledgement.acknowledge() },
            changeVisibility = { visibility.changeTo(it) },
        )
    }

    /** Message attributes originais (contexto de trace); cabecalhos internos do framework e do SQS ficam de fora. */
    private fun traceAttributes(headers: Map<String, Any>): Map<String, String> =
        headers
            .filter { (name, value) -> value is String && !name.startsWith("Sqs_") && name !in FRAMEWORK_HEADERS }
            .mapValues { it.value.toString() }

    companion object {
        private val FRAMEWORK_HEADERS = setOf("id", "timestamp", "contentType")
    }
}

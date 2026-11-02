package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.application.messaging.CommandOutcome
import com.fiap.mechanicalhub.stock.application.messaging.InvalidCommandException
import com.fiap.mechanicalhub.stock.application.messaging.ReserveStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.StockCommand
import com.fiap.mechanicalhub.stock.application.messaging.StockCommandProcessor
import com.fiap.mechanicalhub.stock.application.reservation.BusinessRuleException
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StockCommandConsumerTests {
    private val processor = mockk<StockCommandProcessor>()
    private val deadLetters = mockk<DeadLetterSender>(relaxed = true)
    private val consumer = StockCommandConsumer(StockCommandParser(JsonMapper()), processor, deadLetters)
    private val acks = mutableListOf<Unit>()
    private val visibilities = mutableListOf<Int>()

    private val orderId = UUID.randomUUID()
    private val validBody =
        """{"eventId":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}","orderId":"$orderId",
            "type":"consumeStock","reservedMaterials":[{"materialId":"${UUID.randomUUID()}","quantity":1}]}"""

    private fun consume(body: String, receiveCount: Int = 1) =
        consumer.consume(
            ReceivedCommand(body, "msg-1", orderId.toString(), receiveCount, mapOf("traceparent" to "t")),
            acknowledge = { acks += Unit },
            changeVisibility = { visibilities += it },
        )

    @Test
    fun `given a processed command when consuming then it acknowledges`() {
        every { processor.process(any<StockCommand>()) } returns CommandOutcome.PROCESSED

        consume(validBody)

        assertEquals(1, acks.size)
        assertTrue(visibilities.isEmpty())
    }

    @Test
    fun `given a transient error when consuming then it keeps the message and applies the backoff by receive count`() {
        every { processor.process(any<StockCommand>()) } throws TransientStockException("lock_timeout")

        (1..7).forEach { consume(validBody, it) }

        assertTrue(acks.isEmpty())
        assertEquals(listOf(1, 2, 4, 8, 8, 8, 8), visibilities)
        verify(exactly = 0) { deadLetters.send(any(), any(), any(), any()) }
    }

    @Test
    fun `given an invalid payload when consuming then it goes to the dlq with error attributes and is acknowledged`() {
        consume("not json")

        verify {
            deadLetters.send(
                "not json",
                orderId.toString(),
                "msg-1",
                match {
                    it["errorClass"] == "com.fiap.mechanicalhub.stock.application.messaging.InvalidCommandException" &&
                        it["traceparent"] == "t"
                },
            )
        }
        assertEquals(1, acks.size)
        assertTrue(visibilities.isEmpty())
    }

    @Test
    fun `given a business rule violation when consuming then it goes to the dlq using the eventId and is acknowledged`() {
        every { processor.process(any<StockCommand>()) } throws BusinessRuleException("RESERVED insuficiente")

        consume(validBody)

        verify { deadLetters.send(validBody, orderId.toString(), not("msg-1"), match { it["errorMessage"] == "RESERVED insuficiente" }) }
        assertEquals(1, acks.size)
    }

    @Test
    fun `given a dlq failure when sending a permanent error then the message is not acknowledged`() {
        every { deadLetters.send(any(), any(), any(), any()) } throws IllegalStateException("sqs down")

        consume("{}", 2)

        assertTrue(acks.isEmpty())
        assertEquals(listOf(2), visibilities)
    }

    @Test
    fun `given each command type when parsing then it builds the matching command with the idempotency key`() {
        val parser = StockCommandParser(JsonMapper())
        val tx = UUID.randomUUID()
        val repair = UUID.randomUUID()

        val command =
            parser.parse(
                """{"transactionId":"$tx","orderId":"$orderId","repairs":[{"repairId":"$repair"}],"idempotencyKey":"$tx:RESERVE_STOCK"}""",
            )

        assertEquals("$tx:RESERVE_STOCK", command.idempotencyKey)
        assertEquals(listOf(repair), (command as com.fiap.mechanicalhub.stock.application.messaging.ReserveStockCommand).repairIds)
    }
}

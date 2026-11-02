package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.TestcontainersConfiguration
import io.awspring.cloud.sqs.operations.SqsTemplate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class OutboxRelayTests {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanOutbox() {
        jdbc.update("DELETE FROM outbox_events")
    }

    private fun insertEvent(orderId: UUID = UUID.randomUUID()): UUID {
        val eventId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO outbox_events (event_id, aggregate_id, event_type, payload) VALUES (?, ?, 'stockPending', ?::jsonb)",
            eventId,
            orderId,
            """{"eventId":"$eventId","orderId":"$orderId","type":"stockPending"}""",
        )
        return eventId
    }

    private fun status(eventId: UUID) =
        jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?", String::class.java, eventId)

    @Test
    fun `given pending events when the relay runs then they are sent in order and marked as sent`() {
        val sentOrder = mutableListOf<UUID>()
        val relay = OutboxRelay(jdbc, dataSource, { sentOrder += it.eventId }, 100)
        val first = insertEvent()
        val second = insertEvent()

        assertEquals(2, relay.relay())

        assertEquals(listOf(first, second), sentOrder)
        assertEquals("SENT", status(first))
        assertEquals("SENT", status(second))
        assertEquals(0, relay.relay())
    }

    @Test
    fun `given a send failure when the relay runs then the batch stops and attempts are incremented`() {
        val failing = OutboxSender { error("sqs down") }
        val first = insertEvent()
        val second = insertEvent()

        assertEquals(0, OutboxRelay(jdbc, dataSource, failing, 100).relay())

        assertEquals("PENDING", status(first))
        assertEquals("PENDING", status(second))
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE event_id = ?", Int::class.java, first))
        assertEquals("sqs down", jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE event_id = ?", String::class.java, first))
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE event_id = ?", Int::class.java, second))
    }

    @Test
    fun `given the leader lock held by another session when the relay runs then it sends nothing`() {
        insertEvent()
        dataSource.connection.use { other ->
            other.createStatement().use { it.execute("SELECT pg_advisory_lock(${OutboxRelay.LEADER_LOCK_KEY})") }
            try {
                assertEquals(0, OutboxRelay(jdbc, dataSource, { error("must not send") }, 100).relay())
            } finally {
                other.createStatement().use { it.execute("SELECT pg_advisory_unlock(${OutboxRelay.LEADER_LOCK_KEY})") }
            }
        }
    }

    @Test
    fun `given a pending event when the relay sends through sqs then the fifo message carries group and payload`() {
        val orderId = UUID.randomUUID()
        val eventId = insertEvent(orderId)
        val sender = SqsOutboxSender(SqsTemplate.newTemplate(sqsClient), queueUrl)

        assertEquals(1, OutboxRelay(jdbc, dataSource, sender, 100).relay())

        val message =
            sqsClient
                .receiveMessage(
                    ReceiveMessageRequest
                        .builder()
                        .queueUrl(queueUrl)
                        .waitTimeSeconds(5)
                        .messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID)
                        .build(),
                ).join()
                .messages()
                .firstOrNull()
        assertNotNull(message)
        assertEquals(orderId.toString(), message.attributes()[MessageSystemAttributeName.MESSAGE_GROUP_ID])
        assertEquals(true, message.body().contains(eventId.toString()), message.body())
        assertEquals("SENT", status(eventId))
    }

    companion object {
        private val localstack =
            LocalStackContainer(
                DockerImageName.parse("localstack/localstack:4"),
            ).withServices(LocalStackContainer.Service.SQS)
        private lateinit var sqsClient: SqsAsyncClient
        private lateinit var queueUrl: String

        @JvmStatic
        @BeforeAll
        fun startLocalstack() {
            localstack.start()
            sqsClient =
                SqsAsyncClient
                    .builder()
                    .endpointOverride(localstack.endpoint)
                    .region(Region.of(localstack.region))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)),
                    ).build()
            queueUrl =
                sqsClient
                    .createQueue(
                        CreateQueueRequest
                            .builder()
                            .queueName("orchestrator-events-test.fifo")
                            .attributes(
                                mapOf(
                                    QueueAttributeName.FIFO_QUEUE to "true",
                                    QueueAttributeName.CONTENT_BASED_DEDUPLICATION to "false",
                                ),
                            ).build(),
                    ).join()
                    .queueUrl()
        }

        @JvmStatic
        @AfterAll
        fun stopLocalstack() {
            localstack.stop()
        }
    }
}

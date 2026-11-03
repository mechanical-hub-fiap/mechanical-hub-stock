package com.fiap.mechanicalhub.stock.infrastructure.reservation

import com.fiap.mechanicalhub.stock.TestcontainersConfiguration
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Tag("integration")
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class JdbcStockLocksTests {
    @Autowired
    private lateinit var locks: JdbcStockLocks

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var tx: TransactionTemplate

    private fun materialIds(): List<UUID> = jdbc.queryForList("SELECT id FROM materials ORDER BY id", UUID::class.java).filterNotNull()

    @Test
    fun `given locked materials when another transaction locks them then it fails as transient after the timeout`() {
        val ids = materialIds().take(2)
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            tx.executeWithoutResult {
                locks.lockMaterials(ids.reversed())
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        holding.await(10, TimeUnit.SECONDS)

        val error =
            assertFailsWith<TransientStockException> {
                tx.executeWithoutResult {
                    jdbc.execute("SET LOCAL lock_timeout = '300ms'")
                    locks.lockMaterials(ids)
                }
            }
        release.countDown()
        pool.shutdown()

        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun `given the order lock held when another transaction locks the same order then it waits and fails on timeout`() {
        val orderId = UUID.randomUUID()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            tx.executeWithoutResult {
                locks.lockOrder(orderId)
                holding.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        holding.await(10, TimeUnit.SECONDS)

        assertFailsWith<TransientStockException> {
            tx.executeWithoutResult {
                jdbc.execute("SET LOCAL lock_timeout = '300ms'")
                locks.lockOrder(orderId)
            }
        }
        release.countDown()
        pool.shutdown()
    }

    @Test
    fun `given pending items of an order when counting under the order lock then returns the count`() {
        val orderId = UUID.randomUUID()
        val materialId = materialIds().first()
        jdbc.update(
            "INSERT INTO stock_pending_items (service_order_id, saga_transaction_id, material_id, quantity) VALUES (?, ?, ?, 2)",
            orderId,
            UUID.randomUUID(),
            materialId,
        )

        val count =
            tx.execute {
                locks.lockOrder(orderId)
                locks.countPendingItems(orderId)
            }

        assertEquals(1, count)
        jdbc.update("DELETE FROM stock_pending_items WHERE service_order_id = ?", orderId)
    }

    @Test
    fun `given the lock timeout applied when reading it in the same transaction then it is 5s`() {
        val timeout =
            tx.execute {
                locks.applyLockTimeout()
                jdbc.queryForObject("SHOW lock_timeout", String::class.java)
            }

        assertEquals("5s", timeout)
    }
}

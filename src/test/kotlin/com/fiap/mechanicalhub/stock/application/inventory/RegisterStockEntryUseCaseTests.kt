package com.fiap.mechanicalhub.stock.application.inventory

import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyKeyReusedException
import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyStore
import com.fiap.mechanicalhub.stock.application.idempotency.StoredEntryResponse
import com.fiap.mechanicalhub.stock.application.reservation.PendingItemsResolver
import com.fiap.mechanicalhub.stock.application.reservation.StockLocks
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegisterStockEntryUseCaseTests {
    private val stock = mockk<StockRepository>(relaxed = true)
    private val locks = mockk<StockLocks>(relaxed = true)
    private val idempotency = mockk<IdempotencyStore>(relaxed = true)
    private val useCase = RegisterStockEntryUseCase(stock, locks, idempotency, PendingItemsResolver(stock, locks, mockk(relaxed = true)))
    private val materialId = UUID.randomUUID()

    @Test
    fun `given a transient lock failure when registering an entry then it propagates and stores nothing`() {
        every { idempotency.findLocking(any()) } returns null
        every { stock.materialExists(materialId) } returns true
        every { locks.lockMaterials(any()) } throws TransientStockException("lock_timeout")

        assertFailsWith<TransientStockException> {
            useCase.execute(RegisterStockEntryCommand("key", materialId, 1))
        }

        verify(exactly = 0) { idempotency.save(any(), any()) }
    }

    @Test
    fun `given a stored response with another hash when registering then it fails as reused`() {
        every { idempotency.findLocking("key") } returns
            StoredEntryResponse("other", 201, StockEntryResult(materialId, 1, 1, emptyList()))

        assertFailsWith<IdempotencyKeyReusedException> {
            useCase.execute(RegisterStockEntryCommand("key", materialId, 1))
        }
    }

    @Test
    fun `given a stored response with the same hash when registering then it replays without touching the stock`() {
        val first = slotResponse()
        every { idempotency.findLocking("key") } returns first

        val outcome = useCase.execute(RegisterStockEntryCommand("key", materialId, 1))

        assertEquals(first.result, outcome.result)
        verify(exactly = 0) { locks.lockMaterials(any()) }
    }

    private fun slotResponse() =
        StoredEntryResponse(
            requestHash =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest("$materialId:1".toByteArray())
                    .joinToString("") { "%02x".format(it) },
            status = 201,
            result = StockEntryResult(materialId, 1, 1, emptyList()),
        )
}

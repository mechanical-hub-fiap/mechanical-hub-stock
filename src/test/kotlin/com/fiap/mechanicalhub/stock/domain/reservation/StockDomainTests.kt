package com.fiap.mechanicalhub.stock.domain.reservation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class StockDomainTests {
    private val materialA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val materialB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    @Test
    fun `given reparos com materiais repetidos when somar then agrupa por material em ordem crescente`() {
        val items = listOf(MaterialQuantity(materialB, 1), MaterialQuantity(materialA, 2), MaterialQuantity(materialB, 3))

        val result = items.sumByMaterial()

        assertThat(result).containsExactly(MaterialQuantity(materialA, 2), MaterialQuantity(materialB, 4))
    }

    @Test
    fun `given saldo menor que necessario when reservar then reserva o disponivel e registra o faltante`() {
        val result = reserveStock(MaterialQuantity(materialA, 5), available = 3)

        assertThat(result).isEqualTo(StockReservation(materialA, reserved = 3, missing = 2))
    }

    @Test
    fun `given saldo suficiente when reservar then nao ha faltante`() {
        val result = reserveStock(MaterialQuantity(materialA, 5), available = 10)

        assertThat(result).isEqualTo(StockReservation(materialA, reserved = 5, missing = 0))
    }

    @Test
    fun `given saldo zero when reservar then tudo vira faltante`() {
        val result = reserveStock(MaterialQuantity(materialA, 5), available = 0)

        assertThat(result).isEqualTo(StockReservation(materialA, reserved = 0, missing = 5))
    }

    @Test
    fun `given pendencias de varias ordens when resolver then atende por antiguidade e para quando o saldo zera`() {
        val first = pending(quantity = 4, createdAt = 1)
        val second = pending(quantity = 5, createdAt = 2)
        val third = pending(quantity = 2, createdAt = 3)

        val result = resolvePendingItems(listOf(third, second, first), available = 7)

        assertThat(result.resolutions).containsExactly(
            PendingResolution(first, reserved = 4),
            PendingResolution(second, reserved = 3),
        )
        assertThat(result.resolutions.map { it.remaining }).containsExactly(0, 2)
        assertThat(result.availableAfter).isZero()
    }

    @Test
    fun `given saldo maior que pendencias when resolver then sobra saldo disponivel`() {
        val result = resolvePendingItems(listOf(pending(quantity = 2, createdAt = 1)), available = 5)

        assertThat(result.availableAfter).isEqualTo(3)
    }

    @Test
    fun `given mesmo createdAt when resolver then desempata por id`() {
        val low = pending(quantity = 1, createdAt = 1, id = "00000000-0000-0000-0000-000000000001")
        val high = pending(quantity = 1, createdAt = 1, id = "00000000-0000-0000-0000-000000000002")

        val result = resolvePendingItems(listOf(high, low), available = 1)

        assertThat(result.resolutions.map { it.item }).containsExactly(low)
    }

    @Test
    fun `given movimentacoes RESERVED da ordem when montar reservedMaterials then soma por material`() {
        val movements = listOf(MaterialQuantity(materialA, 2), MaterialQuantity(materialA, 1))

        val result = movements.sumByMaterial()

        assertThat(result).containsExactly(MaterialQuantity(materialA, 3))
    }

    private fun pending(
        quantity: Int,
        createdAt: Long,
        id: String = UUID.randomUUID().toString(),
    ) = PendingItem(
        id = UUID.fromString(id),
        orderId = UUID.randomUUID(),
        materialId = materialA,
        quantity = quantity,
        createdAt = Instant.ofEpochSecond(createdAt),
        sagaTransactionId = UUID.randomUUID(),
    )
}

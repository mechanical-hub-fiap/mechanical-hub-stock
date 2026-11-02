package com.fiap.mechanicalhub.stock.domain.reservation

import java.time.Instant
import java.util.UUID

data class PendingItem(
    val id: UUID,
    val orderId: UUID,
    val materialId: UUID,
    val quantity: Int,
    val createdAt: Instant,
    val sagaTransactionId: UUID,
)

/** Reserva feita a um item pendente; [remaining] e o que continua pendente (0 = item resolvido). */
data class PendingResolution(val item: PendingItem, val reserved: Int) {
    val remaining: Int get() = item.quantity - reserved
    val resolved: Boolean get() = remaining == 0
}

data class PendingResolutionResult(val resolutions: List<PendingResolution>, val availableAfter: Int)

/**
 * Resolve pendencias de um material por antiguidade (createdAt, id): reserva min(disponivel, item.quantity)
 * e para quando o disponivel chega a 0.
 */
fun resolvePendingItems(pendingItems: List<PendingItem>, available: Int): PendingResolutionResult {
    val ordered = pendingItems.sortedWith(compareBy<PendingItem> { it.createdAt }.thenBy { it.id })
    val availableBefore =
        ordered.runningFold(available) { remaining, item -> remaining - minOf(remaining, item.quantity) }
    val attempts =
        ordered.zip(availableBefore) { item, remaining -> PendingResolution(item, minOf(remaining, item.quantity)) }
    val resolutions = attempts.filter { it.reserved > 0 }
    return PendingResolutionResult(resolutions, available - resolutions.sumOf { it.reserved })
}

package com.fiap.mechanicalhub.stock.infrastructure.inventory

import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyKeyReusedException
import com.fiap.mechanicalhub.stock.application.inventory.MaterialBalance
import com.fiap.mechanicalhub.stock.application.inventory.MaterialNotFoundException
import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryCommand
import com.fiap.mechanicalhub.stock.application.inventory.RegisterStockEntryUseCase
import com.fiap.mechanicalhub.stock.application.inventory.StockEntryResult
import com.fiap.mechanicalhub.stock.application.inventory.StockMovementView
import com.fiap.mechanicalhub.stock.application.inventory.StockQueries
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.FlowStep
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class StockEntryRequest(
    @field:NotNull val materialId: UUID?,
    @field:Min(1) val quantity: Int,
)

@RestController
class StockController(
    private val registerStockEntry: RegisterStockEntryUseCase,
    private val queries: StockQueries,
) {
    /**
     * Etapa `REGISTER_STOCK_ENTRY` (TD_PendenciaEstoque P4). A entrada nao pertence a uma ordem; cada ordem cuja
     * pendencia e atendida ganha as proprias linhas `RESERVE_STOCK` (PendingItemsResolver).
     */
    @PostMapping("/stock/entry")
    @Suppress("TooGenericExceptionCaught")
    fun entry(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: StockEntryRequest,
    ): ResponseEntity<StockEntryResult> {
        val step = FlowLog.begin(log, null, STATUS)
        step.start("Entrada de material recebida", "materialId" to request.materialId, "quantity" to request.quantity)
        try {
            require(idempotencyKey.isNotBlank()) { "Idempotency-Key obrigatoria" }
            val outcome =
                registerStockEntry.execute(
                    RegisterStockEntryCommand(idempotencyKey, requireNotNull(request.materialId), request.quantity),
                )
            step.end(
                "Entrada de material registrada",
                "availableQty" to outcome.result.available,
                "pendingResolvedOrderIds" to outcome.result.reservedOrderIds,
                "httpStatus" to outcome.status,
            )
            return ResponseEntity.status(outcome.status).body(outcome.result)
        } catch (e: Exception) {
            logFailure(step, e)
            throw e
        }
    }

    /** Rejeicao do cliente e falha transitoria (cliente repete com a mesma chave) sao WARN; o resto e ERROR. */
    private fun logFailure(step: FlowStep, error: Exception) {
        when (error) {
            is MaterialNotFoundException, is IdempotencyKeyReusedException, is IllegalArgumentException -> {
                step.warnFail("Entrada de material rejeitada", error)
            }

            is TransientStockException, is PessimisticLockingFailureException -> {
                step.warnFail("Falha transitoria ao registrar a entrada, o cliente pode repetir", error)
            }

            else -> {
                step.errorFail("Falha inesperada ao registrar a entrada de material", error)
            }
        }
    }

    @GetMapping("/stock")
    fun balances(): List<MaterialBalance> = queries.balances()

    @GetMapping("/stock/{materialId}/movements")
    fun movements(
        @PathVariable materialId: UUID,
    ): List<StockMovementView> = queries.movements(materialId)

    private companion object {
        const val STATUS = "REGISTER_STOCK_ENTRY"
        val log = LoggerFactory.getLogger(StockController::class.java)
    }
}

package com.fiap.mechanicalhub.stock.infrastructure.messaging

import com.fiap.mechanicalhub.stock.application.messaging.ConsumeStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.InvalidCommandException
import com.fiap.mechanicalhub.stock.application.messaging.ReserveStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.RestoreStockCommand
import com.fiap.mechanicalhub.stock.application.messaging.StockCommand
import com.fiap.mechanicalhub.stock.domain.reservation.MaterialQuantity
import com.fiap.mechanicalhub.stock.domain.reservation.reserveStock
import com.fiap.mechanicalhub.stock.logging.FlowLog
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Converte o JSON recebido na fila em [StockCommand]; qualquer problema de contrato e [InvalidCommandException]. */
@Component
class StockCommandParser(private val json: JsonMapper) {
    @Suppress("TooGenericExceptionCaught")
    fun parse(body: String): StockCommand {
        try {
            val root = json.readTree(body)
            require(root != null && root.isObject) { "O corpo deve ser um objeto JSON" }
            val transactionId = uuid(root, "transactionId")
            val orderId = uuid(root, "orderId")
            val step = step(root)
            val key = text(root, "idempotencyKey") ?: "$transactionId:${step.name}"
            return when (step) {
                Step.RESERVE_STOCK -> {
                    ReserveStockCommand(key, transactionId, orderId, repairIds(root))
                }

                Step.CONSUME_STOCK -> {
                    ConsumeStockCommand(key, transactionId, orderId, materials(root))
                }

                Step.RESTORE_STOCK -> {
                    RestoreStockCommand(key, transactionId, orderId, materials(root))
                }
            }
        } catch (e: InvalidCommandException) {
            throw e
        } catch (e: Exception) {
            throw InvalidCommandException("Comando invalido: ${e.message}", e)
        }
    }

    /** eventId do envelope, se o corpo for legivel; usado como MessageDeduplicationId na DLQ. */
    fun eventIdOrNull(body: String): String? = fieldOrNull(body, "eventId")

    /** Campo texto do envelope sem validar o contrato (null se ausente ou ilegivel); usado nos logs. */
    @Suppress("TooGenericExceptionCaught")
    fun fieldOrNull(body: String, field: String): String? =
        try {
            text(json.readTree(body), field)
        } catch (_: Exception) {
            null
        }

    /** `status` do log (logging-standard §3): pelo `type` ou pelo passo da `idempotencyKey`. */
    fun statusOrNull(body: String): String? =
        fieldOrNull(body, "type")?.let(FlowLog::statusOf)
            ?: fieldOrNull(body, "idempotencyKey")?.substringAfterLast(':')

    private enum class Step(val type: String) {
        RESERVE_STOCK("reserveStock"),
        CONSUME_STOCK("consumeStock"),
        RESTORE_STOCK("restoreStock"),
    }

    private fun step(root: JsonNode): Step {
        val type = text(root, "type") ?: text(root, "idempotencyKey")?.substringAfterLast(':')
        return Step.entries.firstOrNull { it.type == type || it.name == type }
            ?: throw InvalidCommandException("Tipo de comando desconhecido: $type")
    }

    private fun text(root: JsonNode?, field: String): String? =
        root
            ?.get(field)
            ?.takeIf { it.isString }
            ?.asString()
            ?.takeIf { it.isNotBlank() }

    private fun uuid(root: JsonNode, field: String): UUID =
        UUID.fromString(text(root, field) ?: throw InvalidCommandException("Campo obrigatorio ausente: $field"))

    private fun array(root: JsonNode, field: String): JsonNode =
        root.get(field)?.takeIf { it.isArray } ?: throw InvalidCommandException("Campo obrigatorio ausente: $field")

    private fun repairIds(root: JsonNode): List<UUID> = array(root, "repairs").values().map { uuid(it, "repairId") }

    private fun materials(root: JsonNode): List<MaterialQuantity> =
        array(root, "reservedMaterials").values().map { item ->
            val quantity = item.get("quantity")
            if (quantity == null || !quantity.isInt) throw InvalidCommandException("quantity invalida")
            MaterialQuantity(uuid(item, "materialId"), quantity.asInt())
        }
}

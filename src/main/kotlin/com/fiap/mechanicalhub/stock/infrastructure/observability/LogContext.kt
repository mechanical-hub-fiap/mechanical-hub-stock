package com.fiap.mechanicalhub.stock.infrastructure.observability

import org.slf4j.MDC

/** Chaves de MDC exportadas nos logs JSON. */
object LogContext {
    const val ORDER_ID = "orderId"
    const val TRANSACTION_ID = "transactionId"
    const val EVENT_ID = "eventId"
    const val MATERIAL_ID = "materialId"
}

/** Executa o bloco com as chaves no MDC (valores nulos sao ignorados) e restaura o estado anterior ao final. */
fun <T> withLogContext(vararg entries: Pair<String, String?>, block: () -> T): T {
    val previous = entries.associate { (key, _) -> key to MDC.get(key) }
    entries.forEach { (key, value) -> if (value != null) MDC.put(key, value) }
    try {
        return block()
    } finally {
        previous.forEach { (key, old) -> if (old == null) MDC.remove(key) else MDC.put(key, old) }
    }
}

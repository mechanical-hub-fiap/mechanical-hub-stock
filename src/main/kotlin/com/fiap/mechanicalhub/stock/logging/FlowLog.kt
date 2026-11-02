package com.fiap.mechanicalhub.stock.logging

import org.slf4j.Logger
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Nível da linha: WARN para falha que vai ser tentada de novo, ERROR para falha permanente (logging-standard §5). */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** Em que ponto da etapa o serviço está (logging-standard §2). */
enum class Stage { START, END, FAIL }

/**
 * Campos de uma linha no padrão de `engineering/logging-standard.md`. A mensagem sai em logfmt, montada com
 * placeholders `{}` na ordem `orderId status stage attempt durationMs <contexto> msg ex exMsg`; chaves sem valor
 * são omitidas.
 */
data class FlowFields(
    val orderId: Any?,
    val status: String?,
    val msg: String,
    val stage: Stage? = null,
    val attempt: Int? = null,
    val durationMs: Long? = null,
    val context: List<Pair<String, Any?>> = emptyList(),
    val error: Throwable? = null,
)

/**
 * Logs de etapa da transação distribuída. A borda que recebe a mensagem ou a requisição (listener SQS, controller,
 * webhook, job) abre a etapa com [begin], loga `START` e termina com `END` ou `FAIL`. Enquanto a etapa está aberta
 * ela fica associada à thread, e o caso de uso acrescenta campos de contexto e a `msg` do `END` com [detail] e
 * [endMessage], sem conhecer o logger da borda.
 */
object FlowLog {
    const val ORDER_ID_KEY = "orderId"

    private val current = ThreadLocal<FlowStep?>()
    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")

    /** Abre a etapa na thread atual. Quem abre loga `START` e garante o `END` ou o `FAIL`. */
    fun begin(
        log: Logger,
        orderId: Any?,
        status: String?,
        attempt: Int? = null,
        clock: Clock = Clock.systemUTC(),
    ): FlowStep = FlowStep(log, orderId?.toString(), status, attempt, clock).also { current.set(it) }

    /** Etapa aberta na thread atual, se houver. */
    fun current(): FlowStep? = current.get()

    /** Acrescenta campos de contexto (logging-standard §2.1) à etapa aberta; sem efeito fora de uma etapa. */
    fun detail(vararg fields: Pair<String, Any?>) {
        current.get()?.detail(fields.asList())
    }

    /** Define a `msg` do `END` da etapa aberta (o caso de uso sabe o que de fato aconteceu). */
    fun endMessage(msg: String) {
        current.get()?.endMessage(msg)
    }

    /** Ordem descoberta depois do `START` (webhook, job): passa a sair nas linhas seguintes e no MDC. */
    fun assignOrder(orderId: Any?) {
        current.get()?.assignOrder(orderId)
    }

    /** Linha avulsa da etapa aberta (mesmo `orderId` e `status`), sem `stage`: chamada externa, detalhe, alerta. */
    fun note(
        log: Logger,
        level: LogLevel,
        msg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val step = current.get()
        val orderId = step?.orderId ?: MDC.get(ORDER_ID_KEY)
        write(log, level, FlowFields(orderId, step?.status, msg, context = fields.asList()))
    }

    /** Status do log a partir do tipo da mensagem: `generatePaymentLink` vira `GENERATE_PAYMENT_LINK` (§3). */
    fun statusOf(messageType: String): String = messageType.replace(CAMEL_HUMP, "\$1_\$2").uppercase()

    fun write(
        log: Logger,
        level: LogLevel,
        fields: FlowFields,
    ) {
        val builder =
            when (level) {
                LogLevel.DEBUG -> log.atDebug()
                LogLevel.INFO -> log.atInfo()
                LogLevel.WARN -> log.atWarn()
                LogLevel.ERROR -> log.atError()
            }
        val (format, arguments) = LogFormat.format(fields)
        builder.setMessage(format)
        arguments.forEach { builder.addArgument(it) }
        // Só a falha permanente leva a exceção: o encoder gera o stack_trace (logging-standard §8).
        if (level == LogLevel.ERROR) fields.error?.let { builder.setCause(it) }
        withOrderInMdc(fields.orderId?.toString()) { builder.log() }
    }

    /**
     * O `orderId` do JSON (MDC) precisa ser o mesmo da mensagem: com valores diferentes o `| logfmt` do Loki
     * renomeia o campo extraído. Linhas sobre outra ordem (ex.: pendência de outra ordem resolvida por uma
     * devolução) trocam o MDC só durante a escrita.
     */
    private fun withOrderInMdc(
        orderId: String?,
        block: () -> Unit,
    ) {
        val previous = MDC.get(ORDER_ID_KEY)
        if (orderId == null || orderId == previous) return block()
        MDC.put(ORDER_ID_KEY, orderId)
        try {
            block()
        } finally {
            if (previous == null) MDC.remove(ORDER_ID_KEY) else MDC.put(ORDER_ID_KEY, previous)
        }
    }

    internal fun release(step: FlowStep) {
        if (current.get() === step) current.remove()
    }
}

/** Etapa em andamento: guarda o instante do `START` (para o `durationMs`) e o contexto acumulado até o `END`. */
class FlowStep internal constructor(
    private val log: Logger,
    orderId: String?,
    val status: String?,
    val attempt: Int?,
    private val clock: Clock,
) {
    var orderId: String? = orderId
        private set

    private val startedAt: Instant = clock.instant()
    private val context = LinkedHashMap<String, Any?>()
    private var endMsg: String? = null

    fun assignOrder(orderId: Any?) {
        if (orderId == null) return
        this.orderId = orderId.toString()
        MDC.put(FlowLog.ORDER_ID_KEY, orderId.toString())
    }

    fun detail(fields: List<Pair<String, Any?>>): FlowStep {
        fields.forEach { (key, value) -> context[key] = value }
        return this
    }

    fun endMessage(msg: String): FlowStep {
        endMsg = msg
        return this
    }

    /** `START`; os campos informados aqui também saem no `END`/`FAIL` da etapa. */
    fun start(
        msg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.INFO, Stage.START, msg, fields.asList(), null)
    }

    /** `END` com o contexto acumulado; a `msg` definida pelo caso de uso tem prioridade sobre [defaultMsg]. */
    fun end(
        defaultMsg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.INFO, Stage.END, endMsg ?: defaultMsg, context.toList(), null)
        FlowLog.release(this)
    }

    /** `FAIL` que vai ser tentado de novo (ou rejeição que não exige intervenção): WARN, sem stack trace. */
    fun warnFail(
        msg: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.WARN, Stage.FAIL, msg, context.toList(), error)
        FlowLog.release(this)
    }

    /** `FAIL` permanente (DLQ, alerta, intervenção): ERROR com stack trace. */
    fun errorFail(
        msg: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.ERROR, Stage.FAIL, msg, context.toList(), error)
        FlowLog.release(this)
    }

    fun elapsedMs(): Long = Duration.between(startedAt, clock.instant()).toMillis()

    private fun emit(
        level: LogLevel,
        stage: Stage,
        msg: String,
        fields: List<Pair<String, Any?>>,
        error: Throwable?,
    ) {
        val duration = if (stage == Stage.START) null else elapsedMs()
        FlowLog.write(log, level, FlowFields(orderId, status, msg, stage, attempt, duration, fields, error))
    }
}

/** Montagem da mensagem logfmt e higienização dos valores (logging-standard §1 e §6). */
internal object LogFormat {
    private const val OWN_PACKAGE = "com.fiap.mechanicalhub"
    private const val MAX_TEXT = 300
    private const val MAX_CAUSES = 10
    private val WHITESPACE = Regex("\\s+")

    fun format(fields: FlowFields): Pair<String, List<Any?>> {
        val keys = mutableListOf<String>()
        val arguments = mutableListOf<Any?>()
        val plain =
            listOf(
                "orderId" to fields.orderId,
                "status" to fields.status,
                "stage" to fields.stage,
                "attempt" to fields.attempt,
                "durationMs" to fields.durationMs,
            ) + fields.context
        plain.forEach { (key, value) ->
            render(value)?.let {
                keys += "$key={}"
                arguments += it
            }
        }
        keys += "msg=\"{}\""
        arguments += quote(fields.msg)
        fields.error?.let { error ->
            keys += "ex={}"
            arguments += error.javaClass.simpleName
            keys += "exMsg=\"{}\""
            arguments += quote(safeMessage(error))
        }
        return keys.joinToString(" ") to arguments
    }

    /** Valores sem espaço; listas separadas por vírgula; nulo e lista vazia omitem a chave. */
    private fun render(value: Any?): String? =
        when (value) {
            null -> null
            is BigDecimal -> value.toPlainString()
            is Collection<*> -> value.filterNotNull().joinToString(",") { clean(it.toString()) }.ifEmpty { null }
            else -> clean(value.toString()).ifEmpty { null }
        }

    private fun clean(text: String): String = text.replace(WHITESPACE, "_").replace('"', '\'')

    private fun quote(text: String): String = text.replace(WHITESPACE, " ").replace('"', '\'').take(MAX_TEXT)

    /**
     * Mensagem de exceção sem dados de terceiros: usa a primeira mensagem de uma exceção do próprio serviço na
     * cadeia de causas; exceção só de biblioteca externa vira um texto próprio com o nome da classe (§6).
     */
    private fun safeMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .firstOrNull { it.javaClass.name.startsWith(OWN_PACKAGE) && !it.message.isNullOrBlank() }
            ?.message
            ?: "Falha em ${error.javaClass.simpleName}"
}

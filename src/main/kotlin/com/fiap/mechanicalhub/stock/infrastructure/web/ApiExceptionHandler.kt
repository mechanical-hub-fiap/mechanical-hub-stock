package com.fiap.mechanicalhub.stock.infrastructure.web

import com.fiap.mechanicalhub.stock.application.idempotency.IdempotencyKeyReusedException
import com.fiap.mechanicalhub.stock.application.inventory.MaterialNotFoundException
import com.fiap.mechanicalhub.stock.application.reservation.TransientStockException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

data class ErrorResponse(val code: String, val message: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(
        MissingRequestHeaderException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun badRequest(): ResponseEntity<ErrorResponse> =
        error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Requisicao invalida")

    @ExceptionHandler(MaterialNotFoundException::class)
    fun notFound(e: MaterialNotFoundException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.NOT_FOUND, "MATERIAL_NOT_FOUND", e.message.orEmpty())

    @ExceptionHandler(IdempotencyKeyReusedException::class)
    fun unprocessable(e: IdempotencyKeyReusedException): ResponseEntity<ErrorResponse> =
        error(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED", e.message.orEmpty())

    @ExceptionHandler(TransientStockException::class, PessimisticLockingFailureException::class)
    fun unavailable(): ResponseEntity<ErrorResponse> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "TRANSIENT_FAILURE", "Falha transitoria, tente novamente")

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(ErrorResponse(code, message))
}

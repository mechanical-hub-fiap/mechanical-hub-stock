package com.fiap.mechanicalhub.stock.application.reservation

/** Violacao de regra de negocio (ex.: RESERVED insuficiente): erro permanente, vai direto a DLQ. */
class BusinessRuleException(message: String) : RuntimeException(message)

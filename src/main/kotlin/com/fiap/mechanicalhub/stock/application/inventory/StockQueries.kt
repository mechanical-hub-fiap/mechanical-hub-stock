package com.fiap.mechanicalhub.stock.application.inventory

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class StockQueries(private val stock: StockRepository) {
    @Transactional(readOnly = true)
    fun balances(): List<MaterialBalance> = stock.balances()

    @Transactional(readOnly = true)
    fun movements(materialId: UUID): List<StockMovementView> {
        if (!stock.materialExists(materialId)) throw MaterialNotFoundException(materialId)
        return stock.movements(materialId)
    }
}

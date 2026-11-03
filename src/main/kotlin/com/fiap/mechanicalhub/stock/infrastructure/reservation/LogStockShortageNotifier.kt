package com.fiap.mechanicalhub.stock.infrastructure.reservation

import com.fiap.mechanicalhub.stock.application.reservation.ShortageItem
import com.fiap.mechanicalhub.stock.application.reservation.StockShortageNotifier
import com.fiap.mechanicalhub.stock.logging.FlowFields
import com.fiap.mechanicalhub.stock.logging.FlowLog
import com.fiap.mechanicalhub.stock.logging.LogLevel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class LogStockShortageNotifier : StockShortageNotifier {
    private val log = LoggerFactory.getLogger(LogStockShortageNotifier::class.java)

    override fun notifyShortage(orderId: UUID, transactionId: UUID, shortages: List<ShortageItem>) {
        FlowLog.write(
            log,
            LogLevel.INFO,
            FlowFields(
                orderId = orderId,
                status = "RESERVE_STOCK",
                msg = "Alerta de falta de material enviado aos administradores do estoque",
                context =
                    listOf(
                        "transactionId" to transactionId,
                        "pendingMaterialIds" to shortages.map { it.materialId },
                        "missingQty" to shortages.map { it.missingQuantity },
                    ),
            ),
        )
    }
}

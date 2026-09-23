package com.elevencapital.app.purchase

import com.elevencapital.core.stock.flow.OrderSide
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseJournalTest {
    @Test
    fun `schema one records migrate to buy because they predate sell`() {
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put("userId", USER)
            .put("operationId", OPERATION)
            .put("quoteId", "quote-1")
            .put("stockId", STOCK)
            .put("phase", "TRACKING")
            .put("actionIndex", 1)
            .put("actionId", JSONObject.NULL)
            .put("transactionIds", JSONArray())

        assertEquals(OrderSide.BUY, decodePurchaseJournalRecord(legacy.toString()).side)
    }

    @Test
    fun `schema two preserves sell through durable round trip`() {
        val sell = PurchaseJournalRecord(
            userId = USER,
            operationId = OPERATION,
            quoteId = "quote-2",
            stockId = STOCK,
            phase = PurchaseJournalPhase.PROVIDER_INVOKED,
            actionIndex = 0,
            actionId = "route-1",
            transactionIds = emptyList(),
            side = OrderSide.SELL,
        )

        val encoded = encodePurchaseJournalRecord(sell)
        assertEquals(2, JSONObject(encoded).getInt("schemaVersion"))
        assertEquals("SELL", JSONObject(encoded).getString("side"))
        assertEquals(sell, decodePurchaseJournalRecord(encoded))
    }

    @Test
    fun `schema two rejects unknown side instead of guessing`() {
        val encoded = JSONObject(encodePurchaseJournalRecord(PurchaseJournalRecord(
            USER, OPERATION, "quote-3", STOCK, PurchaseJournalPhase.PLANNED,
            0, null, emptyList(), OrderSide.BUY,
        ))).put("side", "sell")

        assertThrows(PurchaseException::class.java) {
            decodePurchaseJournalRecord(encoded.toString())
        }
    }

    private companion object {
        const val USER = "did:privy:user-1"
        const val OPERATION = "123e4567-e89b-42d3-a456-426614174000"
        const val STOCK = "backpack:MSFT.US"
    }
}

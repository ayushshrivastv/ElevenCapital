package com.elevencapital.core.stock.flow

import com.elevencapital.core.stock.InMemoryStockRepository
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockFlowControllerTest {
    @Test
    fun `dynamically added stock can open detail and buy order and return to markets`() {
        val repository = InMemoryStockRepository()
        val controller = StockFlowController(repository)
        val added = stock("provider-new-stock", "NEW")
        repository.replaceCatalog(listOf(added))

        assertTrue(controller.openStock(added.id))
        assertEquals(StockDestination.Detail(added.id), controller.destination.value)
        assertEquals(added, controller.snapshot().stock)
        assertTrue(controller.openOrder(OrderSide.BUY))
        assertEquals(StockDestination.Order(added.id, OrderSide.BUY), controller.destination.value)
        assertEquals(added, controller.snapshot().stock)
        assertTrue(controller.goBack())
        assertEquals(StockDestination.Detail(added.id), controller.destination.value)
        assertTrue(controller.goBack())
        assertEquals(StockDestination.Markets, controller.destination.value)
        assertNull(controller.snapshot().stock)
        assertFalse(controller.snapshot().isStockUnavailable)
        assertFalse(controller.goBack())
    }

    @Test
    fun `unknown stock cannot open detail`() {
        val controller = StockFlowController(InMemoryStockRepository())

        assertFalse(controller.openStock(StockId("missing")))

        assertEquals(StockDestination.Markets, controller.destination.value)
        assertNull(controller.snapshot().stock)
    }

    @Test
    fun `order requires a current available detail selection`() {
        val original = stock("selected")
        val repository = InMemoryStockRepository(listOf(original))
        val controller = StockFlowController(repository)
        assertFalse(controller.openOrder(OrderSide.BUY))
        assertTrue(controller.openStock(original.id))
        repository.replaceCatalog(emptyList())

        assertFalse(controller.openOrder(OrderSide.SELL))

        assertEquals(StockDestination.Detail(original.id), controller.destination.value)
        assertTrue(controller.snapshot().isStockUnavailable)
    }

    @Test
    fun `stock selection is only available from markets`() {
        val first = stock("first")
        val second = stock("second")
        val controller = StockFlowController(InMemoryStockRepository(listOf(first, second)))
        assertTrue(controller.openStock(first.id))

        assertFalse(controller.openStock(second.id))
        assertEquals(StockDestination.Detail(first.id), controller.destination.value)
        assertTrue(controller.openOrder(OrderSide.SELL))
        assertFalse(controller.openStock(second.id))
        assertFalse(controller.openOrder(OrderSide.BUY))
        assertEquals(StockDestination.Order(first.id, OrderSide.SELL), controller.destination.value)
    }

    @Test
    fun `detail and order snapshots resolve latest stock by stable ID`() {
        val original = stock("stable-id", "BEFORE")
        val repository = InMemoryStockRepository(listOf(original))
        val controller = StockFlowController(repository)
        assertTrue(controller.openStock(original.id))
        val detailUpdate = original.copy(
            symbol = "AFTER",
            quote = original.quote.copy(price = BigDecimal("123.456789")),
        )
        repository.replaceCatalog(listOf(detailUpdate))
        assertEquals(detailUpdate, controller.snapshot().stock)
        assertTrue(controller.openOrder(OrderSide.BUY))
        val tradeUpdate = detailUpdate.copy(quote = detailUpdate.quote.copy(price = BigDecimal("124.01")))
        repository.replaceCatalog(listOf(tradeUpdate))

        assertEquals(StockDestination.Order(original.id, OrderSide.BUY), controller.destination.value)
        assertEquals(tradeUpdate, controller.snapshot().stock)
    }

    @Test
    fun `removed stock stays selected through back navigation and can become available again`() {
        val original = stock("removed")
        val repository = InMemoryStockRepository(listOf(original))
        val controller = StockFlowController(repository)
        assertTrue(controller.openStock(original.id))
        assertTrue(controller.openOrder(OrderSide.SELL))
        repository.replaceCatalog(emptyList())

        assertEquals(StockDestination.Order(original.id, OrderSide.SELL), controller.destination.value)
        assertNull(controller.snapshot().stock)
        assertTrue(controller.snapshot().isStockUnavailable)
        assertTrue(controller.goBack())
        assertEquals(StockDestination.Detail(original.id), controller.destination.value)
        assertTrue(controller.snapshot().isStockUnavailable)
        repository.replaceCatalog(listOf(original))
        assertEquals(original, controller.snapshot().stock)
        assertFalse(controller.snapshot().isStockUnavailable)
    }

    @Test
    fun `observers receive route changes quote changes and stock removal`() = runBlocking {
        withTimeout(5_000) {
            val original = stock("observed")
            val updated = original.copy(quote = original.quote.copy(price = BigDecimal("110.29")))
            val repository = InMemoryStockRepository(listOf(original))
            val controller = StockFlowController(repository)
            val snapshots = Channel<StockFlowSnapshot>(Channel.UNLIMITED)
            val collector = launch {
                controller.observeSnapshots().collect { snapshots.send(it) }
            }
            try {
                assertEquals(StockFlowSnapshot(StockDestination.Markets, null), snapshots.receive())
                assertTrue(controller.openStock(original.id))
                assertEquals(StockFlowSnapshot(StockDestination.Detail(original.id), original), snapshots.receive())
                repository.replaceCatalog(listOf(updated))
                assertEquals(StockFlowSnapshot(StockDestination.Detail(original.id), updated), snapshots.receive())
                assertTrue(controller.openOrder(OrderSide.BUY))
                assertEquals(StockFlowSnapshot(StockDestination.Order(original.id, OrderSide.BUY), updated), snapshots.receive())
                repository.replaceCatalog(emptyList())
                val removed = snapshots.receive()
                assertEquals(StockDestination.Order(original.id, OrderSide.BUY), removed.destination)
                assertNull(removed.stock)
                assertTrue(removed.isStockUnavailable)
            } finally {
                collector.cancel()
                snapshots.close()
            }
        }
    }

    private fun stock(id: String, symbol: String = "TEST"): Stock = Stock(
        id = StockId(id),
        symbol = symbol,
        name = "Test Company",
        quote = StockQuote(price = BigDecimal("100.00"), currencyCode = "USD"),
    )
}

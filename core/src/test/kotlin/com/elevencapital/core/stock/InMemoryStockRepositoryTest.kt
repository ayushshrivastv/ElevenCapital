package com.elevencapital.core.stock

import com.elevencapital.core.stock.sample.sampleStockCatalog
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class InMemoryStockRepositoryTest {
    @Test
    fun `any new stock can be added as data`() {
        val first = sampleStockCatalog().single()
        val repository = InMemoryStockRepository(listOf(first))
        val added = stock("provider-new-stock", "NEW", "New Company")

        repository.replaceCatalog(listOf(first, added))

        assertEquals(listOf(first, added), repository.catalog.value)
        assertEquals(added, repository.getStock(added.id))
    }

    @Test
    fun `catalog and detail observers receive quote updates`() = runBlocking {
        withTimeout(5_000) {
            val original = stock("test-stock")
            val updated = original.copy(quote = original.quote.copy(price = BigDecimal("101.37")))
            val repository = InMemoryStockRepository(listOf(original))
            val catalogs = mutableListOf<List<Stock>>()
            val details = mutableListOf<Stock?>()
            val catalogJob = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.catalog.take(2).collect { catalogs.add(it) }
            }
            val detailJob = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeStock(original.id).take(2).collect { details.add(it) }
            }

            repository.replaceCatalog(listOf(updated))
            catalogJob.join()
            detailJob.join()

            assertEquals(listOf(listOf(original), listOf(updated)), catalogs)
            assertEquals(listOf(original, updated), details)
            assertEquals(updated, repository.getStock(original.id))
        }
    }

    @Test
    fun `duplicate IDs are rejected on construction and replacement`() {
        val original = stock("duplicate")
        val duplicate = original.copy(symbol = "OTHER")
        assertThrows(IllegalArgumentException::class.java) {
            InMemoryStockRepository(listOf(original, duplicate))
        }
        val repository = InMemoryStockRepository(listOf(original))

        assertThrows(IllegalArgumentException::class.java) {
            repository.replaceCatalog(listOf(original, duplicate))
        }

        assertEquals(listOf(original), repository.catalog.value)
    }

    @Test
    fun `detail emits null when its stock is removed`() = runBlocking {
        withTimeout(5_000) {
            val original = stock("removed")
            val repository = InMemoryStockRepository(listOf(original))
            val details = mutableListOf<Stock?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeStock(original.id).take(2).collect { details.add(it) }
            }

            repository.replaceCatalog(emptyList())
            collector.join()

            assertEquals(listOf(original, null), details)
            assertNull(repository.getStock(original.id))
        }
    }

    @Test
    fun `unknown detail becomes available when added to catalog`() = runBlocking {
        withTimeout(5_000) {
            val added = stock("later")
            val repository = InMemoryStockRepository()
            val details = mutableListOf<Stock?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeStock(added.id).take(2).collect { details.add(it) }
            }

            repository.replaceCatalog(listOf(added))
            collector.join()

            assertEquals(listOf(null, added), details)
        }
    }

    @Test
    fun `unrelated updates do not emit an unchanged detail`() = runBlocking {
        withTimeout(5_000) {
            val original = stock("observed")
            val other = stock("other")
            val repository = InMemoryStockRepository(listOf(original, other))
            val details = mutableListOf<Stock?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                repository.observeStock(original.id).collect { details.add(it) }
            }

            repository.replaceCatalog(listOf(original, other.copy(name = "Changed Company")))
            yield()
            collector.cancel()
            collector.join()

            assertEquals(listOf(original), details)
        }
    }

    @Test
    fun `prices and changes retain decimal precision and scale`() {
        val exactQuote = StockQuote(
            price = BigDecimal("12345678901234567890.123456789000"),
            currencyCode = "USD",
            changeAmount = BigDecimal("-0.000000000001"),
            changePercent = BigDecimal("-0.000000000010"),
        )
        val exactStock = stock("exact").copy(quote = exactQuote)
        val repository = InMemoryStockRepository(listOf(exactStock))

        val actual = repository.getStock(exactStock.id)!!.quote

        assertEquals(exactQuote, actual)
        assertEquals("12345678901234567890.123456789000", actual.price!!.toPlainString())
        assertEquals("-0.000000000001", actual.changeAmount!!.toPlainString())
        assertEquals("-0.000000000010", actual.changePercent!!.toPlainString())
    }

    @Test
    fun `published snapshots are isolated from mutable input collections`() {
        val point = StockPricePoint(Instant.parse("2026-01-01T00:00:00Z"), BigDecimal("100.01"))
        val points = mutableListOf(point)
        val charts = mutableMapOf<ChartRange, List<StockPricePoint>>(ChartRange.ONE_DAY to points)
        val original = stock("isolated").copy(charts = charts)
        val input = mutableListOf(original)
        val repository = InMemoryStockRepository(input)

        input.clear()
        charts.clear()
        points.clear()

        assertEquals(1, repository.catalog.value.size)
        assertEquals(listOf(point), repository.getStock(original.id)!!.charts[ChartRange.ONE_DAY])
    }

    @Test
    fun `reference quote remains explicitly sample data`() {
        val fixture = sampleStockCatalog().single()

        assertEquals("MSFTx", fixture.symbol)
        assertEquals(BigDecimal("497.68"), fixture.quote.price)
        assertEquals(BigDecimal("3.59"), fixture.quote.changeAmount)
        assertEquals(BigDecimal("0.7"), fixture.quote.changePercent)
        assertNull(fixture.quote.asOf)
        assertNull(fixture.statistics)
        assertEquals(emptyMap<ChartRange, List<StockPricePoint>>(), fixture.charts)
    }

    @Test
    fun `unchanged rows preserve published identity while changed row updates indexed lookup`() {
        val first = stock("first")
        val second = stock("second")
        val repository = InMemoryStockRepository(listOf(first, second))
        val publishedFirst = repository.getStock(first.id)
        val changed = second.copy(quote = second.quote.copy(price = BigDecimal("120.01")))
        repository.replaceCatalog(listOf(first.copy(), changed))
        assertSame(publishedFirst, repository.getStock(first.id))
        assertEquals(BigDecimal("120.01"), repository.getStock(second.id)!!.quote.price)
    }

    private fun stock(
        id: String,
        symbol: String = "TEST",
        name: String = "Test Company",
    ): Stock = Stock(
        id = StockId(id),
        symbol = symbol,
        name = name,
        quote = StockQuote(price = BigDecimal("100.00"), currencyCode = "USD"),
    )
}

package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PreparedStockCatalogTest {
    private val observedAt = Instant.parse("2026-09-29T14:00:00Z")
    private fun stock(id: String) = Stock(StockId(id), id, "Company $id",
        StockQuote(BigDecimal("100.001000"), "USD", asOf = observedAt))

    @Test
    fun `preparation does not publish and adoption retains exact observations`() {
        val original = stock("original")
        val incoming = original.copy(quote = original.quote.copy(price = BigDecimal("101.000000000001")))
        val repository = InMemoryStockRepository(listOf(original))
        val before = repository.catalog.value
        val base = repository.capturePreparationBase()

        val prepared = repository.prepareCatalog(listOf(incoming), base)

        assertSame(before, repository.catalog.value)
        assertSame(before.single(), repository.getStock(original.id))
        assertSame(before.single(), base.getStock(original.id))
        assertTrue(repository.adoptPreparedCatalog(prepared))
        assertSame(prepared.stocks, repository.catalog.value)
        assertSame(prepared.stocks.single(), repository.getStock(original.id))
        assertEquals("101.000000000001", repository.getStock(original.id)!!.quote.price!!.toPlainString())
        assertEquals(observedAt, repository.getStock(original.id)!!.quote.asOf)
    }

    @Test
    fun `chart publication during background preparation rejects stale result without mutation`() = runBlocking {
        val original = stock("charted")
        val repository = InMemoryStockRepository(listOf(original))
        val base = repository.capturePreparationBase()
        val incoming = original.copy(quote = original.quote.copy(price = BigDecimal("102.123400")))
        val started = CompletableDeferred<Unit>()
        val resumePreparation = CompletableDeferred<Unit>()
        val work = async(Dispatchers.Default) {
            started.complete(Unit)
            resumePreparation.await()
            repository.prepareCatalog(listOf(incoming), base)
        }
        started.await()
        val point = StockPricePoint(observedAt.minusSeconds(60), BigDecimal("99.000001"))
        repository.replaceCatalog(listOf(original.copy(charts = mapOf(ChartRange.ONE_DAY to listOf(point)))))
        val chartPublication = repository.catalog.value
        resumePreparation.complete(Unit)

        assertFalse(repository.adoptPreparedCatalog(work.await()))
        assertSame(chartPublication, repository.catalog.value)
        assertSame(chartPublication.single(), repository.getStock(original.id))
        assertEquals(listOf(point), repository.getStock(original.id)!!.charts[ChartRange.ONE_DAY])

        // The owner recomputes the same incoming quote against the latest retained charts.
        val freshBase = repository.capturePreparationBase()
        val retry = repository.prepareCatalog(listOf(incoming.copy(charts = freshBase.getStock(original.id)!!.charts)), freshBase)
        assertTrue(repository.adoptPreparedCatalog(retry))
        assertEquals(BigDecimal("102.123400"), repository.getStock(original.id)!!.quote.price)
        assertEquals(observedAt, repository.getStock(original.id)!!.quote.asOf)
        assertEquals(listOf(point), repository.getStock(original.id)!!.charts[ChartRange.ONE_DAY])
    }

    @Test
    fun `only one prepared edit from a generation can publish`() {
        val original = stock("edited")
        val repository = InMemoryStockRepository(listOf(original))
        val base = repository.capturePreparationBase()
        val first = repository.prepareCatalog(listOf(original.copy(name = "First edit")), base)
        val second = repository.prepareCatalog(listOf(original.copy(name = "Second edit")), base)

        assertTrue(repository.adoptPreparedCatalog(first))
        val accepted = repository.catalog.value
        assertFalse(repository.adoptPreparedCatalog(second))
        assertSame(accepted, repository.catalog.value)
        assertEquals("First edit", repository.getStock(original.id)!!.name)
    }

    @Test
    fun `unchanged preparation reuses row and list identity but retires its generation`() {
        val first = stock("first")
        val second = stock("second")
        val repository = InMemoryStockRepository(listOf(first, second))
        val before = repository.catalog.value
        val base = repository.capturePreparationBase()
        val prepared = repository.prepareCatalog(listOf(first.copy(), second.copy()), base)
        val pending = repository.prepareCatalog(listOf(first.copy(name = "Pending"), second), base)

        assertSame(before, prepared.stocks)
        assertTrue(repository.adoptPreparedCatalog(prepared))
        assertSame(before, repository.catalog.value)
        assertSame(before[0], repository.getStock(first.id))
        assertSame(before[1], repository.getStock(second.id))
        assertFalse(repository.adoptPreparedCatalog(prepared))
        assertFalse(repository.adoptPreparedCatalog(pending))
    }

    @Test
    fun `duplicate validation fails before publication and removal preserves supplied order`() {
        val first = stock("first")
        val second = stock("second")
        val third = stock("third")
        val repository = InMemoryStockRepository(listOf(first, second, third))
        val before = repository.catalog.value
        val base = repository.capturePreparationBase()
        assertThrows(IllegalArgumentException::class.java) {
            repository.prepareCatalog(listOf(first, first.copy(symbol = "Duplicate")), base)
        }
        assertSame(before, repository.catalog.value)
        assertSame(before[1], repository.getStock(second.id))

        val prepared = repository.prepareCatalog(listOf(third.copy(), first.copy()), base)
        assertTrue(repository.adoptPreparedCatalog(prepared))
        assertEquals(listOf(third.id, first.id), repository.catalog.value.map { it.id })
        assertNull(repository.getStock(second.id))
        assertSame(before[2], repository.getStock(third.id))
        assertSame(before[0], repository.getStock(first.id))
    }

    @Test
    fun `prepared catalog cannot be adopted by another repository or use its base`() {
        val original = stock("bound")
        val first = InMemoryStockRepository(listOf(original))
        val second = InMemoryStockRepository(listOf(original))
        val before = second.catalog.value
        val base = first.capturePreparationBase()
        val prepared = first.prepareCatalog(listOf(original.copy(name = "Changed")), base)

        assertFalse(second.adoptPreparedCatalog(prepared))
        assertSame(before, second.catalog.value)
        assertThrows(IllegalArgumentException::class.java) { second.prepareCatalog(listOf(original), base) }
    }

    @Test
    fun `prepared charts deployments and metrics are isolated immutable copies`() {
        val original = stock("isolated")
        val repository = InMemoryStockRepository(listOf(original))
        val point = StockPricePoint(observedAt, BigDecimal("100.001000"))
        val points = mutableListOf(point)
        val charts = mutableMapOf<ChartRange, List<StockPricePoint>>(ChartRange.ONE_DAY to points)
        val deployment = StockDeployment("Robinhood Chain", "0x1111111111111111111111111111111111111111", "4663", 18)
        val deployments = mutableListOf(deployment)
        val metrics = StockMetricKey.entries.associateWith { key ->
            StockMetricData(BigDecimal.ONE, key.unit, "Jupiter", "token", observedAt, null)
        }.toMutableMap()
        val statistics = StockStatisticsReference("solana", "exact-mint", metrics, observedAt).toStatistics()
        val input = mutableListOf(original.copy(charts = charts, deployments = deployments, statistics = statistics))
        val prepared = repository.prepareCatalog(input, repository.capturePreparationBase())

        input.clear()
        charts.clear()
        points.clear()
        deployments.clear()
        metrics.clear()
        assertTrue(repository.adoptPreparedCatalog(prepared))
        val accepted = repository.getStock(original.id)!!
        val reference = requireNotNull(accepted.statistics?.reference)
        assertEquals(listOf(point), accepted.charts[ChartRange.ONE_DAY])
        assertEquals(listOf(deployment), accepted.deployments)
        assertEquals(StockMetricKey.entries.toSet(), reference.metrics.keys)
        assertThrows(UnsupportedOperationException::class.java) { (prepared.stocks as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (accepted.charts as MutableMap<*, *>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (accepted.charts[ChartRange.ONE_DAY] as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (accepted.deployments as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (reference.metrics as MutableMap<*, *>).clear() }
    }
}

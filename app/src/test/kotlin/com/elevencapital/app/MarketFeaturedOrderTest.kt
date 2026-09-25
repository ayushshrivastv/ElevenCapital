package com.elevencapital.app

import com.elevencapital.app.screens.MarketFeaturedOrder
import com.elevencapital.app.screens.MarketQuery
import com.elevencapital.app.screens.MarketRowSelector
import com.elevencapital.app.screens.MarketSort
import com.elevencapital.app.screens.MarketSource
import com.elevencapital.app.screens.PrimaryStockRow
import com.elevencapital.app.screens.selectMarketRows
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketFeaturedOrderTest {
    // Keep the existing selected companies fixed as launch ordering evolves.
    private val companies = listOf(
        "MSFT", "SPCX", "TSLA", "GOOGL", "META", "NFLX", "AAPL", "NVDA", "AMZN", "BRK.B",
        "JPM", "V", "MA", "LLY", "WMT", "KO", "DIS", "COIN", "AMD", "NKE",
    )
    private val spacexId = "backed:eba060bd-f7b3-49e3-8ef1-99869351b434"
    private val nikeId = "backpack:NKE.US"
    private val providers = listOf(MarketSource.BACKED, MarketSource.BACKPACK)
    private fun row(id: String, symbol: String, price: Int = 1) = PrimaryStockRow(
        stock = Stock(StockId(id), symbol, symbol, StockQuote(BigDecimal(price), "USD")),
    )
    private val featured = providers.flatMap { source ->
        companies.mapIndexed { index, company ->
            val symbol = company + if (source == MarketSource.BACKPACK) ".US" else "x"
            val id = if (source == MarketSource.BACKED && company == "SPCX") spacexId else "${source.provider}:$symbol"
            row(id, symbol, index + 1)
        }
    }
    private val remainder = listOf(
        row("backed:other", "OTHERx"), row("prestocks:NKE", "NKE"), row("backpack:other", "OTHER.US"),
    )
    private val catalog = remainder.take(1) + featured + remainder.drop(1)
    private fun ids(rows: List<PrimaryStockRow>) = rows.map { it.stock.id.value }

    @Test fun everyLaunchKeepsBothRequiredListingsInTheFirstTenAndEveryCatalogListing() {
        repeat(512) { seed ->
            val selector = MarketRowSelector(MarketFeaturedOrder(Random(seed)))
            val all = selector.select(catalog, MarketQuery(), false)
            assertEquals("Seed $seed must retain the full catalog", ids(catalog).toSet(), ids(all).toSet())
            assertEquals(catalog.size, all.size)
            assertEquals(ids(featured).toSet(), ids(all.take(40)).toSet())
            assertEquals(ids(remainder), ids(all.drop(40)))
            assertTrue("Nike at seed $seed", nikeId in ids(all.take(10)))
            assertTrue("SpaceX at seed $seed", spacexId in ids(all.take(10)))
            for (source in providers) {
                val selected = selector.select(catalog, MarketQuery(source = source), false)
                val expected = featured.filter { it.stock.id.value.startsWith("${source.provider}:") }
                assertEquals(ids(expected).toSet(), ids(selected.take(20)).toSet())
                assertEquals(21, selected.size)
                val requiredId = if (source == MarketSource.BACKPACK) nikeId else spacexId
                assertTrue("${source.provider} at seed $seed", requiredId in ids(selected.take(5)))
            }
        }
    }

    @Test fun newLaunchesShuffleAllTwentyIncludingThePriorityListings() {
        for (source in providers) {
            val positions = featured.filter { it.stock.id.value.startsWith("${source.provider}:") }
                .associate { it.stock.id.value to mutableSetOf<Int>() }
            repeat(64) { seed ->
                val selector = MarketRowSelector(MarketFeaturedOrder(Random(seed)))
                selector.select(catalog, MarketQuery(source = source), false).take(20)
                    .forEachIndexed { index, row -> positions.getValue(row.stock.id.value).add(index) }
            }
            positions.forEach { (id, indices) -> assertTrue("$id should move between launches", indices.size > 1) }
        }
    }

    @Test fun fixedSeedIsReproducibleAndLaunchOrderIsSharedAcrossSelectorsAndQuoteUpdates() {
        val first = MarketRowSelector(MarketFeaturedOrder(Random(42))).select(catalog, MarketQuery(), false)
        val repeated = MarketRowSelector(MarketFeaturedOrder(Random(42))).select(catalog, MarketQuery(), false)
        assertEquals(ids(first), ids(repeated))

        val launchOrder = ids(selectMarketRows(catalog, MarketQuery(), false))
        val selector = MarketRowSelector()
        assertEquals(launchOrder, ids(selector.select(catalog, MarketQuery(), false)))
        val tick = catalog.map { it.copy(stock = it.stock.copy(quote = it.stock.quote.copy(price = BigDecimal.TEN))) }
        assertEquals(launchOrder, ids(selector.select(tick, MarketQuery(), false)))
        assertEquals(launchOrder, ids(MarketRowSelector().select(tick, MarketQuery(), false)))
        assertTrue(selector.select(tick, MarketQuery(), false).all { it.stock.quote.price == BigDecimal.TEN })
    }

    @Test fun explicitSortingAndSearchStillApplyToTheFullCatalog() {
        val selector = MarketRowSelector(MarketFeaturedOrder(Random(7)))
        assertEquals(catalog.sortedBy { it.stock.name.lowercase() },
            selector.select(catalog, MarketQuery(sort = MarketSort.NAME_ASC), false))
        assertEquals(catalog.sortedByDescending { it.stock.quote.price },
            selector.select(catalog, MarketQuery(sort = MarketSort.PRICE_DESC), false))
        assertEquals(ids(remainder.filter { it.stock.symbol.startsWith("OTHER") }).toSet(),
            ids(selector.select(catalog, MarketQuery(text = "other"), false)).toSet())
        assertEquals(listOf(remainder[1]),
            selector.select(catalog, MarketQuery(source = MarketSource.PRESTOCKS), false))
    }
}

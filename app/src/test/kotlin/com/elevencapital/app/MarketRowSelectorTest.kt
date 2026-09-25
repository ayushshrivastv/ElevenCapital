package com.elevencapital.app

import com.elevencapital.app.screens.MarketLogoPrefetchHistory
import com.elevencapital.app.screens.MarketMovement
import com.elevencapital.app.screens.MarketQuery
import com.elevencapital.app.screens.MarketRowSelector
import com.elevencapital.app.screens.MarketSort
import com.elevencapital.app.screens.MarketSource
import com.elevencapital.app.screens.PrimaryStockRow
import com.elevencapital.app.screens.selectMarketRows
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.StockMarketActivity
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRowSelectorTest {
    private fun row(id: String, symbol: String, name: String, price: String?, change: String?,
        volume: String? = null, watched: Boolean = false) = PrimaryStockRow(
        stock = Stock(StockId(id), symbol, name,
            StockQuote(price?.let(::BigDecimal), "USD", changePercent = change?.let(::BigDecimal))),
        volume = volume?.let(::BigDecimal), watched = watched,
    )

    private val catalog = listOf(
        row("backed:MSFTx", "MSFTx", "Microsoft xStock", "500", "2", "200", true),
        row("backpack:MSFT.US", "MSFT.US", "Microsoft", "499", "-1", "100"),
        row("prestocks:openai", "OPENAI", "OpenAI Pre-IPO", "82", "1", "250"),
        row("backpack:NEW.US", "NEW.US", "New Company", null, null, watched = true),
    )

    @Test fun cachedResultAlwaysUsesLatestQuotesArtworkAndWatchState() {
        val selector = MarketRowSelector()
        val before = selector.select(catalog, MarketQuery(), false)
        val changed = catalog[0].copy(
            stock = catalog[0].stock.copy(
                quote = catalog[0].stock.quote.copy(price = BigDecimal("501"), asOf = Instant.EPOCH),
                logo = StockLogoReference("https://example.com/new-logo.png"),
            ),
            watched = false,
        )
        val after = selector.select(listOf(changed) + catalog.drop(1), MarketQuery(), false)
        assertEquals(before.map { it.stock.id }, after.map { it.stock.id })
        assertSame(changed, after.single { it.stock.id == changed.stock.id })
        assertEquals(BigDecimal("501"), after.single { it.stock.id == changed.stock.id }.stock.quote.price)
        assertFalse(after.single { it.stock.id == changed.stock.id }.watched)
    }

    @Test fun updateOutsideFilteredResultsRetainsListIdentity() {
        val selector = MarketRowSelector()
        val query = MarketQuery(source = MarketSource.PRESTOCKS)
        val before = selector.select(catalog, query, false)
        val changed = catalog[0].copy(stock = catalog[0].stock.copy(
            quote = catalog[0].stock.quote.copy(price = BigDecimal("501")),
        ))
        val after = selector.select(listOf(changed) + catalog.drop(1), query, false)
        assertSame(before, after)
    }

    @Test fun everyQueryKeepsUpWithRelevantCatalogChanges() {
        val newQuote = catalog[0].copy(stock = catalog[0].stock.copy(
            quote = catalog[0].stock.quote.copy(price = BigDecimal.ONE, changePercent = BigDecimal("-3")),
        ), volume = BigDecimal("1000"))
        val renamed = catalog[1].copy(stock = catalog[1].stock.copy(name = "Apple", symbol = "AAPL.US"))
        val newProvider = catalog[2].copy(stock = catalog[2].stock.copy(id = StockId("backpack:OPENAI")))
        val unavailableActivity = catalog[0].copy(stock = catalog[0].stock.copy(activity = StockMarketActivity(
            "USD", "Jupiter", "solana_token", null, null, Instant.EPOCH, Instant.EPOCH,
            "Unavailable", "Unavailable",
        )))
        val snapshots = listOf(
            emptyList(), catalog,
            listOf(newQuote) + catalog.drop(1),
            listOf(catalog[0], renamed) + catalog.drop(2),
            catalog.map { it.copy(watched = !it.watched) },
            listOf(catalog[0], catalog[1], newProvider, catalog[3]),
            listOf(unavailableActivity) + catalog.drop(1),
            catalog.reversed(), catalog.drop(1),
            catalog + row("backpack:NKE.US", "NKE.US", "Nike", "80", "0", "300", true),
            emptyList(), catalog,
        )
        for (source in MarketSource.entries) {
            for (movement in MarketMovement.entries) {
                for (sort in MarketSort.entries) {
                    for (watchedOnly in listOf(false, true)) {
                        for (text in listOf("", "microSOFT", "openai", "apple")) {
                            val query = MarketQuery(text, source, movement, sort)
                            val selector = MarketRowSelector()
                            for (snapshot in snapshots) {
                                val expected = selectMarketRows(snapshot, query, watchedOnly)
                                val actual = selector.select(snapshot, query, watchedOnly)
                                assertEquals("$query, watched=$watchedOnly", expected, actual)
                                expected.indices.forEach { index -> assertSame(expected[index], actual[index]) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun changingQueryAndWatchlistOnSameCatalogRefreshesSelection() {
        val selector = MarketRowSelector()
        val queries = listOf(
            MarketQuery(), MarketQuery(source = MarketSource.BACKED),
            MarketQuery(sort = MarketSort.PRICE_ASC), MarketQuery(text = "new"),
            MarketQuery(movement = MarketMovement.LOSERS), MarketQuery(),
        )
        for (query in queries) {
            for (watchedOnly in listOf(false, true, false)) {
                assertEquals(selectMarketRows(catalog, query, watchedOnly), selector.select(catalog, query, watchedOnly))
            }
        }
    }

    @Test fun fullSizedCatalogRetainsAllProvidersAndUpdatedRows() {
        val providers = listOf("backed", "backpack", "prestocks")
        val rows = List(2_215) { index ->
            row("${providers[index % providers.size]}:$index", "STOCK$index", "Company $index", "1", "0")
        }
        val selector = MarketRowSelector()
        assertEquals(rows, selector.select(rows, MarketQuery(), false))
        val updated = rows.mapIndexed { index, row ->
            if (index % 100 == 0) row.copy(stock = row.stock.copy(
                quote = row.stock.quote.copy(price = BigDecimal("2")),
            )) else row
        }
        val result = selector.select(updated, MarketQuery(), false)
        assertEquals(2_215, result.size)
        updated.indices.forEach { index -> assertSame(updated[index], result[index]) }
        assertEquals(providers.toSet(), result.map { it.stock.id.value.substringBefore(':') }.toSet())
    }

    @Test fun overlappingLookaheadOnlySchedulesNewLogos() {
        val history = MarketLogoPrefetchHistory()
        var requests = 0
        repeat(100) { first ->
            for (index in first until first + 12) {
                if (history.shouldRequest("https://example.com/$index.png")) requests++
            }
        }
        assertEquals(111, requests)
        assertFalse(history.shouldRequest("reference/msft"))
        assertFalse(history.shouldRequest("http://example.com/logo.png"))
    }

    @Test fun logoHistoryIsBoundedAndAllowsRevisitingEvictedArtwork() {
        val history = MarketLogoPrefetchHistory(capacity = 2)
        assertTrue(history.shouldRequest("https://example.com/a.png"))
        assertTrue(history.shouldRequest("https://example.com/b.png"))
        assertFalse(history.shouldRequest("https://example.com/a.png"))
        assertTrue(history.shouldRequest("https://example.com/c.png"))
        assertTrue(history.shouldRequest("https://example.com/a.png"))
    }
}

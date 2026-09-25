package com.elevencapital.app

import com.elevencapital.app.screens.*
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockMarketActivity
import com.elevencapital.core.stock.StockQuote
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketQueryTest {
    private fun row(id: String, symbol: String, name: String, price: String?, change: String?,
        volume: String? = null, watched: Boolean = false): PrimaryStockRow = PrimaryStockRow(
        stock = Stock(StockId(id), symbol, name,
            StockQuote(price?.let(::BigDecimal), "USD", changePercent = change?.let(::BigDecimal))),
        volume = volume?.let(::BigDecimal), watched = watched,
    )
    private val rows = listOf(
        row("backed:MSFTx", "MSFTx", "Microsoft xStock", "500.00", "2.4", "200", true),
        row("backpack:MSFT", "MSFT", "Microsoft", "499.00", "-1.2", "100"),
        row("backed:SPYx", "SPYx", "S&P 500 ETF", "650.00", "0", "300"),
        row("backpack:NEW", "NEW", "New Company", null, null, watched = true),
    )

    private fun ids(result: List<PrimaryStockRow>) = result.map { it.stock.id.value }

    @Test fun defaultRandomizesFeaturedCompaniesAndKeepsRemainingRowsInCatalogOrder() {
        val result = selectMarketRows(rows, MarketQuery(), false)
        assertEquals(setOf("backed:MSFTx", "backpack:MSFT"), ids(result).take(2).toSet())
        assertEquals(listOf("backed:SPYx", "backpack:NEW"), ids(result).drop(2))
    }

    @Test fun nikeAndSpacexListingsAreAlwaysInTheTopTenForAllAndTheirProviders() {
        val symbols = listOf("MSFT", "SPCX", "TSLA", "GOOGL", "META", "NFLX", "AAPL", "NVDA", "AMZN", "BRK.B", "NKE")
        val catalog = symbols.map { symbol ->
            row("backpack:$symbol.US", "$symbol.US", symbol, "1", "0")
        }
        val result = selectMarketRows(catalog, MarketQuery(source = MarketSource.BACKPACK), false)
        assertTrue(result.take(10).any { it.stock.id.value == "backpack:NKE.US" })
        val bothProviders = catalog + symbols.map { symbol ->
            row("backed:${symbol}x", "${symbol}x", symbol, "1", "0")
        }
        val all = selectMarketRows(bothProviders, MarketQuery(), false)
        assertTrue(all.take(10).any { it.stock.id.value == "backpack:NKE.US" })
        assertTrue(all.take(10).any { it.stock.id.value == "backed:SPCXx" })
        val backed = selectMarketRows(bothProviders, MarketQuery(source = MarketSource.BACKED), false)
        assertTrue(backed.take(10).any { it.stock.id.value == "backed:SPCXx" })
    }

    @Test fun defaultShowsFeaturedCompaniesFirstForBothProvidersWithoutReorderingTheRemainder() {
        val mixed = listOf(
            row("backed:ZZZx", "ZZZx", "Other xStock", "1", "0"),
            row("backed:TSLAx", "TSLAx", "Tesla xStock", "2", "1"),
            row("backpack:NEW.US", "NEW.US", "Other company", "3", "1"),
            row("backpack:MSFT.US", "MSFT.US", "Microsoft", "4", "1"),
            row("backed:MSFTx", "MSFTx", "Microsoft xStock", "4", "1"),
        )
        val all = ids(selectMarketRows(mixed, MarketQuery(), false))
        assertEquals(setOf("backpack:MSFT.US", "backed:MSFTx", "backed:TSLAx"), all.take(3).toSet())
        assertEquals(listOf("backed:ZZZx", "backpack:NEW.US"), all.drop(3))
        val backed = ids(selectMarketRows(mixed, MarketQuery(source = MarketSource.BACKED), false))
        assertEquals(setOf("backed:MSFTx", "backed:TSLAx"), backed.take(2).toSet())
        assertEquals("backed:ZZZx", backed.last())
    }

    @Test fun searchMatchesSymbolOrNameIgnoringCaseAndWhitespace() {
        assertEquals(setOf("backed:MSFTx", "backpack:MSFT"),
            ids(selectMarketRows(rows, MarketQuery(text = "  microSOFT  "), false)).toSet())
        assertEquals(listOf("backed:SPYx"), ids(selectMarketRows(rows, MarketQuery(text = "spyX"), false)))
        assertEquals(emptyList<String>(), ids(selectMarketRows(rows, MarketQuery(text = "msft new"), false)))
    }

    @Test fun sourceUsesProviderIdentityInsteadOfSymbolSuffix() {
        val tricky = rows + row("backpack:MSFTx", "MSFTx", "Another listing", "2", "1")
        assertEquals(listOf("backed:MSFTx", "backed:SPYx"), ids(selectMarketRows(tricky,
            MarketQuery(source = MarketSource.BACKED), false)))
    }

    @Test fun prestocksSourceSelectsOnlyPreIpoListingsAndComposesWithSearch() {
        val preIpo = row("prestocks:openai", "OPENAI", "OpenAI Pre-IPO", "82.50", "1.5", "250")
        val catalog = rows + preIpo
        assertEquals(listOf("prestocks:openai"), ids(selectMarketRows(catalog,
            MarketQuery(source = MarketSource.PRESTOCKS), false)))
        assertEquals(listOf("prestocks:openai"), ids(selectMarketRows(catalog,
            MarketQuery(text = "openai", source = MarketSource.PRESTOCKS), false)))
    }

    @Test fun gainersAndLosersExcludeUnknownAndUnchangedReturns() {
        assertEquals(listOf("backed:MSFTx"), ids(selectMarketRows(rows, MarketQuery(movement = MarketMovement.GAINERS), false)))
        assertEquals(listOf("backpack:MSFT"), ids(selectMarketRows(rows, MarketQuery(movement = MarketMovement.LOSERS), false)))
    }

    @Test fun ascendingAndDescendingPricesKeepUnavailableLast() {
        assertEquals(listOf("backpack:MSFT", "backed:MSFTx", "backed:SPYx", "backpack:NEW"),
            ids(selectMarketRows(rows, MarketQuery(sort = MarketSort.PRICE_ASC), false)))
        assertEquals(listOf("backed:SPYx", "backed:MSFTx", "backpack:MSFT", "backpack:NEW"),
            ids(selectMarketRows(rows, MarketQuery(sort = MarketSort.PRICE_DESC), false)))
    }

    @Test fun watchlistCombinesWithSearchAndProviderFilters() {
        assertEquals(listOf("backed:MSFTx"), ids(selectMarketRows(rows,
            MarketQuery(text = "Microsoft", source = MarketSource.BACKED), true)))
        assertEquals(emptyList<String>(), ids(selectMarketRows(rows,
            MarketQuery(text = "Microsoft", source = MarketSource.BACKPACK), true)))
    }

    @Test fun unavailableLiveVolumeNeverUsesOldFixtureFallbackForSort() {
        val missing = rows.first().copy(stock = rows.first().stock.copy(activity = StockMarketActivity(
            "USD", "Jupiter", "solana_token", null, null, Instant.EPOCH, Instant.EPOCH,
            "Unavailable", "Unavailable",
        )))
        assertEquals(listOf("backed:SPYx", "backpack:MSFT", "backed:MSFTx", "backpack:NEW"),
            ids(selectMarketRows(listOf(missing) + rows.drop(1), MarketQuery(sort = MarketSort.VOLUME_DESC), false)))
    }

    @Test fun updatingCatalogRequiresNoQueryOrDesignChange() {
        val newListing = row("backpack:AAPL", "AAPL", "Apple", "230", "1", "1000")
        val query = MarketQuery(text = "apple")
        assertEquals(emptyList<PrimaryStockRow>(), selectMarketRows(rows, query, false))
        assertEquals(listOf(newListing), selectMarketRows(rows + newListing, query, false))
    }

    @Test fun tiedNumericValuesStayInProviderCatalogOrderWithoutRounding() {
        val exact = listOf(row("backed:A", "A", "A", "1.000000000000000002", "1"),
            row("backpack:A", "A", "A", "1.000000000000000001", "1"))
        assertEquals(exact.reversed(), selectMarketRows(exact, MarketQuery(sort = MarketSort.PRICE_ASC), false))
        assertEquals(exact, selectMarketRows(exact, MarketQuery(sort = MarketSort.CHANGE_DESC), false))
    }
}

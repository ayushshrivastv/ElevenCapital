package com.elevencapital.app.screens

import com.elevencapital.app.data.MeteoraMarketToken
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class MeteoraMarketFilterTest {
    private val tokens = listOf(
        token("Alpha", "ALP", "100", "2"),
        token("Beta", "BET", "50", "-3"),
        token("Gamma", "GAM", null, null),
    )

    @Test fun `all keeps the provider ranked top twenty order`() {
        assertEquals(tokens, selectMeteoraMarketTokens(tokens, "", MarketMovement.ALL, MarketSort.DEFAULT))
    }

    @Test fun `search and movement apply only to Meteora token fields`() {
        assertEquals(listOf(tokens[0]),
            selectMeteoraMarketTokens(tokens, "alp", MarketMovement.GAINERS, MarketSort.DEFAULT))
        assertEquals(listOf(tokens[1]),
            selectMeteoraMarketTokens(tokens, "beta", MarketMovement.LOSERS, MarketSort.DEFAULT))
        assertEquals(emptyList<MeteoraMarketToken>(),
            selectMeteoraMarketTokens(tokens, "beta", MarketMovement.GAINERS, MarketSort.DEFAULT))
    }

    @Test fun `volume sort puts unknown volume last in both directions`() {
        assertEquals(listOf(tokens[1], tokens[0], tokens[2]),
            selectMeteoraMarketTokensForSort(MarketSort.VOLUME_ASC))
        assertEquals(listOf(tokens[0], tokens[1], tokens[2]),
            selectMeteoraMarketTokensForSort(MarketSort.VOLUME_DESC))
    }

    private fun selectMeteoraMarketTokensForSort(sort: MarketSort) =
        selectMeteoraMarketTokens(tokens, "", MarketMovement.ALL, sort)

    private fun token(name: String, symbol: String, volume: String?, change: String?) = MeteoraMarketToken(
        mintAddress = name,
        poolAddress = symbol,
        name = name,
        symbol = symbol,
        priceUsd = null,
        volume24hUsd = volume?.let(::BigDecimal),
        change24hPercent = change?.let(::BigDecimal),
    )
}

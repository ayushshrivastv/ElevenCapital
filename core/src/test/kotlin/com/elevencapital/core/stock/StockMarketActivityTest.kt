package com.elevencapital.core.stock

import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class StockMarketActivityTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private fun activity(net: String = "-12.34567890123456789") = StockMarketActivity(
        currencyCode = "USD", source = "Jupiter", scope = "solana_token",
        volume24h = BigDecimal("100.1234567890123456789"), netVolume24h = BigDecimal(net),
        receivedAt = now, updatedAt = now, volumeReason = null, netVolumeReason = null,
    )

    @Test fun exactDecimalAndDirectionalNetSurvive() {
        for (net in listOf("12.34567890123456789", "-12.34567890123456789", "0")) {
            val observation = activity(net).expire(now, 1_000)
            assertEquals(BigDecimal("100.1234567890123456789"), observation.volume24h)
            assertEquals(BigDecimal(net), observation.netVolume24h)
        }
    }

    @Test fun backpackCanHaveTurnoverWithoutNetAndKeepsUsdcUnit() {
        val observation = StockMarketActivity("USDC", "Backpack", "external_market", BigDecimal.ZERO,
            null, now, null, null, "Provider does not report buy/sell volume.").expire(now, 0)
        assertEquals(BigDecimal.ZERO, observation.volume24h)
        assertNull(observation.netVolume24h)
        assertEquals("USDC", observation.currencyCode)
        assertEquals("Provider does not report buy/sell volume.", observation.netVolumeReason)
    }

    @Test fun upstreamAgePlusElapsedTimeExpiresBothValues() {
        val observation = activity().copy(updatedAt = now.minusSeconds(240))
        assertNotNull(observation.expire(now, 60_000).volume24h)
        val expired = observation.expire(now, 60_001)
        assertNull(expired.volume24h)
        assertNull(expired.netVolume24h)
        assertTrue(expired.volumeReason!!.contains("expired"))
        assertTrue(expired.netVolumeReason!!.contains("expired"))
    }

    @Test fun fetchedAgeExpiresWithoutUpstreamTimeAndKeepsMissingReason() {
        val observation = StockMarketActivity("USDC", "Backpack", "external_market", BigDecimal.TEN,
            null, now.minusSeconds(300), null, null, "No breakdown available.")
        val expired = observation.expire(now, 1)
        assertNull(expired.volume24h)
        assertEquals("No breakdown available.", expired.netVolumeReason)
    }

    @Test fun futureObservationCannotStayFresh() {
        assertNull(activity().copy(receivedAt = now.plusSeconds(1)).expire(now, 0).volume24h)
        assertNull(activity().copy(updatedAt = now.plusSeconds(1)).expire(now, 0).volume24h)
    }

    @Test fun netCannotExceedTotalInEitherDirection() {
        for (net in listOf("101", "-101")) {
            assertThrows(IllegalArgumentException::class.java) { activity(net) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            activity().copy(volume24h = null, volumeReason = "No volume.")
        }
    }

    @Test fun unavailableValuesRequireAnExplanationAndDoNotBecomeZero() {
        val unavailable = activity().copy(volume24h = null, netVolume24h = null,
            volumeReason = "Not reported.", netVolumeReason = "Not reported.", receivedAt = null, updatedAt = null)
        assertNull(unavailable.expire(now, 0).volume24h)
        assertNull(unavailable.expire(now, 0).netVolume24h)
        assertThrows(IllegalArgumentException::class.java) { unavailable.copy(volumeReason = " ") }
        assertThrows(IllegalArgumentException::class.java) { unavailable.copy(netVolumeReason = null) }
    }

    @Test fun sourceAndCurrencyCannotBeSubstituted() {
        assertThrows(IllegalArgumentException::class.java) { activity().copy(currencyCode = "USDC") }
        assertThrows(IllegalArgumentException::class.java) { activity().copy(source = "Backpack") }
        assertThrows(IllegalArgumentException::class.java) { activity().copy(scope = "external_market") }
    }

    @Test fun populatedJupiterActivityRequiresBothTimestamps() {
        assertThrows(IllegalArgumentException::class.java) { activity().copy(receivedAt = null) }
        assertThrows(IllegalArgumentException::class.java) { activity().copy(updatedAt = null) }
    }
}

package com.elevencapital.app

import com.elevencapital.app.data.LiveCatalog
import com.elevencapital.app.data.LiveMarketDataParser
import com.elevencapital.app.data.LiveInstrument
import com.elevencapital.app.data.RobinhoodFeaturedSymbols
import com.elevencapital.app.data.mergeRobinhoodFeaturedCatalog
import com.elevencapital.app.data.robinhoodCompositeReceiptElapsed
import com.elevencapital.app.data.robinhoodCatalogReceiptElapsed
import com.elevencapital.core.stock.RobinhoodStockReference
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.isReferenceDataFresh
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RobinhoodFeaturedCatalogMergeTest {
    private fun fixture(): LiveCatalog = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-catalog.json"))
        .bufferedReader().use { LiveMarketDataParser.catalog(JSONObject(it.readText())) }
    private val now = fixture().receivedAt.plusSeconds(6 * 60 * 60)

    @Test fun `full catalog arriving before the seed retains verified Robinhood startup rows and their timestamps`() {
        val featured = fixture()
        val full = featured.copy(instruments = emptyList(), receivedAt = now,
            providers = featured.providers.map { if (it.id == "robinhood") it.copy(status = "unavailable") else it })
        val merged = mergeRobinhoodFeaturedCatalog(full, featured, true, now)
        val expected = featured.instruments.filter { it.stock.symbol in RobinhoodFeaturedSymbols }
        assertEquals(expected.map { it.stock.id }.toSet(), merged.cachedIds)
        assertEquals(expected.size, merged.catalog.instruments.size)
        assertEquals(now, merged.catalog.receivedAt)
        assertEquals("stale", merged.catalog.providers.single { it.id == "robinhood" }.status)
        for (row in merged.catalog.instruments) {
            val original = featured.instruments.single { it.stock.id == row.stock.id }
            assertSame(original.stock, row.stock)
            assertEquals(original.quoteReceivedAt, row.quoteReceivedAt)
        }
    }

    @Test fun `unavailable partial provider cannot erase a dated cached price with an empty recent observation`() {
        val featured = fixture()
        val original = featured.instruments.first { it.stock.symbol in RobinhoodFeaturedSymbols }
        val empty = original.copy(quoteReceivedAt = now, stock = original.stock.copy(
            quote = original.stock.quote.copy(price = null, changeAmount = null, changePercent = null),
            robinhoodReference = null))
        val full = featured.copy(instruments = listOf(empty), receivedAt = now,
            providers = featured.providers.map { if (it.id == "robinhood") it.copy(status = "unavailable") else it })
        val merged = mergeRobinhoodFeaturedCatalog(full, featured, true, now)
        assertEquals(original, merged.catalog.instruments.single { it.stock.id == original.stock.id })
        assertTrue(original.stock.id in merged.cachedIds)
    }

    @Test fun `a recovered full Robinhood catalog supersedes the startup seed without changing other listings`() {
        val full = fixture()
        val older = full.copy(receivedAt = full.receivedAt.minusSeconds(3600))
        val merged = mergeRobinhoodFeaturedCatalog(full, older, true, full.receivedAt)
        assertSame(full, merged.catalog)
        assertTrue(merged.cachedIds.isEmpty())
        assertEquals(full.instruments.map { it.stock.id }, merged.catalog.instruments.map { it.stock.id })
    }

    @Test fun `new featured snapshot keeps fresh activity visible and retains older rows original expiry`() {
        val fullAt = now.minusSeconds(10)
        val recent = datedRow(now.minusSeconds(1))
        val originalOld = fixture().instruments[1]
        val retainedOld = originalOld.copy(quoteReceivedAt = now.minusSeconds(301),
            stock = originalOld.stock.copy(activity = requireNotNull(originalOld.stock.activity)
                .copy(receivedAt = now.minusSeconds(301), updatedAt = null)))
        val full = fixture().copy(instruments = listOf(retainedOld), receivedAt = fullAt)
        val featured = fixture().copy(instruments = listOf(recent), receivedAt = now)
        val merged = mergeRobinhoodFeaturedCatalog(full, featured, false, now).catalog
        assertEquals(now, merged.receivedAt)
        val added = merged.instruments.single { it.stock.id == recent.stock.id }
        assertSame(recent.stock.activity, added.stock.activity)
        assertEquals(recent.quoteReceivedAt, added.quoteReceivedAt)
        assertNotNull(MarketCatalogExpiryCache().expire(added, merged.receivedAt).activity?.volume24h)
        val old = merged.instruments.single { it.stock.id == retainedOld.stock.id }
        assertSame(retainedOld, old)
        val expiredOld = MarketCatalogExpiryCache().expire(old, merged.receivedAt)
        assertNull(expiredOld.quote.price)
        assertNull(expiredOld.activity?.volume24h)
        assertEquals(retainedOld.stock.activity?.receivedAt, expiredOld.activity?.receivedAt)

        val anchor = robinhoodCompositeReceiptElapsed(merged.receivedAt, fullAt, 1_000L, now, 11_000L)
        assertEquals(11_000L, anchor)
        val elapsed = 11_050L - anchor
        assertTrue(isReferenceDataFresh(recent.stock.activity?.receivedAt, merged.receivedAt, elapsed))
        assertFalse(isReferenceDataFresh(now.minusSeconds(301), merged.receivedAt, elapsed))
        assertFalse(isReferenceDataFresh(recent.stock.activity?.receivedAt, merged.receivedAt, 299_001L))
    }

    @Test fun `a fresh full issuer price and reference do not discard newer featured token volume`() {
        val featuredRow = datedRow(now.minusSeconds(20))
        val reference = RobinhoodStockReference(BigDecimal("100"), BigDecimal("102"), null, null, null,
            BigDecimal.ONE, 18, now.minusSeconds(2), now.minusSeconds(1), false)
        val fullRow = withoutActivity(featuredRow).copy(quoteBasis = "provider_indicative_token",
            quoteReceivedAt = now.minusSeconds(1), stock = withoutActivity(featuredRow).stock.copy(
                quote = featuredRow.stock.quote.copy(price = BigDecimal("101"), asOf = reference.asOf),
                robinhoodReference = reference))
        val full = fixture().copy(instruments = listOf(fullRow), receivedAt = now)
        val featured = fixture().copy(instruments = listOf(featuredRow), receivedAt = now.minusSeconds(10))
        val merged = mergeRobinhoodFeaturedCatalog(full, featured, false, now)
        val row = merged.catalog.instruments.single()
        assertSame(fullRow.stock.quote, row.stock.quote)
        assertSame(reference, row.stock.robinhoodReference)
        assertEquals(fullRow.quoteReceivedAt, row.quoteReceivedAt)
        assertEquals("provider_indicative_token", row.quoteBasis)
        assertSame(featuredRow.stock.activity, row.stock.activity)
        assertEquals(featuredRow.stock.activity?.volume24h, row.volume24h)
        assertEquals(featuredRow.stock.activity?.receivedAt, row.stock.activity?.receivedAt)
        assertTrue(merged.cachedIds.isEmpty())
        assertEquals(1_000L, robinhoodCompositeReceiptElapsed(now, now, 1_000L,
            featured.receivedAt, 500L))
    }

    @Test fun `independent activity refresh preserves a newer full token quote`() {
        val featuredRow = datedRow(now.minusSeconds(10))
        val fullRow = withoutActivity(datedRow(now.minusSeconds(1)))
        val full = fixture().copy(instruments = listOf(fullRow), receivedAt = now)
        val featured = fixture().copy(instruments = listOf(featuredRow), receivedAt = now)
        val row = mergeRobinhoodFeaturedCatalog(full, featured, false, now).catalog.instruments.single()
        assertSame(fullRow.stock.quote, row.stock.quote)
        assertEquals(fullRow.quoteReceivedAt, row.quoteReceivedAt)
        assertSame(featuredRow.stock.activity, row.stock.activity)
    }

    @Test fun `retained Application prewarm does not receive a new freshness window on VM recreation`() {
        val snapshotAt = now.minusSeconds(360)
        val observedElapsed = 1_000_000L
        val anchor = robinhoodCatalogReceiptElapsed(snapshotAt, now.toEpochMilli(), observedElapsed)
        assertEquals(640_000L, anchor)
        assertFalse(isReferenceDataFresh(snapshotAt.minusSeconds(1), snapshotAt, observedElapsed - anchor))
    }

    @Test fun `complete featured net may recover an incomplete same timestamp activity observation`() {
        val featuredRow = datedRow(now.minusSeconds(1))
        val fullRow = featuredRow.copy(stock = featuredRow.stock.copy(activity =
            requireNotNull(featuredRow.stock.activity).copy(netVolume24h = null, netVolumeReason = "Buy sell split unavailable.")))
        val row = mergeRobinhoodFeaturedCatalog(fixture().copy(instruments = listOf(fullRow), receivedAt = now),
            fixture().copy(instruments = listOf(featuredRow), receivedAt = now), false, now).catalog.instruments.single()
        assertSame(featuredRow.stock.activity, row.stock.activity)
        assertEquals(featuredRow.stock.activity?.receivedAt, row.stock.activity?.receivedAt)
    }

    @Test fun `stale or future featured activity never replaces a usable current observation`() {
        val fullRow = datedRow(now.minusSeconds(1))
        val full = fixture().copy(instruments = listOf(fullRow), receivedAt = now)
        for (at in listOf(now.minusSeconds(301), now.plusSeconds(1))) {
            val featured = fixture().copy(instruments = listOf(datedRow(at)), receivedAt = now)
            assertSame(full, mergeRobinhoodFeaturedCatalog(full, featured, false, now).catalog)
        }
    }

    @Test fun `same symbol cannot bridge a provider asset or canonical deployment mismatch`() {
        val featuredRow = datedRow(now.minusSeconds(1))
        val fullRow = withoutActivity(datedRow(now.minusSeconds(2)))
        val deployment = fullRow.stock.deployments.single()
        val mismatches = listOf(
            fullRow.copy(provider = "backed"),
            fullRow.copy(stock = fullRow.stock.copy(deployments = listOf(deployment.copy(chainId = "1")))),
            fullRow.copy(stock = fullRow.stock.copy(deployments = listOf(deployment.copy(network = "Ethereum")))),
            fullRow.copy(stock = fullRow.stock.copy(deployments = listOf(deployment.copy(address = "0x" + "f".repeat(40))))),
            fullRow.copy(stock = fullRow.stock.copy(deployments = listOf(deployment.copy(decimals = 6)))),
        )
        for (wrong in mismatches) {
            val full = fixture().copy(instruments = listOf(wrong), receivedAt = now)
            val featured = fixture().copy(instruments = listOf(featuredRow), receivedAt = now)
            assertSame(full, mergeRobinhoodFeaturedCatalog(full, featured, false, now).catalog)
        }
        val otherAsset = featuredRow.copy(stock = featuredRow.stock.copy(
            id = com.elevencapital.core.stock.StockId("robinhood:another-asset")))
        val full = fixture().copy(instruments = listOf(fullRow), receivedAt = now)
        val merged = mergeRobinhoodFeaturedCatalog(full,
            fixture().copy(instruments = listOf(otherAsset), receivedAt = now), false, now)
        assertSame(full, merged.catalog)
    }

    @Test fun `updated logo and explorer metadata cannot block the same canonical deployment`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/robinhood-preview-catalog.json"))
            .bufferedReader().use { JSONObject(it.readText()) }
        val baseline = LiveMarketDataParser.catalog(raw).instruments.first()
        val sourceRows = raw.getJSONArray("stocks")
        sourceRows.getJSONObject(0).put("logoUrl", "https://images.example/new-logo.png")
            .getJSONArray("deployments").getJSONObject(0).put("explorerUrl", "https://old.blockscout.example/address/ignored")
        val changed = LiveMarketDataParser.catalog(raw).instruments.first()
        assertEquals(baseline.stock.deployments, changed.stock.deployments)
        assertNotEquals(baseline.stock.logo, changed.stock.logo)
        val featuredRow = datedRow(now.minusSeconds(1)).copy(stock = datedRow(now.minusSeconds(1)).stock.copy(
            logo = StockLogoReference("https://images.example/new-logo.png"), deployments = changed.stock.deployments))
        val fullRow = withoutActivity(datedRow(now.minusSeconds(2)))
        val merged = mergeRobinhoodFeaturedCatalog(fixture().copy(instruments = listOf(fullRow), receivedAt = now),
            fixture().copy(instruments = listOf(featuredRow), receivedAt = now), false, now)
        assertEquals(fullRow.stock.logo, merged.catalog.instruments.single().stock.logo)
        assertSame(featuredRow.stock.activity, merged.catalog.instruments.single().stock.activity)
    }

    @Test fun `missing featured rows keep priority and original observations in a large multi-provider catalog`() {
        val featured = fixture()
        val foreign = List(2_000) { index ->
            val source = featured.instruments[index % featured.instruments.size]
            source.copy(provider = "backpack", stock = source.stock.copy(
                id = com.elevencapital.core.stock.StockId("backpack:foreign-$index")))
        }
        val full = featured.copy(instruments = foreign, receivedAt = now,
            providers = featured.providers.map { if (it.id == "robinhood") it.copy(status = "unavailable") else it })
        val merged = mergeRobinhoodFeaturedCatalog(full,
            featured.copy(instruments = featured.instruments.reversed()), true, now)
        assertEquals(foreign.size + RobinhoodFeaturedSymbols.size, merged.catalog.instruments.size)
        for (index in foreign.indices) assertSame(foreign[index], merged.catalog.instruments[index])
        val added = merged.catalog.instruments.drop(foreign.size)
        assertEquals(RobinhoodFeaturedSymbols, added.map { it.stock.symbol })
        for (row in added) {
            val original = featured.instruments.single { it.stock.id == row.stock.id }
            assertSame(original, row)
            assertEquals(original.quoteReceivedAt, row.quoteReceivedAt)
            assertEquals(original.stock.activity?.receivedAt, row.stock.activity?.receivedAt)
        }
        assertEquals(added.map { it.stock.id }.toSet(), merged.cachedIds)
    }

    @Test fun `an existing Robinhood symbol blocks a different asset even when its deployment is invalid`() {
        val featuredRow = datedRow(now.minusSeconds(1))
        val blocker = featuredRow.copy(stock = featuredRow.stock.copy(
            id = com.elevencapital.core.stock.StockId("robinhood:other-asset"),
            deployments = featuredRow.stock.deployments.map { it.copy(address = "invalid") }))
        val full = fixture().copy(instruments = listOf(blocker), receivedAt = now)
        val featured = fixture().copy(instruments = listOf(featuredRow), receivedAt = now)
        val merged = mergeRobinhoodFeaturedCatalog(full, featured, false, now)
        assertSame(full, merged.catalog)
        assertTrue(merged.cachedIds.isEmpty())
    }

    @Test fun `canonical deployment collision is case insensitive and scoped to Robinhood`() {
        val featuredRow = datedRow(now.minusSeconds(1))
        val blocker = featuredRow.copy(stock = featuredRow.stock.copy(
            id = com.elevencapital.core.stock.StockId("robinhood:other-asset"), symbol = "OTHER",
            deployments = featuredRow.stock.deployments.map {
                it.copy(address = "0x" + it.address.substring(2).uppercase(java.util.Locale.ROOT))
            }))
        val full = fixture().copy(instruments = listOf(blocker), receivedAt = now)
        val featured = fixture().copy(instruments = listOf(featuredRow), receivedAt = now)
        assertSame(full, mergeRobinhoodFeaturedCatalog(full, featured, false, now).catalog)
        val foreign = blocker.copy(provider = "backed", stock = blocker.stock.copy(
            id = com.elevencapital.core.stock.StockId("backed:other-asset")))
        val withForeign = mergeRobinhoodFeaturedCatalog(full.copy(instruments = listOf(foreign)), featured, false, now)
        assertEquals(listOf(foreign, featuredRow), withForeign.catalog.instruments)
    }

    @Test fun `duplicate featured identity retains the last valid row without duplicating its priority slot`() {
        val featured = fixture()
        val original = featured.instruments.first()
        val latest = datedRow(now.minusSeconds(1))
        val full = featured.copy(instruments = emptyList(), receivedAt = now)
        val merged = mergeRobinhoodFeaturedCatalog(full,
            featured.copy(instruments = featured.instruments.reversed() + latest), false, now)
        assertEquals(RobinhoodFeaturedSymbols, merged.catalog.instruments.map { it.stock.symbol })
        assertSame(latest, merged.catalog.instruments.single { it.stock.id == original.stock.id })
        assertEquals(latest.quoteReceivedAt, merged.catalog.instruments.single { it.stock.id == original.stock.id }.quoteReceivedAt)
    }

    private fun datedRow(at: Instant): LiveInstrument {
        val row = fixture().instruments.first()
        return row.copy(quoteReceivedAt = at, stock = row.stock.copy(
            activity = requireNotNull(row.stock.activity).copy(receivedAt = at, updatedAt = null)))
    }

    private fun withoutActivity(row: LiveInstrument): LiveInstrument = row.copy(volume24h = null,
        stock = row.stock.copy(activity = requireNotNull(row.stock.activity).copy(
            volume24h = null, netVolume24h = null, receivedAt = null, updatedAt = null,
            volumeReason = "Pool turnover is unavailable.", netVolumeReason = "Pool net flow is unavailable.")))
}

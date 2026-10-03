package com.elevencapital.app.data

import com.elevencapital.app.wallet.encodeBase58
import com.elevencapital.core.stock.ChartRange
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeteoraTokenFeedTest {
    @Test fun `only distinct nonblacklisted DBC pools with a known Solana quote are displayed`() {
        val rows = parseMeteoraTokenFeed(feed(
            pool(volume = "120"),
            pool(volume = "150", address = "BnztueWcXv93mgW7yJe8WYpnCxpz34nujPhfjQT6SLu1"),
            pool(volume = "999", launchpad = "unknown"),
            pool(volume = "999", blacklisted = true),
            pool(volume = "999", tokenAddress = "invalid"),
            pool(volume = "999", quoteAddress = "39ahtL8ynzE4amH26J29C93PA5172V3ft9UuUcqQS8fz"),
        ))
        assertEquals(1, rows.size)
        assertEquals("MET", rows.single().symbol)
        assertEquals("BnztueWcXv93mgW7yJe8WYpnCxpz34nujPhfjQT6SLu1", rows.single().poolAddress)
        assertEquals(BigDecimal("0.4591467391768384"), rows.single().priceUsd)
        assertEquals(BigDecimal("150"), rows.single().volume24hUsd)
    }

    @Test fun `discovery list is limited to the top twenty token mints`() {
        val pools = (1..21).map { index ->
            val poolAddress = encodeBase58(ByteArray(32).also { it[31] = index.toByte() })
            val mintAddress = encodeBase58(ByteArray(32).also { it[31] = (index + 100).toByte() })
            pool(address = poolAddress, tokenAddress = mintAddress, volume = index.toString())
        }
        val rows = parseMeteoraTokenFeed(feed(*pools.toTypedArray()))
        assertEquals(20, rows.size)
        assertEquals(BigDecimal("21"), rows.first().volume24hUsd)
        assertEquals(BigDecimal("2"), rows.last().volume24hUsd)
    }

    @Test fun `missing optional prices do not fabricate values`() {
        val row = parseMeteoraTokenFeed(feed(pool(price = "null", volume = "null", marketCap = "null"))).single()
        assertEquals(null, row.priceUsd)
        assertEquals(null, row.volume24hUsd)
        assertEquals(null, row.marketCapUsd)
    }

    @Test fun `quote token can be on either side of a DBC pool`() {
        val reversed = """{
            "address":"FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB",
            "launchpad":"met-dbc", "is_blacklisted":false, "volume":{"24h":42},
            "token_x":{"address":"So11111111111111111111111111111111111111112", "name":"Wrapped SOL", "symbol":"SOL"},
            "token_y":{"address":"METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL", "name":"Meteora", "symbol":"MET", "price":0.42}
        }"""
        assertEquals("METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL",
            parseMeteoraTokenFeed(feed(reversed)).single().mintAddress)
    }

    @Test fun `malformed feed is an error rather than an empty market`() {
        assertThrows(IOException::class.java) { parseMeteoraTokenFeed("""{"data":{}}""") }
    }

    @Test fun `HTTP failure does not become an empty market`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val feed = MeteoraTokenFeed(endpoint = server.url("/pools").toString())
            assertThrows(IOException::class.java) { runBlocking { feed.topTokens() } }
        }
    }

    @Test fun `public feed can be read without stock backend`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed(pool())))
            val client = MeteoraTokenFeed(endpoint = server.url("/pools").toString(), geckoEndpoint = null)
            assertEquals("MET", runBlocking { client.topTokens() }.single().symbol)
            assertEquals("/pools", server.takeRequest().path)
        }
    }

    @Test fun `public pool metadata adds real artwork change and liquidity without stock backend`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setBody(geckoPool()))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
            )
            val row = runBlocking { client.topTokens() }.single()
            assertEquals("https://assets.geckoterminal.com/token-image", row.imageUrl)
            assertEquals(BigDecimal("-5.25"), row.change24hPercent)
            assertEquals(BigDecimal("0.4591467391768384"), row.priceUsd)
            assertEquals(BigDecimal("120"), row.volume24hUsd)
            assertEquals(BigDecimal("42001"), row.liquidityUsd)
            assertEquals(BigDecimal("1800000"), row.marketCapUsd)
            assertEquals("/pools", server.takeRequest().path)
            assertTrue(server.takeRequest().path!!.startsWith(
                "/gecko/pools/multi/FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB?include=base_token,quote_token"))
        }
    }

    @Test fun `bad artwork and metadata outages fall back to live Meteora values`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setBody(geckoPool(imageUrl = "http://localhost/logo.png")))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
            )
            val row = runBlocking { client.topTokens() }.single()
            assertEquals(null, row.imageUrl)
            assertEquals(BigDecimal("0.4591467391768384"), row.priceUsd)
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setResponseCode(429))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
            )
            val row = runBlocking { client.topTokens() }.single()
            assertEquals(BigDecimal("0.4591467391768384"), row.priceUsd)
            assertEquals(null, row.change24hPercent)
        }
    }

    @Test fun `rate limit keeps cached artwork but always refreshes Meteora quote and volume`() {
        MockWebServer().use { server ->
            var monotonicNanos = 1_000_000_000L
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setBody(geckoPool()))
            server.enqueue(MockResponse().setBody(feed(pool(price = "0.50", volume = "200"))))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setBody(feed(pool(price = "0.75", volume = "300"))))
            server.enqueue(MockResponse().setResponseCode(429))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
                nowNanos = { monotonicNanos },
            )
            runBlocking { client.topTokens() }
            monotonicNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(61)
            val second = runBlocking { client.topTokens() }.single()
            assertEquals("https://assets.geckoterminal.com/token-image", second.imageUrl)
            assertEquals(BigDecimal("-5.25"), second.change24hPercent)
            assertEquals(0, BigDecimal("0.50").compareTo(second.priceUsd))
            assertEquals(BigDecimal("200"), second.volume24hUsd)
            monotonicNanos += java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
            val third = runBlocking { client.topTokens() }.single()
            assertEquals("https://assets.geckoterminal.com/token-image", third.imageUrl)
            assertEquals(null, third.change24hPercent)
            assertEquals(0, BigDecimal("0.75").compareTo(third.priceUsd))
            assertEquals(BigDecimal("300"), third.volume24hUsd)
        }
    }

    @Test fun `selected token refresh uses exact pool after it leaves discovery list`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setBody(geckoPool()))
            server.enqueue(MockResponse().setBody(pool(price = "0.60", volume = "250", marketCap = "2000000")))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools?page=1").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
            )
            val selected = runBlocking { client.topTokens() }.single()
            val current = runBlocking { client.currentToken(selected) }
            assertEquals(0, BigDecimal("0.60").compareTo(current.priceUsd))
            assertEquals(BigDecimal("250"), current.volume24hUsd)
            assertEquals(BigDecimal("2000000"), current.marketCapUsd)
            assertEquals(selected.imageUrl, current.imageUrl)
            assertEquals(selected.change24hPercent, current.change24hPercent)
            server.takeRequest()
            server.takeRequest()
            assertEquals("/pools/FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB",
                server.takeRequest().path)
        }
    }

    @Test fun `selected token refreshes Gecko change no more than once per minute while Meteora figures stay current`() {
        MockWebServer().use { server ->
            var monotonicNanos = 1_000_000_000L
            server.enqueue(MockResponse().setBody(feed(pool())))
            server.enqueue(MockResponse().setBody(geckoPool()))
            server.enqueue(MockResponse().setBody(pool(price = "0.60", volume = "250")))
            server.enqueue(MockResponse().setBody(geckoPool(change = "4.5")))
            server.enqueue(MockResponse().setBody(pool(price = "0.70", volume = "300")))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools?page=1").toString(),
                geckoEndpoint = server.url("/gecko").toString(),
                nowNanos = { monotonicNanos },
            )
            val selected = runBlocking { client.topTokens() }.single()
            monotonicNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(61)
            val first = runBlocking { client.currentToken(selected) }
            assertEquals(BigDecimal("4.5"), first.change24hPercent)
            assertEquals(0, BigDecimal("0.60").compareTo(first.priceUsd))
            assertEquals(BigDecimal("250"), first.volume24hUsd)
            monotonicNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            val second = runBlocking { client.currentToken(first) }
            assertEquals(BigDecimal("4.5"), second.change24hPercent)
            assertEquals(0, BigDecimal("0.70").compareTo(second.priceUsd))
            assertEquals(BigDecimal("300"), second.volume24hUsd)
            assertEquals(5, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            server.takeRequest()
            assertEquals("/gecko/pools/multi/FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB?include=base_token,quote_token",
                server.takeRequest().path)
        }
    }

    @Test fun `selected token refresh rejects a different pool or mint`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(pool(
                address = "BnztueWcXv93mgW7yJe8WYpnCxpz34nujPhfjQT6SLu1")))
            server.enqueue(MockResponse().setBody(pool(
                tokenAddress = "39ahtL8ynzE4amH26J29C93PA5172V3ft9UuUcqQS8fz")))
            val client = MeteoraTokenFeed(
                endpoint = server.url("/pools?page=1").toString(), geckoEndpoint = null)
            val selected = parseMeteoraTokenFeed(feed(pool())).single()
            assertThrows(IOException::class.java) { runBlocking { client.currentToken(selected) } }
            assertThrows(IOException::class.java) { runBlocking { client.currentToken(selected) } }
        }
    }

    @Test fun `token description is bounded plain text and rejects wrong mint`() {
        val mint = "METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL"
        val json = description(mint,
            "Launch <b>now</b>. See https://example.com and [docs](https://docs.example.com).")
        assertEquals("Launch now. See and docs.", parseMeteoraTokenDescription(json, mint))
        assertThrows(IOException::class.java) {
            parseMeteoraTokenDescription(description(
                "39ahtL8ynzE4amH26J29C93PA5172V3ft9UuUcqQS8fz", "Other"), mint)
        }
        assertEquals(500, parseMeteoraTokenDescription(description(mint, "a".repeat(600)), mint)!!.length)
    }

    @Test fun `description lookup caches by mint and serves recent copy on rate limit`() {
        MockWebServer().use { server ->
            var monotonicNanos = 1_000_000_000L
            val mint = "METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL"
            server.enqueue(MockResponse().setBody(description(mint, "A real token description.")))
            server.enqueue(MockResponse().setResponseCode(429))
            val client = MeteoraTokenFeed(
                geckoEndpoint = server.url("/gecko").toString(),
                nowNanos = { monotonicNanos },
            )
            val token = parseMeteoraTokenFeed(feed(pool())).single()
            assertEquals("A real token description.", runBlocking { client.descriptionFor(token) })
            assertEquals("A real token description.", runBlocking { client.descriptionFor(token) })
            assertEquals(1, server.requestCount)
            monotonicNanos += java.util.concurrent.TimeUnit.MINUTES.toNanos(31)
            assertEquals("A real token description.", runBlocking { client.descriptionFor(token) })
            assertEquals("A real token description.", runBlocking { client.descriptionFor(token) })
            assertEquals(2, server.requestCount)
            assertEquals("/gecko/tokens/$mint/info", server.takeRequest().path)
        }
    }

    @Test fun `chart uses actual token USD close candles for selected range`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "data":{"attributes":{"ohlcv_list":[
                    [100000,1,2,0.5,1.5,50],
                    [99000,1,2,0.5,1.25,40],
                    [1000,1,2,0.5,4,30],
                    [99000,1,2,0.5,0,40]
                ]}}
            }"""))
            val client = MeteoraTokenFeed(
                geckoEndpoint = server.url("/gecko").toString(),
                nowEpochSeconds = { 100000L },
            )
            val token = parseMeteoraTokenFeed(feed(pool())).single()
            val chart = runBlocking { client.chartFor(token, ChartRange.ONE_DAY) }
            assertEquals(listOf(99000L, 100000L), chart.points.map { it.timestampSeconds })
            assertEquals(listOf(BigDecimal("1.25"), BigDecimal("1.5")), chart.points.map { it.priceUsd })
            assertTrue(server.takeRequest().path!!.contains(
                "/pools/FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB/ohlcv/minute?aggregate=15&limit=100&currency=usd&token=METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL"))
        }
    }

    @Test fun `rate limited chart uses recent cached candles without extending their lifetime`() {
        MockWebServer().use { server ->
            var monotonicNanos = 1_000_000_000L
            server.enqueue(MockResponse().setBody("""{
                "data":{"attributes":{"ohlcv_list":[[100000,1,2,0.5,1.5,50]]}}
            }"""))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))
            val client = MeteoraTokenFeed(
                geckoEndpoint = server.url("/gecko").toString(),
                nowEpochSeconds = { 100000L },
                nowNanos = { monotonicNanos },
            )
            val token = parseMeteoraTokenFeed(feed(pool())).single()
            assertEquals(1, runBlocking { client.chartFor(token) }.points.size)
            monotonicNanos += java.util.concurrent.TimeUnit.SECONDS.toNanos(31)
            assertEquals(1, runBlocking { client.chartFor(token) }.points.size)
            monotonicNanos += java.util.concurrent.TimeUnit.MINUTES.toNanos(11)
            assertThrows(IOException::class.java) { runBlocking { client.chartFor(token) } }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `simultaneous chart callers share one request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{
                "data":{"attributes":{"ohlcv_list":[[100000,1,2,0.5,1.5,50]]}}
            }""").setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS))
            val client = MeteoraTokenFeed(
                geckoEndpoint = server.url("/gecko").toString(),
                nowEpochSeconds = { 100000L },
            )
            val token = parseMeteoraTokenFeed(feed(pool())).single()
            val first = async { client.chartFor(token) }
            val second = async { client.chartFor(token) }
            assertEquals(first.await(), second.await())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `oversized feed is rejected before parsing`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(1_048_577)))
            val client = MeteoraTokenFeed(endpoint = server.url("/pools").toString())
            assertThrows(IOException::class.java) { runBlocking { client.topTokens() } }
        }
    }

    private fun feed(vararg pools: String) = """{"data":[${pools.joinToString(",")}]}"""

    private fun geckoPool(
        imageUrl: String = "https://assets.geckoterminal.com/token-image",
        change: String = "-5.25",
    ) = """{
        "data":[{"type":"pool","attributes":{
            "address":"FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB",
            "base_token_price_usd":"0.46", "volume_usd":{"h24":"166"},
            "price_change_percentage":{"h24":"$change"}, "reserve_in_usd":"42000"
        },"relationships":{"base_token":{"data":{"id":"solana_METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL"}},
            "quote_token":{"data":{"id":"solana_EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"}}}}],
        "included":[{"type":"token","attributes":{
            "address":"METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL",
            "image_url":"$imageUrl"
        }}]
    }"""

    private fun description(mint: String, text: String) = """{
        "data":{"id":"solana_$mint","type":"token","attributes":{
            "address":"$mint", "description":"$text"
        }}
    }"""

    private fun pool(
        volume: String = "120",
        address: String = "FiYyzxapRvkbUhF5ZD3mJigWwqBGhtVLH49MDSgCLHdB",
        tokenAddress: String = "METvsvVRapdj9cFLzq4Tr43xK4tAjQfwX76z3n6mWQL",
        quoteAddress: String = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        launchpad: String = "met-dbc",
        blacklisted: Boolean = false,
        price: String = "0.4591467391768384",
        marketCap: String = "1800000",
    ) = """{
        "address":"$address", "launchpad":"$launchpad", "is_blacklisted":$blacklisted,
        "volume":{"24h":$volume}, "tvl":42001,
        "token_x":{"address":"$tokenAddress", "name":"Meteora", "symbol":"MET", "price":$price, "market_cap":$marketCap},
        "token_y":{"address":"$quoteAddress", "name":"USD Coin", "symbol":"USDC"}
    }"""
}

package com.elevencapital.app

import com.elevencapital.app.data.*
import com.elevencapital.core.stock.*
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class OrderedMarketCatalogTest {
    private val time = Instant.parse("2026-09-19T00:00:00Z")
    private val providers = listOf(LiveProviderStatus("backed", "ok"), LiveProviderStatus("backpack", "ok"))
    private fun row(id: String, price: String = "100.000000000000000001") = LiveInstrument(
        Stock(StockId(id), id.substringAfter(':'), "Test stock", StockQuote(BigDecimal(price), "USD")),
        id.substringBefore(':'), "Source", null, time, "onchain_token_market", "quoted_currency",
    )
    private fun snapshot(revision: Long = 1, session: String = "a", vararg rows: LiveInstrument) =
        MarketStreamEvent.Snapshot(LiveCatalog(rows.toList(), providers, time), session, revision)
    private fun delta(revision: Long, rows: List<LiveInstrument> = emptyList(), removed: Set<StockId> = emptySet(), session: String = "a") =
        MarketStreamEvent.Delta(rows, providers, removed, time.plusSeconds(revision), session, revision)

    @Test fun `gapped revision merges only changed rows and preserves exact money`() {
        val store = OrderedMarketCatalog()
        val unchanged = row("backed:unchanged")
        store.accept(snapshot(rows = arrayOf(unchanged, row("backpack:changed"))))
        val next = requireNotNull(store.accept(delta(13, listOf(row("backpack:changed", "102.000000000000000019")))))
        assertSame(unchanged, next.instruments.first())
        assertEquals(BigDecimal("102.000000000000000019"), next.instruments.last().stock.quote.price)
        assertEquals(2, next.instruments.size)
    }

    @Test fun `duplicate older and foreign session frames cannot roll back values`() {
        val store = OrderedMarketCatalog()
        store.accept(snapshot(rows = arrayOf(row("backed:test"))))
        store.accept(delta(7, listOf(row("backed:test", "107"))))
        assertNull(store.accept(delta(7, listOf(row("backed:test", "2")))))
        assertNull(store.accept(delta(6, listOf(row("backed:test", "1")))))
        assertNull(store.accept(delta(8, listOf(row("backed:test", "3")), session = "old")))
        assertNull(store.accept(snapshot(6, "a", row("backed:test", "4"))))
        assertEquals(BigDecimal("107"), store.catalog!!.instruments.single().stock.quote.price)
    }

    @Test fun `reconnect requires new snapshot before deltas and permits server revision reset`() {
        val store = OrderedMarketCatalog()
        store.accept(snapshot(99, "a", row("backed:test")))
        store.beginConnection()
        assertNull(store.accept(delta(100, listOf(row("backed:test", "1")))))
        assertEquals(BigDecimal("100.000000000000000001"), store.catalog!!.instruments.single().stock.quote.price)
        assertNotNull(store.accept(snapshot(0, "b", row("backed:test", "80"))))
        assertNull(store.accept(snapshot(101, "a", row("backed:test", "1"))))
        assertEquals(BigDecimal("80"), store.catalog!!.instruments.single().stock.quote.price)
    }

    @Test fun `authoritative membership snapshot removes delisted instruments without merging ghost rows`() {
        val store = OrderedMarketCatalog()
        store.accept(snapshot(rows = arrayOf(row("backed:one"), row("backed:two"))))
        store.accept(snapshot(2, "a", row("backed:two")))
        assertEquals(listOf(StockId("backed:two")), store.catalog!!.instruments.map { it.stock.id })
        store.accept(delta(3, removed = setOf(StockId("backed:two"))))
        assertTrue(store.catalog!!.instruments.isEmpty())
    }

    @Test fun `chart revision is independently ordered and never changes the quote`() {
        val store = OrderedMarketCatalog()
        val stock = row("backed:test")
        store.accept(snapshot(20, "a", stock))
        fun chart(revision: Long, range: ChartRange = ChartRange.ONE_DAY, session: String = "a") =
            MarketStreamEvent.Chart(LiveChart(stock.stock.id, range, "ok", "USD",
                listOf(StockPricePoint(time, BigDecimal("99"))), "observed", "Observed since service start"), session, revision)
        assertNotNull(store.accept(chart(18)))
        assertNull(store.accept(chart(17)))
        assertNull(store.accept(chart(18)))
        assertNotNull(store.accept(chart(18, ChartRange.ONE_WEEK)))
        assertNull(store.accept(chart(21, session = "old")))
        assertEquals(stock.stock.quote.price, store.catalog!!.instruments.single().stock.quote.price)
    }

    @Test fun `subscription uses exact identities and omits detail to unsubscribe`() {
        val ids = setOf(StockId("backed:MSFTx"), StockId("backpack:MSFT.US"))
        val json = org.json.JSONObject(MarketSubscription(ids, StockId("backed:MSFTx") to ChartRange.ONE_DAY).toJson())
        assertEquals(2, json.getJSONArray("ids").length())
        assertEquals("backed:MSFTx", json.getJSONObject("detail").getString("id"))
        assertFalse(org.json.JSONObject(MarketSubscription().toJson()).has("detail"))
        assertThrows(IllegalArgumentException::class.java) {
            MarketSubscription((0..100).map { StockId("backed:$it") }.toSet())
        }
    }
    @Test fun `overlapping viewports release only their own shared subscriptions`() {
        val subscriptions = MarketViewportSubscriptions()
        val a = StockId("backed:a")
        val b = StockId("backed:b")
        assertEquals(setOf(a), subscriptions.update("markets", setOf(a)))
        assertEquals(setOf(a, b), subscriptions.update("home", setOf(a, b)))
        assertEquals(setOf(a, b), subscriptions.update("markets", emptySet()))
        assertEquals(setOf(a, b), subscriptions.update("account", setOf(b)))
        assertEquals(setOf(b), subscriptions.update("home", emptySet()))
        subscriptions.clear()
        assertTrue(subscriptions.update("account", emptySet()).isEmpty())
    }

    @Test fun `combined viewport interest is bounded without changing source identities`() {
        val subscriptions = MarketViewportSubscriptions()
        subscriptions.update("markets", (1..75).map { StockId("backed:$it") }.toSet())
        val combined = subscriptions.update("home", (1..75).map { StockId("backpack:$it") }.toSet())
        assertEquals(100, combined.size)
        assertEquals(75, combined.count { it.value.startsWith("backed:") })
        assertEquals(25, combined.count { it.value.startsWith("backpack:") })
    }

    @Test fun `frame coalescer preserves the final state of a five thousand delta burst`() {
        val initial = (0 until 120).map { row("backed:$it", "0") }
        val expected = initial.associate { it.stock.id to BigDecimal.ZERO }.toMutableMap()
        val coalescer = MarketStreamFrameCoalescer()
        coalescer.offer(snapshot(0, "burst", *initial.toTypedArray()))
        for (revision in 1L..5_000L) {
            val id = StockId("backed:${revision % initial.size}")
            val price = revision.toString()
            expected[id] = BigDecimal(price)
            coalescer.offer(delta(revision, listOf(row(id.value, price)), session = "burst"))
        }
        val removed = (110 until 120).map { StockId("backed:$it") }.toSet()
        removed.forEach(expected::remove)
        coalescer.offer(delta(5_001, removed = removed, session = "burst"))

        val frames = coalescer.drain()
        assertEquals("one snapshot barrier and one reduced absolute delta", 2, frames.size)
        val reduced = frames.last() as MarketStreamEvent.Delta
        assertEquals(5_001L, reduced.revision)
        assertEquals(removed, reduced.removedIds)
        val store = OrderedMarketCatalog()
        frames.forEach { event ->
            when (event) {
                is MarketStreamEvent.Snapshot -> store.accept(event)
                is MarketStreamEvent.Delta -> store.accept(event)
                else -> fail("Unexpected frame: $event")
            }
        }
        val actual = requireNotNull(store.catalog).instruments.associate { it.stock.id to it.stock.quote.price }
        assertEquals(expected, actual)
    }

    @Test fun `remove then reinsert remains an ordered barrier while coalescing`() {
        val one = row("backed:one", "1")
        val two = row("backed:two", "2")
        val store = OrderedMarketCatalog()
        store.accept(snapshot(0, "order", one, two))
        val coalescer = MarketStreamFrameCoalescer()
        coalescer.offer(delta(1, removed = setOf(one.stock.id), session = "order"))
        coalescer.offer(delta(2, rows = listOf(row("backed:one", "3")), session = "order"))
        val frames = coalescer.drain()
        assertEquals(2, frames.size)
        frames.forEach { store.accept(it as MarketStreamEvent.Delta) }
        assertEquals(listOf(two.stock.id, one.stock.id), store.catalog?.instruments?.map { it.stock.id })
        assertEquals(BigDecimal("3"), store.catalog?.instruments?.last()?.stock?.quote?.price)
    }

    @Test fun `newer consecutive snapshot replaces older pending snapshot without losing its barrier`() {
        val coalescer = MarketStreamFrameCoalescer()
        coalescer.offer(snapshot(1, "same", row("backed:one", "1")))
        coalescer.offer(snapshot(3, "same", row("backed:one", "3")))
        coalescer.offer(snapshot(2, "same", row("backed:one", "2")))
        val retained = coalescer.drain().single() as MarketStreamEvent.Snapshot
        assertEquals(3L, retained.revision)
        assertEquals(BigDecimal("3"), retained.catalog.instruments.single().stock.quote.price)
    }

    @Test fun `pathological nonmergeable frames fail before the pending queue can grow`() {
        val coalescer = MarketStreamFrameCoalescer()
        repeat(256) { index ->
            coalescer.offer(delta(index.toLong(), rows = listOf(row("backed:$index")), session = "session-${index % 2}"))
        }
        assertThrows(IllegalStateException::class.java) {
            coalescer.offer(delta(257, rows = listOf(row("backed:overflow")), session = "overflow"))
        }
        assertEquals(256, coalescer.drain().size)
    }

}

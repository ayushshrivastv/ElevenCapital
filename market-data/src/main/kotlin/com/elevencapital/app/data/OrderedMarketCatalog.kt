package com.elevencapital.app.data

import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.StockId

/**
 * The server revision orders financial observations; timestamps remain provider facts.
 * Revision gaps are normal because a socket only receives subscribed instruments.
 * Called by one foreground collector. Socket cancellation prevents old collectors publishing.
 */
class OrderedMarketCatalog {
    private var sessionId: String? = null
    private var revision = -1L
    private var waitingForSnapshot = true
    private val chartRevisions = mutableMapOf<Pair<StockId, ChartRange>, Long>()
    var catalog: LiveCatalog? = null
        private set

    fun beginConnection() {
        waitingForSnapshot = true
        sessionId = null
        revision = -1L
        chartRevisions.clear()
        // Keep the last observation visible while transport recovers; it is never marked fresh here.
    }

    fun accept(snapshot: MarketStreamEvent.Snapshot): LiveCatalog? {
        if (!waitingForSnapshot && (snapshot.sessionId != sessionId || snapshot.revision <= revision)) return null
        val oldRows = catalog?.instruments.orEmpty().associateBy { it.stock.id }
        val rows = snapshot.catalog.instruments.map { incoming ->
            oldRows[incoming.stock.id]?.takeIf { it == incoming } ?: incoming
        }
        catalog = snapshot.catalog.copy(instruments = rows)
        if (snapshot.sessionId != sessionId) chartRevisions.clear()
        sessionId = snapshot.sessionId
        revision = snapshot.revision
        waitingForSnapshot = false
        return catalog
    }

    fun accept(delta: MarketStreamEvent.Delta): LiveCatalog? {
        val previous = catalog ?: return null
        if (waitingForSnapshot || delta.sessionId != sessionId || delta.revision <= revision) return null
        val rows = previous.instruments.associateByTo(linkedMapOf()) { it.stock.id }
        delta.removedIds.forEach { id ->
            rows.remove(id)
            chartRevisions.keys.removeAll { it.first == id }
        }
        delta.instruments.forEach { incoming ->
            // Preserve object identities so Compose can skip unchanged rows.
            if (rows[incoming.stock.id] != incoming) rows[incoming.stock.id] = incoming
        }
        require(rows.size <= 5_000)
        catalog = LiveCatalog(rows.values.toList(), delta.providers, delta.receivedAt)
        revision = delta.revision
        return catalog
    }

    fun accept(chart: MarketStreamEvent.Chart): LiveChart? {
        if (waitingForSnapshot || chart.sessionId != sessionId) return null
        val data = chart.chart
        if (catalog?.instruments?.none { it.stock.id == data.stockId } != false) return null
        val key = data.stockId to data.range
        if (chart.revision <= (chartRevisions[key] ?: -1L)) return null
        chartRevisions[key] = chart.revision
        return data
    }
}

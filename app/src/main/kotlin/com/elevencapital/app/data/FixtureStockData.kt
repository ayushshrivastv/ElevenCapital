package com.elevencapital.app.data

import android.content.res.AssetManager
import com.elevencapital.app.screens.DetailNewsItem
import com.elevencapital.app.screens.DetailChartBounds
import com.elevencapital.app.screens.DetailFeedRow
import com.elevencapital.app.screens.DetailFeedSide
import com.elevencapital.app.screens.DetailInfoCell
import com.elevencapital.app.screens.DetailPeriod
import com.elevencapital.app.screens.DetailPresentation
import com.elevencapital.app.screens.DetailRangeChange
import com.elevencapital.app.screens.PrimaryStockRow
import com.elevencapital.core.stock.ChartRange
import com.elevencapital.core.stock.InMemoryStockRepository
import com.elevencapital.core.stock.Stock
import com.elevencapital.core.stock.StockId
import com.elevencapital.core.stock.StockLogoReference
import com.elevencapital.core.stock.StockPricePoint
import com.elevencapital.core.stock.StockQuote
import com.elevencapital.core.stock.StockStatistics
import java.math.BigDecimal
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Explicit screenshot/adaptation values, not connected account balances or live quotes. */
data class FixturePortfolio(
    val balance: BigDecimal,
    val homeChangeAmount: BigDecimal?,
    val homeChangePercent: BigDecimal?,
    val accountChangeAmount: BigDecimal?,
    val accountChangePercent: BigDecimal?,
    val paymentBalance: BigDecimal,
    val fixtureLabel: String,
)

/**
 * Loads stock identities and optional presentation metadata from bundled JSON.
 * Adapters always receive current repository Stocks; metadata never overrides their quotes.
 */
class FixtureStockData private constructor(
    val repository: InMemoryStockRepository,
    val defaultStockId: StockId,
    val initialWatchedIds: Set<StockId>,
    val portfolio: FixturePortfolio,
    private val metadata: Map<StockId, RowMetadata>,
    private val details: Map<StockId, DetailPresentation>,
    private val holdingQuantities: Map<StockId, BigDecimal>,
) {
    val fixtureLabel: String get() = portfolio.fixtureLabel

    fun rows(
        stocks: List<Stock> = repository.catalog.value,
        watchedIds: Set<StockId> = initialWatchedIds,
    ): List<PrimaryStockRow> = stocks.map { stock -> row(stock, watchedIds) }

    fun watchlist(
        stocks: List<Stock> = repository.catalog.value,
        watchedIds: Set<StockId> = initialWatchedIds,
    ): List<PrimaryStockRow> = rows(stocks, watchedIds).filter { it.watched }

    fun holdings(
        stocks: List<Stock> = repository.catalog.value,
        watchedIds: Set<StockId> = initialWatchedIds,
    ): List<PrimaryStockRow> = stocks.mapNotNull { stock ->
        val quantity = holdingQuantities[stock.id] ?: return@mapNotNull null
        row(stock, watchedIds).copy(
            quantity = quantity,
            holdingValue = stock.quote.price?.let(quantity::multiply),
        )
    }

    fun holdingQuantity(stockId: StockId): BigDecimal? = holdingQuantities[stockId]

    fun detail(stockId: StockId): DetailPresentation = details[stockId] ?: DetailPresentation()

    private fun row(stock: Stock, watchedIds: Set<StockId>): PrimaryStockRow {
        val row = metadata[stock.id]
        return PrimaryStockRow(
            stock = stock,
            ageLabel = row?.ageLabel,
            volume = row?.volume,
            netVolume = row?.netVolume,
            watched = stock.id in watchedIds,
            verified = row?.verified ?: false,
        )
    }

    companion object {
        fun load(assets: AssetManager): FixtureStockData {
            val source = assets.json("fixtures/stock-catalog.json")
            require(source.getBoolean("fixture")) { "Bundled stock data must be marked as fixtures." }
            require(source.getInt("schemaVersion") == 1) { "Unsupported stock fixture schema." }
            val rows = linkedMapOf<StockId, RowMetadata>()
            val details = linkedMapOf<StockId, DetailPresentation>()
            val stocks = source.getJSONArray("stocks").objects().map { item ->
                val id = StockId(item.getString("id"))
                val row = item.optJSONObject("row")
                rows[id] = RowMetadata(
                    ageLabel = row?.stringOrNull("ageLabel"),
                    volume = row?.decimalOrNull("volume"),
                    netVolume = row?.decimalOrNull("netVolume"),
                    verified = row?.optBoolean("verified", false) ?: false,
                )
                details[id] = parseDetail(item.optJSONObject("detail"), rows.getValue(id))
                Stock(
                    id = id,
                    symbol = item.getString("symbol"),
                    name = item.getString("name"),
                    quote = StockQuote(
                        price = item.decimal("price"),
                        currencyCode = item.getString("currencyCode"),
                        changeAmount = item.decimalOrNull("changeAmount"),
                        changePercent = item.decimalOrNull("changePercent"),
                        asOf = null,
                    ),
                    logo = item.stringOrNull("logo")?.let(::StockLogoReference),
                    statistics = item.optJSONObject("statistics")?.let { stats ->
                        StockStatistics(
                            marketCapitalization = stats.decimalOrNull("marketCapitalization"),
                            liquidity = stats.decimalOrNull("liquidity"),
                            holderCount = stats.longOrNull("holderCount"),
                            organicScore = stats.decimalOrNull("organizationScore"),
                        )
                    },
                    description = item.stringOrNull("description"),
                    charts = item.optJSONObject("chartAssets")?.entries()?.associate { (range, path) ->
                        ChartRange.valueOf(range) to parseChart(assets.json(path as String), range)
                    }.orEmpty(),
                    informationUrl = item.stringOrNull("informationUrl"),
                )
            }
            val repository = InMemoryStockRepository(stocks)
            val defaultStockId = StockId(source.getString("defaultStockId"))
            require(repository.getStock(defaultStockId) != null) { "Default fixture stock is missing." }
            val watched = source.getJSONArray("initialWatchedIds").strings().map(::StockId).toSet()
            require(watched.all { repository.getStock(it) != null }) { "A watched fixture stock is missing." }
            val portfolio = source.getJSONObject("portfolio")
            val holdings = portfolio.getJSONArray("holdings").objects().associate { holding ->
                val id = StockId(holding.getString("stockId"))
                require(repository.getStock(id) != null) { "A held fixture stock is missing." }
                val quantity = holding.decimal("quantity")
                require(quantity.signum() >= 0) { "A fixture holding quantity must not be negative." }
                id to quantity
            }
            return FixtureStockData(
                repository = repository,
                defaultStockId = defaultStockId,
                initialWatchedIds = watched,
                portfolio = FixturePortfolio(
                    balance = portfolio.decimal("balance"),
                    homeChangeAmount = portfolio.decimalOrNull("homeChangeAmount"),
                    homeChangePercent = portfolio.decimalOrNull("homeChangePercent"),
                    accountChangeAmount = portfolio.decimalOrNull("accountChangeAmount"),
                    accountChangePercent = portfolio.decimalOrNull("accountChangePercent"),
                    paymentBalance = portfolio.decimal("paymentBalance"),
                    fixtureLabel = source.getString("fixtureLabel"),
                ),
                metadata = rows,
                details = details,
                holdingQuantities = holdings,
            )
        }
    }
}

private data class RowMetadata(
    val ageLabel: String?,
    val volume: BigDecimal?,
    val netVolume: BigDecimal?,
    val verified: Boolean,
)

private fun parseDetail(source: JSONObject?, row: RowMetadata): DetailPresentation {
    if (source == null) return DetailPresentation(ageLabel = row.ageLabel, verified = row.verified)
    return DetailPresentation(
        ageLabel = row.ageLabel,
        verified = row.verified,
        news = source.optJSONArray("news")?.objects()?.map { item ->
            DetailNewsItem(
                author = item.getString("author"), ageLabel = item.getString("ageLabel"),
                body = item.getString("body"), authorImageReference = item.stringOrNull("authorImageReference"),
                imageReference = item.stringOrNull("imageReference"), viewsLabel = item.stringOrNull("viewsLabel"),
                likesLabel = item.stringOrNull("likesLabel"),
            )
        }.orEmpty(),
        headerLogo = source.stringOrNull("headerLogo")?.let(::StockLogoReference),
        periodReturns = source.optJSONObject("periodReturns")?.entries()?.associate { (key, value) ->
            DetailPeriod.valueOf(key) to BigDecimal(value.toString())
        }.orEmpty(),
        volume24h = source.decimalOrNull("volume24h"),
        netVolume24h = source.decimalOrNull("netVolume24h"),
        traders24h = source.longOrNull("traders24h"),
        netBuyers24h = source.longOrNull("netBuyers24h"),
        sellVolumeFraction = source.decimalOrNull("sellVolumeFraction")?.toFloat(),
        sellTraderFraction = source.decimalOrNull("sellTraderFraction")?.toFloat(),
        netBuyTrend = source.optJSONArray("netBuyTrend")?.let { values ->
            (0 until values.length()).map { BigDecimal(values.get(it).toString()) }
        }.orEmpty(),
        volumeChangePercent = source.decimalOrNull("volumeChangePercent"),
        liquidityChangePercent = source.decimalOrNull("liquidityChangePercent"),
        holdersChangePercent = source.decimalOrNull("holdersChangePercent"),
        stockInfoCells = source.optJSONArray("stockInfoCells")?.objects()?.map { cell ->
            DetailInfoCell(cell.getString("label"), cell.getString("value"), cell.stringOrNull("badge"))
        }.orEmpty(),
        chartBounds = source.optJSONObject("chartBounds")?.entries()?.associate { (key, raw) ->
            val bounds = raw as JSONObject
            ChartRange.valueOf(key) to DetailChartBounds(bounds.decimal("low"), bounds.decimal("high"))
        }.orEmpty(),
        rangeChanges = source.optJSONObject("rangeChanges")?.entries()?.associate { (key, raw) ->
            val change = raw as JSONObject
            ChartRange.valueOf(key) to DetailRangeChange(change.decimalOrNull("amount"), change.decimalOrNull("percent"))
        }.orEmpty(),
        feedRows = source.optJSONArray("feedRows")?.objects()?.map { row ->
            DetailFeedRow(
                ageLabel = row.getString("ageLabel"),
                side = DetailFeedSide.valueOf(row.getString("side")),
                price = row.decimal("price"),
                volume = row.decimal("volume"),
                quantity = row.decimal("quantity"),
                participantLabel = row.stringOrNull("participantLabel"),
            )
        }.orEmpty(),
    )
}

private fun parseChart(source: JSONObject, expectedRange: String): List<StockPricePoint> {
    require(source.getBoolean("fixture")) { "Screenshot chart must be marked as a fixture." }
    require(source.getString("range") == expectedRange) { "Chart fixture range does not match its catalog entry." }
    val anchor = Instant.parse(source.getString("anchorInstant"))
    return source.getJSONArray("points").objects().map { point ->
        StockPricePoint(anchor.plusSeconds(point.getLong("offsetSeconds")), point.decimal("price"))
    }.also { points ->
        require(points.zipWithNext().all { (left, right) -> left.timestamp < right.timestamp }) {
            "Screenshot chart points must have increasing coordinate timestamps."
        }
    }
}

private fun AssetManager.json(path: String): JSONObject = open(path).bufferedReader().use { JSONObject(it.readText()) }
private fun JSONObject.decimal(key: String): BigDecimal = BigDecimal(get(key).toString())
private fun JSONObject.decimalOrNull(key: String): BigDecimal? = if (isNull(key)) null else decimal(key)
private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
private fun JSONObject.entries(): List<Pair<String, Any>> = keys().asSequence().map { it to get(it) }.toList()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

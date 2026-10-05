package com.elevencapital.app.screens

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

/** One local prediction entry. Amounts are USD quote accounting units, not wallet token balances. */
internal data class PredictionLedgerEntry(
    val id: String,
    val poolId: Long,
    val direction: PredictionDirection,
    val stakeUsd: BigDecimal,
    val entryQuote: BigDecimal,
    val entryPriceUsd: BigDecimal,
    val oracleSourceId: String = "preview",
    val anchorOracleQuote: PredictionOracleQuote? = null,
    val entryPublishTime: Long? = null,
    /** The pool's immutable USD half-width, retained for exits and settlement after process death. */
    val bandUsd: Double = PredictionPreviewRound.BAND,
    /** The pool's immutable prestart 90-second volatility forecast, when available. */
    val forecastSigmaUsd: Double? = null,
    val btcMarket: Boolean = false,
) {
    val isLive: Boolean get() = oracleSourceId == "pyth"
    val quotedWinningPayoutUsd: BigDecimal get() =
        stakeUsd.divide(entryQuote, 6, if (isLive) RoundingMode.DOWN else RoundingMode.HALF_UP)
}

internal data class PredictionPoolTerms(
    val anchorOracleQuote: PredictionOracleQuote,
    val bandUsd: Double,
    val forecastSigmaUsd: Double? = null,
    val btcMarket: Boolean = false,
)

/**
 * Pure local accounting for the prediction practice experience. It never represents spendable
 * wallet funds and never submits a transaction.
 */
internal class PredictionWalletLedger private constructor(
    val availableQuoteUsd: BigDecimal,
    val currentPoolId: Long?,
    val realizedPoolPnlUsd: BigDecimal,
    /** Null only for migrated v1 state that never recorded all prior stakes. */
    val poolStakedUsd: BigDecimal?,
    val lifetimeRealizedPnlUsd: BigDecimal,
    val finalizedOutcome: PredictionDirection?,
    private val openById: Map<String, PredictionLedgerEntry>,
    private val closedIds: Set<String>,
    private val historyRows: List<PredictionHistoryRow>,
    /** False only after loading a v1 snapshot whose old closed-entry detail was never saved. */
    val historyComplete: Boolean,
    private val closedLivePools: Set<Long>,
    /** Remembers this epoch's targets even when its last position is traded out. */
    val currentLivePoolTerms: PredictionPoolTerms?,
) {
    val reservedQuoteUsd: BigDecimal get() = openById.values.fold(ZERO) { total, entry -> total + entry.stakeUsd }
    val openEntryCount: Int get() = openById.size
    val openEntries: List<PredictionLedgerEntry> get() = openById.values.toList()
    val history: List<PredictionHistoryRow> get() = historyRows.toList()
    val localUsdcNetFundingUsd: BigDecimal get() = historyRows.fold(ZERO) { total, row ->
        when (row.kind) {
            PredictionHistoryKind.LOCAL_USDC_ADD -> total + row.amountUsd
            PredictionHistoryKind.LOCAL_USDC_WITHDRAW -> total - row.amountUsd
            else -> total
        }
    }

    fun openEntry(id: String): PredictionLedgerEntry? = openById[id]
    /** Moves practice USDC into this local quote ledger. */
    fun creditLocalUsdc(requestId: String, amountUsd: BigDecimal, atMillis: Long): PredictionWalletLedger =
        recordLocalUsdc(requestId, amountUsd, atMillis, PredictionHistoryKind.LOCAL_USDC_ADD)

    /** Moves available local USDC back to the source wallet. */
    fun withdrawLocalUsdc(requestId: String, amountUsd: BigDecimal, atMillis: Long): PredictionWalletLedger =
        recordLocalUsdc(requestId, amountUsd, atMillis, PredictionHistoryKind.LOCAL_USDC_WITHDRAW)

    private fun recordLocalUsdc(requestId: String, amountUsd: BigDecimal, atMillis: Long,
        kind: PredictionHistoryKind): PredictionWalletLedger {
        requireEntryId(requestId)
        requireTime(atMillis)
        val amount = quoteAmount(amountUsd, allowZero = false)
        val id = "local:$requestId"
        historyRows.firstOrNull { it.id == id }?.let { previous ->
            require(previous.kind == kind && previous.amountUsd == amount) {
                "Local USDC request ID has conflicting details"
            }
            return this
        }
        check(historyRows.size < MAX_HISTORY) { "Prediction history is full" }
        if (kind == PredictionHistoryKind.LOCAL_USDC_WITHDRAW) {
            check(availableQuoteUsd >= amount) { "Insufficient available USDC" }
        }
        return changed(
            availableQuoteUsd = if (kind == PredictionHistoryKind.LOCAL_USDC_ADD)
                availableQuoteUsd + amount else availableQuoteUsd - amount,
            historyRows = historyRows + PredictionHistoryRow(id, kind,
                PredictionHistoryStatus.CONFIRMED, amount,
                createdAtMillis = atMillis, completedAtMillis = atMillis),
        )
    }

    /** Advances the displayed pool. All old entries must close before its realized P/L resets. */
    fun beginPool(poolId: Long): PredictionWalletLedger {
        val current = currentPoolId
        if (current == poolId) return this
        require(current == null || poolId > current) { "Pool IDs must advance" }
        check(openById.isEmpty()) { "Settle or trade out open entries before the next pool" }
        return changed(currentPoolId = poolId, realizedPoolPnlUsd = ZERO,
            poolStakedUsd = ZERO, finalizedOutcome = null, currentLivePoolTerms = null)
    }

    /** Displays a new epoch while older live entries wait for their own historical boundary proof. */
    fun beginLivePool(poolId: Long): PredictionWalletLedger {
        require(poolId >= 0 && poolId < Long.MAX_VALUE / PredictionPreviewRound.DURATION_MILLIS)
        if (currentPoolId == poolId) return this
        require(currentPoolId == null || poolId > currentPoolId) { "Pool IDs must advance" }
        check(openById.values.all { it.isLive && it.poolId < poolId }) {
            "Legacy positions must be refunded before opening a live epoch"
        }
        return changed(currentPoolId = poolId, realizedPoolPnlUsd = ZERO,
            poolStakedUsd = ZERO, finalizedOutcome = null, currentLivePoolTerms = null)
    }

    /** Debits local quote credit using one fresh Pyth observation and fixed pool targets. */
    fun enterLive(
        entryId: String,
        round: PredictionPreviewRound,
        direction: PredictionDirection,
        stakeUsd: BigDecimal,
        nowMillis: Long,
    ): PredictionWalletLedger {
        requireTime(nowMillis)
        check(round.canEnterLiveAt(nowMillis)) { "A fresh live observation inside the entry window is required" }
        val synced = round.syncTo(nowMillis)
        val poolId = requireNotNull(synced.roundId)
        val anchor = requireNotNull(synced.anchorOracleQuote)
        val latest = requireNotNull(synced.latestOracleQuote)
        check(poolId !in closedLivePools) { "Pool has already closed" }
        val current = beginLivePool(poolId)
        val terms = PredictionPoolTerms(anchor, synced.band, synced.forecastSigmaUsd, synced.btcMarket)
        check(current.currentLivePoolTerms == null || current.currentLivePoolTerms == terms) {
            "This pool's opening observation and band are already locked"
        }
        check(current.openById.values.filter { it.poolId == poolId }.all {
            it.isLive && it.anchorOracleQuote == anchor && it.bandUsd == synced.band &&
            it.forecastSigmaUsd == synced.forecastSigmaUsd && it.btcMarket == synced.btcMarket
        }) { "Entries in one pool must use the same opening observation and band" }
        val entered = current.stake(entryId, poolId, direction, stakeUsd,
            BigDecimal.valueOf(synced.quoteFor(direction)),
            BigDecimal.valueOf(synced.price).setScale(6, RoundingMode.HALF_UP))
        val entry = entered.requireOpenEntry(entryId).copy(oracleSourceId = latest.sourceId,
            anchorOracleQuote = anchor, entryPublishTime = latest.publishTime, bandUsd = synced.band,
            forecastSigmaUsd = synced.forecastSigmaUsd, btcMarket = synced.btcMarket)
        return entered.changed(openById = entered.openById + (entryId to entry),
            currentLivePoolTerms = terms)
    }

    /** Derives an exit from the same fresh live quote model; arbitrary exit credits cannot close live entries. */
    fun tradeOutLive(
        entryId: String,
        round: PredictionPreviewRound,
        nowMillis: Long,
    ): PredictionWalletLedger {
        val entry = requireOpenEntry(entryId)
        check(entry.isLive && round.canTradeLiveAt(nowMillis)) { "A fresh live observation is required" }
        check(round.roundId == entry.poolId && round.anchorOracleQuote == entry.anchorOracleQuote &&
            round.band == entry.bandUsd && round.forecastSigmaUsd == entry.forecastSigmaUsd &&
            round.btcMarket == entry.btcMarket) {
            "Exit must use this position's fixed pool targets"
        }
        check(requireNotNull(round.latestOracleQuote).publishTime >= requireNotNull(entry.entryPublishTime)) {
            "Exit observation predates entry"
        }
        requireTime(nowMillis)
        val exitQuote = BigDecimal.valueOf(round.syncTo(nowMillis).quoteFor(entry.direction))
        val proceeds = entry.stakeUsd.multiply(exitQuote).divide(entry.entryQuote, 6, RoundingMode.DOWN)
        return close(entry, proceeds).changed(historyRows = historyRows + PredictionHistoryRow(
            id = "trade:$entryId", kind = PredictionHistoryKind.TRADE_OUT,
            status = PredictionHistoryStatus.CONFIRMED, amountUsd = proceeds,
            realizedPnlUsd = proceeds - entry.stakeUsd, poolId = entry.poolId,
            entryId = entry.id, createdAtMillis = nowMillis, completedAtMillis = nowMillis,
        ))
    }

    /** Settles all local positions in one pool only with its first-after-deadline historical Pyth report. */
    fun settleLivePool(
        poolId: Long,
        finalQuote: PredictionOracleQuote,
        nowMillis: Long,
    ): PredictionWalletLedger {
        requireTime(nowMillis)
        val entries = openById.values.filter { it.poolId == poolId }
        if (entries.isEmpty()) return this
        check(entries.all { it.isLive }) { "A live report cannot settle a legacy position" }
        val anchor = requireNotNull(entries.first().anchorOracleQuote)
        val band = entries.first().bandUsd
        check(entries.all { it.anchorOracleQuote == anchor && it.bandUsd == band &&
            it.forecastSigmaUsd == entries.first().forecastSigmaUsd &&
                it.btcMarket == entries.first().btcMarket }) {
            "Pool opening observations or bands disagree"
        }
        val finished = PredictionPreviewRound.liveForPool(poolId, anchor, nowMillis, band,
            entries.first().forecastSigmaUsd, allowWatchOnlyAnchor = entries.first().btcMarket)
            .settleLive(finalQuote, nowMillis)
        var next = this
        for (entry in entries) {
            val won = entry.direction == finished.outcome
            val payout = if (won) entry.stakeUsd.divide(entry.entryQuote, 6, RoundingMode.DOWN) else ZERO
            next = next.close(entry, payout).changed(historyRows = next.historyRows + PredictionHistoryRow(
                id = "settle:${entry.id}",
                kind = if (won) PredictionHistoryKind.SETTLEMENT_WIN else PredictionHistoryKind.SETTLEMENT_LOSS,
                status = PredictionHistoryStatus.CONFIRMED, amountUsd = payout,
                realizedPnlUsd = payout - entry.stakeUsd, poolId = poolId, entryId = entry.id,
                createdAtMillis = nowMillis, completedAtMillis = nowMillis,
            ))
        }
        return next.changed(closedLivePools = closedLivePools + poolId,
            finalizedOutcome = if (currentPoolId == poolId) finished.outcome else finalizedOutcome)
    }

    /** Local hackathon-only timeout: refund principal after 30 seconds without a valid final report. */
    fun cancelLivePool(poolId: Long, nowMillis: Long): PredictionWalletLedger {
        require(poolId >= 0 && poolId < Long.MAX_VALUE / PredictionPreviewRound.DURATION_MILLIS - 1L)
        requireTime(nowMillis)
        val deadline = (poolId + 1L) * PredictionPreviewRound.DURATION_MILLIS
        check(nowMillis >= deadline && nowMillis - deadline >= LIVE_REFUND_DELAY_MILLIS) {
            "Historical settlement report is still within its retry period"
        }
        val entries = openById.values.filter { it.poolId == poolId }
        check(entries.all { it.isLive }) { "Live timeout cannot close a legacy position" }
        if (entries.isEmpty()) return this
        var next = this
        entries.forEach { entry -> next = next.refund(entry, nowMillis) }
        return next.changed(closedLivePools = closedLivePools + poolId)
    }

    /** Migrates old synthetic positions once by returning principal, never inventing a winning price. */
    fun refundLegacyEntries(nowMillis: Long): PredictionWalletLedger {
        requireTime(nowMillis)
        var next = this
        openById.values.filterNot { it.isLive }.forEach { entry -> next = next.refund(entry, nowMillis) }
        return next
    }

    private fun refund(entry: PredictionLedgerEntry, nowMillis: Long): PredictionWalletLedger =
        close(entry, entry.stakeUsd).changed(historyRows = historyRows + PredictionHistoryRow(
            id = "refund:${entry.id}", kind = PredictionHistoryKind.REFUND,
            status = PredictionHistoryStatus.CONFIRMED, amountUsd = entry.stakeUsd,
            realizedPnlUsd = ZERO, poolId = entry.poolId, entryId = entry.id,
            createdAtMillis = nowMillis, completedAtMillis = nowMillis,
        ))

    /** Reserves the quote amount immediately; repeated or closed entry IDs cannot debit twice. */
    fun stake(
        entryId: String,
        poolId: Long,
        direction: PredictionDirection,
        stakeUsd: BigDecimal,
        entryQuote: BigDecimal,
        entryPriceUsd: BigDecimal,
    ): PredictionWalletLedger {
        requireEntryId(entryId)
        val amount = quoteAmount(stakeUsd, allowZero = false)
        val quote = probability(entryQuote)
        val price = quoteAmount(entryPriceUsd, allowZero = false)
        check(currentPoolId == poolId) { "Stake must target the current pool" }
        check(finalizedOutcome == null) { "A finalized pool cannot accept stakes" }
        check(entryId !in openById && entryId !in closedIds) { "Entry ID has already been used" }
        check(availableQuoteUsd >= amount) { "Insufficient available quote balance" }
        return changed(
            availableQuoteUsd = availableQuoteUsd - amount,
            poolStakedUsd = poolStakedUsd?.plus(amount),
            openById = openById + (entryId to PredictionLedgerEntry(entryId, poolId, direction,
                amount, quote, price)),
        )
    }

    /** Closes one entry at an explicit exit quote, crediting proceeds and realizing exit minus stake. */
    fun tradeOut(
        entryId: String,
        exitValueUsd: BigDecimal,
        tradedAtMillis: Long? = null,
    ): PredictionWalletLedger {
        val entry = requireOpenEntry(entryId)
        check(!entry.isLive) { "Live positions require a timestamped exit quote" }
        check(finalizedOutcome == null) { "A finalized pool cannot trade out" }
        requireTime(tradedAtMillis)
        val proceeds = quoteAmount(exitValueUsd, allowZero = true)
        return close(entry, proceeds).changed(historyRows = historyRows + PredictionHistoryRow(
            id = "trade:$entryId", kind = PredictionHistoryKind.TRADE_OUT,
            status = PredictionHistoryStatus.CONFIRMED, amountUsd = proceeds,
            realizedPnlUsd = proceeds - entry.stakeUsd, poolId = entry.poolId,
            entryId = entryId, createdAtMillis = tradedAtMillis,
            completedAtMillis = tradedAtMillis,
        ))
    }

    /** Closes one entry against the shared outcome using its persisted, simulated entry quote. */
    fun settle(
        entryId: String,
        finalOutcome: PredictionDirection,
        settledAtMillis: Long? = null,
    ): PredictionWalletLedger {
        val entry = requireOpenEntry(entryId)
        check(!entry.isLive) { "Live positions require a historical boundary observation" }
        check(finalizedOutcome == null || finalizedOutcome == finalOutcome) {
            "Entries in one pool must settle against one outcome"
        }
        requireTime(settledAtMillis)
        val won = entry.direction == finalOutcome
        val payout = if (won) entry.quotedWinningPayoutUsd else ZERO
        return close(entry, payout).changed(
            finalizedOutcome = finalOutcome,
            historyRows = historyRows + PredictionHistoryRow(
                id = "settle:$entryId",
                kind = if (won) PredictionHistoryKind.SETTLEMENT_WIN else PredictionHistoryKind.SETTLEMENT_LOSS,
                status = PredictionHistoryStatus.CONFIRMED, amountUsd = payout,
                realizedPnlUsd = payout - entry.stakeUsd, poolId = entry.poolId,
                entryId = entryId, createdAtMillis = settledAtMillis,
                completedAtMillis = settledAtMillis,
            ),
        )
    }

    /** A process-death snapshot for app-private practice state. */
    fun toJson(): String {
        val entries = JSONArray()
        openById.toSortedMap().forEach { (_, entry) ->
            entries.put(JSONObject()
                .put("id", entry.id)
                .put("poolId", entry.poolId)
                .put("direction", entry.direction.name)
                .put("stakeUsd", entry.stakeUsd.toPlainString())
                .put("entryQuote", entry.entryQuote.toPlainString())
                .put("entryPriceUsd", entry.entryPriceUsd.toPlainString())
                .put("bandUsd", BigDecimal.valueOf(entry.bandUsd).toPlainString())
                .put("forecastSigmaUsd", entry.forecastSigmaUsd?.let {
                    BigDecimal.valueOf(it).toPlainString()
                } ?: JSONObject.NULL)
                .put("btcMarket", entry.btcMarket)
                .put("oracleSourceId", entry.oracleSourceId)
                .put("entryPublishTime", entry.entryPublishTime ?: JSONObject.NULL)
                .put("anchorOracleQuote", entry.anchorOracleQuote?.let { quote -> JSONObject()
                    .put("priceUsd", quote.priceUsd)
                    .put("confidenceUsd", quote.confidenceUsd)
                    .put("publishTime", quote.publishTime)
                    .put("sourceId", quote.sourceId)
                } ?: JSONObject.NULL))
        }
        val closed = JSONArray()
        closedIds.sorted().forEach { id -> closed.put(id) }
        val closedPools = JSONArray()
        closedLivePools.sorted().forEach { id -> closedPools.put(id) }
        val history = JSONArray()
        historyRows.forEach { row ->
            history.put(JSONObject()
                .put("id", row.id)
                .put("kind", row.kind.name)
                .put("status", row.status.name)
                .put("amountUsd", row.amountUsd.toPlainString())
                .put("realizedPnlUsd", row.realizedPnlUsd?.toPlainString() ?: JSONObject.NULL)
                .put("poolId", row.poolId ?: JSONObject.NULL)
                .put("entryId", row.entryId ?: JSONObject.NULL)
                .put("createdAtMillis", row.createdAtMillis ?: JSONObject.NULL)
                .put("completedAtMillis", row.completedAtMillis ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("version", SNAPSHOT_VERSION)
            .put("availableQuoteUsd", availableQuoteUsd.toPlainString())
            .put("currentPoolId", currentPoolId ?: JSONObject.NULL)
            .put("realizedPoolPnlUsd", realizedPoolPnlUsd.toPlainString())
            .put("poolStakedUsd", poolStakedUsd?.toPlainString() ?: JSONObject.NULL)
            .put("lifetimeRealizedPnlUsd", lifetimeRealizedPnlUsd.toPlainString())
            .put("finalizedOutcome", finalizedOutcome?.name ?: JSONObject.NULL)
            .put("openEntries", entries)
            .put("closedIds", closed)
            .put("closedLivePools", closedPools)
            .put("currentLivePoolTerms", currentLivePoolTerms?.let { terms -> JSONObject()
                .put("bandUsd", BigDecimal.valueOf(terms.bandUsd).toPlainString())
                .put("forecastSigmaUsd", terms.forecastSigmaUsd?.let {
                    BigDecimal.valueOf(it).toPlainString()
                } ?: JSONObject.NULL)
                .put("btcMarket", terms.btcMarket)
                .put("anchorOracleQuote", JSONObject()
                    .put("priceUsd", terms.anchorOracleQuote.priceUsd)
                    .put("confidenceUsd", terms.anchorOracleQuote.confidenceUsd)
                    .put("publishTime", terms.anchorOracleQuote.publishTime)
                    .put("sourceId", terms.anchorOracleQuote.sourceId))
            } ?: JSONObject.NULL)
            .put("history", history)
            .put("historyComplete", historyComplete)
            .toString()
    }

    private fun requireOpenEntry(id: String): PredictionLedgerEntry {
        requireEntryId(id)
        return checkNotNull(openById[id]) { "Entry is not open" }
    }

    private fun close(entry: PredictionLedgerEntry, proceeds: BigDecimal): PredictionWalletLedger = changed(
        availableQuoteUsd = availableQuoteUsd + proceeds,
        realizedPoolPnlUsd = if (entry.poolId == currentPoolId) realizedPoolPnlUsd + proceeds - entry.stakeUsd
            else realizedPoolPnlUsd,
        lifetimeRealizedPnlUsd = lifetimeRealizedPnlUsd + proceeds - entry.stakeUsd,
        openById = openById - entry.id,
        closedIds = closedIds + entry.id,
    )

    private fun changed(
        availableQuoteUsd: BigDecimal = this.availableQuoteUsd,
        currentPoolId: Long? = this.currentPoolId,
        realizedPoolPnlUsd: BigDecimal = this.realizedPoolPnlUsd,
        poolStakedUsd: BigDecimal? = this.poolStakedUsd,
        lifetimeRealizedPnlUsd: BigDecimal = this.lifetimeRealizedPnlUsd,
        finalizedOutcome: PredictionDirection? = this.finalizedOutcome,
        openById: Map<String, PredictionLedgerEntry> = this.openById,
        closedIds: Set<String> = this.closedIds,
        historyRows: List<PredictionHistoryRow> = this.historyRows,
        historyComplete: Boolean = this.historyComplete,
        closedLivePools: Set<Long> = this.closedLivePools,
        currentLivePoolTerms: PredictionPoolTerms? = this.currentLivePoolTerms,
    ) = PredictionWalletLedger(availableQuoteUsd, currentPoolId, realizedPoolPnlUsd,
        poolStakedUsd, lifetimeRealizedPnlUsd, finalizedOutcome,
        openById, closedIds, historyRows, historyComplete,
        closedLivePools, currentLivePoolTerms)

    companion object {
        private const val SNAPSHOT_VERSION = 8
        const val LIVE_REFUND_DELAY_MILLIS = 30_000L
        private const val MAX_SNAPSHOT_LENGTH = 1_000_000
        private const val MAX_ENTRIES = 1_000
        private const val MAX_CLOSED_IDS = 20_000
        private const val MAX_HISTORY = 30_000
        private val ZERO = BigDecimal.ZERO.setScale(6)

        fun empty(): PredictionWalletLedger = PredictionWalletLedger(
            availableQuoteUsd = ZERO,
            currentPoolId = null,
            realizedPoolPnlUsd = ZERO,
            poolStakedUsd = ZERO,
            lifetimeRealizedPnlUsd = ZERO,
            finalizedOutcome = null,
            openById = emptyMap(),
            closedIds = emptySet(),
            historyRows = emptyList(),
            historyComplete = true,
            closedLivePools = emptySet(),
            currentLivePoolTerms = null,
        )

        /** Rejects damaged or pre-practice snapshots; callers fall back to an empty local ledger. */
        fun fromJson(raw: String): PredictionWalletLedger {
            require(raw.length <= MAX_SNAPSHOT_LENGTH) { "Prediction ledger snapshot is too large" }
            val json = JSONObject(raw)
            val version = json.getInt("version")
            require(version in 6..SNAPSHOT_VERSION) { "Unsupported prediction ledger version" }
            val available = quoteAmount(BigDecimal(json.getString("availableQuoteUsd")), allowZero = true)
            require(json.has("currentPoolId") && json.has("finalizedOutcome")) {
                "Prediction ledger snapshot is incomplete"
            }
            val poolId = if (json.isNull("currentPoolId")) null else json.getLong("currentPoolId")
            val realized = signedQuoteAmount(BigDecimal(json.getString("realizedPoolPnlUsd")))
            val poolStaked = if (json.isNull("poolStakedUsd")) null else
                quoteAmount(BigDecimal(json.getString("poolStakedUsd")), allowZero = true)
            val lifetime = signedQuoteAmount(BigDecimal(json.getString("lifetimeRealizedPnlUsd")))
            val outcome = if (json.isNull("finalizedOutcome")) null else
                PredictionDirection.valueOf(json.getString("finalizedOutcome"))
            require(json.optJSONArray("deposits")?.length() in setOf(null, 0)) {
                "External funding snapshots are no longer supported"
            }
            val entriesJson = json.getJSONArray("openEntries")
            require(entriesJson.length() <= MAX_ENTRIES) { "Too many open entries" }
            val entries = LinkedHashMap<String, PredictionLedgerEntry>()
            for (index in 0 until entriesJson.length()) {
                val row = entriesJson.getJSONObject(index)
                val id = row.getString("id")
                requireEntryId(id)
                val entryPoolId = row.getLong("poolId")
                val sourceId = if (version < 3) "preview" else row.getString("oracleSourceId")
                require(sourceId == "preview" || sourceId == "pyth") { "Invalid prediction price source" }
                val anchor = if (version < 3 || row.isNull("anchorOracleQuote")) null else
                    row.getJSONObject("anchorOracleQuote").let { quote -> PredictionOracleQuote(
                        priceUsd = quote.getDouble("priceUsd"), confidenceUsd = quote.getDouble("confidenceUsd"),
                        publishTime = quote.getLong("publishTime"), sourceId = quote.getString("sourceId"),
                    ) }
                val entryTime = if (version < 3 || row.isNull("entryPublishTime")) null else
                    row.getLong("entryPublishTime")
                val band = if (version < 4) PredictionPreviewRound.BAND else
                    row.getString("bandUsd").toDouble()
                require(band.isFinite() && band > 0.0 &&
                    BigDecimal.valueOf(band).stripTrailingZeros().scale() <= 2) {
                    "Invalid prediction band"
                }
                require(version < 5 || row.has("forecastSigmaUsd")) {
                    "Prediction volatility forecast is missing"
                }
                val sigma = if (version < 5 || row.isNull("forecastSigmaUsd")) null else
                    row.getString("forecastSigmaUsd").toDouble()
                // V6 BTC entries had bands of $20-$80 but did not save the market explicitly.
                val btcMarket = if (version >= 7) row.getBoolean("btcMarket") else band >= 20.0
                require(sigma == null || (sigma.isFinite() && sigma > 0.0 &&
                    sigma <= PredictionPreviewRound.MAX_FORECAST_SIGMA_USD)) {
                    "Invalid prediction volatility forecast"
                }
                require(!btcMarket || band in PredictionPreviewRound.BTC_MIN_BAND_USD..PredictionPreviewRound.BTC_MAX_BAND_USD) {
                    "Invalid BTC prediction band"
                }
                if (sourceId == "pyth") {
                    require(entryPoolId >= 0 && entryPoolId < Long.MAX_VALUE / PredictionPreviewRound.DURATION_MILLIS)
                    require(poolId != null && entryPoolId <= poolId) { "Live entry belongs to a future pool" }
                    val startSecond = entryPoolId * PredictionPreviewRound.DURATION
                    require(anchor != null && band < anchor.priceUsd &&
                        anchor.isBoundaryQuote(startSecond, band, btcMarket) && entryTime != null &&
                        entryTime >= anchor.publishTime && entryTime < startSecond + PredictionPreviewRound.DURATION) {
                        "Live entry has invalid oracle metadata"
                    }
                } else {
                    require(entryPoolId == poolId && anchor == null && entryTime == null) {
                        "Legacy entry has invalid pool metadata"
                    }
                }
                val entry = PredictionLedgerEntry(
                    id = id,
                    poolId = entryPoolId,
                    direction = PredictionDirection.valueOf(row.getString("direction")),
                    stakeUsd = quoteAmount(BigDecimal(row.getString("stakeUsd")), allowZero = false),
                    entryQuote = probability(BigDecimal(row.getString("entryQuote"))),
                    entryPriceUsd = quoteAmount(BigDecimal(row.getString("entryPriceUsd")), allowZero = false),
                    oracleSourceId = sourceId,
                    anchorOracleQuote = anchor,
                    entryPublishTime = entryTime,
                    bandUsd = band,
                    forecastSigmaUsd = sigma,
                    btcMarket = btcMarket,
                )
                require(entries.put(id, entry) == null) { "Duplicate open entry" }
            }
            val closedJson = json.getJSONArray("closedIds")
            require(closedJson.length() <= MAX_CLOSED_IDS) { "Too many closed entry IDs" }
            val closed = LinkedHashSet<String>()
            for (index in 0 until closedJson.length()) {
                val id = closedJson.getString(index)
                requireEntryId(id)
                require(closed.add(id) && id !in entries) { "Duplicate or reopened entry ID" }
            }
            val closedPools = LinkedHashSet<Long>()
            if (version >= 3) {
                val poolsJson = json.getJSONArray("closedLivePools")
                require(poolsJson.length() <= MAX_CLOSED_IDS) { "Too many closed pool IDs" }
                for (index in 0 until poolsJson.length()) {
                    val id = poolsJson.getLong(index)
                    require(id >= 0 && poolId != null && id <= poolId && closedPools.add(id)) {
                        "Invalid closed pool ID"
                    }
                }
                require(entries.values.none { it.poolId in closedPools }) { "Closed pool has open entries" }
                entries.values.filter { it.isLive }.groupBy { it.poolId }.values.forEach { samePool ->
                    require(samePool.map {
                        listOf(it.anchorOracleQuote, it.bandUsd, it.forecastSigmaUsd, it.btcMarket)
                    }.distinct().size == 1) {
                        "Live pool opening observations or bands disagree"
                    }
                }
            }
            val currentEntries = entries.values.filter { it.isLive && it.poolId == poolId }
            val poolTerms = if (version < 4) {
                currentEntries.firstOrNull()?.let {
                    PredictionPoolTerms(requireNotNull(it.anchorOracleQuote), it.bandUsd,
                        it.forecastSigmaUsd, it.btcMarket)
                }
            } else {
                require(json.has("currentLivePoolTerms")) { "Prediction pool terms are missing" }
                if (json.isNull("currentLivePoolTerms")) null else {
                    val terms = json.getJSONObject("currentLivePoolTerms")
                    require(version < 5 || terms.has("forecastSigmaUsd")) {
                        "Current prediction volatility forecast is missing"
                    }
                    val quote = terms.getJSONObject("anchorOracleQuote")
                    val band = terms.getString("bandUsd").toDouble()
                    val btcMarket = if (version >= 7) terms.getBoolean("btcMarket") else band >= 20.0
                    PredictionPoolTerms(PredictionOracleQuote(
                        quote.getDouble("priceUsd"), quote.getDouble("confidenceUsd"),
                        quote.getLong("publishTime"), quote.getString("sourceId"),
                    ), band,
                        if (version < 5 || terms.isNull("forecastSigmaUsd")) null else
                            terms.getString("forecastSigmaUsd").toDouble(), btcMarket)
                }
            }
            if (poolTerms != null) {
                val id = requireNotNull(poolId)
                require(id >= 0 && id < Long.MAX_VALUE / PredictionPreviewRound.DURATION_MILLIS &&
                    poolTerms.bandUsd.isFinite() && poolTerms.bandUsd > 0.0 &&
                    (!poolTerms.btcMarket || poolTerms.bandUsd in
                        PredictionPreviewRound.BTC_MIN_BAND_USD..PredictionPreviewRound.BTC_MAX_BAND_USD) &&
                    BigDecimal.valueOf(poolTerms.bandUsd).stripTrailingZeros().scale() <= 2 &&
                    poolTerms.bandUsd < poolTerms.anchorOracleQuote.priceUsd &&
                    (poolTerms.forecastSigmaUsd == null || (poolTerms.forecastSigmaUsd.isFinite() &&
                        poolTerms.forecastSigmaUsd > 0.0 &&
                        poolTerms.forecastSigmaUsd <= PredictionPreviewRound.MAX_FORECAST_SIGMA_USD)) &&
                    poolTerms.anchorOracleQuote.isBoundaryQuote(id * PredictionPreviewRound.DURATION,
                        poolTerms.bandUsd, poolTerms.btcMarket)) {
                    "Invalid current pool targets"
                }
            }
            require(currentEntries.isEmpty() || (poolTerms != null && currentEntries.all {
                it.anchorOracleQuote == poolTerms.anchorOracleQuote && it.bandUsd == poolTerms.bandUsd &&
                    it.forecastSigmaUsd == poolTerms.forecastSigmaUsd &&
                    it.btcMarket == poolTerms.btcMarket
            })) { "Current live entries disagree with pool targets" }
            val history = ArrayList<PredictionHistoryRow>()
            require(json.has("poolStakedUsd") && json.has("historyComplete")) {
                "Prediction ledger snapshot is incomplete"
            }
            val historyComplete = json.getBoolean("historyComplete")
            require(historyComplete) { "Incomplete legacy prediction history is unsupported" }
            require(json.optJSONArray("withdrawals")?.length() in setOf(null, 0)) {
                "External withdrawal snapshots are no longer supported"
            }
            val historyJson = json.getJSONArray("history")
            require(historyJson.length() <= MAX_HISTORY) { "Too many history rows" }
            val historyIds = HashSet<String>()
            for (index in 0 until historyJson.length()) {
                val row = historyJson.getJSONObject(index)
                val id = row.getString("id")
                requireEntryId(id)
                require(historyIds.add(id)) { "Duplicate history ID" }
                require(!row.has("txHash") || row.isNull("txHash")) {
                    "External transaction history is no longer supported"
                }
                val createdAt = if (row.isNull("createdAtMillis")) null else row.getLong("createdAtMillis")
                val completedAt = if (row.isNull("completedAtMillis")) null else row.getLong("completedAtMillis")
                requireTime(createdAt)
                requireTime(completedAt)
                history += PredictionHistoryRow(
                    id = id,
                    kind = PredictionHistoryKind.valueOf(row.getString("kind")),
                    status = PredictionHistoryStatus.valueOf(row.getString("status")),
                    amountUsd = quoteAmount(BigDecimal(row.getString("amountUsd")), allowZero = true),
                    realizedPnlUsd = if (row.isNull("realizedPnlUsd")) null else
                        signedQuoteAmount(BigDecimal(row.getString("realizedPnlUsd"))),
                    poolId = if (row.isNull("poolId")) null else row.getLong("poolId"),
                    entryId = if (row.isNull("entryId")) null else row.getString("entryId"),
                    createdAtMillis = createdAt,
                    completedAtMillis = completedAt,
                )
            }
            history.filter { it.kind == PredictionHistoryKind.LOCAL_USDC_ADD ||
                it.kind == PredictionHistoryKind.LOCAL_USDC_WITHDRAW }.forEach { row ->
                require(version >= 6 && row.id.startsWith("local:") && row.id.length > "local:".length &&
                    row.status == PredictionHistoryStatus.CONFIRMED && row.amountUsd.signum() > 0 &&
                    row.poolId == null && row.entryId == null &&
                    row.realizedPnlUsd == null && row.createdAtMillis != null &&
                    row.createdAtMillis == row.completedAtMillis) {
                    "Invalid local USDC funding record"
                }
            }
            require(poolId != null || (entries.isEmpty() && outcome == null && realized.signum() == 0)) {
                "An unopened ledger cannot contain pool state"
            }
            val reserved = entries.values.fold(ZERO) { total, entry -> total + entry.stakeUsd }
            val localNet = history.fold(ZERO) { total, row -> when (row.kind) {
                PredictionHistoryKind.LOCAL_USDC_ADD -> total + row.amountUsd
                PredictionHistoryKind.LOCAL_USDC_WITHDRAW -> total - row.amountUsd
                else -> total
            } }
            val currentReserved = entries.values.filter { it.poolId == poolId }
                .fold(ZERO) { total, entry -> total + entry.stakeUsd }
            require(poolStaked == null || poolStaked >= currentReserved) { "Pool stake total is incomplete" }
            require(available + reserved == localNet + lifetime) {
                "Prediction ledger balance does not reconcile"
            }
            if (historyComplete) closed.forEach { id ->
                require(history.count { it.entryId == id && (it.kind == PredictionHistoryKind.TRADE_OUT ||
                    it.kind == PredictionHistoryKind.SETTLEMENT_WIN ||
                    it.kind == PredictionHistoryKind.SETTLEMENT_LOSS || it.kind == PredictionHistoryKind.REFUND) } == 1) {
                    "Closed entry history is incomplete"
                }
            }
            return PredictionWalletLedger(available, poolId, realized, poolStaked, lifetime, outcome,
                entries.toMap(), closed.toSet(), history.toList(), historyComplete,
                closedPools.toSet(), poolTerms)
        }

        private fun quoteAmount(value: BigDecimal, allowZero: Boolean): BigDecimal {
            require(value.signum() > 0 || (allowZero && value.signum() == 0)) {
                "USD quote amount must be ${if (allowZero) "nonnegative" else "positive"}"
            }
            require(value.stripTrailingZeros().scale() <= 6) { "USD quote amount exceeds six decimals" }
            return value.setScale(6, RoundingMode.UNNECESSARY)
        }

        private fun signedQuoteAmount(value: BigDecimal): BigDecimal {
            require(value.stripTrailingZeros().scale() <= 6) { "USD quote amount exceeds six decimals" }
            return value.setScale(6, RoundingMode.UNNECESSARY)
        }

        private fun probability(value: BigDecimal): BigDecimal {
            require(value > BigDecimal.ZERO && value <= BigDecimal.ONE) { "Entry quote must be in (0, 1]" }
            require(value.stripTrailingZeros().scale() <= 18) { "Entry quote exceeds 18 decimals" }
            return value.setScale(18, RoundingMode.UNNECESSARY)
        }

        private fun requireEntryId(id: String) {
            require(id.isNotBlank() && id == id.trim()) { "Entry ID must be nonblank and canonical" }
        }

        private fun requireTime(atMillis: Long?) {
            require(atMillis == null || atMillis > 0L) { "Event time must be positive when supplied" }
        }
    }
}

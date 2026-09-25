package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

/** Local stage-preview accounting, separate from Privy balances and chain activity. */
data class PreviewWalletPurchase(
    val stockId: String,
    val symbol: String,
    val spentUsd: BigDecimal,
    val purchasedAtEpochMillis: Long,
)

data class PreviewWalletState(
    val refreshTapCount: Int = 0,
    val fundedAtEpochMillis: Long? = null,
    val purchases: List<PreviewWalletPurchase> = emptyList(),
) {
    val funded: Boolean get() = fundedAtEpochMillis != null
    val availableUsd: BigDecimal get() = if (funded) {
        (INCOMING_USD - purchases.fold(BigDecimal.ZERO) { total, purchase -> total + purchase.spentUsd })
            .max(BigDecimal.ZERO)
    } else BigDecimal.ZERO
    val availableSol: BigDecimal get() = if (funded) {
        INCOMING_SOL.multiply(availableUsd).divide(INCOMING_USD, 8, RoundingMode.DOWN)
    } else BigDecimal.ZERO

    /** Remove only the local incoming SOL preview, leaving completed preview positions intact. */
    fun withoutFunding(): PreviewWalletState = copy(refreshTapCount = 0, fundedAtEpochMillis = null)

    companion object {
        val INCOMING_SOL: BigDecimal = BigDecimal("0.04151")
        val INCOMING_USD: BigDecimal = BigDecimal("4.80")
    }
}

/** Debug-only, per-user preview data. Nothing here is passed to a wallet or purchase backend. */
object PreviewWalletStore {
    private const val PREFERENCES = "eleven-wallet-preview-v1"
    private const val MAX_PURCHASES = 8

    @Synchronized
    fun read(context: Context, userId: String): PreviewWalletState {
        if (!BuildConfig.DEBUG || userId.isBlank()) return PreviewWalletState()
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(userId, null) ?: return PreviewWalletState()
        return runCatching {
            val json = JSONObject(raw)
            val taps = json.getInt("refreshTapCount")
            val fundedAt = json.optLong("fundedAtEpochMillis").takeIf { it > 0L }
            val rows = json.getJSONArray("purchases")
            require(taps in 0..4 && rows.length() <= MAX_PURCHASES)
            val purchases = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val stockId = row.getString("stockId")
                val symbol = row.getString("symbol")
                val spentUsd = BigDecimal(row.getString("spentUsd"))
                val purchasedAt = row.getLong("purchasedAtEpochMillis")
                require(stockId.isNotBlank() && stockId.length <= 200 &&
                    symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")) &&
                    spentUsd.signum() > 0 && purchasedAt > 0L)
                PreviewWalletPurchase(stockId, symbol, spentUsd, purchasedAt)
            }
            require(purchases.map { it.stockId }.toSet().size == purchases.size)
            val state = PreviewWalletState(taps, fundedAt, purchases)
            require(purchases.fold(BigDecimal.ZERO) { total, row -> total + row.spentUsd } <= PreviewWalletState.INCOMING_USD)
            state
        }.getOrDefault(PreviewWalletState())
    }

    /** The fourth refresh reveals a fixed incoming amount; later refreshes do not add funds. */
    @Synchronized
    fun refresh(context: Context, userId: String): PreviewWalletState {
        val current = read(context, userId)
        if (!BuildConfig.DEBUG || userId.isBlank() || current.funded) return current
        val taps = (current.refreshTapCount + 1).coerceAtMost(4)
        val next = current.copy(refreshTapCount = taps,
            fundedAtEpochMillis = if (taps == 4) System.currentTimeMillis() else null)
        return write(context, userId, next) ?: current
    }

    /** Idempotent per stock so a repeated callback cannot deduct the preview balance twice. */
    @Synchronized
    fun recordPurchase(context: Context, userId: String, stockId: String, symbol: String,
        spentUsd: BigDecimal): PreviewWalletState? {
        if (!BuildConfig.DEBUG || userId.isBlank() || stockId.isBlank() || stockId.length > 200 ||
            !symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")) || spentUsd.signum() <= 0) return null
        val current = read(context, userId)
        if (!current.funded) return null
        if (current.purchases.any { it.stockId == stockId }) return current
        if (current.purchases.size >= MAX_PURCHASES || spentUsd > current.availableUsd) return null
        return write(context, userId, current.copy(purchases = current.purchases +
            PreviewWalletPurchase(stockId, symbol, spentUsd, System.currentTimeMillis())))
    }

    @Synchronized
    fun removePurchase(context: Context, userId: String, stockId: String): PreviewWalletState {
        val current = read(context, userId)
        val next = current.copy(purchases = current.purchases.filterNot { it.stockId == stockId })
        return if (next == current) current else write(context, userId, next) ?: current
    }

    /** Removes only the simulated SOL balance and its incoming activity row. */
    @Synchronized
    fun removeFunding(context: Context, userId: String): PreviewWalletState {
        val current = read(context, userId)
        if (!BuildConfig.DEBUG || userId.isBlank() || !current.funded) return current
        return write(context, userId, current.withoutFunding()) ?: current
    }

    /** Clears the seed, tap count, and all local purchases for a fresh rehearsal. */
    @Synchronized
    fun clear(context: Context, userId: String): PreviewWalletState {
        if (!BuildConfig.DEBUG || userId.isBlank()) return PreviewWalletState()
        val removed = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(userId).commit()
        return if (removed) PreviewWalletState() else read(context, userId)
    }

    private fun write(context: Context, userId: String, state: PreviewWalletState): PreviewWalletState? {
        val purchases = JSONArray()
        state.purchases.forEach { row ->
            purchases.put(JSONObject()
                .put("stockId", row.stockId)
                .put("symbol", row.symbol)
                .put("spentUsd", row.spentUsd.toPlainString())
                .put("purchasedAtEpochMillis", row.purchasedAtEpochMillis))
        }
        val encoded = JSONObject()
            .put("refreshTapCount", state.refreshTapCount)
            .put("fundedAtEpochMillis", state.fundedAtEpochMillis ?: 0L)
            .put("purchases", purchases).toString()
        return state.takeIf {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(userId, encoded).commit()
        }
    }
}

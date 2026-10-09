package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class PreviewCommunityCashAsset { SOL, USDC }

enum class PreviewCommunityTradeSide { BUY, SELL }

/** Local cash movement for a creator-token trade; never a wallet transaction. */
data class PreviewCommunityCashTrade(
    val tradeId: String,
    val mintAddress: String,
    val asset: PreviewCommunityCashAsset,
    val side: PreviewCommunityTradeSide,
    val assetAmount: BigDecimal,
    val tokenQuantity: BigDecimal,
    val tradedAtEpochMillis: Long,
)

/** Local wallet-flow accounting, separate from Privy balances and chain activity. */
data class PreviewWalletPurchase(
    val stockId: String,
    val symbol: String,
    val spentUsd: BigDecimal,
    val purchasedAtEpochMillis: Long,
    val receivedTokens: BigDecimal? = null,
    val receivedValueUsd: BigDecimal? = null,
    val purchaseId: String = UUID.randomUUID().toString(),
)

data class PreviewWalletState(
    val refreshTapCount: Int = 0,
    val fundedAtEpochMillis: Long? = null,
    val purchases: List<PreviewWalletPurchase> = emptyList(),
    val communityTrades: List<PreviewCommunityCashTrade> = emptyList(),
) {
    val funded: Boolean get() = fundedAtEpochMillis != null
    private val stockSpentUsd: BigDecimal get() =
        purchases.fold(BigDecimal.ZERO) { total, purchase -> total + purchase.spentUsd }
    private val communitySolNet: BigDecimal get() = communityTrades.fold(BigDecimal.ZERO) { total, trade ->
        total + if (trade.asset == PreviewCommunityCashAsset.SOL) {
            if (trade.side == PreviewCommunityTradeSide.BUY) trade.assetAmount else trade.assetAmount.negate()
        } else BigDecimal.ZERO
    }
    private val communityUsdcNet: BigDecimal get() = communityTrades.fold(BigDecimal.ZERO) { total, trade ->
        total + if (trade.asset == PreviewCommunityCashAsset.USDC) {
            if (trade.side == PreviewCommunityTradeSide.BUY) trade.assetAmount else trade.assetAmount.negate()
        } else BigDecimal.ZERO
    }
    internal val availableSolUsdExact: BigDecimal get() = if (funded) {
        INCOMING_SOL_USD - stockSpentUsd - communitySolNet.multiply(SOL_USD_RATE)
    } else BigDecimal.ZERO
    val availableSolUsd: BigDecimal get() = if (funded) {
        availableSolUsdExact.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
    } else BigDecimal.ZERO
    val availableSol: BigDecimal get() = if (funded) {
        (INCOMING_SOL - stockSpentUsd.divide(SOL_USD_RATE, 18, RoundingMode.DOWN) - communitySolNet)
            .max(BigDecimal.ZERO).setScale(9, RoundingMode.DOWN)
            .stripTrailingZeros()
    } else BigDecimal.ZERO
    val availableUsdc: BigDecimal get() = if (funded) {
        communityUsdcNet.negate().max(BigDecimal.ZERO).stripTrailingZeros()
    } else BigDecimal.ZERO
    val availableUsd: BigDecimal get() = availableSolUsd + availableUsdc

    fun communityHeldQuantity(mintAddress: String): BigDecimal =
        communityTrades.asSequence().filter { it.mintAddress == mintAddress }.fold(BigDecimal.ZERO) { total, trade ->
            total + if (trade.side == PreviewCommunityTradeSide.BUY) trade.tokenQuantity
                else trade.tokenQuantity.negate()
        }.max(BigDecimal.ZERO)

    companion object {
        val INCOMING_SOL: BigDecimal = BigDecimal("0.4")
        val INCOMING_SOL_USD: BigDecimal = BigDecimal("82.00")
        val INCOMING_USDC: BigDecimal = BigDecimal.ZERO
        val INCOMING_USD: BigDecimal = INCOMING_SOL_USD + INCOMING_USDC
        val SOL_USD_RATE: BigDecimal = INCOMING_SOL_USD.divide(INCOMING_SOL)
    }
}

/** Debug-only, per-user preview data. Nothing here is passed to a wallet or purchase backend. */
object PreviewWalletStore {
    private const val PREFERENCES = "eleven-wallet-preview-v2"
    private const val PREVIOUS_PREFERENCES = "eleven-wallet-preview-v3"
    private const val CLEARED = "cleared"
    private const val MAX_PURCHASES = 100
    private const val MAX_COMMUNITY_TRADES = 256
    private val MAX_COMMUNITY_ASSET_AMOUNT = BigDecimal("1000000000000")
    private val PURCHASE_ID_PATTERN = Regex("[A-Za-z0-9:_-]{1,100}")

    @Synchronized
    fun read(context: Context, userId: String): PreviewWalletState {
        if (!BuildConfig.DEBUG || userId.isBlank()) return PreviewWalletState()
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(userId, null)
        // A cleared v2 account keeps a marker so an older APK's v3 state cannot return.
        if (raw != null) return if (raw == CLEARED) PreviewWalletState() else decode(raw) ?: PreviewWalletState()
        // A previous debug APK saved preview funding under v3. Restore it once when this
        // install has no v2 state, then continue using v2 for all subsequent writes.
        val previous = context.getSharedPreferences(PREVIOUS_PREFERENCES, Context.MODE_PRIVATE)
            .getString(userId, null)?.let(::decode) ?: return PreviewWalletState()
        write(context, userId, previous)
        return previous
    }

    private fun decode(raw: String): PreviewWalletState? {
        return runCatching {
            val json = JSONObject(raw)
            val taps = json.getInt("refreshTapCount")
            val fundedAt = json.optLong("fundedAtEpochMillis").takeIf { it > 0L }
            val rows = json.getJSONArray("purchases")
            val tradeRows = json.optJSONArray("communityTrades") ?: JSONArray()
            require(taps in 0..4 && rows.length() <= MAX_PURCHASES &&
                tradeRows.length() <= MAX_COMMUNITY_TRADES)
            val purchases = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val stockId = row.getString("stockId")
                val symbol = row.getString("symbol")
                val spentUsd = BigDecimal(row.getString("spentUsd"))
                val purchasedAt = row.getLong("purchasedAtEpochMillis")
                val receivedTokens = row.optString("receivedTokens").takeIf(String::isNotBlank)?.let(::BigDecimal)
                val receivedValueUsd = row.optString("receivedValueUsd").takeIf(String::isNotBlank)?.let(::BigDecimal)
                // Older saved rows had no ID. Use a deterministic one so history keys stay
                // stable across reads, even if two rows share a stock and timestamp.
                val purchaseId = if (row.has("purchaseId")) row.getString("purchaseId") else {
                    val legacyKey = "$index:$stockId:$purchasedAt"
                    "legacy-${UUID.nameUUIDFromBytes(legacyKey.toByteArray(StandardCharsets.UTF_8))}"
                }
                require(stockId.isNotBlank() && stockId.length <= 200 &&
                    symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")) &&
                    purchaseId.matches(PURCHASE_ID_PATTERN) &&
                    spentUsd.signum() > 0 && spentUsd <= PreviewWalletState.INCOMING_SOL_USD &&
                    purchasedAt > 0L &&
                    (receivedTokens == null && receivedValueUsd == null ||
                        receivedTokens != null && receivedTokens.signum() > 0 &&
                        receivedValueUsd != null && receivedValueUsd.signum() > 0 && receivedValueUsd <= spentUsd))
                PreviewWalletPurchase(stockId, symbol, spentUsd, purchasedAt, receivedTokens,
                    receivedValueUsd, purchaseId)
            }
            require(purchases.map { it.purchaseId }.toSet().size == purchases.size)
            val communityTrades = (0 until tradeRows.length()).map { index ->
                val row = tradeRows.getJSONObject(index)
                val trade = PreviewCommunityCashTrade(
                    tradeId = row.getString("tradeId"),
                    mintAddress = row.getString("mintAddress"),
                    asset = PreviewCommunityCashAsset.valueOf(row.getString("asset")),
                    side = PreviewCommunityTradeSide.valueOf(row.getString("side")),
                    assetAmount = BigDecimal(row.getString("assetAmount")),
                    tokenQuantity = BigDecimal(row.getString("tokenQuantity")),
                    tradedAtEpochMillis = row.getLong("tradedAtEpochMillis"),
                )
                require(validCommunityTrade(trade))
                trade
            }
            require(communityTrades.map { it.tradeId }.toSet().size == communityTrades.size)
            val state = PreviewWalletState(taps, fundedAt, purchases, communityTrades)
            require(validateCommunityTradeSequence(state))
            state
        }.getOrNull()
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

    /** One ID per Buy action; retries with the same ID do not debit the balance twice. */
    @Synchronized
    fun recordPurchase(context: Context, userId: String, stockId: String, symbol: String,
        spentUsd: BigDecimal, receivedTokens: BigDecimal, receivedValueUsd: BigDecimal,
        purchaseId: String = UUID.randomUUID().toString()): PreviewWalletState? {
        if (!BuildConfig.DEBUG || userId.isBlank() || stockId.isBlank() || stockId.length > 200 ||
            !symbol.matches(Regex("[A-Za-z0-9._-]{1,30}")) || spentUsd.signum() <= 0 ||
            spentUsd > PreviewWalletState.INCOMING_SOL_USD || !purchaseId.matches(PURCHASE_ID_PATTERN) ||
            receivedTokens.signum() <= 0 || receivedValueUsd.signum() <= 0 || receivedValueUsd > spentUsd) return null
        val current = read(context, userId)
        if (!current.funded) return null
        current.purchases.firstOrNull { it.purchaseId == purchaseId }?.let { existing ->
            return current.takeIf {
                existing.stockId == stockId && existing.symbol == symbol &&
                    existing.spentUsd.compareTo(spentUsd) == 0 &&
                    existing.receivedTokens?.compareTo(receivedTokens) == 0 &&
                    existing.receivedValueUsd?.compareTo(receivedValueUsd) == 0
            }
        }
        if (current.purchases.size >= MAX_PURCHASES || spentUsd > current.availableSolUsdExact) return null
        return write(context, userId, current.copy(purchases = current.purchases +
            PreviewWalletPurchase(stockId, symbol, spentUsd, System.currentTimeMillis(),
                receivedTokens, receivedValueUsd, purchaseId)))
    }

    /** Adds one cash movement for a simulated creator-token trade. Reusing an ID cannot move cash twice. */
    @Synchronized
    fun recordCommunityTrade(
        context: Context,
        userId: String,
        tradeId: String,
        mintAddress: String,
        asset: PreviewCommunityCashAsset,
        side: PreviewCommunityTradeSide,
        assetAmount: BigDecimal,
        tokenQuantity: BigDecimal,
    ): PreviewWalletState? {
        if (!BuildConfig.DEBUG || userId.isBlank()) return null
        val trade = PreviewCommunityCashTrade(tradeId, mintAddress, asset, side,
            assetAmount, tokenQuantity, System.currentTimeMillis())
        if (!validCommunityTrade(trade)) return null
        val current = read(context, userId)
        if (!current.funded) return null
        current.communityTrades.firstOrNull { it.tradeId == tradeId }?.let { old ->
            return current.takeIf {
                old.mintAddress == mintAddress && old.asset == asset && old.side == side &&
                    old.assetAmount.compareTo(assetAmount) == 0 &&
                    old.tokenQuantity.compareTo(tokenQuantity) == 0
            }
        }
        if (current.communityTrades.size >= MAX_COMMUNITY_TRADES) return null
        if (side == PreviewCommunityTradeSide.BUY &&
            (if (asset == PreviewCommunityCashAsset.SOL) assetAmount > current.availableSol
                else assetAmount > current.availableUsdc)) return null
        if (side == PreviewCommunityTradeSide.SELL &&
            tokenQuantity > current.communityHeldQuantity(mintAddress)) return null
        val next = current.copy(communityTrades = current.communityTrades + trade)
        if (!validateCommunityTradeSequence(next)) return null
        return write(context, userId, next)
    }

    /** Backfills a pre-ledger local holding at its original cost; never credits money by itself. */
    @Synchronized
    fun reconcileLegacyCommunityHolding(
        context: Context,
        userId: String,
        mintAddress: String,
        spentSol: BigDecimal?,
        spentUsd: BigDecimal?,
        receivedTokens: BigDecimal,
    ): PreviewWalletState? {
        if (!BuildConfig.DEBUG || canonicalSolanaPublicKey(mintAddress) != mintAddress ||
            (spentSol == null && spentUsd == null) ||
            (spentSol != null && !validCommunityAmount(spentSol, 9)) ||
            (spentUsd != null && !validCommunityAmount(spentUsd, 9)) ||
            !validCommunityAmount(receivedTokens, 9)) return null
        val current = read(context, userId)
        if (!current.funded) return null
        val alreadyCovered = current.communityHeldQuantity(mintAddress)
        if (alreadyCovered > receivedTokens) return null
        val missing = receivedTokens - alreadyCovered
        if (missing.signum() == 0) return current
        if (current.communityTrades.any { it.tradeId == "legacy:$mintAddress" }) return null
        val totalCostSol = (spentSol ?: BigDecimal.ZERO) +
            (spentUsd ?: BigDecimal.ZERO).divide(PreviewWalletState.SOL_USD_RATE, 18, RoundingMode.UP)
        val totalSol = totalCostSol.multiply(missing)
            .divide(receivedTokens, 9, RoundingMode.UP)
            .max(BigDecimal("0.000000001"))
        return recordCommunityTrade(context, userId, "legacy:$mintAddress", mintAddress,
            PreviewCommunityCashAsset.SOL, PreviewCommunityTradeSide.BUY,
            totalSol, missing)
    }

    /** Compensates a failed holding-store write; refuses removal when later trades depend on it. */
    @Synchronized
    fun removeCommunityTrade(context: Context, userId: String, tradeId: String): PreviewWalletState? {
        if (!BuildConfig.DEBUG || userId.isBlank() || !tradeId.matches(PURCHASE_ID_PATTERN)) return null
        val current = read(context, userId)
        if (current.communityTrades.none { it.tradeId == tradeId }) return current
        val next = current.copy(communityTrades = current.communityTrades.filterNot { it.tradeId == tradeId })
        if (!validateCommunityTradeSequence(next)) return null
        return write(context, userId, next)
    }

    @Synchronized
    fun removePurchase(context: Context, userId: String, stockId: String): PreviewWalletState {
        val current = read(context, userId)
        val next = current.copy(purchases = current.purchases.filterNot { it.stockId == stockId })
        return if (next == current) current else write(context, userId, next) ?: current
    }

    /** Clears the seed, tap count, and all local purchases for a fresh rehearsal. */
    @Synchronized
    fun clear(context: Context, userId: String): PreviewWalletState {
        if (!BuildConfig.DEBUG || userId.isBlank()) return PreviewWalletState()
        val cleared = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(userId, CLEARED).commit()
        if (!cleared) return read(context, userId)
        // The durable marker already prevents restoration if removing the old row fails.
        context.getSharedPreferences(PREVIOUS_PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(userId).commit()
        return PreviewWalletState()
    }

    private fun write(context: Context, userId: String, state: PreviewWalletState): PreviewWalletState? {
        if (state.communityTrades.size > MAX_COMMUNITY_TRADES || !validateCommunityTradeSequence(state)) return null
        val purchases = JSONArray()
        state.purchases.forEach { row ->
            val purchase = JSONObject()
                .put("purchaseId", row.purchaseId)
                .put("stockId", row.stockId)
                .put("symbol", row.symbol)
                .put("spentUsd", row.spentUsd.toPlainString())
                .put("purchasedAtEpochMillis", row.purchasedAtEpochMillis)
            row.receivedTokens?.let { purchase.put("receivedTokens", it.toPlainString()) }
            row.receivedValueUsd?.let { purchase.put("receivedValueUsd", it.toPlainString()) }
            purchases.put(purchase)
        }
        val communityTrades = JSONArray()
        state.communityTrades.forEach { row ->
            communityTrades.put(JSONObject()
                .put("tradeId", row.tradeId)
                .put("mintAddress", row.mintAddress)
                .put("asset", row.asset.name)
                .put("side", row.side.name)
                .put("assetAmount", row.assetAmount.toPlainString())
                .put("tokenQuantity", row.tokenQuantity.toPlainString())
                .put("tradedAtEpochMillis", row.tradedAtEpochMillis))
        }
        val encoded = JSONObject()
            .put("refreshTapCount", state.refreshTapCount)
            .put("fundedAtEpochMillis", state.fundedAtEpochMillis ?: 0L)
            .put("purchases", purchases)
            .put("communityTrades", communityTrades).toString()
        return state.takeIf {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(userId, encoded).commit()
        }
    }

    private fun validCommunityAmount(value: BigDecimal, maxScale: Int): Boolean =
        value.signum() > 0 && value.scale() in 0..maxScale && value.precision() <= 24 &&
            value <= MAX_COMMUNITY_ASSET_AMOUNT

    private fun validCommunityTrade(trade: PreviewCommunityCashTrade): Boolean =
        trade.tradeId.matches(PURCHASE_ID_PATTERN) &&
            canonicalSolanaPublicKey(trade.mintAddress) == trade.mintAddress &&
            validCommunityAmount(trade.assetAmount,
                if (trade.asset == PreviewCommunityCashAsset.SOL) 9 else 6) &&
            validCommunityAmount(trade.tokenQuantity, 9) &&
            trade.tradedAtEpochMillis > 0

    /** Replays the bounded ledger to reject corrupt/overdrawn JSON and unsafe rollbacks. */
    private fun validateCommunityTradeSequence(state: PreviewWalletState): Boolean {
        if (state.communityTrades.any { !validCommunityTrade(it) } ||
            state.communityTrades.map { it.tradeId }.toSet().size != state.communityTrades.size) return false
        if (!state.funded && state.communityTrades.isNotEmpty()) return false
        var sol = if (state.funded) PreviewWalletState.INCOMING_SOL -
            state.purchases.fold(BigDecimal.ZERO) { total, row -> total + row.spentUsd }
                .divide(PreviewWalletState.SOL_USD_RATE, 18, RoundingMode.DOWN) else BigDecimal.ZERO
        var usdc = BigDecimal.ZERO
        val quantities = HashMap<String, BigDecimal>()
        state.communityTrades.forEach { trade ->
            if (trade.side == PreviewCommunityTradeSide.BUY) {
                if (trade.asset == PreviewCommunityCashAsset.SOL) sol -= trade.assetAmount
                else usdc -= trade.assetAmount
                quantities[trade.mintAddress] = (quantities[trade.mintAddress] ?: BigDecimal.ZERO) +
                    trade.tokenQuantity
            } else {
                val held = quantities[trade.mintAddress] ?: BigDecimal.ZERO
                if (trade.tokenQuantity > held) return false
                quantities[trade.mintAddress] = held - trade.tokenQuantity
                if (trade.asset == PreviewCommunityCashAsset.SOL) sol += trade.assetAmount
                else usdc += trade.assetAmount
            }
        }
        return sol.signum() >= 0 && usdc.signum() >= 0
    }
}

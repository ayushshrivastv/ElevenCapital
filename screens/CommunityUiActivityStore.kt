package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Local creator-token actions. These records are not wallet or on-chain transactions. */
enum class CommunityUiActivityKind { MINT, BUY, SELL }

data class CommunityUiActivity(
    val id: String,
    val kind: CommunityUiActivityKind,
    val mintAddress: String,
    val symbol: String,
    val occurredAtMillis: Long,
    /** USD value shown at the time of a local trade; never inferred from token quantity. */
    val amountUsd: BigDecimal? = null,
)

/** Debug-only activity, kept apart from verified wallet and purchase history. */
internal object CommunityUiActivityStore {
    private const val PREFERENCES = "eleven-community-ui-activity-v1"

    fun read(context: Context, userId: String): List<CommunityUiActivity> {
        if (!BuildConfig.DEBUG || !validActivityUserId(userId)) return emptyList()
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ownerKey(userId), null) ?: return emptyList()
        val saved = decodeCommunityUiActivity(raw, System.currentTimeMillis())
            ?.map(::currentPreviewActivity) ?: return emptyList()
        // Earlier debug builds saved the trade ID without its USD quote. Recover only when
        // the same ID has a matching local wallet-ledger entry; never guess from a holding.
        val walletTrades = PreviewWalletStore.read(context, userId).communityTrades
        val tradesById = walletTrades.associateBy { it.tradeId }
        val holdingByMint = CommunityScannedTokenPurchaseStore.read(context, userId)
            .associateBy { it.mintAddress }
        return saved.map { activity ->
            if (activity.amountUsd != null || activity.kind == CommunityUiActivityKind.MINT) activity
            else {
                val trade = tradesById[activity.id.substringAfter(':')]
                    ?.takeIf { it.mintAddress == activity.mintAddress &&
                        (activity.kind == CommunityUiActivityKind.BUY &&
                            it.side == PreviewCommunityTradeSide.BUY ||
                            activity.kind == CommunityUiActivityKind.SELL &&
                            it.side == PreviewCommunityTradeSide.SELL) }
                val exactAmount = trade?.let {
                    if (it.asset == PreviewCommunityCashAsset.SOL)
                        it.assetAmount.multiply(PreviewWalletState.SOL_USD_RATE)
                    else it.assetAmount
                }
                // Before the cash ledger existed, one completed Buy could only be recovered
                // from its sole holding. Do not apportion an aggregate across repeated Buys.
                val legacyAmount = if (exactAmount == null && activity.kind == CommunityUiActivityKind.BUY &&
                    activity.mintAddress == tokenLaunchUiPreviewResult().mintAddress &&
                    saved.count { it.kind == CommunityUiActivityKind.BUY &&
                        it.mintAddress == activity.mintAddress } == 1 &&
                    saved.none { it.kind == CommunityUiActivityKind.SELL &&
                        it.mintAddress == activity.mintAddress } &&
                    walletTrades.none { it.mintAddress == activity.mintAddress }) {
                    holdingByMint[activity.mintAddress]?.takeIf {
                        kotlin.math.abs(it.purchasedAtMillis - activity.occurredAtMillis) <= 60_000L &&
                            it.receivedTokens != null &&
                            (it.spentSol != null) != (it.spentUsd != null)
                    }?.let(::spxtrPreviewHoldingCostUsd)
                } else null
                activity.copy(amountUsd = exactAmount ?: legacyAmount)
            }
        }
    }

    /** One Mint per local launch. Re-reading a confirmed screen cannot create duplicates. */
    fun recordMint(
        context: Context,
        userId: String,
        mintAddress: String,
        symbol: String,
        occurredAtMillis: Long = System.currentTimeMillis(),
    ): List<CommunityUiActivity>? = record(context, userId,
        CommunityUiActivity("mint:$mintAddress", CommunityUiActivityKind.MINT,
            mintAddress, symbol, occurredAtMillis))

    /** A fresh ID represents one completed simulated Buy action, even for the same token. */
    fun recordBuy(
        context: Context,
        userId: String,
        mintAddress: String,
        symbol: String,
        eventId: String = UUID.randomUUID().toString(),
        occurredAtMillis: Long = System.currentTimeMillis(),
        amountUsd: BigDecimal? = null,
    ): List<CommunityUiActivity>? = record(context, userId,
        CommunityUiActivity("buy:$eventId", CommunityUiActivityKind.BUY,
            mintAddress, symbol, occurredAtMillis, amountUsd))

    /** Reserved for a completed local Sell UI action; no Sell action is connected yet. */
    fun recordSell(
        context: Context,
        userId: String,
        mintAddress: String,
        symbol: String,
        eventId: String = UUID.randomUUID().toString(),
        occurredAtMillis: Long = System.currentTimeMillis(),
        amountUsd: BigDecimal? = null,
    ): List<CommunityUiActivity>? = record(context, userId,
        CommunityUiActivity("sell:$eventId", CommunityUiActivityKind.SELL,
            mintAddress, symbol, occurredAtMillis, amountUsd))

    @Synchronized
    fun removeForMint(
        context: Context,
        userId: String,
        mintAddress: String,
    ): List<CommunityUiActivity>? {
        if (!BuildConfig.DEBUG || !validActivityUserId(userId) ||
            canonicalSolanaPublicKey(mintAddress) != mintAddress) return null
        val current = read(context, userId)
        val remaining = removeCommunityUiActivityForMint(current, mintAddress) ?: return null
        if (remaining == current) return remaining
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        if (remaining.isEmpty()) editor.remove(ownerKey(userId))
        else editor.putString(ownerKey(userId), encodeCommunityUiActivity(remaining) ?: return null)
        return remaining.takeIf { editor.commit() }
    }

    /** Clears this user's local token actions, never wallet or chain transaction history. */
    @Synchronized
    fun clear(context: Context, userId: String): Boolean =
        BuildConfig.DEBUG && validActivityUserId(userId) &&
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(ownerKey(userId)).commit()

    @Synchronized
    private fun record(
        context: Context,
        userId: String,
        activity: CommunityUiActivity,
    ): List<CommunityUiActivity>? {
        val currentActivity = currentPreviewActivity(activity)
        if (!BuildConfig.DEBUG || !validActivityUserId(userId) || !validCommunityUiActivity(currentActivity) ||
            currentActivity.occurredAtMillis > System.currentTimeMillis() + MAX_FUTURE_SKEW_MILLIS) return null
        val current = read(context, userId)
        val updated = addCommunityUiActivity(current, currentActivity) ?: return null
        if (updated == current) return updated
        val encoded = encodeCommunityUiActivity(updated) ?: return null
        return updated.takeIf {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(ownerKey(userId), encoded).commit()
        }
    }

    private fun ownerKey(userId: String): String = "user:${encodeActivityText(userId)}"
}

/** The same synthetic mint survives the Christmas ticker rename in saved local history. */
private fun currentPreviewActivity(activity: CommunityUiActivity): CommunityUiActivity =
    if (activity.mintAddress == tokenLaunchUiPreviewResult().mintAddress &&
        activity.symbol == "SPXTR") {
        activity.copy(symbol = TOKEN_LAUNCH_UI_PREVIEW_SYMBOL)
    } else activity

internal fun addCommunityUiActivity(
    existing: List<CommunityUiActivity>,
    activity: CommunityUiActivity,
): List<CommunityUiActivity>? {
    if (encodeCommunityUiActivity(existing) == null || !validCommunityUiActivity(activity)) return null
    existing.firstOrNull { it.id == activity.id }?.let { previous ->
        return existing.takeIf { previous == activity ||
            (activity.kind == CommunityUiActivityKind.MINT && previous.kind == activity.kind &&
                previous.mintAddress == activity.mintAddress && previous.symbol == activity.symbol) }
    }
    return (listOf(activity) + existing).take(MAX_ACTIVITY_ROWS)
}

internal fun removeCommunityUiActivityForMint(
    existing: List<CommunityUiActivity>,
    mintAddress: String,
): List<CommunityUiActivity>? {
    if (canonicalSolanaPublicKey(mintAddress) != mintAddress ||
        encodeCommunityUiActivity(existing) == null) return null
    return existing.filterNot { it.mintAddress == mintAddress }
}

internal fun encodeCommunityUiActivity(rows: List<CommunityUiActivity>): String? {
    if (rows.size > MAX_ACTIVITY_ROWS || rows.map(CommunityUiActivity::id).toSet().size != rows.size ||
        rows.any { !validCommunityUiActivity(it) }) return null
    return rows.joinToString("\n") { row ->
        val prefix = listOf(if (row.amountUsd == null) "1" else "2", row.id,
            row.kind.name, row.mintAddress, encodeActivityText(row.symbol),
            row.occurredAtMillis.toString())
        (prefix + listOfNotNull(row.amountUsd?.stripTrailingZeros()?.toPlainString()))
            .joinToString("|")
    }.takeIf { it.length <= MAX_ACTIVITY_BYTES }
}

internal fun decodeCommunityUiActivity(raw: String, nowMillis: Long): List<CommunityUiActivity>? {
    if (raw.length > MAX_ACTIVITY_BYTES || nowMillis <= 0L) return null
    if (raw.isEmpty()) return emptyList()
    val lines = raw.split('\n')
    if (lines.size > MAX_ACTIVITY_ROWS) return null
    val rows = lines.map { line ->
        val fields = line.split('|')
        if (fields.size != 6 && fields.size != 7) return null
        val expectedVersion = if (fields.size == 6) "1" else "2"
        if (fields[0] != expectedVersion) return null
        val kind = CommunityUiActivityKind.entries.firstOrNull { it.name == fields[2] } ?: return null
        val symbol = decodeActivityText(fields[4]) ?: return null
        val timestamp = fields[5].toLongOrNull() ?: return null
        if (fields[5] != timestamp.toString() || timestamp > nowMillis + MAX_FUTURE_SKEW_MILLIS) return null
        val amount = if (fields.size == 7)
            runCatching { BigDecimal(fields[6]) }.getOrNull() ?: return null else null
        CommunityUiActivity(fields[1], kind, fields[3], symbol, timestamp, amount)
    }
    return rows.takeIf { encodeCommunityUiActivity(it) == raw }
}

private fun validCommunityUiActivity(row: CommunityUiActivity): Boolean =
    row.id.matches(Regex("[A-Za-z0-9:_-]{1,100}")) &&
        row.id.startsWith("${row.kind.name.lowercase()}:") &&
        canonicalSolanaPublicKey(row.mintAddress) == row.mintAddress &&
        row.symbol.isNotBlank() && row.symbol == row.symbol.trim() &&
        row.symbol.toByteArray(Charsets.UTF_8).size <= 48 &&
        row.symbol.none(Char::isISOControl) &&
        (row.amountUsd == null ||
            (row.kind != CommunityUiActivityKind.MINT && row.amountUsd.signum() > 0 &&
                row.amountUsd <= BigDecimal("1000000000000") &&
                row.amountUsd.stripTrailingZeros().scale() <= 8)) &&
        row.occurredAtMillis > 0L

private fun validActivityUserId(userId: String): Boolean =
    userId.isNotBlank() && userId.length <= 256 && userId == userId.trim() &&
        userId.none(Char::isISOControl) && userId.none(Char::isWhitespace)

private fun encodeActivityText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeActivityText(value: String): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString()
}.getOrNull()?.takeIf { encodeActivityText(it) == value }

private const val MAX_ACTIVITY_ROWS = 200
private const val MAX_ACTIVITY_BYTES = 96_000
private const val MAX_FUTURE_SKEW_MILLIS = 5 * 60 * 1_000L

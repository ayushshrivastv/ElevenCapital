package com.elevencapital.app.screens

import android.content.Context
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.data.MeteoraMarketToken
import com.elevencapital.app.data.safeTokenImageUrl
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

private val SPXTR_TOKENS_PER_USD = BigDecimal("1000")

/** A local, simulated Buy result. This is never an on-chain purchase or proof of ownership. */
data class CommunityScannedTokenPurchase(
    val mintAddress: String,
    val purchasedAtMillis: Long,
    val name: String? = null,
    val symbol: String? = null,
    val imageUrl: String? = null,
    val spentSol: BigDecimal? = null,
    val receivedTokens: BigDecimal? = null,
    val spentUsd: BigDecimal? = null,
)

/** A fixed local quote for the SPXTR interaction; it is never a Meteora or wallet quote. */
internal fun spxtrPreviewReceivedTokens(spentSol: BigDecimal): BigDecimal? {
    if (!validScannedPurchaseAmount(spentSol)) return null
    return spentSol.multiply(BigDecimal("1000"))
        .setScale(6, RoundingMode.DOWN)
        .takeIf(::validScannedPurchaseAmount)
}

/** Fixed local USD quote for new SPXTR Buys: $0.40 receives 400 SPXTR. */
internal fun spxtrPreviewReceivedTokensUsd(spentUsd: BigDecimal): BigDecimal? {
    if (!validScannedPurchaseAmount(spentUsd)) return null
    return spentUsd.multiply(SPXTR_TOKENS_PER_USD)
        .setScale(6, RoundingMode.DOWN)
        .takeIf(::validScannedPurchaseAmount)
}

/** The local SPXTR holding's USD cost basis, using the same SOL quote as the local wallet. */
internal fun spxtrPreviewHoldingValueUsd(spentSol: BigDecimal?): BigDecimal? {
    val spend = spentSol?.takeIf(::validScannedPurchaseAmount) ?: return null
    return spend.multiply(PreviewWalletState.INCOMING_SOL_USD)
        .divide(PreviewWalletState.INCOMING_SOL, 2, RoundingMode.HALF_UP)
}

/** Combines earlier SOL-denominated cost basis with new USD-denominated Buys. */
internal fun spxtrPreviewHoldingCostUsd(purchase: CommunityScannedTokenPurchase?): BigDecimal? {
    purchase ?: return null
    val legacyUsd = spxtrPreviewHoldingValueUsd(purchase.spentSol)
    val newUsd = purchase.spentUsd?.takeIf(::validScannedPurchaseAmount)
    if (legacyUsd == null && newUsd == null) return null
    return ((legacyUsd ?: BigDecimal.ZERO) + (newUsd ?: BigDecimal.ZERO))
        .setScale(2, RoundingMode.HALF_UP)
}

/** The flat SPXTR opening quote values tokens at $0.001 each, independent of units paid. */
internal fun spxtrPreviewHoldingMarketValueUsd(receivedTokens: BigDecimal?): BigDecimal? {
    val quantity = receivedTokens?.takeIf(::validScannedPurchaseAmount) ?: return null
    return quantity.divide(SPXTR_TOKENS_PER_USD, 4, RoundingMode.HALF_UP)
}

/** Debug-only QR Buy state, deliberately separate from verified Mainnet purchase receipts. */
internal object CommunityScannedTokenPurchaseStore {
    private const val PREFERENCES = "eleven-community-scanned-ui-purchases-v1"
    private val TRADE_ID_PATTERN = Regex("[A-Za-z0-9:_-]{1,100}")

    fun read(context: Context, userId: String): List<CommunityScannedTokenPurchase> {
        if (!BuildConfig.DEBUG || !validScannedPurchaseUserId(userId)) return emptyList()
        val raw = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ownerKey(userId), null) ?: return emptyList()
        return decodeCommunityScannedTokenPurchases(raw, System.currentTimeMillis()) ?: emptyList()
    }

    /** Records a simulated Buy only after the QR Buy UI completes. Never invokes wallet or chain code. */
    @Synchronized
    fun record(
        context: Context,
        userId: String,
        mintAddress: String,
        metadata: MeteoraMarketToken? = null,
        spentSol: BigDecimal? = null,
        receivedTokens: BigDecimal? = null,
        spentUsd: BigDecimal? = null,
    ): List<CommunityScannedTokenPurchase>? {
        if (!BuildConfig.DEBUG || !validScannedPurchaseUserId(userId) ||
            canonicalSolanaPublicKey(mintAddress) != mintAddress) return null
        val updated = addCommunityScannedTokenPurchase(
            read(context, userId), mintAddress, metadata, System.currentTimeMillis(),
            spentSol, receivedTokens, spentUsd,
        ) ?: return null
        val encoded = encodeCommunityScannedTokenPurchases(updated) ?: return null
        return updated.takeIf {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(ownerKey(userId), encoded).commit()
        }
    }

    /** Idempotent, quantified local Buy. The journal and holding are committed together. */
    @Synchronized
    fun recordBuy(
        context: Context,
        userId: String,
        mintAddress: String,
        tradeId: String,
        metadata: MeteoraMarketToken? = null,
        spentSol: BigDecimal? = null,
        receivedTokens: BigDecimal,
        spentUsd: BigDecimal? = null,
    ): List<CommunityScannedTokenPurchase>? {
        if (!BuildConfig.DEBUG || !validScannedPurchaseUserId(userId) ||
            canonicalSolanaPublicKey(mintAddress) != mintAddress ||
            !tradeId.matches(TRADE_ID_PATTERN)) return null
        val trade = CommunityPurchaseTradeRecord(tradeId, "BUY", mintAddress,
            spentSol, spentUsd, receivedTokens)
        if (!validPurchaseTradeRecord(trade)) return null
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val journal = readPurchaseTradeJournal(preferences.getString(tradeJournalKey(userId), null)) ?: return null
        journal.firstOrNull { it.tradeId == tradeId }?.let { previous ->
            return read(context, userId).takeIf { samePurchaseTrade(previous, trade) }
        }
        if (journal.size >= MAX_PURCHASE_TRADE_RECORDS) return null
        val updated = addCommunityScannedTokenPurchase(read(context, userId), mintAddress,
            metadata, System.currentTimeMillis(), spentSol, receivedTokens, spentUsd) ?: return null
        val encoded = encodeCommunityScannedTokenPurchases(updated) ?: return null
        val encodedJournal = encodePurchaseTradeJournal(journal + trade) ?: return null
        return updated.takeIf {
            preferences.edit().putString(ownerKey(userId), encoded)
                .putString(tradeJournalKey(userId), encodedJournal).commit()
        }
    }

    /** Idempotent partial/full local Sell. A full sale removes the holding row. */
    @Synchronized
    fun recordSell(
        context: Context,
        userId: String,
        mintAddress: String,
        tradeId: String,
        soldTokens: BigDecimal,
    ): List<CommunityScannedTokenPurchase>? {
        if (!BuildConfig.DEBUG || !validScannedPurchaseUserId(userId) ||
            canonicalSolanaPublicKey(mintAddress) != mintAddress ||
            !tradeId.matches(TRADE_ID_PATTERN)) return null
        val trade = CommunityPurchaseTradeRecord(tradeId, "SELL", mintAddress,
            null, null, soldTokens)
        if (!validPurchaseTradeRecord(trade)) return null
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val journal = readPurchaseTradeJournal(preferences.getString(tradeJournalKey(userId), null)) ?: return null
        journal.firstOrNull { it.tradeId == tradeId }?.let { previous ->
            return read(context, userId).takeIf { samePurchaseTrade(previous, trade) }
        }
        if (journal.size >= MAX_PURCHASE_TRADE_RECORDS) return null
        val updated = sellCommunityScannedTokenPurchase(read(context, userId),
            mintAddress, soldTokens) ?: return null
        val encoded = encodeCommunityScannedTokenPurchases(updated) ?: return null
        val encodedJournal = encodePurchaseTradeJournal(journal + trade) ?: return null
        val editor = preferences.edit().putString(tradeJournalKey(userId), encodedJournal)
        if (updated.isEmpty()) editor.remove(ownerKey(userId))
        else editor.putString(ownerKey(userId), encoded)
        return updated.takeIf { editor.commit() }
    }

    /** Removes one simulated Buy for this user; verified purchase receipts are stored elsewhere. */
    @Synchronized
    fun remove(
        context: Context,
        userId: String,
        mintAddress: String,
    ): List<CommunityScannedTokenPurchase>? {
        if (!BuildConfig.DEBUG || !validScannedPurchaseUserId(userId)) return null
        val current = read(context, userId)
        val updated = removeCommunityScannedTokenPurchase(current, mintAddress) ?: return null
        if (updated == current) return updated
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        if (updated.isEmpty()) editor.remove(ownerKey(userId))
        else editor.putString(ownerKey(userId), encodeCommunityScannedTokenPurchases(updated) ?: return null)
        return updated.takeIf { editor.commit() }
    }

    /** Clears this user's simulated QR Buys, without touching verified purchase receipts. */
    @Synchronized
    fun clear(context: Context, userId: String): Boolean =
        BuildConfig.DEBUG && validScannedPurchaseUserId(userId) &&
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().remove(ownerKey(userId)).remove(tradeJournalKey(userId)).commit()

    private fun ownerKey(userId: String): String = "user:${encodeScannedPurchaseText(userId)}"
    private fun tradeJournalKey(userId: String): String = "trades:${encodeScannedPurchaseText(userId)}"

    private data class CommunityPurchaseTradeRecord(
        val tradeId: String,
        val side: String,
        val mintAddress: String,
        val spentSol: BigDecimal?,
        val spentUsd: BigDecimal?,
        val tokens: BigDecimal,
    )

    private fun samePurchaseTrade(a: CommunityPurchaseTradeRecord, b: CommunityPurchaseTradeRecord): Boolean =
        a.tradeId == b.tradeId && a.side == b.side && a.mintAddress == b.mintAddress &&
            a.spentSol.compareNullable(b.spentSol) && a.spentUsd.compareNullable(b.spentUsd) &&
            a.tokens.compareTo(b.tokens) == 0

    private fun BigDecimal?.compareNullable(other: BigDecimal?): Boolean =
        if (this == null || other == null) this == null && other == null else compareTo(other) == 0

    private fun validPurchaseTradeRecord(record: CommunityPurchaseTradeRecord): Boolean {
        val buy = record.side == "BUY" &&
            ((record.spentSol != null) != (record.spentUsd != null)) &&
            (record.spentSol == null || validScannedPurchaseAmount(record.spentSol)) &&
            (record.spentUsd == null || validScannedPurchaseAmount(record.spentUsd))
        val sell = record.side == "SELL" && record.spentSol == null && record.spentUsd == null
        return record.tradeId.matches(TRADE_ID_PATTERN) &&
            canonicalSolanaPublicKey(record.mintAddress) == record.mintAddress &&
            validScannedPurchaseAmount(record.tokens) && (buy || sell)
    }

    private fun readPurchaseTradeJournal(raw: String?): List<CommunityPurchaseTradeRecord>? {
        if (raw == null || raw.isEmpty()) return emptyList()
        if (raw.length > MAX_PURCHASE_TRADE_BYTES) return null
        val rows = raw.split('\n')
        if (rows.size > MAX_PURCHASE_TRADE_RECORDS) return null
        val decoded = rows.map { row ->
            val fields = row.split('|')
            if (fields.size != 6) return null
            CommunityPurchaseTradeRecord(fields[0], fields[1], fields[2],
                fields[3].takeUnless { it == "-" }?.toBigDecimalOrNull(),
                fields[4].takeUnless { it == "-" }?.toBigDecimalOrNull(),
                fields[5].toBigDecimalOrNull() ?: return null)
        }
        return decoded.takeIf { encodePurchaseTradeJournal(it) == raw }
    }

    private fun encodePurchaseTradeJournal(records: List<CommunityPurchaseTradeRecord>): String? {
        if (records.size > MAX_PURCHASE_TRADE_RECORDS ||
            records.map { it.tradeId }.toSet().size != records.size ||
            records.any { !validPurchaseTradeRecord(it) }) return null
        return records.joinToString("\n") { record ->
            listOf(record.tradeId, record.side, record.mintAddress,
                record.spentSol?.toPlainString() ?: "-",
                record.spentUsd?.toPlainString() ?: "-",
                record.tokens.toPlainString()).joinToString("|")
        }.takeIf { it.length <= MAX_PURCHASE_TRADE_BYTES }
    }
}

/** Returns null for a missing, unquantified, or over-sold holding. */
internal fun sellCommunityScannedTokenPurchase(
    existing: List<CommunityScannedTokenPurchase>,
    mintAddress: String,
    soldTokens: BigDecimal,
): List<CommunityScannedTokenPurchase>? {
    if (canonicalSolanaPublicKey(mintAddress) != mintAddress ||
        !validScannedPurchaseAmount(soldTokens) ||
        encodeCommunityScannedTokenPurchases(existing) == null) return null
    val old = existing.firstOrNull { it.mintAddress == mintAddress } ?: return null
    val held = old.receivedTokens ?: return null
    if (soldTokens > held) return null
    val remaining = held - soldTokens
    if (remaining.signum() == 0) return existing.filterNot { it.mintAddress == mintAddress }
    fun remainingBasis(basis: BigDecimal?): BigDecimal? = basis?.let {
        it.multiply(remaining).divide(held, 9, RoundingMode.HALF_UP)
            .max(BigDecimal("0.000000001"))
    }
    val updated = old.copy(receivedTokens = remaining,
        spentSol = remainingBasis(old.spentSol), spentUsd = remainingBasis(old.spentUsd))
    if (!validScannedPurchase(updated)) return null
    return existing.map { if (it.mintAddress == mintAddress) updated else it }
}

internal fun removeCommunityScannedTokenPurchase(
    existing: List<CommunityScannedTokenPurchase>,
    mintAddress: String,
): List<CommunityScannedTokenPurchase>? {
    if (canonicalSolanaPublicKey(mintAddress) != mintAddress ||
        encodeCommunityScannedTokenPurchases(existing) == null) return null
    return existing.filterNot { it.mintAddress == mintAddress }
}

internal fun addCommunityScannedTokenPurchase(
    existing: List<CommunityScannedTokenPurchase>,
    mintAddress: String,
    metadata: MeteoraMarketToken?,
    purchasedAtMillis: Long,
    spentSol: BigDecimal? = null,
    receivedTokens: BigDecimal? = null,
    spentUsd: BigDecimal? = null,
): List<CommunityScannedTokenPurchase>? {
    if (canonicalSolanaPublicKey(mintAddress) != mintAddress || purchasedAtMillis <= 0 ||
        encodeCommunityScannedTokenPurchases(existing) == null ||
        (spentSol != null && spentUsd != null) ||
        (spentSol != null || spentUsd != null) != (receivedTokens != null) ||
        (spentSol != null && !validScannedPurchaseAmount(spentSol)) ||
        (spentUsd != null && !validScannedPurchaseAmount(spentUsd)) ||
        (receivedTokens != null && !validScannedPurchaseAmount(receivedTokens))) return null
    val previous = existing.firstOrNull { it.mintAddress == mintAddress }
    val match = metadata?.takeIf {
        it.mintAddress == mintAddress && canonicalSolanaPublicKey(it.poolAddress) == it.poolAddress &&
            validScannedPurchaseText(it.name, 96) && validScannedPurchaseText(it.symbol, 24)
    }
    val purchase = CommunityScannedTokenPurchase(
        mintAddress = mintAddress,
        purchasedAtMillis = purchasedAtMillis,
        name = match?.name ?: previous?.name,
        symbol = match?.symbol ?: previous?.symbol,
        imageUrl = match?.imageUrl?.let(::safeTokenImageUrl) ?: previous?.imageUrl,
        spentSol = if (spentSol != null) (previous?.spentSol ?: BigDecimal.ZERO) + spentSol
            else previous?.spentSol,
        receivedTokens = if (receivedTokens != null)
            (previous?.receivedTokens ?: BigDecimal.ZERO) + receivedTokens else previous?.receivedTokens,
        spentUsd = if (spentUsd != null) (previous?.spentUsd ?: BigDecimal.ZERO) + spentUsd
            else previous?.spentUsd,
    )
    if (!validScannedPurchase(purchase)) return null
    return (listOf(purchase) + existing.filterNot { it.mintAddress == mintAddress })
        .take(MAX_SCANNED_PURCHASES)
}

internal fun encodeCommunityScannedTokenPurchases(
    purchases: List<CommunityScannedTokenPurchase>,
): String? {
    if (purchases.size > MAX_SCANNED_PURCHASES ||
        purchases.map { it.mintAddress }.toSet().size != purchases.size ||
        purchases.any { !validScannedPurchase(it) }) return null
    return purchases.joinToString("\n") { purchase ->
        val quantified = purchase.receivedTokens != null
        val usdDenominated = purchase.spentUsd != null
        (listOf(
            if (usdDenominated) "3" else if (quantified) "2" else "1",
            purchase.mintAddress, purchase.purchasedAtMillis.toString(),
            purchase.name?.let(::encodeScannedPurchaseText) ?: "-",
            purchase.symbol?.let(::encodeScannedPurchaseText) ?: "-",
            purchase.imageUrl?.let(::encodeScannedPurchaseText) ?: "-",
        ) + if (usdDenominated) listOf(
            purchase.spentSol?.toPlainString() ?: "-",
            requireNotNull(purchase.spentUsd).toPlainString(),
            requireNotNull(purchase.receivedTokens).toPlainString(),
        ) else if (quantified) listOf(
            requireNotNull(purchase.spentSol).toPlainString(),
            requireNotNull(purchase.receivedTokens).toPlainString(),
        ) else emptyList()).joinToString("|")
    }.takeIf { it.length <= MAX_SCANNED_PURCHASE_BYTES }
}

internal fun decodeCommunityScannedTokenPurchases(
    raw: String,
    nowMillis: Long,
): List<CommunityScannedTokenPurchase>? {
    if (raw.length > MAX_SCANNED_PURCHASE_BYTES || nowMillis <= 0) return null
    if (raw.isEmpty()) return emptyList()
    val rows = raw.split('\n')
    if (rows.size > MAX_SCANNED_PURCHASES) return null
    val decoded = rows.map { row ->
        val fields = row.split('|')
        if ((fields.size != 6 || fields[0] != "1") &&
            (fields.size != 8 || fields[0] != "2") &&
            (fields.size != 9 || fields[0] != "3")) return null
        val purchasedAtMillis = fields[2].toLongOrNull() ?: return null
        if (fields[2] != purchasedAtMillis.toString()) return null
        CommunityScannedTokenPurchase(
            mintAddress = fields[1],
            purchasedAtMillis = purchasedAtMillis,
            name = (decodeOptionalScannedPurchaseText(fields[3]) ?: return null).value,
            symbol = (decodeOptionalScannedPurchaseText(fields[4]) ?: return null).value,
            imageUrl = (decodeOptionalScannedPurchaseText(fields[5]) ?: return null).value,
            spentSol = when (fields[0]) {
                "2" -> fields[6].toBigDecimalOrNull() ?: return null
                "3" -> if (fields[6] == "-") null else fields[6].toBigDecimalOrNull() ?: return null
                else -> null
            },
            receivedTokens = when (fields[0]) {
                "2" -> fields[7].toBigDecimalOrNull() ?: return null
                "3" -> fields[8].toBigDecimalOrNull() ?: return null
                else -> null
            },
            spentUsd = if (fields[0] == "3") fields[7].toBigDecimalOrNull() ?: return null else null,
        )
    }
    if (decoded.any { it.purchasedAtMillis > nowMillis + MAX_FUTURE_SKEW_MILLIS }) return null
    return decoded.takeIf { encodeCommunityScannedTokenPurchases(it) == raw }
}

private fun validScannedPurchase(value: CommunityScannedTokenPurchase): Boolean =
    canonicalSolanaPublicKey(value.mintAddress) == value.mintAddress &&
        value.purchasedAtMillis > 0 &&
        ((value.spentSol == null && value.spentUsd == null && value.receivedTokens == null) ||
            ((value.spentSol != null || value.spentUsd != null) && value.receivedTokens != null &&
                (value.spentSol == null || validScannedPurchaseAmount(value.spentSol)) &&
                (value.spentUsd == null || validScannedPurchaseAmount(value.spentUsd)) &&
                validScannedPurchaseAmount(value.receivedTokens))) &&
        ((value.name == null && value.symbol == null && value.imageUrl == null) ||
            (value.name != null && value.symbol != null &&
                validScannedPurchaseText(value.name, 96) &&
                validScannedPurchaseText(value.symbol, 24) &&
                (value.imageUrl == null || safeTokenImageUrl(value.imageUrl) == value.imageUrl)))

private fun validScannedPurchaseAmount(value: BigDecimal): Boolean =
    value.signum() > 0 && value.scale() in 0..9 && value.precision() <= 24 &&
        value <= BigDecimal("1000000000000")

private fun validScannedPurchaseText(value: String, maxBytes: Int): Boolean =
    value.isNotBlank() && value == value.trim() &&
        value.toByteArray(Charsets.UTF_8).size <= maxBytes && value.none(Char::isISOControl)

private fun validScannedPurchaseUserId(value: String): Boolean =
    value.isNotBlank() && value.length <= 256 && value == value.trim() &&
        value.none(Char::isISOControl) && value.none(Char::isWhitespace)

private fun encodeScannedPurchaseText(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

/** A null return means invalid data; the `-` sentinel represents a deliberately absent field. */
private fun decodeOptionalScannedPurchaseText(raw: String): OptionalScannedPurchaseText? {
    if (raw == "-") return OptionalScannedPurchaseText(null)
    val value = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(raw))).toString()
    }.getOrNull() ?: return null
    return OptionalScannedPurchaseText(value).takeIf { encodeScannedPurchaseText(value) == raw }
}

private data class OptionalScannedPurchaseText(val value: String?)

private const val MAX_SCANNED_PURCHASES = 100
private const val MAX_SCANNED_PURCHASE_BYTES = 320_000
private const val MAX_PURCHASE_TRADE_RECORDS = 256
private const val MAX_PURCHASE_TRADE_BYTES = 64_000
private const val MAX_FUTURE_SKEW_MILLIS = 5 * 60 * 1_000L

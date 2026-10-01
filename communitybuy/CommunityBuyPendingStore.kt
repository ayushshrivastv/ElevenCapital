package com.elevencapital.app.communitybuy

import android.content.Context
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigInteger
import java.time.Instant
import org.json.JSONObject

/** Durable read-only reconciliation identity written before a signed buy can reach /submit. */
data class PendingCommunityBuy internal constructor(
    val request: CommunityBuyRequest,
    val poolAddress: String,
    val configAddress: String,
    val tokenDecimals: Int,
    val expectedAmountOut: BigInteger,
    val minimumAmountOut: BigInteger,
    val estimatedFeeLamports: Long,
    val maximumWalletDebitLamports: Long,
    val observedSlot: Long,
    val recentBlockhash: String,
    val lastValidBlockHeight: Long,
    val preparedAt: Instant,
    val expiresAt: Instant,
    val transactionSha256: String,
    val expectedSignature: String?,
) {
    internal fun forStatusOnly(): PreparedCommunityBuy = PreparedCommunityBuy(
        request, poolAddress, configAddress, tokenDecimals, expectedAmountOut, minimumAmountOut,
        estimatedFeeLamports, maximumWalletDebitLamports, observedSlot, recentBlockhash,
        lastValidBlockHeight, preparedAt, expiresAt, transactionSha256,
        ByteArray(0), ByteArray(0),
    )

    companion object {
        internal fun from(prepared: PreparedCommunityBuy, expectedSignature: String? = null) = PendingCommunityBuy(
            prepared.request, prepared.poolAddress, prepared.configAddress, prepared.tokenDecimals,
            prepared.expectedAmountOut, prepared.minimumAmountOut, prepared.estimatedFeeLamports,
            prepared.maximumWalletDebitLamports, prepared.observedSlot, prepared.recentBlockhash,
            prepared.lastValidBlockHeight, prepared.preparedAt, prepared.expiresAt,
            prepared.transactionSha256, expectedSignature,
        )
    }
}

class CommunityBuyPendingStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("eleven-community-buy-pending-mainnet-v1", Context.MODE_PRIVATE)

    fun load(userId: String, walletAddress: String): PendingCommunityBuy? {
        val encoded = preferences.getString(key(userId, walletAddress), null) ?: return null
        return try {
            if (encoded.length > 4096) error("oversized")
            val json = JSONObject(encoded)
            if (json.keys().asSequence().toSet() != FIELDS || json.getInt("schemaVersion") != 1 ||
                json.getString("network") != BUY_NETWORK) error("schema")
            val request = CommunityBuyRequest(
                json.getString("operationId"), userId, json.getString("walletId"), walletAddress,
                json.getString("mintAddress"), json.getString("amountInLamports").toLong(),
                json.getInt("slippageBps"),
            )
            val pending = PendingCommunityBuy(
                request, json.getString("poolAddress"), json.getString("configAddress"),
                json.getInt("tokenDecimals"), json.getString("expectedAmountOut").toBigInteger(),
                json.getString("minimumAmountOut").toBigInteger(),
                json.getString("estimatedFeeLamports").toLong(),
                json.getString("maximumWalletDebitLamports").toLong(),
                json.getString("observedSlot").toLong(), json.getString("recentBlockhash"),
                json.getString("lastValidBlockHeight").toLong(),
                Instant.parse(json.getString("preparedAt")), Instant.parse(json.getString("expiresAt")),
                json.getString("transactionSha256"), json.getString("expectedSignature"),
            )
            if (canonicalSolanaPublicKey(pending.poolAddress) != pending.poolAddress ||
                canonicalSolanaPublicKey(pending.configAddress) != pending.configAddress ||
                canonicalSolanaPublicKey(pending.recentBlockhash) != pending.recentBlockhash ||
                pending.tokenDecimals !in 0..18 ||
                pending.minimumAmountOut <= BigInteger.ZERO ||
                pending.expectedAmountOut < pending.minimumAmountOut ||
                pending.expectedAmountOut > U64_MAX ||
                pending.maximumWalletDebitLamports < request.amountInLamports ||
                !pending.transactionSha256.matches(Regex("[a-f0-9]{64}")) ||
                pending.expectedSignature?.let(::isCanonicalSolanaSignature) != true) error("invalid")
            pending
        } catch (_: Exception) {
            throw CommunityBuyException(CommunityBuyFailure.PROTOCOL_ERROR,
                "A pending buy cannot be checked on this device. Do not buy again until it is reconciled.",
                submissionMayHaveReachedServer = true)
        }
    }

    /** Refuses to replace an unresolved operation for this exact signed-in wallet. */
    fun save(prepared: PreparedCommunityBuy, expectedSignature: String): Boolean {
        if (!isCanonicalSolanaSignature(expectedSignature)) return false
        val request = prepared.request
        val existing = load(request.userId, request.walletAddress)
        if (existing != null && (existing.request.operationId != request.operationId ||
                existing.expectedSignature != expectedSignature)) return false
        val json = JSONObject()
            .put("schemaVersion", 1).put("network", BUY_NETWORK).put("operationId", request.operationId)
            .put("walletId", request.walletId).put("mintAddress", request.mintAddress)
            .put("amountInLamports", request.amountInLamports.toString())
            .put("slippageBps", request.slippageBps)
            .put("poolAddress", prepared.poolAddress).put("configAddress", prepared.configAddress)
            .put("tokenDecimals", prepared.tokenDecimals)
            .put("expectedAmountOut", prepared.expectedAmountOut.toString())
            .put("minimumAmountOut", prepared.minimumAmountOut.toString())
            .put("estimatedFeeLamports", prepared.estimatedFeeLamports.toString())
            .put("maximumWalletDebitLamports", prepared.maximumWalletDebitLamports.toString())
            .put("observedSlot", prepared.observedSlot.toString())
            .put("recentBlockhash", prepared.recentBlockhash)
            .put("lastValidBlockHeight", prepared.lastValidBlockHeight.toString())
            .put("preparedAt", prepared.preparedAt.toString())
            .put("expiresAt", prepared.expiresAt.toString())
            .put("transactionSha256", prepared.transactionSha256)
            .put("expectedSignature", expectedSignature)
        return preferences.edit().putString(key(request.userId, request.walletAddress), json.toString()).commit()
    }

    fun clear(pending: PendingCommunityBuy): Boolean {
        val request = pending.request
        val current = load(request.userId, request.walletAddress) ?: return true
        if (current.request.operationId != request.operationId ||
            current.transactionSha256 != pending.transactionSha256) return false
        return preferences.edit().remove(key(request.userId, request.walletAddress)).commit()
    }

    private fun key(userId: String, walletAddress: String): String {
        require(userId.isNotBlank() && userId.length <= 256 && userId.none(Char::isWhitespace))
        require(canonicalSolanaPublicKey(walletAddress) == walletAddress)
        return "pending:$userId:$walletAddress"
    }

    private companion object {
        val U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val FIELDS = setOf(
            "schemaVersion", "network", "operationId", "walletId", "mintAddress", "amountInLamports",
            "slippageBps", "poolAddress", "configAddress", "tokenDecimals", "expectedAmountOut",
            "minimumAmountOut", "estimatedFeeLamports", "maximumWalletDebitLamports",
            "observedSlot", "recentBlockhash", "lastValidBlockHeight", "preparedAt", "expiresAt",
            "transactionSha256", "expectedSignature",
        )
    }
}

package com.elevencapital.app.communitybuy

import android.content.Context
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigInteger
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Public on-chain receipts only; no keys, credentials, or unsigned transactions are stored. */
data class CommunityPurchasedToken(
    val mintAddress: String,
    val poolAddress: String,
    val transactionId: String,
    val verifiedAt: Instant,
    val lastVerifiedTokenBalanceRaw: BigInteger,
) {
    init {
        require(canonicalSolanaPublicKey(mintAddress) == mintAddress)
        require(canonicalSolanaPublicKey(poolAddress) == poolAddress)
        require(isCanonicalSolanaSignature(transactionId))
        require(lastVerifiedTokenBalanceRaw >= BigInteger.ZERO)
    }
}

class CommunityPurchasedTokenStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("eleven-community-purchases-mainnet-v1", Context.MODE_PRIVATE)

    fun load(userId: String, walletAddress: String): List<CommunityPurchasedToken> {
        val key = key(userId, walletAddress) ?: return emptyList()
        val encoded = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val list = JSONArray(encoded)
            if (list.length() > MAX_TOKENS) return@runCatching emptyList()
            List(list.length()) { index ->
                val item = list.getJSONObject(index)
                if (item.keys().asSequence().toSet() != setOf(
                        "network", "mintAddress", "poolAddress", "transactionId", "verifiedAt", "balance") ||
                    item.getString("network") != BUY_NETWORK) {
                    error("Invalid purchased token record")
                }
                CommunityPurchasedToken(
                    item.getString("mintAddress"), item.getString("poolAddress"),
                    item.getString("transactionId"), Instant.parse(item.getString("verifiedAt")),
                    item.getString("balance").toBigInteger(),
                )
            }.distinctBy(CommunityPurchasedToken::mintAddress)
        }.getOrDefault(emptyList())
    }

    /** Called only with a status verified by the buy protocol for this same reviewed wallet. */
    fun save(userId: String, status: CommunityBuyStatus): Boolean {
        if (!status.isVerifiedPurchase) return false
        val key = key(userId, status.walletAddress) ?: return false
        val receipt = runCatching {
            CommunityPurchasedToken(
                status.mintAddress, status.poolAddress, requireNotNull(status.transactionId),
                status.observedAt, status.walletTokenBalanceRaw ?: BigInteger.ZERO,
            )
        }.getOrNull() ?: return false
        val existing = load(userId, status.walletAddress)
        val records = (listOf(receipt) + existing.filterNot { it.mintAddress == receipt.mintAddress })
            .take(MAX_TOKENS)
        val json = JSONArray()
        records.forEach { record ->
            json.put(JSONObject().put("network", BUY_NETWORK).put("mintAddress", record.mintAddress)
                .put("poolAddress", record.poolAddress)
                .put("transactionId", record.transactionId)
                .put("verifiedAt", record.verifiedAt.toString())
                .put("balance", record.lastVerifiedTokenBalanceRaw.toString()))
        }
        return preferences.edit().putString(key, json.toString()).commit()
    }

    private fun key(userId: String, walletAddress: String): String? {
        if (userId.isBlank() || userId.length > 256 || userId.any(Char::isWhitespace) ||
            canonicalSolanaPublicKey(walletAddress) != walletAddress) return null
        return "purchase:$userId:$walletAddress"
    }

    private companion object { const val MAX_TOKENS = 100 }
}

package com.elevencapital.app.tokenlaunch

import android.content.Context
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private persistence for public, confirmed on-chain identifiers only. It never stores JWTs,
 * transaction bytes, wallet signatures, or backend signer material.
 */
internal fun interface TokenLaunchReceiptSink {
    fun save(receipt: ConfirmedTokenLaunchReceipt): Boolean
}

class TokenLaunchReceiptStore(context: Context) : TokenLaunchReceiptSink {
    private val preferences = context.applicationContext.getSharedPreferences(
        // Do not load historical Devnet receipts as Mainnet launches.
        "eleven-token-launch-receipts-mainnet-v1",
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private var healthy = true
    private var receipts: List<ConfirmedTokenLaunchReceipt> = runCatching {
        decode(preferences.getString(RECEIPTS_KEY, null))
    }.getOrElse {
        healthy = false
        emptyList()
    }

    val storageHealthy: Boolean get() = synchronized(lock) { healthy }

    fun receiptsForVerifiedUser(userId: String): List<ConfirmedTokenLaunchReceipt> {
        require(safeIdentity(userId, 256))
        return synchronized(lock) {
            receipts.filter { it.userId == userId }.sortedByDescending { it.confirmedAt }
        }
    }

    override fun save(receipt: ConfirmedTokenLaunchReceipt): Boolean = synchronized(lock) {
        if (!healthy) return@synchronized false
        val next = (receipts.filterNot { it.operationId == receipt.operationId } + receipt)
            .sortedByDescending { it.confirmedAt }
            .take(MAX_RECEIPTS)
        val encoded = encode(next)
        if (!preferences.edit().putString(RECEIPTS_KEY, encoded).commit()) {
            healthy = false
            return@synchronized false
        }
        receipts = next
        true
    }

    private fun decode(raw: String?): List<ConfirmedTokenLaunchReceipt> {
        if (raw == null) return emptyList()
        if (raw.length > MAX_STORAGE_CHARS) throw IllegalArgumentException()
        val array = JSONArray(raw)
        if (array.length() > MAX_RECEIPTS) throw IllegalArgumentException()
        val decoded = (0 until array.length()).map { index ->
            val value = array.get(index) as? JSONObject ?: throw IllegalArgumentException()
            if (value.keys().asSequence().toSet() != RECEIPT_KEYS) throw IllegalArgumentException()
            fun text(key: String, maximum: Int): String = (value.get(key) as? String)
                ?.takeIf { it.isNotEmpty() && it.length <= maximum && it.none(Char::isISOControl) }
                ?: throw IllegalArgumentException()
            ConfirmedTokenLaunchReceipt(
                userId = text("userId", 256),
                operationId = text("operationId", 36),
                walletAddress = text("walletAddress", 44),
                mintAddress = text("mintAddress", 44),
                configAddress = text("configAddress", 44),
                poolAddress = text("poolAddress", 44),
                transactionId = text("transactionId", 90),
                explorerUrl = text("explorerUrl", 300),
                confirmedAt = Instant.parse(text("confirmedAt", 40)),
                finalized = value.get("finalized") as? Boolean ?: throw IllegalArgumentException(),
            )
        }
        if (decoded.map { it.operationId }.distinct().size != decoded.size) throw IllegalArgumentException()
        return decoded
    }

    private fun encode(values: List<ConfirmedTokenLaunchReceipt>): String = JSONArray().also { array ->
        values.forEach { receipt ->
            array.put(JSONObject()
                .put("userId", receipt.userId)
                .put("operationId", receipt.operationId)
                .put("walletAddress", receipt.walletAddress)
                .put("mintAddress", receipt.mintAddress)
                .put("configAddress", receipt.configAddress)
                .put("poolAddress", receipt.poolAddress)
                .put("transactionId", receipt.transactionId)
                .put("explorerUrl", receipt.explorerUrl)
                .put("confirmedAt", receipt.confirmedAt.toString())
                .put("finalized", receipt.finalized))
        }
    }.toString().also { if (it.length > MAX_STORAGE_CHARS) throw IllegalArgumentException() }

    private companion object {
        const val RECEIPTS_KEY = "receipts"
        const val MAX_RECEIPTS = 64
        const val MAX_STORAGE_CHARS = 96 * 1024
        val RECEIPT_KEYS = setOf(
            "userId", "operationId", "walletAddress", "mintAddress", "configAddress", "poolAddress",
            "transactionId", "explorerUrl", "confirmedAt", "finalized",
        )
    }
}

package com.elevencapital.app.data

import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import com.elevencapital.app.wallet.isSolanaPublicKey
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class SolanaDevnetTransaction(
    val signature: String,
    val timestamp: Instant,
    val direction: SolanaActivityDirection,
    val amount: BigDecimal,
    val counterparty: String?,
)

/** Devnet is deliberately distinct from mainnet; its SOL has no USD portfolio value. */
data class SolanaDevnetSnapshot(
    val walletAddress: String,
    val status: SolanaActivityStatus,
    val observedAt: Instant,
    val balanceSol: BigDecimal?,
    val transactions: List<SolanaDevnetTransaction>,
)

object SolanaDevnetParser {
    private val amountPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")

    fun parse(source: JSONObject, requestedAddress: String, now: Instant = Instant.now()): SolanaDevnetSnapshot {
        require(isSolanaPublicKey(requestedAddress))
        require(source.keys().asSequence().toSet() == setOf("schemaVersion", "scope", "network", "walletAddress",
            "status", "observedAt", "balanceSol", "transactions"))
        require(source.get("schemaVersion") == 1 && source.get("scope") == "solana-devnet-wallet")
        require(source.get("network") == "SOLANA_DEVNET")
        require(source.get("walletAddress") == requestedAddress)
        val status = when (source.get("status")) {
            "ok" -> SolanaActivityStatus.OK
            "partial" -> SolanaActivityStatus.PARTIAL
            "unavailable" -> SolanaActivityStatus.UNAVAILABLE
            else -> error("Unknown Devnet wallet status")
        }
        val observedAt = recentInstant(source.get("observedAt") as String, now)
        val balance = nullableDecimal(source, "balanceSol")
        val rows = source.getJSONArray("transactions")
        require(rows.length() <= 16)
        val transactions = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("signature", "timestamp", "direction", "amount", "counterparty"))
            val signature = row.get("signature") as String
            require(isCanonicalSolanaSignature(signature))
            val timestamp = Instant.parse(row.get("timestamp") as String)
            require(!timestamp.isAfter(now.plusSeconds(60)))
            val direction = when (row.get("direction")) {
                "RECEIVE" -> SolanaActivityDirection.RECEIVE
                "SEND" -> SolanaActivityDirection.SEND
                else -> error("Unknown Devnet transfer direction")
            }
            val amount = requireNotNull(nullableDecimal(row, "amount"))
            require(amount.signum() > 0)
            val counterparty = nullableString(row, "counterparty")
            require(counterparty == null || isSolanaPublicKey(counterparty))
            SolanaDevnetTransaction(signature, timestamp, direction, amount, counterparty)
        }
        require(transactions.map { it.signature }.distinct().size == transactions.size)
        require(status != SolanaActivityStatus.UNAVAILABLE || balance == null && transactions.isEmpty())
        require(status == SolanaActivityStatus.UNAVAILABLE || balance != null || transactions.isNotEmpty())
        return SolanaDevnetSnapshot(requestedAddress, status, observedAt, balance, transactions)
    }

    private fun recentInstant(raw: String, now: Instant): Instant = Instant.parse(raw).also {
        require(!it.isAfter(now.plusSeconds(60)) && !it.isBefore(now.minus(Duration.ofMinutes(5))))
    }

    private fun nullableString(row: JSONObject, key: String): String? = row.get(key).let {
        if (it === JSONObject.NULL) null else (it as String)
    }

    private fun nullableDecimal(row: JSONObject, key: String): BigDecimal? = nullableString(row, key)?.let { raw ->
        require(raw.length <= 100 && amountPattern.matches(raw))
        BigDecimal(raw)
    }
}

class SolanaDevnetClient internal constructor(baseUrl: String, http: OkHttpClient = OkHttpClient(),
    private val now: () -> Instant = Instant::now) {
    private val transport = ActivityHttp(baseUrl, http)

    suspend fun wallet(walletAddress: String): SolanaDevnetSnapshot {
        require(isSolanaPublicKey(walletAddress))
        val body = JSONObject().put("walletAddress", walletAddress).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(transport.url("/v1/wallet/devnet"))
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(body).build()
        return SolanaDevnetParser.parse(transport.execute(request), walletAddress, now())
    }
}

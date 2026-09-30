package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.util.Locale
import java.util.UUID

const val METEORA_DBC_PROGRAM_ID = "dbcij3LWUppWqq96dh6gJWwBifmcGfLSB5D4DuSMaqN"
const val WRAPPED_SOL_MINT = "So11111111111111111111111111111111111111112"
const val TOKEN_LAUNCH_NETWORK = "SOLANA_MAINNET"
const val TOKEN_LAUNCH_MIGRATION_TARGET = "METEORA_DAMM_V2"
const val TOKEN_LAUNCH_AUTHORITY = "IMMUTABLE"
const val TOKEN_LAUNCH_DECIMALS = 6
const val MINIMUM_LAUNCH_BALANCE_LAMPORTS = 50_000_000L
const val MAXIMUM_LAUNCH_DEBIT_LAMPORTS = 50_000_000L

/** Exact user-reviewed values used to ask the backend for one mainnet DBC transaction. */
data class TokenLaunchRequest(
    val operationId: String,
    val userId: String,
    val walletId: String,
    val walletAddress: String,
    val name: String,
    val symbol: String,
    val metadataUri: String,
    val totalTokenSupply: String,
    val feeBps: Int,
    val migrationThresholdSol: String,
    val percentageSupplyOnMigration: Int,
) {
    init {
        require(isUuid(operationId) && operationId == operationId.lowercase(Locale.US))
        require(safeIdentity(userId, 256) && safeIdentity(walletId, 200))
        require(canonicalSolanaPublicKey(walletAddress) == walletAddress)
        require(name.isNotEmpty() && name == name.trim() && name.toByteArray(Charsets.UTF_8).size <= 32 &&
            name.none(Char::isISOControl))
        require(symbol.matches(Regex("[A-Z0-9]{1,10}")))
        require(isPublicTokenMetadataUri(metadataUri))
        require(totalTokenSupply.matches(Regex("[1-9][0-9]{0,15}")))
        require(BigInteger(totalTokenSupply) in MINIMUM_TOKEN_SUPPLY..MAXIMUM_TOKEN_SUPPLY)
        require(feeBps in 25..1_000)
        require(canonicalSolAmount(migrationThresholdSol))
        require(percentageSupplyOnMigration in 1..90)
    }

    companion object {
        fun create(
            userId: String,
            walletId: String,
            walletAddress: String,
            name: String,
            symbol: String,
            metadataUri: String,
            totalTokenSupply: String,
            feeBps: Int,
            migrationThresholdSol: String,
            percentageSupplyOnMigration: Int,
            operationId: String = UUID.randomUUID().toString(),
        ): TokenLaunchRequest = TokenLaunchRequest(
            operationId = operationId.lowercase(Locale.US),
            userId = userId,
            walletId = walletId,
            walletAddress = walletAddress,
            name = name,
            symbol = symbol.uppercase(Locale.US),
            metadataUri = metadataUri,
            totalTokenSupply = totalTokenSupply,
            feeBps = feeBps,
            migrationThresholdSol = migrationThresholdSol,
            percentageSupplyOnMigration = percentageSupplyOnMigration,
        )
    }
}

/** Independently validated, short-lived, partially signed legacy Solana transaction. */
class PreparedTokenLaunch internal constructor(
    val request: TokenLaunchRequest,
    val mintAddress: String,
    val configAddress: String,
    val poolAddress: String,
    val estimatedFeeLamports: Long,
    val maximumWalletDebitLamports: Long,
    val balanceLamports: Long,
    val simulationUnitsConsumed: Int,
    val observedSlot: Long,
    val recentBlockhash: String,
    val lastValidBlockHeight: Long,
    val preparedAt: Instant,
    val expiresAt: Instant,
    val transactionSha256: String,
    transactionBytes: ByteArray,
    messageBytes: ByteArray,
) {
    private val immutableTransaction = transactionBytes.copyOf()
    private val immutableMessage = messageBytes.copyOf()

    fun partiallySignedTransactionBytes(): ByteArray = immutableTransaction.copyOf()
    internal fun exactMessageBytes(): ByteArray = immutableMessage.copyOf()

    fun requireUsable(now: Instant = Instant.now()): PreparedTokenLaunch {
        if (!now.isBefore(expiresAt)) throw TokenLaunchException(
            TokenLaunchFailureCode.EXPIRED,
            "This launch preparation expired. Prepare it again before signing.",
            retryable = true,
            providerInvoked = false,
        )
        return this
    }
}

enum class TokenLaunchSubmissionState { BROADCAST, UNKNOWN }

data class TokenLaunchSubmission(
    val operationId: String,
    val state: TokenLaunchSubmissionState,
    val transactionId: String,
    val idempotent: Boolean,
    val submittedAt: Instant,
) {
    init {
        require(isUuid(operationId))
        require(isCanonicalSolanaSignature(transactionId))
    }
}

enum class TokenLaunchStatusState { COMMITTED, BROADCAST, CONFIRMED, FAILED, EXPIRED, UNKNOWN }

data class TokenLaunchStatus(
    val operationId: String,
    val state: TokenLaunchStatusState,
    val transactionId: String?,
    val isFinalized: Boolean,
    val message: String?,
    val observedAt: Instant,
    val mintAddress: String,
    val configAddress: String,
    val poolAddress: String,
    val explorerUrl: String,
) {
    init {
        require(isUuid(operationId))
        transactionId?.let { require(isCanonicalSolanaSignature(it)) }
        require(canonicalSolanaPublicKey(mintAddress) == mintAddress)
        require(canonicalSolanaPublicKey(configAddress) == configAddress)
        require(canonicalSolanaPublicKey(poolAddress) == poolAddress)
        require(message == null || message.length in 1..240 && message.none(Char::isISOControl))
    }

    // A processed-but-not-finalized Mainnet launch must not become a persisted Community token.
    val terminal: Boolean get() = isFinalized && state in setOf(
        TokenLaunchStatusState.CONFIRMED,
        TokenLaunchStatusState.FAILED,
        TokenLaunchStatusState.EXPIRED,
    )
}

data class ConfirmedTokenLaunchReceipt(
    val userId: String,
    val operationId: String,
    val walletAddress: String,
    val mintAddress: String,
    val configAddress: String,
    val poolAddress: String,
    val transactionId: String,
    val explorerUrl: String,
    val confirmedAt: Instant,
    val finalized: Boolean,
) {
    init {
        require(safeIdentity(userId, 256) && isUuid(operationId))
        listOf(walletAddress, mintAddress, configAddress, poolAddress).forEach {
            require(canonicalSolanaPublicKey(it) == it)
        }
        require(isCanonicalSolanaSignature(transactionId))
        require(explorerUrl == mainnetTokenExplorerUrl(mintAddress))
    }
}

enum class TokenLaunchProgress { COMMITTING, SIGNING, SUBMITTING, CONFIRMING }

enum class TokenLaunchFailureCode {
    INVALID_REQUEST,
    AUTHENTICATION_REQUIRED,
    WRONG_NETWORK,
    INSUFFICIENT_BALANCE,
    COST_TOO_HIGH,
    SIMULATION_FAILED,
    SIGNED_TRANSACTION_INVALID,
    STALE_CHAIN_DATA,
    CONFLICT,
    NOT_FOUND,
    STATE_INVALID,
    STORAGE_UNAVAILABLE,
    METADATA_UNAVAILABLE,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
    PROTOCOL_ERROR,
    EXPIRED,
    SIGNING_FAILED,
    SUBMISSION_UNCERTAIN,
    LAUNCH_FAILED,
}

class TokenLaunchException internal constructor(
    val code: TokenLaunchFailureCode,
    val userMessage: String,
    val retryable: Boolean,
    val providerInvoked: Boolean = false,
    val submissionMayHaveReachedServer: Boolean = false,
    val statusCode: Int? = null,
) : Exception(userMessage) {
    init {
        require(userMessage.isNotBlank() && userMessage.length <= 240 && userMessage.none(Char::isISOControl))
        require(statusCode == null || statusCode in 400..599)
    }
}

internal fun mainnetTokenExplorerUrl(mintAddress: String): String =
    "https://solscan.io/token/$mintAddress"

private val MINIMUM_TOKEN_SUPPLY = BigInteger("1000000")
private val MAXIMUM_TOKEN_SUPPLY = BigInteger("1000000000000")
private val MINIMUM_MIGRATION_SOL = BigDecimal("0.01")
private val MAXIMUM_MIGRATION_SOL = BigDecimal("10000")
private val SOL_AMOUNT = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,9})?")

internal fun safeIdentity(value: String, maximum: Int): Boolean =
    value.isNotEmpty() && value.length <= maximum && value.none { it.isWhitespace() || it.isISOControl() }

internal fun isUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

private fun canonicalSolAmount(value: String): Boolean {
    if (!SOL_AMOUNT.matches(value)) return false
    val amount = runCatching { BigDecimal(value) }.getOrNull() ?: return false
    return amount >= MINIMUM_MIGRATION_SOL && amount <= MAXIMUM_MIGRATION_SOL
}

internal fun isPublicTokenMetadataUri(value: String): Boolean {
    if (value.length !in 12..200 || value.any(Char::isISOControl)) return false
    if (value.matches(Regex("ipfs://[A-Za-z0-9]{32,}(?:/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*)?"))) return true
    return isPublicTokenHttpsUrl(value)
}

internal fun isPublicTokenHttpsUrl(value: String): Boolean {
    if (value.length !in 12..200 || value != value.trim() ||
        value.any { it.isISOControl() || it.isWhitespace() }) return false
    return runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching false
        val labels = host.split('.')
        uri.scheme.equals("https", ignoreCase = true) && !uri.isOpaque && uri.userInfo == null && uri.port == -1 &&
            host.length <= 253 && labels.size >= 2 && labels.all { label ->
                label.length in 1..63 && label.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
            } && labels.last().any { it in 'a'..'z' }
    }.getOrDefault(false)
}

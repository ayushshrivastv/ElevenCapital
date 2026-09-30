package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.communitybuy.SolanaBuyAddresses
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.encodeBase58
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import org.json.JSONObject

/** Strict wire parsing plus an independent decoder for the legacy partially signed transaction. */
object TokenLaunchProtocol {
    private val preparationKeys = setOf(
        "schemaVersion", "operationId", "network", "walletId", "walletAddress", "name", "symbol",
        "metadataUri", "programId", "quoteMint", "mintAddress", "configAddress", "poolAddress",
        "totalTokenSupply", "tokenDecimals", "feeBps", "migrationThresholdSol",
        "percentageSupplyOnMigration", "migrationTarget", "tokenAuthority", "dynamicFeeEnabled",
        "creatorPermanentLockedLiquidityPercentage", "estimatedFeeLamports",
        "maximumWalletDebitLamports", "balanceLamports", "simulationUnitsConsumed", "observedSlot",
        "recentBlockhash", "lastValidBlockHeight", "preparedAt", "expiresAt", "transactionSha256",
        "transactionBase64", "encoding",
    )

    fun preparation(source: JSONObject, request: TokenLaunchRequest, now: Instant = Instant.now()): PreparedTokenLaunch = guarded {
        source.exactKeys(preparationKeys)
        source.version()
        if (source.text("operationId", 36) != request.operationId ||
            source.text("network", 32) != TOKEN_LAUNCH_NETWORK ||
            source.text("walletId", 200) != request.walletId ||
            source.text("walletAddress", 44) != request.walletAddress ||
            source.text("name", 32) != request.name ||
            source.text("symbol", 10) != request.symbol ||
            source.text("metadataUri", 200) != request.metadataUri ||
            source.text("totalTokenSupply", 16) != request.totalTokenSupply ||
            source.integer("feeBps") != request.feeBps.toLong() ||
            source.text("migrationThresholdSol", 32) != request.migrationThresholdSol ||
            source.integer("percentageSupplyOnMigration") != request.percentageSupplyOnMigration.toLong()
        ) protocolFailure()
        if (source.text("programId", 44) != METEORA_DBC_PROGRAM_ID ||
            source.text("quoteMint", 44) != WRAPPED_SOL_MINT ||
            source.integer("tokenDecimals") != TOKEN_LAUNCH_DECIMALS.toLong() ||
            source.text("migrationTarget", 32) != TOKEN_LAUNCH_MIGRATION_TARGET ||
            source.text("tokenAuthority", 16) != TOKEN_LAUNCH_AUTHORITY ||
            source.boolean("dynamicFeeEnabled") ||
            source.integer("creatorPermanentLockedLiquidityPercentage") != 100L ||
            source.text("encoding", 64) != "base64-legacy-partially-signed"
        ) protocolFailure()

        val mint = source.publicKey("mintAddress")
        val config = source.publicKey("configAddress")
        val pool = source.publicKey("poolAddress")
        if (setOf(mint, config, pool).size != 3 || request.walletAddress in setOf(mint, config, pool)) protocolFailure()
        val estimatedFee = source.lamports("estimatedFeeLamports")
        val maximumDebit = source.lamports("maximumWalletDebitLamports")
        val balance = source.lamports("balanceLamports")
        if (estimatedFee <= 0L || maximumDebit < estimatedFee || maximumDebit > MAXIMUM_LAUNCH_DEBIT_LAMPORTS ||
            balance < MINIMUM_LAUNCH_BALANCE_LAMPORTS || balance < maximumDebit) protocolFailure()
        val units = source.integer("simulationUnitsConsumed")
        if (units !in 0..1_400_000) protocolFailure()
        val observedSlot = source.lamports("observedSlot")
        val lastValidBlockHeight = source.lamports("lastValidBlockHeight")
        if (observedSlot <= 0L || lastValidBlockHeight <= 0L) protocolFailure()
        val blockhash = source.publicKey("recentBlockhash")
        val preparedAt = source.instant("preparedAt")
        val expiresAt = source.instant("expiresAt")
        if (preparedAt.isAfter(now.plusSeconds(30)) || preparedAt.isBefore(now.minus(Duration.ofMinutes(2)))) protocolFailure()
        if (!expiresAt.isAfter(now) || !expiresAt.isAfter(preparedAt) ||
            expiresAt.isAfter(preparedAt.plusSeconds(120))) expired()

        val transactionHash = source.text("transactionSha256", 64)
        if (!transactionHash.matches(Regex("[a-f0-9]{64}"))) protocolFailure()
        val transaction = canonicalBase64(source.text("transactionBase64", 4_096), 75..1_232)
        if (sha256(transaction) != transactionHash) protocolFailure()
        val decoded = decodePreparedLegacyTransaction(transaction)
        if (decoded.requiredSignatures != 3 || decoded.signatures.size != 3 ||
            decoded.signatures[0].any { it != 0.toByte() } ||
            decoded.signatures[1].all { it == 0.toByte() } ||
            decoded.signatures[2].all { it == 0.toByte() } ||
            decoded.accountKeys.firstOrNull() != request.walletAddress ||
            decoded.accountKeys.take(3).toSet() != setOf(request.walletAddress, mint, config) ||
            pool !in decoded.accountKeys || WRAPPED_SOL_MINT !in decoded.accountKeys ||
            METEORA_DBC_PROGRAM_ID !in decoded.accountKeys ||
            decoded.recentBlockhash != blockhash
        ) protocolFailure()
        validateLaunchInstructions(decoded, request, mint, config, pool)

        PreparedTokenLaunch(
            request = request,
            mintAddress = mint,
            configAddress = config,
            poolAddress = pool,
            estimatedFeeLamports = estimatedFee,
            maximumWalletDebitLamports = maximumDebit,
            balanceLamports = balance,
            simulationUnitsConsumed = units.toInt(),
            observedSlot = observedSlot,
            recentBlockhash = blockhash,
            lastValidBlockHeight = lastValidBlockHeight,
            preparedAt = preparedAt,
            expiresAt = expiresAt,
            transactionSha256 = transactionHash,
            transactionBytes = transaction,
            messageBytes = decoded.messageBytes,
        )
    }

    /**
     * OnDevice Privy 0.15 emits a nonstandard one-signature serialization. TEE may instead
     * preserve all three slots. Both forms must contain the exact reviewed message; a full form
     * must also preserve the backend's two partial signatures byte-for-byte.
     */
    fun walletSignedTransaction(encoded: String, prepared: PreparedTokenLaunch): String = guarded {
        val raw = canonicalBase64(encoded, 100..1_232)
        val reader = ByteReader(raw)
        val signatureCount = reader.shortVec()
        if (signatureCount !in setOf(1, 3)) protocolFailure()
        val signatures = List(signatureCount) { reader.bytes(64) }
        val walletSignature = signatures[0]
        if (walletSignature.all { it == 0.toByte() }) protocolFailure()
        val message = reader.bytes(raw.size - reader.offset)
        if (!message.contentEquals(prepared.exactMessageBytes())) protocolFailure()
        if (signatureCount == 3) {
            val expected = decodePreparedLegacyTransaction(prepared.partiallySignedTransactionBytes()).signatures
            if (!signatures[1].contentEquals(expected[1]) || !signatures[2].contentEquals(expected[2])) {
                protocolFailure()
            }
        }
        encoded
    }

    /** The transaction ID is the Privy wallet signature, never a backend-supplied identifier. */
    fun signedTransactionId(encoded: String, prepared: PreparedTokenLaunch): String = guarded {
        val checked = walletSignedTransaction(encoded, prepared)
        val reader = ByteReader(canonicalBase64(checked, 100..1_232))
        if (reader.shortVec() !in setOf(1, 3)) protocolFailure()
        encodeBase58(reader.bytes(64))
    }

    fun mutation(source: JSONObject, operationId: String, expectedState: String): Boolean = guarded {
        source.exactKeys(setOf("schemaVersion", "operationId", "state", "idempotent"))
        source.version()
        if (source.text("operationId", 36) != operationId || source.text("state", 16) != expectedState) {
            protocolFailure()
        }
        source.boolean("idempotent")
    }

    fun submission(source: JSONObject, prepared: PreparedTokenLaunch): TokenLaunchSubmission = guarded {
        source.exactKeys(setOf("schemaVersion", "operationId", "state", "transactionId", "idempotent", "submittedAt"))
        source.version()
        if (source.text("operationId", 36) != prepared.request.operationId) protocolFailure()
        val state = when (source.text("state", 16)) {
            "broadcast" -> TokenLaunchSubmissionState.BROADCAST
            "unknown" -> TokenLaunchSubmissionState.UNKNOWN
            else -> protocolFailure()
        }
        val transactionId = source.text("transactionId", 90)
        if (!isCanonicalSolanaSignature(transactionId)) protocolFailure()
        TokenLaunchSubmission(
            operationId = prepared.request.operationId,
            state = state,
            transactionId = transactionId,
            idempotent = source.boolean("idempotent"),
            submittedAt = source.instant("submittedAt"),
        )
    }

    fun status(source: JSONObject, prepared: PreparedTokenLaunch): TokenLaunchStatus = guarded {
        source.exactKeys(setOf(
            "schemaVersion", "operationId", "state", "transactionId", "isFinalized", "message",
            "observedAt", "mintAddress", "configAddress", "poolAddress", "explorerUrl",
        ))
        source.version()
        if (source.text("operationId", 36) != prepared.request.operationId) protocolFailure()
        val state = when (source.text("state", 16)) {
            "committed" -> TokenLaunchStatusState.COMMITTED
            "broadcast" -> TokenLaunchStatusState.BROADCAST
            "confirmed" -> TokenLaunchStatusState.CONFIRMED
            "failed" -> TokenLaunchStatusState.FAILED
            "expired" -> TokenLaunchStatusState.EXPIRED
            "unknown" -> TokenLaunchStatusState.UNKNOWN
            else -> protocolFailure()
        }
        val transactionId = source.nullableText("transactionId", 90)?.also {
            if (!isCanonicalSolanaSignature(it)) protocolFailure()
        }
        if (state in setOf(TokenLaunchStatusState.BROADCAST, TokenLaunchStatusState.CONFIRMED) && transactionId == null) {
            protocolFailure()
        }
        val finalized = source.boolean("isFinalized")
        if (finalized && state !in setOf(
                TokenLaunchStatusState.CONFIRMED,
                TokenLaunchStatusState.FAILED,
                TokenLaunchStatusState.EXPIRED,
            )) protocolFailure()
        val mint = source.publicKey("mintAddress")
        val config = source.publicKey("configAddress")
        val pool = source.publicKey("poolAddress")
        if (mint != prepared.mintAddress || config != prepared.configAddress || pool != prepared.poolAddress) protocolFailure()
        val explorer = source.text("explorerUrl", 300)
        if (explorer != mainnetTokenExplorerUrl(mint)) protocolFailure()
        TokenLaunchStatus(
            operationId = prepared.request.operationId,
            state = state,
            transactionId = transactionId,
            isFinalized = finalized,
            message = source.nullableText("message", 240),
            observedAt = source.instant("observedAt"),
            mintAddress = mint,
            configAddress = config,
            poolAddress = pool,
            explorerUrl = explorer,
        )
    }

    private data class DecodedLegacyTransaction(
        val signatures: List<ByteArray>,
        val requiredSignatures: Int,
        val accountKeys: List<String>,
        val recentBlockhash: String,
        val writableKeys: Set<String>,
        val instructions: List<LaunchInstruction>,
        val messageBytes: ByteArray,
    )

    private data class LaunchInstruction(val program: String, val keys: List<String>, val data: ByteArray)

    private fun decodePreparedLegacyTransaction(raw: ByteArray): DecodedLegacyTransaction {
        val reader = ByteReader(raw)
        val signatureCount = reader.shortVec()
        if (signatureCount !in 1..8) protocolFailure()
        val signatures = List(signatureCount) { reader.bytes(64) }
        val messageStart = reader.offset
        val requiredSignatures = reader.byte()
        if (requiredSignatures and 0x80 != 0) protocolFailure() // Versioned messages are not permitted.
        val readonlySigned = reader.byte()
        val readonlyUnsigned = reader.byte()
        val keyCount = reader.shortVec()
        if (keyCount !in requiredSignatures..128 || requiredSignatures != signatureCount ||
            readonlySigned != 0 || readonlyUnsigned > keyCount - requiredSignatures) protocolFailure()
        val accountKeys = List(keyCount) { encodeBase58(reader.bytes(32)) }
        if (accountKeys.distinct().size != accountKeys.size) protocolFailure()
        val recentBlockhash = encodeBase58(reader.bytes(32))
        val instructionCount = reader.shortVec()
        if (instructionCount !in 1..64) protocolFailure()
        val instructions = List(instructionCount) {
            val programIndex = reader.byte()
            if (programIndex >= keyCount) protocolFailure()
            val accountCount = reader.shortVec()
            if (accountCount > 128) protocolFailure()
            val keys = List(accountCount) { accountKeys.getOrNull(reader.byte()) ?: protocolFailure() }
            val dataLength = reader.shortVec()
            if (dataLength > raw.size - reader.offset) protocolFailure()
            LaunchInstruction(accountKeys[programIndex], keys, reader.bytes(dataLength))
        }
        if (reader.offset != raw.size) protocolFailure()
        val referencedKeys = instructions.flatMap { it.keys + it.program }.toSet()
        if (accountKeys.toSet() != referencedKeys) protocolFailure()
        return DecodedLegacyTransaction(
            signatures,
            requiredSignatures,
            accountKeys,
            recentBlockhash,
            accountKeys.take(keyCount - readonlyUnsigned).toSet(),
            instructions,
            raw.copyOfRange(messageStart, raw.size),
        )
    }

    /** Independent Android check of the two exact DBC launch instructions before Privy signs. */
    private fun validateLaunchInstructions(
        decoded: DecodedLegacyTransaction,
        request: TokenLaunchRequest,
        mint: String,
        config: String,
        pool: String,
    ) {
        val wallet = request.walletAddress
        val baseVault = SolanaBuyAddresses.dbcVault(pool, mint)
        val quoteVault = SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT)
        val metadata = SolanaBuyAddresses.mintMetadata(mint)
        if (pool != SolanaBuyAddresses.dbcPool(mint, config) ||
            decoded.writableKeys != setOf(wallet, mint, config, pool, baseVault, quoteVault, metadata) ||
            decoded.instructions.size != 2) protocolFailure()
        val create = decoded.instructions[0]
        if (create.program != METEORA_DBC_PROGRAM_ID || create.keys != listOf(
                config, wallet, wallet, WRAPPED_SOL_MINT, wallet, SolanaBuyAddresses.SYSTEM,
                SolanaBuyAddresses.DBC_EVENT_AUTHORITY, METEORA_DBC_PROGRAM_ID,
            )) protocolFailure()
        validateCreateConfigData(create.data, request)
        val initialize = decoded.instructions[1]
        if (initialize.program != METEORA_DBC_PROGRAM_ID || initialize.keys != listOf(
                config, SolanaBuyAddresses.DBC_POOL_AUTHORITY, wallet, mint, WRAPPED_SOL_MINT,
                pool, baseVault, quoteVault, metadata, SolanaBuyAddresses.METADATA_PROGRAM,
                wallet, SolanaBuyAddresses.TOKEN, SolanaBuyAddresses.TOKEN,
                SolanaBuyAddresses.SYSTEM, SolanaBuyAddresses.DBC_EVENT_AUTHORITY,
                METEORA_DBC_PROGRAM_ID,
            )) protocolFailure()
        val expectedPoolData = byteArrayOf(140.toByte(), 85, 215.toByte(), 176.toByte(), 102, 54, 104, 79) +
            borshString(request.name) + borshString(request.symbol) + borshString(request.metadataUri)
        if (!initialize.data.contentEquals(expectedPoolData)) protocolFailure()
    }

    /** Meteora SDK 1.5.13 ConfigParameters Borsh layout. Curve points remain backend-canonical. */
    private fun validateCreateConfigData(data: ByteArray, request: TokenLaunchRequest) {
        if (data.size < 227 || !data.copyOfRange(0, 8).contentEquals(byteArrayOf(
                201.toByte(), 207.toByte(), 243.toByte(), 114, 75, 111, 47, 189.toByte(),
            ))) protocolFailure()
        if (unsignedLe(data, 8, 8) != BigInteger.valueOf(request.feeBps.toLong() * 100_000L) ||
            !zeroRange(data, 16, 34) || data.u8(34) != 0 || data.u8(35) != 0 ||
            !data.copyOfRange(36, 45).contentEquals(byteArrayOf(0, 1, 1, 0, 6, 0, 0, 0, 100))) protocolFailure()
        val threshold = runCatching { BigDecimal(request.migrationThresholdSol)
            .movePointRight(9).toBigIntegerExact() }.getOrElse { protocolFailure() }
        val supply = BigInteger(request.totalTokenSupply).multiply(BigInteger.valueOf(1_000_000L))
        val sqrtStart = unsignedLe(data, 53, 16)
        if (unsignedLe(data, 45, 8) != threshold || sqrtStart <= BigInteger.ZERO ||
            !zeroRange(data, 69, 109) || data.u8(109) != 6 || data.u8(110) != 1 ||
            unsignedLe(data, 111, 8) != supply || unsignedLe(data, 119, 8) != supply ||
            data.u8(127) != 100 || data.u8(128) != 1 || !zeroRange(data, 129, 133) ||
            unsignedLe(data, 133, 2) != BigInteger.valueOf(request.feeBps.toLong()) ||
            !zeroRange(data, 135, 169) || data.u8(169) != 0 ||
            !zeroRange(data, 170, 191)) protocolFailure()
        val count = unsignedLe(data, 191, 4).toInt()
        if (count !in 1..2 || data.size != 195 + 32 * count) protocolFailure()
        var previous = sqrtStart
        repeat(count) { index ->
            val point = unsignedLe(data, 195 + index * 32, 16)
            val liquidity = unsignedLe(data, 211 + index * 32, 16)
            if (point <= previous || point > MAX_SQRT_PRICE || liquidity <= BigInteger.ZERO) protocolFailure()
            if (index == 1 && point != MAX_SQRT_PRICE) protocolFailure()
            previous = point
        }
    }

    private fun borshString(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = bytes.size
        return byteArrayOf(length.toByte(), (length ushr 8).toByte(),
            (length ushr 16).toByte(), (length ushr 24).toByte()) + bytes
    }

    private fun ByteArray.u8(offset: Int): Int = getOrNull(offset)?.toInt()?.and(0xff) ?: protocolFailure()
    private fun zeroRange(data: ByteArray, start: Int, end: Int): Boolean =
        start >= 0 && end <= data.size && data.copyOfRange(start, end).all { it == 0.toByte() }
    private fun unsignedLe(data: ByteArray, offset: Int, length: Int): BigInteger {
        if (offset < 0 || length !in 1..16 || offset + length > data.size) protocolFailure()
        return BigInteger(1, data.copyOfRange(offset, offset + length).reversedArray())
    }

    private val MAX_SQRT_PRICE = BigInteger("79226673521066979257578248091")

    private class ByteReader(private val source: ByteArray) {
        var offset: Int = 0
            private set

        fun byte(): Int {
            if (offset >= source.size) protocolFailure()
            return source[offset++].toInt() and 0xff
        }

        fun bytes(count: Int): ByteArray {
            if (count < 0 || count > source.size - offset) protocolFailure()
            return source.copyOfRange(offset, offset + count).also { offset += count }
        }

        fun shortVec(): Int {
            var value = 0
            var shift = 0
            var count = 0
            while (true) {
                if (count == 3) protocolFailure()
                val next = byte()
                value = value or ((next and 0x7f) shl shift)
                count++
                if (next and 0x80 == 0) {
                    if (count > 1 && value < (1 shl (7 * (count - 1)))) protocolFailure()
                    return value
                }
                shift += 7
            }
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: TokenLaunchException) {
        throw failure
    } catch (_: Exception) {
        protocolFailure()
    }
}

private fun JSONObject.exactKeys(expected: Set<String>) {
    if (keys().asSequence().toSet() != expected) protocolFailure()
}

private fun JSONObject.version() {
    if (integer("schemaVersion") != 1L) protocolFailure()
}

private fun JSONObject.text(key: String, maximum: Int): String {
    val value = get(key)
    if (value !is String || value.isEmpty() || value.length > maximum || value.any(Char::isISOControl)) protocolFailure()
    return value
}

private fun JSONObject.nullableText(key: String, maximum: Int): String? = when (val value = get(key)) {
    JSONObject.NULL -> null
    is String -> value.takeIf { it.isNotEmpty() && it.length <= maximum && it.none(Char::isISOControl) }
        ?: protocolFailure()
    else -> protocolFailure()
}

private fun JSONObject.integer(key: String): Long {
    val value = get(key)
    if (value !is Int && value !is Long) protocolFailure()
    return (value as Number).toLong().takeIf { it >= 0 } ?: protocolFailure()
}

private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: protocolFailure()

private fun JSONObject.publicKey(key: String): String = text(key, 44).also {
    if (canonicalSolanaPublicKey(it) != it) protocolFailure()
}

private fun JSONObject.lamports(key: String): Long {
    val raw = text(key, 20)
    if (!raw.matches(Regex("(?:0|[1-9][0-9]*)"))) protocolFailure()
    return raw.toLongOrNull()?.takeIf { it >= 0 } ?: protocolFailure()
}

private fun JSONObject.instant(key: String): Instant = try {
    Instant.parse(text(key, 40))
} catch (_: DateTimeParseException) {
    protocolFailure()
}

private fun canonicalBase64(value: String, size: IntRange): ByteArray {
    val decoded = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        protocolFailure()
    }
    if (decoded.size !in size || Base64.getEncoder().encodeToString(decoded) != value) protocolFailure()
    return decoded
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun expired(): Nothing = throw TokenLaunchException(
    TokenLaunchFailureCode.EXPIRED,
    "This launch preparation expired. Prepare it again before signing.",
    retryable = true,
)

internal fun protocolFailure(): Nothing = throw TokenLaunchException(
    TokenLaunchFailureCode.PROTOCOL_ERROR,
    "The launch service returned data that could not be verified. Nothing was submitted.",
    retryable = false,
)

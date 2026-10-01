package com.elevencapital.app.communitybuy

import com.elevencapital.app.tokenlaunch.WRAPPED_SOL_MINT
import com.elevencapital.app.wallet.canonicalSolanaPublicKey
import com.elevencapital.app.wallet.encodeBase58
import com.elevencapital.app.wallet.isCanonicalSolanaSignature
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.json.JSONObject

/** Exact server responses are checked before a Privy signing sheet can open. */
object CommunityBuyProtocol {
    private val preparationKeys = setOf(
        "schemaVersion", "operationId", "network", "walletId", "walletAddress", "mintAddress",
        "amountInLamports", "slippageBps", "programId", "quoteMint", "poolAddress", "configAddress",
        "tokenDecimals", "expectedAmountOut", "minimumAmountOut", "estimatedFeeLamports",
        "maximumWalletDebitLamports", "balanceLamports", "simulationUnitsConsumed", "observedSlot",
        "recentBlockhash", "lastValidBlockHeight", "preparedAt", "expiresAt",
        "transactionSha256", "transactionBase64", "encoding",
    )
    fun preparation(source: JSONObject, request: CommunityBuyRequest, now: Instant = Instant.now()): PreparedCommunityBuy = checked {
        source.exactKeys(preparationKeys)
        source.version()
        if (source.text("operationId", 36) != request.operationId ||
            source.text("network", 32) != BUY_NETWORK ||
            source.text("walletId", 200) != request.walletId ||
            source.text("walletAddress", 44) != request.walletAddress ||
            source.text("mintAddress", 44) != request.mintAddress ||
            source.lamports("amountInLamports") != request.amountInLamports ||
            source.integer("slippageBps") != request.slippageBps ||
            source.text("programId", 44) != BUY_DBC_PROGRAM ||
            source.text("quoteMint", 44) != WRAPPED_SOL_MINT ||
            source.text("encoding", 64) != "base64-legacy-unsigned"
        ) invalid()

        val pool = source.publicKey("poolAddress")
        val config = source.publicKey("configAddress")
        if (setOf(pool, config, request.mintAddress, request.walletAddress, WRAPPED_SOL_MINT).size != 5) invalid()
        val decimals = source.integer("tokenDecimals")
        if (decimals !in 0..18) invalid()
        val expected = source.rawTokenAmount("expectedAmountOut")
        val minimum = source.rawTokenAmount("minimumAmountOut")
        val slippageMinimum = expected.multiply(BigInteger.valueOf((10_000 - request.slippageBps).toLong()))
            .divide(BigInteger.valueOf(10_000))
        if (minimum <= BigInteger.ZERO || expected < minimum || minimum != slippageMinimum) invalid()
        val fee = source.lamports("estimatedFeeLamports")
        val debit = source.lamports("maximumWalletDebitLamports")
        val balance = source.lamports("balanceLamports")
        if (fee !in 1L..10_000_000L || debit < request.amountInLamports ||
            debit - request.amountInLamports < fee ||
            debit > request.amountInLamports + 10_000_000L || balance < debit) invalid()
        if (source.integer("simulationUnitsConsumed") !in 0..1_400_000) invalid()
        val slot = source.lamports("observedSlot")
        val height = source.lamports("lastValidBlockHeight")
        if (slot <= 0 || height <= 0) invalid()
        val blockhash = source.publicKey("recentBlockhash")
        val preparedAt = source.instant("preparedAt")
        val expiresAt = source.instant("expiresAt")
        if (preparedAt.isAfter(now.plusSeconds(30)) || preparedAt.isBefore(now.minusSeconds(120)) ||
            !expiresAt.isAfter(now) || !expiresAt.isAfter(preparedAt) ||
            expiresAt.isAfter(preparedAt.plus(Duration.ofMinutes(2)))) expired()
        val hash = source.text("transactionSha256", 64)
        if (!hash.matches(Regex("[a-f0-9]{64}"))) invalid()
        val transaction = canonicalBase64(source.text("transactionBase64", 4_096))
        if (transaction.size !in 100..1_232 || sha256(transaction) != hash) invalid()
        val decoded = decodeLegacy(transaction)
        if (decoded.signatures.size != 1 || decoded.signatures[0].any { it != 0.toByte() } ||
            decoded.requiredSignatures != 1 || decoded.readonlySigned != 0 ||
            decoded.accountKeys.firstOrNull() != request.walletAddress ||
            decoded.recentBlockhash != blockhash) invalid()
        if (decoded.accountKeys.toSet() != decoded.instructions.flatMap { it.keys + it.program }.toSet()) invalid()
        validateExactSwap(decoded.instructions, request, pool, config, minimum)
        val writable = decoded.accountKeys.take(decoded.accountKeys.size - decoded.readonlyUnsigned).toSet()
        val expectedWritable = setOf(request.walletAddress,
            SolanaBuyAddresses.associatedToken(WRAPPED_SOL_MINT, request.walletAddress),
            SolanaBuyAddresses.associatedToken(request.mintAddress, request.walletAddress),
            pool, SolanaBuyAddresses.dbcVault(pool, request.mintAddress),
            SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT))
        if (writable != expectedWritable) invalid()
        PreparedCommunityBuy(
            request, pool, config, decimals, expected, minimum, fee, debit, slot,
            blockhash, height, preparedAt, expiresAt, hash, transaction, decoded.message,
        )
    }

    fun walletSignedTransaction(encoded: String, prepared: PreparedCommunityBuy): String = checked {
        validatedSignedTransaction(encoded, prepared)
        encoded
    }

    fun signedTransactionId(encoded: String, prepared: PreparedCommunityBuy): String = checked {
        encodeBase58(validatedSignedTransaction(encoded, prepared).signatures.single())
    }

    private fun validatedSignedTransaction(encoded: String, prepared: PreparedCommunityBuy): Legacy {
        val raw = canonicalBase64(encoded)
        if (raw.size !in 100..1_232) invalid()
        val decoded = decodeLegacy(raw)
        if (decoded.signatures.size != 1 || decoded.signatures[0].all { it == 0.toByte() } ||
            !decoded.message.contentEquals(prepared.messageBytes())) invalid()
        return decoded
    }

    fun mutation(source: JSONObject, operationId: String, expected: String) = checked {
        source.exactKeys(setOf("schemaVersion", "operationId", "state", "idempotent"))
        source.version()
        if (source.text("operationId", 36) != operationId || source.text("state", 16) != expected) invalid()
        source.boolean("idempotent")
    }

    fun submission(source: JSONObject, prepared: PreparedCommunityBuy, expectedSignature: String): String = checked {
        source.exactKeys(setOf("schemaVersion", "operationId", "state", "transactionId", "idempotent", "submittedAt"))
        source.version()
        if (source.text("operationId", 36) != prepared.request.operationId ||
            source.text("state", 16) !in setOf("broadcast", "unknown")) invalid()
        val signature = source.text("transactionId", 90)
        if (!isCanonicalSolanaSignature(signature) || signature != expectedSignature) invalid()
        source.boolean("idempotent")
        source.instant("submittedAt")
        signature
    }

    fun status(source: JSONObject, prepared: PreparedCommunityBuy): CommunityBuyStatus = checked {
        source.exactKeys(setOf(
            "schemaVersion", "operationId", "network", "state", "transactionId", "isFinalized",
            "message", "observedAt", "walletAddress", "mintAddress", "poolAddress",
            "amountInLamports", "tokenAmountReceived", "walletTokenBalanceRaw", "explorerUrl",
        ))
        source.version()
        if (source.text("operationId", 36) != prepared.request.operationId ||
            source.text("network", 32) != BUY_NETWORK ||
            source.text("walletAddress", 44) != prepared.request.walletAddress ||
            source.text("mintAddress", 44) != prepared.request.mintAddress ||
            source.text("poolAddress", 44) != prepared.poolAddress ||
            source.lamports("amountInLamports") != prepared.request.amountInLamports ||
            source.text("explorerUrl", 300) !=
            "https://solscan.io/token/${prepared.request.mintAddress}") invalid()
        val state = when (source.text("state", 16)) {
            "committed" -> CommunityBuyStatusState.COMMITTED
            "broadcast" -> CommunityBuyStatusState.BROADCAST
            "confirmed" -> CommunityBuyStatusState.CONFIRMED
            "failed" -> CommunityBuyStatusState.FAILED
            "expired" -> CommunityBuyStatusState.EXPIRED
            "unknown" -> CommunityBuyStatusState.UNKNOWN
            else -> invalid()
        }
        val signature = source.nullableText("transactionId", 90)?.also {
            if (!isCanonicalSolanaSignature(it)) invalid()
        }
        if (state in setOf(CommunityBuyStatusState.BROADCAST, CommunityBuyStatusState.CONFIRMED) &&
            signature == null) invalid()
        val finalized = source.boolean("isFinalized")
        if (finalized && state !in setOf(CommunityBuyStatusState.CONFIRMED,
                CommunityBuyStatusState.FAILED, CommunityBuyStatusState.EXPIRED)) invalid()
        val received = source.nullableRawTokenAmount("tokenAmountReceived")
        val balance = source.nullableRawTokenAmount("walletTokenBalanceRaw")
        if (state == CommunityBuyStatusState.CONFIRMED &&
            (received ?: BigInteger.ZERO) < prepared.minimumAmountOut) invalid()
        CommunityBuyStatus(
            prepared.request.operationId, state, signature, finalized,
            source.nullableText("message", 240), source.instant("observedAt"),
            prepared.request.walletAddress, prepared.request.mintAddress, prepared.poolAddress,
            prepared.request.amountInLamports, received, balance,
        )
    }

    private data class Instruction(val program: String, val keys: List<String>, val data: ByteArray)

    private data class Legacy(
        val signatures: List<ByteArray>, val requiredSignatures: Int,
        val readonlySigned: Int, val readonlyUnsigned: Int, val accountKeys: List<String>,
        val recentBlockhash: String, val instructions: List<Instruction>, val message: ByteArray,
    )

    private fun decodeLegacy(raw: ByteArray): Legacy {
        val reader = ByteReader(raw)
        val signatures = List(reader.shortVec().also { if (it !in 1..8) invalid() }) { reader.bytes(64) }
        val start = reader.offset
        val required = reader.byte()
        if (required and 0x80 != 0 || required != signatures.size) invalid()
        val readonlySigned = reader.byte()
        val readonlyUnsigned = reader.byte()
        val keys = List(reader.shortVec().also {
            if (it !in required..128 || readonlySigned > required || readonlyUnsigned > it - required) invalid()
        }) { encodeBase58(reader.bytes(32)) }
        if (keys.size != keys.toSet().size) invalid()
        val blockhash = encodeBase58(reader.bytes(32))
        val instructions = List(reader.shortVec().also { if (it !in 1..6) invalid() }) {
            val programIndex = reader.byte()
            if (programIndex >= keys.size) invalid()
            val accountCount = reader.shortVec()
            if (accountCount > 128) invalid()
            val accounts = List(accountCount) { keys.getOrNull(reader.byte()) ?: invalid() }
            Instruction(keys[programIndex], accounts, reader.bytes(reader.shortVec()))
        }
        if (reader.offset != raw.size) invalid()
        return Legacy(signatures, required, readonlySigned, readonlyUnsigned,
            keys, blockhash, instructions, raw.copyOfRange(start, raw.size))
    }

    /** Mirrors the SDK's narrow standard-SPL ExactIn route, without trusting a backend-provided payee. */
    private fun validateExactSwap(
        instructions: List<Instruction>, request: CommunityBuyRequest,
        pool: String, config: String, minimumOut: BigInteger,
    ) {
        val wallet = request.walletAddress
        val mint = request.mintAddress
        val wsolAta = SolanaBuyAddresses.associatedToken(WRAPPED_SOL_MINT, wallet)
        val tokenAta = SolanaBuyAddresses.associatedToken(mint, wallet)
        if (pool != SolanaBuyAddresses.dbcPool(mint, config)) invalid()
        val baseVault = SolanaBuyAddresses.dbcVault(pool, mint)
        val quoteVault = SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT)
        var offset = 0
        val optionalAtaMints = listOf(WRAPPED_SOL_MINT to wsolAta, mint to tokenAta)
        for ((ataMint, ataAddress) in optionalAtaMints) {
            val item = instructions.getOrNull(offset) ?: invalid()
            if (item.program == SolanaBuyAddresses.ATA && item.keys.getOrNull(1) == ataAddress) {
                if (item.keys != listOf(wallet, ataAddress, wallet, ataMint,
                        SolanaBuyAddresses.SYSTEM, SolanaBuyAddresses.TOKEN) ||
                    !item.data.contentEquals(byteArrayOf(1))) invalid()
                offset++
            }
        }
        if (instructions.size - offset != 4) invalid()
        val transfer = instructions[offset]
        if (transfer.program != SolanaBuyAddresses.SYSTEM ||
            transfer.keys != listOf(wallet, wsolAta) || transfer.data.size != 12 ||
            !transfer.data.copyOfRange(0, 4).contentEquals(byteArrayOf(2, 0, 0, 0)) ||
            littleEndianU64(transfer.data, 4) != BigInteger.valueOf(request.amountInLamports)) invalid()
        val sync = instructions[offset + 1]
        if (sync.program != SolanaBuyAddresses.TOKEN || sync.keys != listOf(wsolAta) ||
            !sync.data.contentEquals(byteArrayOf(17))) invalid()
        val swap = instructions[offset + 2]
        val expectedKeys = listOf(
            SolanaBuyAddresses.DBC_POOL_AUTHORITY, config, pool, wsolAta, tokenAta,
            baseVault, quoteVault, mint, WRAPPED_SOL_MINT, wallet,
            SolanaBuyAddresses.TOKEN, SolanaBuyAddresses.TOKEN, BUY_DBC_PROGRAM,
            SolanaBuyAddresses.DBC_EVENT_AUTHORITY, BUY_DBC_PROGRAM,
        )
        if (swap.program != BUY_DBC_PROGRAM ||
            swap.keys !in listOf(expectedKeys, expectedKeys + SolanaBuyAddresses.SYSVAR_INSTRUCTIONS) ||
            swap.data.size != 25 ||
            !swap.data.copyOfRange(0, 8).contentEquals(
                byteArrayOf(65, 75, 63, 76, -21, 91, 91, -120)) ||
            littleEndianU64(swap.data, 8) != BigInteger.valueOf(request.amountInLamports) ||
            littleEndianU64(swap.data, 16) != minimumOut || swap.data[24] != 0.toByte()) invalid()
        val close = instructions[offset + 3]
        if (close.program != SolanaBuyAddresses.TOKEN ||
            close.keys != listOf(wsolAta, wallet, wallet) ||
            !close.data.contentEquals(byteArrayOf(9))) invalid()
    }

    private fun littleEndianU64(data: ByteArray, offset: Int): BigInteger {
        if (offset < 0 || offset + 8 > data.size) invalid()
        return BigInteger(1, data.copyOfRange(offset, offset + 8).reversedArray())
    }

    private class ByteReader(private val source: ByteArray) {
        var offset = 0
            private set
        fun byte(): Int = if (offset < source.size) source[offset++].toInt() and 0xff else invalid()
        fun bytes(count: Int): ByteArray = if (count >= 0 && count <= source.size - offset) {
            source.copyOfRange(offset, offset + count).also { offset += count }
        } else invalid()
        fun shortVec(): Int {
            var value = 0
            var shift = 0
            repeat(3) { index ->
                val byte = byte()
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0) {
                    if (index > 0 && value < (1 shl (7 * index))) invalid()
                    return value
                }
                shift += 7
            }
            invalid()
        }
    }

    private fun JSONObject.exactKeys(expected: Set<String>) { if (keys().asSequence().toSet() != expected) invalid() }
    private fun JSONObject.version() { if (get("schemaVersion") != 1) invalid() }
    private fun JSONObject.text(key: String, max: Int): String = (get(key) as? String)
        ?.takeIf { it.isNotEmpty() && it.length <= max && it.none(Char::isISOControl) } ?: invalid()
    private fun JSONObject.nullableText(key: String, max: Int): String? =
        if (isNull(key)) null else text(key, max)
    private fun JSONObject.publicKey(key: String): String = text(key, 44).also {
        if (canonicalSolanaPublicKey(it) != it) invalid()
    }
    private fun JSONObject.lamports(key: String): Long = text(key, 20).let { raw ->
        if (!raw.matches(Regex("(?:0|[1-9][0-9]*)"))) invalid()
        raw.toLongOrNull() ?: invalid()
    }
    private fun JSONObject.rawTokenAmount(key: String): BigInteger = text(key, 20).let { raw ->
        if (!raw.matches(Regex("(?:0|[1-9][0-9]*)"))) invalid()
        raw.toBigIntegerOrNull()?.takeIf { it <= U64_MAX } ?: invalid()
    }
    private fun JSONObject.nullableRawTokenAmount(key: String): BigInteger? =
        if (isNull(key)) null else rawTokenAmount(key)
    private fun JSONObject.integer(key: String): Int = (get(key) as? Number)?.toInt()?.takeIf {
        it.toString() == get(key).toString()
    } ?: invalid()
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: invalid()
    private fun JSONObject.instant(key: String): Instant = runCatching { Instant.parse(text(key, 40)) }.getOrElse { invalid() }
    private fun canonicalBase64(value: String): ByteArray = runCatching {
        Base64.getDecoder().decode(value).takeIf { Base64.getEncoder().encodeToString(it) == value } ?: invalid()
    }.getOrElse { invalid() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (failure: CommunityBuyException) {
        throw failure
    } catch (_: Exception) { invalid() }
    private fun expired(): Nothing = throw CommunityBuyException(CommunityBuyFailure.EXPIRED,
        "This quote expired. Review a fresh quote before signing.")
    private fun invalid(): Nothing = throw CommunityBuyException(CommunityBuyFailure.PROTOCOL_ERROR,
        "The buy service returned an invalid quote. Nothing was signed or submitted.")
}

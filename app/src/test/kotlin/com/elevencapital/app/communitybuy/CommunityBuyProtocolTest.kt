package com.elevencapital.app.communitybuy

import com.elevencapital.app.screens.parseCommunityBuySol
import com.elevencapital.app.tokenlaunch.WRAPPED_SOL_MINT
import com.elevencapital.app.wallet.encodeBase58
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommunityBuyProtocolTest {
    private val now = Instant.parse("2026-10-08T10:00:00Z")
    private val wallet = "AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9"
    private val mint = "9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu"
    private val config = key(4)
    private val pool = SolanaBuyAddresses.dbcPool(mint, config)
    private val blockhash = key(5)
    private val request = CommunityBuyRequest("123e4567-e89b-42d3-a456-426614174000",
        "did:privy:test", "wallet-id", wallet, mint, 100_000_000L, 100)

    @Test fun `PDA derivation matches Solana SDK`() {
        assertEquals("7azMPufyz8EfKAif9WajHfBbFj5ic8C8rLfaTBfKdN1A",
            SolanaBuyAddresses.associatedToken(WRAPPED_SOL_MINT, wallet))
        assertEquals("A2XhaCzf7YeQdxcYQvhHufAHnS6Ae8e9UWXMfawxrZHW",
            SolanaBuyAddresses.associatedToken(mint, wallet))
        assertEquals("AANDtVUg9jp7TQ9DBRSr5YL68keS2zG6pgf2vNccRst2", pool)
        assertEquals("A1Kbzd2MkvpvpaDvviM1XWaDL5U2DrBhYGxtteNd5B8a",
            SolanaBuyAddresses.dbcVault(pool, mint))
        assertEquals("Gw1j46cFYfKX7oj9p4uNefX3Bcy8jmGTBXoqrHUC7F7i",
            SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT))
        assertEquals("5WHKuWMRUkk1GfsgFDoUpQtryyKrmBAHUUqo7gmifcXh",
            SolanaBuyAddresses.mintMetadata(mint))
    }

    @Test fun `unsigned quote and signed message bind exact reviewed swap`() {
        val bytes = transaction()
        val prepared = CommunityBuyProtocol.preparation(response(bytes), request, now)
        assertEquals(pool, prepared.poolAddress)
        assertEquals(BigInteger.valueOf(1_980_000L), prepared.minimumAmountOut)
        prepared.unsignedTransactionBytes()[1] = 7
        assertEquals(0, prepared.unsignedTransactionBytes()[1].toInt())
        val signed = bytes.copyOf().also { it[1] = 7 }
        assertEquals(Base64.getEncoder().encodeToString(signed), CommunityBuyProtocol.walletSignedTransaction(
            Base64.getEncoder().encodeToString(signed), prepared))
        assertEquals(encodeBase58(signed.copyOfRange(1, 65)),
            CommunityBuyProtocol.signedTransactionId(Base64.getEncoder().encodeToString(signed), prepared))
        assertThrows(CommunityBuyException::class.java) { CommunityBuyProtocol.walletSignedTransaction(
            Base64.getEncoder().encodeToString(signed.copyOf().also { it[it.lastIndex] = 99 }), prepared) }
    }

    @Test fun `canonical idempotent ATA creation for a new buyer is accepted`() {
        val prepared = CommunityBuyProtocol.preparation(response(transaction(
            createWsolAta = true, createTokenAta = true)), request, now)
        assertEquals(pool, prepared.poolAddress)
        val tokenOnly = CommunityBuyProtocol.preparation(response(transaction(
            createTokenAta = true)), request, now)
        assertEquals(mint, tokenOnly.request.mintAddress)
    }

    @Test fun `malicious system transfer and mismatched quote fail before signing`() {
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("network", "SOLANA_DEVNET") }, { it.put("walletAddress", key(10)) },
            { it.put("amountInLamports", "200000000") }, { it.put("quoteMint", key(11)) },
            { it.put("minimumAmountOut", "1") },
            { it.put("maximumWalletDebitLamports", "1050000001") },
            { it.put("transactionSha256", "0".repeat(64)) }, { it.put("unexpected", true) },
        )
        changes.forEach { change -> assertThrows(CommunityBuyException::class.java) {
            CommunityBuyProtocol.preparation(response(transaction()).also(change), request, now)
        } }
        listOf(transaction(destination = key(9)), transaction(minOut = BigInteger.ONE),
            transaction(extraTransfer = true))
            .forEach { bytes -> assertThrows(CommunityBuyException::class.java) {
                CommunityBuyProtocol.preparation(response(bytes), request, now)
            } }
    }

    @Test fun `finalized delivery is historical purchase but current holding requires balance`() {
        val prepared = CommunityBuyProtocol.preparation(response(transaction()), request, now)
        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        val delivered = CommunityBuyProtocol.status(status(signature), prepared)
        assertTrue(delivered.isVerifiedPurchase)
        assertTrue(delivered.isCurrentlyHeld)
        val emptied = CommunityBuyProtocol.status(status(signature).put("walletTokenBalanceRaw", "0"), prepared)
        assertTrue(emptied.isVerifiedPurchase)
        assertFalse(emptied.isCurrentlyHeld)
        assertFalse(CommunityBuyProtocol.status(status(signature).put("isFinalized", false), prepared).isVerifiedPurchase)
        assertThrows(CommunityBuyException::class.java) {
            CommunityBuyProtocol.status(status(signature).put("walletAddress", key(11)), prepared)
        }
        assertThrows(CommunityBuyException::class.java) {
            CommunityBuyProtocol.status(status(signature).put("network", "SOLANA_DEVNET"), prepared)
        }
        assertThrows(CommunityBuyException::class.java) {
            CommunityBuyProtocol.status(status(signature)
                .put("explorerUrl", "https://solscan.io/token/$mint?cluster=devnet"), prepared)
        }
        assertThrows(CommunityBuyException::class.java) {
            CommunityBuyProtocol.status(status(signature).put("tokenAmountReceived", "1"), prepared)
        }
    }

    @Test fun `u64 token raw amount and exact SOL entry are supported`() {
        val max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)
        val slippageMinimum = max.multiply(BigInteger.valueOf(9_900)).divide(BigInteger.valueOf(10_000))
        val prepared = CommunityBuyProtocol.preparation(response(transaction(minOut = slippageMinimum))
            .put("expectedAmountOut", max.toString()).put("minimumAmountOut", slippageMinimum.toString()), request, now)
        assertEquals(slippageMinimum, prepared.minimumAmountOut)
        assertEquals(100_000L, parseCommunityBuySol("0.0001"))
        assertEquals(1_000_000_000L, parseCommunityBuySol("1"))
        listOf("SPXTR", "0", "0.000000001", "1.000000001", "01", "1.1.1", "-0.1")
            .forEach { assertEquals(null, parseCommunityBuySol(it)) }
    }

    private fun status(signature: String) = JSONObject()
        .put("schemaVersion", 1).put("operationId", request.operationId)
        .put("network", BUY_NETWORK).put("state", "confirmed")
        .put("transactionId", signature).put("isFinalized", true)
        .put("message", JSONObject.NULL).put("observedAt", "2026-10-08T10:00:30Z")
        .put("walletAddress", wallet).put("mintAddress", mint).put("poolAddress", pool)
        .put("amountInLamports", request.amountInLamports.toString())
        .put("tokenAmountReceived", "2000000").put("walletTokenBalanceRaw", "2000000")
        .put("explorerUrl", "https://solscan.io/token/$mint")

    private fun response(bytes: ByteArray) = JSONObject()
        .put("schemaVersion", 1).put("operationId", request.operationId)
        .put("network", BUY_NETWORK).put("walletId", request.walletId)
        .put("walletAddress", wallet).put("mintAddress", mint)
        .put("amountInLamports", request.amountInLamports.toString())
        .put("slippageBps", request.slippageBps).put("programId", BUY_DBC_PROGRAM)
        .put("quoteMint", WRAPPED_SOL_MINT).put("poolAddress", pool)
        .put("configAddress", config).put("tokenDecimals", 6)
        .put("expectedAmountOut", "2000000").put("minimumAmountOut", "1980000")
        .put("estimatedFeeLamports", "5000").put("maximumWalletDebitLamports", "100005000")
        .put("balanceLamports", "200000000").put("simulationUnitsConsumed", 200_000)
        .put("observedSlot", "12345").put("recentBlockhash", blockhash)
        .put("lastValidBlockHeight", "45678").put("preparedAt", "2026-10-08T10:00:00Z")
        .put("expiresAt", "2026-10-08T10:01:00Z").put("transactionSha256", hash(bytes))
        .put("transactionBase64", Base64.getEncoder().encodeToString(bytes))
        .put("encoding", "base64-legacy-unsigned")

    private fun transaction(destination: String = SolanaBuyAddresses.associatedToken(WRAPPED_SOL_MINT, wallet),
        minOut: BigInteger = BigInteger.valueOf(1_980_000), extraTransfer: Boolean = false,
        createWsolAta: Boolean = false, createTokenAta: Boolean = false): ByteArray {
        val wsol = SolanaBuyAddresses.associatedToken(WRAPPED_SOL_MINT, wallet)
        val base = SolanaBuyAddresses.associatedToken(mint, wallet)
        val swap = listOf(SolanaBuyAddresses.DBC_POOL_AUTHORITY, config, pool, wsol, base,
            SolanaBuyAddresses.dbcVault(pool, mint), SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT),
            mint, WRAPPED_SOL_MINT, wallet, SolanaBuyAddresses.TOKEN, SolanaBuyAddresses.TOKEN,
            BUY_DBC_PROGRAM, SolanaBuyAddresses.DBC_EVENT_AUTHORITY, BUY_DBC_PROGRAM)
        val writable = (listOf(wallet, wsol, base, pool,
            SolanaBuyAddresses.dbcVault(pool, mint), SolanaBuyAddresses.dbcVault(pool, WRAPPED_SOL_MINT)) +
            (if (destination != wsol) listOf(destination) else emptyList()) +
            (if (extraTransfer) listOf(key(9)) else emptyList())).distinct()
        val readonly = (listOf(mint, config, WRAPPED_SOL_MINT, SolanaBuyAddresses.TOKEN,
            SolanaBuyAddresses.SYSTEM, BUY_DBC_PROGRAM, SolanaBuyAddresses.DBC_POOL_AUTHORITY,
            SolanaBuyAddresses.DBC_EVENT_AUTHORITY) +
            (if (createWsolAta || createTokenAta) listOf(SolanaBuyAddresses.ATA) else emptyList()) +
            swap).distinct().filterNot { it in writable }
        val keys = writable + readonly
        fun ix(program: String, accounts: List<String>, data: ByteArray): ByteArray =
            byteArrayOf(keys.indexOf(program).toByte(), accounts.size.toByte()) +
                accounts.map { keys.indexOf(it).toByte() }.toByteArray() + byteArrayOf(data.size.toByte()) + data
        val transferData = byteArrayOf(2, 0, 0, 0) + le64(100_000_000)
        val ataInstructions = buildList {
            if (createWsolAta) add(ix(SolanaBuyAddresses.ATA,
                listOf(wallet, wsol, wallet, WRAPPED_SOL_MINT, SolanaBuyAddresses.SYSTEM,
                    SolanaBuyAddresses.TOKEN), byteArrayOf(1)))
            if (createTokenAta) add(ix(SolanaBuyAddresses.ATA,
                listOf(wallet, base, wallet, mint, SolanaBuyAddresses.SYSTEM,
                    SolanaBuyAddresses.TOKEN), byteArrayOf(1)))
        }
        val instructions = ataInstructions + listOf(
            ix(SolanaBuyAddresses.SYSTEM, listOf(wallet, destination), transferData),
            ix(SolanaBuyAddresses.TOKEN, listOf(wsol), byteArrayOf(17)),
            ix(BUY_DBC_PROGRAM, swap, byteArrayOf(65, 75, 63, 76, -21, 91, 91, -120) +
                le64(100_000_000) + le64(minOut) + byteArrayOf(0)),
            ix(SolanaBuyAddresses.TOKEN, listOf(wsol, wallet, wallet), byteArrayOf(9)),
        ) + if (extraTransfer) listOf(ix(SolanaBuyAddresses.SYSTEM,
            listOf(wallet, key(9)), transferData)) else emptyList()
        val message = byteArrayOf(1, 0, readonly.size.toByte(), keys.size.toByte()) +
            keys.fold(ByteArray(0)) { acc, key -> acc + decodeKey(key) } +
            decodeKey(blockhash) + byteArrayOf(instructions.size.toByte()) +
            instructions.fold(ByteArray(0)) { acc, ix -> acc + ix }
        return byteArrayOf(1) + ByteArray(64) + message
    }

    private fun le64(value: Long) = ByteArray(8) { (value ushr (it * 8)).toByte() }
    private fun le64(value: BigInteger) = ByteArray(8) { index ->
        value.shiftRight(index * 8).and(BigInteger.valueOf(255)).toByte()
    }
    private fun key(seed: Int) = encodeBase58(ByteArray(32) { (seed + it).toByte() })
    private fun decodeKey(value: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var integer = BigInteger.ZERO
        value.forEach { integer = integer * BigInteger.valueOf(58) +
            BigInteger.valueOf(alphabet.indexOf(it).toLong()) }
        val magnitude = if (integer == BigInteger.ZERO) byteArrayOf() else integer.toByteArray()
            .let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val leading = value.takeWhile { it == '1' }.length
        return ByteArray(leading) + ByteArray(32 - leading - magnitude.size) + magnitude
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

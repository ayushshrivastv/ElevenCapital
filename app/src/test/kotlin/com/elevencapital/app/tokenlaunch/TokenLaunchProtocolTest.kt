package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.wallet.encodeBase58
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TokenLaunchProtocolTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    // Deterministic offline transaction built by Meteora SDK 1.5.13.
    private val wallet = "AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9"
    private val mint = "9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu"
    private val config = "GyGKxMyg1p9SsHfm15MkNUu1u9TN2JtTspcdmrtGUdse"
    private val pool = "ETudp4Y7x7QnZRyPvadnR24fQrz1oJnDpMeJcBnoW4ML"
    private val blockhash = "EdmxWPmx2WH6WgFfTdu9xfkYf3k1g5wD1zccTVySEEh1"
    private val request = TokenLaunchRequest.create(
        userId = "did:privy:test-user",
        walletId = "privy-wallet-id",
        walletAddress = wallet,
        name = "Launch Test",
        symbol = "TEST",
        metadataUri = "https://metadata.example/token.json",
        totalTokenSupply = "1000000000",
        feeBps = 100,
        migrationThresholdSol = "1",
        percentageSupplyOnMigration = 20,
        operationId = "123e4567-e89b-42d3-a456-426614174000",
    )

    @Test fun `preparation is bound to request constants economics hash and legacy signer layout`() {
        val transaction = partialTransaction()
        val prepared = TokenLaunchProtocol.preparation(response(transaction), request, now)
        assertEquals(mint, prepared.mintAddress)
        assertEquals(config, prepared.configAddress)
        assertEquals(pool, prepared.poolAddress)
        assertEquals(50_000_000L, prepared.balanceLamports)
        assertEquals(50_000_000L, prepared.maximumWalletDebitLamports)
        assertEquals(sha256(transaction), prepared.transactionSha256)
        val copy = prepared.partiallySignedTransactionBytes()
        copy[1] = 99
        assertEquals(0, prepared.partiallySignedTransactionBytes()[1].toInt())
    }

    @Test fun `preparation fails closed for changed review or unsafe debit`() {
        val transaction = partialTransaction()
        val changes = listOf<(JSONObject) -> Unit>(
            { it.put("network", "SOLANA_DEVNET") },
            { it.put("walletId", "another-wallet") },
            { it.put("symbol", "OTHER") },
            { it.put("programId", key(9)) },
            { it.put("quoteMint", key(10)) },
            { it.put("tokenDecimals", 9) },
            { it.put("migrationTarget", "METEORA_DAMM_V1") },
            { it.put("tokenAuthority", "MUTABLE") },
            { it.put("dynamicFeeEnabled", true) },
            { it.put("creatorPermanentLockedLiquidityPercentage", 99) },
            { it.put("balanceLamports", "49999999") },
            { it.put("maximumWalletDebitLamports", "50000001") },
            { it.put("transactionSha256", "0".repeat(64)) },
            { it.put("unexpected", true) },
        )
        changes.forEach { change ->
            assertThrows(TokenLaunchException::class.java) {
                TokenLaunchProtocol.preparation(response(transaction).also(change), request, now)
            }
        }
    }

    @Test fun `prepared transaction requires exact signers accounts and DBC instructions`() {
        val transactions = listOf(
            partialTransaction().also { it[1] = 1 }, // Wallet slot must be empty.
            partialTransaction().also { it.fill(0, 1 + 64, 1 + 128) }, // Mint signature must be present.
            partialTransaction().also { it.fill(0, 1 + 128, 1 + 192) }, // Config signature must be present.
            partialTransaction().also { it[it.lastIndex] = (it.last() + 1).toByte() },
        )
        transactions.forEach { transaction ->
            assertThrows(TokenLaunchException::class.java) {
                TokenLaunchProtocol.preparation(response(transaction), request, now)
            }
        }
    }

    @Test fun `wallet signing accepts OnDevice and TEE forms only for exact message and partials`() {
        val transaction = partialTransaction()
        val prepared = TokenLaunchProtocol.preparation(response(transaction), request, now)
        val message = transaction.copyOfRange(1 + 3 * 64, transaction.size)
        val walletSignature = ByteArray(64) { (it + 10).toByte() }
        val oneSignature = byteArrayOf(1) + walletSignature + message
        val full = transaction.copyOf().also {
            walletSignature.copyInto(it, destinationOffset = 1)
        }
        assertEquals(
            Base64.getEncoder().encodeToString(oneSignature),
            TokenLaunchProtocol.walletSignedTransaction(Base64.getEncoder().encodeToString(oneSignature), prepared),
        )
        assertEquals(
            Base64.getEncoder().encodeToString(full),
            TokenLaunchProtocol.walletSignedTransaction(Base64.getEncoder().encodeToString(full), prepared),
        )

        val changedMessage = oneSignature.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val changedPartial = full.copyOf().also { it[1 + 64] = (it[1 + 64] + 1).toByte() }
        val zeroWallet = oneSignature.copyOf().also { it.fill(0, 1, 65) }
        listOf(changedMessage, changedPartial, zeroWallet).forEach { invalid ->
            assertThrows(TokenLaunchException::class.java) {
                TokenLaunchProtocol.walletSignedTransaction(Base64.getEncoder().encodeToString(invalid), prepared)
            }
        }
    }

    @Test fun `status binds addresses explorer and transaction signature`() {
        val prepared = TokenLaunchProtocol.preparation(response(partialTransaction()), request, now)
        val signature = encodeBase58(ByteArray(64) { (it + 1).toByte() })
        val status = JSONObject()
            .put("schemaVersion", 1)
            .put("operationId", request.operationId)
            .put("state", "confirmed")
            .put("transactionId", signature)
            .put("isFinalized", false)
            .put("message", JSONObject.NULL)
            .put("observedAt", "2026-10-07T10:00:30Z")
            .put("mintAddress", mint)
            .put("configAddress", config)
            .put("poolAddress", pool)
            .put("explorerUrl", mainnetTokenExplorerUrl(mint))
        assertEquals(TokenLaunchStatusState.CONFIRMED, TokenLaunchProtocol.status(status, prepared).state)
        assertEquals(false, TokenLaunchProtocol.status(status, prepared).terminal)
        val expired = JSONObject(status.toString())
            .put("state", "expired")
            .put("transactionId", JSONObject.NULL)
            .put("isFinalized", true)
        assertEquals(TokenLaunchStatusState.EXPIRED, TokenLaunchProtocol.status(expired, prepared).state)
        assertThrows(TokenLaunchException::class.java) {
            TokenLaunchProtocol.status(JSONObject(status.toString()).put("explorerUrl", "https://example.com"), prepared)
        }
        assertThrows(TokenLaunchException::class.java) {
            TokenLaunchProtocol.status(JSONObject(status.toString()).put("mintAddress", key(12)), prepared)
        }
    }

    private fun response(transaction: ByteArray): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("operationId", request.operationId)
        .put("network", TOKEN_LAUNCH_NETWORK)
        .put("walletId", request.walletId)
        .put("walletAddress", request.walletAddress)
        .put("name", request.name)
        .put("symbol", request.symbol)
        .put("metadataUri", request.metadataUri)
        .put("programId", METEORA_DBC_PROGRAM_ID)
        .put("quoteMint", WRAPPED_SOL_MINT)
        .put("mintAddress", mint)
        .put("configAddress", config)
        .put("poolAddress", pool)
        .put("totalTokenSupply", request.totalTokenSupply)
        .put("tokenDecimals", TOKEN_LAUNCH_DECIMALS)
        .put("feeBps", request.feeBps)
        .put("migrationThresholdSol", request.migrationThresholdSol)
        .put("percentageSupplyOnMigration", request.percentageSupplyOnMigration)
        .put("migrationTarget", TOKEN_LAUNCH_MIGRATION_TARGET)
        .put("tokenAuthority", TOKEN_LAUNCH_AUTHORITY)
        .put("dynamicFeeEnabled", false)
        .put("creatorPermanentLockedLiquidityPercentage", 100)
        .put("estimatedFeeLamports", "5000")
        .put("maximumWalletDebitLamports", "50000000")
        .put("balanceLamports", "50000000")
        .put("simulationUnitsConsumed", 250000)
        .put("observedSlot", "12345")
        .put("recentBlockhash", blockhash)
        .put("lastValidBlockHeight", "45678")
        .put("preparedAt", "2026-10-07T10:00:00Z")
        .put("expiresAt", "2026-10-07T10:00:45Z")
        .put("transactionSha256", sha256(transaction))
        .put("transactionBase64", Base64.getEncoder().encodeToString(transaction))
        .put("encoding", "base64-legacy-partially-signed")

    private fun partialTransaction(): ByteArray = Base64.getDecoder().decode(SDK_TRANSACTION_BASE64)

    private companion object {
        // Offline SDK fixture; never submitted to a network.
        const val SDK_TRANSACTION_BASE64 = "AwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADyv51YeYzYwGqqryk6cgXlixT1lmvfNL5a6dIIimgi263FI+Xp6xMgPUPulK5R31y+Mu94ppaTMrVZiE5aOmQFvXFn1l3nKshY8NhJK8jbF9NthJqVmpcPb7a4r/i1yWYzw7Dy8Odydq4PlU8qEprB89KVitBLQNQqKso/Pi2fAAMABw6KiOPddAnxlf1S2y08ul1yymcJvx2UEhvzdIgBtA9vXIE5dw6ofRdfVqNUZsNMfszLjYqRtO43ol32D1uPybOU7UkoxijRwsbq6QM4kFmVYSlZJzpcY/k2NsFGFKyHN9E82jihqR9mpU83ql0LG2FRROUOi3Ubk+ZHTfx0ncfrz0LvDQ0xUOxkQgyuBh9OYiIGfa2Ve3pa8TXm7s8JSCige85Ur6ob4TG+BtOKogAEH+V9cdmJEmAyBzyBC1CAqp7IDIuYyVgJ3GJYptC0iJK9ToOMdJanjAUrdfkQn/NxjwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAbNbMgMewCyTCECA4Qz9Iqg/KjufoGQM8D+H7pyjf9QwJYAylJPext9bMscOXOqAzDRkD2mAcybXe48ZitMrRSdpjaB9yhrzIBnGeLCtQogFXJDv8lmixFSC8U4Q+3NsIC3BlsePRfEU4nVJ/awTDzVi4bHMaoP21SbbRvAP4KUYGm4hX/quBhPtof2NGGMA12sQ53BrrO1WYoPAAAAAAAQbd9uHXZaGT2cvhRs7reawctIXtX1s3kTqM9YV+/wCpypOsFwUYcHHWe4PH/w7+gQjo7EUwV113JoeTM9vavnwCCQgCAAAMAAcICYMCyc/zcktvL72AlpgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEBAAYAAABkAMqaOwAAAADJ1XAFwKIkAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAYBAIDGpH6NAwAAgMakfo0DAGQBAAAAAGQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAbDLC1f6KkgAAAAAAAAAAADtFpq4HSa5gnklY1YoAAACbV2lOqRpchLHE/v8AAAAAPdSnhGj53hWb1wIAAAAAAAkQAgoAAQwGBQMECwANDQcICUaMVdewZjZoTwsAAABMYXVuY2ggVGVzdAQAAABURVNUIwAAAGh0dHBzOi8vbWV0YWRhdGEuZXhhbXBsZS90b2tlbi5qc29u"
    }

    private fun key(seed: Int): String = encodeBase58(ByteArray(32) { (seed + it).toByte() })

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

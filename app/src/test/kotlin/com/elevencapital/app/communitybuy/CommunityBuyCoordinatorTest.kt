package com.elevencapital.app.communitybuy

import com.elevencapital.app.wallet.encodeBase58
import java.math.BigInteger
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityBuyCoordinatorTest {
    private val now = Instant.parse("2026-10-08T10:00:00Z")
    private val wallet = encodeBase58(ByteArray(32) { (it + 1).toByte() })
    private val mint = encodeBase58(ByteArray(32) { (it + 2).toByte() })
    private val pool = encodeBase58(ByteArray(32) { (it + 3).toByte() })
    private val config = encodeBase58(ByteArray(32) { (it + 4).toByte() })
    private val blockhash = encodeBase58(ByteArray(32) { (it + 5).toByte() })
    private val signature = encodeBase58(ByteArray(64) { 1 })
    private val request = CommunityBuyRequest("123e4567-e89b-42d3-a456-426614174000",
        "did:privy:test", "wallet-id", wallet, mint, 100_000_000, 100)
    private val message = byteArrayOf(1, 0, 0, 1) + ByteArray(32) { (it + 1).toByte() } +
        ByteArray(32) { (it + 5).toByte() } + byteArrayOf(1, 0, 0, 0)
    private val prepared = PreparedCommunityBuy(
        request, pool, config, 6, BigInteger.valueOf(2_000_000), BigInteger.valueOf(1_000_000), 5_000, 100_005_000,
        12_345, blockhash, 45_678, now, now.plusSeconds(60), "0".repeat(64),
        byteArrayOf(1) + ByteArray(64) + message, message,
    )

    @Test fun `ambiguous submit is not retried and finalized delivery completes purchase`() = runBlocking {
        val backend = FakeBackend().apply { submitThrows = true }
        val signed = Base64.getEncoder().encodeToString(byteArrayOf(1) + ByteArray(64) { 1 } + message)
        val coordinator = CommunityBuyCoordinator(backend, CommunityBuySigner { signed }, { now }, { }, testOnly = Unit)
        val status = coordinator.execute(prepared)
        assertTrue(status.isVerifiedPurchase)
        assertEquals(1, backend.submitCount)
        assertEquals(1, backend.statusCount)
        assertEquals(0, backend.releaseCount)
    }

    @Test fun `finalized historical delivery completes even if current balance is zero`() = runBlocking {
        val backend = FakeBackend().apply { tokenBalance = BigInteger.ZERO }
        val signed = Base64.getEncoder().encodeToString(byteArrayOf(1) + ByteArray(64) { 1 } + message)
        val coordinator = CommunityBuyCoordinator(backend, CommunityBuySigner { signed }, { now }, { }, testOnly = Unit)
        val status = coordinator.execute(prepared)
        assertTrue(status.isVerifiedPurchase)
        assertTrue(!status.isCurrentlyHeld)
        assertEquals(1, backend.submitCount)
    }

    @Test fun `signer rejection releases and never submits`() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CommunityBuyCoordinator(backend, CommunityBuySigner {
            throw CommunityBuyException(CommunityBuyFailure.SIGNING_FAILED, "Declined")
        }, { now }, { }, testOnly = Unit)
        assertThrows(CommunityBuyException::class.java) { runBlocking { coordinator.execute(prepared) } }
        assertEquals(1, backend.commitCount)
        assertEquals(1, backend.releaseCount)
        assertEquals(CommunityBuyReleaseReason.SIGNED_NOT_SUBMITTED, backend.lastReleaseReason)
        assertEquals(0, backend.submitCount)
    }

    @Test fun `backend cannot substitute another transaction signature`() = runBlocking {
        val backend = FakeBackend().apply {
            reportedSignature = encodeBase58(ByteArray(64) { 2 })
        }
        val signed = Base64.getEncoder().encodeToString(byteArrayOf(1) + ByteArray(64) { 1 } + message)
        val coordinator = CommunityBuyCoordinator(backend, CommunityBuySigner { signed }, { now }, { }, testOnly = Unit)
        val failure = assertThrows(CommunityBuyException::class.java) {
            runBlocking { coordinator.execute(prepared) }
        }
        assertEquals(CommunityBuyFailure.PROTOCOL_ERROR, failure.code)
        assertEquals(1, backend.submitCount)
        assertEquals(0, backend.releaseCount)
    }

    @Test fun `cluster verification rejection releases as before signing`() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CommunityBuyCoordinator(backend, CommunityBuySigner { error("must not sign") },
            { now }, { }, clusterVerifier = CommunityBuyClusterVerifier { error("wrong cluster") }, testOnly = Unit)
        assertThrows(IllegalStateException::class.java) { runBlocking { coordinator.execute(prepared) } }
        assertEquals(CommunityBuyReleaseReason.BEFORE_SIGNING, backend.lastReleaseReason)
        assertEquals(0, backend.submitCount)
    }

    private inner class FakeBackend : CommunityBuyBackend {
        var submitThrows = false
        var reportedSignature = signature
        var submitCount = 0
        var statusCount = 0
        var releaseCount = 0
        var lastReleaseReason: CommunityBuyReleaseReason? = null
        var commitCount = 0
        var tokenBalance = BigInteger.valueOf(1_500_000)
        override suspend fun prepare(request: CommunityBuyRequest) = prepared
        override suspend fun commit(prepared: PreparedCommunityBuy) { commitCount++ }
        override suspend fun release(prepared: PreparedCommunityBuy, reason: CommunityBuyReleaseReason) {
            releaseCount++
            lastReleaseReason = reason
        }
        override suspend fun submit(prepared: PreparedCommunityBuy, signedBase64: String): String {
            submitCount++
            if (submitThrows) error("ambiguous timeout")
            return reportedSignature
        }
        override suspend fun status(prepared: PreparedCommunityBuy): CommunityBuyStatus {
            statusCount++
            return CommunityBuyStatus(request.operationId, CommunityBuyStatusState.CONFIRMED,
                signature, true, null, now.plusSeconds(5), wallet, mint, pool,
                request.amountInLamports, BigInteger.valueOf(1_500_000), tokenBalance)
        }
    }
}

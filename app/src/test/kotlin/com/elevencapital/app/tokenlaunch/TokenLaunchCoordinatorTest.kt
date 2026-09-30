package com.elevencapital.app.tokenlaunch

import com.elevencapital.app.wallet.encodeBase58
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLaunchCoordinatorTest {
    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private val prepared = prepared()

    @Test fun `successful execution commits signs submits once polls and saves public receipt`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus())
        val saved = mutableListOf<ConfirmedTokenLaunchReceipt>()
        val progress = mutableListOf<TokenLaunchProgress>()
        val coordinator = coordinator(backend, TokenLaunchSigner { walletSigned() }) { saved += it; true }

        val receipt = coordinator.execute(prepared) { progress += it }

        assertEquals(listOf(
            TokenLaunchProgress.COMMITTING,
            TokenLaunchProgress.SIGNING,
            TokenLaunchProgress.SUBMITTING,
            TokenLaunchProgress.CONFIRMING,
        ), progress)
        assertEquals(1, backend.commits.get())
        assertEquals(1, backend.submits.get())
        assertEquals(1, backend.statuses.get())
        assertEquals(0, backend.releases.get())
        assertEquals(prepared.mintAddress, receipt.mintAddress)
        assertEquals(listOf(receipt), saved)
    }

    @Test fun `ambiguous submit is never retried and confirmation is recovered by status`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus()).apply { failSubmit = true }
        val coordinator = coordinator(backend, TokenLaunchSigner { walletSigned() }) { true }

        val receipt = coordinator.execute(prepared)

        assertEquals(prepared.mintAddress, receipt.mintAddress)
        assertEquals(1, backend.submits.get())
        assertEquals(1, backend.statuses.get())
        assertEquals(0, backend.releases.get())
    }

    @Test fun `backend cannot substitute another signature for the signed launch`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus(signatureSeed = 2)).apply { failSubmit = true }
        val coordinator = coordinator(backend, TokenLaunchSigner { walletSigned() }) { true }

        val failure = runCatching { coordinator.execute(prepared) }.exceptionOrNull()

        assertTrue(failure is TokenLaunchException)
        assertEquals(TokenLaunchFailureCode.PROTOCOL_ERROR, (failure as TokenLaunchException).code)
        assertEquals(1, backend.submits.get())
        assertEquals(0, backend.releases.get())
    }

    @Test fun `cancellation while nonbroadcasting Privy signing returns releases and never submits`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val returnSignature = CompletableDeferred<Unit>()
        val backend = FakeBackend(prepared, confirmedStatus())
        val coordinator = coordinator(backend, TokenLaunchSigner {
            entered.complete(Unit)
            withContext(NonCancellable) { returnSignature.await() }
            walletSigned()
        }) { true }

        val execution = async { coordinator.execute(prepared) }
        entered.await()
        execution.cancel()
        returnSignature.complete(Unit)
        execution.join()

        assertTrue(execution.isCancelled)
        assertEquals(1, backend.commits.get())
        assertEquals(1, backend.releases.get())
        assertEquals(listOf(TokenLaunchReleaseReason.SIGNED_TRANSACTION_NOT_SUBMITTED), backend.releaseReasons)
        assertEquals(0, backend.submits.get())
    }

    @Test fun `provider signing failure is pre-submit and releases even after provider invocation`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus())
        val coordinator = coordinator(backend, TokenLaunchSigner {
            throw TokenLaunchException(
                TokenLaunchFailureCode.SIGNING_FAILED,
                "The wallet could not sign this launch. Nothing was submitted.",
                retryable = true,
                providerInvoked = true,
            )
        }) { true }

        val failure = runCatching { coordinator.execute(prepared) }.exceptionOrNull()

        assertTrue(failure is TokenLaunchException)
        assertEquals(1, backend.releases.get())
        assertEquals(listOf(TokenLaunchReleaseReason.SIGNED_TRANSACTION_NOT_SUBMITTED), backend.releaseReasons)
        assertEquals(0, backend.submits.get())
    }

    @Test fun `caller cancellation after submit begins keeps reconciliation alive without resubmit`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus()).apply {
            submitStarted = CompletableDeferred()
            submitGate = CompletableDeferred()
        }
        val saved = mutableListOf<ConfirmedTokenLaunchReceipt>()
        val coordinator = coordinator(backend, TokenLaunchSigner { walletSigned() }) { saved += it; true }

        val execution = async { coordinator.execute(prepared) }
        requireNotNull(backend.submitStarted).await()
        execution.cancel()
        requireNotNull(backend.submitGate).complete(Unit)
        execution.join()
        withTimeout(2_000) {
            while (saved.isEmpty()) delay(10)
        }

        assertEquals(1, backend.submits.get())
        assertEquals(1, backend.statuses.get())
        assertEquals(0, backend.releases.get())
        assertEquals(1, saved.size)
    }

    @Test fun `cancelled preparation is completed only to release its exact backend identity`() = runBlocking {
        val backend = FakeBackend(prepared, confirmedStatus()).apply {
            prepareStarted = CompletableDeferred()
            prepareGate = CompletableDeferred()
        }
        val coordinator = coordinator(backend, TokenLaunchSigner { walletSigned() }) { true }

        val preparation = async { coordinator.prepare(prepared.request) }
        requireNotNull(backend.prepareStarted).await()
        preparation.cancel()
        requireNotNull(backend.prepareGate).complete(Unit)
        preparation.join()
        withTimeout(2_000) {
            while (backend.releases.get() == 0) delay(10)
        }

        assertEquals(1, backend.releases.get())
        assertEquals(listOf(TokenLaunchReleaseReason.PROVIDER_NOT_INVOKED), backend.releaseReasons)
        assertEquals(0, backend.commits.get())
        assertEquals(0, backend.submits.get())
    }

    private fun coordinator(
        backend: TokenLaunchBackend,
        signer: TokenLaunchSigner,
        sink: TokenLaunchReceiptSink,
    ) = TokenLaunchCoordinator(
        backend = backend,
        signer = signer,
        receipts = sink,
        now = { now },
        pollingDelay = {},
    )

    private fun walletSigned(): String = Base64.getEncoder().encodeToString(
        byteArrayOf(1) + ByteArray(64) { (it + 1).toByte() } + prepared.exactMessageBytes(),
    )

    private fun prepared(): PreparedTokenLaunch {
        val wallet = key(1)
        val mint = key(2)
        val config = key(3)
        val pool = key(4)
        val request = TokenLaunchRequest.create(
            userId = "did:privy:test-user",
            walletId = "wallet-id",
            walletAddress = wallet,
            name = "Race",
            symbol = "RACE",
            metadataUri = "https://metadata.example/race.json",
            totalTokenSupply = "1000000000",
            feeBps = 100,
            migrationThresholdSol = "10",
            percentageSupplyOnMigration = 20,
            operationId = "123e4567-e89b-42d3-a456-426614174000",
        )
        return PreparedTokenLaunch(
            request = request,
            mintAddress = mint,
            configAddress = config,
            poolAddress = pool,
            estimatedFeeLamports = 5_000,
            maximumWalletDebitLamports = 50_000_000,
            balanceLamports = 50_000_000,
            simulationUnitsConsumed = 250_000,
            observedSlot = 12_345,
            recentBlockhash = key(5),
            lastValidBlockHeight = 45_678,
            preparedAt = now,
            expiresAt = now.plusSeconds(45),
            transactionSha256 = "a".repeat(64),
            transactionBytes = ByteArray(300),
            messageBytes = ByteArray(100) { (it + 1).toByte() },
        )
    }

    private fun confirmedStatus(signatureSeed: Int = 1): TokenLaunchStatus {
        val signature = encodeBase58(ByteArray(64) { (it + signatureSeed).toByte() })
        return TokenLaunchStatus(
            operationId = prepared.request.operationId,
            state = TokenLaunchStatusState.CONFIRMED,
            transactionId = signature,
            isFinalized = true,
            message = null,
            observedAt = now.plusSeconds(20),
            mintAddress = prepared.mintAddress,
            configAddress = prepared.configAddress,
            poolAddress = prepared.poolAddress,
            explorerUrl = mainnetTokenExplorerUrl(prepared.mintAddress),
        )
    }

    private class FakeBackend(
        private val prepared: PreparedTokenLaunch,
        private val result: TokenLaunchStatus,
    ) : TokenLaunchBackend {
        val commits = AtomicInteger()
        val releases = AtomicInteger()
        val releaseReasons = mutableListOf<TokenLaunchReleaseReason>()
        val submits = AtomicInteger()
        val statuses = AtomicInteger()
        var failSubmit = false
        var prepareStarted: CompletableDeferred<Unit>? = null
        var prepareGate: CompletableDeferred<Unit>? = null
        var submitStarted: CompletableDeferred<Unit>? = null
        var submitGate: CompletableDeferred<Unit>? = null

        override suspend fun prepare(request: TokenLaunchRequest): PreparedTokenLaunch {
            prepareStarted?.complete(Unit)
            prepareGate?.await()
            return prepared
        }
        override suspend fun commit(prepared: PreparedTokenLaunch) { commits.incrementAndGet() }
        override suspend fun release(prepared: PreparedTokenLaunch, reason: TokenLaunchReleaseReason) {
            releaseReasons += reason
            releases.incrementAndGet()
        }
        override suspend fun submit(
            prepared: PreparedTokenLaunch,
            walletSignedTransactionBase64: String,
        ): TokenLaunchSubmission {
            submits.incrementAndGet()
            submitStarted?.complete(Unit)
            submitGate?.await()
            if (failSubmit) throw TokenLaunchException(
                TokenLaunchFailureCode.NETWORK_UNAVAILABLE,
                "The launch result is uncertain. Check its status and do not submit again.",
                retryable = false,
                submissionMayHaveReachedServer = true,
            )
            return TokenLaunchSubmission(
                operationId = prepared.request.operationId,
                state = TokenLaunchSubmissionState.BROADCAST,
                transactionId = requireNotNull(result.transactionId),
                idempotent = false,
                submittedAt = result.observedAt.minusSeconds(5),
            )
        }
        override suspend fun status(prepared: PreparedTokenLaunch): TokenLaunchStatus {
            statuses.incrementAndGet()
            return result
        }
    }

    private fun key(seed: Int): String = encodeBase58(ByteArray(32) { (seed + it).toByte() })
}

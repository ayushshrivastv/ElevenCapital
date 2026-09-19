package com.elevencapital.app.wallet

import java.math.BigInteger
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferJournalTest {
    @Test
    fun activeSubmissionGuardExcludesAnInFlightOperationFromRecovery() {
        val active = ActiveTransferOperations()
        val operationId = "5a40fc8d-10d4-4da9-b44f-b4e28ef917cc"

        assertTrue(active.begin(operationId))
        assertFalse(active.begin(operationId))
        assertTrue(active.contains(operationId))
        active.end(operationId)
        assertFalse(active.contains(operationId))
    }

    @Test
    fun committingIsDurableRecoverableAndBlocksOnlyTheSameUserWalletAfterRestore() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        val journal = journal(storage, 1_000)

        journal.beginCommitting(review)

        assertEquals(1, storage.writeCount)
        val restored = journal(storage, 2_000)
        assertTrue(restored.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertFalse(restored.hasHashlessUnresolved("privy:user-a", "wallet-b"))
        assertFalse(restored.hasHashlessUnresolved("privy:user-b", "wallet-a"))
        assertEquals(TransferJournalState.COMMITTING,
            restored.recordsForVerifiedUser("privy:user-a").single().state)
        assertEquals(review.operationId,
            restored.recordsNeedingServerRelease("privy:user-a").single().review.operationId)
    }

    @Test
    fun providerBoundaryStopsAutomaticReleaseAfterRestore() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        journal(storage, 1_000).apply {
            beginCommitting(review)
            beginSubmitting(review)
        }

        val restored = journal(storage, 2_000)
        assertEquals(TransferJournalState.SUBMITTING,
            restored.recordsForVerifiedUser("privy:user-a").single().state)
        assertTrue(restored.recordsNeedingServerRelease("privy:user-a").isEmpty())
    }

    @Test
    fun definitelyNotBroadcastUnblocksButAmbiguousRemainsBlocked() {
        val definitelyStorage = MemoryJournalStorage()
        val definitelyReview = ethereumReview("privy:user-a", "wallet-a")
        journal(definitelyStorage, 1_000).apply {
            beginCommitting(definitelyReview)
            beginSubmitting(definitelyReview)
            markProviderFailure(definitelyReview, definitelyNotBroadcast = true)
        }
        val releasePending = journal(definitelyStorage, 2_000)
        assertTrue(releasePending.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertEquals(TransferJournalState.RELEASING,
            releasePending.recordsNeedingServerRelease("privy:user-a").single().state)
        releasePending.recordDefinitelyNotBroadcast(definitelyReview)
        val definitelyRestored = journal(definitelyStorage, 3_000)
        assertFalse(definitelyRestored.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertEquals(TransferJournalState.DEFINITELY_NOT_BROADCAST,
            definitelyRestored.recordsForVerifiedUser("privy:user-a").single().state)

        val ambiguousStorage = MemoryJournalStorage()
        val ambiguousReview = ethereumReview("privy:user-a", "wallet-a")
        journal(ambiguousStorage, 1_000).apply {
            beginCommitting(ambiguousReview)
            beginSubmitting(ambiguousReview)
            markProviderFailure(ambiguousReview, definitelyNotBroadcast = false)
        }
        val ambiguousRestored = journal(ambiguousStorage, 2_000)
        assertTrue(ambiguousRestored.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertEquals(TransferJournalState.AMBIGUOUS,
            ambiguousRestored.recordsForVerifiedUser("privy:user-a").single().state)
    }

    @Test
    fun serverGateFailureCanBeRecordedBeforeLocalSubmittingBegins() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        val journal = journal(storage, 1_000)

        journal.recordDefinitelyNotBroadcast(review)

        assertFalse(journal.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertEquals(TransferJournalState.DEFINITELY_NOT_BROADCAST,
            journal.recordsForVerifiedUser("privy:user-a").single().state)
        assertEquals(1, storage.writeCount)
    }

    @Test
    fun validHashSurvivesRestoreSameStatusDoesNotRewriteAndNonfinalMayReorg() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        val hash = "0x" + "ab".repeat(32)
        val journal = journal(storage, 1_000)
        journal.beginCommitting(review)
        journal.beginSubmitting(review)
        journal.markBroadcast(review, hash)
        val writesAfterHash = storage.writeCount

        journal.markBroadcast(review, hash)
        assertEquals(writesAfterHash, storage.writeCount)

        journal.markObservation(ethereumObservation(review, hash, TransferLifecycleStatus.PENDING))
        val writesAfterPending = storage.writeCount
        journal.markObservation(ethereumObservation(review, hash, TransferLifecycleStatus.PENDING))
        assertEquals(writesAfterPending, storage.writeCount)
        journal.markObservation(ethereumObservation(review, hash, TransferLifecycleStatus.UNKNOWN))
        assertEquals(writesAfterPending + 1, storage.writeCount)

        val restored = journal(storage, 2_000)
        val record = restored.recordsForVerifiedUser("privy:user-a").single()
        assertEquals(hash, record.transactionId)
        assertEquals(TransferJournalState.UNKNOWN, record.state)
        assertTrue(record.needsStatusPolling)
        assertFalse(record.blocksNewSubmission)
    }

    @Test
    fun failedExecutionKeepsPollingUntilAuthoritativeFinality() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        val hash = "0x" + "cd".repeat(32)
        val journal = journal(storage, 1_000)
        journal.beginCommitting(review)
        journal.beginSubmitting(review)
        journal.markBroadcast(review, hash)

        journal.markObservation(ethereumFailure(review, hash, finalized = false))
        assertEquals(TransferJournalState.FAILED_NONFINAL,
            journal.recordsForVerifiedUser("privy:user-a").single().state)
        assertTrue(journal.recordsForVerifiedUser("privy:user-a").single().needsStatusPolling)

        journal.markObservation(ethereumFailure(review, hash, finalized = true))
        assertEquals(TransferJournalState.FAILED_FINALIZED,
            journal.recordsForVerifiedUser("privy:user-a").single().state)
        assertFalse(journal.recordsForVerifiedUser("privy:user-a").single().needsStatusPolling)
    }

    @Test
    fun recordsAreNeverReturnedAcrossVerifiedUsers() {
        val storage = MemoryJournalStorage()
        val first = ethereumReview("privy:user-a", "wallet-a")
        val second = ethereumReview("privy:user-b", "wallet-b")
        val journal = journal(storage, 1_000)
        journal.beginCommitting(first)
        journal.beginSubmitting(first)
        journal.markProviderFailure(first, true)
        journal.beginCommitting(second)

        assertEquals(listOf("privy:user-a"),
            journal.recordsForVerifiedUser("privy:user-a").map { it.review.userId }.distinct())
        assertEquals(listOf("privy:user-b"),
            journal.recordsForVerifiedUser("privy:user-b").map { it.review.userId }.distinct())
    }

    @Test
    fun corruptPersistenceFailsClosedInsteadOfErasingAHashlessLock() {
        val storage = MemoryJournalStorage("not-a-valid-eleven-journal")
        val journal = journal(storage, 1_000)

        assertFalse(journal.storageHealthy)
        assertTrue(journal.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertThrows(TransferJournalPersistenceException::class.java) {
            journal.beginCommitting(ethereumReview("privy:user-a", "wallet-a"))
        }
        assertEquals(0, storage.writeCount)
    }

    @Test
    fun oneMalformedRecordMakesOtherwiseValidPersistenceFailClosed() {
        val storage = MemoryJournalStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        journal(storage, 1_000).beginCommitting(review)
        storage.value = requireNotNull(storage.value) + "\n%%%"

        val restored = journal(storage, 2_000)

        assertFalse(restored.storageHealthy)
        assertTrue(restored.hasHashlessUnresolved("privy:another", "another-wallet"))
        // The intact record remains visible for diagnosis, but corruption never becomes an empty,
        // permissive journal.
        assertEquals(1, restored.recordsForVerifiedUser("privy:user-a").size)
    }

    @Test
    fun failedHashPersistenceLeavesTheDurableSubmittingLockInPlace() {
        val storage = FailsAfterTwoWritesStorage()
        val review = ethereumReview("privy:user-a", "wallet-a")
        val journal = TransferJournal(storage, { 1_000 })
        journal.beginCommitting(review)
        journal.beginSubmitting(review)

        assertThrows(TransferJournalPersistenceException::class.java) {
            journal.markBroadcast(review, "0x" + "12".repeat(32))
        }

        val restored = TransferJournal(storage, { 2_000 })
        assertTrue(restored.hasHashlessUnresolved("privy:user-a", "wallet-a"))
        assertEquals(TransferJournalState.SUBMITTING,
            restored.recordsForVerifiedUser("privy:user-a").single().state)
    }

    private fun journal(storage: MemoryJournalStorage, now: Long): TransferJournal =
        TransferJournal(storage, { now })

    private fun ethereumReview(userId: String, walletId: String): TransferReviewSnapshot {
        val session = TransferSessionIdentity.create(
            userId = userId,
            walletId = walletId,
            networkId = WalletNetworkId.ETHEREUM_MAINNET,
            walletAddress = "0x1111111111111111111111111111111111111111",
        )
        return TransferDraft.create(
            session = session,
            assetId = WalletAssetId.ETHEREUM_ETH,
            recipient = "0x2222222222222222222222222222222222222222",
            amount = "0.125",
            createdAtEpochMillis = 500,
        ).review()
    }

    private fun ethereumObservation(
        review: TransferReviewSnapshot,
        hash: String,
        status: TransferLifecycleStatus,
    ): EthereumTransferStatus = EthereumTransferStatus(
        review = review,
        transactionId = hash,
        senderVerified = if (status == TransferLifecycleStatus.UNKNOWN) null else true,
        status = status,
        confirmations = if (status == TransferLifecycleStatus.UNKNOWN) null else BigInteger.ZERO,
        isFinalized = false,
        observedAt = Instant.ofEpochMilli(900),
        blockNumber = null,
        blockHash = null,
        blockTime = null,
        finalizedBlockNumber = null,
        failure = null,
    )

    private fun ethereumFailure(
        review: TransferReviewSnapshot,
        hash: String,
        finalized: Boolean,
    ): EthereumTransferStatus = EthereumTransferStatus(
        review = review,
        transactionId = hash,
        senderVerified = true,
        status = TransferLifecycleStatus.FAILED,
        confirmations = BigInteger.ONE,
        isFinalized = finalized,
        observedAt = Instant.ofEpochMilli(900),
        blockNumber = BigInteger.ONE,
        blockHash = "0x" + "ef".repeat(32),
        blockTime = Instant.ofEpochMilli(800),
        finalizedBlockNumber = if (finalized) BigInteger.ONE else null,
        failure = EthereumTransferFailure.EXECUTION_REVERTED,
    )
}

private class MemoryJournalStorage(initial: String? = null) : TransferJournalStorage {
    var value: String? = initial
    var writeCount: Int = 0

    override fun read(): String? = value

    override fun write(value: String): Boolean {
        writeCount++
        this.value = value
        return true
    }
}

private class FailsAfterTwoWritesStorage : TransferJournalStorage {
    private var saved: String? = null
    private var writes = 0

    override fun read(): String? = saved

    override fun write(value: String): Boolean {
        writes++
        if (writes > 2) return false
        saved = value
        return true
    }
}

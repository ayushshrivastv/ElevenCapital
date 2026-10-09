package com.elevencapital.app.wallet

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWalletActivityTest {
    @Test
    fun currentWalletExcludesOtherAccountsAndReplacedWallets() {
        val matching = record()
        val otherAccount = record(userId = "privy:other")
        val replacedId = record(walletId = "old-wallet")
        val replacedAddress = record(sender = "8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR")

        assertEquals(
            listOf(matching),
            filterHomeWalletTransactions(
                listOf(otherAccount, replacedId, replacedAddress, matching), USER, listOf(wallet()),
            ),
        )
        assertTrue(filterHomeWalletTransactions(listOf(matching), null, listOf(wallet())).isEmpty())
        assertTrue(filterHomeWalletTransactions(listOf(matching), USER, emptyList()).isEmpty())
    }

    @Test
    fun exactSolanaAddressMatchesButMissingIdentityAndMalformedAddressDoNot() {
        val record = record()
        val connected = UserWallet(WalletChain.SOLANA, SENDER, WALLET)

        assertEquals(listOf(record), filterHomeWalletTransactions(listOf(record), USER, listOf(connected)))
        assertTrue(filterHomeWalletTransactions(listOf(record), USER, listOf(connected.copy(walletId = ""))).isEmpty())
        assertTrue(filterHomeWalletTransactions(listOf(record), USER,
            listOf(connected.copy(address = "invalid"))).isEmpty())
    }

    @Test
    fun pollingAnOlderTransferDoesNotMoveItAheadOfANewerSend() {
        val older = record(createdAt = 1_000, updatedAt = 9_000)
        val newer = record(createdAt = 2_000, updatedAt = 2_000)

        assertEquals(listOf(newer, older),
            filterHomeWalletTransactions(listOf(older, newer), USER, listOf(wallet())))
    }

    @Test
    fun failedAndUnverifiedAttemptsNeverClaimCompletedOutgoingFunds() {
        val uncertain = listOf(
            TransferJournalState.SUBMITTING,
            TransferJournalState.AMBIGUOUS,
            TransferJournalState.BROADCAST,
            TransferJournalState.UNKNOWN,
            TransferJournalState.PENDING,
            TransferJournalState.DEFINITELY_NOT_BROADCAST,
            TransferJournalState.FAILED_NONFINAL,
            TransferJournalState.FAILED_FINALIZED,
        )
        uncertain.forEach { state ->
            assertFalse(homeWalletTransferAmount(record(state = state)).startsWith("−"))
            assertFalse(homeWalletTransferStatus(state) == "Transfer")
        }
        assertEquals("−0.123456789 SOL",
            homeWalletTransferAmount(record(state = TransferJournalState.FINALIZED)))
        assertEquals("Confirmed", homeWalletTransferStatus(TransferJournalState.CONFIRMED))
        assertEquals("Pending", homeWalletTransferStatus(TransferJournalState.PENDING))
        assertEquals("Awaiting network", homeWalletTransferStatus(TransferJournalState.UNKNOWN))
    }

    private fun wallet() = UserWallet(WalletChain.SOLANA, SENDER, WALLET)

    private fun record(
        userId: String = USER,
        walletId: String = WALLET,
        sender: String = SENDER,
        createdAt: Long = 1_000,
        updatedAt: Long = createdAt,
        state: TransferJournalState = TransferJournalState.FINALIZED,
    ): TransferJournalRecord {
        val review = TransferDraft.create(
            TransferSessionIdentity.create(userId, walletId, WalletNetworkId.SOLANA_MAINNET, sender),
            WalletAssetId.SOLANA_SOL,
            "So11111111111111111111111111111111111111112",
            "0.123456789",
            createdAt,
        ).review()
        val hash = when (state) {
            TransferJournalState.COMMITTING,
            TransferJournalState.SUBMITTING,
            TransferJournalState.RELEASING,
            TransferJournalState.AMBIGUOUS,
            TransferJournalState.DEFINITELY_NOT_BROADCAST -> null
            else -> encodeBase58(ByteArray(64) { 5 })
        }
        return TransferJournalRecord(review, state, hash, updatedAt)
    }

    private companion object {
        const val USER = "privy:current"
        const val WALLET = "wallet-current"
        const val SENDER = "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"
    }
}

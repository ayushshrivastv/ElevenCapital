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
        val replacedAddress = record(sender = "0x2222222222222222222222222222222222222222")

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
    fun canonicalEthereumAddressMatchesButMissingIdentityAndWrongChainDoNot() {
        val record = record(sender = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val upper = UserWallet(WalletChain.ETHEREUM, "0x" + "A".repeat(40), WALLET)

        assertEquals(listOf(record), filterHomeWalletTransactions(listOf(record), USER, listOf(upper)))
        assertTrue(filterHomeWalletTransactions(listOf(record), USER, listOf(upper.copy(walletId = ""))).isEmpty())
        assertTrue(filterHomeWalletTransactions(listOf(record), USER,
            listOf(upper.copy(chain = WalletChain.SOLANA))).isEmpty())
        assertTrue(filterHomeWalletTransactions(listOf(record), USER,
            listOf(upper.copy(address = "invalid"))).isEmpty())
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
        assertEquals("−0.123456789123456789 ETH",
            homeWalletTransferAmount(record(state = TransferJournalState.FINALIZED)))
        assertEquals("Confirmed", homeWalletTransferStatus(TransferJournalState.CONFIRMED))
        assertEquals("Pending", homeWalletTransferStatus(TransferJournalState.PENDING))
        assertEquals("Awaiting network", homeWalletTransferStatus(TransferJournalState.UNKNOWN))
    }

    private fun wallet() = UserWallet(WalletChain.ETHEREUM, SENDER, WALLET)

    private fun record(
        userId: String = USER,
        walletId: String = WALLET,
        sender: String = SENDER,
        createdAt: Long = 1_000,
        updatedAt: Long = createdAt,
        state: TransferJournalState = TransferJournalState.FINALIZED,
    ): TransferJournalRecord {
        val review = TransferDraft.create(
            TransferSessionIdentity.create(userId, walletId, WalletNetworkId.ETHEREUM_MAINNET, sender),
            WalletAssetId.ETHEREUM_ETH,
            "0x3333333333333333333333333333333333333333",
            "0.123456789123456789",
            createdAt,
        ).review()
        val hash = when (state) {
            TransferJournalState.COMMITTING,
            TransferJournalState.SUBMITTING,
            TransferJournalState.RELEASING,
            TransferJournalState.AMBIGUOUS,
            TransferJournalState.DEFINITELY_NOT_BROADCAST -> null
            else -> "0x" + "ab".repeat(32)
        }
        return TransferJournalRecord(review, state, hash, updatedAt)
    }

    private companion object {
        const val USER = "privy:current"
        const val WALLET = "wallet-current"
        const val SENDER = "0x1111111111111111111111111111111111111111"
    }
}

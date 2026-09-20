package com.elevencapital.app.wallet

import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.auth.WalletChain

/**
 * The home wallet only shows sends belonging to the current verified wallet identities.
 * An account's old wallet journal remains available in History, but must not appear under a
 * replacement wallet's balance. The journal does not contain external incoming transfers.
 */
fun filterHomeWalletTransactions(
    records: List<TransferJournalRecord>,
    verifiedUserId: String?,
    wallets: List<UserWallet>,
): List<TransferJournalRecord> {
    if (verifiedUserId.isNullOrBlank()) return emptyList()
    val connected = wallets.mapNotNull { wallet ->
        if (wallet.walletId.isBlank()) return@mapNotNull null
        val network = when (wallet.chain) {
            WalletChain.ETHEREUM -> WalletNetworkId.ETHEREUM_MAINNET
            WalletChain.SOLANA -> WalletNetworkId.SOLANA_MAINNET
        }
        runCatching { wallet.walletId to ValidatedWalletAddress.wallet(network, wallet.address) }.getOrNull()
    }.toSet()
    return records.asSequence()
        .filter { record ->
            record.review.userId == verifiedUserId &&
                (record.review.walletId to record.review.sender) in connected
        }
        // Status polling must not move an old transfer ahead of a more recent send.
        .sortedWith(compareByDescending<TransferJournalRecord> { it.review.createdAtEpochMillis }
            .thenByDescending { it.updatedAtEpochMillis })
        .toList()
}

internal fun homeWalletTransferStatus(state: TransferJournalState): String = when (state) {
    TransferJournalState.COMMITTING -> "Preparing"
    TransferJournalState.SUBMITTING -> "Submitting"
    TransferJournalState.RELEASING -> "Needs attention"
    TransferJournalState.AMBIGUOUS -> "Needs attention"
    TransferJournalState.DEFINITELY_NOT_BROADCAST -> "Not sent"
    TransferJournalState.BROADCAST, TransferJournalState.UNKNOWN -> "Awaiting network"
    TransferJournalState.PENDING -> "Pending"
    TransferJournalState.CONFIRMED -> "Confirmed"
    TransferJournalState.FINALIZED -> "Transfer"
    TransferJournalState.FAILED_NONFINAL -> "Failed · confirming"
    TransferJournalState.FAILED_FINALIZED -> "Failed"
}

/** A minus sign describes an observed transfer, not a failed or unverified attempt. */
internal fun homeWalletTransferAmount(record: TransferJournalRecord): String {
    val prefix = when (record.state) {
        TransferJournalState.CONFIRMED, TransferJournalState.FINALIZED -> "−"
        else -> ""
    }
    return "$prefix${record.review.displayAmount} ${record.review.assetSymbol}"
}

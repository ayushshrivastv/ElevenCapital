package com.elevencapital.app.data

/** Keep devnet transfers visible without assigning mainnet USD value to faucet SOL. */
fun SolanaDevnetSnapshot.walletTransactionsFor(verifiedSolanaAddress: String?): List<WalletTransaction> {
    if (walletAddress != verifiedSolanaAddress) return emptyList()
    return transactions.map { transaction ->
        WalletTransaction(
            id = "solana-devnet:${transaction.signature}",
            chain = WalletActivityChain.SOLANA_DEVNET,
            transactionId = transaction.signature,
            timestamp = transaction.timestamp,
            direction = when (transaction.direction) {
                SolanaActivityDirection.RECEIVE -> WalletTransactionDirection.RECEIVE
                SolanaActivityDirection.SEND -> WalletTransactionDirection.SEND
            },
            assetSymbol = "SOL",
            amount = transaction.amount,
            valueUsd = null,
            usdBasis = null,
            counterparty = transaction.counterparty,
        )
    }
}

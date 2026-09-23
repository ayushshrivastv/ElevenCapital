package com.elevencapital.app.wallet

/** A Privy provider accepted the transaction and returned its public chain identifier. */
data class WalletSubmission(
    val operationId: TransferOperationId,
    val networkId: WalletNetworkId,
    val transactionId: String,
    val submittedAtEpochMillis: Long,
) {
    init {
        require(transactionId.length in 64..90)
        require(transactionId.none(Char::isWhitespace) && transactionId.none(Char::isISOControl))
        require(submittedAtEpochMillis >= 0)
    }
}

/**
 * A signing/broadcast failure is deliberately classified by certainty. Unless Privy supplies its
 * exact `transaction_broadcast_failure` code, the app must assume submission may have happened and
 * must reconcile chain state instead of blindly sending the same payment again.
 */
class WalletSubmissionException(
    val definitelyNotBroadcast: Boolean,
    val userMessage: String,
    val transactionId: String? = null,
) : Exception(userMessage) {
    init {
        require(userMessage.isNotBlank() && userMessage.length <= 220)
        require(userMessage.none(Char::isISOControl))
        require(transactionId == null || (transactionId.length in 64..90 &&
            transactionId.none(Char::isWhitespace) && transactionId.none(Char::isISOControl)))
    }
}

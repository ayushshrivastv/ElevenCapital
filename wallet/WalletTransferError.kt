package com.elevencapital.app.wallet

/** Stable, user-safe reasons. Neither the rejected input nor an upstream error body is retained. */
enum class WalletTransferErrorCode {
    UNSUPPORTED_ASSET,
    WRONG_NETWORK,
    INVALID_RECIPIENT,
    SAME_SENDER_AND_RECIPIENT,
    INVALID_WALLET,
    INVALID_SESSION,
    INVALID_AMOUNT,
    EXCESS_PRECISION,
    AMOUNT_TOO_LARGE,
}

class WalletTransferValidationException internal constructor(
    val code: WalletTransferErrorCode,
    val userMessage: String,
) : IllegalArgumentException(userMessage) {
    init {
        require(userMessage.isNotBlank() && userMessage.length <= 160 && userMessage.none(Char::isISOControl))
    }
}

internal fun transferValidationFailure(
    code: WalletTransferErrorCode,
    message: String,
): Nothing = throw WalletTransferValidationException(code, message)

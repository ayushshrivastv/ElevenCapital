package com.elevencapital.app.auth

import io.privy.network.PrivyApiException

/** Deliberately excludes SDK message/body, stack traces, user IDs, wallet addresses, and tokens. */
internal fun safeWalletFailureDiagnostic(failure: Throwable): String {
    val step = failure as? WalletProvisioningFailure
    val causes = generateSequence(failure) { it.cause }.take(6).toList()
    val typeNames = causes.map { it.javaClass.name }.joinToString(",")
    val status = causes.filterIsInstance<PrivyApiException>().firstNotNullOfOrNull {
        it.statusCode?.takeIf { code -> code in 100..599 }
    }
    return "stage=${step?.stage?.name ?: "SESSION"} chain=${step?.chain?.name ?: "UNSPECIFIED"}" +
        " exceptionTypes=$typeNames httpStatus=${status ?: "unavailable"}"
}

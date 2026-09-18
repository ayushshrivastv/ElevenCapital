package com.elevencapital.app.auth

import io.privy.network.PrivyApiException
import org.junit.Assert.*
import org.junit.Test

class WalletDiagnosticsTest {
    @Test fun `diagnostic keeps failure stage and class but excludes messages and secrets`() {
        val privateDetails = "Bearer secret-example wallet-private-address user-private-id"
        val failure = WalletProvisioningFailure(WalletProvisioningStage.CREATE, WalletChain.SOLANA,
            IllegalStateException(privateDetails, IllegalArgumentException(privateDetails)))
        val output = safeWalletFailureDiagnostic(failure)
        assertTrue(output.contains("stage=CREATE chain=SOLANA"))
        assertTrue(output.contains("java.lang.IllegalStateException"))
        assertFalse(output.contains("Bearer"))
        assertFalse(output.contains("secret-example"))
        assertFalse(output.contains("wallet-private-address"))
        assertFalse(output.contains("user-private-id"))
    }

    @Test fun `diagnostic exposes only numeric HTTP status from typed SDK error`() {
        val failure = PrivyApiException(429, null, "private-response-content", IllegalStateException("private-cause"))
        val output = safeWalletFailureDiagnostic(failure)
        assertTrue(output.contains("stage=SESSION chain=UNSPECIFIED"))
        assertTrue(output.contains("httpStatus=429"))
        assertFalse(output.contains("private-response-content"))
        assertFalse(output.contains("private-cause"))
    }

    @Test fun `invalid HTTP status is omitted`() {
        val failure = PrivyApiException(-1, null, "private", IllegalStateException())
        assertTrue(safeWalletFailureDiagnostic(failure).contains("httpStatus=unavailable"))
    }
}

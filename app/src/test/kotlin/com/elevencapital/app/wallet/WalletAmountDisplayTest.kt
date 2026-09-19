package com.elevencapital.app.wallet

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletAmountDisplayTest {
    @Test fun formatsExactBaseUnitsWithoutFloatingPoint() {
        assertEquals("0", formatBaseUnits(BigInteger.ZERO, 18))
        assertEquals("0.000001", formatBaseUnits(BigInteger.ONE, 6))
        assertEquals("1", formatBaseUnits(BigInteger("1000000"), 6))
        assertEquals("12.3405", formatBaseUnits(BigInteger("12340500"), 6))
        assertEquals("42000000", formatBaseUnits(BigInteger("42000000"), 0))
    }
}

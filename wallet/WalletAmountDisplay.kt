package com.elevencapital.app.wallet

import java.math.BigInteger

/** Exact display formatting for already-validated integer base units; no floating point. */
internal fun formatBaseUnits(value: BigInteger, decimals: Int): String {
    require(value.signum() >= 0 && decimals >= 0)
    if (decimals == 0) return value.toString()
    val raw = value.toString().padStart(decimals + 1, '0')
    val integer = raw.dropLast(decimals)
    val fraction = raw.takeLast(decimals).trimEnd('0')
    return if (fraction.isEmpty()) integer else "$integer.$fraction"
}

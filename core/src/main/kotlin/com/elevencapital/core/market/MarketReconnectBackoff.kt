package com.elevencapital.core.market

import kotlin.random.Random

/** A foreground-only reconnect schedule. Jitter avoids synchronized reconnect storms. */
class MarketReconnectBackoff(private val random: Random = Random.Default) {
    private var failures = 0

    fun nextDelayMillis(): Long {
        val ceiling = minOf(30_000L, 1_000L shl minOf(failures, 5))
        failures = minOf(failures + 1, 6)
        return random.nextLong(ceiling / 2, ceiling + 1)
    }

    /** A valid server frame, not merely a TCP connection, establishes recovery. */
    fun recovered() { failures = 0 }
}

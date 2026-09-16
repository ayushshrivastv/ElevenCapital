package com.elevencapital.core.stock

import java.time.Duration
import java.time.Instant

/** Fetch freshness, not market-tick freshness. Uses server-relative age plus a monotonic local age. */
fun isReferenceDataFresh(
    fetchedAt: Instant?,
    snapshotAt: Instant,
    elapsedSinceSnapshotMillis: Long,
    maxAgeMillis: Long = 300_000,
): Boolean {
    if (fetchedAt == null || fetchedAt > snapshotAt || elapsedSinceSnapshotMillis < 0) return false
    val serverAge = Duration.between(fetchedAt, snapshotAt).toMillis()
    return serverAge <= maxAgeMillis && elapsedSinceSnapshotMillis <= maxAgeMillis - serverAge
}

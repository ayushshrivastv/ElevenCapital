package com.elevencapital.core.stock

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReferenceFreshnessTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    @Test fun resumeAfterSixMinutesExpiresData() {
        assertFalse(isReferenceDataFresh(now, now, 360_000))
    }
    @Test fun upstreamAgeAndDeviceAgeAccumulate() {
        assertFalse(isReferenceDataFresh(now.minusSeconds(240), now, 120_000))
        assertTrue(isReferenceDataFresh(now.minusSeconds(240), now, 30_000))
    }
    @Test fun unknownFetchTimeIsNotFresh() {
        assertFalse(isReferenceDataFresh(null, now, 0))
    }
    @Test fun futureFetchTimeIsNotMadeFresh() {
        assertFalse(isReferenceDataFresh(now.plusSeconds(60), now, 0))
    }
}

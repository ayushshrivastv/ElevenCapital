package com.elevencapital.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A bounded recovery episode. Repeated SDK states cannot restart its clock or multiply requests. */
internal class SessionRestoreRecovery<T : Any>(
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val verify: suspend () -> T,
    private val onObservation: (T) -> Unit,
    private val onRetryAvailable: (Boolean) -> Unit,
    private val attemptTimeoutMs: Long = 6_000L,
    private val retryAvailableAfterMs: Long = 12_000L,
    private val automaticDelaysMs: List<Long> = listOf(1_000L, 2_000L, 4_000L),
) {
    private var generation = 0L
    private var episodeStarted = false
    private var delayElapsed = false
    private var retryAvailable = false
    private var checkJob: Job? = null
    private var retryDelayJob: Job? = null

    init {
        require(attemptTimeoutMs > 0 && retryAvailableAfterMs >= 0)
        require(automaticDelaysMs.isNotEmpty() && automaticDelaysMs.all { it >= 0 })
    }

    fun start() {
        if (episodeStarted || !isCurrent()) return
        episodeStarted = true
        delayElapsed = false
        val attempt = ++generation
        setRetryAvailable(false)
        retryDelayJob = scope.launch {
            delay(retryAvailableAfterMs)
            if (!current(attempt)) return@launch
            delayElapsed = true
            if (checkJob?.isActive != true) setRetryAvailable(true)
        }
        check(attempt, automaticDelaysMs)
    }

    /** One intentional retry after automatic work ends. It never starts parallel verification. */
    fun retry() {
        if (!episodeStarted || !retryAvailable || checkJob?.isActive == true || !isCurrent()) return
        setRetryAvailable(false)
        check(generation, listOf(0L))
    }

    /** Retires every outstanding reply before another login, logout or verified account can run. */
    fun stop() {
        generation++
        episodeStarted = false
        delayElapsed = false
        checkJob?.cancel()
        checkJob = null
        retryDelayJob?.cancel()
        retryDelayJob = null
        setRetryAvailable(false)
    }

    private fun check(attempt: Long, pauses: List<Long>) {
        checkJob = scope.launch {
            try {
                for (pause in pauses) {
                    delay(pause)
                    if (!current(attempt)) return@launch
                    val observed = try {
                        withTimeoutOrNull(attemptTimeoutMs) { verify() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A timeout or network failure says nothing about whether credentials remain valid.
                        null
                    }
                    if (!current(attempt)) return@launch
                    observed?.let(onObservation)
                    if (!current(attempt)) return@launch
                }
            } finally {
                if (current(attempt) && delayElapsed) setRetryAvailable(true)
            }
        }
    }

    private fun current(attempt: Long): Boolean =
        episodeStarted && generation == attempt && isCurrent()

    private fun setRetryAvailable(available: Boolean) {
        if (retryAvailable == available) return
        retryAvailable = available
        onRetryAvailable(available)
    }
}

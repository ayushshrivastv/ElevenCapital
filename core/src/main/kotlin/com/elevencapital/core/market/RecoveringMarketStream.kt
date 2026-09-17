package com.elevencapital.core.market

import java.io.EOFException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen

/** Failed updates leave the consumer's last snapshot intact. Cancellation never schedules a retry. */
fun <T> recoveringMarketStream(
    connect: () -> Flow<T>,
    onInterrupted: suspend (Exception) -> Unit,
    backoff: MarketReconnectBackoff = MarketReconnectBackoff(),
    isRecovery: (T) -> Boolean = { true },
    pause: suspend (Long) -> Unit = { delay(it) },
): Flow<T> = flow {
    emitAll(connect())
    throw EOFException("Market update stream ended.")
}.onEach {
    if (isRecovery(it)) backoff.recovered()
}.retryWhen { cause, _ ->
    // retryWhen intentionally does not intercept exceptions from the consumer.
    if (cause is CancellationException) throw cause
    if (cause !is Exception) return@retryWhen false
    onInterrupted(cause)
    pause(backoff.nextDelayMillis())
    true
}

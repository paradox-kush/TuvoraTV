package com.nuvio.tv.core.util

import kotlinx.coroutines.CancellationException

/**
 * Result of a guarded read that distinguishes a genuine value (including `null`) from a swallowed
 * failure, so a caller can degrade without conflating "read failed" with "read returned null".
 * Twin of NuvioMobile/NuvioDesktop `Guarded` — hand-ported.
 */
internal sealed interface Guarded<out T> {
    data class Ok<out T>(val value: T) : Guarded<T>
    object Failed : Guarded<Nothing>
}

/**
 * Runs [read], returning [Guarded.Ok] (even for a null value) or [Guarded.Failed] if it threw.
 * `CancellationException` is ALWAYS re-raised — a guard must never swallow structured cancellation.
 */
internal inline fun <T> guarded(onError: (Throwable) -> Unit, read: () -> T): Guarded<T> =
    try {
        Guarded.Ok(read())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        onError(t)
        Guarded.Failed
    }

/**
 * Runs [body], containing any failure via [onError] except `CancellationException`, which is
 * re-raised. For a bare `launch {}` on a `SupervisorJob` scope with no `CoroutineExceptionHandler`,
 * where an uncaught throwable reaches the process default handler and crashes the app — a
 * `SupervisorJob` stops sibling cancellation, it is NOT a handler.
 */
internal inline fun containTask(onError: (Throwable) -> Unit, body: () -> Unit) {
    try {
        body()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        onError(t)
    }
}

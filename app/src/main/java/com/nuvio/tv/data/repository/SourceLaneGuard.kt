package com.nuvio.tv.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** A provider lane ran past [SourceLaneGuard.LANE_DEADLINE_MS] and is ended as a failure. */
class SourceLaneTimeoutException(val deadlineMs: Long) :
    Exception("Source did not answer within ${deadlineMs / 1000}s")

/** A provider threw a CancellationException of its own while the caller still wanted the list. */
class SourceLaneInterruptedException(cause: Throwable) :
    Exception(cause.message ?: "Source request was interrupted", cause)

/**
 * One provider's lane in a title's merged source list. Every lane must END and be counted: the
 * aggregator closes its result channel only after every lane's `finally` has run, and its catch
 * blocks re-throw CancellationException before recording a failure.
 *
 * A provider-side CancellationException (stale scope, epoch change, an inner `withTimeout`) is not
 * the caller leaving; re-throwing it dropped the failure record (the title showed "No streams"
 * rather than the reason) and, in the plugin lane whose catch skips the completion counter, left the
 * channel open forever (B226 family). A provider that never answered held the list open with no
 * deadline. Same rule as NuvioMobile `features/streams/SourceLaneGuard`: only the CALLER's
 * cancellation propagates; everything else becomes an ordinary exception for the lane's catch.
 */
object SourceLaneGuard {
    const val LANE_DEADLINE_MS = 60_000L

    suspend fun <T> bounded(deadlineMs: Long = LANE_DEADLINE_MS, block: suspend () -> T): T {
        val caller = currentCoroutineContext()
        try {
            return withTimeout(deadlineMs) { block() }
        } catch (error: TimeoutCancellationException) {
            caller.ensureActive()
            throw SourceLaneTimeoutException(deadlineMs)
        } catch (error: CancellationException) {
            caller.ensureActive()
            throw SourceLaneInterruptedException(error)
        }
    }
}

package com.nuvio.tv.core.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Step 2 — how the TV's "Enter setup code" screen finishes BY ITSELF when a phone redeems the code on
 * tuvora.co. The network rule applies: a poll must be delta-shaped, lifecycle-bound and tested.
 *
 *  - The probe is the tiny `get_managed_playlists(profile)` read — never a playlist pull.
 *  - First call after [FIRST_CALL_MS], then every [INTERVAL_MS]; one in flight at most (the loop awaits
 *    each call); stop at [HARD_STOP_MS] after the screen opened (<= 50 calls).
 *  - [SLOW_AFTER_FAILURES] consecutive failures slow the cadence to [SLOW_INTERVAL_MS].
 *  - Success = a `playlist_key` that was not in the snapshot taken when the screen opened. If the
 *    snapshot itself could not be read, the first answer that does arrive becomes the baseline (it never
 *    counts as success: that would credit an old playlist to this screen).
 *
 * The caller runs [run] under `repeatOnLifecycle(RESUMED)` and cancels it when the screen is left or the
 * code is typed on the TV; cancellation is the only other way it stops, and it never issues a request
 * after being cancelled.
 */
object SetupWaitPolicy {
    const val FIRST_CALL_MS = 3_000L
    const val INTERVAL_MS = 6_000L
    const val SLOW_INTERVAL_MS = 30_000L
    const val HARD_STOP_MS = 300_000L
    const val SLOW_AFTER_FAILURES = 3

    sealed interface Outcome {
        /** [newKeys] (sorted) appeared since the snapshot. */
        data class Found(val newKeys: List<String>) : Outcome
        data object TimedOut : Outcome
    }

    /** The pause before call number [callsMade] + 1. */
    fun delayBeforeNextCall(callsMade: Int, consecutiveFailures: Int): Long = when {
        consecutiveFailures >= SLOW_AFTER_FAILURES -> SLOW_INTERVAL_MS
        callsMade == 0 -> FIRST_CALL_MS
        else -> INTERVAL_MS
    }

    fun newKeys(snapshot: Set<String>, current: Set<String>): Set<String> = current - snapshot

    /**
     * @param snapshot the managed playlist keys when the screen opened; null when that read failed.
     * @param startedAtMs [now]'s value when the screen first opened (the cap spans re-resumes).
     * @param poll one `get_managed_playlists` call: the keys, or the failure.
     */
    suspend fun run(
        snapshot: Set<String>?,
        startedAtMs: Long,
        now: () -> Long,
        /** Called when the first answer becomes the baseline (the opening snapshot failed), so a re-resume keeps it. */
        onBaseline: (Set<String>) -> Unit = {},
        poll: suspend () -> Result<Set<String>>,
    ): Outcome {
        var baseline = snapshot
        var calls = 0
        var failures = 0
        while (true) {
            val wait = delayBeforeNextCall(calls, failures)
            if (now() - startedAtMs + wait > HARD_STOP_MS) return Outcome.TimedOut
            delay(wait)
            calls++
            val result = try {
                poll()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                Result.failure(e)
            }
            val keys = result.getOrNull()
            if (keys == null) {
                failures++
                continue
            }
            failures = 0
            val base = baseline
            if (base == null) {
                baseline = keys
                onBaseline(keys)
                continue
            }
            val fresh = newKeys(base, keys)
            if (fresh.isNotEmpty()) return Outcome.Found(fresh.sorted())
        }
    }
}

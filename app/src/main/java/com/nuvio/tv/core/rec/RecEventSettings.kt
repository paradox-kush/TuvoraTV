package com.nuvio.tv.core.rec

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS = "nuvio_rec_events"
private const val QUEUE_FILE = "rec-events-queue.jsonl"
private const val KEY_ENABLED = "logging_enabled"
private const val KEY_SUPPRESSED_UNTIL = "suppressed_until_ms"

/** How long a 410 from the ingest endpoint silences the client. */
const val REC_KILL_SWITCH_BACKOFF_MS = 24 * 60 * 60 * 1000L

/**
 * The two things that can stop the recommendation logger: the user's own opt-out, and the
 * backend's kill switch.
 *
 * There is no remote feature-flag system in this app, so the kill switch IS the remote control:
 * `rec_ingest_config.enabled = false` makes the edge function answer 410 and every client goes
 * quiet for 24h. That is the only lever available if the stream ever needs stopping in the field
 * without shipping a release.
 */
@Singleton
class RecEventSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: RecEventIdentity,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, true))

    /** "Share anonymous usage to improve recommendations" — on by default, off instantly. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var queuePurger: (() -> Unit)? = null

    internal fun registerQueuePurger(purger: () -> Unit) {
        queuePurger = purger
        if (!_enabled.value) purger()
    }

    fun setEnabled(value: Boolean) {
        if (_enabled.value == value) return
        if (!value) {
            preferences.edit().putBoolean(KEY_ENABLED, false).apply()
            _enabled.value = false
            purgePendingEvents()
            return
        }

        // Always purge before identity rotation: an old on-disk queue from a previous build must
        // never be uploaded as though it belonged to the new post-opt-out device id.
        purgePendingEvents()
        identity.rotateDeviceId()
        preferences.edit().putBoolean(KEY_ENABLED, true).apply()
        _enabled.value = true
    }

    fun suppressUntil(nowMs: Long) {
        preferences.edit()
            .putLong(KEY_SUPPRESSED_UNTIL, nowMs + REC_KILL_SWITCH_BACKOFF_MS)
            .apply()
    }

    private fun suppressedUntilMs(): Long = preferences.getLong(KEY_SUPPRESSED_UNTIL, 0L)

    /** True when events may be collected and sent right now. */
    fun isActive(nowMs: Long): Boolean = _enabled.value && nowMs >= suppressedUntilMs()

    /** Account deletion keeps the user's opt-out setting but removes queued data and identity. */
    fun clearAccountState() {
        purgePendingEvents()
        identity.resetLocalState()
    }

    private fun purgePendingEvents() {
        runCatching { File(context.filesDir, QUEUE_FILE).delete() }
        queuePurger?.invoke()
    }
}

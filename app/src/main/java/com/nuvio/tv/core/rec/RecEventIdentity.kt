package com.nuvio.tv.core.rec

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS = "nuvio_rec_events"
private const val KEY_DEVICE_ID = "device_id"

/**
 * Session boundary. Brief backgrounding (a TV user answering the door, an overlay stealing
 * focus) must NOT split a session, or impression dedupe resets and the same shelf logs twice.
 */
private const val SESSION_IDLE_RESET_MS = 30 * 60 * 1000L

/**
 * Identity for the recommendation event stream: an install-scoped device id and a
 * foreground-session id.
 *
 * The device id is a plain random UUID minted on first use — deliberately NOT an advertising id,
 * a hardware id, or anything derived from one, so the app never needs an ATT prompt and the
 * stream carries no cross-app identity. It is rotated when the user re-enables logging after
 * opting out, which makes an opt-out cycle a genuine break in the record rather than a pause.
 *
 * Mirrors [com.nuvio.tv.core.sync.SyncClientIdentity]'s storage pattern, but that id is a sync
 * client identifier the backend joins on; this one must be a UUID because the edge function
 * validates it as one.
 */
@Singleton
class RecEventIdentity @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var cachedDeviceId: String? = null

    private var currentSessionId: String = UUID.randomUUID().toString()
    private var lastActivityAtMs: Long = 0L

    @Synchronized
    fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val stored = preferences.getString(KEY_DEVICE_ID, null)?.trim()?.takeIf { it.isUuid() }
        if (stored != null) {
            cachedDeviceId = stored
            return stored
        }
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        cachedDeviceId = generated
        return generated
    }

    /** Called when logging is re-enabled: the new stream must not be joinable to the old one. */
    @Synchronized
    fun rotateDeviceId() {
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        cachedDeviceId = generated
        currentSessionId = UUID.randomUUID().toString()
    }

    /** Forget the install token when local account data is wiped. */
    @Synchronized
    fun resetLocalState() {
        preferences.edit().remove(KEY_DEVICE_ID).apply()
        cachedDeviceId = null
        currentSessionId = UUID.randomUUID().toString()
        lastActivityAtMs = 0L
    }

    /**
     * The session this event belongs to. Rolls over after [SESSION_IDLE_RESET_MS] of no logging,
     * which is what "app-foreground session" means in practice on a TV that is never really
     * closed. Callers must treat a changed value as a dedupe-set reset.
     */
    @Synchronized
    fun sessionId(nowMs: Long): String {
        if (lastActivityAtMs != 0L && nowMs - lastActivityAtMs > SESSION_IDLE_RESET_MS) {
            currentSessionId = UUID.randomUUID().toString()
        }
        lastActivityAtMs = nowMs
        return currentSessionId
    }

    /**
     * The current session without counting as activity. Dedupe bookkeeping must be able to ask
     * "which session are we in" without keeping an idle session alive.
     */
    @Synchronized
    fun peekSessionId(): String = currentSessionId

    private fun String.isUuid(): Boolean = runCatching { UUID.fromString(this) }.isSuccess
}

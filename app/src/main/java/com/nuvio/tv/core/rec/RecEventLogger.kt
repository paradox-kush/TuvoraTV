package com.nuvio.tv.core.rec

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RecEventLogger"

private const val APP_IDENTIFIER = "tv"
private const val QUEUE_FILE = "rec-events-queue.jsonl"

/** The edge function caps a batch at 200; stay well under so one flush is always one request. */
private const val FLUSH_AT_EVENTS = 50
private const val MAX_QUEUED_EVENTS = 500
private const val FLUSH_INTERVAL_MS = 30_000L

private const val BACKOFF_START_MS = 30_000L
private const val BACKOFF_MAX_MS = 15 * 60 * 1000L

/**
 * The recommendation event queue: everything the app wants to record goes through [log], and
 * this class decides when it reaches the backend.
 *
 * Shape of the thing: events accumulate in memory, the whole unsent buffer is mirrored to a
 * JSON-lines file on each flush attempt, and the file is cleared only once the backend has
 * accepted them. A process kill therefore loses at most one flush interval rather than the
 * session — without paying a disk write per impression, which matters on the budget boxes where
 * this app already fights for I/O.
 *
 * Failure handling is deliberately asymmetric:
 *  - 204: accepted, drop the batch.
 *  - 410: the backend's kill switch. Go quiet for 24h ([RecEventSettings.suppressUntil]) and
 *    discard what is queued — it is not coming back.
 *  - other 4xx: the function validates the WHOLE batch and rejects it on any bad field, so a 4xx
 *    means a client bug. Retrying is pointless and would wedge the queue forever; drop and log
 *    loudly so it surfaces in debug.
 *  - 5xx / network: transient. Keep the batch, retry with exponential backoff.
 *
 * FAIL-OPEN CONTRACT: nothing in this package may ever break the app. Telemetry is worth less
 * than a working player, always. Every public entry point swallows its own exceptions, no caller
 * is ever blocked, and a misconfigured or unreachable backend degrades to "no events" rather
 * than to an error the user can see. If you add a code path here, it either cannot throw or it
 * is wrapped.
 */
@Singleton
class RecEventLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: RecEventIdentity,
    private val settings: RecEventSettings,
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val profileManager: ProfileManager,
) {

    /**
     * Read per event, never cached. On a household TV the profile IS the user as far as training
     * is concerned — blending a child's viewing into a parent's stream is the specific failure
     * this guards against.
     */
    private fun activeProfileId(): Int =
        runCatching { profileManager.activeProfileId.value.coerceIn(1, Short.MAX_VALUE.toInt()) }
            .getOrDefault(1)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()
    private val queueLock = Any()
    private val queue = ArrayDeque<RecEventRecord>()

    init {
        // MUST come after queueLock/queue are initialized: registerQueuePurger invokes the purger
        // IMMEDIATELY when logging is already opted out, and discardPendingEvents synchronizes on
        // queueLock — so registering from an init block placed above these properties crashed at
        // startup ("Null reference used for synchronization") for every user who had opted out.
        settings.registerQueuePurger(::discardPendingEvents)
    }

    @Volatile
    private var started = false

    @Volatile
    private var backoffMs = BACKOFF_START_MS

    @Volatile
    private var retryNotBeforeMs = 0L

    private val queueFile: File
        get() = File(context.filesDir, QUEUE_FILE)

    /** Called once from the Application. Restores anything a previous process left unsent. */
    fun start() {
        if (started) return
        started = true
        if (BuildConfig.IS_DEBUG_BUILD) Log.d(TAG, "start(): logger running")
        scope.launch {
            runCatching {
                if (settings.isActive(System.currentTimeMillis())) restoreQueue()
                else discardPendingEvents()
            }
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush("timer")
            }
        }
    }

    /**
     * Queue one event. Safe to call from any thread, including composition and the playback hot
     * path — the only work done on the caller's thread is a bounded list append, and the whole
     * body is guarded so a bad event builder can never surface as a crash in the UI.
     */
    fun log(event: RecEvent) {
        try {
            val now = System.currentTimeMillis()
            if (!settings.isActive(now)) return
            val sessionId = identity.sessionId(now)
            val record = RecEventRecord(
                sessionId = sessionId,
                event = event.copy(
                    clientTs = isoTimestamp(now),
                    profileId = activeProfileId(),
                ),
            )

            val shouldFlush: Boolean
            synchronized(queueLock) {
                // Drop-oldest: a device offline for a week must not grow without bound, and
                // recent behaviour is worth more to training than stale behaviour.
                while (queue.size >= MAX_QUEUED_EVENTS) queue.removeFirst()
                queue.addLast(record)
                shouldFlush = queue.size >= FLUSH_AT_EVENTS
            }
            if (BuildConfig.IS_DEBUG_BUILD) {
                Log.d(
                    TAG,
                    "queued ${record.event.eventType} ${record.event.itemId} " +
                        "row=${record.event.rowId}#${record.event.itemPosition} " +
                        "type=${record.event.contentType} pct=${record.event.progressPct} " +
                        "profile=${record.event.profileId}",
                )
            }
            if (shouldFlush) scope.launch { flush("threshold") }
        } catch (e: Throwable) {
            Log.d(TAG, "Dropped event: ${e.message}")
        }
    }

    /** The session impressions should be deduped against. Does not extend the session. */
    fun currentSessionId(): String = identity.peekSessionId()

    /** Best-effort send. Single-flight: a concurrent caller simply returns. */
    fun requestFlush(reason: String) {
        if (!started) return
        runCatching { scope.launch { flush(reason) } }
    }

    /** Synchronous privacy boundary invoked by the settings opt-out callback. */
    private fun discardPendingEvents() {
        synchronized(queueLock) { queue.clear() }
        retryNotBeforeMs = 0L
        backoffMs = BACKOFF_START_MS
        persistQueue(emptyList())
    }

    private suspend fun flush(reason: String) {
        val now = System.currentTimeMillis()
        if (!settings.isActive(now) || now < retryNotBeforeMs) return
        if (!flushMutex.tryLock()) return
        try {
            val pending = synchronized(queueLock) { queue.toList() }
            if (pending.isEmpty()) return
            persistQueue(pending)
            if (!settings.isActive(System.currentTimeMillis())) {
                discardPendingEvents()
                return
            }

            // One request per session: the envelope carries a single session_id, and a batch that
            // survived a cold start can straddle two.
            for ((sessionId, records) in pending.groupBy { it.sessionId }) {
                for (chunk in records.chunked(FLUSH_AT_EVENTS)) {
                    if (!settings.isActive(System.currentTimeMillis())) {
                        discardPendingEvents()
                        return
                    }
                    val outcome = send(sessionId, chunk.map { it.event })
                    if (outcome == SendOutcome.RETRY) {
                        retryNotBeforeMs = System.currentTimeMillis() + backoffMs
                        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
                        Log.d(TAG, "Flush ($reason) deferred; retrying in ${backoffMs}ms")
                        return
                    }
                    if (BuildConfig.IS_DEBUG_BUILD) {
                        Log.d(TAG, "flush($reason) sent ${chunk.size} events -> $outcome")
                    }
                    drop(chunk)
                    if (outcome == SendOutcome.DISABLED) {
                        settings.suppressUntil(System.currentTimeMillis())
                        synchronized(queueLock) { queue.clear() }
                        persistQueue(emptyList())
                        Log.i(TAG, "Ingest disabled by backend; silent for 24h")
                        return
                    }
                }
            }
            backoffMs = BACKOFF_START_MS
            persistQueue(synchronized(queueLock) { queue.toList() })
        } catch (e: Throwable) {
            Log.d(TAG, "Flush ($reason) failed: ${e.message}")
        } finally {
            flushMutex.unlock()
        }
    }

    private fun drop(sent: List<RecEventRecord>) {
        synchronized(queueLock) {
            // Identity-based removal: new events may have been appended while the request was in
            // flight, and they must survive.
            for (record in sent) queue.remove(record)
        }
    }

    private enum class SendOutcome { ACCEPTED, DROP, RETRY, DISABLED }

    private suspend fun send(sessionId: String, events: List<RecEvent>): SendOutcome {
        val backend = supabaseProvider.selectedBackend
        // A backend with no URL or key is a configuration state, not a transient fault: retrying
        // it forever would spin the queue and the backoff timer for nothing.
        if (backend.normalizedSupabaseUrl.isBlank() || backend.anonKey.isBlank()) {
            Log.d(TAG, "No sync backend configured; discarding batch")
            return SendOutcome.DROP
        }
        val batch = RecEventBatch(
            deviceId = identity.deviceId(),
            sessionId = sessionId,
            app = APP_IDENTIFIER,
            appVersion = BuildConfig.VERSION_NAME.ifBlank { "dev" }.take(32),
            events = events,
        )
        val token = runCatching { supabaseProvider.auth.currentAccessTokenOrNull() }.getOrNull()
        val request = Request.Builder()
            .url("${backend.normalizedSupabaseUrl}/functions/v1/rec-events")
            .header("apikey", backend.anonKey)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .post(json.encodeToString(batch).toRequestBody("application/json".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> SendOutcome.ACCEPTED
                        response.code == 410 -> SendOutcome.DISABLED
                        response.code in 400..499 -> {
                            Log.w(
                                TAG,
                                "Batch rejected (${response.code}): ${response.body?.string().orEmpty()}",
                            )
                            SendOutcome.DROP
                        }
                        else -> SendOutcome.RETRY
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Send failed: ${e.message}")
                SendOutcome.RETRY
            }
        }
    }

    private fun persistQueue(records: List<RecEventRecord>) {
        runCatching {
            if (records.isEmpty()) {
                queueFile.delete()
            } else {
                queueFile.writeText(records.joinToString("\n") { json.encodeToString(it) })
            }
        }.onFailure { Log.d(TAG, "Queue persist failed: ${it.message}") }
    }

    private fun restoreQueue() {
        val file = queueFile
        if (!file.exists()) return
        val restored = runCatching {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> runCatching { json.decodeFromString<RecEventRecord>(line) }.getOrNull() }
        }.getOrElse { emptyList() }
        if (restored.isEmpty()) {
            file.delete()
            return
        }
        synchronized(queueLock) {
            for (record in restored) {
                while (queue.size >= MAX_QUEUED_EVENTS) queue.removeFirst()
                queue.addLast(record)
            }
        }
        Log.d(TAG, "Restored ${restored.size} unsent events")
    }

    private fun isoTimestamp(millis: Long): String = iso8601.get()!!.format(java.util.Date(millis))

    private companion object {
        // SimpleDateFormat is not thread-safe and log() is called from anywhere.
        val iso8601: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}

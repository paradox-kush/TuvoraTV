package com.nuvio.tv.core.livepause

import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.ProxyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// The HLS flow (playlist poll, each segment fetched once, resets, init segment) follows the idea of
// homelessbrian/NuvioPlusIPTV (GPL-3.0) LiveTvTimeshift.kt. Changed from the original: OkHttp through the
// app's stack instead of HttpURLConnection; CancellationException always rethrown; ONE capped reconnect
// rule (LiveCaptureReconnect) instead of an uncapped HLS refetch and a forever TS loop; every open mints
// a fresh link through the app's resolver, so a single-use link is never re-opened; one capture per
// player instance, confined to one coroutine, immutable snapshots out; free space is checked before every
// write; more than 3 sequence resets fail the capture.

/** What the loop does with what it has saved. */
sealed interface LiveRetention {
    /** A rewind buffer: keep about [keepMs], drop the oldest pieces, trim under disk pressure. */
    data class Trim(val keepMs: Long) : LiveRetention

    /** A recording: never drop a piece (EVENT playlist); no room means Failed(NO_SPACE) with the files kept. */
    data object KeepAll : LiveRetention
}

/**
 * The capture I/O (TS read, HLS poll with each segment fetched once, capped reconnect, link minting through the
 * budget, stall watchdog, free-space gate), shared by live pause ([OkHttpLiveCapture]) and recordings. The
 * files live in [store]'s directory (the root dir). [stop] finalises the playlist and keeps the files;
 * [dispose] cancels and deletes them.
 */
class LiveCaptureLoop(
    private val spec: LiveCaptureSpec,
    private val scope: CoroutineScope,
    private val store: FileLiveSegmentStore,
    private val clients: LiveCaptureClients,
    private val io: CoroutineDispatcher,
    private val monotonicMs: () -> Long,
    private val freeBytes: () -> Long,
    private val cleanupScope: CoroutineScope,
    private val retention: LiveRetention,
    /** Test seam: shortens the reconnect backoff (production: identity, so 2/4/8 s from [LiveCaptureReconnect]). */
    private val reconnectDelayMs: (Long) -> Long = { it },
) {
    private val mutableEvents = MutableSharedFlow<LiveCaptureEvent>(replay = 16, extraBufferCapacity = 64)
    val events: SharedFlow<LiveCaptureEvent> = mutableEvents

    private var job: Job? = null
    private var disposed = false
    private val ended = CompletableDeferred<Unit>()

    fun start() {
        if (job != null || disposed) return
        // invokeOnCompletion also fires for a coroutine cancelled before it ran.
        job = scope.launch(io) { run() }.also { it.invokeOnCompletion { ended.complete(Unit) } }
    }

    /**
     * Returns once the capture coroutine is over for any reason: stopped, disposed, failed (a Failed event was
     * emitted first) or the provider ended the stream (no event). A recording waits on this; the event flow
     * never completes.
     */
    suspend fun awaitEnd() = ended.await()

    fun dispose() {
        if (disposed) return
        disposed = true
        val running = job
        job = null
        // The files go on the cleanup scope: the caller (a ViewModel on Main) never touches the disk.
        cleanupScope.launch(io) {
            running?.cancelAndJoin()
            // A recording is the user's: dispose only ends the capture (finalised, playable); only a rewind
            // buffer is deleted.
            if (retention is LiveRetention.KeepAll) finalise() else store.deleteAll()
        }
    }

    /**
     * Ends the capture and keeps what is saved: the playlist is finalised with #EXT-X-ENDLIST (the recording is
     * complete and playable). The files stay; the caller owns them from here.
     */
    suspend fun stop() {
        if (disposed || stopped) return
        stopped = true
        val running = job
        job = null
        withContext(io) {
            running?.cancelAndJoin()
            finalise()
        }
    }

    private var stopped = false

    private fun finalise() {
        if (hlsPieces.isNotEmpty()) {
            store.writePlaylistAtomically(
                LocalHlsPlaylist.render(
                    hlsPieces, hlsTargetSec, hlsPieces.first().sequence,
                    ended = true, eventType = retention is LiveRetention.KeepAll, initSegment = hlsInit,
                ),
            )
        } else {
            segmenter.finish(ended = true)
        }
    }

    private class Stop(val reason: LivePauseFailure) : RuntimeException("live pause capture stopped")

    // ---- capture state (confined to the capture coroutine; the stall watchdog reads only lastProgress)
    private val lastProgress = AtomicLong(0L)
    private val reconnect = LiveCaptureReconnect(spec.singleUseLink)
    private var budgetBytes = 0L
    private var bufferedBytes = 0L
    private var readySent = false
    private var landed = 0
    private val segmenter = TsSegmenter(store, monotonicMs, keepAll = retention is LiveRetention.KeepAll, onPiece = ::onTsPiece)
    private val hlsPieces = ArrayList<LivePiece>()
    private var hlsTrimmedMs = 0L
    private var hlsSeq = 0L
    private var hlsTargetSec = 2
    private var hlsInit: String? = null
    private var hlsNextDiscontinuity = false
    private var variantBandwidth: Long? = null
    // One tracker for the whole capture: a reconnect re-reads the playlist but never re-fetches a segment.
    private val hlsTracker = HlsLiveSegmentTracker()
    private var finished = false

    private suspend fun run() {
        val requestedKeepMs = (retention as? LiveRetention.Trim)?.keepMs ?: LivePausePolicy.MIN_KEEP_MS
        val start = LivePausePolicy.diskStart(freeBytes(), spec.bitrateBps, requestedKeepMs)
        if (start !is LivePausePolicy.StartDecision.Start) {
            emit(LiveCaptureEvent.Failed(LivePauseFailure.NO_SPACE))
            return
        }
        // A recording is bounded by the free-space gate alone, never by a keep-window budget.
        budgetBytes = if (retention is LiveRetention.KeepAll) Long.MAX_VALUE else start.budgetBytes
        lastProgress.set(monotonicMs())
        try {
            coroutineScope {
                val main = launch { body() }
                val watchdog = launch {
                    // No new piece for max(3 x target, 30 s) on the MONOTONIC clock: the capture is stuck.
                    while (isActive) {
                        delay(WATCHDOG_TICK_MS)
                        if (monotonicMs() - lastProgress.get() > stallLimitMs()) {
                            main.cancelAndJoin()
                            emitFailed(LivePauseFailure.STALLED)
                            return@launch
                        }
                    }
                }
                main.join()
                watchdog.cancel()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun stallLimitMs(): Long = maxOf(3L * hlsTargetSec * 1000L, STALL_MIN_MS)

    private suspend fun body() {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                openAndCapture()
                if (finished) return
                // The stream ended or dropped without a thrown error: same rule as a failure.
                segmenter.reconnected()
                backoffOrStop()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (stop: Stop) {
            emitFailed(stop.reason)
        } catch (error: IOException) {
            emitFailed(if (isSpaceError(error)) LivePauseFailure.NO_SPACE else LivePauseFailure.PROVIDER_REFUSED)
        } catch (_: Exception) {
            emitFailed(LivePauseFailure.PROVIDER_REFUSED)
        }
    }

    private fun isSpaceError(error: IOException): Boolean =
        error.message?.contains("ENOSPC", ignoreCase = true) == true ||
            error.message?.contains("No space", ignoreCase = true) == true

    /** The one capped rule: Retry waits and goes round again; GiveUp stops the capture. */
    private suspend fun backoffOrStop() {
        when (val decision = reconnect.onDrop()) {
            is LiveCaptureReconnect.Decision.Retry -> delay(reconnectDelayMs(decision.delayMs))
            LiveCaptureReconnect.Decision.GiveUp ->
                throw Stop(if (landed == 0) LivePauseFailure.PROVIDER_REFUSED else LivePauseFailure.STALLED)
        }
    }

    // ---- one open of the stream

    private suspend fun openAndCapture() {
        // Every open mints: a single-use link is never re-opened, and the first open of one was already
        // spent by the player that just released it.
        if (!spec.linkBudget.tryAcquire()) {
            throw Stop(if (landed == 0) LivePauseFailure.PROVIDER_REFUSED else LivePauseFailure.STALLED)
        }
        val link = spec.minter.mint() ?: return
        val client = clients.clientFor(link) ?: throw Stop(LivePauseFailure.PROVIDER_REFUSED)
        val call = client.newCall(requestFor(link, link.url))
        withResponse(call) { response ->
            val source = response.body?.source() ?: return@withResponse
            if (isPlaylist(source)) {
                captureHls(client, link, response.request.url.toString(), source.readUtf8())
            } else {
                captureTs(source)
            }
        }
    }

    /**
     * Runs [block] on a successful response, closing it after; the call is cancelled the moment this
     * coroutine is (so a blocking body read ends). Null = the request failed or answered a non-2xx.
     */
    private suspend fun <T : Any> withResponse(call: Call, block: suspend (Response) -> T): T? = coroutineScope {
        // Cancelling this coroutine must end a blocking socket read: the child runs its finally on the
        // cancelling thread and cancels the call, which unblocks the read with an IOException.
        val canceller = launch(Dispatchers.Unconfined) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = try {
                call.execute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return@coroutineScope null
            }
            response.use { if (it.isSuccessful) block(it) else null }
        } finally {
            canceller.cancel()
        }
    }

    private fun isPlaylist(source: BufferedSource): Boolean =
        source.request(7) && source.buffer.rangeEquals(0, "#EXTM3U".encodeUtf8())

    // ---- MPEG-TS

    private suspend fun captureTs(source: BufferedSource) {
        val buffer = ByteArray(CHUNK)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = try {
                source.read(buffer)
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return
            }
            if (n < 0) return
            if (n == 0) continue
            ensureSpace(n.toLong())
            segmenter.write(buffer, 0, n)
        }
    }

    private fun onTsPiece(piece: LivePiece) {
        bufferedBytes += piece.bytes
        val durations = segmenter.state().pieces.map { it.durationMs }
        val drop = if (retention is LiveRetention.Trim) LivePausePolicy.piecesToDrop(durations, retention.keepMs) else 0
        if (drop > 0) dropTs(drop)
        pieceLanded(totalMs = segmenter.state().totalMs, trimmedMs = segmenter.state().trimmedMs)
    }

    private fun dropTs(count: Int) {
        val freed = segmenter.state().pieces.take(count).sumOf { it.bytes }
        segmenter.dropOldest(count)
        bufferedBytes = (bufferedBytes - freed).coerceAtLeast(0L)
    }

    // ---- HLS

    private suspend fun captureHls(client: OkHttpClient, link: LiveLink, url: String, firstText: String) {
        var playlistUrl = url
        var text = firstText
        val variants = parseHlsMaster(text, playlistUrl)
        if (variants.isNotEmpty()) {
            val pick = LivePausePolicy.pickVariant(variants, spec.maxCaptureBitrateBps) ?: throw Stop(LivePauseFailure.UNSUPPORTED_STREAM)
            variantBandwidth = pick.bandwidthBps
            playlistUrl = pick.uri
            text = fetchText(client, link, playlistUrl) ?: return
        }
        val tracker = hlsTracker
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val update = tracker.onPlaylist(text, playlistUrl)) {
                is HlsLiveSegmentTracker.Update.NewSegments -> fetchSegments(client, link, tracker, update.segments)
                is HlsLiveSegmentTracker.Update.SequenceReset -> {
                    if (tracker.resetCount > MAX_RESETS) throw Stop(LivePauseFailure.SEQUENCE_RESET)
                    fetchSegments(client, link, tracker, update.segments)
                }
                HlsLiveSegmentTracker.Update.Unchanged -> Unit
                is HlsLiveSegmentTracker.Update.Unsupported -> throw Stop(LivePauseFailure.UNSUPPORTED_STREAM)
            }
            if (tracker.ended) {
                // The provider ended the stream: what is saved stays playable; nothing more to fetch.
                finished = true
                return
            }
            delay((tracker.targetDurationMs / 2).coerceAtLeast(MIN_POLL_MS))
            text = fetchText(client, link, playlistUrl) ?: return
        }
    }

    private suspend fun fetchText(client: OkHttpClient, link: LiveLink, url: String): String? {
        val call = client.newCall(requestFor(link, url))
        return withResponse(call) { r -> r.body?.source()?.readUtf8() ?: "" }
    }

    private suspend fun fetchSegments(
        client: OkHttpClient,
        link: LiveLink,
        tracker: HlsLiveSegmentTracker,
        segments: List<HlsLiveSegmentTracker.MediaSegment>,
    ) {
        tracker.initSegmentUri?.takeIf { hlsInit == null }?.let { init ->
            val written = downloadWhole(client, link, init, "init.mp4", estimate = 0L)
            if (written != null) hlsInit = "init.mp4" else { hlsNextDiscontinuity = true }
        }
        for (segment in segments) {
            currentCoroutineContext().ensureActive()
            val extension = if (hlsInit != null) "m4s" else "ts"
            val name = "seg$hlsSeq.$extension"
            val estimate = LivePausePolicy.bytesFor(segment.durationMs, variantBandwidth ?: spec.bitrateBps)
            val bytes = downloadWhole(client, link, segment.uri, name, estimate)
            if (bytes == null) {
                // The segment is lost (the tracker will not offer it again): the playlist carries a gap.
                hlsNextDiscontinuity = true
                backoffOrStop()
                continue
            }
            val piece = LivePiece(hlsSeq, name, segment.durationMs, bytes, segment.discontinuity || hlsNextDiscontinuity)
            hlsNextDiscontinuity = false
            hlsSeq++
            bufferedBytes += bytes
            hlsPieces += piece
            hlsTargetSec = maxOf(hlsTargetSec, ceilSeconds(piece.durationMs), ceilSeconds(tracker.targetDurationMs))
            val drop = if (retention is LiveRetention.Trim) {
                LivePausePolicy.piecesToDrop(hlsPieces.map { it.durationMs }, retention.keepMs)
            } else {
                0
            }
            val dropped = if (drop > 0) ArrayList(hlsPieces.subList(0, drop)) else emptyList()
            repeat(dropped.size) { hlsPieces.removeAt(0) }
            hlsTrimmedMs += dropped.sumOf { it.durationMs }
            bufferedBytes = (bufferedBytes - dropped.sumOf { it.bytes }).coerceAtLeast(0L)
            // Playlist first, files after: the player never reads an entry whose file is gone.
            store.writePlaylistAtomically(
                LocalHlsPlaylist.render(hlsPieces, hlsTargetSec, hlsPieces.first().sequence, ended = false, eventType = retention is LiveRetention.KeepAll, initSegment = hlsInit),
            )
            dropped.forEach { store.delete(it.name) }
            pieceLanded(hlsPieces.sumOf { it.durationMs }, hlsTrimmedMs)
        }
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    /** Downloads one object to `<name>.part` and renames it; null = the fetch failed (nothing is left behind). */
    private suspend fun downloadWhole(client: OkHttpClient, link: LiveLink, url: String, name: String, estimate: Long): Long? {
        if (estimate > 0) ensureSpace(estimate)
        val call = client.newCall(requestFor(link, url))
        return try {
            withResponse(call) { r ->
                val source = r.body?.source() ?: throw IOException("empty body")
                store.writeWhole(name) { out ->
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        ensureSpaceBlocking(n.toLong())
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (stop: Stop) {
            throw stop
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (isSpaceError(error)) throw Stop(LivePauseFailure.NO_SPACE)
            null
        }
    }

    private fun requestFor(link: LiveLink, url: String): Request =
        Request.Builder().url(url).apply { link.headers.forEach { (k, v) -> header(k, v) } }.build()

    // ---- shared

    private fun ensureSpaceBlocking(next: Long) = ensureSpace(next)

    /** Checked before EVERY write: trim the oldest piece, or stop with NO_SPACE. */
    private fun ensureSpace(next: Long) {
        while (true) {
            when (LivePausePolicy.beforeWrite(freeBytes(), next, bufferedBytes, budgetBytes)) {
                LivePausePolicy.SpaceVerdict.WRITE -> return
                LivePausePolicy.SpaceVerdict.STOP_NO_SPACE -> throw Stop(LivePauseFailure.NO_SPACE)
                LivePausePolicy.SpaceVerdict.TRIM_OLDEST ->
                    // A recording never drops: no room is a failure, with everything saved so far kept.
                    if (retention is LiveRetention.KeepAll || !trimOne()) throw Stop(LivePauseFailure.NO_SPACE)
            }
        }
    }

    private fun trimOne(): Boolean {
        if (hlsPieces.size > 1) {
            val first = hlsPieces.removeAt(0)
            hlsTrimmedMs += first.durationMs
            bufferedBytes = (bufferedBytes - first.bytes).coerceAtLeast(0L)
            store.writePlaylistAtomically(
                LocalHlsPlaylist.render(hlsPieces, hlsTargetSec, hlsPieces.first().sequence, false, false, hlsInit),
            )
            store.delete(first.name)
            return true
        }
        if (segmenter.state().pieces.size > 1) {
            dropTs(1)
            return true
        }
        return false
    }

    private fun pieceLanded(totalMs: Long, trimmedMs: Long) {
        landed++
        reconnect.onProgress()
        spec.linkBudget.refill()
        lastProgress.set(monotonicMs())
        if (!readySent) {
            readySent = true
            emit(LiveCaptureEvent.Ready("file://" + store.playlistFile.absolutePath))
        }
        emit(LiveCaptureEvent.Window(totalMs, trimmedMs, bufferFull = trimmedMs > 0))
    }

    /** A recording that stops on a failure is still a playable recording: finalise it before announcing. */
    private fun emitFailed(reason: LivePauseFailure) {
        if (retention is LiveRetention.KeepAll) finalise()
        emit(LiveCaptureEvent.Failed(reason))
    }

    private fun emit(event: LiveCaptureEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_RESETS = 3
        const val MIN_POLL_MS = 500L
        const val STALL_MIN_MS = 30_000L
        const val WATCHDOG_TICK_MS = 1_000L
    }
}


package com.nuvio.tv.core.recordings.internal

import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingSaver
import com.nuvio.tv.core.recordings.api.SaveOutcome
import com.nuvio.tv.core.recordings.api.SaveRequest
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Saves a catch-up programme with OkHttp. Candidates are tried in order; the request headers are
 * the channel's own (they already carry the provider user-agent), nothing is invented. Writes
 * `target.part`, resumes it with a Range request when asked (restarting if the server answers 200),
 * and renames it to the target when whole.
 */
internal class AndroidRecordingSaver(
    private val isOnline: () -> Boolean,
    baseClient: OkHttpClient,
) : RecordingSaver {

    // The app's IPTV client (IPv4-first DNS, panel-tolerant TLS, redirects followed, NO unconditional
    // User-Agent), so the channel's own headers decide the UA. Same pool; only the read timeout differs:
    // a 30 s silence is a stall, and there is deliberately no whole-call limit (a recording streams for hours).
    private val client: OkHttpClient = baseClient.newBuilder()
        .readTimeout(RecordingStallPolicy.STALL_MS, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()


    override suspend fun save(req: SaveRequest, progress: (Long) -> Unit): SaveOutcome = withContext(Dispatchers.IO) {
        val target = File(req.targetPath)
        val part = File(req.targetPath + ".part")
        part.parentFile?.mkdirs()
        var best: SaveOutcome.Failed? = null
        for (url in req.candidates) {
            ensureActive()
            when (val r = attempt(url, req, part, progress)) {
                is Attempt.Finished -> {
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        return@withContext SaveOutcome.Failed(RecordingProblem.UNKNOWN, offline = false, writtenBytes = part.length(), rangeSupported = true)
                    }
                    return@withContext SaveOutcome.Done(target.length())
                }
                is Attempt.Stopped -> return@withContext r.failure // out of space: no other link will help
                is Attempt.Failure -> {
                    val f = r.failure
                    if (best == null || f.writtenBytes >= best.writtenBytes) best = f
                    // Offline: every other link fails the same way.
                    if (f.offline) break
                }
            }
        }
        best ?: SaveOutcome.Failed(RecordingProblem.NO_CATCH_UP, offline = false, writtenBytes = part.length(), rangeSupported = false)
    }

    private sealed interface Attempt {
        data object Finished : Attempt
        data class Failure(val failure: SaveOutcome.Failed) : Attempt
        data class Stopped(val failure: SaveOutcome.Failed) : Attempt
    }

    private suspend fun attempt(url: String, req: SaveRequest, part: File, progress: (Long) -> Unit): Attempt {
        // Resume only a partial that is known to come from THIS link; anything else would splice
        // bytes of a different file (another candidate, a re-minted link) into this one.
        val meta = ResumeMeta.read(part)
        var offset = if (req.resumeFromBytes > 0 && meta?.url == url) part.length() else 0L
        if (offset == 0L) { part.delete(); ResumeMeta.delete(part) }
        var restartedOnce = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val request = try {
                Request.Builder().url(url).apply {
                    req.headers.forEach { (k, v) ->
                        if (!k.equals("Range", true) && !k.equals("If-Range", true) && !k.equals("Accept-Encoding", true)) header(k, v)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        meta?.validator?.let { header("If-Range", it) }
                    }
                }.build()
            } catch (e: IllegalArgumentException) {
                // A link OkHttp cannot parse is a link the provider can't serve.
                return Attempt.Failure(SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, part.length(), false))
            }
            val call = client.newCall(request)
            val stall = RecordingStallPolicy(0)
            val clock = TimeSource.Monotonic.markNow()
            var stalledByWatchdog = false
            try {
                return coroutineScope {
                    // A blocking read ignores coroutine cancellation, so a sibling that waits for it
                    // closes the connection from another thread (invokeOnCompletion would only run
                    // after the blocked read had already returned).
                    val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
                    val watchdog = launch {
                        while (true) {
                            delay(WATCHDOG_TICK_MS)
                            val stalled = synchronized(stall) { stall.isStalled(clock.elapsedNow().inWholeMilliseconds) }
                            if (stalled) { stalledByWatchdog = true; call.cancel(); break }
                        }
                    }
                    try {
                        val response = call.execute()
                        response.use { resp ->
                            if (resp.code == 416 && offset > 0L && !restartedOnce) {
                                part.delete(); ResumeMeta.delete(part); offset = 0L; restartedOnce = true
                                return@coroutineScope null // go round again from zero
                            }
                            if (resp.code == 206 && offset > 0L && resp.header("Content-Range")?.let(::startOfRange) != offset) {
                                // Bytes that do not start where our partial ends: never append them.
                                part.delete(); ResumeMeta.delete(part); offset = 0L
                                return@coroutineScope null
                            }
                            if (!resp.isSuccessful) {
                                val kind = RecordingSaveClassifier.forHttp(resp.code, isOnline())
                                return@coroutineScope Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, part.length(), false))
                            }
                            val resumed = resp.code == 206 && offset > 0L &&
                                resp.header("Content-Range")?.let(::startOfRange) == offset
                            val rangeSupported = resumed || resp.header("Accept-Ranges")?.contains("bytes", true) == true
                            val body = resp.body ?: return@coroutineScope Attempt.Failure(
                                SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, part.length(), rangeSupported),
                            )
                            val expected = body.contentLength().takeIf { it >= 0 }?.let { if (resumed) offset + it else it }
                            if (!resumed) ResumeMeta.write(part, url, resp.header("ETag"), resp.header("Last-Modified"))
                            var written = if (resumed) offset else 0L
                            var sinceCheck = 0L
                            progress(written)
                            FileOutputStream(part, resumed).use { out ->
                                val buffer = ByteArray(64 * 1024)
                                body.byteStream().use { input ->
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buffer)
                                        if (n == -1) break
                                        out.write(buffer, 0, n)
                                        written += n
                                        sinceCheck += n
                                        synchronized(stall) { stall.onProgress(clock.elapsedNow().inWholeMilliseconds) }
                                        progress(written)
                                        if (sinceCheck >= SPACE_CHECK_BYTES) {
                                            sinceCheck = 0
                                            val problem = req.spaceCheck?.invoke(SPACE_CHECK_BYTES)
                                            if (problem != null) {
                                                return@coroutineScope Attempt.Stopped(SaveOutcome.Failed(problem, false, written, rangeSupported))
                                            }
                                        }
                                    }
                                }
                                out.fd.sync()
                            }
                            when {
                                written == 0L -> Attempt.Failure(SaveOutcome.Failed(RecordingProblem.PROVIDER_REFUSED, false, 0, rangeSupported))
                                expected != null && written != expected -> {
                                    val kind = RecordingSaveClassifier.forIo(null, stalled = true, online = isOnline())
                                    Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, written, rangeSupported))
                                }
                                else -> { ResumeMeta.delete(part); Attempt.Finished }
                            }
                        }
                    } finally {
                        watchdog.cancel()
                        canceller.cancel()
                    }
                } ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // A closed call after our own cancel() is a cancellation, not a failure.
                currentCoroutineContext().ensureActive()
                val kind = RecordingSaveClassifier.forIo(e, stalledByWatchdog, isOnline())
                return Attempt.Failure(SaveOutcome.Failed(kind.problem, kind.offline, part.length(), offset > 0L || part.length() > 0))
            }
        }
    }

    private fun startOfRange(header: String): Long? =
        Regex("bytes (\\d+)-").find(header.trim())?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        const val WATCHDOG_TICK_MS = 5_000L
        const val SPACE_CHECK_BYTES = 16L * 1024 * 1024
    }
}

internal data class ResumeMeta(val url: String, val etag: String?, val lastModified: String?) {
    val validator: String? get() = etag?.takeIf { !it.startsWith("W/") } ?: lastModified

    companion object {
        private fun file(part: File) = File(part.path + ".meta")

        fun write(part: File, url: String, etag: String?, lastModified: String?) {
            val text = listOf(url, etag.orEmpty(), lastModified.orEmpty()).joinToString("\n")
            val tmp = File(part.path + ".meta.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file(part))) { file(part).writeText(text); tmp.delete() }
        }

        fun read(part: File): ResumeMeta? {
            val lines = try { file(part).takeIf { it.isFile }?.readText()?.split("\n") } catch (e: IOException) { null } ?: return null
            val url = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
            return ResumeMeta(url, lines.getOrNull(1)?.takeIf { it.isNotBlank() }, lines.getOrNull(2)?.takeIf { it.isNotBlank() })
        }

        fun delete(part: File) { file(part).delete() }
    }
}

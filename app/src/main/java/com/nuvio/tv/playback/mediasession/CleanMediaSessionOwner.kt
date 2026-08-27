package com.nuvio.tv.playback.mediasession

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.nuvio.tv.playback.ui.PlaybackSessionController
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns Android's system media-control surface for exactly one clean playback session.
 *
 * V1 is a live play/pause/stop foundation. It deliberately advertises no seeking, queue editing,
 * playback speed, volume, video Surface, VOD, or catch-up transport contract.
 */
@UnstableApi
class CleanMediaSessionOwner private constructor(
    private val applicationLooper: Looper,
    private val controller: PlaybackSessionController,
    private val player: CleanMediaSessionPlayer,
    private val mediaSession: MediaSession,
) {
    private val releaseMutex = Mutex()

    @Volatile
    private var released = false

    /** Updates display metadata only; the facade still contains no media URI or request headers. */
    fun updateMetadata(metadata: CleanMediaSessionMetadata) {
        check(!released) { "MediaSession owner is released" }
        player.updateMetadata(metadata)
    }

    /**
     * Stops accepting system commands, releases Media3 session resources, then waits for the clean
     * session's affirmative provider/engine release barrier. Safe to call repeatedly.
     */
    suspend fun release() = releaseMutex.withLock {
        if (released) return@withLock
        try {
            onApplicationLooper {
                try {
                    mediaSession.release()
                } finally {
                    player.release()
                }
            }
        } finally {
            controller.release()
            released = true
        }
    }

    private suspend fun onApplicationLooper(block: () -> Unit) {
        if (Looper.myLooper() == applicationLooper) {
            block()
            return
        }
        suspendCancellableCoroutine { continuation ->
            val posted = Handler(applicationLooper).post {
                runCatching(block)
                    .onSuccess { if (continuation.isActive) continuation.resume(Unit) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }
            if (!posted && continuation.isActive) continuation.resumeWithException(
                IllegalStateException("MediaSession application looper is shutting down"),
            )
        }
    }

    companion object {
        private val nextSessionId = AtomicLong(1)

        /** Must be called on [applicationLooper], matching Media3's single-application-thread API. */
        fun create(
            context: Context,
            applicationLooper: Looper,
            parentScope: CoroutineScope,
            controller: PlaybackSessionController,
            metadata: CleanMediaSessionMetadata,
        ): CleanMediaSessionOwner {
            check(Looper.myLooper() == applicationLooper) {
                "Create the clean MediaSession on its application looper"
            }
            val player = CleanMediaSessionPlayer(
                applicationLooper = applicationLooper,
                parentScope = parentScope,
                controller = controller,
                metadata = metadata,
            )
            val mediaSession = try {
                MediaSession.Builder(context.applicationContext, player)
                    .setId("clean-playback-${nextSessionId.getAndIncrement()}")
                    .build()
            } catch (error: Exception) {
                player.release()
                throw error
            }
            return CleanMediaSessionOwner(
                applicationLooper = applicationLooper,
                controller = controller,
                player = player,
                mediaSession = mediaSession,
            )
        }
    }
}

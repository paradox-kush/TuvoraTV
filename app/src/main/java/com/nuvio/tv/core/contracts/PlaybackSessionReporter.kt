package com.nuvio.tv.core.contracts

import kotlinx.coroutines.CancellationException

/** How the server is delivering the stream (media servers report it; others leave it unset). */
enum class PlaybackPlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/**
 * What the player knows about the playback a reporter is told about. [playMethod] is null when the player does
 * not know it - a reporter that minted the stream fills in the method it chose.
 */
data class PlaybackSessionState(
    val videoId: String,
    val parentMetaId: String,
    val providerAddonId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val playMethod: PlaybackPlayMethod? = null,
)

/**
 * A source that wants to know how a playback of ITS content goes - a media server keeping its own
 * watched/resume state in step (start / progress / stop reports). Neutral: it does not widen the tracking
 * providers (Trakt/Simkl/MDBList) and is fed from the player's own call sites without a TMDB identity.
 * Cadence (progress every ~15 s playing, ~60 s paused) and double-scrobble rules belong to the reporter's own
 * pure policy - the player just announces start / progress(paused) / stop.
 */
interface PlaybackSessionReporter {
    val name: String

    /** Cheap, synchronous ownership check; plays of other sources are never forwarded. */
    fun handles(videoId: String, providerAddonId: String?): Boolean

    suspend fun onStart(session: PlaybackSessionState)
    suspend fun onProgress(session: PlaybackSessionState, paused: Boolean)
    suspend fun onStop(session: PlaybackSessionState)
}

object PlaybackSessionReporterRegistry {
    private val reporters = NamedRegistry<PlaybackSessionReporter>("PlaybackSessionReporter")

    fun register(reporter: PlaybackSessionReporter) = reporters.register(reporter.name, reporter)

    val all: List<PlaybackSessionReporter> get() = reporters.all

    val isEmpty: Boolean get() = reporters.isEmpty

    fun handlersFor(videoId: String, providerAddonId: String?): List<PlaybackSessionReporter> =
        all.filter { it.handles(videoId, providerAddonId) }

    suspend fun start(session: PlaybackSessionState) = dispatch(session) { it.onStart(session) }

    suspend fun progress(session: PlaybackSessionState, paused: Boolean) = dispatch(session) { it.onProgress(session, paused) }

    suspend fun stop(session: PlaybackSessionState) = dispatch(session) { it.onStop(session) }

    /** One reporter failing must neither break playback nor starve the others. */
    private suspend fun dispatch(session: PlaybackSessionState, call: suspend (PlaybackSessionReporter) -> Unit) {
        handlersFor(session.videoId, session.providerAddonId).forEach { reporter ->
            try {
                call(reporter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // a reporter's own failure is its own to log; the player carries on
            }
        }
    }

    fun resetForTest() = reporters.resetForTest()
}

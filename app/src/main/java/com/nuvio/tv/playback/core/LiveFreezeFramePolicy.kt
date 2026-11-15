package com.nuvio.tv.playback.core

/**
 * Owner rule (2026-10-03): live TV never shows a black screen.
 *
 * A reconnect or handoff tears the engine and its surface down, mpv blanks its output when a stream
 * ends, and Amlogic can blank the video plane when a decoder is released — so keeping the surface
 * alone cannot guarantee a picture. The host copies the outgoing frame at an interruption boundary
 * and holds it until a new frame is drawn. Periodic surface readback can stall hardware decoders.
 */
object LiveFreezeFramePolicy {
    enum class Overlay {
        /** Real video is on screen. */
        HIDDEN,

        /** The picture is interrupted: hold the last frame. */
        FROZEN,

        /** Terminal failure: keep the frame, dimmed under the error. */
        DIMMED,

        /** Nothing has played in this session: drop any frame. */
        CLEARED,
    }

    fun overlay(snapshot: PlaybackSnapshot): Overlay = when {
        // STOPPED is also a suspend (Home, then back): hold the frame for the resume. Really leaving
        // disposes the host, which removes the frame with it.
        snapshot.state == PlaybackState.IDLE -> Overlay.CLEARED
        snapshot.state == PlaybackState.FAILED -> Overlay.DIMMED
        showsRealVideo(snapshot) -> Overlay.HIDDEN
        else -> Overlay.FROZEN
    }

    /**
     * A held frame belongs to the one interruption it was captured for. Once real video is back
     * (HIDDEN) or nothing has played (CLEARED) it is released, so it can never cover a later stall
     * or another channel (emulator 2026-11-15: a stall on channel 1 showed channel 2's 24 s old
     * picture). Every interruption captures its own frame at its boundary; a capture that fails or
     * is skipped then leaves no frame rather than an older one.
     */
    fun keepsHeldFrame(overlay: Overlay): Boolean = overlay == Overlay.FROZEN || overlay == Overlay.DIMMED

    /** Only capture a picture that is actually moving: never a stalled, recovering or blank surface. */
    fun mayCapture(snapshot: PlaybackSnapshot): Boolean =
        showsRealVideo(snapshot) && snapshot.isPlaying && !snapshot.isReconnecting

    private fun showsRealVideo(snapshot: PlaybackSnapshot): Boolean =
        snapshot.state in VIDEO_STATES && snapshot.progress.renderedVideoFrame && !snapshot.isBuffering

    private val VIDEO_STATES = setOf(PlaybackState.PLAYING, PlaybackState.DEGRADED)
}

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

    /** Why the engine is tearing the picture down, as only the engine knows it at its boundary. */
    enum class Interruption {
        /** Reconnect, recovery, engine handoff, suspend or a full release: the same channel returns. */
        SAME_CHANNEL,

        /** The source is replaced by another channel (the session's REPLACE_REQUEST release). */
        CHANNEL_CHANGE,
    }

    /**
     * Owner decision (2026-11-15): "channel switch we dont care" — a moment of black on a zap is
     * fine, a picture of the old channel on the new one is not (emulator: a stall on channel 1
     * showed channel 2's frame from 24 s earlier). A channel change therefore captures nothing and
     * drops any frame still held; every same-channel interruption captures and holds as before.
     */
    fun holdsFrameAcross(interruption: Interruption): Boolean = interruption == Interruption.SAME_CHANNEL

    fun overlay(snapshot: PlaybackSnapshot): Overlay = when {
        // STOPPED is also a suspend (Home, then back): hold the frame for the resume. Really leaving
        // disposes the host, which removes the frame with it.
        snapshot.state == PlaybackState.IDLE -> Overlay.CLEARED
        snapshot.state == PlaybackState.FAILED -> Overlay.DIMMED
        showsRealVideo(snapshot) -> Overlay.HIDDEN
        else -> Overlay.FROZEN
    }

    /** Only capture a picture that is actually moving: never a stalled, recovering or blank surface. */
    fun mayCapture(snapshot: PlaybackSnapshot): Boolean =
        showsRealVideo(snapshot) && snapshot.isPlaying && !snapshot.isReconnecting

    private fun showsRealVideo(snapshot: PlaybackSnapshot): Boolean =
        snapshot.state in VIDEO_STATES && snapshot.progress.renderedVideoFrame && !snapshot.isBuffering

    private val VIDEO_STATES = setOf(PlaybackState.PLAYING, PlaybackState.DEGRADED)
}

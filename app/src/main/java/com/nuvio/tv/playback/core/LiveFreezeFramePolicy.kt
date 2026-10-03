package com.nuvio.tv.playback.core

/**
 * Owner rule (2026-10-03): live TV never shows a black screen.
 *
 * A reconnect or handoff tears the engine and its surface down, mpv blanks its output when a stream
 * ends, and Amlogic can blank the video plane when a decoder is released — so keeping the surface
 * alone cannot guarantee a picture. The host keeps a recent copy of the frame while video is really
 * playing and shows it whenever the picture is interrupted, until a new frame is drawn.
 */
object LiveFreezeFramePolicy {
    enum class Overlay {
        /** Real video is on screen. */
        HIDDEN,

        /** The picture is interrupted: hold the last frame. */
        FROZEN,

        /** Terminal failure: keep the frame, dimmed under the error. */
        DIMMED,

        /** Playback ended by the viewer: drop the frame. */
        CLEARED,
    }

    fun overlay(snapshot: PlaybackSnapshot): Overlay = when {
        snapshot.state == PlaybackState.IDLE || snapshot.state == PlaybackState.STOPPED -> Overlay.CLEARED
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

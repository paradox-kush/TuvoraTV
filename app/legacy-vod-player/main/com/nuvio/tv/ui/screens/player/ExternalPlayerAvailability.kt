package com.nuvio.tv.ui.screens.player

/**
 * Whether the "open in external player" action is offered. A media-server stream is a session URL that
 * carries the server's own auth headers and possibly a transcode session, which an external app cannot
 * reuse, so those streams stay in the in-app player (upstream 5.x: hide the button for server streams).
 */
internal object ExternalPlayerAvailability {
    fun canOpen(isServerStream: Boolean): Boolean = !isServerStream
}

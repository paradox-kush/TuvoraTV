package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.HealthStatus

/** What the server list shows for one entry (design 5.4 / 5.8: signed in / sign in again / offline). */
internal enum class ServerStatus {
    /** Reachable and the token is good. */
    SIGNED_IN,

    /** The server refused this device's token (revoked / password changed): the entry stays, the device signs in again. */
    SIGN_IN_AGAIN,

    /** An entry that arrived from another device (or lost its token): this device has never signed in. */
    NEEDS_SIGN_IN,

    /** Signed in, but the server did not answer. Never retried in a loop - the badge is refreshed when the page is opened. */
    OFFLINE,

    /** The user switched the server off. */
    DISABLED,
}

internal object MediaServerStatusPolicy {
    /**
     * [signedIn]: a token is stored on this device. [expired]: the client layer recorded a 401/403. [health]: the
     * last (lazy) reachability + token check, null = not asked yet (the row shows its token-based state meanwhile).
     */
    fun of(entry: MediaServerEntry, signedIn: Boolean, expired: Boolean, health: HealthStatus?): ServerStatus = when {
        !entry.enabled -> ServerStatus.DISABLED
        expired || health == HealthStatus.AUTH_ERROR && signedIn -> ServerStatus.SIGN_IN_AGAIN
        !signedIn || entry.address.isNullOrBlank() -> ServerStatus.NEEDS_SIGN_IN
        health == HealthStatus.OFFLINE -> ServerStatus.OFFLINE
        else -> ServerStatus.SIGNED_IN
    }

    /** Whether the row has something to do with a token at all - i.e. whether a health check can be asked for it. */
    fun canCheckHealth(entry: MediaServerEntry, signedIn: Boolean): Boolean =
        entry.enabled && signedIn && !entry.address.isNullOrBlank()
}

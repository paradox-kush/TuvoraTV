package com.nuvio.tv.data.repository

import com.nuvio.tv.core.contracts.OwnSourcePolicy

/**
 * Which items a tracking add-on may be told about (twin of the KMP `AddonEventPolicy`). Pure.
 * An IPTV / media-server id says what the user watches in a source that is theirs alone; it never leaves
 * the device through a tracker, consented or not. Scrobble-excluded ids stay out for the same reason.
 */
internal object AddonEventPolicy {
    fun mayDescribe(
        id: String,
        isServerId: (String) -> Boolean,
        isOwnContentId: (String) -> Boolean = { OwnSourcePolicy.isOwnContentId(it) },
        isTrackingExcluded: (String) -> Boolean = { OwnSourcePolicy.isExcludedFromTrackingScrobble(it) },
    ): Boolean = id.isNotBlank() && !isServerId(id) && !isOwnContentId(id) && !isTrackingExcluded(id)
}

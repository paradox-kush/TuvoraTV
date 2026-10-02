package com.nuvio.tv.core.sync

/** What the Addons screen says about whether the device's list has reached the account (UX71). */
enum class AddonSyncStatus {
    /** Nothing to say: no account to sync to, or this profile shows the primary profile's addons. */
    NotApplicable,

    /** The list matches what this device and the account last agreed on. */
    Synced,

    /** A push or retry is on its way; the marker stays hidden so an edit doesn't flash it. */
    Syncing,

    /** Local edits differ from the last-synced list (skipped or failed push): show "Not synced yet" + retry. */
    NotSynced
}

/**
 * Decides the "Not synced yet" marker on the Addons screen. KMP twin: AddonSyncStatusPolicy in NuvioMobile.
 *
 * [lastSynced] is the [AddonSyncMerge] baseline — the list this device and the server last agreed on (on TV the
 * factory defaults until the first sync, as in the merge). Order counts: a reorder is pushed (`sort_order`), so an
 * unpushed reorder is unsynced. [key] identifies the same addon across URL spellings, as in [AddonSyncMerge].
 */
object AddonSyncStatusPolicy {
    fun status(
        local: List<String>,
        lastSynced: List<String>?,
        hasAccount: Boolean,
        followsPrimaryProfile: Boolean,
        syncInFlight: Boolean,
        key: (String) -> String = { it }
    ): AddonSyncStatus {
        if (!hasAccount || followsPrimaryProfile) return AddonSyncStatus.NotApplicable
        if (syncInFlight) return AddonSyncStatus.Syncing
        val localKeys = local.map(key).distinct()
        val syncedKeys = lastSynced?.map(key)?.distinct()
        return when {
            syncedKeys == null && localKeys.isEmpty() -> AddonSyncStatus.Synced
            localKeys == syncedKeys -> AddonSyncStatus.Synced
            else -> AddonSyncStatus.NotSynced
        }
    }
}

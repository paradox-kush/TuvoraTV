package com.nuvio.tv.core.sync

/**
 * Decides the addon list a device should hold after pulling the server's list.
 *
 * Addon edits are pushed as they happen, but a push can be skipped (no live session: signed out,
 * "continue without account", a token gap) or fail (offline). Before this, the next pull simply
 * replaced the device's list with the server's, so those edits were silently lost — a field report
 * had a whole desktop addon set never reach the account, and the TV signed in to an empty list.
 *
 * The merge is three-way. [lastSynced] is the list this device and the server last agreed on
 * (written after every successful pull or push, wiped with the rest of the account data on sign
 * out; a TV that never synced passes its factory-default addons, since that is what it started with):
 *  * no local edits since then → the server wins outright, including an empty server, so deleting
 *    every addon on one device still sticks on the others;
 *  * addons added locally since then → appended after the server's list;
 *  * addons removed locally since then → dropped from the server's list;
 *  * `null` (this device never synced: first sign-in after using the app without an account, or an
 *    install from before this existed) → nothing is known to have been removed, so the result is
 *    the server's list plus the device's extras — never a loss.
 *
 * The server's order is kept; local additions go at the end. [key] identifies the same addon
 * across URL spellings. Plugin repositories sync the same way and reuse this merge.
 */
object AddonSyncMerge {

    data class Outcome(
        /** The list the device should now hold, in order. */
        val urls: List<String>,
        /** True when [urls] differs from the server's list, so it must be pushed. */
        val pushNeeded: Boolean,
    )

    fun merge(
        local: List<String>,
        remote: List<String>,
        lastSynced: List<String>?,
        key: (String) -> String = { it },
    ): Outcome {
        val remoteDistinct = remote.distinctBy(key)
        val remoteKeys = remoteDistinct.map(key)
        val remoteKeySet = remoteKeys.toSet()
        val localDistinct = local.distinctBy(key)
        val localKeySet = localDistinct.map(key).toSet()
        val syncedKeySet = lastSynced.orEmpty().map(key).toSet()

        val removedLocally = if (lastSynced == null) emptySet() else syncedKeySet - localKeySet
        val addedLocally = localDistinct.filter { url ->
            val k = key(url)
            k !in syncedKeySet && k !in remoteKeySet
        }

        val merged = remoteDistinct.filter { key(it) !in removedLocally } + addedLocally
        return Outcome(
            urls = merged,
            pushNeeded = merged.map(key) != remoteKeys,
        )
    }
}

package com.nuvio.tv.core.mediaserver

import com.nuvio.tv.core.mediaserver.source.MediaServerItemRegistry
import com.nuvio.tv.core.mediaserver.source.MediaServerPlaybackSessions
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sign-out / profile delete / "make this my main profile" for the media-server feature, through the same set
 * ([ProfileScopedCredentialStore]) the other per-profile credential stores use, so no new call lands in the
 * baselined account-reset service. Server entries (addresses + user ids), this device's sign-in tokens and the
 * pinned certificates are device state that must not outlive the account (design 5.3).
 */
@Singleton
internal class MediaServerAccountCleaner @Inject constructor(
    // Lazy: the profile manager injects the whole credential-store set, and the runtime needs the profile manager.
    private val runtimeProvider: dagger.Lazy<MediaServerRuntime>,
) : ProfileScopedCredentialStore {
    private val runtime: MediaServerRuntime get() = runtimeProvider.get()

    override fun removeProfile(profileId: Int) {
        // Tokens are device-wide per server+user; one is dropped only when no OTHER profile still names that server+user.
        val store = runtime.entryStore
        val doomed = store.entriesOf(profileId)
        store.clearProfile(profileId)
        doomed.forEach { entry ->
            val stillUsed = store.profilesReferencing(runtime.allProfileIds.filter { it != profileId }, entry.type, entry.machineId, entry.userId).isNotEmpty()
            if (!stillUsed) runtime.services.credentials.remove(entry.serverKey)
        }
        runtime.services.notifyCredentialsChanged()
    }

    override fun clearAllProfiles() {
        runtime.entryStore.clearAll(runtime.allProfileIds)
        runtime.services.credentials.clearAll()
        runtime.trust.clearAll()
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
        runtime.homeContributor?.resetForProfile()
        runtime.services.notifyCredentialsChanged()
    }

    override fun swapProfiles(a: Int, b: Int) {
        // The entries ride the account sync with their profile: whichever way the account swaps its rows, the next
        // sync's pull re-applies them, so nothing local is moved here - only what was cached for the old arrangement.
        MediaServerItemRegistry.reset()
        MediaServerPlaybackSessions.reset()
        runtime.homeContributor?.resetForProfile()
    }
}

package com.nuvio.tv.core.mediaserver

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.mediaserver.api.MediaServerSyncSink
import com.nuvio.tv.core.mediaserver.client.AuthorityRoutingHttp
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.flow.MediaServerManagement
import com.nuvio.tv.core.mediaserver.client.MediaServerTrust
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserClientIdentity
import com.nuvio.tv.core.mediaserver.source.MediaServerHomeContributor
import com.nuvio.tv.core.mediaserver.store.MediaServerCredentialStore
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.core.mediaserver.store.MediaServerStorage
import com.nuvio.tv.core.mediaserver.store.PlatformEntriesPersistence
import com.nuvio.tv.core.mediaserver.store.PlatformSecureTokenStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.WatchStatePrefixMover
import com.nuvio.tv.data.local.LibraryPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The media-server feature's collaborators, assembled once per process by Hilt (the KMP twins build a global
 * `production` object instead): the entry store, the client services and the clock. Registrations and the
 * screens take what they need from here. Nothing in it is reachable from upstream-aligned code - that meets
 * only the neutral ports in `core/contracts`.
 */
@Singleton
internal class MediaServerRuntime @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val profileManager: ProfileManager,
    // Lazy: this runtime is reachable from the sign-out cleanup set (ProfileScopedCredentialStore), which the account
    // manager injects - an eager WatchStatePrefixMover (which injects the account manager) would close a cycle.
    private val watchState: dagger.Lazy<WatchStatePrefixMover>,
    private val libraryPreferences: dagger.Lazy<LibraryPreferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncSink = AtomicReference<MediaServerSyncSink?>(null)

    /** The composition root hands over how local changes reach the playlist-sync engine (null = device-local). */
    fun installSyncSink(sink: MediaServerSyncSink?) = syncSink.set(sink)

    val nowMs: () -> Long = { System.currentTimeMillis() }

    val trust: MediaServerTrust by lazy {
        MediaServerStorage.initialize(appContext)
        MediaServerTrust.shared
    }

    val credentials: MediaServerCredentialStore by lazy {
        PlatformSecureTokenStore.initialize(appContext)
        MediaServerCredentialStore(PlatformSecureTokenStore)
    }

    val services: MediaServerServices by lazy {
        MediaServerServices(
            http = AuthorityRoutingHttp(trust),
            credentials = credentials,
            identityProvider = {
                MediaBrowserClientIdentity(
                    client = "Tuvora",
                    device = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV",
                    deviceId = credentials.deviceId(),
                    version = BuildConfig.VERSION_NAME,
                )
            },
            nowMs = nowMs,
        )
    }

    val entryStore: MediaServerEntryStore by lazy {
        MediaServerStorage.initialize(appContext)
        MediaServerEntryStore(
            persistence = PlatformEntriesPersistence,
            sink = { syncSink.get() },
            activeProfileId = { profileManager.activeProfileId.value },
            migrateSavedData = { oldPrefix, newPrefix ->
                // The same fan-out an IPTV playlist edit uses: watch progress + watched marks (and their sync ops), then Library.
                val profileId = profileManager.activeProfileId.value
                scope.launch {
                    runCatching { watchState.get().move(profileId, oldPrefix, newPrefix) }
                    runCatching { libraryPreferences.get().migrateIdPrefix(oldPrefix, newPrefix) }
                }
            },
        )
    }

    val accounts: MediaServerAccounts by lazy { MediaServerAccounts(entryStore, services, allProfileIds) }

    /** Bumped on every change a screen made to a server (Home rows, the Home layout list and search re-read on it). */
    val changeVersion = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** A server was added / signed in / edited / removed: its Home rows are stale and observers re-read. */
    fun notifyChanged(entry: com.nuvio.tv.core.mediaserver.api.MediaServerEntry) {
        homeContributor?.invalidate(entry.sourceKey)
        changeVersion.value = changeVersion.value + 1
    }

    val management: MediaServerManagement by lazy { MediaServerManagement(accounts, services, onChanged = ::notifyChanged) }

    /** The row status of [entry] from what this device knows (the list's lazy health check refines it). */
    fun entryStatus(entry: com.nuvio.tv.core.mediaserver.api.MediaServerEntry, expired: Set<String>): com.nuvio.tv.core.mediaserver.flow.ServerStatus =
        com.nuvio.tv.core.mediaserver.flow.MediaServerStatusPolicy.of(entry, services.isSignedIn(entry), entry.serverKey in expired, null)

    /** The Home contributor the registrations created (the screens invalidate it after a change). Null until registered. */
    @Volatile
    var homeContributor: MediaServerHomeContributor? = null

    val allProfileIds: Iterable<Int> get() = 1..ProfileManager.MAX_PROFILES
}

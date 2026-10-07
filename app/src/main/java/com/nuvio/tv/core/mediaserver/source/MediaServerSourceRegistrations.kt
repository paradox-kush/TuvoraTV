package com.nuvio.tv.core.mediaserver.source

import android.content.Context
import com.nuvio.tv.core.contracts.HomeSectionContributorRegistry
import com.nuvio.tv.core.contracts.MetaSourceRegistry
import com.nuvio.tv.core.contracts.OwnSourcePolicy
import com.nuvio.tv.core.contracts.OwnSourceSubtitleRegistry
import com.nuvio.tv.core.contracts.PlaybackResumeOfferRegistry
import com.nuvio.tv.core.contracts.PlaybackSessionReporterRegistry
import com.nuvio.tv.core.contracts.SearchProviderRegistry
import com.nuvio.tv.core.contracts.StreamSourceRegistry
import com.nuvio.tv.core.mediaserver.MediaServerRuntime
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.store.MediaServerEntryStore
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.WatchProgressPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import com.nuvio.tv.core.mediaserver.source.MediaServerMetaSource
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.launch

/**
 * Media servers register into every plural source port as ONE entry each (design 5.1) under the name
 * `mediaserver`, plus the own-source id predicates (`ms:` content ids; `ms` / `ms-match:` provider ids), the Home
 * contributor and the playback-session reporter. The one place that lists them. Called once per process from
 * NuvioApplication (a duplicate name is refused by the registries); idempotent against a second call.
 */
@Singleton
class MediaServerSourceRegistrations @Inject internal constructor(
    @ApplicationContext private val appContext: Context,
    private val runtime: MediaServerRuntime,
    private val profileManager: ProfileManager,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val tmdbEnricher: MediaServerTmdbEnricher,
    private val syncSink: com.nuvio.tv.core.iptv.PlaylistMediaServerSyncSink,
) {
    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun register() {
        if (!registered.compareAndSet(false, true)) return
        // Local entry changes reach the ONE playlist-sync engine through this sink (design 5.3).
        runtime.installSyncSink(syncSink)
        val store = runtime.entryStore
        val home = registerMediaServerSources(
            store = store,
            services = runtime.services,
            nowMs = runtime.nowMs,
            titles = ResourceMediaServerRowTitles(appContext),
            enrich = { m, id -> tmdbEnricher.enrich(m, id) },
            tuvoraContinueWatchingIds = {
                // What Tuvora's own Continue Watching shows: the ids of unfinished entries (a server row repeating them is hidden).
                watchProgressPreferences.getAllRawEntries(profileManager.activeProfileId.value).values
                    .filter { !it.isCompleted() }
                    .flatMap { listOf(it.contentId, it.videoId) }
                    .toSet()
            },
            changes = runtime.changeVersion.drop(1).map { },
        )
        runtime.homeContributor = home
        // A profile switch reloads the entries and drops everything the previous profile's session cached.
        scope.launch {
            profileManager.activeProfileId.drop(1).collect { profileId ->
                store.onProfileChanged(profileId)
                MediaServerItemRegistry.reset()
                MediaServerPlaybackSessions.reset()
                home.resetForProfile()
            }
        }
    }

    companion object {
        const val NAME = "mediaserver"
    }
}

/**
 * The registration set itself, free of Android and Hilt so a test wires exactly what production does: every plural
 * source port gets ONE `mediaserver` entry, plus the own-source id predicates. Returns the Home contributor (the
 * screens invalidate it after a change). A duplicate name is refused by the registries.
 */
internal fun registerMediaServerSources(
    store: MediaServerEntryStore,
    services: MediaServerServices,
    nowMs: () -> Long,
    titles: MediaServerRowTitles,
    enrich: suspend (Meta, tmdbId: String) -> Meta = { meta, _ -> meta },
    tuvoraContinueWatchingIds: suspend () -> Set<String> = { emptySet() },
    changes: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
): MediaServerHomeContributor {
    val name = MediaServerSourceRegistrations.NAME
    val home = MediaServerHomeContributor(store, services, nowMs, tuvoraContinueWatchingIds, titles, changes)
    val meta = MediaServerMetaSource(store, services, enrich = enrich)
    StreamSourceRegistry.register(name, MediaServerStreamSourceProvider(store, services, ensureRegistered = { id -> meta.ensureStreamRegistered(id) }))
    MetaSourceRegistry.register(name, meta)
    SearchProviderRegistry.register(name, MediaServerSearchProvider(store, services, titles))
    OwnSourcePolicy.registerContentIdPredicate(name, MediaServerIds::isOwnContentId)
    OwnSourcePolicy.registerProviderIdPredicate(name, MediaServerIds::isOwnProviderId)
    // `ms:` ids embed the server's machine id and the user id: never to a third-party subtitle add-on.
    OwnSourcePolicy.registerSubtitleScopedPredicate(name, MediaServerIds::isContentId)
    OwnSourcePolicy.registerLinkCacheExclusion(name, MediaServerIds::isContentId)
    // v1: a server's own items are never scrobbled to Trakt/Simkl/MDBList (owner decision 2026-10-06).
    OwnSourcePolicy.registerScrobbleExclusion(name, MediaServerIds::isContentId)
    // ...and no event leaving the device may name the server or the user: telemetry gets the salted hash form.
    OwnSourcePolicy.registerTelemetryRewriter(name) { id, salt -> MediaServerIds.parse(id)?.let { MediaServerIds.telemetryId(it, salt) } }
    HomeSectionContributorRegistry.register(home)
    PlaybackResumeOfferRegistry.register(MediaServerResumeOffers(store, services))
    // its sidecar subtitles reach the player through the own-source subtitle port (add-ons are never asked about an ms: id)
    OwnSourceSubtitleRegistry.register(MediaServerSubtitleProvider(store, services))
    PlaybackSessionReporterRegistry.register(
        MediaServerSessionReporter(store, services, nowMs, onReported = { sourceKey -> home.invalidate(sourceKey) }),
    )
    return home
}

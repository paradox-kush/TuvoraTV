package com.nuvio.tv.data.repository

import com.nuvio.tv.core.diagnostics.LogRedaction
import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.streams.AddonSourcePolicy
import com.nuvio.tv.core.streams.withSourceQueryDeadline
import com.nuvio.tv.core.streams.PlaybackAvailability
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.data.mediaserver.messageRes
import com.nuvio.tv.data.mediaserver.serverFailure
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.core.iptv.isXtream
import com.nuvio.tv.core.iptv.rebuildFromId
import com.nuvio.tv.core.iptv.toAddonStreams
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.core.streams.supportsStreamResource
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

import com.nuvio.tv.core.plugin.resolvePluginSeasonEpisode
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerStreamSource
import com.nuvio.tv.domain.model.DebridSettings
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transformLatest
import java.security.MessageDigest

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val profileManager: ProfileManager,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val tmdbService: TmdbService,
    private val debridStreamPresentation: DebridStreamPresentation,
    private val localDebridAvailabilityService: LocalDebridAvailabilityService,
    private val xtreamRegistry: com.nuvio.tv.core.iptv.XtreamItemRegistry,
    private val iptvClientFactory: com.nuvio.tv.core.iptv.IptvClientFactory,
    private val xtreamAccountStore: com.nuvio.tv.data.local.XtreamAccountStore,
    private val xtreamStreamSource: com.nuvio.tv.core.iptv.match.XtreamStreamSource,
    private val serverStreams: ServerStreams,
    /** Step 0.3: moves a registered Xtream stream URL onto the playlist's ACTIVE server at play time. */
    private val serverFailover: com.nuvio.tv.core.iptv.PlaylistServerFailover =
        com.nuvio.tv.core.iptv.PlaylistServerFailover.detached(),
) : StreamRepository {
    // When paused, local (installed-plugin) stream search is skipped so an IPTV/direct play
    // does not also fire a redundant addon scrape. Fork-appropriate one-shot gate (checked at
    // search time) vs upstream's reactive flow — same intent, this codebase's semantics.
    private val streamSearchSessions = StreamSearchSessionCache(
        interruptedResult = { NetworkResult.Error(context.getString(R.string.stream_source_search_interrupted)) }
    )
    private val localPluginSearchPaused = MutableStateFlow(false)

    override fun setLocalPluginSearchPaused(paused: Boolean) {
        localPluginSearchPaused.value = paused
    }

    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    private data class StreamSourceConfigurationSnapshot(
        val profileId: Int,
        val addons: List<Addon>,
        val pendingAddonUrls: Set<String>,
        val pluginsEnabled: Boolean,
        val enabledScrapers: List<ScraperInfo>,
        val groupPluginsByRepository: Boolean,
        val pluginRepositories: List<PluginRepository>,
        val debridSettings: DebridSettings
    )

    /**
     * Build the single direct-stream group for an xtream/registry item at PLAY time. If the item
     * already carries a stream URL (Xtream / M3U — stable), use it. If it's blank (Stalker — the
     * create_link URL is single-use, so it's never cached), resolve it FRESH now via the source's
     * [com.nuvio.tv.core.iptv.IptvClient.resolveStreamUrl] (or the Stalker episode resolver). A
     * resolve miss / gone item -> empty (the picker shows "no streams").
     */
    private suspend fun resolveXtreamPlayStreams(
        videoId: String,
        item: com.nuvio.tv.core.iptv.XtreamResolvedItem
    ): List<AddonStreams> {
        val account = xtreamAccountStore.accounts.first().firstOrNull { it.id == item.accountId }
        if (account?.availableOnDevice != true) return emptyList()
        val playable = if (item.streamUrl.isNotBlank()) {
            val rebased = account?.let { serverFailover.rebaseStreamUrl(it, item.streamUrl) } ?: item.streamUrl
            item.copy(streamUrl = rebased)
        } else {
            val freshUrl = refreshIptvStreamUrl(videoId) ?: return emptyList()
            item.copy(streamUrl = freshUrl)
        }
        com.nuvio.tv.core.iptv.IptvPanelHosts.note(playable.streamUrl)
        return playable.toAddonStreams().map { group -> group.copy(streams = group.streams.map { stream ->
            com.nuvio.tv.core.iptv.StreamUserAgentPolicy.applyTo(stream, account).copy(deviceSourceKind = "iptv", deviceSourceRef = account.id)
        }) }
    }

    /**
     * Always resolves FRESH — for Stalker this mints a new single-use create_link token (or, for
     * an unflagged row, returns its static cmd), which is also the mid-playback recovery for an
     * expired/rotated one (the player calls this with [forceFresh] on stream 401/403 before
     * giving up — forcing the mint past a static verdict, because the static URL just died).
     * Xtream/M3U return their stable URL, so a "refresh" is a no-op round-trip at worst.
     */
    override suspend fun refreshIptvStreamUrl(videoId: String, forceFresh: Boolean): String? =
        refreshIptvStreamUrlInner(videoId, forceFresh).also { com.nuvio.tv.core.iptv.IptvPanelHosts.note(it) }

    private suspend fun refreshIptvStreamUrlInner(videoId: String, forceFresh: Boolean): String? {
        val parsed = com.nuvio.tv.core.iptv.XtreamItemRegistry.parseId(videoId) ?: return null
        val account = xtreamAccountStore.accounts.first().firstOrNull { it.id == parsed.accountId } ?: return null
        val client = iptvClientFactory.clientFor(account)
        return when (parsed.kind) {
            "live" -> {
                // The saved sid is a hint, not the truth: bind the channel's identity to whatever
                // sid the CURRENT catalog gives it (panels renumber — commit 9c7248641). Falls back to
                // the saved sid only when this device has never indexed the playlist.
                val saved = parsed.streamId.toIntOrNull() ?: return null
                val current = xtreamStreamSource.currentLiveSid(account, saved) ?: saved
                client.resolveStreamUrl(account, "live", current, forceFresh)
            }
            "vod" -> client.resolveStreamUrl(account, "movie", parsed.streamId.toIntOrNull() ?: return null, forceFresh)
            "episode" -> {
                // Stalker episode ids encode "seriesId:season:episodeNum" as the streamId segment.
                // A legacy 2-part id ("seriesId:episodeNum") predates seasons -> season null.
                val stalker = client as? com.nuvio.tv.core.iptv.stalker.StalkerClient
                val parts = parsed.streamId.split(":")
                val seriesId = parts.getOrNull(0)?.toIntOrNull()
                val season = if (parts.size >= 3) parts.getOrNull(1)?.toIntOrNull() else null
                val epNum = (if (parts.size >= 3) parts.getOrNull(2) else parts.getOrNull(1))?.toIntOrNull()
                if (stalker != null && seriesId != null && epNum != null) {
                    stalker.resolveEpisodeUrl(account, seriesId, season, epNum)
                } else null
            }
            else -> null
        }
    }

    // Other own sources (a media server) register into the plural stream-source port; IPTV keeps its own lane below.
    private val sourceStreams get() = com.nuvio.tv.core.contracts.StreamSourceAccess.current()

    override fun isDeferredIptvUrl(url: String?): Boolean =
        com.nuvio.tv.core.iptv.match.XtreamStreamSource.isDeferred(url) || sourceStreams.isDeferredUrl(url)

    override suspend fun mintDeferredIptvUrl(url: String): String? {
        if (sourceStreams.isDeferredUrl(url)) {
            return runCatching { sourceStreams.resolveDeferredUrl(url, forceMint = false) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "deferred source mint failed: ${LogRedaction.text(it.message)}") }
                .getOrNull()
        }
        if (!com.nuvio.tv.core.iptv.match.XtreamStreamSource.isDeferred(url)) return url
        val accounts = xtreamAccountStore.accounts.first()
        return runCatching { xtreamStreamSource.resolveDeferredUrl(url, accounts) }
            .onFailure { Log.w(TAG, "deferred stalker mint failed: ${it.message}") }
            .getOrNull()
            .also { com.nuvio.tv.core.iptv.IptvPanelHosts.note(it) }
    }

    override suspend fun refreshMatchedIptvStreamUrl(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addonName: String?,
        streamName: String?,
        failedUrl: String
    ): String? {
        if (videoId.isBlank() || addonName.isNullOrBlank()) return null
        if (xtreamRegistry.isXtreamId(videoId)) return null
        // Matched-lane streams carry the account's display name as addonName — an unknown label
        // means the failing stream isn't ours (addon/debrid), so bail before any network.
        val candidates = xtreamAccountStore.accounts.first()
            .filter { it.availableOnDevice && it.name == addonName }
        if (candidates.isEmpty()) return null
        for (account in candidates) {
            val streams = runCatching { xtreamStreamSource.streamsFor(account, type, videoId, season, episode) }
                .onFailure { Log.w(TAG, "matched-iptv refresh: matcher threw for ${account.name}: ${it.message}") }
                .getOrDefault(emptyList())
            val fresh = streams.firstOrNull { it.name == streamName && !it.url.isNullOrBlank() && it.url != failedUrl }
                ?: streams.firstOrNull { !it.url.isNullOrBlank() && it.url != failedUrl }
            val freshUrl = fresh?.url
            if (freshUrl != null) {
                // A matched Stalker candidate is DEFERRED ("stalker-deferred:…") — the listing
                // never mints. The recovery swaps this URL straight into the engine, so the real
                // link must be minted HERE, and minted fresh: a static-cmd verdict would rebuild
                // the very URL that just died. Xtream URLs pass through unchanged.
                val playable = runCatching {
                    xtreamStreamSource.resolveDeferredUrl(freshUrl, candidates, forceFresh = true)
                }.onFailure { Log.w(TAG, "matched-iptv refresh: deferred mint failed: ${it.message}") }
                    .getOrNull()
                if (!playable.isNullOrBlank() && playable != failedUrl) {
                    com.nuvio.tv.core.iptv.IptvPanelHosts.note(playable)
                    return playable
                }
            }
        }
        return null
    }

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)
        // OWN-SOURCE LANE (media server): its ids play their own deferred streams - skip addon + debrid.
        if (sourceStreams.isHandledId(videoId)) {
            emit(NetworkResult.Success(sourceStreams.directStreams(videoId)))
            return@flow
        }
        // HYBRID LANE: Xtream ids play their own single direct stream — skip addon + debrid.
        if (xtreamRegistry.isXtreamId(videoId)) {
            // Rebuild from the id on a registry miss (saved/deep-linked item not browsed this session).
            val item = xtreamRegistry.get(videoId)
                ?: xtreamRegistry.rebuildFromId(videoId, xtreamAccountStore, iptvClientFactory)
            // Stalker create_link URLs are SINGLE-USE, so browse-time items carry a blank stream URL:
            // resolve it FRESH here at play time (never from cache). Xtream/M3U items already have a
            // stable URL, so this only fires for Stalker (or a placeholder).
            val streams = item?.let { resolveXtreamPlayStreams(videoId, it) } ?: emptyList()
            emit(NetworkResult.Success(streams))
            return@flow
        }

        val sourceConfiguration = captureSourceConfiguration()
        val isNativeServerRequest = serverStreams.isNativeRequest(videoId)
        val requestKey = buildStreamSearchRequestKey(sourceConfiguration, type, videoId, season, episode)

        val producer = {
            fetchStreamsFromAllSources(
                type = type, videoId = videoId, season = season, episode = episode,
                addons = if (isNativeServerRequest) emptyList() else sourceConfiguration.addons,
                pendingAddonUrls = if (isNativeServerRequest || !AppFeaturePolicy.addonStreamSourcesEnabled) emptySet() else sourceConfiguration.pendingAddonUrls,
                forceRefresh = forceRefresh,
                debridSettings = sourceConfiguration.debridSettings,
                hasCompatiblePlugins = !isNativeServerRequest && sourceConfiguration.pluginsEnabled &&
                    sourceConfiguration.enabledScrapers.any { it.supportsType(type) },
                serverSources = serverStreams.sources(type, videoId, season, episode, forceRefresh)
            ).withSourceQueryDeadline { NetworkResult.Error(context.getString(R.string.stream_source_query_timed_out)) }
        }
        // Own-source matching can mint single-use links and has its own refresh policy.
        // Keep those searches scoped to their collector; reuse upstream searches only.
        if (hasForkMatchSources(type)) emitAll(producer())
        else emitAll(streamSearchSessions.observe(requestKey, forceRefresh, producer))
    }.withSourceQueryDeadline { NetworkResult.Error(context.getString(R.string.stream_source_query_timed_out)) }

    private suspend fun hasForkMatchSources(type: String): Boolean =
        xtreamAccountStore.accounts.first().any { it.availableOnDevice } || sourceStreams.matchSourceGroups(type).isNotEmpty()

    override suspend fun getCachedStreamsFromAllAddons(
        type: String, videoId: String, season: Int?, episode: Int?
    ): List<AddonStreams>? {
        if (sourceStreams.isHandledId(videoId) || xtreamRegistry.isXtreamId(videoId) || hasForkMatchSources(type)) return null
        return streamSearchSessions.peek(buildStreamSearchRequestKey(captureSourceConfiguration(), type, videoId, season, episode))
    }

    private fun buildStreamSearchRequestKey(
        sourceConfiguration: StreamSourceConfigurationSnapshot,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): StreamSearchRequestKey = StreamSearchRequestKey(
        profileId = sourceConfiguration.profileId,
        type = type.lowercase(),
        videoId = videoId,
        season = season,
        episode = episode,
        sourceConfiguration = buildSourceConfigurationKey(
            addons = sourceConfiguration.addons,
            pluginsEnabled = sourceConfiguration.pluginsEnabled,
            enabledScrapers = sourceConfiguration.enabledScrapers,
            groupPluginsByRepository = sourceConfiguration.groupPluginsByRepository,
            pluginRepositories = sourceConfiguration.pluginRepositories,
            debridPresentationConfiguration = sourceConfiguration.debridSettings
                .withoutRawCredentials()
                .toString(),
            serverRevision = serverStreams.revision,
            pendingAddonUrls = sourceConfiguration.pendingAddonUrls,
        )
    )

    private suspend fun captureSourceConfiguration(): StreamSourceConfigurationSnapshot {
        while (true) {
            val profileId = profileManager.activeProfileId.value
            val discovery = addonRepository.getAddonDiscoveryState().first { it.initialized }
            val addons = discovery.addons.enabledAddons()
            val pluginsEnabled = pluginManager.pluginsEnabled.first()
            val enabledScrapers = if (pluginsEnabled) pluginManager.enabledScrapers.first() else emptyList()
            val groupPluginsByRepository = pluginsEnabled && pluginManager.groupStreamsByRepository.first()
            val pluginRepositories = if (groupPluginsByRepository) pluginManager.repositories.first() else emptyList()
            val debridSettings = debridSettingsDataStore.settings.first()

            if (profileManager.activeProfileId.value != profileId) continue

            return StreamSourceConfigurationSnapshot(
                profileId = profileId,
                addons = addons,
                pendingAddonUrls = discovery.pendingUrls + discovery.failedUrls,
                pluginsEnabled = pluginsEnabled,
                enabledScrapers = enabledScrapers,
                groupPluginsByRepository = groupPluginsByRepository,
                pluginRepositories = pluginRepositories,
                debridSettings = debridSettings
            )
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun fetchStreamsFromAllSources(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        addons: List<Addon>,
        pendingAddonUrls: Set<String>,
        forceRefresh: Boolean,
        debridSettings: DebridSettings,
        hasCompatiblePlugins: Boolean,
        serverSources: List<ServerStreamSource>
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)
        // Media servers (upstream's native feature): a server item id plays only its own versions, so
        // add-ons and plugins are skipped; any other id also asks every enabled server for a match.
        val isNativeServerRequest = serverStreams.isNativeRequest(videoId)

        try {
            
            // Filter addons that support streams for this type and id
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type, videoId)
            }

            // Convert IMDB ID to TMDB ID if needed for plugins
            val tmdbId = if (hasCompatiblePlugins) tmdbService.ensureTmdbId(videoId, type) else null
            Log.d(TAG, LogRedaction.text("Video ID: $videoId -> TMDB ID: $tmdbId (type: $type)"))
            val pluginRequest = if (!hasCompatiblePlugins) null else buildPluginRequest(tmdbId, type, videoId)
            val attemptedAddonNames = (streamAddons.map { it.displayName } + serverSources.map { it.name } +
                pendingAddonUrls.map { context.getString(R.string.stream_discovering_source) }).toMutableList()
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            // Xtream IPTV as a stream source for TMDB content: each enabled account gets
            // its own group via the TMDB->stream matcher (index + verify + synced cache).
            // TMDB->stream matching (Sports Centre + addon-detail IPTV streams) needs a get_vod_info /
            // get_series_info verify endpoint, which only real Xtream panels have — M3U (url/file)
            // must not reach the resolver (its player_api calls just fail into backoff); its content
            // still plays via its own namespaced hybrid lane (registry ids). Stalker IS included: it
            // takes a different route inside XtreamStreamSource (the portal's own search endpoint).
            val xtreamMatchTargets = if (type == "movie" || type == "series") {
                xtreamAccountStore.accounts.first().filter {
                    it.availableOnDevice && (it.isXtream() || it.sourceType == XtreamAccount.SOURCE_STALKER)
                }
            } else {
                emptyList()
            }
            Log.d(TAG, "Xtream match targets: ${xtreamMatchTargets.size} type=$type")
            // Matched lane of the other own sources (a media server's copy of this TMDB title): one group each.
            // Empty until a source offers match groups (media servers: P3); nothing about IPTV changes.
            val sourceMatchGroups = if (type == "movie" || type == "series") sourceStreams.matchSourceGroups(type) else emptyList()

            attemptedAddonNames += xtreamMatchTargets.map { it.name } + sourceMatchGroups.map { it.addonName }
            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)

                // Track number of pending jobs
                val totalJobs = streamAddons.size + pendingAddonUrls.size +
                    (if (pluginRequest != null) 1 else 0) +
                    xtreamMatchTargets.size +
                    sourceMatchGroups.size +
                    serverSources.size
                val completedJobs = java.util.concurrent.atomic.AtomicInteger(0)

                // B63: the IPTV jobs wait past their budget only while these (addon + plugin)
                // sources are still loading — see IptvSourceWaitPolicy.
                val nonIptvJobs = totalJobs - xtreamMatchTargets.size - sourceMatchGroups.size
                val nonIptvDone = java.util.concurrent.atomic.AtomicInteger(0)
                val othersSettled = kotlinx.coroutines.Job()
                if (nonIptvJobs == 0) othersSettled.complete()
                fun markNonIptvDone() {
                    if (nonIptvDone.incrementAndGet() >= nonIptvJobs) othersSettled.complete()
                }

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            val streamsResult = getStreamsFromAddon(addon, type, videoId)
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo, deviceSourceKind = "addon", deviceSourceRef = addon.baseUrl)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    } else {
                                        // Stream endpoint returned empty - try inline
                                        // streams from meta response as fallback.
                                        val inlineStreams = fetchInlineStreamsFromMeta(
                                            addon, type, videoId
                                        )
                                        if (inlineStreams.isNotEmpty()) {
                                            resultChannel.send(
                                                AddonStreams(
                                                    addonName = addon.displayName,
                                                    addonLogo = addon.logo,
                                                    streams = inlineStreams
                                                )
                                            )
                                        } else {
                                            attemptedFailures += buildMissingStreamFailure(addon)
                                        }
                                    }
                                }
                                is NetworkResult.Error -> {
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${LogRedaction.text(e.message)}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
                            )
                        } finally {
                            markNonIptvDone()
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch media-server jobs (one group per connected server; its own versions or a matched title)
                serverSources.forEach { source ->
                    launch {
                        try {
                            val streams = source.load()
                            if (streams.isNotEmpty()) {
                                resultChannel.send(AddonStreams(addonName = source.name, addonLogo = null, streams = streams))
                            } else {
                                attemptedFailures += StreamAttemptFailure(
                                    addonName = source.name,
                                    kind = StreamFailureKind.MISSING,
                                    detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            attemptedFailures += StreamAttemptFailure(
                                addonName = source.name,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = context.getString(e.serverFailure().messageRes())
                            )
                        } finally {
                            markNonIptvDone()
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Cold manifests join the existing discovery owner while known sources remain selectable.
                pendingAddonUrls.forEach { url ->
                    launch {
                        val label = context.getString(R.string.stream_discovering_source)
                        try {
                            val ready = addonRepository.getAddonDiscoveryState().first {
                                it.initialized && url !in it.pendingUrls
                            }
                            val addon = if (url in ready.failedUrls && forceRefresh) {
                                (addonRepository.fetchAddon(url) as? NetworkResult.Success)?.data
                            } else ready.addons.firstOrNull { it.baseUrl == url && it.availableOnDevice }
                            if (addon == null || url in ready.failedUrls && !forceRefresh) {
                                attemptedFailures += StreamAttemptFailure(label, StreamFailureKind.REQUEST_FAILED,
                                    context.getString(R.string.stream_source_configuration_failed))
                            } else if (addon.enabled && addon.supportsStreamResource(type, videoId)) {
                                when (val result = getStreamsFromAddon(addon, type, videoId)) {
                                    is NetworkResult.Success -> if (result.data.isNotEmpty()) {
                                        resultChannel.send(AddonStreams(addon.displayName, addon.logo,
                                            result.data.map { it.copy(addonName = addon.displayName, addonLogo = addon.logo, deviceSourceKind = "addon", deviceSourceRef = addon.baseUrl) }))
                                    }
                                    is NetworkResult.Error -> attemptedFailures += buildAddonFailure(addon, result)
                                    NetworkResult.Loading -> Unit
                                }
                            }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            attemptedFailures += StreamAttemptFailure(label, StreamFailureKind.REQUEST_FAILED,
                                context.getString(R.string.stream_source_configuration_failed))
                        } finally {
                            markNonIptvDone()
                            if (completedJobs.incrementAndGet() >= totalJobs) resultChannel.close()
                        }
                    }
                }

                // Launch TMDB->Xtream match jobs (one group per enabled IPTV account)
                xtreamMatchTargets.forEach { acc ->
                    launch {
                        try {
                            // B63: a dead playlist can no longer hold the whole list open for its
                            // connect timeout — it is cut at the budget once nothing else is loading.
                            val streams = com.nuvio.tv.core.iptv.match.IptvSourceWaitPolicy.await(othersSettled) {
                                xtreamStreamSource.streamsFor(acc, type, videoId, season, episode)
                            } ?: emptyList<Stream>().also {
                                attemptedFailures += StreamAttemptFailure(acc.name, StreamFailureKind.REQUEST_FAILED,
                                    context.getString(R.string.stream_source_query_timed_out))
                            }
                            streams.forEach { com.nuvio.tv.core.iptv.IptvPanelHosts.note(it.url) }
                            if (streams.isNotEmpty()) {
                                resultChannel.send(
                                    AddonStreams(
                                        addonName = acc.name,
                                        addonLogo = null,
                                        streams = streams
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            attemptedFailures += StreamAttemptFailure(acc.name, StreamFailureKind.REQUEST_FAILED,
                                context.getString(R.string.stream_error_fetch_failed))
                            Log.e(TAG, "Xtream match failed for ${acc.name}: ${e.message}")
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch the matched-lane jobs of the other own sources (one group per signed-in server)
                sourceMatchGroups.forEach { group ->
                    launch {
                        try {
                            val streams = com.nuvio.tv.core.iptv.match.IptvSourceWaitPolicy.await(othersSettled) {
                                sourceStreams.resolveMatchStreams(group.sourceId, type, videoId, season, episode)
                            } ?: emptyList<Stream>().also {
                                attemptedFailures += StreamAttemptFailure(group.addonName, StreamFailureKind.REQUEST_FAILED,
                                    context.getString(R.string.stream_source_query_timed_out))
                            }
                            if (streams.isNotEmpty()) {
                                resultChannel.send(
                                    AddonStreams(
                                        addonName = group.addonName,
                                        addonLogo = null,
                                        streams = streams.map { it.copy(addonName = group.addonName, sourceId = it.sourceId ?: group.sourceId) }
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            attemptedFailures += StreamAttemptFailure(group.addonName, StreamFailureKind.REQUEST_FAILED,
                                context.getString(R.string.stream_error_fetch_failed))
                            Log.e(TAG, "Source match failed for ${group.addonName}: ${LogRedaction.text(e.message)}")
                        } finally {
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch plugin jobs if we have a supported plugin id - each scraper sends its own result
                if (pluginRequest != null) {
                    launch {
                        try {
                            // Stream plugins individually
                            streamLocalPlugins(
                                pluginId = pluginRequest.id,
                                mediaType = pluginRequest.mediaType,
                                pluginSource = pluginRequest.source,
                                season = season,
                                episode = episode,
                                resultChannel = resultChannel
                            ) {
                                markNonIptvDone()
                                if (completedJobs.incrementAndGet() >= totalJobs) {
                                    resultChannel.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Plugin execution failed: ${e.message}")
                            markNonIptvDone()
                            if (completedJobs.incrementAndGet() >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Handle case where there are no jobs
                if (totalJobs == 0) {
                    resultChannel.close()
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    val checkingResult = localDebridAvailabilityService.markChecking(listOf(result)).firstOrNull() ?: result
                    val checkedResult = localDebridAvailabilityService.annotateCachedAvailability(listOf(checkingResult)).firstOrNull() ?: checkingResult
                    mergePresentedResult(accumulatedResults, checkedResult, debridSettings)
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${checkedResult.addonName} with ${checkedResult.streams.size} streams")
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: context.getString(com.nuvio.tv.R.string.stream_error_fetch_failed)))
        }
    }

    private fun buildSourceConfigurationKey(
        addons: List<Addon>,
        pluginsEnabled: Boolean,
        enabledScrapers: List<ScraperInfo>,
        groupPluginsByRepository: Boolean,
        pluginRepositories: List<PluginRepository>,
        debridPresentationConfiguration: String,
        serverRevision: Int,
        pendingAddonUrls: Set<String> = emptySet()
    ): String = buildString {
        append("addons:")
        addons.forEach { addon ->
            append("|addon:").append(addon)
        }
        append("plugins:").append(pluginsEnabled)
        append("|grouped:").append(groupPluginsByRepository)
        enabledScrapers.forEach { scraper ->
            append("|scraper:").append(scraper)
        }
        if (groupPluginsByRepository) {
            pluginRepositories.forEach { repository ->
                append("|repo:").append(repository)
            }
        }
        append("|debrid:").append(debridPresentationConfiguration)
        append("|servers:").append(serverRevision)
        pendingAddonUrls.sorted().forEach { append("|pending:").append(it) }
    }.sha256()

    private fun DebridSettings.withoutRawCredentials(): DebridSettings = copy(
        torboxApiKey = torboxApiKey.sha256(),
        premiumizeApiKey = premiumizeApiKey.sha256(),
        realDebridApiKey = realDebridApiKey.sha256()
    )

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class PluginRequest(
        val id: String,
        val mediaType: String,
        val source: String
    )

    private fun buildPluginRequest(tmdbId: String?, type: String, videoId: String): PluginRequest? {
        if (tmdbId != null) {
            return PluginRequest(
                id = tmdbId,
                mediaType = normalizeTmdbPluginType(type),
                source = "TMDB"
            )
        }

        if (!videoId.canRunLocalPlugins()) return null

        return PluginRequest(
            id = if (videoId.startsWith("kitsu:", ignoreCase = true)) {
                cleanKitsuPluginId(videoId)
            } else {
                videoId
            },
            mediaType = type.lowercase(),
            source = videoId.substringBefore(":").uppercase()
        )
    }

    private fun normalizeTmdbPluginType(type: String): String {
        return when (type.lowercase()) {
            "series", "tv", "show" -> "tv"
            else -> type.lowercase()
        }
    }

    private fun cleanKitsuPluginId(videoId: String): String {
        val parts = videoId.split(":")
        return if (parts.size > 2 && parts.last().toIntOrNull() != null) {
            parts.dropLast(1).joinToString(":")
        } else {
            videoId
        }
    }

    private suspend fun mergePresentedResult(
        accumulatedResults: MutableList<AddonStreams>,
        result: AddonStreams,
        debridSettings: DebridSettings
    ) {
        val existingIndex = accumulatedResults.indexOfFirst { it.addonName == result.addonName }
        if (existingIndex >= 0) {
            val existing = accumulatedResults[existingIndex]
            val merged = existing.copy(
                streams = mergeStreams(existing.streams, result.streams)
            )
            accumulatedResults[existingIndex] = presentStreams(merged, debridSettings)
        } else {
            accumulatedResults.add(presentStreams(result, debridSettings))
        }
    }

    private suspend fun presentStreams(result: AddonStreams, debridSettings: DebridSettings): AddonStreams {
        return debridStreamPresentation.apply(
            groups = listOf(result),
            settings = debridSettings,
            includeBadgeMatches = false
        ).firstOrNull() ?: result
    }

    private fun mergeStreams(existing: List<Stream>, incoming: List<Stream>): List<Stream> {
        val streamsByKey = LinkedHashMap<String, Stream>()
        existing.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        incoming.forEach { stream -> streamsByKey[stream.dedupKey()] = stream }
        return streamsByKey.values.toList()
    }

    /**
     * Stream local plugin results - each scraper sends results individually
     */
    private fun String.canRunLocalPlugins(): Boolean {
        return startsWith("kitsu:", ignoreCase = true) ||
            startsWith("anilist:", ignoreCase = true) ||
            startsWith("mal:", ignoreCase = true)
    }

    private suspend fun streamLocalPlugins(
        pluginId: String,
        mediaType: String,
        pluginSource: String,
        season: Int?,
        episode: Int?,
        resultChannel: Channel<AddonStreams>,
        onComplete: () -> Unit
    ) {
        // Check if plugins are enabled
        if (!pluginManager.pluginsEnabled.first()) {
            Log.d(TAG, "Plugins are disabled")
            onComplete()
            return
        }

        // Skip local-plugin search while paused (e.g. during an IPTV/direct play).
        if (localPluginSearchPaused.value) {
            Log.d(TAG, "Local plugin search paused — skipping $pluginSource: $pluginId")
            onComplete()
            return
        }

        Log.d(TAG, "Streaming plugins for $pluginSource: $pluginId, type: $mediaType")

        try {
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val repositoriesById = if (groupByRepository) {
                pluginManager.repositories.first().associateBy { it.id }
            } else {
                emptyMap()
            }

            // Collect streaming results from each scraper
            pluginManager.executeScrapersStreaming(
                tmdbId = pluginId,
                mediaType = mediaType,
                season = season,
                episode = episode
            ).collect { (scraper, results) ->
                if (results.isNotEmpty()) {
                    val addonName = scraper.pluginAddonName(groupByRepository, repositoriesById)
                    val addonStreams = AddonStreams(
                        addonName = addonName,
                        addonLogo = null,
                        streams = results.map { result -> result.toPluginStream(scraper, addonName) }
                    )
                    resultChannel.send(addonStreams)
                    Log.d(TAG, "Streamed ${results.size} results from ${scraper.name}")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stream plugins: ${e.message}", e)
        } finally {
            onComplete()
        }
    }

    private fun ScraperInfo.pluginAddonName(
        groupByRepository: Boolean,
        repositoriesById: Map<String, PluginRepository>
    ): String {
        if (!groupByRepository) return name
        return repositoriesById[repositoryId]?.name?.takeIf { it.isNotBlank() } ?: name
    }

    private fun LocalScraperResult.toPluginStream(scraper: ScraperInfo, addonName: String): Stream {
        val baseTitle = title.takeIf { it.isNotBlank() }
        val baseName = name?.takeIf { it.isNotBlank() }
        val quality = quality?.takeIf { it.isNotBlank() }
        val qualityLabel = quality ?: context.getString(com.nuvio.tv.R.string.stream_quality_unknown)
        val displayName = buildString {
            append(baseName ?: baseTitle ?: scraper.name)
            if (!toString().contains(qualityLabel)) {
                append(" - ").append(qualityLabel)
            }
        }.takeIf { it.isNotBlank() }
        val displayTitle = (baseTitle ?: baseName ?: scraper.name).takeIf { it.isNotBlank() }

        return Stream(
            name = displayName,
            title = displayTitle,
            url = url,
            addonName = addonName,
            addonLogo = null,
            description = buildDescription(this),
            behaviorHints = headers?.let { headers ->
                StreamBehaviorHints(
                    notWebReady = null,
                    bingeGroup = null,
                    countryWhitelist = null,
                    proxyHeaders = ProxyHeaders(request = headers, response = null)
                )
            },
            infoHash = infoHash,
            fileIdx = null,
            ytId = null,
            externalUrl = null,
            quality = quality,
            qualityValue = parseQualityValue(quality),
            subtitles = subtitles
        )
    }

    private fun Stream.dedupKey(): String {
        val base = infoHash?.lowercase()?.let { hash -> "$hash:${fileIdx ?: ""}" }
            ?: clientResolve?.infoHash?.lowercase()?.let { hash -> "$hash:${clientResolve.fileIdx}" }
            ?: url
            ?: externalUrl
            ?: ytId
            ?: serverTarget?.key()
            ?: "${addonName}:${name}:${title}"
        val nameSuffix = if (base == url) {
            val discriminator = name?.takeIf { it.isNotBlank() }
            if (discriminator != null) "|$discriminator" else ""
        } else ""
        return "$base$nameSuffix"
    }

    /**
     * Build a description string from scraper result
     */
    private fun buildDescription(result: com.nuvio.tv.domain.model.LocalScraperResult): String? {
        // Quality is shown in the stream name — only show size/language in description
        val parts = mutableListOf<String>()
        result.size?.let { parts.add(it) }
        result.language?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }

    private fun parseQualityValue(quality: String?): Int {
        if (quality == null) return -1
        val lower = quality.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("800") -> 800
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> -1
        }
    }

    override suspend fun getStreamsFromAddon(
        addon: Addon,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$basePath/stream/$encodedType/$encodedVideoId.json$baseQuery"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=${LogRedaction.url(streamUrl)}")

        // Display info comes from the installed addon the caller already holds. Calling
        // addonRepository.fetchAddon() here caused an unconditional manifest GET ahead of every
        // queried addon's stream request: fetchAddon is the low-level fetch that does not consult
        // the cache, so this sidestepped the manifest-cache policy in AddonRepositoryImpl.
        val addonName = addon.displayName
        val addonLogo = addon.logo

        return when (val result = safeApiCall(context) { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=${LogRedaction.url(streamUrl)}")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${LogRedaction.text(result.message)} url=${LogRedaction.url(streamUrl)}"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Fetch meta for the given content and extract inline streams from the
     * matching video entry.  Returns an empty list when the addon doesn't
     * support meta or the video has no inline streams.
     */
    private suspend fun fetchInlineStreamsFromMeta(
        addon: Addon,
        type: String,
        videoId: String
    ): List<Stream> {
        // For inline streams the meta is fetched using the content-level ID
        // (everything before the video-specific suffix).  For "other" type
        // the videoId IS the content ID; for series it is contentId:S:E.
        // Video ID formats:
        //   tt1234567:1:5      → metaId = tt1234567
        //   mal:63375:1:5      → metaId = mal:63375
        //   kitsu:12345:2      → metaId = kitsu:12345
        // Strategy: drop up to 2 trailing numeric segments (season, episode)
        // but never reduce below 2 segments for prefixed IDs (mal:X, kitsu:X).
        val metaId = run {
            val parts = videoId.split(":")
            if (parts.size <= 1) return@run videoId
            // Count trailing numeric segments
            val trailingNumericCount = parts.reversed().takeWhile { it.toIntOrNull() != null }.size
            // Keep at least 2 segments for prefixed IDs (e.g. "mal:63375"),
            // or 1 segment for IMDB-style IDs (e.g. "tt1234567")
            val firstSegment = parts.first()
            val minSegments = if (firstSegment.startsWith("tt") || firstSegment.toIntOrNull() != null) 1 else 2
            val segmentsToDrop = trailingNumericCount.coerceAtMost((parts.size - minSegments).coerceAtLeast(0))
            if (segmentsToDrop > 0) {
                parts.dropLast(segmentsToDrop).joinToString(":")
            } else {
                videoId
            }
        }
        val cleanBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedMetaId = encodePathSegment(metaId)
        val metaUrl = "$basePath/meta/$encodedType/$encodedMetaId.json$baseQuery"
        Log.d(TAG, "Fetching inline streams via meta type=$type metaId=$metaId videoId=$videoId url=${LogRedaction.url(metaUrl)}")
        return try {
            when (val result = safeApiCall(context) { api.getMeta(metaUrl) }) {
                is NetworkResult.Success -> {
                    val metaDto = result.data.meta ?: return emptyList()
                    val matchingVideo = metaDto.videos?.firstOrNull { it.id == videoId }
                    val streams = AddonSourcePolicy.embeddedStreamsForBuild(
                        streams = matchingVideo?.streams
                            ?.mapNotNull { it.toDomain(addon.displayName, addon.logo) }
                            ?: emptyList(),
                        streamSourcesEnabled = AppFeaturePolicy.addonStreamSourcesEnabled,
                        isIptv = PlaybackAvailability.isIptvId(videoId)
                    )
                    Log.d(TAG, LogRedaction.text("Inline streams from meta: addon=${addon.displayName} videoId=$videoId found=${streams.size}"))
                    streams
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch inline streams from meta for ${addon.displayName}: ${LogRedaction.text(e.message)}")
            emptyList()
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.stream_error_detail_no_streams_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.nuvio.tv.R.string.stream_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(if (AppFeaturePolicy.addonStreamSourcesEnabled) R.string.stream_no_compatible_sources
                else R.string.playback_build_restricted_message)
        }
        // All sources answered empty (or do not provide this ID): a successful no-match, not a failure.
        if (failures.isEmpty() || failures.all { it.kind == StreamFailureKind.MISSING }) return null
        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${LogRedaction.text(it.detail)}" }
        return if (issueSummary.isBlank()) context.getString(R.string.stream_source_configuration_failed)
            else context.getString(R.string.stream_sources_failed, issueSummary)
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

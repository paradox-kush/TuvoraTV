package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterArt
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"
private const val DEBOUNCE_MS = 2_000L

/**
 * Keeps the Android TV "Continue Watching" preview channel in sync with the app's
 * CW enrichment cache. Uses the same enriched data that the in-app Continue Watching
 * section displays, respecting user settings (days cap, show unaired, etc.).
 *
 * Items are sourced from [ContinueWatchingEnrichmentCache] which is populated by the
 * HomeViewModel CW pipeline. This ensures the launcher channel shows the same items
 * the user sees in the app UI.
 *
 * Before publishing new programs, all stale programs are removed first to avoid
 * the launcher showing outdated tiles while new ones are being inserted.
 */
@Singleton
class AndroidTvChannelSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: AndroidTvChannelManager,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tvRecommendationManager: TvRecommendationManager,
    private val removalStore: LauncherRemovalStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The launcher channel is only visible while the app is in the background. Reconciling on
    // every in-playback cache bump (~10s) thrashes the TvProvider and stops launchers like
    // Projectivy from repainting. We instead reconcile once when the app goes to background
    // (user returns to the launcher) — see [onForegroundChanged].
    @Volatile private var appInForeground = false

    /** Called from the host Activity's onStart/onStop. On background we reconcile once so the
     *  channel reflects the latest watch progress exactly as the launcher regains foreground. */
    fun onForegroundChanged(foreground: Boolean) {
        val wasForeground = appInForeground
        appInForeground = foreground
        if (wasForeground && !foreground) {
            scope.launch { reconcileFromCache() }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) {
            Log.d(TAG, "Non-leanback device; channel sync skipped")
            return
        }
        TvChannelRefreshJobService.schedulePeriodic(context)

        scope.launch {
            // Observe cache snapshot updates and settings changes to trigger reconciliation.
            // snapshotVersion bumps every time the CW pipeline writes new data to disk cache.
            // We drop the initial value (0) to avoid reconciling with stale disk cache before
            // the pipeline has had a chance to produce fresh data.
            combine(
                cwEnrichmentCache.snapshotVersion
                    .dropWhile { it == 0 },
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw,
                combine(
                    layoutPreferenceDataStore.continueWatchingSortMode,
                    layoutPreferenceDataStore.tvHomeIncludeNextUp
                ) { sortMode, includeNextUp -> sortMode to includeNextUp }
            ) { _, daysCap, dismissed, useEpisodeThumbnails, (sortMode, includeNextUp) ->
                ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails, sortMode, includeNextUp)
            }
                .debounce(DEBOUNCE_MS)
                .collect { settings ->
                    // Skip while the app is foregrounded — the launcher channel isn't visible
                    // then, and reconciling on every ~10s in-playback cache bump thrashes the
                    // provider (and stops launchers like Projectivy from repainting). The
                    // background transition (onForegroundChanged) + periodic job cover it.
                    if (appInForeground) return@collect
                    reconcileFromCache(settings)
                }
        }

        // The "Show on the TV home screen" switch acts at once, foreground or not: turning it off
        // clears both surfaces now, turning it on publishes now. A reconcile with the switch off also
        // clears (see reconcileFromCache), so it stays off across boot and the periodic job.
        scope.launch {
            var first = true
            layoutPreferenceDataStore.tvHomePublishEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    clearLauncherSurfaces()
                } else if (!first) {
                    reconcileFromCache()
                }
                first = false
            }
        }
    }

    private suspend fun clearLauncherSurfaces() {
        runCatching { manager.clearAll() }
        runCatching { tvRecommendationManager.clearAll() }
    }

    /**
     * Reads the CW enrichment cache and reconciles the launcher channel.
     * Called both from the live observer and from [TvChannelRefreshJobService].
     */
    suspend fun reconcileFromCache(settings: ChannelSettingsSnapshot? = null) {
        // Off means both surfaces stay empty, however this run was triggered (boot, periodic job,
        // background transition, settings change).
        if (!layoutPreferenceDataStore.tvHomePublishEnabled.first()) {
            clearLauncherSurfaces()
            return
        }

        val resolvedSettings = settings ?: run {
            val daysCap = traktSettingsDataStore.continueWatchingDaysCap.first()
            val dismissed = traktSettingsDataStore.dismissedNextUpKeys.first()
            val useEpisodeThumbnails = layoutPreferenceDataStore.useEpisodeThumbnailsInCw.first()
            ChannelSettingsSnapshot(
                daysCap = daysCap,
                dismissedNextUp = dismissed,
                useEpisodeThumbnails = useEpisodeThumbnails,
                sortMode = layoutPreferenceDataStore.continueWatchingSortMode.first(),
                includeNextUp = layoutPreferenceDataStore.tvHomeIncludeNextUp.first()
            )
        }

        val inProgressItems = runCatching { cwEnrichmentCache.getInProgressSnapshot() }
            .getOrDefault(emptyList())
        val nextUpItems = runCatching { cwEnrichmentCache.getNextUpSnapshot() }
            .getOrDefault(emptyList())

        val posterPattern = patternForScreen(
            layoutPreferenceDataStore.customPosterUrlPattern.first(),
            CustomPosterScreen.TV_RECOMMENDATIONS,
            layoutPreferenceDataStore.customPosterEnabledScreens.first()
        )
        // Cache only, no network: aired state is re-checked against the clock from the stored release
        // timestamp, and removals the viewer made on the launcher are honoured.
        val launcherItems = LauncherItemsPlanner.plan(
            inProgress = inProgressItems,
            nextUp = nextUpItems,
            settings = LauncherPlanSettings(
                daysCap = resolvedSettings.daysCap,
                dismissedNextUp = resolvedSettings.dismissedNextUp,
                useEpisodeThumbnails = resolvedSettings.useEpisodeThumbnails,
                sortMode = resolvedSettings.sortMode,
                includeNextUp = resolvedSettings.includeNextUp
            ),
            nowMs = System.currentTimeMillis(),
            removed = runCatching { removalStore.all() }.getOrDefault(emptyMap())
        )
        val channelItems = launcherItems.map { it.progress }.withCustomPosterArt(posterPattern)

        Log.d(
            TAG,
            "Reconciling from cache: ${channelItems.size} items " +
                "(${inProgressItems.size} in-progress, ${nextUpItems.size} next-up raw)"
        )
        manager.reconcile(channelItems)

        runCatching {
            tvRecommendationManager.updateWatchNext(launcherItems)
        }
    }

    data class ChannelSettingsSnapshot(
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val useEpisodeThumbnails: Boolean,
        val sortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
        val includeNextUp: Boolean = true
    )
}

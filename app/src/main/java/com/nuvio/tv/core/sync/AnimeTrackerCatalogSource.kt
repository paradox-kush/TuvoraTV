package com.nuvio.tv.core.sync

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.AniListSettingsDataStore
import com.nuvio.tv.data.local.KitsuSettingsDataStore
import com.nuvio.tv.data.local.MalSettingsDataStore
import com.nuvio.tv.data.repository.AniListListService
import com.nuvio.tv.data.repository.KitsuListService
import com.nuvio.tv.data.repository.MalListService
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TrackerListItem
import com.nuvio.tv.domain.model.TrackerListStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aggregates tracker-backed catalog rows across MAL / AniList / Kitsu. Rows
 * whose status is not enabled in the respective settings data store are
 * filtered out. The resulting [Flow] is what [HomeViewModel] consumes — exact
 * wiring lands in Phase 6 alongside the Settings UI that flips the toggles.
 *
 * Row keys are stable strings shaped `tracker:{service}:{status}` so they
 * integrate with the existing [LocalHomeCatalogSettingsState.disabledKeys]
 * pattern without collisions.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeTrackerCatalogSource @Inject constructor(
    private val malList: MalListService,
    private val malSettings: MalSettingsDataStore,
    private val aniListList: AniListListService,
    private val aniListSettings: AniListSettingsDataStore,
    private val kitsuList: KitsuListService,
    private val kitsuSettings: KitsuSettingsDataStore
) {
    data class TrackerRowKey(
        val source: TrackerListItem.TrackerSource,
        val status: TrackerListStatus
    ) {
        val key: String = "tracker:${source.name.lowercase()}:${status.key}"
    }

    /**
     * Emits the full list of enabled tracker rows whenever any underlying
     * settings or cache changes. The caller is expected to de-duplicate /
     * throttle; rows arrive in the order (MAL, AniList, Kitsu) grouped by
     * each tracker's configured `rowOrder`.
     */
    val enabledRows: Flow<List<CatalogRow>> = combine(
        malRows(),
        aniListRows(),
        kitsuRows()
    ) { mal, ani, kit -> mal + ani + kit }
        .distinctUntilChanged()

    fun rowKeys(
        malEnabled: Set<TrackerListStatus>,
        aniEnabled: Set<TrackerListStatus>,
        kitsuEnabled: Set<TrackerListStatus>
    ): List<TrackerRowKey> =
        malEnabled.map { TrackerRowKey(TrackerListItem.TrackerSource.MAL, it) } +
            aniEnabled.map { TrackerRowKey(TrackerListItem.TrackerSource.ANILIST, it) } +
            kitsuEnabled.map { TrackerRowKey(TrackerListItem.TrackerSource.KITSU, it) }

    // --- per-tracker plumbing --- //

    private fun malRows(): Flow<List<CatalogRow>> =
        malSettings.settings.flatMapLatest { settings ->
            val ordered = settings.rowOrder.filter { it in settings.enabledStatuses }
            if (ordered.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(ordered.map { status -> malList.observe(status).map { status to it } }) { perStatus ->
                perStatus.mapNotNull { (status, result) ->
                    toRow(TrackerListItem.TrackerSource.MAL, "MyAnimeList", status, result)
                }
            }
        }

    private fun aniListRows(): Flow<List<CatalogRow>> =
        aniListSettings.settings.flatMapLatest { settings ->
            val ordered = settings.rowOrder.filter { it in settings.enabledStatuses }
            if (ordered.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(ordered.map { status -> aniListList.observe(status).map { status to it } }) { perStatus ->
                perStatus.mapNotNull { (status, result) ->
                    toRow(TrackerListItem.TrackerSource.ANILIST, "AniList", status, result)
                }
            }
        }

    private fun kitsuRows(): Flow<List<CatalogRow>> =
        kitsuSettings.settings.flatMapLatest { settings ->
            val ordered = settings.rowOrder.filter { it in settings.enabledStatuses }
            if (ordered.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(ordered.map { status -> kitsuList.observe(status).map { status to it } }) { perStatus ->
                perStatus.mapNotNull { (status, result) ->
                    toRow(TrackerListItem.TrackerSource.KITSU, "Kitsu", status, result)
                }
            }
        }

    private fun toRow(
        source: TrackerListItem.TrackerSource,
        addonName: String,
        status: TrackerListStatus,
        result: NetworkResult<List<TrackerListItem>>
    ): CatalogRow? {
        val items = (result as? NetworkResult.Success)?.data ?: return null
        if (items.isEmpty()) return null
        val key = TrackerRowKey(source, status)
        // `addonId` piggy-backs on the key so HomeCatalogSync's order/disabled
        // plumbing works without a schema change.
        return CatalogRow(
            addonId = key.key,
            addonName = addonName,
            addonBaseUrl = "tracker://${source.name.lowercase()}",
            catalogId = status.key,
            catalogName = "${status.displayName} on $addonName",
            type = ContentType.SERIES,
            rawType = "series",
            items = items.map { it.preview },
            isLoading = false,
            hasMore = false,
            currentPage = 0,
            supportsSkip = false
        )
    }
}

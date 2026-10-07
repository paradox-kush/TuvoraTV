package com.nuvio.tv.core.contracts

import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge

/**
 * A row a contributor WOULD show (its settings say it is on), independent of whether it currently has items -
 * what the Home catalog-order settings list so the viewer can reorder / hide / rename it even while it is empty
 * or the server is offline. [key] is the same stable key the row renders under (never carries a user id).
 */
data class ContributedRowDeclaration(val key: String, val title: String, val subtitle: String)

/** One row a contributor puts on Home. [key] is stable across a re-login (`ms:{type}:{machineId}:{rowId}`). */
data class ContributedHomeRow(
    val key: String,
    /** The owning source's stable identity (`{type}:{machineId}`) - what "see all" is routed by. */
    val sourceKey: String,
    /** The row's list id within the source (`continue_watching`, `library:{id}`...). */
    val listId: String,
    val title: String,
    val subtitle: String,
    /** The Stremio type the cards route on ("movie" / "series"). */
    val rawType: String,
    val items: List<MetaPreview>,
    val hasMore: Boolean,
)

/** One page of a "see all" listing. [nextSkip] null = no more pages. */
data class ContributedPage(val items: List<MetaPreview>, val nextSkip: Int?)

/**
 * A source that contributes its own rows to Home (a media server's Continue Watching / Next Up / Recently added /
 * a chosen library). Contract (same as the KMP twin):
 *  - keys MUST be stable across re-login: key on the source identity + list id, never on a user id;
 *  - [sections] is called under Home's lifecycle (a refresh), so it may do network work but must be TTL-gated by
 *    the contributor itself - Home does not poll on a timer. Return an empty list when the source is off, offline
 *    or signed out; never throw for expected states;
 *  - the rows never feed Home's hero (owner decision 2026-10-06: "server rows don't feed the hero").
 * With nothing registered Home is unchanged.
 */
interface HomeSectionContributor {
    val name: String

    suspend fun sections(forceRefresh: Boolean): List<ContributedHomeRow>

    /** True when [sourceKey] belongs to this contributor. */
    fun ownsSource(sourceKey: String): Boolean

    /** One page of a "see all" listing for a row this contributor owns. */
    suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?): ContributedPage

    /** The rows this contributor is configured to show, for the Home layout settings. Cheap and synchronous (no network). */
    fun declaredRows(): List<ContributedRowDeclaration> = emptyList()

    /** Emits when the contributor's CONFIGURATION changed (a server added / edited / signed in): Home asks again. */
    val changes: Flow<Unit> get() = emptyFlow()
}

/**
 * How a contributed row is keyed on TV Home: it is a plain catalog row of the pseudo-add-on [ADDON_ID], so it joins
 * the same order / hide / rename preferences as every add-on catalog (keyed by [homeKey], the catalog row's legacy
 * key). The contributor's own key (`ms:{type}:{machineId}:{rowId}`) is the catalog id and never carries a user id.
 */
object ContributedRows {
    const val ADDON_ID = "contrib"
    const val TYPE = "movie"

    fun homeKey(rowKey: String): String = catalogRowLegacyKey(ADDON_ID, TYPE, rowKey)

    fun isContributedHomeKey(key: String): Boolean = key.startsWith("${ADDON_ID}_")
}

object HomeSectionContributorRegistry {
    private val contributors = NamedRegistry<HomeSectionContributor>("HomeSectionContributor")

    fun register(contributor: HomeSectionContributor) = contributors.register(contributor.name, contributor)

    val all: List<HomeSectionContributor> get() = contributors.all

    val isEmpty: Boolean get() = contributors.isEmpty

    /** Every contributor's rows in registration order. A failing contributor contributes nothing; a repeated key keeps the first. */
    suspend fun collectSections(forceRefresh: Boolean): List<ContributedHomeRow> {
        val seen = mutableSetOf<String>()
        return all.flatMap { contributor ->
            try {
                contributor.sections(forceRefresh)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        }.filter { seen.add(it.key) }
    }

    /** Merged configuration-change signals of every contributor. */
    fun changes(): Flow<Unit> = merge(*all.map { it.changes }.toTypedArray().ifEmpty { arrayOf(emptyFlow()) })

    /** Every contributor's declared rows (a failing contributor declares none), first declaration of a key wins. */
    fun declaredRows(): List<ContributedRowDeclaration> {
        val seen = mutableSetOf<String>()
        return all.flatMap { contributor ->
            try {
                contributor.declaredRows()
            } catch (_: Throwable) {
                emptyList()
            }
        }.filter { seen.add(it.key) }
    }

    /** True when [key] is one of the keys the contributors declare (the Home settings' membership test). */
    fun isContributedKey(key: String): Boolean = declaredRows().any { it.key == key }

    /** The "see all" page, or null when no contributor owns [sourceKey]. */
    suspend fun loadSourcePage(sourceKey: String, listId: String, skip: Int?): ContributedPage? =
        all.firstOrNull { it.ownsSource(sourceKey) }?.loadSourcePage(sourceKey, listId, skip)

    fun resetForTest() = contributors.resetForTest()
}

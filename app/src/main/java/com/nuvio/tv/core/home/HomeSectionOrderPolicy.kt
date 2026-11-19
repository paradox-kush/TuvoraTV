package com.nuvio.tv.core.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One Home section in the shared `home_sections` list: its id and whether it shows. */
@Serializable
data class HomeSectionEntry(
    val id: String,
    val enabled: Boolean = true,
)

/**
 * The shared `home_sections` value (a top-level key of the `home_catalog_shared` blob; the phone, Apple TV and
 * Desktop write the same shape). While [customized] is false every device uses its own default order.
 */
@Serializable
data class HomeSectionsConfig(
    val customized: Boolean = false,
    val sections: List<HomeSectionEntry> = emptyList(),
)

/** JSON in and out of the device-local copy of the config. A bad or missing string is "no config". */
object HomeSectionsJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(raw: String?): HomeSectionsConfig? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(HomeSectionsConfig.serializer(), raw) }.getOrNull()
    }

    fun encode(config: HomeSectionsConfig): String = json.encodeToString(HomeSectionsConfig.serializer(), config)
}

/**
 * Decides which Home rows show, and in which order, from the shared `home_sections` list.
 *
 * Pure: no Android, no I/O. The rules it keeps:
 * - Not customised (key absent or `customized: false`): this device's default order, everything on.
 * - The hero is always first and can only be switched on or off; it is never moved.
 * - Ids this device cannot draw (`new_episodes`, `sports`, anything unknown) are skipped when drawing and
 *   stay where they are in the list on every write, so another device's rows survive a change made here.
 * - A row the list does not mention (an older write from another device) is slotted in after the row
 *   that precedes it in the default order, so it neither vanishes nor lands somewhere surprising.
 * - Hero and Continue Watching also have their own long-standing switches; callers pass those so the
 *   `enabled` flags and the switches stay in step. See [effective].
 */
object HomeSectionOrderPolicy {
    const val HERO = "hero"
    const val CONTINUE_WATCHING = "continue_watching"
    const val LIVE_RECENTS = "live_recents"
    const val NEW_EPISODES = "new_episodes"
    const val SPORTS = "sports"
    const val UPCOMING = "upcoming"
    const val CATALOGS = "catalogs"

    /** Rows this device can move and hide, in its default order (the hero sits above them). */
    val MOVABLE_ROWS: List<String> = listOf(CONTINUE_WATCHING, LIVE_RECENTS, UPCOMING, CATALOGS)

    /** Every id this device can draw on Home. */
    val DRAWN_IDS: Set<String> = MOVABLE_ROWS.toSet() + HERO

    /** Today's order with Recent Channels right under Continue Watching. */
    val DEFAULT_ORDER: List<String> = listOf(HERO) + MOVABLE_ROWS

    /** The default config: not customised, so devices fall back to their own order. */
    fun reset(): HomeSectionsConfig =
        HomeSectionsConfig(customized = false, sections = DEFAULT_ORDER.map { HomeSectionEntry(it, true) })

    /**
     * The full ordered list with the hero pinned first, missing rows slotted in, and hero / Continue Watching
     * `enabled` taken from their switches. Unknown ids keep their place. Includes disabled rows.
     */
    fun effective(
        config: HomeSectionsConfig?,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): List<HomeSectionEntry> {
        val base = if (config == null || !config.customized) {
            DEFAULT_ORDER.map { HomeSectionEntry(it, true) }
        } else {
            withDefaultsSlottedIn(config.sections)
        }
        return base.map { entry ->
            when (entry.id) {
                HERO -> entry.copy(enabled = heroEnabled)
                CONTINUE_WATCHING -> entry.copy(enabled = continueWatchingEnabled)
                else -> entry
            }
        }
    }

    /** The rows this device can move and hide (no hero, no ids it cannot draw), in order, disabled ones included. */
    fun movableRows(
        config: HomeSectionsConfig?,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): List<HomeSectionEntry> =
        effective(config, heroEnabled, continueWatchingEnabled).filter { it.id in MOVABLE_ROWS }

    /** The ids to draw under the hero, top to bottom: enabled, drawable, hero excluded. */
    fun renderOrder(
        config: HomeSectionsConfig?,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): List<String> =
        movableRows(config, heroEnabled, continueWatchingEnabled).filter { it.enabled }.map { it.id }

    /** Whether the row can move one place in [direction] (-1 up, +1 down) among the movable rows. */
    fun canMove(
        config: HomeSectionsConfig?,
        id: String,
        direction: Int,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): Boolean {
        val rows = movableRows(config, heroEnabled, continueWatchingEnabled)
        val at = rows.indexOfFirst { it.id == id }
        if (at < 0) return false
        return (at + direction) in rows.indices
    }

    /**
     * Moves a movable row one place up (-1) or down (+1) among the movable rows. The row swaps places with its
     * neighbour; ids this device cannot draw stay exactly where they were. Returns a customised config, or the
     * same [config] when the move is not possible.
     */
    fun move(
        config: HomeSectionsConfig?,
        id: String,
        direction: Int,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): HomeSectionsConfig? {
        if (direction != -1 && direction != 1) return config
        val list = effective(config, heroEnabled, continueWatchingEnabled).toMutableList()
        val slots = list.indices.filter { list[it].id in MOVABLE_ROWS }
        val at = slots.indexOfFirst { list[it].id == id }
        if (at < 0 || (at + direction) !in slots.indices) return config
        val from = slots[at]
        val to = slots[at + direction]
        val moved = list[from]
        list[from] = list[to]
        list[to] = moved
        return HomeSectionsConfig(customized = true, sections = list)
    }

    /**
     * Switches one section on or off (the hero included). An id this device does not know changes nothing.
     * Returns a customised config.
     */
    fun setEnabled(
        config: HomeSectionsConfig?,
        id: String,
        enabled: Boolean,
        heroEnabled: Boolean = true,
        continueWatchingEnabled: Boolean = true,
    ): HomeSectionsConfig? {
        val list = effective(config, heroEnabled, continueWatchingEnabled)
        if (list.none { it.id == id }) return config
        return HomeSectionsConfig(
            customized = true,
            sections = list.map { if (it.id == id) it.copy(enabled = enabled) else it },
        )
    }

    /**
     * Keeps a stored list in step when the long-standing Hero / Continue Watching switch is flipped in Settings.
     * Only a customised list carries the flags, so anything else comes back unchanged (and stays "not customised").
     */
    fun withLegacyToggle(config: HomeSectionsConfig?, id: String, enabled: Boolean): HomeSectionsConfig? {
        if (config == null || !config.customized) return config
        if (id != HERO && id != CONTINUE_WATCHING) return config
        val list = withDefaultsSlottedIn(config.sections)
        return config.copy(sections = list.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /** Whether a customised config says [id] is on. Null when the config does not customise or does not name it. */
    fun storedEnabled(config: HomeSectionsConfig?, id: String): Boolean? {
        if (config == null || !config.customized) return null
        return config.sections.firstOrNull { it.id == id }?.enabled
    }

    private fun withDefaultsSlottedIn(stored: List<HomeSectionEntry>): List<HomeSectionEntry> {
        val seen = HashSet<String>()
        val kept = stored.filter { it.id.isNotBlank() && seen.add(it.id) }
        val hero = kept.firstOrNull { it.id == HERO } ?: HomeSectionEntry(HERO, true)
        val list = ArrayList<HomeSectionEntry>(kept.size + DEFAULT_ORDER.size)
        list += hero
        list += kept.filter { it.id != HERO }
        for ((position, id) in DEFAULT_ORDER.withIndex()) {
            if (list.any { it.id == id }) continue
            val predecessor = DEFAULT_ORDER.subList(0, position).last { earlier -> list.any { it.id == earlier } }
            list.add(list.indexOfFirst { it.id == predecessor } + 1, HomeSectionEntry(id, true))
        }
        return list
    }
}

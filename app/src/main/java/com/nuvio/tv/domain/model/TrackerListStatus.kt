package com.nuvio.tv.domain.model

/**
 * Canonical anime-tracker list status. The three backing services each expose
 * a slightly different enum on the wire, so callers work in terms of this
 * unified type and the mapping tables below translate at the edges.
 *
 *   MAL    : watching | completed | on_hold | dropped | plan_to_watch
 *   AniList: CURRENT  | COMPLETED | PAUSED  | DROPPED | PLANNING  | REPEATING
 *   Kitsu  : current  | completed | on_hold | dropped | planned
 *
 * [REWATCHING] is AniList-only on the wire but we expose it uniformly; for
 * MAL / Kitsu it maps back to WATCHING + the respective `is_rewatching`
 * flag when writing.
 */
enum class TrackerListStatus(val key: String, val displayName: String) {
    WATCHING("watching", "Watching"),
    PLANNED("planned", "Plan to Watch"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped"),
    REWATCHING("rewatching", "Rewatching");

    fun toMal(): String = when (this) {
        WATCHING, REWATCHING -> "watching"
        COMPLETED -> "completed"
        ON_HOLD -> "on_hold"
        DROPPED -> "dropped"
        PLANNED -> "plan_to_watch"
    }

    fun toAniList(): String = when (this) {
        WATCHING -> "CURRENT"
        PLANNED -> "PLANNING"
        COMPLETED -> "COMPLETED"
        ON_HOLD -> "PAUSED"
        DROPPED -> "DROPPED"
        REWATCHING -> "REPEATING"
    }

    fun toKitsu(): String = when (this) {
        WATCHING, REWATCHING -> "current"
        PLANNED -> "planned"
        COMPLETED -> "completed"
        ON_HOLD -> "on_hold"
        DROPPED -> "dropped"
    }

    companion object {
        fun fromMal(raw: String?): TrackerListStatus? = when (raw?.lowercase()) {
            "watching" -> WATCHING
            "completed" -> COMPLETED
            "on_hold" -> ON_HOLD
            "dropped" -> DROPPED
            "plan_to_watch" -> PLANNED
            else -> null
        }

        fun fromAniList(raw: String?): TrackerListStatus? = when (raw?.uppercase()) {
            "CURRENT" -> WATCHING
            "PLANNING" -> PLANNED
            "COMPLETED" -> COMPLETED
            "PAUSED" -> ON_HOLD
            "DROPPED" -> DROPPED
            "REPEATING" -> REWATCHING
            else -> null
        }

        fun fromKitsu(raw: String?): TrackerListStatus? = when (raw?.lowercase()) {
            "current" -> WATCHING
            "planned" -> PLANNED
            "completed" -> COMPLETED
            "on_hold" -> ON_HOLD
            "dropped" -> DROPPED
            else -> null
        }
    }
}

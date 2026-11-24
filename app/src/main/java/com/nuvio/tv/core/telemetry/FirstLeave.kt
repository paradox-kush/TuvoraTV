package com.nuvio.tv.core.telemetry

/**
 * Pure pieces of the `first_leave` summary (contract Addendum 1): no clock, no SDK, no Android. The persisted facts
 * (form opened, save tried, last save error) live in [FirstRunState]; the per-process ones live in [FirstLeaveVisit].
 * The TV twin of NuvioMobile's `core/telemetry/internal/FirstLeave.kt`.
 */
object FirstLeavePolicy {
    const val OTHER = "other"
    const val MAX_SCREEN_LENGTH = 40

    /**
     * Every `last_screen` value that can be sent: the sanitised static head of each route in `ui/navigation/Screen.kt`
     * (what [com.nuvio.tv.ui.navigation.ScreenRouteName] produces). Anything else, including the `dest_<id>` fallback, is
     * reported as [OTHER], so no title, channel or typed text can ever become a `last_screen`. A test fails when a route
     * is added to `Screen.kt` without being listed here.
     */
    val KNOWN_SCREENS: Set<String> = setOf(
        "home", "detail", "stream", "player", "clean_live", "search", "discover", "library", "calendar", "settings", "trakt",
        "watch_party", "tmdb_settings", "theme_settings", "playback_settings", "about", "supporters_contributors",
        "licenses_attributions", "addon_manager", "xtream_hub", "iptv_settings", "iptv_pairing", "iptv_setup_code",
        "sports_hub", "game_detail", "recordings", "multiview", "catalog_order", "plugins", "experience_mode_selection",
        "layout_selection", "layout_settings", "account", "manage_profiles", "auth_sign_in", "auth_qr_sign_in",
        "sync_code_generate", "sync_code_claim", "catalog_see_all", "collections", "collection_editor", "folder_detail",
        "profile_selection", "cast_detail", "tmdb_entity_browse",
    )

    /**
     * A raw identifier reduced to `[a-z0-9_.]`, lower-cased, at most [MAX_SCREEN_LENGTH] chars; any other run of
     * characters becomes one `_`. Empty or nothing left -> [OTHER].
     */
    fun sanitize(raw: String?): String {
        val out = StringBuilder()
        for (ch in raw.orEmpty().lowercase()) {
            val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '_' || ch == '.'
            if (ok) out.append(ch) else if (out.isNotEmpty() && out.last() != '_') out.append('_')
        }
        val cleaned = out.toString().trim('_').take(MAX_SCREEN_LENGTH).trimEnd('_')
        return cleaned.ifEmpty { OTHER }
    }

    /** The `last_screen` value for [raw]: its sanitised form when that is a known route head, else [OTHER]. */
    fun screenId(raw: String?): String = sanitize(raw).takeIf { it in KNOWN_SCREENS } ?: OTHER
}

/**
 * What the person did since this process started: the last screen, how many distinct screens, and when the current
 * foreground stint began. The caller serialises access (the service holds its funnel lock). The set is bounded: it only
 * ever holds [FirstLeavePolicy.KNOWN_SCREENS] values plus `other`.
 */
class FirstLeaveVisit(startMs: Long) {
    private var foregroundSinceMs = startMs
    private var lastScreen: String? = null
    private val seen = LinkedHashSet<String>()

    fun screen(raw: String) {
        if (raw.isBlank()) return
        val id = FirstLeavePolicy.screenId(raw)
        lastScreen = id
        seen += id
    }

    /** The app came back to the foreground: a new stint starts. */
    fun foreground(nowMs: Long) {
        foregroundSinceMs = nowMs
    }

    /** `last_screen`, `secs_in_app`, `screens_seen` as of [nowMs] (the moment the app left the foreground). */
    fun props(nowMs: Long): Map<String, Any> = mapOf(
        "last_screen" to (lastScreen ?: FirstLeavePolicy.OTHER),
        "secs_in_app" to ((nowMs - foregroundSinceMs) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        "screens_seen" to seen.size,
    )
}

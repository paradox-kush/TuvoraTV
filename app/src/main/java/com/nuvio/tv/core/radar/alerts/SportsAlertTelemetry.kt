package com.nuvio.tv.core.radar.alerts

/**
 * The `sports_alert` event, shared schema on every platform: `outcome`, `reason`, `kind`, `sport` (slug), `batch`,
 * `action`. Only two things are ever SENT: a banner that was `shown`, and what the viewer did with it (`action` =
 * open_game / watch / open_sports / dismissed). Everything the policy holds or drops is only COUNTED
 * ([COUNT_ONLY_REASONS]); the reasons below are never emitted at all (they would leak who follows what, or are noise).
 * Never a team, league or event id.
 */
object SportsAlertTelemetry {
    const val EVENT = "sports_alert"
    const val ACTION_DISMISSED = "dismissed"

    /** Counted in the ledger, never sent. */
    val COUNT_ONLY_REASONS: Set<String> = setOf(
        "watching_this_game", "quiet_hours", "during_playback", "scores_hidden", "hourly_cap", "superseded", "watch_unknown",
    )

    /** Neither sent nor counted. */
    val NEVER_EMIT_REASONS: Set<String> = setOf("not_followed", "duplicate", "stale", "disabled", "muted", "kind_off")

    fun shown(banner: AlertBanner): Map<String, Any> = buildMap {
        put("outcome", "shown")
        banner.kind?.let { put("kind", it.wire) }
        banner.sport?.let { put("sport", it) }
        put("batch", banner.size)
    }

    fun action(banner: AlertBanner, action: String): Map<String, Any> = buildMap {
        put("outcome", "action")
        put("action", action)
        banner.kind?.let { put("kind", it.wire) }
        banner.sport?.let { put("sport", it) }
        put("batch", banner.size)
    }
}

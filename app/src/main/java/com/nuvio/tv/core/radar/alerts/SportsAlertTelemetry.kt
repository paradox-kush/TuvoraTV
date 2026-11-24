package com.nuvio.tv.core.radar.alerts

/**
 * The `sports_alert` event. Allow-listed properties only: outcome, reason, kind, sport slug, batch size, action.
 * Never a team, league, event id, key or score.
 */
object SportsAlertTelemetry {
    const val EVENT = "sports_alert"

    fun shown(banner: AlertBanner): Map<String, Any> = buildMap {
        put("outcome", "shown")
        banner.kind?.let { put("kind", it.wire) }
        banner.sport?.let { put("sport", it) }
        put("batch_size", banner.size)
    }

    fun held(alert: SportsAlert, reason: HoldReason): Map<String, Any> =
        mapOf("outcome" to "held", "reason" to reason.wire, "kind" to alert.kind.wire, "sport" to alert.sport)

    fun dropped(alert: SportsAlert, reason: DropReason): Map<String, Any> =
        mapOf("outcome" to "dropped", "reason" to reason.wire, "kind" to alert.kind.wire, "sport" to alert.sport)

    fun action(banner: AlertBanner, action: String): Map<String, Any> = buildMap {
        put("outcome", "action")
        put("action", action)
        banner.kind?.let { put("kind", it.wire) }
        banner.sport?.let { put("sport", it) }
        put("batch_size", banner.size)
    }
}

package com.nuvio.tv.core.sports.detail

/**
 * One `sports_detail` PostHog event. Closed vocabulary only: sport profile, block, source, outcome,
 * duration. Never a URL, host, team or event name.
 */
data class SportsDetailEvent(
    val sport: String,
    /** null = the whole source call (used for failures). */
    val block: BlockType?,
    val source: SourceId,
    val outcome: FetchOutcome,
    val durationMs: Long,
) {
    fun toProperties(): Map<String, Any> = mapOf(
        "sport" to sport,
        "block" to (block?.wire ?: "all"),
        "source" to source.wire,
        "outcome" to outcome.wire,
        "duration_ms" to durationMs,
    )
}

interface SportsDetailTelemetry {
    fun record(event: SportsDetailEvent)

    object None : SportsDetailTelemetry { override fun record(event: SportsDetailEvent) = Unit }
}

class PostHogSportsDetailTelemetry : SportsDetailTelemetry {
    override fun record(event: SportsDetailEvent) {
        runCatching { com.posthog.PostHog.capture(event = "sports_detail", properties = event.toProperties()) }
    }
}

package com.nuvio.tv.playback.core

/** The three same-engine re-opens a live Media3 start may try before an engine hand-off. */
enum class LiveFormatStep {
    /** Xtream-style `.../N.m3u8` that is not a playlist: ask for the sibling `.../N.ts` as raw MPEG-TS. */
    SIBLING_TS_URL,

    /** The same URL, but treat the bytes as raw MPEG-TS (forced `video/mp2t`). */
    FORCE_RAW_TS,

    /** The same URL, but treat the bytes as HLS (forced `application/x-mpegURL`). */
    FORCE_HLS,
}

/**
 * B262: a live stream whose URL or declared type lies about its container (an `.m3u8` that is raw
 * TS or an HTML error page, an extension-less HLS playlist) used to fail Media3 and go straight to
 * the libmpv hand-off, or to a terminal "unreadable stream format" when mpv could not read it
 * either. A stock ExoPlayer app re-opens the same player once with a forced MIME type. This is the
 * pure decision for that: no I/O, no URL ever leaves this object's return values.
 *
 * Contract (every line is a test):
 *  - live only, Media3 only (the ladder re-opens the SAME engine; libmpv sniffs for itself);
 *  - never for a single-use link (Stalker): re-requesting it spends the token, and a fresh link
 *    has its own reconnect/mint budget, so such sources are excluded;
 *  - each step at most once per channel (the caller keeps [Input.tried] by stream identity,
 *    never by URL); once nothing is left the caller falls through to today's hand-off.
 */
object LiveFormatLadderPolicy {

    data class Input(
        val contentType: ContentType,
        val engine: EngineType,
        val failure: PlaybackFailure,
        val delivery: EvidenceFact<DeliveryType>?,
        val url: String,
        val singleUse: Boolean,
        val tried: Set<LiveFormatStep>,
    )

    fun next(input: Input): LiveFormatStep? {
        if (input.contentType != ContentType.LIVE) return null
        if (input.engine != EngineType.MEDIA3) return null
        if (input.singleUse) return null
        if (input.failure.retryability != Retryability.HANDOFF_ELIGIBLE) return null
        val delivery = input.delivery
        return when {
            input.failure.code == FailureCode.MANIFEST_INVALID &&
                input.failure.domain == FailureDomain.MANIFEST &&
                delivery?.value == DeliveryType.HLS -> when {
                LiveFormatStep.SIBLING_TS_URL !in input.tried && siblingTsUrl(input.url) != null ->
                    LiveFormatStep.SIBLING_TS_URL
                LiveFormatStep.FORCE_RAW_TS !in input.tried -> LiveFormatStep.FORCE_RAW_TS
                else -> null
            }
            input.failure.code == FailureCode.DEMUX_FAILED &&
                input.failure.domain == FailureDomain.DEMUX &&
                !isDeclared(delivery) &&
                LiveFormatStep.FORCE_HLS !in input.tried -> LiveFormatStep.FORCE_HLS
            else -> null
        }
    }

    /** Steps that are spent once [step] is taken: the sibling-.ts re-open already plays as raw TS. */
    fun spentBy(step: LiveFormatStep): Set<LiveFormatStep> = when (step) {
        LiveFormatStep.SIBLING_TS_URL -> setOf(LiveFormatStep.SIBLING_TS_URL, LiveFormatStep.FORCE_RAW_TS)
        else -> setOf(step)
    }

    /** `http://host/[live/]user/pass/123.m3u8` -> the same with `.ts`; null for any other shape. */
    fun siblingTsUrl(url: String): String? =
        if (XTREAM_LIVE_M3U8.matches(url)) url.dropLast(".m3u8".length) + ".ts" else null

    /** The evidence the re-open presents to the engine; the forced fact is tagged as ladder-made. */
    fun evidenceFor(step: LiveFormatStep, current: StreamEvidence): StreamEvidence = when (step) {
        LiveFormatStep.SIBLING_TS_URL,
        LiveFormatStep.FORCE_RAW_TS,
        -> current.copy(
            delivery = EvidenceFact(DeliveryType.RAW_TRANSPORT_STREAM, EvidenceProvenance.LADDER_FORCED),
            container = EvidenceFact(ContainerType.MPEG_TS, EvidenceProvenance.LADDER_FORCED),
        )
        LiveFormatStep.FORCE_HLS -> current.copy(
            delivery = EvidenceFact(DeliveryType.HLS, EvidenceProvenance.LADDER_FORCED),
            container = null,
        )
    }

    /** "No declared delivery": nothing, unknown, or only guessed from the URL. */
    private fun isDeclared(delivery: EvidenceFact<DeliveryType>?): Boolean =
        delivery != null &&
            delivery.value != DeliveryType.UNKNOWN &&
            delivery.provenance != EvidenceProvenance.URL_INFERRED &&
            delivery.provenance != EvidenceProvenance.UNKNOWN

    private val XTREAM_LIVE_M3U8 =
        Regex("^https?://[^/?#]+(/live)?/[^/?#]+/[^/?#]+/\\d+\\.m3u8$", RegexOption.IGNORE_CASE)
}

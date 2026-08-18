package com.nuvio.tv.core.epg

import com.posthog.PostHog

/**
 * The one place EPG work reports what it did.
 *
 * Until now the entire EPG subsystem was dark on mobile and desktop: TV had `iptv_index_build` and
 * nothing else existed, so when two viewers reported "the EPG takes a very long time to load"
 * (2026-08-17) PostHog could not distinguish a slow panel from a slow parse from a fan-out — the
 * diagnosis had to be reconstructed from source and a mock portal. These events exist so the next
 * one is a query.
 *
 * **Nothing identifying may be added to these events.** No panel URL, host, username, playlist
 * name, or channel name — telemetry has leaked a provider host and username once already
 * (`tuvora-privacy-claims`). the host app's PostHog privacy guard strips URL-shaped and
 * credential-shaped values as a last line of defence, but it cannot recognise a hostname sitting
 * in a field called `source`, so the rule is upstream: counts, durations, and closed vocabularies
 * only. Errors report their exception CLASS, never their message, because panel error messages
 * routinely quote the request URL.
 */
object EpgTelemetry {

    /** Which lane did the work. A closed vocabulary — never a URL or a playlist name. */
    enum class Source(val wire: String) {
        /** The backend-published canonical mirror (epgshare / EPGenius / epg.pw feeds). */
        MIRROR("mirror"),

        /** An XMLTV document belonging to the playlist: its `url-tvg` header, or an explicit URL. */
        PLAYLIST_XMLTV("playlist_xmltv"),

        /** A panel's per-channel guide table (`get_simple_data_table`) — the catch-up lane. */
        PANEL_TABLE("panel_table"),
    }

    enum class Outcome(val wire: String) {
        /** Rows were stored. */
        OK("ok"),

        /** The fetch and parse worked, but the source had nothing for this playlist's channels —
         *  the single most useful outcome to be able to count, because it looks identical to a
         *  failure on screen and is not one. */
        EMPTY("empty"),

        /** Fetch or parse threw. */
        ERROR("error"),

        /** Nothing was attempted: no resolvable source, or the playlist has no channels to attach
         *  a guide to. Distinguishing this from ERROR is what stops "EPG is broken" reports that
         *  are really "this playlist never had an EPG URL". */
        SKIPPED("skipped"),
    }

    /**
     * One ingest attempt finished.
     *
     * [channels] is how many channels the ingest was FOR (the allow-set), [channelsCovered] how
     * many actually got rows — their ratio is the coverage number every EPG complaint turns out to
     * be about, and it cannot be derived from anything we log today.
     */
    fun ingestFinished(
        source: Source,
        outcome: Outcome,
        programmes: Int = 0,
        channels: Int = 0,
        channelsCovered: Int = 0,
        durationMs: Long = 0,
        errorClass: String? = null,
    ) {
        runCatching {
            PostHog.capture(
            event = "epg_ingest",
            properties = buildMap {
                put("source", source.wire)
                put("outcome", outcome.wire)
                put("programmes", programmes)
                put("channels", channels)
                put("channels_covered", channelsCovered)
                put("duration_ms", durationMs)
                // Class name only. A panel's error message routinely quotes the request URL,
                // which carries the host and the credentials in the query string.
                errorClass?.let { put("error_class", it) }
            },
            )
        }
    }

    /**
     * A playlist's channels were matched against the mirror index.
     *
     * The expensive half of a mirror sync and the half that decides whether the guide has anything
     * to show, yet it has never been visible: a match rate collapsing after a provider renumbers
     * its catalog looks, from support's side, exactly like the app breaking.
     */
    fun mappingFinished(matched: Int, channels: Int, durationMs: Long) {
        runCatching {
            PostHog.capture(
                event = "epg_mapping",
                properties = mapOf(
                    "matched" to matched,
                    "channels" to channels,
                    "duration_ms" to durationMs,
                ),
            )
        }
    }
}

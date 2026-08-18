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
     * Which source is actually feeding the viewer's channels, once enough have resolved to mean
     * something ([EpgSourceLadder.MIN_REPORT_SAMPLE], once per account per session).
     *
     * This is the half `epg_ingest` and `epg_mapping` cannot show. Those measure the MIRROR — how
     * much of the backup we downloaded and how many channels it could match. Neither says anything
     * about the panel's own EPG, which is the primary source, so "13% matched" was being read as
     * "only 13% of my channels have a guide" when the two are independent and overlapping. This
     * event answers the actual question: of the channels this viewer encountered, how many were
     * fed by the panel, how many fell through to the mirror, and how many had nothing.
     *
     * Note the sample is what the viewer BROWSED, not the whole lineup — coverage as experienced,
     * which is the more useful number, but not a lineup-wide census. Counts only; no account,
     * host, or channel name.
     *
     * `none` counts channels with genuinely no guide anywhere; `unavailable` counts channels whose
     * panel ask failed with no mirror to fall back on. Reading them as one number is what made the
     * first field sample unreadable, so they are reported apart.
     */
    fun resolveTallied(manual: Int, provider: Int, mirror: Int, none: Int, unavailable: Int = 0, store: Int = 0) {
        val total = manual + store + provider + mirror + none + unavailable
        if (total <= 0) return
        runCatching {
            PostHog.capture(
                event = "epg_resolve",
                properties = mapOf(
                    "manual" to manual,
                    // Served from the account's own stored guide: zero network for this channel.
                    "store" to store,
                // Served from the account's own stored guide: zero network for this channel.
                "store" to store,
                    "provider" to provider,
                    "mirror" to mirror,
                    "none" to none,
                    // A FAILED panel ask, not a coverage fact. Kept apart from `none` because the
                    // two were conflated and the tally lied on the first field read: a saturated
                    // box reported none=68/80 for a panel another device resolved 37% from.
                    "unavailable" to unavailable,
                    "total" to total,
                ),
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

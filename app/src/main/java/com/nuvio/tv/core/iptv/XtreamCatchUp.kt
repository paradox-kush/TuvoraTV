package com.nuvio.tv.core.iptv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up (`tv_archive`) replay: which URL to ask a panel for, and whether a programme is old
 * enough to have been recorded but young enough to still be there.
 *
 * Pure so the dialects and the window can be pinned in tests — a wrong catch-up URL is a dead
 * channel rather than a degraded one, and the only way to find out against a real panel is to try
 * it on someone's live subscription.
 */
object XtreamCatchUp {

    /** The `start` shape every known panel expects, in UTC. */
    private const val START_PATTERN = "yyyy-MM-dd:HH-mm"

    /**
     * Panels interpret `start` in THEIR OWN timezone, so a panel in New York replaying a programme
     * we describe in UTC lands hours off.
     *
     * [serverOffsetMs] is the measured clock-pair offset ([ServerClockOffset]) and wins when known:
     * it is derived from the panel's own clocks, needs no timezone database, and stays right when
     * the panel's `timezone` field lies. [serverTimeZone] is the panel's self-reported
     * `server_info.timezone`, used only when no offset was measured.
     *
     * The fallback is UTC rather than the device's local time. Most panels never report a zone, and
     * UTC is what Tuvora has always sent — moving the default would silently shift replay for every
     * provider that works today, which is a worse trade than leaving the unknown case as it is.
     * An unusable zone string falls back the same way rather than throwing.
     */
    fun formatStart(startMs: Long, serverTimeZone: String? = null, serverOffsetMs: Long? = null): String {
        if (serverOffsetMs != null) {
            // Panel-local wall time is the UTC instant plus the panel's offset; formatting the
            // shifted instant as UTC prints exactly that wall time.
            return SimpleDateFormat(START_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(startMs + serverOffsetMs))
        }
        val zone = serverTimeZone?.takeIf { it.isNotBlank() }
            ?.let { id ->
                // getTimeZone() answers GMT for anything it does not recognise, which would look
                // like a deliberate UTC choice instead of a bad id.
                TimeZone.getTimeZone(id).takeIf { it.id == id || TimeZone.getAvailableIDs().contains(id) }
            }
            ?: TimeZone.getTimeZone("UTC")
        return SimpleDateFormat(START_PATTERN, Locale.US)
            .apply { timeZone = zone }
            .format(Date(startMs))
    }

    /** What the guide can offer for one programme. */
    enum class ProgrammeAction {
        /** Nothing playable: not broadcast yet, or gone from the panel. */
        NONE,

        /** Airing now on a channel with no archive — ordinary live playback. */
        PLAY_LIVE,

        /** Airing now, and the panel kept the beginning: restart from the top. */
        START_OVER,

        /** Finished, and still inside the panel's window. */
        REPLAY,
    }

    /**
     * Which action a guide cell should offer.
     *
     * Deliberately covers the airing programme as well as finished ones: a channel with an archive
     * can restart what is on right now, which is the catch-up affordance most viewers actually
     * reach for, and a channel WITHOUT an archive must still be watchable live rather than
     * offering nothing at all.
     *
     * [programmeHasArchive] is the per-programme `has_archive` flag from get_simple_data_table —
     * the panel saying, recording by recording, what it actually kept, which is the strongest
     * signal there is. POSITIVE override only: true makes the programme replayable past every
     * channel-level rule (the start must still have passed); false and null leave the channel
     * rules untouched, because many panels serve catch-up while never marking a single row.
     */
    fun actionFor(
        programmeStartMs: Long,
        programmeEndMs: Long,
        nowMs: Long,
        hasArchive: Boolean,
        catchUpDays: Int,
        programmeHasArchive: Boolean? = null,
    ): ProgrammeAction {
        // Degenerate EPG rows: a zero/negative-length programme, or a zero/epoch start (an absent
        // timestamp parses to 0). No real broadcast looks like this, and a replay URL built from
        // it is guaranteed dead — refuse before any other rule can offer one.
        if (programmeEndMs <= programmeStartMs || programmeStartMs <= 0) return ProgrammeAction.NONE
        if (programmeStartMs > nowMs) return ProgrammeAction.NONE
        val replayable = programmeHasArchive == true ||
            (hasArchive && isWithinWindow(programmeStartMs, nowMs, catchUpDays))
        val finished = programmeEndMs <= nowMs
        return when {
            finished && replayable -> ProgrammeAction.REPLAY
            finished -> ProgrammeAction.NONE
            replayable -> ProgrammeAction.START_OVER
            else -> ProgrammeAction.PLAY_LIVE
        }
    }

    /** Whole minutes, floored, never below one — a zero-length request plays nothing. */
    fun durationMinutes(startMs: Long, endMs: Long): Int =
        (((endMs - startMs) / 60_000L).toInt()).coerceAtLeast(1)

    /**
     * Whether a programme can still be replayed.
     *
     * [catchUpDays] comes from the panel's `tv_archive_duration`, which is frequently absent or
     * zero even on providers that do serve catch-up — `tv_archive` is the real flag. So an unknown
     * window permits replay rather than hiding a feature the provider supports; the panel's own
     * error is a better answer than a missing button. A programme that has not aired yet is never
     * replayable, whatever the window says.
     */
    fun isWithinWindow(programmeStartMs: Long, nowMs: Long, catchUpDays: Int): Boolean {
        if (programmeStartMs > nowMs) return false
        if (catchUpDays <= 0) return true
        return nowMs - programmeStartMs <= catchUpDays * 24L * 60 * 60 * 1000
    }

    /**
     * Every catch-up URL worth trying, best-known first.
     *
     * Panels do not agree on the shape and none of them advertise which they speak, so the caller
     * walks this list until one plays. The first entry is the XUI path form Tuvora already shipped
     * and must stay exactly that, or panels that work today would regress.
     */
    fun candidateUrls(
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int,
        startMs: Long,
        endMs: Long,
        containerExtension: String?,
        serverTimeZone: String? = null,
        serverOffsetMs: Long? = null,
    ): List<String> {
        // Blank credentials cannot form a playable URL — built anyway they become
        // `.../timeshift///60/...`, a failure that looks like a provider fault.
        if (username.isBlank() || password.isBlank()) return emptyList()
        val root = baseUrl.trimEnd('/')
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "ts"
        val start = formatStart(startMs, serverTimeZone, serverOffsetMs)
        val minutes = durationMinutes(startMs, endMs)
        val u = encode(username)
        val p = encode(password)
        val startPath = encode(start)
        val query = "username=$u&password=$p&stream=$streamId&start=$startPath&duration=$minutes"

        return listOf(
            // XUI standard, and what we already ship.
            "$root/timeshift/$u/$p/$minutes/$startPath/$streamId.$ext",
            // Same idea, id and start swapped — a common panel variant.
            "$root/timeshifts/$u/$p/$minutes/$streamId/$startPath.$ext",
            "$root/streaming/timeshift.php?$query&extension=$ext",
            "$root/streaming/timeshift.php?$query",
            "$root/timeshift.php?$query",
        ).distinct()
    }

    /**
     * Percent-encode a path/query segment. Credentials routinely contain characters that would
     * otherwise change the URL's shape — a `/` in a password silently adds a path segment.
     */
    private fun encode(value: String): String = buildString {
        value.forEach { c ->
            if (c.isLetterOrDigit() || c in UNRESERVED) append(c)
            else c.toString().toByteArray(Charsets.UTF_8).forEach { b ->
                append('%').append("%02X".format(b))
            }
        }
    }

    private const val UNRESERVED = "-_.~:"
}

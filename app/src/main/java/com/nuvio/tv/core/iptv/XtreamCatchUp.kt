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
     * we describe in UTC lands hours off. [serverTimeZone] is the panel's `server_info.timezone`
     * when we know it.
     *
     * The fallback is UTC rather than the device's local time. Most panels never report a zone, and
     * UTC is what Tuvora has always sent — moving the default would silently shift replay for every
     * provider that works today, which is a worse trade than leaving the unknown case as it is.
     * An unusable zone string falls back the same way rather than throwing.
     */
    fun formatStart(startMs: Long, serverTimeZone: String? = null): String {
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
    ): List<String> {
        val root = baseUrl.trimEnd('/')
        val ext = containerExtension?.takeIf { it.isNotBlank() } ?: "ts"
        val start = formatStart(startMs, serverTimeZone)
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

package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamCatchUpTest {

    private val start = 1_710_000_000_000L   // 2024-03-09 16:00 UTC
    private val end = start + 60 * 60_000L   // one hour later

    @Test
    fun `start is formatted in the panel's expected shape`() {
        assertEquals("2024-03-09:16-00", XtreamCatchUp.formatStart(start))
    }

    @Test
    fun `duration is whole minutes, and never zero`() {
        assertEquals(60, XtreamCatchUp.durationMinutes(start, end))
        assertEquals(90, XtreamCatchUp.durationMinutes(start, start + 90 * 60_000L))
        // A programme with no usable end still has to ask for something playable.
        assertEquals(1, XtreamCatchUp.durationMinutes(start, start))
        assertEquals(1, XtreamCatchUp.durationMinutes(start, start - 5_000L))
    }

    /**
     * Providers advertise a window in days via `tv_archive_duration`. Offering replay outside it
     * just fails, so the guide has to know before it shows the affordance.
     */
    @Test
    fun `a programme inside the provider's window is replayable`() {
        val now = start + 2 * DAY
        assertTrue(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = now, catchUpDays = 3))
    }

    @Test
    fun `a programme older than the window is not`() {
        val now = start + 5 * DAY
        assertFalse(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = now, catchUpDays = 3))
    }

    /** A future programme has not been recorded yet, whatever the window says. */
    @Test
    fun `a programme still to air is not replayable`() {
        assertFalse(
            XtreamCatchUp.isWithinWindow(programmeStartMs = start + DAY, nowMs = start, catchUpDays = 7)
        )
    }

    /**
     * Panels that report no window at all still serve catch-up — tv_archive is the flag, and
     * tv_archive_duration is frequently absent or zero. Treat that as "unknown, allow it" rather
     * than hiding a feature the provider does support.
     */
    @Test
    fun `an unknown window does not block replay`() {
        assertTrue(XtreamCatchUp.isWithinWindow(programmeStartMs = start, nowMs = start + DAY, catchUpDays = 0))
    }

    /**
     * Panels disagree about the catch-up URL shape, and a wrong guess is a dead channel rather than
     * a degraded one. These are the forms seen in the wild, best-known first: the XUI path form we
     * already shipped, the variant with the id and start SWAPPED, and the php form with and without
     * an explicit extension, at both the api path and the domain root.
     */
    @Test
    fun `every known panel dialect is offered, best-known first`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com",
            username = "user",
            password = "pass",
            streamId = 777,
            startMs = start,
            endMs = end,
            containerExtension = "ts",
        )

        assertEquals(
            listOf(
                "https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.ts",
                "https://example.com/timeshifts/user/pass/60/777/2024-03-09:16-00.ts",
                "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60&extension=ts",
                "https://example.com/streaming/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60",
                "https://example.com/timeshift.php?username=user&password=pass&stream=777&start=2024-03-09:16-00&duration=60",
            ),
            urls,
        )
    }

    /** The first candidate must stay byte-identical to what shipped, or working panels regress. */
    @Test
    fun `the first candidate is the form we already shipped`() {
        val first = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com/", username = "user", password = "pass",
            streamId = 777, startMs = start, endMs = end, containerExtension = null,
        ).first()
        assertEquals("https://example.com/timeshift/user/pass/60/2024-03-09:16-00/777.ts", first)
    }

    /**
     * Blank or whitespace credentials cannot form a playable URL — building one anyway yields
     * `.../timeshift///60/...`, which fails looking like a provider fault. No URL at all is the
     * honest answer (iptvnator returns none from the same state).
     */
    @Test
    fun `blank credentials build no urls`() {
        fun urls(user: String, pass: String) = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = user, password = pass,
            streamId = 7, startMs = start, endMs = end, containerExtension = "ts",
        )
        assertEquals(emptyList<String>(), urls("", "pass"))
        assertEquals(emptyList<String>(), urls("user", ""))
        assertEquals(emptyList<String>(), urls("   ", "pass"))
        assertEquals(emptyList<String>(), urls("user", "\t"))
        assertEquals(emptyList<String>(), urls("", ""))
    }

    @Test
    fun `credentials with url-unsafe characters are encoded`() {
        val first = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "a b", password = "p/s",
            streamId = 5, startMs = start, endMs = end, containerExtension = "ts",
        ).first()
        assertTrue("username must be encoded: $first", first.contains("a%20b"))
        assertTrue("password must be encoded: $first", first.contains("p%2Fs"))
    }

    /**
     * Panels interpret `start` in THEIR timezone, not ours, so a panel in New York replaying a
     * programme we describe in UTC lands hours off. When the panel tells us its timezone
     * (server_info.timezone) we have to speak it.
     */
    @Test
    fun `start is formatted in the panel's timezone when it tells us one`() {
        // 2024-03-09 16:00 UTC is 11:00 in New York (EST, UTC-5).
        assertEquals("2024-03-09:11-00", XtreamCatchUp.formatStart(start, "America/New_York"))
        // ...and 17:00 in Berlin (CET, UTC+1).
        assertEquals("2024-03-09:17-00", XtreamCatchUp.formatStart(start, "Europe/Berlin"))
    }

    /**
     * UTC stays the fallback, NOT the device's local time. Most panels never report a timezone, and
     * UTC is what Tuvora has always sent — switching the default would silently shift replay for
     * every provider that works today.
     */
    @Test
    fun `an unknown or unusable timezone falls back to UTC`() {
        assertEquals("2024-03-09:16-00", XtreamCatchUp.formatStart(start, null))
        assertEquals("2024-03-09:16-00", XtreamCatchUp.formatStart(start, ""))
        assertEquals("2024-03-09:16-00", XtreamCatchUp.formatStart(start, "Not/AZone"))
    }

    @Test
    fun `the timezone reaches the built urls`() {
        val url = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "u", password = "p",
            streamId = 1, startMs = start, endMs = end, containerExtension = "ts",
            serverTimeZone = "America/New_York",
        ).first()
        assertTrue("expected New York time in $url", url.contains("2024-03-09:11-00"))
    }

    /**
     * Credentials carrying the characters that would break a query string (&, =, #) must arrive
     * percent-encoded in the php-query dialects, or the panel reads a different set of params.
     */
    @Test
    fun `credentials with query-breaking characters survive the php forms`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "a&b", password = "p=q#r",
            streamId = 5, startMs = start, endMs = end, containerExtension = "ts",
        )
        val php = urls.filter { it.contains("timeshift.php") }
        assertEquals(3, php.size)
        php.forEach { url ->
            assertTrue(url, url.contains("username=a%26b"))
            assertTrue(url, url.contains("password=p%3Dq%23r"))
        }
        // ...and the raw separators appear nowhere in any dialect.
        urls.forEach { url ->
            assertFalse(url, url.contains("a&b"))
            assertFalse(url, url.contains("p=q#r"))
        }
    }

    /** A panel served under a path (reverse proxy / shared host) keeps that path in every dialect. */
    @Test
    fun `a base url with a path keeps the path`() {
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com/xtream/", username = "u", password = "p",
            streamId = 7, startMs = start, endMs = end, containerExtension = "ts",
        )
        assertEquals("https://example.com/xtream/timeshift/u/p/60/2024-03-09:16-00/7.ts", urls.first())
        urls.forEach { url -> assertTrue(url, url.startsWith("https://example.com/xtream/")) }
    }

    /**
     * The airing programme asks for its FULL scheduled duration, future end included — both
     * reference players send it unclamped and panels serve what exists so far. Pinned so nobody
     * "fixes" it into a clamp that truncates start-over.
     */
    @Test
    fun `an airing programme requests the full duration unclamped`() {
        // A 90-minute programme of which only a slice has aired: the request still says 90.
        val urls = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "u", password = "p",
            streamId = 7, startMs = start, endMs = start + 90 * 60_000L, containerExtension = "ts",
        )
        assertEquals("https://example.com/timeshift/u/p/90/2024-03-09:16-00/7.ts", urls.first())
        assertTrue(urls.last(), urls.last().contains("duration=90"))
    }

    /**
     * Partial minutes floor — rounding up would request a final fragment past the recording's
     * end, which some panels answer with an error for the whole request.
     */
    @Test
    fun `duration floors partial minutes`() {
        assertEquals(61, XtreamCatchUp.durationMinutes(start, start + 61 * 60_000L + 30_000L))
        assertEquals(59, XtreamCatchUp.durationMinutes(start, start + 60 * 60_000L - 1L))
    }

    /**
     * The clock-pair offset (ServerClockOffset) beats the timezone name: it needs no timezone
     * database and works when the panel's `timezone` field lies. formatStart applies it as a
     * plain shift and formats the shifted instant as UTC.
     */
    @Test
    fun `an explicit clock-pair offset shifts the start`() {
        assertEquals("2024-03-09:17-00", XtreamCatchUp.formatStart(start, serverOffsetMs = 3_600_000L))
        assertEquals("2024-03-09:11-00", XtreamCatchUp.formatStart(start, serverOffsetMs = -18_000_000L))
        // Half-hour zones exist (India +5:30) and must survive.
        assertEquals("2024-03-09:21-30", XtreamCatchUp.formatStart(start, serverOffsetMs = 19_800_000L))
        // A shift across midnight moves the date too.
        assertEquals("2024-03-10:01-00", XtreamCatchUp.formatStart(start, serverOffsetMs = 9 * 3_600_000L))
    }

    /** When both are known the measured offset wins — it is derived, the name is self-reported. */
    @Test
    fun `the offset wins over a timezone name`() {
        assertEquals(
            "2024-03-09:17-00",
            XtreamCatchUp.formatStart(start, serverTimeZone = "America/New_York", serverOffsetMs = 3_600_000L)
        )
    }

    @Test
    fun `the clock-pair offset reaches the built urls`() {
        val url = XtreamCatchUp.candidateUrls(
            baseUrl = "https://example.com", username = "u", password = "p",
            streamId = 1, startMs = start, endMs = end, containerExtension = "ts",
            serverOffsetMs = 3_600_000L,
        ).first()
        assertTrue("expected the +1h panel-local time in $url", url.contains("2024-03-09:17-00"))
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}

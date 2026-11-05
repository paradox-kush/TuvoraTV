package com.nuvio.tv.core.iptv

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0): pins every catch-up style and placeholder.
 */
class M3uCatchUpTest {

    private val startMs = Instant.parse("2024-03-05T12:34:56Z").toEpochMilli()
    private val endMs = startMs + 30 * 60_000L
    private val nowMs = startMs + 2 * 3_600_000L
    private val startS = startMs / 1000
    private val endS = endMs / 1000
    private val nowS = nowMs / 1000
    private val live = "http://h.example:8080/live/chan.ts"

    private fun info(type: M3uCatchUpType, source: String? = null, days: Int = 3) =
        M3uCatchUpInfo(type, source, days)

    private fun build(i: M3uCatchUpInfo, url: String = live, offsetMs: Long = 0L, end: Long = endMs) =
        M3uCatchUp.build(i, url, startMs, end, nowMs, offsetMs)

    // --- type mapping ---------------------------------------------------------

    @Test
    fun `catchUpType maps every known value`() {
        assertEquals("default", M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType("default", null))
        assertEquals("append", M3uCatchUpType.APPEND, M3uCatchUp.catchUpType("append", null))
        assertEquals("shift", M3uCatchUpType.SHIFT, M3uCatchUp.catchUpType("shift", null))
        assertEquals("timeshift", M3uCatchUpType.SHIFT, M3uCatchUp.catchUpType("timeshift", null))
        for (v in listOf("flussonic", "flussonic-hls", "flussonic-ts", "fs")) {
            assertEquals(v, M3uCatchUpType.FLUSSONIC, M3uCatchUp.catchUpType(v, null))
        }
        assertEquals("xc", M3uCatchUpType.XTREAM, M3uCatchUp.catchUpType("xc", null))
        assertEquals("xtream", M3uCatchUpType.XTREAM, M3uCatchUp.catchUpType("xtream", null))
    }

    @Test
    fun `catchUpType is case and space insensitive and defaults to DEFAULT`() {
        assertEquals("case", M3uCatchUpType.APPEND, M3uCatchUp.catchUpType("  APPEND ", null))
        assertEquals("null", M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType(null, null))
        assertEquals("blank", M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType(" ", "http://x/{utc}"))
    }

    @Test
    fun `unknown type with a source behaves like default and without is null`() {
        assertEquals(M3uCatchUpType.DEFAULT, M3uCatchUp.catchUpType("vod", "http://x/{utc}"))
        assertNull(M3uCatchUp.catchUpType("vod", null))
    }

    // --- append ---------------------------------------------------------------

    @Test
    fun `append adds the filled source after the live url`() {
        val i = info(M3uCatchUpType.APPEND, "?utc={utc}&lutc={lutc}")
        assertEquals("$live?utc=$startS&lutc=$nowS", build(i))
    }

    @Test
    fun `append without a source cannot build`() {
        assertNull(build(info(M3uCatchUpType.APPEND, null)))
    }

    // --- default: relative vs absolute ---------------------------------------

    @Test
    fun `default with an absolute source replaces the live url`() {
        val i = info(M3uCatchUpType.DEFAULT, "http://arch.example/rec/{utc}/{duration}.ts")
        assertEquals("http://arch.example/rec/$startS/${endS - startS}.ts", build(i))
    }

    @Test
    fun `default with a relative source is appended to the live url`() {
        val i = info(M3uCatchUpType.DEFAULT, "?start={utc}&end={utcend}")
        assertEquals("$live?start=$startS&end=$endS", build(i))
    }

    @Test
    fun `default absolute scheme check ignores case`() {
        val i = info(M3uCatchUpType.DEFAULT, "HTTP://a/{utc}")
        assertEquals("HTTP://a/$startS", build(i))
    }

    @Test
    fun `default without a source on a non-xtream url falls back to shift`() {
        assertEquals("$live?utc=$startS&lutc=$nowS", build(info(M3uCatchUpType.DEFAULT, null)))
    }

    @Test
    fun `default without a source on an xtream url uses the xtream timeshift link`() {
        val x = "http://h.example:8080/live/user/pass/4242.ts"
        assertEquals(
            "http://h.example:8080/timeshift/user/pass/30/2024-03-05:12-34/4242.ts",
            build(info(M3uCatchUpType.DEFAULT, null), x),
        )
    }

    // --- shift ----------------------------------------------------------------

    @Test
    fun `shift adds utc and lutc and respects an existing query`() {
        val i = info(M3uCatchUpType.SHIFT)
        assertEquals("$live?utc=$startS&lutc=$nowS", build(i))
        assertEquals("$live?token=1&utc=$startS&lutc=$nowS", build(i, "$live?token=1"))
    }

    @Test
    fun `shift ignores a source that is present`() {
        assertEquals("$live?utc=$startS&lutc=$nowS", build(info(M3uCatchUpType.SHIFT, "?x={utc}")))
    }

    @Test
    fun `shift without a source on an xtream url prefers the xtream link`() {
        val x = "http://h.example:8080/live/user/pass/4242.ts"
        assertEquals(
            "http://h.example:8080/timeshift/user/pass/30/2024-03-05:12-34/4242.ts",
            build(info(M3uCatchUpType.SHIFT), x),
        )
    }

    // --- flussonic ------------------------------------------------------------

    @Test
    fun `flussonic m3u8 form puts start and duration in the playlist name`() {
        val i = info(M3uCatchUpType.FLUSSONIC)
        assertEquals(
            "http://h.example:8080/ch1/index-$startS-1800.m3u8?token=a",
            build(i, "http://h.example:8080/ch1/index.m3u8?token=a"),
        )
        assertEquals(
            "http://h.example:8080/ch1/mono-$startS-1800.m3u8",
            build(i, "http://h.example:8080/ch1/mono.m3u8"),
        )
    }

    @Test
    fun `flussonic ts form uses timeshift_abs`() {
        val i = info(M3uCatchUpType.FLUSSONIC)
        assertEquals(
            "http://h.example:8080/ch1/timeshift_abs-$startS.ts",
            build(i, "http://h.example:8080/ch1/live.ts"),
        )
    }

    @Test
    fun `flussonic with a non-http url cannot build`() {
        assertNull(build(info(M3uCatchUpType.FLUSSONIC), "rtmp://h/ch1/x"))
    }

    // --- xtream ---------------------------------------------------------------

    @Test
    fun `xtream delegates to XtreamCatchUp timeshift shape`() {
        val x = "http://h.example:8080/live/user/pass/4242.m3u8?k=v"
        val expected = XtreamCatchUp.candidateUrls(
            "http://h.example:8080", "user", "pass", 4242, startMs, endMs, "m3u8",
            serverOffsetMs = null,
        ).first()
        assertEquals(expected, build(info(M3uCatchUpType.XTREAM), x).orEmpty().substringBefore('?'))
        assertEquals("k=v", build(info(M3uCatchUpType.XTREAM), x)!!.substringAfter('?'))
    }

    @Test
    fun `xtream defaults the container to ts and honours the offset`() {
        val x = "http://h.example:8080/user/pass/4242"
        assertEquals(
            "http://h.example:8080/timeshift/user/pass/30/2024-03-05:14-34/4242.ts",
            build(info(M3uCatchUpType.XTREAM), x, offsetMs = 2 * 3_600_000L),
        )
    }

    @Test
    fun `xtream with a url that is not xtream shaped cannot build`() {
        assertNull(build(info(M3uCatchUpType.XTREAM), "http://h/stream/abc.ts"))
    }

    // --- placeholders ---------------------------------------------------------

    private fun fill(template: String, offsetMs: Long = 0L) =
        build(info(M3uCatchUpType.APPEND, template), "", offsetMs)

    @Test
    fun `plain placeholders with and without dollar`() {
        val d = endS - startS
        assertEquals(
            "$startS $startS $endS $endS $nowS $nowS $nowS $d ${nowS - startS}",
            fill("{utc} \${start} {utcend} \${end} {lutc} {now} {timestamp} {duration} {offset}"),
        )
    }

    @Test
    fun `duration and offset with divisors`() {
        assertEquals("30", fill("{duration:60}"))
        assertEquals("${(nowS - startS) / 60}", fill("\${offset:60}"))
        assertEquals("1800", fill("{duration:0}"))
    }

    @Test
    fun `formatted placeholders are UTC`() {
        assertEquals("20240305123456", fill("{utc:YmdHMS}"))
        assertEquals("2024-03-05T12:34", fill("\${start:Y-m-dTH:M}"))
        assertEquals("20240305130456", fill("{utcend:YmdHMS}"))
        assertEquals("20240305143456", fill("{lutc:YmdHMS}"))
        assertEquals("20240305143456", fill("{now:YmdHMS}"))
        assertEquals("20240305143456", fill("{timestamp:YmdHMS}"))
    }

    @Test
    fun `formatted placeholders ignore the local offset`() {
        assertEquals("20240305123456", fill("{utc:YmdHMS}", offsetMs = 5 * 3_600_000L))
    }

    @Test
    fun `bare local time placeholders use the explicit offset`() {
        assertEquals("2024/03/05 12:34:56", fill("{Y}/{m}/{d} {H}:{M}:{S}"))
        assertEquals("2024/03/05 17:34:56", fill("{Y}/{m}/{d} {H}:{M}:{S}", 5 * 3_600_000L))
    }

    @Test
    fun `bare local placeholders roll the date over with a negative offset`() {
        // 12:34 UTC minus 13h = 23:34 the previous day
        assertEquals("2024-03-04 23", fill("{Y}-{m}-{d} {H}", -13 * 3_600_000L))
    }

    @Test
    fun `duration never falls under one minute`() {
        assertEquals("60", build(info(M3uCatchUpType.APPEND, "{duration}"), "", 0L, startMs + 5_000))
    }

    // --- isAvailable ----------------------------------------------------------

    @Test
    fun `isAvailable respects the day window and future programmes`() {
        val i = info(M3uCatchUpType.DEFAULT, null, days = 2)
        assertTrue("recent", M3uCatchUp.isAvailable(i, nowMs - 3_600_000, nowMs))
        assertTrue("edge", M3uCatchUp.isAvailable(i, nowMs - 2 * 86_400_000L, nowMs))
        assertFalse("too old", M3uCatchUp.isAvailable(i, nowMs - 2 * 86_400_000L - 1, nowMs))
        assertFalse("future", M3uCatchUp.isAvailable(i, nowMs + 1, nowMs))
        assertFalse("not started yet at now", M3uCatchUp.isAvailable(i, nowMs, nowMs))
        assertFalse("no catch-up", M3uCatchUp.isAvailable(null, nowMs - 1000, nowMs))
    }

    @Test
    fun `isAvailable treats zero days as one day`() {
        val i = info(M3uCatchUpType.DEFAULT, null, days = 0)
        assertTrue(M3uCatchUp.isAvailable(i, nowMs - 80_000_000L, nowMs))
        assertFalse(M3uCatchUp.isAvailable(i, nowMs - 90_000_000L, nowMs))
    }

    // --- alternatives / link forms -------------------------------------------

    private val ts = "http://h:80/timeshift/u/p/30/2024-03-05:12-34/42.ts"
    private val hls = "http://h:80/timeshift/u/p/30/2024-03-05:12-34/42.m3u8"

    @Test
    fun `alternatives from a ts link offers hls first then php`() {
        val alts = M3uCatchUp.alternatives(ts)
        assertEquals(hls, alts.first())
        assertEquals(
            "http://h:80/streaming/timeshift.php?username=u&password=p&stream=42&start=2024-03-05:12-34&duration=30",
            alts[1],
        )
        assertEquals(2, alts.size)
    }

    @Test
    fun `alternatives from an hls link offers ts first`() {
        assertEquals(ts, M3uCatchUp.alternatives(hls).first())
    }

    @Test
    fun `alternatives order is ext swap aired short php then other clock`() {
        val alts = M3uCatchUp.alternatives(
            url = ts, offsetMs = 3_600_000L, otherOffsetMs = 0L, showMinutes = 20, airedMinutes = 10,
        )
        val base = "http://h:80/timeshift/u/p"
        assertEquals(
            listOf(
                "$base/30/2024-03-05:12-34/42.m3u8",
                "$base/10/2024-03-05:12-34/42.ts",
                "$base/10/2024-03-05:12-34/42.m3u8",
                "$base/20/2024-03-05:12-34/42.ts",
                "$base/20/2024-03-05:12-34/42.m3u8",
                "http://h:80/streaming/timeshift.php?username=u&password=p&stream=42&start=2024-03-05:12-34&duration=30",
                "$base/30/2024-03-05:11-34/42.ts",
            ),
            alts,
        )
    }

    @Test
    fun `alternatives skip windows not shorter than the original`() {
        val alts = M3uCatchUp.alternatives(ts, showMinutes = 30, airedMinutes = 45)
        assertEquals(2, alts.size)
    }

    @Test
    fun `alternatives for a non timeshift url is the ts fallback or nothing`() {
        assertEquals(emptyList<String>(), M3uCatchUp.alternatives("http://h/x/y.ts"))
        assertEquals(
            listOf("http://h/timeshift/odd.ts?a=1"),
            M3uCatchUp.alternatives("http://h/timeshift/odd.m3u8?a=1"),
        )
    }

    @Test
    fun `alternatives keep the query string on the timeshift forms`() {
        assertEquals("$hls?x=1", M3uCatchUp.alternatives("$ts?x=1").first())
    }

    @Test
    fun `linkForm names the kind of link`() {
        assertEquals("ts", M3uCatchUp.linkForm(ts, ts))
        assertEquals("hls", M3uCatchUp.linkForm(hls, ts))
        assertEquals("php", M3uCatchUp.linkForm("http://h/streaming/timeshift.php?a=1", ts))
        assertEquals("ts+clock", M3uCatchUp.linkForm("http://h:80/timeshift/u/p/30/2024-03-05:11-34/42.ts", ts))
        assertEquals("hls+short", M3uCatchUp.linkForm("http://h:80/timeshift/u/p/10/2024-03-05:12-34/42.m3u8", ts))
    }

    @Test
    fun `preferForm puts the remembered form first`() {
        val php = "http://h:80/streaming/timeshift.php?username=u"
        val alts = listOf(ts, php)
        assertEquals(listOf(hls) + alts, M3uCatchUp.preferForm(hls, alts, null))
        assertEquals(listOf(ts, hls, php), M3uCatchUp.preferForm(hls, alts, "ts"))
        assertEquals(listOf(php, hls, ts), M3uCatchUp.preferForm(hls, alts, "php"))
        assertEquals(listOf(hls, ts, php), M3uCatchUp.preferForm(hls, alts, "hls"))
    }

    @Test
    fun `preferForm does not return to hls once the primary is ts`() {
        val alts = listOf(hls)
        assertEquals(listOf(ts, hls), M3uCatchUp.preferForm(ts, alts, "hls"))
    }

    @Test
    fun `preferForm falls back from short to plain when no short link exists`() {
        assertEquals(listOf(ts, hls), M3uCatchUp.preferForm(ts, listOf(hls), "ts+short"))
    }

    @Test
    fun `preferForm with an unknown form keeps the order`() {
        assertEquals(listOf(ts, hls), M3uCatchUp.preferForm(ts, listOf(hls), "bogus"))
    }

    @Test
    fun `wholeMinutes recognises xtream replay links`() {
        assertTrue(M3uCatchUp.wholeMinutes(ts))
        assertTrue(M3uCatchUp.wholeMinutes("http://h/streaming/timeshift.php?a=1"))
        assertFalse(M3uCatchUp.wholeMinutes("http://h/x?utc=1"))
    }
}

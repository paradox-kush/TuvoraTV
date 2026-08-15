package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.iptv.content.EpgProgramme
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * `get_simple_data_table` — the only Xtream endpoint that returns PAST programmes, and therefore
 * the one that makes "days back" real. It is stream-parsed because the XMLTV OOM was caused by
 * materializing a guide body, and a full week of a busy channel is the same shape of payload.
 */
class XtreamSimpleDataTableTest {

    private val now = 1_710_000_000_000L
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun row(
        title: String = b64("Gardeners' World"),
        desc: String? = b64("Monty Don visits a walled garden."),
        startSec: Long,
        stopSec: Long,
        hasArchive: String? = "1",
    ): String = buildString {
        append("""{"id":"9","epg_id":"12","title":"$title",""")
        append(""""lang":"","start":"2026-08-15 18:30:00","end":"2026-08-15 19:30:00",""")
        if (desc != null) append(""""description":"$desc",""")
        append(""""channel_id":"bbc.one","start_timestamp":"$startSec","stop_timestamp":"$stopSec",""")
        append(""""now_playing":0""")
        if (hasArchive != null) append(""","has_archive":$hasArchive""")
        append("}")
    }

    private fun body(vararg rows: String): Buffer =
        Buffer().writeUtf8("""{"epg_listings":[${rows.joinToString(",")}]}""")

    private fun parse(
        buffer: Buffer,
        catchUpDays: Int = 7,
    ): List<EpgProgramme> {
        val out = ArrayList<EpgProgramme>()
        XtreamSimpleDataTable.parseInto(buffer, "chan-1", now, catchUpDays) { out.add(it) }
        return out
    }

    @Test
    fun `a row inside the window is parsed whole`() {
        val start = (now - 3 * hour) / 1000
        val stop = (now - 2 * hour) / 1000
        val rows = parse(body(row(startSec = start, stopSec = stop)))
        assertEquals("one row", 1, rows.size)
        val p = rows.single()
        assertEquals("channel", "chan-1", p.channelId)
        assertEquals("title decoded", "Gardeners' World", p.title)
        assertEquals("description decoded", "Monty Don visits a walled garden.", p.desc)
        assertEquals("start in ms", start * 1000, p.startMs)
        assertEquals("end in ms", stop * 1000, p.endMs)
        assertTrue("archive mark carried", p.hasArchive)
    }

    /**
     * The parse-window filter is what keeps a week of a 24h channel from reaching the heap. It runs
     * per row inside the stream, so an out-of-window row is never even constructed as a programme.
     */
    @Test
    fun `rows outside the parse window are skipped`() {
        val old = (now - 30 * day) / 1000
        val soon = (now - 2 * hour) / 1000
        val far = (now + 40 * hour) / 1000
        val rows = parse(
            body(
                row(startSec = old, stopSec = old + 3600),
                row(startSec = soon, stopSec = soon + 3600),
                row(startSec = far, stopSec = far + 3600),
            )
        )
        assertEquals("only the in-window row survives", 1, rows.size)
        assertEquals("the surviving row", soon * 1000, rows.single().startMs)
    }

    /**
     * Malformed rows are the panel's normal output, not an exception: absent timestamps parse to 0,
     * some rows end before they start, and some carry no numbers at all. Every one of them must be
     * skipped without aborting the rest of the body.
     */
    @Test
    fun `malformed rows are skipped without losing the good ones`() {
        val good = (now - 2 * hour) / 1000
        val rows = parse(
            body(
                """{"title":"","start_timestamp":"","stop_timestamp":"","has_archive":1}""",
                """{"title":"x","start_timestamp":"0","stop_timestamp":"0"}""",
                row(startSec = good + 3600, stopSec = good),   // ends before it starts
                """{"title":"x","start_timestamp":"notanumber","stop_timestamp":"alsonot"}""",
                row(startSec = good, stopSec = good + 3600),
            )
        )
        assertEquals("only the well-formed row survives", 1, rows.size)
        assertEquals("the surviving row", good * 1000, rows.single().startMs)
    }

    /** Panels disagree on the encoding of every field; has_archive arrives int, string or absent. */
    @Test
    fun `has_archive parses from int string and absent`() {
        val start = (now - 2 * hour) / 1000
        fun mark(raw: String?) =
            parse(body(row(startSec = start, stopSec = start + 3600, hasArchive = raw))).single().hasArchive
        assertTrue("int 1", mark("1"))
        assertTrue("string \"1\"", mark("\"1\""))
        assertEquals("int 0", false, mark("0"))
        assertEquals("absent", false, mark(null))
    }

    /** A missing description is a null column, not an empty string — the sheet can tell them apart. */
    @Test
    fun `a row with no description stores null`() {
        val start = (now - 2 * hour) / 1000
        val p = parse(body(row(desc = null, startSec = start, stopSec = start + 3600))).single()
        assertEquals("no description", null, p.desc)
    }

    /**
     * Some panels base64 the text and some send it plain. Decoding blindly turns a short plain
     * title ("News" is valid base64) into mojibake, so the decode has to keep the original when
     * what came out is not text.
     */
    @Test
    fun `base64 text falls back to plain text`() {
        assertEquals("real base64", "Match of the Day", XtreamSimpleDataTable.decodeText(b64("Match of the Day")))
        assertEquals("plain with spaces", "Match of the Day", XtreamSimpleDataTable.decodeText("Match of the Day"))
        assertEquals("plain but base64-shaped", "News", XtreamSimpleDataTable.decodeText("News"))
        assertEquals("plain short word", "Film", XtreamSimpleDataTable.decodeText("Film"))
        assertEquals("empty", "", XtreamSimpleDataTable.decodeText(null))
        assertEquals("blank", "", XtreamSimpleDataTable.decodeText("   "))
        assertEquals("utf-8 survives", "Grüße", XtreamSimpleDataTable.decodeText(b64("Grüße")))
    }

    /** A panel that errors mid-session answers with an object where the listings should be. */
    @Test
    fun `an unexpected body yields nothing rather than throwing`() {
        assertEquals(
            "user_info instead of listings",
            0,
            parse(Buffer().writeUtf8("""{"user_info":{"status":"Expired"}}""")).size,
        )
        assertEquals("a bare array", 0, parse(Buffer().writeUtf8("[]")).size)
    }

    /**
     * A handful of panels key the listings by index instead of shipping an array. Same rows, one
     * character of difference — worth surviving rather than reading as an empty guide.
     */
    @Test
    fun `listings keyed as an object parse the same as an array`() {
        val start = (now - 2 * hour) / 1000
        val r = row(startSec = start, stopSec = start + 3600)
        val buffer = Buffer().writeUtf8("""{"epg_listings":{"0":$r}}""")
        assertEquals("object-shaped listings", 1, parse(buffer).size)
    }

    /**
     * Timestamps in milliseconds put programmes in the year 52,000. iptvnator normalizes both
     * shapes; so do we, because a panel that does this would otherwise show an empty guide.
     */
    @Test
    fun `millisecond timestamps are normalized to seconds`() {
        val startMs = now - 2 * hour
        val buffer = Buffer().writeUtf8(
            """{"epg_listings":[{"title":"x","start_timestamp":"$startMs","stop_timestamp":"${startMs + hour}"}]}"""
        )
        val p = parse(buffer).single()
        assertEquals("start read as ms", startMs, p.startMs)
    }

    @Test
    fun `the row count is what reached the sink`() {
        val start = (now - 2 * hour) / 1000
        val old = (now - 30 * day) / 1000
        val n = XtreamSimpleDataTable.parseInto(
            body(row(startSec = old, stopSec = old + 3600), row(startSec = start, stopSec = start + 3600)),
            "chan-1",
            now,
            7,
        ) { }
        assertEquals("kept rows only", 1, n)
    }
}

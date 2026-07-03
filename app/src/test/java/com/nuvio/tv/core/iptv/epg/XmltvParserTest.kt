package com.nuvio.tv.core.iptv.epg

import com.nuvio.tv.core.iptv.content.EpgProgramme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Pins XMLTV parsing: the 14-digit time -> UTC conversion (pure) and the streaming programme walk
 * with channel filtering (needs android.util.Xml -> Robolectric, same as IptvContentDbTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class XmltvParserTest {

    // --- time parsing -------------------------------------------------------

    /** Millis for a UTC wall-clock (helper — mirrors what parseXmltvTime should produce at +0000). */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun `explicit +0000 offset parses as UTC`() {
        assertEquals(utc(2026, 7, 2, 18, 0, 0), XmltvParser.parseXmltvTime("20260702180000 +0000"))
    }

    @Test
    fun `positive offset is subtracted to reach UTC`() {
        // 18:00 at +0200 is 16:00 UTC.
        assertEquals(utc(2026, 7, 2, 16, 0, 0), XmltvParser.parseXmltvTime("20260702180000 +0200"))
    }

    @Test
    fun `negative offset is added to reach UTC`() {
        // 18:00 at -0530 is 23:30 UTC.
        assertEquals(utc(2026, 7, 2, 23, 30, 0), XmltvParser.parseXmltvTime("20260702180000 -0530"))
    }

    @Test
    fun `no offset is treated as UTC (spec fallback)`() {
        assertEquals(utc(2026, 7, 2, 18, 0, 0), XmltvParser.parseXmltvTime("20260702180000"))
    }

    @Test
    fun `offset with no space still parses`() {
        assertEquals(utc(2026, 7, 2, 16, 0, 0), XmltvParser.parseXmltvTime("20260702180000+0200"))
    }

    @Test
    fun `malformed times return null`() {
        assertNull(XmltvParser.parseXmltvTime(null))
        assertNull(XmltvParser.parseXmltvTime(""))
        assertNull(XmltvParser.parseXmltvTime("2026070218"))          // too short
        assertNull(XmltvParser.parseXmltvTime("2026XX02180000"))      // non-digit
        assertNull(XmltvParser.parseXmltvTime("20261302180000"))      // month 13
    }

    // --- streaming parse + channel filter -----------------------------------

    private val XMLTV = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="BBC.uk"><display-name>BBC One</display-name></channel>
          <channel id="cnn.us"><display-name>CNN</display-name></channel>
          <programme start="20260702180000 +0000" stop="20260702190000 +0000" channel="bbc.uk">
            <title>Evening News</title><desc>The headlines</desc>
          </programme>
          <programme start="20260702190000 +0000" stop="20260702200000 +0000" channel="bbc.uk">
            <title>Drama</title>
          </programme>
          <programme start="20260702180000 +0000" stop="20260702190000 +0000" channel="espn.us">
            <title>Not in the playlist</title>
          </programme>
          <programme start="20260702200000 +0000" stop="20260702180000 +0000" channel="bbc.uk">
            <title>Bad window (stop before start)</title>
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(xml: String, filter: Set<String>): List<EpgProgramme> {
        val parser = android.util.Xml.newPullParser().apply {
            setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        return buildList { XmltvParser.parseProgrammes(parser, filter) { add(it) } }
    }

    @Test
    fun `filters to playlist channels normalizes ids and drops bad windows`() {
        // Only bbc.uk is in the playlist; the ATTR id "BBC.uk" in the doc must match normalized.
        val programmes = parse(XMLTV, setOf("bbc.uk"))
        // 2 valid bbc.uk programmes (espn.us filtered out, the reversed-window one dropped).
        assertEquals(2, programmes.size)
        assertTrue(programmes.all { it.channelId == "bbc.uk" })
        assertEquals("Evening News", programmes[0].title)
        assertEquals("The headlines", programmes[0].desc)
        assertEquals(utc(2026, 7, 2, 18, 0, 0), programmes[0].startMs)
        assertEquals(utc(2026, 7, 2, 19, 0, 0), programmes[0].endMs)
        // second programme has a title but no desc
        assertEquals("Drama", programmes[1].title)
        assertNull(programmes[1].desc)
    }

    @Test
    fun `channel not in filter yields nothing`() {
        assertTrue(parse(XMLTV, setOf("nonexistent.tv")).isEmpty())
    }
}

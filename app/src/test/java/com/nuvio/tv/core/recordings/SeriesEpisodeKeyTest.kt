package com.nuvio.tv.core.recordings

import com.nuvio.tv.core.recordings.internal.SeriesEpisodeKey
import com.nuvio.tv.core.recordings.internal.SeriesEpisodeKey.Key
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SeriesEpisodeKeyTest {

    private fun norm(raw: String?) = SeriesEpisodeKey.fromText(raw)?.normalized

    @Test
    fun `season and episode in the usual forms`() {
        val forms = listOf(
            "S02E05", "s2e5", "S2 E5", "S02.E05", "2x05", "Season 2 Episode 5", "Season 2, Episode 5",
            "s02e05", "S02 E05", "S2E5",
        )
        for (f in forms) assertEquals("form '$f'", "S02E05", norm(f))
    }

    @Test
    fun `episode only forms`() {
        assertEquals("E12", norm("Ep. 12"))
        assertEquals("E12", norm("E12"))
        assertEquals("E12", norm("Ep 12"))
        assertEquals("E12", norm("Episode 12"))
        assertEquals(Key(null, 12), SeriesEpisodeKey.fromText("E12"))
    }

    @Test
    fun `the key carries season and episode numbers`() {
        assertEquals(Key(2, 5), SeriesEpisodeKey.fromText("S02E05"))
        assertEquals(Key(10, 123), SeriesEpisodeKey.fromText("S10E123"))
        assertEquals("S10E123", Key(10, 123).normalized)
        assertEquals("E05", Key(null, 5).normalized)
    }

    @Test
    fun `xmltv_ns is zero based`() {
        assertEquals("S02E05", norm("1.4."))
        assertEquals("S02E05", norm("1.4/10.0/1"))
        assertEquals("S01E01", norm("0.0.0/1"))
        assertEquals("E05", norm(".4."))
        assertEquals("E05", norm(".4/10."))
    }

    @Test
    fun `fromXmltvNs reads the same forms`() {
        assertEquals(Key(2, 5), SeriesEpisodeKey.fromXmltvNs("1.4."))
        assertEquals(Key(2, 5), SeriesEpisodeKey.fromXmltvNs("1.4/10.0/1"))
        assertEquals(Key(null, 5), SeriesEpisodeKey.fromXmltvNs(".4."))
        assertNull("no episode", SeriesEpisodeKey.fromXmltvNs("1."))
        assertNull("not xmltv_ns", SeriesEpisodeKey.fromXmltvNs("S02E05"))
        assertNull(SeriesEpisodeKey.fromXmltvNs(null))
        assertNull(SeriesEpisodeKey.fromXmltvNs(""))
    }

    @Test
    fun `a year used as the season is rejected`() {
        assertNull(norm("S2026E609"))
        assertNull(norm("S2026 E609"))
        assertNull(norm("Season 2026 Episode 609"))
        assertNull("xmltv with a year as the season", SeriesEpisodeKey.fromXmltvNs("2025.608."))
        assertNull(norm("2025.608."))
    }

    @Test
    fun `a season of 100 or more is rejected and 99 is fine`() {
        assertNull(SeriesEpisodeKey.fromXmltvNs("99.0."))
        assertEquals(Key(99, 1), SeriesEpisodeKey.fromXmltvNs("98.0."))
        assertNull(norm("S100E01"))
    }

    @Test
    fun `plain ids are rejected`() {
        assertNull(norm("609178259"))
        assertNull(norm("12345"))
        assertNull(norm("EP012345678"))
        assertNull(norm("E609178259"))
    }

    @Test
    fun `blank and null are rejected`() {
        assertNull(norm(null))
        assertNull(norm(""))
        assertNull(norm("   "))
        assertNull(norm("Part One"))
        assertNull(norm("Finale"))
    }

    @Test
    fun `episode zero is rejected`() {
        assertNull(norm("E0"))
        assertNull(norm("S01E00"))
    }

    @Test
    fun `a screen resolution is not an episode`() {
        assertNull(norm("1920x1080"))
    }

    @Test
    fun `fromProgramme prefers the guide's episode field then the title then the description`() {
        assertEquals(Key(2, 5), SeriesEpisodeKey.fromProgramme("Show S01E01", "Desc S03E03", "1.4."))
        assertEquals(Key(1, 1), SeriesEpisodeKey.fromProgramme("Show S01E01", "Desc S03E03", null))
        assertEquals("a junk field falls through", Key(1, 1), SeriesEpisodeKey.fromProgramme("Show S01E01", "Desc S03E03", "609178259"))
        assertEquals(Key(3, 3), SeriesEpisodeKey.fromProgramme("Show", "Desc S03E03 more", null))
        assertNull(SeriesEpisodeKey.fromProgramme("Show", "Nothing here", null))
    }

    @Test
    fun `the description can carry an episode label in running text`() {
        assertEquals(Key(4, 9), SeriesEpisodeKey.fromProgramme("Show", "Season 4 Episode 9. The one where it ends.", null))
    }
}

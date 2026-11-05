package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.M3uCatchUp
import com.nuvio.tv.core.iptv.M3uCatchUpInfo
import com.nuvio.tv.core.iptv.M3uCatchUpType
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import org.junit.Test
import org.junit.Assert.assertEquals
import com.nuvio.tv.core.recordings.assertNotNull
import org.junit.Assert.assertNull

class RecordingChannelFactoryTest {

    private fun xtream(hasArchive: Boolean = true, days: Int = 7, ua: String? = null, offset: Long? = null) = RecordingChannelFactory.xtream(
        contentId = "xtream:acc:live:42", accountId = "acc", name = "News", logo = "http://logo", liveUrl = "http://h/live/u/p/42.ts",
        userAgent = ua, baseUrl = "http://h", username = "u", password = "p", streamId = 42,
        hasArchive = hasArchive, catchUpDays = days, serverOffsetMs = offset,
    )

    @Test
    fun `an xtream channel is keyed by its content id and its playlist`() {
        val ch = xtream()
        assertEquals("xtream:acc:live:42", ch.key)
        assertEquals("acc", ch.sourceId)
        assertEquals("News", ch.name)
        assertEquals("http://h/live/u/p/42.ts", ch.liveUrl)
    }

    @Test
    fun `an xtream archive carries the panel facts`() {
        val cu = xtream(days = 7, offset = 3_600_000L).catchUp as RecordingCatchUp.Xtream
        assertEquals("http://h", cu.baseUrl)
        assertEquals("u", cu.username)
        assertEquals("p", cu.password)
        assertEquals(42, cu.streamId)
        assertEquals(7, cu.days)
        assertEquals(3_600_000L, cu.serverOffsetMs)
    }

    @Test
    fun `a channel with no archive has no catch-up to save from`() {
        assertNull(xtream(hasArchive = false).catchUp)
    }

    @Test
    fun `an archive whose panel gave no window assumes the default days`() {
        assertEquals(RecordingChannelFactory.DEFAULT_ARCHIVE_DAYS, xtream(days = 0).catchUp?.days)
    }

    @Test
    fun `the playlist user agent becomes a request header and blank adds none`() {
        assertEquals(mapOf("User-Agent" to "Tuvora/1"), xtream(ua = " Tuvora/1 ").headers)
        assertEquals(emptyMap<String, String>(), xtream(ua = "  ").headers)
        assertEquals(emptyMap<String, String>(), xtream(ua = null).headers)
    }

    @Test
    fun `an m3u channel keeps its catchup attributes and correction offset`() {
        val ch = RecordingChannelFactory.m3u(
            contentId = "m3u:acc:live:9", accountId = "acc", name = "Sport", logo = null, liveUrl = "http://h/9.ts",
            userAgent = "UA", info = M3uCatchUpInfo(M3uCatchUpType.SHIFT, source = null, days = 4), offsetMs = 120_000L,
        )
        val cu = ch.catchUp as RecordingCatchUp.M3u
        assertEquals(4, cu.days)
        assertEquals(120_000L, cu.offsetMs)
        assertEquals(mapOf("User-Agent" to "UA"), ch.headers)
    }

    @Test
    fun `an m3u channel without catchup attributes has none`() {
        val ch = RecordingChannelFactory.m3u("m3u:acc:live:9", "acc", "Sport", null, "http://h/9.ts", null, info = null, offsetMs = 0)
        assertNull(ch.catchUp)
    }

    @Test
    fun `every m3u type name reads back to the same type`() {
        M3uCatchUpType.entries.forEach { type ->
            val name = RecordingChannelFactory.m3uTypeName(type)
            assertEquals("$type via '$name'", type, M3uCatchUp.catchUpType(name, source = "x"))
            assertNotNull(name)
        }
    }

    @Test
    fun `a playlist type without saveable catch-up has no catch-up`() {
        assertNull(RecordingChannelFactory.withoutCatchUp("stalker:acc:live:1", "acc", "Ch", null, "http://h/x", null).catchUp)
    }
}

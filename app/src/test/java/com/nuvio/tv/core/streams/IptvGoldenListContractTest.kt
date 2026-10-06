package com.nuvio.tv.core.streams

import com.nuvio.tv.core.contracts.OwnSourcePolicy
import com.nuvio.tv.core.iptv.IptvSourceRegistrations
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.match.XtreamStreamSource
import com.nuvio.tv.core.player.AddonSubtitleIdPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GOLDEN-LIST CONTRACT for Wave 3 / J4 (media-servers design 5.1, TV twin of Mobile's IptvGoldenListContractTest):
 * making sources plural must leave IPTV behaviour byte-identical. The tables were recorded from the TV code
 * BEFORE the seam existed (hard-coded "xtream:" / "m3u:" prefixes) and must never change in a seam commit -
 * only the registration line in [wire]/[unwire] may. If a row has to change, IPTV behaviour changed and that
 * needs its own reviewed decision.
 *
 * Every input goes through the facades production call sites use (PlaybackAvailability, XtreamItemRegistry,
 * XtreamStreamSource, AddonSubtitleIdPolicy), with IPTV registered the way the composition root registers it.
 */
class IptvGoldenListContractTest {

    @Before
    fun wire() {
        OwnSourcePolicy.resetForTest()
        IptvSourceRegistrations.register()
    }

    @After
    fun unwire() = OwnSourcePolicy.resetForTest()

    private val contentIds: List<String?> = listOf(
        "xtream:acc1:vod:101",
        "xtream:acc1:live:55",
        "xtream:acc1:series:9",
        "xtream:stalker|http://p.example:80/c/|00:1a:79:aa:bb:cc:live:7",
        "xtream:",
        "xtream",
        "xtreamx:acc:vod:1",
        "XTREAM:acc:vod:1",
        " xtream:a:vod:1",
        "m3u:abc",
        "m3u:",
        "m3ux:abc",
        "tt0111161",
        "tmdb:603",
        "kitsu:1:2",
        "",
    )

    private val deferredUrls: List<String?> = listOf(
        "stalker-deferred:acc1|movie|12|Heat",
        "stalker-deferred:stalker|http://p.example|00:1a:79:aa:bb:cc|episode|3|1|2",
        "stalker-deferred",
        "http://panel.example/movie/u/p/1.mp4",
        "",
        null,
    )

    private fun Boolean.flag() = if (this) "T" else "F"

    private fun contentRow(id: String?): String {
        if (id == null) return "<null>"
        val prefix = PlaybackAvailability.isIptvId(id)
        val xtream = XtreamItemRegistry().isXtreamId(id)
        val live = XtreamItemRegistry.isLiveContentId(id)
        val scoped = AddonSubtitleIdPolicy.isProviderScoped(id)
        return "$id -> prefix=${prefix.flag()} xtream=${xtream.flag()} live=${live.flag()} subtitleScoped=${scoped.flag()}"
    }

    @Test
    fun `content id recognition is frozen`() {
        val expected = """
            |xtream:acc1:vod:101 -> prefix=T xtream=T live=F subtitleScoped=T
            |xtream:acc1:live:55 -> prefix=T xtream=T live=T subtitleScoped=T
            |xtream:acc1:series:9 -> prefix=T xtream=T live=F subtitleScoped=T
            |xtream:stalker|http://p.example:80/c/|00:1a:79:aa:bb:cc:live:7 -> prefix=T xtream=T live=T subtitleScoped=T
            |xtream: -> prefix=T xtream=T live=F subtitleScoped=T
            |xtream -> prefix=F xtream=F live=F subtitleScoped=F
            |xtreamx:acc:vod:1 -> prefix=F xtream=F live=F subtitleScoped=F
            |XTREAM:acc:vod:1 -> prefix=F xtream=F live=F subtitleScoped=F
            | xtream:a:vod:1 -> prefix=F xtream=F live=F subtitleScoped=T
            |m3u:abc -> prefix=T xtream=F live=F subtitleScoped=F
            |m3u: -> prefix=T xtream=F live=F subtitleScoped=F
            |m3ux:abc -> prefix=F xtream=F live=F subtitleScoped=F
            |tt0111161 -> prefix=F xtream=F live=F subtitleScoped=F
            |tmdb:603 -> prefix=F xtream=F live=F subtitleScoped=F
            |kitsu:1:2 -> prefix=F xtream=F live=F subtitleScoped=F
            | -> prefix=F xtream=F live=F subtitleScoped=F
        """.trimMargin()
        assertEquals("content-id golden list", expected, contentIds.joinToString("\n", transform = ::contentRow))
    }

    @Test
    fun `deferred url recognition is frozen`() {
        val expected = """
            |stalker-deferred:acc1|movie|12|Heat -> deferred=T
            |stalker-deferred:stalker|http://p.example|00:1a:79:aa:bb:cc|episode|3|1|2 -> deferred=T
            |stalker-deferred -> deferred=F
            |http://panel.example/movie/u/p/1.mp4 -> deferred=F
            | -> deferred=F
            |<null> -> deferred=F
        """.trimMargin()
        assertEquals(
            "deferred-url golden list",
            expected,
            deferredUrls.joinToString("\n") { "${it ?: "<null>"} -> deferred=${XtreamStreamSource.isDeferred(it).flag()}" },
        )
    }

    @Test
    fun `an own-source id that is not IPTV does not change IPTV answers`() {
        // The media-server namespace is unregistered here: it must read as an ordinary id everywhere.
        assertEquals(false, PlaybackAvailability.isIptvId("ms:jellyfin:m:u:movie:1"))
        assertEquals(false, AddonSubtitleIdPolicy.isProviderScoped("ms:jellyfin:m:u:movie:1"))
    }
}

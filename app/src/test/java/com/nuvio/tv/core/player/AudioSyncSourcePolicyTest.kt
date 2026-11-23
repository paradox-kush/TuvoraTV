package com.nuvio.tv.core.player

import com.nuvio.tv.core.contracts.OwnSourcePolicy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Sync 17: upstream's audio subtitle sync wraps the stream's extractors (audio tap) and its spot
 * sampler opens EXTRA connections to the stream URL. On a single-use Stalker link or a
 * one-connection IPTV panel that burns the link / steals the slot, so it may run only where
 * AutoSync may probe, with every setting on, and only in builds that ship it.
 *
 * NOTE: JUnit argument order is assertX(message, actual) here.
 */
class AudioSyncSourcePolicyTest {
    @Before fun registerOwnSources() {
        OwnSourcePolicy.resetForTest()
        OwnSourcePolicy.registerContentIdPredicate("test-iptv") { it.startsWith("xtream:") || it.startsWith("m3u:") }
    }

    @After fun clearRegistry() = OwnSourcePolicy.resetForTest()

    /** Everything on (feature shipped, AutoSync on, audio sync on): only the stream decides. */
    private fun mayRunFor(
        url: String,
        iptvId: String? = null,
        contentId: String? = "tt0111161",
        contentType: String? = "movie",
        isCatchUp: Boolean = false,
        isMatchedIptvSource: Boolean = false,
        panelHosts: Set<String> = emptySet(),
        featureAvailable: Boolean = true,
    ) = AudioSyncSourcePolicy.mayRun(
        featureAvailable = featureAvailable,
        autoSyncEnabled = true,
        audioSyncEnabled = true,
        autoSyncMayProbe = AutoSyncSourcePolicy.mayProbe(
            url, iptvId, contentId, contentId, contentType, isCatchUp, isMatchedIptvSource,
        ) { it in panelHosts },
    )

    @Test fun `plain add-on stream may use the audio sync`() =
        assertTrue("add-on https stream", mayRunFor("https://cdn.example/movie.mkv"))

    @Test fun `single-use Stalker VOD link never gets a tap or a spot sampler`() = assertFalse(
        "Stalker play_token link",
        mayRunFor(
            url = "http://portal.example:8080/play/movie.php?mac=00:1A:79:00:00:01&stream=123&play_token=abc",
            iptvId = "xtream:acc1:vod:123",
            contentId = "xtream:acc1:vod:123",
        )
    )

    @Test fun `one-connection Xtream panel host is never sampled`() = assertFalse(
        "panel host",
        mayRunFor(url = "http://panel.example:8080/movie/u/p/77.mkv", panelHosts = setOf("panel.example"))
    )

    @Test fun `TMDB-matched IPTV copy under a public id is never sampled`() = assertFalse(
        "matched IPTV copy",
        mayRunFor(url = "http://panel.example:8080/movie/u/p/77.mkv", isMatchedIptvSource = true)
    )

    @Test fun `catch-up replay is never sampled`() = assertFalse(
        "catch-up",
        mayRunFor(url = "https://cdn.example/archive.ts", contentType = "tv", isCatchUp = true)
    )

    @Test fun `store builds never run it, whatever the stream`() = assertFalse(
        "feature not shipped",
        mayRunFor(url = "https://cdn.example/movie.mkv", featureAvailable = false)
    )

    @Test fun `any setting off keeps it off`() {
        assertFalse("AutoSync off", AudioSyncSourcePolicy.mayRun(true, false, true, true))
        assertFalse("audio sync off", AudioSyncSourcePolicy.mayRun(true, true, false, true))
    }
}

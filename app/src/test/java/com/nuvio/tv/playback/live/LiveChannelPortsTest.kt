package com.nuvio.tv.playback.live

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderCatchUpWindow
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelPortsTest {
    private val profile = PlaybackProfileId("profile-secret")

    @Test
    fun `target requires LIVE and exact selection identity`() {
        val live = selection(ContentType.LIVE)
        val wrongIdentity = runCatching {
            target(live, ProviderSelectionId("wrong-secret"))
        }.exceptionOrNull()
        val catchUp = runCatching {
            target(selection(ContentType.CATCH_UP), ProviderSelectionId("channel-secret"))
        }.exceptionOrNull()

        assertTrue(wrongIdentity is IllegalArgumentException)
        assertTrue(catchUp is IllegalArgumentException)
    }

    @Test
    fun `target sanitizes labels and credential-bearing artwork`() {
        val live = selection(ContentType.LIVE)
        val target = LiveChannelTarget.sanitized(
            selection = live,
            contentId = live.contentKey,
            title = "https://provider-secret/channel",
            logo = "https://user:password@provider-secret/logo.png",
            playlistVersion = 8,
            boundProfileId = profile,
        )

        assertEquals("Live TV", target.title)
        assertNull(target.logo)
        assertEquals(64, target.mediaFingerprint.length)
    }

    @Test
    fun `all contract strings redact stable identity display labels and profile`() {
        val live = selection(ContentType.LIVE)
        val target = target(live, live.contentKey)
        val texts = listOf(
            profile.toString(),
            LiveRelativeRequest(live.contentKey, LiveZapDirection.NEXT, profile).toString(),
            target.toString(),
            LiveRelativeResult.Target(target).toString(),
            LivePlayedIdentity(target, profile, 7).toString(),
        )

        texts.forEach { text ->
            assertFalse(text.contains("profile-secret"))
            assertFalse(text.contains("account-secret"))
            assertFalse(text.contains("channel-secret"))
            assertFalse(text.contains("News Secret"))
            assertFalse(text.contains("provider-secret"))
        }
    }

    @Test
    fun `fingerprint is exact stable and profile scoped`() {
        val live = selection(ContentType.LIVE)
        val first = LiveMediaFingerprint.create(live, profile)

        assertEquals(first, LiveMediaFingerprint.create(live, profile))
        assertFalse(first == LiveMediaFingerprint.create(live, PlaybackProfileId("other-profile")))
        assertTrue(Regex("[a-f0-9]{64}").matches(first))
    }

    private fun target(
        selection: ProviderPlaybackSelection,
        contentId: ProviderSelectionId,
    ) = LiveChannelTarget.sanitized(
        selection = selection,
        contentId = contentId,
        title = "News Secret",
        logo = "https://provider-secret/logo.png",
        playlistVersion = 4,
        boundProfileId = profile,
    )

    private fun selection(contentType: ContentType): ProviderPlaybackSelection =
        ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId("account-secret"),
            itemId = ProviderSelectionId("42"),
            contentKey = ProviderSelectionId("channel-secret"),
            contentType = contentType,
            catchUpWindow = if (contentType == ContentType.CATCH_UP) {
                ProviderCatchUpWindow(1_000, 2_000)
            } else {
                null
            },
        )
}

package com.nuvio.tv.ui.navigation

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.live.LiveChannelTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class CleanLiveLaunchStoreTest {
    @Test
    fun `direct target put preserves the exact atomic selection target`() {
        val store = store()
        val selection = selection()
        val target = LiveChannelTarget.sanitized(
            selection = selection,
            contentId = selection.contentKey,
            title = "News",
            logo = "https://images.test/news.png",
            playlistVersion = 9,
            boundProfileId = PlaybackProfileId("4"),
        )

        val token = store.put(target, 4, CleanLiveLaunchOrigin.SPORTS)
        val consumed = store.consume(token.routeValue, 4).ready()

        assertSame(target, consumed.target)
        assertEquals(CleanLiveLaunchOrigin.SPORTS, consumed.origin)
        assertEquals("News", consumed.metadata.title)
        assertThrows {
            store.put(target, 5, CleanLiveLaunchOrigin.CATALOG_SEE_ALL)
        }
    }

    @Test
    fun `put returns a random shaped token and consume returns the URL-free selection once`() {
        val clock = FakeClock(100)
        val store = store(clock = clock)
        val selection = selection()

        val token = store.put(
            selection = selection,
            activeProfileId = 4,
            origin = CleanLiveLaunchOrigin.SEARCH,
            title = "Live News",
            subtitle = "Morning",
            station = "Station",
            logo = "https://images.test/news.png",
            playlistVersion = 7,
        )
        val consumed = store.consume(token.routeValue, currentProfileId = 4).ready()

        assertTrue(token.routeValue.matches(Regex("[a-f0-9]{64}")))
        assertSame(selection, consumed.selection)
        assertEquals(4, consumed.activeProfileId)
        assertEquals(CleanLiveLaunchOrigin.SEARCH, consumed.origin)
        assertEquals("Live News", consumed.metadata.title)
        assertEquals("Morning", consumed.metadata.subtitle)
        assertEquals("Station", consumed.metadata.station)
        assertSame(selection, consumed.target.selection)
        assertEquals(selection.contentKey, consumed.target.contentId)
        assertEquals("https://images.test/news.png", consumed.target.logo)
        assertEquals(7L, consumed.target.playlistVersion)
        assertEquals(consumed.target.mediaFingerprint, consumed.mediaFingerprint)
        assertTrue(consumed.mediaFingerprint.matches(Regex("[a-f0-9]{64}")))
        assertRejected(
            store.consume(token.routeValue, currentProfileId = 4),
            CleanLiveLaunchConsumeFailure.MISSING,
        )
    }

    @Test
    fun `tokens are uncorrelated with provider identity and differ per launch`() {
        val providerText = "https://provider.test:8443|customer"
        val contentText = "xtream:$providerText:live:42"
        val store = store()
        val selection = selection(accountId = providerText, contentKey = contentText)

        val first = store.put(selection, 1, CleanLiveLaunchOrigin.SEARCH, "News")
        val second = store.put(selection, 1, CleanLiveLaunchOrigin.LIBRARY, "News")

        assertNotEquals(first, second)
        listOf(first.routeValue, second.routeValue).forEach { token ->
            assertFalse(token.contains("provider"))
            assertFalse(token.contains("customer"))
            assertFalse(token.contains("xtream"))
        }
    }

    @Test
    fun `consume removes a profile-mismatched entry and reveals no profile values`() {
        val store = store()
        val token = store.put(selection(), 2, CleanLiveLaunchOrigin.LIBRARY, "Channel")

        val mismatch = store.consume(token.routeValue, currentProfileId = 3)

        assertRejected(mismatch, CleanLiveLaunchConsumeFailure.PROFILE_MISMATCH)
        assertEquals(
            "Rejected(reason=PROFILE_MISMATCH)",
            mismatch.toString(),
        )
        assertRejected(
            store.consume(token.routeValue, currentProfileId = 2),
            CleanLiveLaunchConsumeFailure.MISSING,
        )
    }

    @Test
    fun `entry expires at the short TTL boundary and cannot be retried`() {
        val clock = FakeClock(500)
        val store = store(clock = clock, ttlMs = 1_000)
        val token = store.put(selection(), 1, CleanLiveLaunchOrigin.SEARCH, "Channel")

        clock.now = 1_499
        assertTrue(store.consume(token.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)

        val expiredToken = store.put(selection(itemId = "43"), 1, CleanLiveLaunchOrigin.SEARCH, "Next")
        clock.now = 2_499
        assertRejected(
            store.consume(expiredToken.routeValue, 1),
            CleanLiveLaunchConsumeFailure.EXPIRED,
        )
        assertRejected(
            store.consume(expiredToken.routeValue, 1),
            CleanLiveLaunchConsumeFailure.MISSING,
        )
    }

    @Test
    fun `bounded store evicts the oldest pending launch`() {
        val store = store(maximumEntries = 2)
        val first = store.put(selection(itemId = "1"), 1, CleanLiveLaunchOrigin.SEARCH, "One")
        val second = store.put(selection(itemId = "2"), 1, CleanLiveLaunchOrigin.SEARCH, "Two")
        val third = store.put(selection(itemId = "3"), 1, CleanLiveLaunchOrigin.LIBRARY, "Three")

        assertRejected(store.consume(first.routeValue, 1), CleanLiveLaunchConsumeFailure.MISSING)
        assertTrue(store.consume(second.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)
        assertTrue(store.consume(third.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)
    }

    @Test
    fun `expired launches are pruned before capacity eviction`() {
        val clock = FakeClock(0)
        val store = store(clock = clock, ttlMs = 10, maximumEntries = 2)
        val expired = store.put(selection(itemId = "1"), 1, CleanLiveLaunchOrigin.SEARCH, "Old")
        clock.now = 10
        val current = store.put(selection(itemId = "2"), 1, CleanLiveLaunchOrigin.SEARCH, "Current")
        val newest = store.put(selection(itemId = "3"), 1, CleanLiveLaunchOrigin.SEARCH, "Newest")

        assertRejected(store.consume(expired.routeValue, 1), CleanLiveLaunchConsumeFailure.MISSING)
        assertTrue(store.consume(current.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)
        assertTrue(store.consume(newest.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)
    }

    @Test
    fun `invalid route token is missing and cannot consume a valid launch`() {
        val store = store()
        val valid = store.put(selection(), 1, CleanLiveLaunchOrigin.SEARCH, "Channel")

        assertRejected(store.consume("../$valid", 1), CleanLiveLaunchConsumeFailure.MISSING)
        assertTrue(store.consume(valid.routeValue, 1) is CleanLiveLaunchConsumeResult.Ready)
    }

    @Test
    fun `concurrent consumers cannot claim one launch more than once`() {
        val store = store()
        val token = store.put(selection(), 1, CleanLiveLaunchOrigin.SEARCH, "Channel")
        val executor = Executors.newFixedThreadPool(8)

        val results = try {
            executor.invokeAll(
                List(16) {
                    Callable { store.consume(token.routeValue, currentProfileId = 1) }
                },
            ).map { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it is CleanLiveLaunchConsumeResult.Ready })
        assertEquals(
            15,
            results.count {
                (it as? CleanLiveLaunchConsumeResult.Rejected)?.reason ==
                    CleanLiveLaunchConsumeFailure.MISSING
            },
        )
    }

    @Test
    fun `display metadata strips controls and rejects transport-shaped labels`() {
        val store = store()
        val token = store.put(
            selection = selection(),
            activeProfileId = 1,
            origin = CleanLiveLaunchOrigin.SEARCH,
            title = "News\u0000   Channel",
            subtitle = "https://provider.test/live?token=secret",
            station = "Authorization: Bearer secret",
        )

        val metadata = store.consume(token.routeValue, 1).ready().metadata

        assertEquals("News Channel", metadata.title)
        assertNull(metadata.subtitle)
        assertNull(metadata.station)
        assertEquals("Tuvora", CleanLiveLaunchMetadata.sanitized("https://host/path").title)
        assertEquals(256, CleanLiveLaunchMetadata.sanitized("x".repeat(300)).title.length)
    }

    @Test
    fun `launch target is the single sanitized selection display and fingerprint authority`() {
        val store = store()
        val selection = selection()
        val token = store.put(
            selection = selection,
            activeProfileId = 1,
            origin = CleanLiveLaunchOrigin.SEARCH,
            title = "https://provider.test/live?token=secret",
            logo = "https://user:password@provider.test/logo.png",
            playlistVersion = 3,
        )

        val entry = store.consume(token.routeValue, 1).ready()

        assertSame(selection, entry.selection)
        assertEquals("Live TV", entry.target.title)
        assertEquals("Live TV", entry.metadata.title)
        assertNull(entry.target.logo)
        assertEquals(3L, entry.target.playlistVersion)
        assertEquals(entry.target.mediaFingerprint, entry.mediaFingerprint)
    }

    @Test
    fun `entry and display metadata expose no transport fields`() {
        val entryFields = CleanLiveLaunchEntry::class.java.declaredFields.map { it.name.lowercase() }
        val metadataFields = CleanLiveLaunchMetadata::class.java.declaredFields.map { it.name.lowercase() }

        (entryFields + metadataFields).forEach { field ->
            assertFalse(field.contains("url"))
            assertFalse(field.contains("header"))
            assertFalse(field.contains("cookie"))
            assertFalse(field.contains("authorization"))
        }
    }

    @Test
    fun `media fingerprint is stable per opaque identity and changes with profile or stream`() {
        val firstStore = store()
        val secondStore = store(entropyStart = 100)
        val selection = selection()

        val first = firstStore.put(selection, 1, CleanLiveLaunchOrigin.SEARCH, "A")
        val same = secondStore.put(selection, 1, CleanLiveLaunchOrigin.LIBRARY, "B")
        val profile = secondStore.put(selection, 2, CleanLiveLaunchOrigin.SEARCH, "A")
        val stream = secondStore.put(selection(itemId = "99"), 1, CleanLiveLaunchOrigin.SEARCH, "A")

        val firstFingerprint = firstStore.consume(first.routeValue, 1).ready().mediaFingerprint
        val sameFingerprint = secondStore.consume(same.routeValue, 1).ready().mediaFingerprint
        val profileFingerprint = secondStore.consume(profile.routeValue, 2).ready().mediaFingerprint
        val streamFingerprint = secondStore.consume(stream.routeValue, 1).ready().mediaFingerprint

        assertEquals(firstFingerprint, sameFingerprint)
        assertNotEquals(firstFingerprint, profileFingerprint)
        assertNotEquals(firstFingerprint, streamFingerprint)
    }

    @Test
    fun `all public string forms redact tokens identity selection and labels`() {
        val secret = "provider-secret-customer"
        val store = store()
        val token = store.put(
            selection = selection(accountId = secret, contentKey = "xtream:$secret:live:42"),
            activeProfileId = 987,
            origin = CleanLiveLaunchOrigin.SEARCH,
            title = "Secret Channel Label",
            subtitle = "Secret Subtitle",
            station = "Secret Station",
        )
        val ready = store.consume(token.routeValue, 987) as CleanLiveLaunchConsumeResult.Ready
        val texts = listOf(
            token.toString(),
            ready.toString(),
            ready.entry.toString(),
            ready.entry.metadata.toString(),
        )

        texts.forEach { text ->
            assertFalse(text.contains(token.routeValue))
            assertFalse(text.contains(secret))
            assertFalse(text.contains("987"))
            assertFalse(text.contains("Secret Channel Label"))
            assertFalse(text.contains("Secret Subtitle"))
            assertFalse(text.contains("Secret Station"))
            assertFalse(text.contains(ready.entry.mediaFingerprint))
        }
    }

    @Test
    fun `store rejects non-live selections and invalid profiles`() {
        val store = store()
        val vod = selection(contentType = ContentType.VOD)

        assertThrows { store.put(vod, 1, CleanLiveLaunchOrigin.SEARCH, "Movie") }
        assertThrows { store.put(selection(), 0, CleanLiveLaunchOrigin.SEARCH, "Live") }
        assertThrows { store.consume("0".repeat(64), 0) }
    }

    private fun store(
        clock: FakeClock = FakeClock(0),
        ttlMs: Long = 120_000,
        maximumEntries: Int = 16,
        entropyStart: Int = 1,
    ) = CleanLiveLaunchStore(
        clock = clock,
        entropy = CountingEntropy(entropyStart),
        ttlMs = ttlMs,
        maximumEntries = maximumEntries,
    )

    private fun selection(
        accountId: String = "https://provider.test:8443|customer",
        itemId: String = "42",
        contentKey: String = "xtream:$accountId:live:$itemId",
        contentType: ContentType = ContentType.LIVE,
    ) = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.XTREAM,
        accountId = ProviderSelectionId(accountId),
        itemId = ProviderSelectionId(itemId),
        contentKey = ProviderSelectionId(contentKey),
        contentType = contentType,
    )

    private fun CleanLiveLaunchConsumeResult.ready(): CleanLiveLaunchEntry =
        (this as CleanLiveLaunchConsumeResult.Ready).entry

    private fun assertRejected(
        result: CleanLiveLaunchConsumeResult,
        expected: CleanLiveLaunchConsumeFailure,
    ) {
        assertEquals(expected, (result as CleanLiveLaunchConsumeResult.Rejected).reason)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    private class FakeClock(var now: Long) : CleanLiveLaunchClock {
        override fun nowMs(): Long = now
    }

    private class CountingEntropy(start: Int) : CleanLiveLaunchEntropy {
        private var next = start

        override fun nextBytes(size: Int): ByteArray = ByteArray(size).also { bytes ->
            val value = next++
            bytes[bytes.lastIndex - 3] = (value ushr 24).toByte()
            bytes[bytes.lastIndex - 2] = (value ushr 16).toByte()
            bytes[bytes.lastIndex - 1] = (value ushr 8).toByte()
            bytes[bytes.lastIndex] = value.toByte()
        }
    }
}

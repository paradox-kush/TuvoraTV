package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamLiveChannelIdentity
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import com.nuvio.tv.playback.live.LiveMediaFingerprint
import com.nuvio.tv.playback.live.LivePlayedIdentity
import com.nuvio.tv.playback.live.LiveRelativeFailure
import com.nuvio.tv.playback.live.LiveRelativeRequest
import com.nuvio.tv.playback.live.LiveRelativeResult
import com.nuvio.tv.playback.live.LiveZapDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvLiveChannelBridgeTest {
    private val profile = PlaybackProfileId("2")
    private val currentId = ProviderSelectionId("current-private")
    private val nextId = ProviderSelectionId(XtreamItemRegistry.liveId("account-private", 42))

    @Test
    fun `initial maps exact profile-bound live selection with display-only presentation`() = runTest {
        var profileReads = 0
        val calls = mutableListOf<String>()
        val subject = bridge(
            active = {
                profileReads++
                2
            },
            initial = { contentId, profileId ->
                calls += "$contentId:$profileId"
                selected(nextId)
            },
            initialPresentation = { profileId, contentId ->
                assertEquals(2, profileId)
                assertEquals(nextId.value, contentId)
                presentation(nextId)
            },
        )

        val result = subject.select(LiveInitialRequest(nextId, profile)) as LiveInitialResult.Target

        assertEquals(listOf("${nextId.value}:2"), calls)
        assertEquals(2, profileReads)
        assertEquals(nextId, result.target.contentId)
        assertEquals("Next News", result.target.title)
        assertEquals(null, result.target.playlistVersion)
        assertEquals(
            LiveMediaFingerprint.create(result.target.selection, profile),
            result.target.mediaFingerprint,
        )
        assertFalse(result.toString().contains(nextId.value))
    }

    @Test
    fun `initial profile fences reject before and after provider reads`() = runTest {
        var calls = 0
        val before = bridge(
            active = { 1 },
            initial = { _, _ ->
                calls++
                selected(nextId)
            },
        )
        assertInitialRejected(
            before.select(LiveInitialRequest(nextId, profile)),
            LiveInitialFailure.PROFILE_CHANGED,
        )
        assertEquals(0, calls)

        var active = 2
        val during = bridge(
            active = { active },
            initial = { _, _ ->
                active = 3
                selected(nextId)
            },
        )
        assertInitialRejected(
            during.select(LiveInitialRequest(nextId, profile)),
            LiveInitialFailure.PROFILE_CHANGED,
        )
    }

    @Test
    fun `initial validates profile live identity and exposes only coarse failures`() = runTest {
        val invalidProfile = PlaybackProfileId("not-a-positive-profile")
        var calls = 0
        val subject = bridge(initial = { _, _ ->
            calls++
            selected(nextId)
        })
        assertInitialRejected(
            subject.select(LiveInitialRequest(nextId, invalidProfile)),
            LiveInitialFailure.INVALID_TARGET,
        )
        assertEquals(0, calls)

        val wrongId = ProviderSelectionId(XtreamItemRegistry.liveId("account-private", 99))
        assertInitialRejected(
            bridge(initial = { _, _ -> selected(wrongId) })
                .select(LiveInitialRequest(nextId, profile)),
            LiveInitialFailure.INVALID_TARGET,
        )

        assertInitialRejected(
            bridge(initialPresentation = { _, _ -> null })
                .select(LiveInitialRequest(nextId, profile)),
            LiveInitialFailure.UNAVAILABLE,
        )

        assertInitialRejected(
            bridge(initial = { _, _ -> error("secret provider fault") })
                .select(LiveInitialRequest(nextId, profile)),
            LiveInitialFailure.UNAVAILABLE,
        )
    }

    @Test
    fun `initial preserves structured cancellation`() = runTest {
        val subject = bridge(
            initial = { _, _ -> throw CancellationException("cancel") },
        )

        val failure = runCatching {
            subject.select(LiveInitialRequest(nextId, profile))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `relative maps one exact profile-bound selection and presentation without resolving media`() = runTest {
        var active = 2
        var profileReads = 0
        val calls = mutableListOf<String>()
        val subject = bridge(
            active = {
                profileReads++
                active
            },
            relative = { contentId, delta, profileId ->
                calls += "$contentId:$delta:$profileId"
                selected(nextId)
            },
        )

        val result = subject.relative(
            LiveRelativeRequest(currentId, LiveZapDirection.NEXT, profile),
        ) as LiveRelativeResult.Target

        assertEquals(listOf("${currentId.value}:1:2"), calls)
        assertEquals(2, profileReads)
        assertEquals(nextId, result.target.contentId)
        assertEquals(nextId, result.target.selection.contentKey)
        assertEquals("Next News", result.target.title)
        assertEquals(9L, result.target.playlistVersion)
        assertEquals(
            LiveMediaFingerprint.create(result.target.selection, profile),
            result.target.mediaFingerprint,
        )
        assertFalse(result.toString().contains(nextId.value))
    }

    @Test
    fun `profile mismatch before lookup rejects without touching provider storage`() = runTest {
        var calls = 0
        val subject = bridge(
            active = { 1 },
            relative = { _, _, _ ->
                calls++
                selected(nextId)
            },
        )

        assertRejected(
            subject.relative(LiveRelativeRequest(currentId, LiveZapDirection.PREVIOUS, profile)),
            LiveRelativeFailure.PROFILE_CHANGED,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `profile switch during lookup rejects the stale result`() = runTest {
        var active = 2
        val subject = bridge(
            active = { active },
            relative = { _, delta, profileId ->
                assertEquals(-1, delta)
                assertEquals(2, profileId)
                active = 3
                selected(nextId)
            },
        )

        assertRejected(
            subject.relative(LiveRelativeRequest(currentId, LiveZapDirection.PREVIOUS, profile)),
            LiveRelativeFailure.PROFILE_CHANGED,
        )
    }

    @Test
    fun `relative validates atomic identity and exposes only coarse failures`() = runTest {
        val mismatched = ProviderSelectionId("mismatched-private")
        val invalid = bridge(relative = { _, _, _ ->
            IptvIngressSelectionResult.Selected(
                selection = selection(nextId),
                presentation = presentation(mismatched),
            )
        })
        assertRejected(
            invalid.relative(LiveRelativeRequest(currentId, LiveZapDirection.NEXT, profile)),
            LiveRelativeFailure.INVALID_TARGET,
        )

        val unavailable = bridge(relative = { _, _, _ ->
            IptvIngressSelectionResult.Rejected(IptvIngressSelectionFailure.ACCOUNT_MISSING)
        })
        assertRejected(
            unavailable.relative(LiveRelativeRequest(currentId, LiveZapDirection.NEXT, profile)),
            LiveRelativeFailure.UNAVAILABLE,
        )

        val fault = bridge(relative = { _, _, _ -> error("secret provider failure") })
        assertRejected(
            fault.relative(LiveRelativeRequest(currentId, LiveZapDirection.NEXT, profile)),
            LiveRelativeFailure.UNAVAILABLE,
        )
    }

    @Test
    fun `relative preserves structured cancellation`() = runTest {
        val subject = bridge(relative = { _, _, _ -> throw CancellationException("cancel") })
        val failure = runCatching {
            subject.relative(LiveRelativeRequest(currentId, LiveZapDirection.NEXT, profile))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun `history writes exact bound profile only and non-cancellation faults are best effort`() = runTest {
        var active = 2
        var profileReads = 0
        val writes = mutableListOf<String>()
        val target = target(nextId)
        val subject = bridge(
            active = {
                profileReads++
                active
            },
            history = { profileId, contentId, title, logo ->
                writes += "$profileId:$contentId:$title:$logo"
            },
        )

        subject.record(LivePlayedIdentity(target, profile, generation = 7))
        assertEquals(listOf("2:${nextId.value}:Next News:logo.png"), writes)
        assertEquals(2, profileReads)

        active = 1
        subject.record(LivePlayedIdentity(target, profile, generation = 8))
        assertEquals(1, writes.size)

        active = 2
        bridge(history = { _, _, _, _ -> error("history unavailable") })
            .record(LivePlayedIdentity(target, profile, generation = 9))
    }

    @Test
    fun `history preserves cancellation`() = runTest {
        val subject = bridge(
            history = { _, _, _, _ -> throw CancellationException("cancel") },
        )
        val failure = runCatching {
            subject.record(LivePlayedIdentity(target(nextId), profile, generation = 10))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private fun bridge(
        active: () -> Int = { 2 },
        initial: suspend (String, Int) -> IptvIngressSelectionResult = { _, _ ->
            selected(nextId)
        },
        initialPresentation: (Int, String) -> LiveChannelPresentation? = { _, _ ->
            presentation(nextId)
        },
        relative: suspend (String, Int, Int) -> IptvIngressSelectionResult = { _, _, _ ->
            selected(nextId)
        },
        history: suspend (Int, String, String, String?) -> Unit = { _, _, _, _ -> },
    ) = IptvLiveChannelBridge(
        activeProfile = ActivePlaybackProfileSource(active),
        initialSource = InitialLiveSelectionSource(initial),
        initialPresentation = IptvInitialLivePresentationReader(
            playlist = InitialLivePlaylistPresentationSource(initialPresentation),
            registry = InitialLiveRegistryItemSource { null },
            persisted = ExplicitProfileStoredLiveIdentitySource { _, _ -> null },
        ),
        relativeSource = RelativeLiveSelectionSource(relative),
        history = ExplicitProfileLiveHistorySink(history),
    )

    private fun selected(contentId: ProviderSelectionId) = IptvIngressSelectionResult.Selected(
        selection = selection(contentId),
        presentation = presentation(contentId),
    )

    private fun selection(contentId: ProviderSelectionId) = ProviderPlaybackSelection(
        sourceType = ProviderSourceType.XTREAM,
        accountId = ProviderSelectionId("account-private"),
        itemId = ProviderSelectionId("42"),
        contentKey = contentId,
        contentType = ContentType.LIVE,
        providerConnectionLimit = 1,
    )

    private fun presentation(contentId: ProviderSelectionId): LiveChannelPresentation =
        checkNotNull(
            LiveChannelPresentation.from(
                requireNotNull(
                    XtreamLiveChannelIdentity.from(
                        contentId = contentId.value,
                        title = "Next News",
                        logo = "logo.png",
                    ),
                ),
                playlistVersion = 9,
            ),
        )

    private fun target(contentId: ProviderSelectionId): LiveChannelTarget =
        LiveChannelTarget.sanitized(
            selection = selection(contentId),
            contentId = contentId,
            title = "Next News",
            logo = "logo.png",
            playlistVersion = 9,
            boundProfileId = profile,
        )

    private fun assertRejected(result: LiveRelativeResult, expected: LiveRelativeFailure) {
        assertEquals(expected, (result as LiveRelativeResult.Rejected).reason)
    }

    private fun assertInitialRejected(result: LiveInitialResult, expected: LiveInitialFailure) {
        assertEquals(expected, (result as LiveInitialResult.Rejected).reason)
    }
}

package com.nuvio.tv.ui.navigation

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import com.nuvio.tv.playback.core.ProviderSourceType
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LiveChannelTarget
import com.nuvio.tv.playback.live.LiveInitialFailure
import com.nuvio.tv.playback.live.LiveInitialRequest
import com.nuvio.tv.playback.live.LiveInitialResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CleanLiveIngressLauncherTest {
    @Test
    fun `launcher captures profile and stores the exact selected target`() = runTest {
        val target = target(profileId = 7)
        var capturedRequest: LiveInitialRequest? = null
        var storedTarget: LiveChannelTarget? = null
        var storedProfile = -1
        var storedOrigin: CleanLiveLaunchOrigin? = null
        val expectedToken = CleanLiveLaunchToken("a".repeat(64))
        val launcher = launcher(
            profile = { 7 },
            select = { request ->
                capturedRequest = request
                LiveInitialResult.Target(target)
            },
            store = { selected, profileId, origin ->
                storedTarget = selected
                storedProfile = profileId
                storedOrigin = origin
                expectedToken
            },
        )

        val result = launcher.launch(target.contentId.value, CleanLiveLaunchOrigin.FOLDER)

        assertSame(expectedToken, (result as CleanLiveIngressResult.Ready).token)
        assertEquals(target.contentId, capturedRequest?.contentId)
        assertEquals(PlaybackProfileId("7"), capturedRequest?.boundProfileId)
        assertSame(target, storedTarget)
        assertEquals(7, storedProfile)
        assertEquals(CleanLiveLaunchOrigin.FOLDER, storedOrigin)
    }

    @Test
    fun `profile change during selection rejects before target storage`() = runTest {
        var profileId = 3
        var storeCalls = 0
        val launcher = launcher(
            profile = { profileId },
            select = {
                profileId = 4
                LiveInitialResult.Target(target(profileId = 3))
            },
            store = { _, _, _ ->
                storeCalls++
                CleanLiveLaunchToken("b".repeat(64))
            },
        )

        val result = launcher.launch("channel:42", CleanLiveLaunchOrigin.SPORTS)

        assertRejected(result, CleanLiveIngressFailure.PROFILE_CHANGED)
        assertEquals(0, storeCalls)
    }

    @Test
    fun `invalid input fails before selection and exposes only coarse failure`() = runTest {
        var selectionCalls = 0
        val launcher = launcher(
            profile = { 1 },
            select = {
                selectionCalls++
                LiveInitialResult.Rejected(LiveInitialFailure.INVALID_TARGET)
            },
        )

        val result = launcher.launch("   ", CleanLiveLaunchOrigin.CATALOG_SEE_ALL)

        assertRejected(result, CleanLiveIngressFailure.INVALID_REQUEST)
        assertEquals(0, selectionCalls)
    }

    @Test
    fun `selection rejections are reduced to profile changed or unavailable`() = runTest {
        val cases = listOf(
            LiveInitialFailure.UNAVAILABLE to CleanLiveIngressFailure.UNAVAILABLE,
            LiveInitialFailure.INVALID_TARGET to CleanLiveIngressFailure.UNAVAILABLE,
            LiveInitialFailure.PROFILE_CHANGED to CleanLiveIngressFailure.PROFILE_CHANGED,
        )

        cases.forEach { (portFailure, expected) ->
            val launcher = launcher(
                profile = { 1 },
                select = { LiveInitialResult.Rejected(portFailure) },
            )

            assertRejected(
                launcher.launch("channel:42", CleanLiveLaunchOrigin.SEARCH),
                expected,
            )
        }
    }

    @Test
    fun `ordinary selection and storage failures fail closed`() = runTest {
        val selectionFailure = launcher(
            profile = { 1 },
            select = { throw IllegalStateException("provider detail") },
        ).launch("channel:42", CleanLiveLaunchOrigin.LIBRARY)
        val storageFailure = launcher(
            profile = { 1 },
            select = { LiveInitialResult.Target(target(profileId = 1)) },
            store = { _, _, _ -> throw IllegalArgumentException("target mismatch") },
        ).launch("channel:42", CleanLiveLaunchOrigin.LIBRARY)

        assertRejected(selectionFailure, CleanLiveIngressFailure.UNAVAILABLE)
        assertRejected(storageFailure, CleanLiveIngressFailure.UNAVAILABLE)
        assertFalse(selectionFailure.toString().contains("provider detail"))
        assertFalse(storageFailure.toString().contains("target mismatch"))
    }

    @Test
    fun `selection and storage cancellation are preserved`() = runTest {
        assertCancellation {
            launcher(
                profile = { 1 },
                select = { throw CancellationException("cancel select") },
            ).launch("channel:42", CleanLiveLaunchOrigin.SEARCH)
        }
        assertCancellation {
            launcher(
                profile = { 1 },
                select = { LiveInitialResult.Target(target(profileId = 1)) },
                store = { _, _, _ -> throw CancellationException("cancel store") },
            ).launch("channel:42", CleanLiveLaunchOrigin.SEARCH)
        }
    }

    @Test
    fun `view model is a transparent URL-free launcher wrapper`() = runTest {
        val expectedToken = CleanLiveLaunchToken("c".repeat(64))
        val viewModel = CleanLiveIngressViewModel(
            launcher(
                profile = { 2 },
                select = { LiveInitialResult.Target(target(profileId = 2)) },
                store = { _, _, _ -> expectedToken },
            ),
        )

        val result = viewModel.launch("channel:42", CleanLiveLaunchOrigin.LIBRARY)

        assertSame(expectedToken, (result as CleanLiveIngressResult.Ready).token)
        val publicShape = CleanLiveIngressViewModel::class.java.declaredFields
            .joinToString("|") { it.name.lowercase() }
        assertTrue(listOf("url", "probe", "client").none(publicShape::contains))
    }

    private fun launcher(
        profile: () -> Int,
        select: suspend (LiveInitialRequest) -> LiveInitialResult,
        store: (LiveChannelTarget, Int, CleanLiveLaunchOrigin) -> CleanLiveLaunchToken =
            { _, _, _ -> CleanLiveLaunchToken("d".repeat(64)) },
    ) = CleanLiveIngressLauncher(
        profileSource = CleanLiveIngressProfileSource { profile() },
        selectionPort = LiveChannelSelectionPort { request -> select(request) },
        targetStore = CleanLiveIngressTargetStore { target, profileId, origin ->
            store(target, profileId, origin)
        },
    )

    private fun target(profileId: Int): LiveChannelTarget {
        val contentId = ProviderSelectionId("channel:42")
        val selection = ProviderPlaybackSelection(
            sourceType = ProviderSourceType.XTREAM,
            accountId = ProviderSelectionId("account"),
            itemId = ProviderSelectionId("42"),
            contentKey = contentId,
            contentType = ContentType.LIVE,
        )
        return LiveChannelTarget.sanitized(
            selection = selection,
            contentId = contentId,
            title = "News",
            logo = null,
            playlistVersion = 4,
            boundProfileId = PlaybackProfileId(profileId.toString()),
        )
    }

    private fun assertRejected(
        result: CleanLiveIngressResult,
        expected: CleanLiveIngressFailure,
    ) {
        assertEquals(expected, (result as CleanLiveIngressResult.Rejected).reason)
    }

    private suspend fun assertCancellation(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            Unit
        }
    }
}

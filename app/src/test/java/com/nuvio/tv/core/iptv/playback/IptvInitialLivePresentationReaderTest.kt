package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamLiveChannelIdentity
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.StoredLiveChannelIdentity
import com.nuvio.tv.domain.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class IptvInitialLivePresentationReaderTest {
    @Test
    fun `exact current playlist presentation wins without reading lower priority sources`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        var registryReads = 0
        var persistedReads = 0
        val playlistProfiles = mutableListOf<Int>()
        val subject = reader(
            playlist = { profileId, _ ->
                playlistProfiles += profileId
                LiveChannelPresentation.from(
                    identity(contentId, "Playlist", "playlist.png"),
                    playlistVersion = 3,
                )
            },
            registry = {
                registryReads++
                item(contentId, "account", 7, "Registry", "registry.png")
            },
            persisted = { _, _ ->
                persistedReads++
                StoredLiveChannelIdentity(contentId, "Stored", "stored.png")
            },
        )

        val result = subject.read(profileId = 2, contentId = contentId)

        assertEquals("Playlist", result?.title)
        assertEquals("playlist.png", result?.logo)
        assertEquals(listOf(2), playlistProfiles)
        assertEquals(0, registryReads)
        assertEquals(0, persistedReads)
    }

    @Test
    fun `identity verified registry item wins without reading persistence`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        var persistedReads = 0
        val subject = reader(
            registry = { item(contentId, "account", 7, "Registry", "registry.png") },
            persisted = { _, _ ->
                persistedReads++
                StoredLiveChannelIdentity(contentId, "Stored", "stored.png")
            },
        )

        val result = subject.read(profileId = 2, contentId = contentId)

        assertEquals("Registry", result?.title)
        assertEquals("registry.png", result?.logo)
        assertEquals(0, persistedReads)
    }

    @Test
    fun `registry account stream and kind mismatches fail closed to explicit profile persistence`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        val mismatches = listOf(
            item(XtreamItemRegistry.liveId("account", 9), "account", 7, "Wrong id", null),
            item(contentId, "other-account", 7, "Wrong account", null),
            item(contentId, "account", 8, "Wrong stream", null),
            item(contentId, "account", 7, "Wrong kind", null).copy(kind = XtreamKind.VOD),
        )

        mismatches.forEach { mismatch ->
            val explicitProfiles = mutableListOf<Int>()
            val subject = reader(
                registry = { mismatch },
                persisted = { profileId, requestedId ->
                    explicitProfiles += profileId
                    StoredLiveChannelIdentity(requestedId, "Stored", "stored.png")
                },
            )

            val result = subject.read(profileId = 9, contentId = contentId)

            assertEquals("Stored", result?.title)
            assertEquals(listOf(9), explicitProfiles)
        }
    }

    @Test
    fun `persisted lookup rejects a mismatched returned identity`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        val subject = reader(
            persisted = { _, _ ->
                StoredLiveChannelIdentity(
                    XtreamItemRegistry.liveId("account", 8),
                    "Wrong",
                    null,
                )
            },
        )

        assertNull(subject.read(profileId = 2, contentId = contentId))
    }

    @Test
    fun `presentation sanitizes transport shaped title and credential bearing logo`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        val subject = reader(
            persisted = { _, requestedId ->
                StoredLiveChannelIdentity(
                    requestedId,
                    "https://provider.invalid/live?token=private",
                    "https://user:pass@images.invalid/logo.png",
                )
            },
        )

        val result = subject.read(profileId = 2, contentId = contentId)

        assertEquals("Live TV", result?.title)
        assertNull(result?.logo)
        assertFalse(result.toString().contains("provider.invalid"))
        assertFalse(result.toString().contains("user:pass"))
        assertFalse(
            IptvInitialLivePresentation::class.java.declaredFields.any {
                it.name.contains("url", ignoreCase = true) ||
                    it.name.contains("stream", ignoreCase = true)
            },
        )
    }

    @Test
    fun `invalid profile and non live identities fail closed without consulting sources`() = runTest {
        var reads = 0
        val subject = reader(
            playlist = { _, _ -> reads++; null },
            registry = { reads++; null },
            persisted = { _, _ -> reads++; null },
        )

        assertNull(subject.read(0, XtreamItemRegistry.liveId("account", 7)))
        assertNull(subject.read(2, XtreamItemRegistry.vodId("account", 7)))
        assertNull(subject.read(2, "not-an-iptv-id"))
        assertEquals(0, reads)
    }

    @Test
    fun `persistence failures return no presentation while cancellation is preserved`() = runTest {
        val contentId = XtreamItemRegistry.liveId("account", 7)
        assertNull(
            reader(persisted = { _, _ -> error("private persistence failure") })
                .read(profileId = 2, contentId = contentId),
        )

        try {
            reader(persisted = { _, _ -> throw CancellationException("cancelled") })
                .read(profileId = 2, contentId = contentId)
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            // Expected: structured concurrency is never converted into missing presentation.
        }
    }

    private fun reader(
        playlist: (Int, String) -> LiveChannelPresentation? = { _, _ -> null },
        registry: (String) -> XtreamResolvedItem? = { null },
        persisted: suspend (Int, String) -> StoredLiveChannelIdentity? = { _, _ -> null },
    ) = IptvInitialLivePresentationReader(
        playlist = InitialLivePlaylistPresentationSource(playlist),
        registry = InitialLiveRegistryItemSource(registry),
        persisted = ExplicitProfileStoredLiveIdentitySource(persisted),
    )

    private fun item(
        contentId: String,
        accountId: String,
        streamId: Int,
        name: String,
        poster: String?,
    ) = XtreamResolvedItem(
        id = contentId,
        type = ContentType.TV,
        name = name,
        poster = poster,
        streamUrl = "https://must-not-cross.invalid/live",
        kind = XtreamKind.LIVE,
        accountId = accountId,
        streamId = streamId,
    )

    private fun identity(
        contentId: String,
        title: String,
        logo: String? = null,
    ): XtreamLiveChannelIdentity = requireNotNull(
        XtreamLiveChannelIdentity.from(contentId, title, logo),
    )
}

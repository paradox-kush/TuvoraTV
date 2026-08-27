package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.domain.model.ContentType as CatalogContentType
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSourceType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvIngressSelectionFactoryTest {
    @Test
    fun `live selection maps every supported account source without a URL`() = runTest {
        listOf(
            XtreamAccount.SOURCE_XTREAM to ProviderSourceType.XTREAM,
            XtreamAccount.SOURCE_URL to ProviderSourceType.M3U,
            XtreamAccount.SOURCE_FILE to ProviderSourceType.M3U,
            XtreamAccount.SOURCE_STALKER to ProviderSourceType.STALKER,
        ).forEach { (source, expected) ->
            val account = account("account-$source", sourceType = source)
            val contentId = XtreamItemRegistry.liveId(account.id, 42)
            val selected = factory(listOf(account)).create(
                IptvIngressSelectionInput(contentId),
            ).selected()

            assertEquals(expected, selected.sourceType)
            assertEquals(ContentType.LIVE, selected.contentType)
            assertEquals("42", selected.itemId.value)
            assertEquals(1, selected.providerConnectionLimit)
            assertNoUrlField(selected)
        }
    }

    @Test
    fun `Xtream live and VOD carry only conservative declared transport evidence`() = runTest {
        val account = account("xtream")
        val factory = factory(listOf(account))

        val live = factory.create(
            IptvIngressSelectionInput(XtreamItemRegistry.liveId(account.id, 7)),
        ).selected()
        assertEquals(DeliveryType.RAW_TRANSPORT_STREAM, live.declaredEvidence.delivery?.value)
        assertEquals(ContainerType.MPEG_TS, live.declaredEvidence.container?.value)

        val vod = factory.create(
            IptvIngressSelectionInput(XtreamItemRegistry.vodId(account.id, 8)),
        ).selected()
        assertEquals(ContentType.VOD, vod.contentType)
        assertEquals(DeliveryType.PROGRESSIVE, vod.declaredEvidence.delivery?.value)
        assertEquals(ContainerType.MP4, vod.declaredEvidence.container?.value)
    }

    @Test
    fun `Xtream catch-up accepts canonical and legacy relative stable IDs`() = runTest {
        val account = account("catch-up")
        val start = 7_200_000L
        val end = 10_800_000L
        val liveId = XtreamItemRegistry.liveId(account.id, 99)
        val factory = factory(listOf(account))

        listOf(liveId, "${liveId}r${start / 60_000L}").forEach { contentId ->
            val selected = factory.create(
                IptvIngressSelectionInput(
                    contentId = contentId,
                    contentType = ContentType.CATCH_UP,
                    catchUpStartEpochMs = start,
                    catchUpEndEpochMs = end,
                ),
            ).selected()
            assertEquals(ContentType.CATCH_UP, selected.contentType)
            assertEquals(start, selected.catchUpWindow?.startEpochMs)
            assertEquals(end, selected.catchUpWindow?.endEpochMs)
            assertEquals("99", selected.itemId.value)
            assertTrue(selected.declaredEvidence == com.nuvio.tv.playback.core.StreamEvidence())
        }
    }

    @Test
    fun `malformed IDs and catch-up bounds fail closed`() = runTest {
        val account = account("malformed")
        val factory = factory(listOf(account))
        val malformed = listOf(
            "",
            "not-iptv",
            "xtream::live:1",
            "xtream:${account.id}:live:0",
            "xtream:${account.id}:live:01",
            "xtream:${account.id}:vod:1r2",
            "xtream:${account.id}:live:999999999999999999999",
        )
        malformed.forEach { contentId ->
            assertRejected(
                factory.create(IptvIngressSelectionInput(contentId)),
                IptvIngressSelectionFailure.MALFORMED_CONTENT_ID,
            )
        }

        val liveId = XtreamItemRegistry.liveId(account.id, 1)
        listOf(
            IptvIngressSelectionInput(liveId, ContentType.CATCH_UP),
            IptvIngressSelectionInput(liveId, ContentType.CATCH_UP, 1_000, null),
            IptvIngressSelectionInput(liveId, ContentType.CATCH_UP, 2_000, 1_000),
        ).forEach { input ->
            assertRejected(
                factory.create(input),
                IptvIngressSelectionFailure.CATCH_UP_BOUNDS_INVALID,
            )
        }
        assertRejected(
            factory.create(
                IptvIngressSelectionInput(
                    "${liveId}r99",
                    ContentType.CATCH_UP,
                    1_000,
                    2_000,
                ),
            ),
            IptvIngressSelectionFailure.MALFORMED_CONTENT_ID,
        )
    }

    @Test
    fun `missing disabled and registry-mismatched accounts are distinguished`() = runTest {
        val account = account("account")
        val contentId = XtreamItemRegistry.liveId(account.id, 4)
        assertRejected(
            factory(emptyList()).create(IptvIngressSelectionInput(contentId)),
            IptvIngressSelectionFailure.ACCOUNT_MISSING,
        )
        assertRejected(
            factory(listOf(account.copy(enabled = false))).create(IptvIngressSelectionInput(contentId)),
            IptvIngressSelectionFailure.ACCOUNT_DISABLED,
        )

        val registry = XtreamItemRegistry().apply {
            register(
                XtreamResolvedItem(
                    id = contentId,
                    type = CatalogContentType.TV,
                    name = "private",
                    poster = null,
                    streamUrl = "https://secret.invalid/live",
                    kind = XtreamKind.LIVE,
                    accountId = "different-account",
                    streamId = 4,
                ),
            )
        }
        assertRejected(
            factory(listOf(account), registry).create(IptvIngressSelectionInput(contentId)),
            IptvIngressSelectionFailure.ACCOUNT_MISMATCH,
        )
    }

    @Test
    fun `content kind request and account enablement must agree`() = runTest {
        val account = account("types")
        val factory = factory(listOf(account))
        assertRejected(
            factory.create(
                IptvIngressSelectionInput(
                    XtreamItemRegistry.liveId(account.id, 1),
                    contentType = ContentType.VOD,
                ),
            ),
            IptvIngressSelectionFailure.CONTENT_TYPE_MISMATCH,
        )
        listOf(
            XtreamItemRegistry.seriesId(account.id, 2),
            XtreamItemRegistry.episodeId(account.id, "3"),
        ).forEach { contentId ->
            assertRejected(
                factory.create(IptvIngressSelectionInput(contentId)),
                IptvIngressSelectionFailure.CONTENT_TYPE_UNSUPPORTED,
            )
        }
        assertRejected(
            factory(listOf(account.copy(contentTypes = setOf(XtreamAccount.TYPE_MOVIES))))
                .create(IptvIngressSelectionInput(XtreamItemRegistry.liveId(account.id, 1))),
            IptvIngressSelectionFailure.CONTENT_TYPE_DISABLED,
        )
    }

    @Test
    fun `unknown sources and non-Xtream catch-up are rejected`() = runTest {
        val unknown = account("unknown", sourceType = "future-source")
        assertRejected(
            factory(listOf(unknown)).create(
                IptvIngressSelectionInput(XtreamItemRegistry.liveId(unknown.id, 1)),
            ),
            IptvIngressSelectionFailure.SOURCE_UNSUPPORTED,
        )

        val m3u = account("m3u", sourceType = XtreamAccount.SOURCE_URL)
        assertRejected(
            factory(listOf(m3u)).create(
                IptvIngressSelectionInput(
                    XtreamItemRegistry.liveId(m3u.id, 1),
                    ContentType.CATCH_UP,
                    60_000,
                    120_000,
                ),
            ),
            IptvIngressSelectionFailure.CATCH_UP_SOURCE_UNSUPPORTED,
        )
    }

    @Test
    fun `account store exceptions become typed failures and preserve cancellation`() = runTest {
        val account = account("store")
        val factory = IptvIngressSelectionFactory(
            registry = XtreamItemRegistry(),
            accounts = IngressAccountSource { error("private provider failure") },
            relativeLive = RelativeLivePresentationSource { _, _ -> null },
        )
        assertRejected(
            factory.create(
                IptvIngressSelectionInput(XtreamItemRegistry.liveId(account.id, 1)),
            ),
            IptvIngressSelectionFailure.ACCOUNT_STORE_UNAVAILABLE,
        )
    }

    @Test
    fun `relative live helper carries only the neighboring stable ID`() = runTest {
        val account = account("zap")
        val current = XtreamItemRegistry.liveId(account.id, 1)
        val next = XtreamItemRegistry.liveId(account.id, 2)
        var lookupInput: String? = null
        val factory = IptvIngressSelectionFactory(
            registry = XtreamItemRegistry(),
            accounts = IngressAccountSource { listOf(account) },
            relativeLive = RelativeLivePresentationSource { contentId, delta ->
                lookupInput = "$contentId:$delta"
                LiveChannelPresentation.from(
                    LiveChannelRef(next, "Next", "logo.png", "https://secret.invalid/live"),
                    playlistVersion = 7,
                )
            },
        )

        val result = factory.relativeLive(current, 1) as IptvIngressSelectionResult.Selected
        val selected = result.selection
        assertEquals("$current:1", lookupInput)
        assertEquals(next, selected.contentKey.value)
        assertEquals("2", selected.itemId.value)
        assertEquals(next, result.presentation?.contentId?.value)
        assertEquals("Next", result.presentation?.title)
        assertEquals(7L, result.presentation?.playlistVersion)
        assertNoUrlField(selected)

        val rendered = result.toString()
        assertFalse(rendered.contains(next))
        assertFalse(rendered.contains("Next"))
        assertFalse(rendered.contains("secret.invalid"))

        assertRejected(
            factory.relativeLive(current, 0),
            IptvIngressSelectionFailure.RELATIVE_CHANNEL_UNAVAILABLE,
        )
    }

    @Test
    fun `input result and failures never print stable IDs account secrets or URLs`() = runTest {
        val account = account("private-account")
        val contentId = XtreamItemRegistry.liveId(account.id, 55)
        val input = IptvIngressSelectionInput(contentId)
        val result = factory(listOf(account)).create(input)
        val rendered = listOf(input, result, result.selected()).joinToString()

        assertFalse(rendered.contains(contentId))
        assertFalse(rendered.contains(account.id))
        assertFalse(rendered.contains(account.username))
        assertFalse(rendered.contains(account.password))
        assertFalse(rendered.contains("https://"))
        assertTrue(rendered.contains("contentType=LIVE"))
    }

    private fun factory(
        accounts: List<XtreamAccount>,
        registry: XtreamItemRegistry = XtreamItemRegistry(),
    ) = IptvIngressSelectionFactory(
        registry = registry,
        accounts = IngressAccountSource { accounts },
        relativeLive = RelativeLivePresentationSource { _, _ -> null },
    )

    private fun account(
        id: String,
        sourceType: String = XtreamAccount.SOURCE_XTREAM,
    ) = XtreamAccount(
        id = id,
        name = "private name",
        baseUrl = "https://provider.invalid",
        username = "private-user",
        password = "private-password",
        sourceType = sourceType,
    )

    private fun IptvIngressSelectionResult.selected(): ProviderPlaybackSelection =
        (this as IptvIngressSelectionResult.Selected).selection

    private fun assertRejected(
        result: IptvIngressSelectionResult,
        expected: IptvIngressSelectionFailure,
    ) {
        assertEquals(expected, (result as IptvIngressSelectionResult.Rejected).reason)
    }

    private fun assertNoUrlField(selection: ProviderPlaybackSelection) {
        assertFalse(
            ProviderPlaybackSelection::class.java.declaredFields.any {
                it.name.contains("url", ignoreCase = true)
            },
        )
    }
}

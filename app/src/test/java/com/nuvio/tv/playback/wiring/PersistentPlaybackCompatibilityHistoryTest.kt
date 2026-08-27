package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.core.AudioCodec
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.CompatibilityGraphFingerprint
import com.nuvio.tv.playback.core.CompatibilityOutcome
import com.nuvio.tv.playback.core.CompatibilityRecord
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.CompatibilityScopeKey
import com.nuvio.tv.playback.core.ContainerType
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DeliveryType
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackClock
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.VideoCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPlaybackCompatibilityHistoryTest {
    @Test
    fun `records persist across instances and expired rows are removed`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(1_000)
        val first = history(storage, clock)
        first.record(fatal(scopeA, recordedAt = 1_000, expiresAt = 2_000))

        val restored = history(storage, clock)
        assertEquals(1, restored.records(scopeA).size)
        clock.now = 2_000

        assertTrue(restored.records(scopeA).isEmpty())
        assertFalse(storage.value.orEmpty().contains("scope-a"))
    }

    @Test
    fun `success invalidates deterministic failure for the exact graph only`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(1_000)
        val history = history(storage, clock)
        history.record(fatal(scopeA, recordedAt = 1_000, expiresAt = 10_000))
        history.record(fatal(scopeB, recordedAt = 1_001, expiresAt = 10_000))
        history.record(success(scopeA, recordedAt = 1_002, expiresAt = 10_000))

        val exact = history.records(scopeA)
        assertEquals(1, exact.size)
        assertEquals(CompatibilityOutcome.SUCCESS, exact.single().outcome)
        assertEquals(CompatibilityOutcome.DETERMINISTIC_FATAL, history.records(scopeB).single().outcome)
    }

    @Test
    fun `success does not erase a different decoder or surface graph`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(1_000)
        val history = history(storage, clock)
        val surfaceViewFatal = fatal(scopeA, recordedAt = 1_000, expiresAt = 10_000)
        val textureSuccess = success(scopeA, recordedAt = 1_001, expiresAt = 10_000).copy(
            graph = graph(surfaceMode = SurfaceMode.TEXTURE_VIEW),
        )

        history.record(surfaceViewFatal)
        history.record(textureSuccess)

        assertEquals(2, history.records(scopeA).size)
        assertTrue(history.records(scopeA).any { it.graph.surfaceMode == SurfaceMode.SURFACE_VIEW })
        assertTrue(history.records(scopeA).any { it.graph.surfaceMode == SurfaceMode.TEXTURE_VIEW })
    }

    @Test
    fun `network authorization TLS DRM resource and unknown failures never establish engine history`() = runTest {
        val storage = FakeStorage()
        val history = history(storage, FakeClock(1_000))
        listOf(
            FailureDomain.NETWORK to FailureCode.NETWORK_TIMEOUT,
            FailureDomain.AUTHORIZATION_PROVIDER_LIMIT to FailureCode.AUTHORIZATION_REJECTED,
            FailureDomain.TLS to FailureCode.TLS_HANDSHAKE_FAILED,
            FailureDomain.DRM to FailureCode.DRM_LICENSE_FAILED,
            FailureDomain.DEVICE_RESOURCE to FailureCode.RESOURCE_BUDGET_EXCEEDED,
            FailureDomain.UNKNOWN to FailureCode.PROVIDER_CONNECTION_LIMIT,
        ).forEachIndexed { index, (domain, code) ->
            history.record(
                fatal(
                    scopeA,
                    domain = domain,
                    code = code,
                    recordedAt = 1_000L + index,
                    expiresAt = 10_000,
                ),
            )
        }

        assertTrue(history.records(scopeA).isEmpty())
        assertTrue(storage.value.isNullOrEmpty())
    }

    @Test
    fun `only explicitly classified parser decoder and render failures are learned`() = runTest {
        val accepted = listOf(
            FailureDomain.MANIFEST to FailureCode.MANIFEST_INVALID,
            FailureDomain.DEMUX to FailureCode.DEMUX_FAILED,
            FailureDomain.VIDEO_DECODER to FailureCode.VIDEO_DECODER_UNAVAILABLE,
            FailureDomain.VIDEO_DECODER to FailureCode.VIDEO_DECODER_FAILED,
            FailureDomain.VIDEO_RENDERER_SURFACE to FailureCode.VIDEO_RENDERER_FAILED,
            FailureDomain.VIDEO_RENDERER_SURFACE to FailureCode.SURFACE_LOST,
        )

        accepted.forEachIndexed { index, (domain, code) ->
            val storage = FakeStorage()
            val history = history(storage, FakeClock(1_000))
            history.record(
                fatal(
                    scopeA,
                    domain = domain,
                    code = code,
                    recordedAt = 1_000L + index,
                    expiresAt = 10_000,
                ),
            )
            assertEquals("$domain/$code", 1, history.records(scopeA).size)
        }

        val mismatched = history(FakeStorage(), FakeClock(1_000))
        mismatched.record(
            fatal(
                scopeA,
                domain = FailureDomain.VIDEO_DECODER,
                code = FailureCode.NETWORK_TIMEOUT,
                recordedAt = 1_000,
                expiresAt = 10_000,
            ),
        )
        assertTrue(mismatched.records(scopeA).isEmpty())

        val audioWithoutRouteScope = history(FakeStorage(), FakeClock(1_000))
        audioWithoutRouteScope.record(
            fatal(
                scopeA,
                domain = FailureDomain.AUDIO,
                code = FailureCode.AUDIO_OUTPUT_FAILED,
                recordedAt = 1_000,
                expiresAt = 10_000,
            ),
        )
        assertTrue(audioWithoutRouteScope.records(scopeA).isEmpty())
    }

    @Test
    fun `app and stable runtime fingerprint mismatch do not influence current selection`() = runTest {
        val storage = FakeStorage()
        val old = history(storage, FakeClock(1_000), appVersion = "old-app")
        old.record(
            fatal(scopeA, appVersion = "old-app", recordedAt = 1_000, expiresAt = 10_000),
        )

        assertTrue(history(storage, FakeClock(1_001), appVersion = "new-app").records(scopeA).isEmpty())

        val runtimeStorage = FakeStorage()
        history(runtimeStorage, FakeClock(1_000)).record(
            fatal(scopeA, recordedAt = 1_000, expiresAt = 10_000),
        )
        val changedRuntime = runtime.copy(capabilityFingerprint = "stable-capability-v2")
        assertTrue(
            history(runtimeStorage, FakeClock(1_001), currentRuntime = changedRuntime)
                .records(scopeA)
                .isEmpty(),
        )

        val engineStorage = FakeStorage()
        history(engineStorage, FakeClock(1_000), media3Version = "media3-old").record(
            fatal(
                scopeA,
                engineVersion = "media3-old",
                recordedAt = 1_000,
                expiresAt = 10_000,
            ),
        )
        assertTrue(history(engineStorage, FakeClock(1_001)).records(scopeA).isEmpty())
    }

    @Test
    fun `older or already expired outcomes cannot replace newer exact graph history`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(2_000)
        val history = history(storage, clock)
        history.record(fatal(scopeA, recordedAt = 2_000, expiresAt = 10_000))
        history.record(success(scopeA, recordedAt = 1_500, expiresAt = 10_000))
        assertEquals(CompatibilityOutcome.DETERMINISTIC_FATAL, history.records(scopeA).single().outcome)

        history.record(success(scopeA, recordedAt = 1_000, expiresAt = 1_500))
        assertEquals(CompatibilityOutcome.DETERMINISTIC_FATAL, history.records(scopeA).single().outcome)
    }

    @Test
    fun `scope keys are deterministic exact and never printable`() {
        val secretProvider = "provider-account-username"
        val input = CompatibilityScopeInput(
            providerScope = secretProvider,
            streamScope = "playlist/channel/42",
            contentType = ContentType.LIVE,
            delivery = DeliveryType.HLS,
            container = ContainerType.MPEG_TS,
            videoCodec = VideoCodec.HEVC,
            audioCodec = AudioCodec.EAC3,
        )
        val same = CompatibilityScopeKeyFactory.create(input)
        val changed = CompatibilityScopeKeyFactory.create(
            CompatibilityScopeInput(
                providerScope = secretProvider,
                streamScope = "playlist/channel/43",
                contentType = ContentType.LIVE,
                delivery = DeliveryType.HLS,
                container = ContainerType.MPEG_TS,
                videoCodec = VideoCodec.HEVC,
                audioCodec = AudioCodec.EAC3,
            ),
        )

        assertEquals(same, CompatibilityScopeKeyFactory.create(input))
        assertNotEquals(same, changed)
        assertFalse(input.toString().contains(secretProvider))
        assertFalse(same.toString().contains(secretProvider))
    }

    @Test
    fun `history compacts by expiry count and least recently used scope`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(1_000)
        val history = history(storage, clock, maxRecords = 2)
        history.record(success(scopeA, recordedAt = 1_000, expiresAt = 10_000))
        clock.now = 1_001
        history.record(success(scopeB, recordedAt = 1_001, expiresAt = 10_000))
        clock.now = 1_002
        assertEquals(1, history.records(scopeA).size)
        clock.now = 1_003
        history.record(success(scopeC, recordedAt = 1_003, expiresAt = 10_000))

        assertEquals(1, history.records(scopeA).size)
        assertTrue(history.records(scopeB).isEmpty())
        assertEquals(1, history.records(scopeC).size)
    }

    @Test
    fun `history never exceeds its encoded byte budget`() = runTest {
        val storage = FakeStorage()
        val clock = FakeClock(1_000)
        val byteBudget = 420
        val history = history(storage, clock, maxEncodedBytes = byteBudget)

        history.record(success(scopeA, recordedAt = 1_000, expiresAt = 10_000))
        clock.now = 1_001
        history.record(success(scopeB, recordedAt = 1_001, expiresAt = 10_000))
        clock.now = 1_002
        history.record(success(scopeC, recordedAt = 1_002, expiresAt = 10_000))

        assertTrue(storage.value.orEmpty().toByteArray().size <= byteBudget)
    }

    private fun history(
        storage: FakeStorage,
        clock: FakeClock,
        appVersion: String = "app-1",
        maxRecords: Int = 512,
        maxEncodedBytes: Int = 256 * 1024,
        currentRuntime: CompatibilityRuntimeFingerprint = runtime,
        media3Version: String = "media3-1",
    ) = PersistentPlaybackCompatibilityHistory(
        storage = storage,
        clock = clock,
        currentAppVersion = appVersion,
        currentRuntime = currentRuntime,
        currentEngineVersions = mapOf(
            EngineType.MEDIA3 to media3Version,
            EngineType.LIBMPV to "mpv-1",
        ),
        maxRecords = maxRecords,
        maxEncodedBytes = maxEncodedBytes,
    )

    private fun fatal(
        scope: CompatibilityScopeKey,
        domain: FailureDomain = FailureDomain.VIDEO_DECODER,
        code: FailureCode = FailureCode.VIDEO_DECODER_FAILED,
        appVersion: String = "app-1",
        engineVersion: String = "media3-1",
        recordedAt: Long,
        expiresAt: Long,
    ) = CompatibilityRecord(
        scopeKey = scope,
        graph = graph(),
        runtime = runtime,
        outcome = CompatibilityOutcome.DETERMINISTIC_FATAL,
        failureDomain = domain,
        failureCode = code,
        appVersion = appVersion,
        engineVersion = engineVersion,
        recordedAtEpochMs = recordedAt,
        expiresAtEpochMs = expiresAt,
    )

    private fun success(
        scope: CompatibilityScopeKey,
        recordedAt: Long,
        expiresAt: Long,
    ) = CompatibilityRecord(
        scopeKey = scope,
        graph = graph(),
        runtime = runtime,
        outcome = CompatibilityOutcome.SUCCESS,
        appVersion = "app-1",
        engineVersion = "media3-1",
        recordedAtEpochMs = recordedAt,
        expiresAtEpochMs = expiresAt,
    )

    private class FakeStorage : PlaybackCompatibilityStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private class FakeClock(var now: Long) : PlaybackClock {
        override fun nowEpochMs(): Long = now
        override suspend fun delayMs(durationMs: Long) = Unit
    }

    private companion object {
        val scopeA = CompatibilityScopeKey("scope-a")
        val scopeB = CompatibilityScopeKey("scope-b")
        val scopeC = CompatibilityScopeKey("scope-c")
        val runtime = CompatibilityRuntimeFingerprint(
            deviceVersion = "device-model-v1",
            firmwareVersion = "device-firmware-1",
            capabilityFingerprint = "stable-capability-v1",
        )

        fun graph(surfaceMode: SurfaceMode = SurfaceMode.SURFACE_VIEW) = CompatibilityGraphFingerprint(
            engine = EngineType.MEDIA3,
            outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = surfaceMode,
            secureOutput = false,
            decoderStableId = "decoder.hevc",
        )
    }
}

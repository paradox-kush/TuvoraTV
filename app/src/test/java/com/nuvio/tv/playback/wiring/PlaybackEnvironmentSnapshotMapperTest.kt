package com.nuvio.tv.playback.wiring

import com.nuvio.tv.playback.android.AndroidDeviceFacts
import com.nuvio.tv.playback.android.AndroidPlaybackQuirkRegistry
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilitySnapshot
import com.nuvio.tv.playback.android.AndroidStableCapabilityFingerprint
import com.nuvio.tv.playback.android.AndroidSurfaceFacts
import com.nuvio.tv.playback.android.AndroidCapabilityProof
import com.nuvio.tv.playback.core.AudioRoute
import com.nuvio.tv.playback.core.AudioRouteCapabilities
import com.nuvio.tv.playback.core.CompatibilityRuntimeFingerprint
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.DisplayCapabilities
import com.nuvio.tv.playback.core.EnginePreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.PreferenceAvailability
import com.nuvio.tv.playback.core.PreferenceReason
import com.nuvio.tv.playback.core.PreferenceResolution
import com.nuvio.tv.playback.core.RequestSummary
import com.nuvio.tv.playback.core.CrossHostAuthorization
import com.nuvio.tv.playback.core.DnsPolicy
import com.nuvio.tv.playback.core.RedirectPolicy
import com.nuvio.tv.playback.core.ResolutionAuthority
import com.nuvio.tv.playback.core.ResourceCapabilities
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.RuntimeCapabilities
import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.ThermalState
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.core.ChangeImpact
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import com.nuvio.tv.playback.settings.MpvOutputPreference
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolutionContext
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolver
import com.nuvio.tv.playback.settings.ResolvedPlaybackPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import com.nuvio.tv.playback.core.DefaultPlaybackRequirementsResolver
import com.nuvio.tv.playback.core.PlaybackGraphInput
import com.nuvio.tv.playback.core.PlaybackPolicy
import com.nuvio.tv.playback.core.PlaybackRequirementsInput
import com.nuvio.tv.playback.core.PlaybackResult

class PlaybackEnvironmentSnapshotMapperTest {
    @Test
    fun `applied AFTKM quirk restricts only Media3 in the guide to TextureView`() {
        val android = snapshot(
            appliedAftkmQuirk = true,
            surfaces = allSurfaceCapabilities(),
        )

        val mapped = map(android = android, profile = SessionProfile.GUIDE)

        assertEquals(
            "the quirk pins Media3's surface (black SurfaceView on AFTKM); libmpv keeps its own surfaces",
            setOf(SurfaceMode.TEXTURE_VIEW, SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER),
            mapped.allowedSurfaceModes,
        )
    }

    @Test
    fun `AFTKM guide still hands a failed Media3 live channel to libmpv on a low RAM box`() = runBlocking {
        // Fire TV Stick (AFTKM, 1.6 GB): low memory means no software-decode graphs, so the guide
        // handoff has exactly one libmpv candidate: the hardware direct embed. The quirk used to
        // strip libmpv's surfaces, leaving Media3 as the only graph and HANDOFF_EXCLUDED the reason.
        val android = snapshot(appliedAftkmQuirk = true, surfaces = allSurfaceCapabilities(), lowMemory = true)
        val preferences = resolvedPreferences()
        val environment = map(preferences = preferences, android = android, profile = SessionProfile.GUIDE)
        val requirements = (DefaultPlaybackRequirementsResolver().resolve(
            PlaybackRequirementsInput(
                requestSummary = requestSummary(),
                evidence = StreamEvidence(),
                profile = SessionProfile.GUIDE,
                effectivePreferences = preferences.effective.playback,
                environment = environment,
            ),
        ) as PlaybackResult.Success).value
        val candidates = (ProductionPlaybackGraphProvider.candidates(
            PlaybackGraphInput(requirements, StreamEvidence()),
        ) as PlaybackResult.Success).value
        val media3 = candidates.first { it.engine == EngineType.MEDIA3 }

        val selection = PlaybackPolicy().selectHandoff(
            requirements, candidates, media3, StreamEvidence(), excludedEngines = setOf(EngineType.MEDIA3),
        )

        assertTrue("handoff must find libmpv, got $selection", selection is PlaybackPolicy.Selection.Selected)
        assertEquals(EngineType.LIBMPV, (selection as PlaybackPolicy.Selection.Selected).graph.engine)
    }

    @Test
    fun `no forced surface of any quirk can remove libmpv as the live handoff target`() = runBlocking {
        // General invariant: a quirk pins one engine family's surface; whatever it pins, in either profile,
        // a failed Media3 start must still find a libmpv graph (and a failed libmpv start a Media3 one).
        for (profile in SessionProfile.entries) {
            for (forced in SurfaceMode.entries) {
                val quirk = com.nuvio.tv.playback.android.AppliedAndroidPlaybackQuirk(
                    quirk = com.nuvio.tv.playback.android.AndroidPlaybackQuirk(
                        id = "invariant", registryVersion = 1,
                        match = com.nuvio.tv.playback.android.AndroidPlaybackQuirkMatch(manufacturer = "X", model = "Y"),
                        domain = com.nuvio.tv.playback.android.AndroidPlaybackQuirkDomain.VIDEO_RENDERER_SURFACE,
                        override = com.nuvio.tv.playback.android.AndroidPlaybackQuirkOverride.ForceEmbeddedSurface(profile, forced),
                        evidenceReference = "test", introducedInAppVersion = "test",
                        revalidateAfterEpochMs = 1L, expiresAtEpochMs = 2L,
                    ),
                    revalidationDue = false,
                )
                val android = snapshot(surfaces = allSurfaceCapabilities(), lowMemory = true).copy(appliedQuirks = listOf(quirk))
                val preferences = resolvedPreferences()
                val environment = map(preferences = preferences, android = android, profile = profile)
                val requirements = (DefaultPlaybackRequirementsResolver().resolve(
                    PlaybackRequirementsInput(requestSummary(), StreamEvidence(), profile, preferences.effective.playback, environment),
                ) as? PlaybackResult.Success)?.value
                val where = "profile=$profile forced=$forced"
                if (requirements == null) {
                    // Forcing a surface the device does not offer may empty the set; that is the quirk's own doing, not engine loss.
                    continue
                }
                val candidates = (ProductionPlaybackGraphProvider.candidates(
                    PlaybackGraphInput(requirements, StreamEvidence()),
                ) as PlaybackResult.Success).value
                val media3Forced = forced == SurfaceMode.SURFACE_VIEW || forced == SurfaceMode.TEXTURE_VIEW
                val mpvForced = forced == SurfaceMode.NATIVE_EMBED || forced == SurfaceMode.GPU_RENDER
                if (media3Forced) assertTrue("libmpv graph survives: $where", candidates.any { it.engine == EngineType.LIBMPV })
                if (mpvForced) assertTrue("media3 graph survives: $where", candidates.any { it.engine == EngineType.MEDIA3 })
            }
        }
    }

    @Test
    fun `applied AFTKM guide quirk leaves fullscreen surfaces unaffected`() {
        val surfaces = allSurfaceCapabilities()
        val mapped = map(
            android = snapshot(appliedAftkmQuirk = true, surfaces = surfaces),
            profile = SessionProfile.FULLSCREEN,
        )

        assertEquals(SurfaceMode.entries.toSet(), mapped.allowedSurfaceModes)
    }

    @Test
    fun `AFTKM identity without an applied quirk does not change surfaces`() {
        val android = snapshot(surfaces = allSurfaceCapabilities()).copy(
            device = AndroidDeviceFacts(
                manufacturer = "Amazon",
                model = "AFTKM",
                device = "karat",
                hardware = "mt8696",
                board = "mt8696",
                firmware = "firmware",
            ),
            appliedQuirks = emptyList(),
        )

        val mapped = map(android = android, profile = SessionProfile.GUIDE)

        assertEquals(SurfaceMode.entries.toSet(), mapped.allowedSurfaceModes)
    }

    @Test
    fun `AUTO retains base eligibility and orders learned effective engine first`() {
        val resolved = resolvedPreferences().withEffectiveEngine(
            requested = EnginePreference.AUTO,
            effective = EnginePreference.LIBMPV,
            authority = ResolutionAuthority.LEARNED_COMPATIBILITY,
        )

        val mapped = map(preferences = resolved)

        assertEquals(setOf(EngineType.MEDIA3, EngineType.LIBMPV), mapped.eligibleEngines)
        assertEquals(listOf(EngineType.LIBMPV), mapped.preferredEngineOrder)
    }

    @Test
    fun `explicit engine without fallback restricts engine and its surface family`() {
        val resolved = resolvedPreferences(
            engine = EnginePreference.LIBMPV,
            automaticFallback = false,
            mpvOutput = MpvOutputPreference.DIRECT,
        )

        val mapped = map(preferences = resolved)

        assertEquals(setOf(EngineType.LIBMPV), mapped.eligibleEngines)
        assertEquals(listOf(EngineType.LIBMPV), mapped.preferredEngineOrder)
        assertEquals(setOf(SurfaceMode.NATIVE_EMBED), mapped.allowedSurfaceModes)
    }

    @Test
    fun `expert mpv output filters only libmpv surfaces while fallback is allowed`() {
        val direct = map(
            preferences = resolvedPreferences(mpvOutput = MpvOutputPreference.DIRECT),
        )
        val render = map(
            preferences = resolvedPreferences(mpvOutput = MpvOutputPreference.RENDER),
        )

        assertEquals(
            setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW, SurfaceMode.NATIVE_EMBED),
            direct.allowedSurfaceModes,
        )
        assertEquals(
            setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW, SurfaceMode.GPU_RENDER),
            render.allowedSurfaceModes,
        )
    }

    @Test
    fun `native embed and GPU surfaces require affirmative runtime proof`() {
        val mapped = map(
            android = snapshot(
                surfaces = SurfaceCapabilities(
                    surfaceViewSupported = true,
                    textureViewSupported = true,
                    nativeEmbedSupported = false,
                    gpuRenderingSupported = false,
                ),
            ),
        )

        assertEquals(setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW), mapped.allowedSurfaceModes)
        assertFalse(SurfaceMode.NATIVE_EMBED in mapped.allowedSurfaceModes)
        assertFalse(SurfaceMode.GPU_RENDER in mapped.allowedSurfaceModes)
    }

    @Test
    fun `secure output evidence resource budget and viewport pass through unchanged`() {
        val budget = ResourceBudget(networkBitrateCeiling = 3_000_000)
        val viewport = VideoDimensions(960, 540)
        val mapped = map(
            resourceBudget = budget,
            previewViewport = viewport,
            secureOutputRequired = true,
        )

        assertTrue(mapped.secureOutputRequired)
        assertEquals(budget, mapped.resourceBudget)
        assertEquals(viewport, mapped.previewViewport)
    }

    private fun map(
        preferences: ResolvedPlaybackPreferences = resolvedPreferences(),
        android: AndroidRuntimeCapabilitySnapshot = snapshot(surfaces = allSurfaceCapabilities()),
        profile: SessionProfile = SessionProfile.GUIDE,
        resourceBudget: ResourceBudget = ResourceBudget(),
        previewViewport: VideoDimensions? = null,
        secureOutputRequired: Boolean = false,
    ) = PlaybackEnvironmentSnapshotMapper.map(
        PlaybackEnvironmentMappingInput(
            preferences = preferences,
            android = android,
            profile = profile,
            resourceBudget = resourceBudget,
            previewViewport = previewViewport,
            secureOutputRequired = secureOutputRequired,
        ),
    )

    private fun resolvedPreferences(
        engine: EnginePreference = EnginePreference.AUTO,
        automaticFallback: Boolean = true,
        mpvOutput: MpvOutputPreference = MpvOutputPreference.AUTO,
    ): ResolvedPlaybackPreferences {
        val defaults = CleanPlaybackPreferences.recommended()
        val requested = defaults.copy(
            playback = defaults.playback.copy(
                engine = engine,
                automaticFallback = automaticFallback,
            ),
            expert = defaults.expert.copy(mpvOutput = mpvOutput),
        )
        return PlaybackPreferenceResolver.resolve(
            requested,
            PlaybackPreferenceResolutionContext(
                request = requestSummary(),
                evidence = StreamEvidence(),
                capabilities = runtimeCapabilities(allSurfaceCapabilities()),
            ),
        )
    }

    private fun ResolvedPlaybackPreferences.withEffectiveEngine(
        requested: EnginePreference,
        effective: EnginePreference,
        authority: ResolutionAuthority,
    ): ResolvedPlaybackPreferences = copy(
        requested = this.requested.copy(
            playback = this.requested.playback.copy(engine = requested),
        ),
        effective = this.effective.copy(
            playback = this.effective.playback.copy(engine = effective),
        ),
        engine = PreferenceResolution(
            requested = requested,
            effective = effective,
            authority = authority,
            availability = PreferenceAvailability.SUPPORTED,
            primaryReason = PreferenceReason.REQUEST_EFFECTIVE,
            impact = ChangeImpact.RESELECT_GRAPH,
        ),
    )

    private fun snapshot(
        appliedAftkmQuirk: Boolean = false,
        surfaces: SurfaceCapabilities,
        lowMemory: Boolean = false,
    ): AndroidRuntimeCapabilitySnapshot {
        val device = AndroidDeviceFacts(
            manufacturer = if (appliedAftkmQuirk) "Amazon" else "Generic",
            model = if (appliedAftkmQuirk) "AFTKM" else "TV",
            device = "device",
            hardware = "hardware",
            board = "board",
            firmware = "firmware",
        )
        val appliedQuirks = if (appliedAftkmQuirk) {
            AndroidPlaybackQuirkRegistry.resolve(
                device = device,
                codecStableIds = emptySet(),
                nowEpochMs = 1_800_000_000_000L,
                apiLevel = 35,
            )
        } else {
            emptyList()
        }
        val surfaceFacts = AndroidSurfaceFacts(
            surfaceViewAvailable = surfaces.surfaceViewSupported,
            textureViewAvailable = surfaces.textureViewSupported,
            gpuRenderingProof = if (surfaces.gpuRenderingSupported) {
                AndroidCapabilityProof.PROBED_SUPPORTED
            } else {
                AndroidCapabilityProof.UNPROBED
            },
            secureSurfaceProof = AndroidCapabilityProof.UNPROBED,
            secureGpuRenderingProof = AndroidCapabilityProof.UNPROBED,
        )
        return AndroidRuntimeCapabilitySnapshot(
            capabilities = runtimeCapabilities(surfaces, lowMemory),
            observationSequence = 1,
            stableFingerprint = AndroidStableCapabilityFingerprint(1, "fingerprint"),
            compatibilityRuntime = CompatibilityRuntimeFingerprint("device", "firmware", "fingerprint"),
            device = device,
            decoderFacts = emptyList(),
            surfaceFacts = surfaceFacts,
            decoderPerformancePoints = emptyMap(),
            appliedQuirks = appliedQuirks,
        )
    }

    private fun runtimeCapabilities(surfaces: SurfaceCapabilities, lowMemory: Boolean = false) = RuntimeCapabilities(
        snapshotVersion = 1,
        capturedAtEpochMs = 1,
        apiLevel = 35,
        display = DisplayCapabilities(VideoDimensions(3_840, 2_160)),
        audioRoute = AudioRouteCapabilities(AudioRoute.HDMI),
        resources = ResourceCapabilities(
            availableMemoryBytes = 2_000_000_000,
            lowMemory = lowMemory,
            thermalState = ThermalState.NOMINAL,
        ),
        surfaces = surfaces,
    )

    private fun requestSummary() = RequestSummary(
        scheme = "https",
        contentType = ContentType.LIVE,
        hasAuthorization = false,
        hasCustomHeaders = false,
        hasCookies = false,
        hasUserAgent = false,
        hasReferer = false,
        hasOrigin = false,
        hasDrm = false,
        redirectPolicy = RedirectPolicy.FOLLOW,
        crossHostAuthorization = CrossHostAuthorization.STRIP,
        tlsPolicy = TlsPolicy.PLATFORM_DEFAULT,
        dnsPolicy = DnsPolicy.SYSTEM,
        providerConnectionConstrained = false,
    )

    private fun allSurfaceCapabilities() = SurfaceCapabilities(
        surfaceViewSupported = true,
        textureViewSupported = true,
        nativeEmbedSupported = true,
        gpuRenderingSupported = true,
    )
}

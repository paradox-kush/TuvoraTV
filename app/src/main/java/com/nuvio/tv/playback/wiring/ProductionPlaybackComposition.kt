package com.nuvio.tv.playback.wiring

import android.content.Context
import android.media.AudioDeviceInfo
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilityCollector
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilitySnapshot
import com.nuvio.tv.playback.android.FrameworkAndroidCapabilitySource
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.CompatibilityGraphFingerprint
import com.nuvio.tv.playback.core.CompatibilityRecordingEnvironment
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.DefaultPlaybackRequirementsResolver
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.ExternalSubtitleResolver
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackClock
import com.nuvio.tv.playback.core.PlaybackCommand
import com.nuvio.tv.playback.core.PlaybackEngine
import com.nuvio.tv.playback.core.PlaybackEngineRegistry
import com.nuvio.tv.playback.core.PlaybackEnvironmentInput
import com.nuvio.tv.playback.core.PlaybackEnvironmentProvider
import com.nuvio.tv.playback.core.PlaybackEnvironmentResolution
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackGraphInput
import com.nuvio.tv.playback.core.PlaybackGraphProvider
import com.nuvio.tv.playback.core.PlaybackLifecyclePort
import com.nuvio.tv.playback.core.PlaybackOutputController
import com.nuvio.tv.playback.core.PlaybackPolicy
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.PlaybackRequestResolver
import com.nuvio.tv.playback.core.PlaybackRequirementsInput
import com.nuvio.tv.playback.core.PlaybackResult
import com.nuvio.tv.playback.core.PlaybackSession
import com.nuvio.tv.playback.core.ProviderPlaybackResolverFactory
import com.nuvio.tv.playback.core.ResolvedPlaybackRequest
import com.nuvio.tv.playback.core.ResourceBudget
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.core.SurfaceCapabilities
import com.nuvio.tv.playback.core.SurfaceMode
import com.nuvio.tv.playback.core.TlsPolicy
import com.nuvio.tv.playback.core.VideoDimensions
import com.nuvio.tv.playback.media3.AndroidMedia3BackendFactory
import com.nuvio.tv.playback.media3.ApplicationDnsResolver
import com.nuvio.tv.playback.media3.Media3Engine
import com.nuvio.tv.playback.media3.Media3SurfaceHost
import com.nuvio.tv.playback.mpv.AndroidMpvBackendFactory
import com.nuvio.tv.playback.mpv.MpvEngine
import com.nuvio.tv.playback.mpv.MpvSurfaceHost
import com.nuvio.tv.playback.settings.CleanPlaybackPreferences
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolutionContext
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolver
import com.nuvio.tv.playback.settings.SharedPreferencesPlaybackPreferenceDocumentStore
import com.nuvio.tv.playback.ui.PlaybackSessionController
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Stable facts which invalidate compatibility records when an app or engine implementation changes. */
internal data class ProductionPlaybackVersionFacts(
    val appVersion: String,
    val engineVersions: Map<EngineType, String>,
) {
    init {
        require(appVersion.isNotBlank())
        require(EngineType.entries.all { engineVersions[it]?.isNotBlank() == true })
    }

    companion object {
        fun current() = ProductionPlaybackVersionFacts(
            appVersion = "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})",
            engineVersions = mapOf(
                EngineType.MEDIA3 to MEDIA3_ENGINE_VERSION,
                EngineType.LIBMPV to LIBMPV_ENGINE_VERSION,
            ),
        )

        private const val MEDIA3_ENGINE_VERSION = "androidx-media3-1.11.0+nuvio-fork"
        private const val LIBMPV_ENGINE_VERSION =
            "lib-mpv-release.aar@44747a57bef59979d32ab2b28d9b582cb05e91684d53f1bdf5f120183b380a8b"
    }
}

/** UI-lifecycle facts and owners. No view, Activity, or navigation object crosses this boundary. */
internal data class ProductionPlaybackHost(
    val parentScope: CoroutineScope,
    val media3SurfaceHost: Media3SurfaceHost,
    val mpvSurfaceHost: MpvSurfaceHost,
    val surfaceCapabilities: SurfaceCapabilities,
    val outputController: PlaybackOutputController,
    val lifecycle: PlaybackLifecyclePort,
    val previewViewport: VideoDimensions? = null,
    val resourceBudget: ResourceBudget = ResourceBudget(),
    val routedAudioDevice: () -> AudioDeviceInfo? = { null },
    val externalSubtitleResolver: ExternalSubtitleResolver = ExternalSubtitleResolver { null },
) {
    init {
        require(!surfaceCapabilities.secureNativeEmbedSupported) {
            "The current libmpv adapter cannot promise secure native output"
        }
        require(!surfaceCapabilities.secureGpuRenderingSupported) {
            "The current libmpv adapter cannot promise secure GPU output"
        }
    }
}

/**
 * Production construction boundary for one owner, one engine registry, and one release path.
 * It is intentionally not referenced by a production route until the atomic live ingress cutover.
 */
@Singleton
internal class ProductionPlaybackSessionFactory @Inject constructor(
    @ApplicationContext context: Context,
    private val providerResolverFactory: ProviderPlaybackResolverFactory,
    private val applicationDnsResolver: ApplicationDnsResolver,
    legacyPreferenceSource: LegacyPlaybackPreferenceSnapshotSource,
) {
    private val appContext = context.applicationContext
    private val strictPlaybackHttpClient = OkHttpClient.Builder().build()
    private val preferenceRepository = PlaybackPreferenceRepository(
        SharedPreferencesPlaybackPreferenceDocumentStore(appContext),
    )
    private val preferenceBootstrap = ProductionPlaybackPreferenceBootstrap(
        repository = preferenceRepository,
        legacySource = legacyPreferenceSource,
    )
    private val compatibilityStorage = SharedPreferencesPlaybackCompatibilityStorage(
        appContext.getSharedPreferences(COMPATIBILITY_PREFERENCES, Context.MODE_PRIVATE),
    )
    private val clock: PlaybackClock = AndroidPlaybackClock
    private val versions = ProductionPlaybackVersionFacts.current()
    private val diagnostics = FormattingPlaybackDiagnostics(PostHogPlaybackDiagnosticSink)

    suspend fun create(
        preferenceProfileId: PlaybackProfileId,
        host: ProductionPlaybackHost,
    ): PlaybackSessionController {
        val bootstrap = preferenceBootstrap.load(preferenceProfileId.value)
        val requested = bootstrap.preferences
        val collector = AndroidRuntimeCapabilityCollector(
            FrameworkAndroidCapabilitySource(appContext, host.routedAudioDevice),
        )
        val firstAndroid = withContext(Dispatchers.Default) {
            collector.refresh(observationSequence = 1, capturedAtEpochMs = clock.nowEpochMs())
                .withHostSurfaces(host.surfaceCapabilities)
        }
        val history = PersistentPlaybackCompatibilityHistory(
            storage = compatibilityStorage,
            clock = clock,
            currentAppVersion = versions.appVersion,
            currentRuntime = firstAndroid.compatibilityRuntime,
            currentEngineVersions = versions.engineVersions,
        )
        val graphProvider = ProductionPlaybackGraphProvider
        val environmentProvider = ProductionPlaybackEnvironmentProvider(
            requested = requested,
            collector = collector,
            firstSnapshot = firstAndroid,
            host = host,
            history = history,
            versions = versions,
            clock = clock,
            graphProvider = graphProvider,
        )
        val engines = productionEngines(host)
        val session = PlaybackSession(
            parentScope = host.parentScope,
            requestResolver = concreteRequestResolver,
            providerPlaybackResolver = providerResolverFactory.create(preferenceProfileId),
            environmentProvider = environmentProvider,
            requirementsResolver = DefaultPlaybackRequirementsResolver(),
            graphProvider = graphProvider,
            engineRegistry = PlaybackEngineRegistry { engines[it] },
            outputController = host.outputController,
            clock = clock,
            diagnostics = diagnostics,
            lifecycle = host.lifecycle,
            compatibilityRecording = CompatibilityRecordingEnvironment(
                history = history,
                runtime = firstAndroid.compatibilityRuntime,
                appVersion = versions.appVersion,
                engineVersions = versions.engineVersions,
                successTtlMs = COMPATIBILITY_TTL_MS,
                fatalTtlMs = COMPATIBILITY_TTL_MS,
            ),
            policy = PlaybackPolicy(),
        )
        // The actor lane preserves this command before any Tune/Zap sent through the returned owner.
        session.dispatch(bootstrap.initialCommand())
        return PlaybackSessionController(session)
    }

    private fun productionEngines(host: ProductionPlaybackHost): Map<EngineType, PlaybackEngine> = mapOf(
        EngineType.MEDIA3 to Media3Engine(
            scope = host.parentScope,
            surfaceHost = host.media3SurfaceHost,
            backendFactory = AndroidMedia3BackendFactory(
                context = appContext,
                sharedHttpClient = strictPlaybackHttpClient,
                applicationDnsResolver = applicationDnsResolver,
                sharedClientTlsPolicy = TlsPolicy.STRICT,
                externalSubtitleResolver = host.externalSubtitleResolver,
            ),
        ),
        EngineType.LIBMPV to MpvEngine(
            scope = host.parentScope,
            surfaceHost = host.mpvSurfaceHost,
            backendFactory = AndroidMpvBackendFactory(
                context = appContext,
                externalSubtitleResolver = host.externalSubtitleResolver,
            ),
        ),
    )

    private companion object {
        const val COMPATIBILITY_PREFERENCES = "clean_playback_compatibility_v2"
        const val COMPATIBILITY_TTL_MS = 24L * 60L * 60L * 1_000L

        val concreteRequestResolver = PlaybackRequestResolver { request ->
            PlaybackResult.Success(
                ResolvedPlaybackRequest(
                    request = request,
                    summary = request.summary(),
                    evidence = StreamEvidence(),
                    compatibilityScopeKey = null,
                ),
            )
        }
    }
}

internal data class BootstrappedPlaybackPreferences(
    val profileId: String,
    val preferences: CleanPlaybackPreferences,
    val importedLegacy: Boolean,
) {
    fun initialCommand(): PlaybackCommand.PreferencesChanged =
        PlaybackCommand.PreferencesChanged(preferences.playback)
}

internal class ProductionPlaybackPreferenceBootstrap(
    private val repository: PlaybackPreferenceRepository,
    private val legacySource: LegacyPlaybackPreferenceSnapshotSource,
) {
    suspend fun load(profileId: String): BootstrappedPlaybackPreferences {
        require(profileId.isNotBlank()) { "Playback preference profile id must not be blank" }
        val result = repository.loadOrImportLegacyIfAbsent(profileId) {
            legacySource.snapshot(profileId)
        }
        return BootstrappedPlaybackPreferences(
            profileId = profileId,
            preferences = result.snapshot.preferences,
            importedLegacy = result.imported,
        )
    }
}

private object AndroidPlaybackClock : PlaybackClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis().coerceAtLeast(0L)
    override suspend fun delayMs(durationMs: Long) = delay(durationMs)
}

/** Mechanical candidate catalog. Selection and fallback authority remain in [PlaybackPolicy]. */
internal object ProductionPlaybackGraphProvider : PlaybackGraphProvider {
    override suspend fun candidates(input: PlaybackGraphInput): PlaybackResult<List<PlaybackGraph>> {
        val requirements = input.requirements
        val decoders = when (requirements.decoderPreference) {
            DecoderPreference.HARDWARE_ONLY -> listOf(DecoderMode.HARDWARE)
            DecoderPreference.SOFTWARE_ONLY -> listOf(DecoderMode.SOFTWARE)
            DecoderPreference.AUTO -> buildList {
                add(DecoderMode.HARDWARE)
                if (requirements.softwareDecodeFallbackAllowed) add(DecoderMode.SOFTWARE)
            }
        }
        val audio = when (requirements.audioOutput) {
            AudioOutputPreference.PASSTHROUGH -> AudioMode.PASSTHROUGH
            AudioOutputPreference.AUTO, AudioOutputPreference.PCM -> AudioMode.DECODE
        }
        val graphs = buildList {
            if (EngineType.MEDIA3 in requirements.eligibleEngines) {
                requirements.allowedSurfaceModes
                    .filter { it == SurfaceMode.SURFACE_VIEW || it == SurfaceMode.TEXTURE_VIEW }
                    .forEach { surface ->
                        decoders.forEach { decoder ->
                            add(graph(EngineType.MEDIA3, GraphOutputProfile.MEDIA3_STANDARD, decoder, audio, surface,
                                requirements.secureOutputRequired))
                        }
                    }
            }
            if (EngineType.LIBMPV in requirements.eligibleEngines && !requirements.secureOutputRequired) {
                requirements.allowedSurfaceModes.forEach { surface ->
                    val output = when (surface) {
                        SurfaceMode.NATIVE_EMBED -> GraphOutputProfile.MPV_DIRECT
                        SurfaceMode.GPU_RENDER -> GraphOutputProfile.MPV_RENDER
                        else -> null
                    } ?: return@forEach
                    decoders.forEach { decoder ->
                        if (output != GraphOutputProfile.MPV_DIRECT || decoder == DecoderMode.HARDWARE) {
                            add(graph(EngineType.LIBMPV, output, decoder, audio, surface, secure = false))
                        }
                    }
                }
            }
        }
        return PlaybackResult.Success(graphs)
    }

    private fun graph(
        engine: EngineType,
        output: GraphOutputProfile,
        decoder: DecoderMode,
        audio: AudioMode,
        surface: SurfaceMode,
        secure: Boolean,
    ) = PlaybackGraph(
        id = listOf(engine, output, decoder, audio, surface, secure).joinToString("-") { it.toString().lowercase() },
        engine = engine,
        outputProfile = output,
        decoderMode = decoder,
        audioMode = audio,
        surfaceMode = surface,
        secureOutput = secure,
    )
}

private class ProductionPlaybackEnvironmentProvider(
    private val requested: CleanPlaybackPreferences,
    private val collector: AndroidRuntimeCapabilityCollector,
    firstSnapshot: AndroidRuntimeCapabilitySnapshot,
    private val host: ProductionPlaybackHost,
    private val history: com.nuvio.tv.playback.core.PlaybackCompatibilityHistory,
    private val versions: ProductionPlaybackVersionFacts,
    private val clock: PlaybackClock,
    private val graphProvider: PlaybackGraphProvider,
) : PlaybackEnvironmentProvider {
    private val nextObservation = AtomicLong(firstSnapshot.observationSequence + 1)
    private val compatibilityRuntime = firstSnapshot.compatibilityRuntime

    override suspend fun snapshot(input: PlaybackEnvironmentInput): PlaybackResult<PlaybackEnvironmentResolution> {
        val now = clock.nowEpochMs()
        val android = withContext(Dispatchers.Default) {
            collector.refresh(nextObservation.getAndIncrement(), now)
                .withHostSurfaces(host.surfaceCapabilities)
        }
        val currentRequested = requested.copy(playback = input.effectivePreferences)
        val records = input.compatibilityScopeKey?.let { history.records(it) }.orEmpty()
        val provisional = resolvePreferences(input, android, currentRequested, records, emptySet(), now)
        val provisionalEnvironment = environment(input, android, provisional)
        val provisionalRequirements = DefaultPlaybackRequirementsResolver().resolve(
            PlaybackRequirementsInput(
                requestSummary = input.requestSummary,
                evidence = input.evidence,
                profile = input.profile,
                effectivePreferences = provisional.effective.playback,
                environment = provisionalEnvironment,
            ),
        )
        val graphs = if (provisionalRequirements is PlaybackResult.Success) {
            when (val result = graphProvider.candidates(PlaybackGraphInput(provisionalRequirements.value, input.evidence))) {
                is PlaybackResult.Success -> result.value
                is PlaybackResult.Failure -> emptyList()
            }
        } else {
            emptyList()
        }
        val eligibleFingerprints = eligibleFingerprints(graphs, records.map { it.graph })
        val effective = resolvePreferences(input, android, currentRequested, records, eligibleFingerprints, now)
        return PlaybackResult.Success(
            PlaybackEnvironmentResolution(
                snapshot = environment(input, android, effective),
                effectivePreferences = effective.effective.playback,
            ),
        )
    }

    private fun resolvePreferences(
        input: PlaybackEnvironmentInput,
        android: AndroidRuntimeCapabilitySnapshot,
        requested: CleanPlaybackPreferences,
        records: List<com.nuvio.tv.playback.core.CompatibilityRecord>,
        eligibleGraphs: Set<CompatibilityGraphFingerprint>,
        now: Long,
    ) = PlaybackPreferenceResolver.resolve(
        requested,
        PlaybackPreferenceResolutionContext(
            request = input.requestSummary,
            evidence = input.evidence,
            capabilities = android.capabilities,
            eligibleEngines = eligibleEngines(android.capabilities.surfaces),
            compatibilityScopeKey = input.compatibilityScopeKey,
            compatibilityRecords = records,
            eligibleGraphFingerprints = eligibleGraphs,
            compatibilityRuntime = compatibilityRuntime,
            nowEpochMs = now,
            appVersion = versions.appVersion,
            engineVersions = versions.engineVersions,
            rapidLiveZapping = input.profile == com.nuvio.tv.playback.core.SessionProfile.GUIDE,
        ),
    )

    private fun environment(
        input: PlaybackEnvironmentInput,
        android: AndroidRuntimeCapabilitySnapshot,
        preferences: com.nuvio.tv.playback.settings.ResolvedPlaybackPreferences,
    ) = PlaybackEnvironmentSnapshotMapper.map(
        PlaybackEnvironmentMappingInput(
            preferences = preferences,
            android = android,
            profile = input.profile,
            resourceBudget = host.resourceBudget,
            previewViewport = host.previewViewport,
            baseEligibleEngines = eligibleEngines(android.capabilities.surfaces),
            baseAllowedSurfaceModes = supportedSurfaceModes(android.capabilities.surfaces),
            secureOutputRequired = input.requestSummary.secureOutputRequired,
        ),
    )

    private fun eligibleFingerprints(
        graphs: List<PlaybackGraph>,
        recorded: List<CompatibilityGraphFingerprint>,
    ): Set<CompatibilityGraphFingerprint> {
        val base = graphs.mapTo(linkedSetOf()) { it.fingerprint() }
        recorded.filterTo(base) { record ->
            base.any { candidate -> candidate.sameGraphWithoutDecoderIdentity(record) }
        }
        return base
    }
}

private fun AndroidRuntimeCapabilitySnapshot.withHostSurfaces(
    host: SurfaceCapabilities,
): AndroidRuntimeCapabilitySnapshot = copy(
    capabilities = capabilities.copy(
        surfaces = host.copy(
            // Secure output is implemented only by Media3 SurfaceView in the current adapters.
            secureNativeEmbedSupported = false,
            secureGpuRenderingSupported = false,
        ),
    ),
)

private fun eligibleEngines(surfaces: SurfaceCapabilities): Set<EngineType> = buildSet {
    if (surfaces.surfaceViewSupported || surfaces.textureViewSupported) add(EngineType.MEDIA3)
    if (surfaces.nativeEmbedSupported || surfaces.gpuRenderingSupported) add(EngineType.LIBMPV)
}

private fun supportedSurfaceModes(surfaces: SurfaceCapabilities): Set<SurfaceMode> = buildSet {
    if (surfaces.surfaceViewSupported) add(SurfaceMode.SURFACE_VIEW)
    if (surfaces.textureViewSupported) add(SurfaceMode.TEXTURE_VIEW)
    if (surfaces.nativeEmbedSupported) add(SurfaceMode.NATIVE_EMBED)
    if (surfaces.gpuRenderingSupported) add(SurfaceMode.GPU_RENDER)
}

private fun PlaybackGraph.fingerprint() = CompatibilityGraphFingerprint(
    engine = engine,
    outputProfile = outputProfile,
    decoderMode = decoderMode,
    audioMode = audioMode,
    surfaceMode = surfaceMode,
    secureOutput = secureOutput,
)

private fun CompatibilityGraphFingerprint.sameGraphWithoutDecoderIdentity(
    other: CompatibilityGraphFingerprint,
): Boolean = copy(decoderStableId = null) == other.copy(decoderStableId = null)

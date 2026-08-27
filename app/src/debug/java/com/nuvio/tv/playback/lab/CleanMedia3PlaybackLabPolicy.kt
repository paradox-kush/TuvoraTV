package com.nuvio.tv.playback.lab

import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.data.local.LiveChannelRef
import com.nuvio.tv.playback.core.AudioMode
import com.nuvio.tv.playback.core.AudioOutputPreference
import com.nuvio.tv.playback.core.DecoderMode
import com.nuvio.tv.playback.core.DecoderPreference
import com.nuvio.tv.playback.core.EngineType
import com.nuvio.tv.playback.core.FailureCode
import com.nuvio.tv.playback.core.FailureDomain
import com.nuvio.tv.playback.core.FailurePhase
import com.nuvio.tv.playback.core.GraphOutputProfile
import com.nuvio.tv.playback.core.PlaybackFailure
import com.nuvio.tv.playback.core.PlaybackGraph
import com.nuvio.tv.playback.core.PlaybackRequirements
import com.nuvio.tv.playback.core.Retryability
import com.nuvio.tv.playback.core.SurfaceMode

internal enum class LabReadinessCode {
    READY,
    NO_SELECTED_PLAYLIST,
    SELECTED_PLAYLIST_MISSING,
    SELECTED_PLAYLIST_DISABLED,
    NO_RECENT_LIVE_CHANNEL,
    UNSUPPORTED_SOURCE,
    STREAM_RESOLUTION_FAILED,
    POLICY_REJECTED,
    START_FAILED,
    SURFACE_RECREATE_FAILED,
    RELEASE_FAILED,
}

/** Holds secrets in memory but permanently redacts its string representation. */
internal class SelectedDebugFixture(
    val account: XtreamAccount,
    val contentId: String,
    val streamId: Int,
) {
    override fun toString(): String = "SelectedDebugFixture(hasAccount=true, hasContentId=true)"
}

internal sealed interface DebugFixtureSelection {
    class Ready(val fixture: SelectedDebugFixture) : DebugFixtureSelection {
        override fun toString(): String = "DebugFixtureSelection.Ready(fixture=$fixture)"
    }

    data class Blocked(val code: LabReadinessCode) : DebugFixtureSelection
}

/** Pure selection over already-loaded debug-profile data. It never falls back to another playlist. */
internal fun selectDebugFixture(
    selectedAccountId: String?,
    accounts: List<XtreamAccount>,
    recents: List<LiveChannelRef>,
): DebugFixtureSelection {
    if (selectedAccountId == null) {
        return DebugFixtureSelection.Blocked(LabReadinessCode.NO_SELECTED_PLAYLIST)
    }
    val account = accounts.firstOrNull { it.id == selectedAccountId }
        ?: return DebugFixtureSelection.Blocked(LabReadinessCode.SELECTED_PLAYLIST_MISSING)
    if (!account.enabled) {
        return DebugFixtureSelection.Blocked(LabReadinessCode.SELECTED_PLAYLIST_DISABLED)
    }
    // Stalker URL resolution currently emits provider-identifying diagnostics in its legacy
    // session. Keep this closed-schema lab fail-closed until that path has a sanitized adapter.
    if (account.sourceType == XtreamAccount.SOURCE_STALKER) {
        return DebugFixtureSelection.Blocked(LabReadinessCode.UNSUPPORTED_SOURCE)
    }
    val recent = recents.firstOrNull { ref ->
        XtreamItemRegistry.parseId(ref.id)?.let { parsed ->
            parsed.accountId == account.id && parsed.kind == "live"
        } == true
    } ?: return DebugFixtureSelection.Blocked(LabReadinessCode.NO_RECENT_LIVE_CHANNEL)
    val parsed = XtreamItemRegistry.parseId(recent.id)
        ?: return DebugFixtureSelection.Blocked(LabReadinessCode.NO_RECENT_LIVE_CHANNEL)
    val streamId = parsed.streamId.toIntOrNull()
        ?: return DebugFixtureSelection.Blocked(LabReadinessCode.NO_RECENT_LIVE_CHANNEL)
    return DebugFixtureSelection.Ready(SelectedDebugFixture(account, recent.id, streamId))
}

/** Candidate materialization is mechanical; [com.nuvio.tv.playback.core.PlaybackPolicy] selects. */
internal fun media3LabCandidates(requirements: PlaybackRequirements): List<PlaybackGraph> {
    if (EngineType.MEDIA3 !in requirements.eligibleEngines) return emptyList()
    val surfaces = requirements.allowedSurfaceModes.intersect(
        setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW),
    )
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
        AudioOutputPreference.AUTO,
        AudioOutputPreference.PCM,
        -> AudioMode.DECODE
    }
    return surfaces.flatMap { surface ->
        decoders.map { decoder ->
            PlaybackGraph(
                id = "media3-${surface.name.lowercase()}-${decoder.name.lowercase()}",
                engine = EngineType.MEDIA3,
                outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
                decoderMode = decoder,
                audioMode = audio,
                surfaceMode = surface,
                secureOutput = requirements.secureOutputRequired,
            )
        }
    }
}

/** Debug lab candidates remain mechanical and never introduce an engine outside requirements. */
internal fun mpvLabCandidates(requirements: PlaybackRequirements): List<PlaybackGraph> {
    if (EngineType.LIBMPV !in requirements.eligibleEngines) return emptyList()
    val surfaces = requirements.allowedSurfaceModes.intersect(
        setOf(SurfaceMode.NATIVE_EMBED, SurfaceMode.GPU_RENDER),
    )
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
        AudioOutputPreference.AUTO,
        AudioOutputPreference.PCM,
        -> AudioMode.DECODE
    }
    return surfaces.flatMap { surface ->
        decoders.mapNotNull { decoder ->
            val output = when (surface) {
                SurfaceMode.NATIVE_EMBED -> GraphOutputProfile.MPV_DIRECT
                SurfaceMode.GPU_RENDER -> GraphOutputProfile.MPV_RENDER
                else -> return@mapNotNull null
            }
            if (output == GraphOutputProfile.MPV_DIRECT && decoder == DecoderMode.SOFTWARE) {
                return@mapNotNull null
            }
            PlaybackGraph(
                id = "libmpv-${surface.name.lowercase()}-${decoder.name.lowercase()}",
                engine = EngineType.LIBMPV,
                outputProfile = output,
                decoderMode = decoder,
                audioMode = audio,
                surfaceMode = surface,
                secureOutput = requirements.secureOutputRequired,
            )
        }
    }
}

internal enum class LabPlayerState { IDLE, BUFFERING, READY, ENDED, ERROR, RELEASED }

/** Typed formatter for the exact closed schema consumed by playback_device_smoke.py. */
internal object CleanPlaybackSmokeLine {
    private const val PREFIX = "CP_SMOKE v=1"

    fun session(generation: Long, engine: EngineType = EngineType.MEDIA3): String =
        "$PREFIX event=SESSION engine=${engine.name} profile=GUIDE generation=$generation"

    fun state(
        generation: Long,
        state: LabPlayerState,
        playWhenReady: Boolean,
        loading: Boolean,
        engine: EngineType = EngineType.MEDIA3,
    ): String =
        "$PREFIX event=STATE engine=${engine.name} profile=GUIDE generation=$generation " +
            "player_state=${state.name} play_when_ready=$playWhenReady " +
            "is_loading=$loading"

    fun renderer(
        decoderName: String? = null,
        sampleMimeType: String? = null,
        engine: EngineType = EngineType.MEDIA3,
    ): String = buildString {
        val renderer = if (engine == EngineType.MEDIA3) "MediaCodecVideoRenderer" else "libmpv"
        append("$PREFIX event=RENDERER engine=${engine.name} renderer=$renderer")
        decoderName?.takeIf(SAFE_DECODER::matches)?.let { append(" decoder=").append(it) }
        sampleMimeType?.toSmokeCodec()?.let { append(" codec=").append(it) }
    }

    fun surface(mode: SurfaceMode, valid: Boolean, width: Int, height: Int): String {
        val engine = if (mode in setOf(SurfaceMode.SURFACE_VIEW, SurfaceMode.TEXTURE_VIEW)) {
            EngineType.MEDIA3
        } else {
            EngineType.LIBMPV
        }
        val surfaceType = when (mode) {
            SurfaceMode.SURFACE_VIEW -> "SURFACE_VIEW"
            SurfaceMode.TEXTURE_VIEW -> "TEXTURE_VIEW"
            SurfaceMode.NATIVE_EMBED -> "MPV_DIRECT"
            SurfaceMode.GPU_RENDER -> "MPV_RENDER"
        }
        return "$PREFIX event=SURFACE engine=${engine.name} surface_type=$surfaceType " +
            "surface_valid=$valid surface_width=${width.coerceAtLeast(0)} " +
            "surface_height=${height.coerceAtLeast(0)} secure=false"
    }

    fun firstFrame(engine: EngineType = EngineType.MEDIA3): String =
        "$PREFIX event=VIDEO engine=${engine.name} rendered_first_frame=true"

    fun firstAudio(engine: EngineType): String =
        "$PREFIX event=AUDIO engine=${engine.name} rendered_first_audio=true"

    fun videoSize(width: Int, height: Int, engine: EngineType = EngineType.MEDIA3): String {
        require(width > 0 && height > 0)
        return "$PREFIX event=VIDEO engine=${engine.name} video_width=$width video_height=$height"
    }

    fun metrics(rendered: Long?, dropped: Long?, engine: EngineType = EngineType.MEDIA3): String =
        "$PREFIX event=VIDEO engine=${engine.name} rendered_first_frame=${(rendered ?: 0) > 0} " +
            "dropped_frames=${(dropped ?: 0).coerceIn(0, Int.MAX_VALUE.toLong())}"

    fun error(failure: PlaybackFailure, engine: EngineType = EngineType.MEDIA3): String =
        "$PREFIX event=ERROR engine=${engine.name} error_domain=${failure.smokeDomain()} " +
            "error_code=${failure.code.name} phase=${failure.phase.smokePhase()} " +
            "fatal=${failure.retryability == Retryability.FATAL}"

    fun release(nonce: String, hardAbort: Boolean, engine: EngineType = EngineType.MEDIA3): String {
        require(RELEASE_NONCE.matches(nonce))
        return "$PREFIX event=RELEASE engine=${engine.name} " +
            "release_outcome=${if (hardAbort) "HARD_ABORT" else "GRACEFUL"} " +
            "provider_owned=false surface_owned=false release_nonce=$nonce"
    }

    private fun PlaybackFailure.smokeDomain(): String = when {
        code == FailureCode.PROVIDER_CONNECTION_LIMIT -> "PROVIDER_LIMIT"
        code == FailureCode.AUTHORIZATION_REJECTED -> "AUTHORIZATION"
        domain == FailureDomain.AUTHORIZATION_PROVIDER_LIMIT -> "AUTHORIZATION"
        domain == FailureDomain.VIDEO_RENDERER_SURFACE -> "VIDEO_RENDERER_SURFACE"
        else -> domain.name
    }

    private fun FailurePhase.smokePhase(): String = when (this) {
        FailurePhase.REQUEST_RESOLUTION,
        FailurePhase.GRAPH_SELECTION,
        -> "PREPARE"
        FailurePhase.SURFACE_ATTACHMENT,
        FailurePhase.ENGINE_START,
        -> "STARTUP"
        FailurePhase.PLAYBACK,
        FailurePhase.RECOVERY,
        -> "PLAYING"
        FailurePhase.RELEASE -> "RELEASE"
    }

    val RELEASE_NONCE = Regex("[a-f0-9]{16}")
    private val SAFE_DECODER = Regex("[A-Za-z0-9_.:+|-]{1,96}")

    private fun String.toSmokeCodec(): String? = when (lowercase()) {
        "video/avc" -> "AVC"
        "video/hevc" -> "HEVC"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mpeg2" -> "MPEG2"
        "video/mp4v-es" -> "MPEG4"
        "video/dolby-vision" -> "DOLBY_VISION"
        else -> null
    }
}

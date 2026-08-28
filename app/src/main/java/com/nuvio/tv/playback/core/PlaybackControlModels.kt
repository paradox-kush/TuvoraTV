package com.nuvio.tv.playback.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Generation-bound, engine-neutral VOD timeline facts. */
data class PlaybackTimelineFacts(
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedPositionMs: Long = 0,
    val seekable: Boolean = false,
) {
    init {
        require(positionMs >= 0) { "Playback position must not be negative" }
        require(durationMs == null || durationMs >= 0) { "Playback duration must not be negative" }
        require(bufferedPositionMs >= 0) { "Buffered position must not be negative" }
    }
}

@JvmInline
value class PlaybackTrackId(val value: String) {
    init {
        require(value.isNotBlank()) { "Playback track id must not be blank" }
    }
}

enum class PlaybackTrackType { AUDIO, SUBTITLE }

/** Stable for one source generation; it contains presentation-safe facts and never a stream URL. */
data class PlaybackTrackDescriptor(
    val id: PlaybackTrackId,
    val type: PlaybackTrackType,
    val label: String? = null,
    val language: String? = null,
    val mimeType: String? = null,
    val codec: String? = null,
    val channelCount: Int? = null,
    val sampleRate: Int? = null,
    val forced: Boolean = false,
    val default: Boolean = false,
) {
    init {
        require(channelCount == null || channelCount > 0) { "Channel count must be positive" }
        require(sampleRate == null || sampleRate > 0) { "Sample rate must be positive" }
    }
}

data class PlaybackTrackCatalog(
    val revision: Long = 0,
    val audio: List<PlaybackTrackDescriptor> = emptyList(),
    val subtitles: List<PlaybackTrackDescriptor> = emptyList(),
    val selectedAudioTrackId: PlaybackTrackId? = null,
    val selectedSubtitleTrackId: PlaybackTrackId? = null,
    val subtitlesEnabled: Boolean = false,
) {
    init {
        require(revision >= 0) { "Track catalog revision must not be negative" }
        require(audio.all { it.type == PlaybackTrackType.AUDIO })
        require(subtitles.all { it.type == PlaybackTrackType.SUBTITLE })
        require(audio.map { it.id }.distinct().size == audio.size)
        require(subtitles.map { it.id }.distinct().size == subtitles.size)
        require(selectedAudioTrackId == null || audio.any { it.id == selectedAudioTrackId })
        require(selectedSubtitleTrackId == null || subtitles.any { it.id == selectedSubtitleTrackId })
        require(subtitlesEnabled || selectedSubtitleTrackId == null)
    }
}

@JvmInline
value class ExternalSubtitleId(val value: String) {
    init {
        require(value.isNotBlank()) { "External subtitle id must not be blank" }
    }

    override fun toString(): String = "ExternalSubtitleId([OPAQUE])"
}

/** Private transport input registered by the VOD subtitle feature coordinator. */
data class ExternalSubtitleRegistration(
    val uri: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
) {
    init {
        require(uri.isNotBlank()) { "External subtitle URI must not be blank" }
        require(mimeType.isNotBlank()) { "External subtitle MIME type must not be blank" }
    }

    override fun toString(): String =
        "ExternalSubtitleRegistration(uri=[REDACTED], mimeType=$mimeType, language=$language, label=$label)"
}

/** Adapter-only lookup. Public snapshots and presentation state contain only the opaque id. */
fun interface ExternalSubtitleResolver {
    fun resolve(id: ExternalSubtitleId): ExternalSubtitleRegistration?
}

/** One destination owns one registry and clears it after its playback release barrier. */
internal class DestinationExternalSubtitleRegistry : ExternalSubtitleResolver {
    private val sequence = AtomicLong(0)
    private val registrations = ConcurrentHashMap<ExternalSubtitleId, ExternalSubtitleRegistration>()

    fun register(registration: ExternalSubtitleRegistration): ExternalSubtitleId {
        val next = sequence.incrementAndGet()
        check(next > 0) { "External subtitle registry exhausted" }
        return ExternalSubtitleId("external-$next").also { registrations[it] = registration }
    }

    override fun resolve(id: ExternalSubtitleId): ExternalSubtitleRegistration? = registrations[id]

    fun clear() {
        registrations.clear()
    }
}

enum class PlaybackCompletionReason { EOF }

/** State that the session, not an engine instance, carries across a rebuild or handoff. */
data class VodRestorationCheckpoint(
    val positionMs: Long,
    val playWhenReady: Boolean,
    val selectedAudio: RestorableTrackSelection?,
    val selectedSubtitle: RestorableTrackSelection?,
    val subtitlesEnabled: Boolean,
    val subtitleDelayMs: Long,
    val playbackRate: Float,
) {
    init {
        require(positionMs >= 0)
        require(playbackRate.isFinite() && playbackRate in 0.25f..4f)
    }
}

/** Cross-engine selection identity: prefer the exact ID, then match stable media facts. */
data class RestorableTrackSelection(
    val originalId: PlaybackTrackId,
    val type: PlaybackTrackType,
    val language: String?,
    val label: String?,
    val mimeType: String?,
    val codec: String?,
    val channelCount: Int?,
    val forced: Boolean,
)

internal fun PlaybackSnapshot.vodCheckpoint(subtitleDelayMs: Long): VodRestorationCheckpoint = VodRestorationCheckpoint(
    positionMs = positionMs,
    playWhenReady = playWhenReady,
    selectedAudio = trackCatalog.selectedAudioTrackId?.let { selected ->
        trackCatalog.audio.firstOrNull { it.id == selected }?.restorable()
    },
    selectedSubtitle = trackCatalog.selectedSubtitleTrackId?.let { selected ->
        trackCatalog.subtitles.firstOrNull { it.id == selected }?.restorable()
    },
    subtitlesEnabled = trackCatalog.subtitlesEnabled,
    subtitleDelayMs = subtitleDelayMs,
    playbackRate = playbackRate,
)

private fun PlaybackTrackDescriptor.restorable() = RestorableTrackSelection(
    originalId = id,
    type = type,
    language = language,
    label = label,
    mimeType = mimeType,
    codec = codec,
    channelCount = channelCount,
    forced = forced,
)

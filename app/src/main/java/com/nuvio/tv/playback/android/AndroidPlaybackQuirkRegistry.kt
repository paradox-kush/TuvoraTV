package com.nuvio.tv.playback.android

import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode

internal enum class AndroidPlaybackQuirkDomain { VIDEO_DECODER, VIDEO_RENDERER_SURFACE, AUDIO_OUTPUT }

internal sealed interface AndroidPlaybackQuirkOverride {
    data class ForceEmbeddedSurface(
        val profile: SessionProfile,
        val surfaceMode: SurfaceMode,
    ) : AndroidPlaybackQuirkOverride

    /**
     * Exclude hardware video decoding entirely on this device. Introduced for the emulator
     * family, whose goldfish MediaCodec teardown deadlocks in native on repeated sessions
     * (androidx/media#2461; thread-dump-confirmed wedge at mpv_command during stop).
     */
    data object ForceSoftwareVideoDecode : AndroidPlaybackQuirkOverride
}

internal data class AndroidPlaybackQuirkMatch(
    val manufacturer: String,
    /** Exact model, or null when [hardware] alone identifies the platform (e.g. qemu). */
    val model: String? = null,
    /** Exact ro.hardware identity; the emulator family is exactly "ranchu". */
    val hardware: String? = null,
    val codecStableId: String? = null,
    val apiLevel: Int? = null,
    val firmware: String? = null,
) {
    init {
        require(model != null || hardware != null) {
            "A quirk match must pin model or hardware identity"
        }
    }

    fun matches(device: AndroidDeviceFacts, codecStableIds: Set<String>, runtimeApiLevel: Int): Boolean =
        manufacturer.equals(device.manufacturer.trim(), ignoreCase = true) &&
            (model == null || model.equals(device.model.trim(), ignoreCase = true)) &&
            (hardware == null || hardware.equals(device.hardware.trim(), ignoreCase = true)) &&
            (codecStableId == null || codecStableIds.any(codecStableId::equals)) &&
            (apiLevel == null || apiLevel == runtimeApiLevel) &&
            (firmware == null || firmware.equals(device.firmware.trim(), ignoreCase = true))
}

internal data class AndroidPlaybackQuirk(
    val id: String,
    val registryVersion: Int,
    val match: AndroidPlaybackQuirkMatch,
    val domain: AndroidPlaybackQuirkDomain,
    val override: AndroidPlaybackQuirkOverride,
    val evidenceReference: String,
    val introducedInAppVersion: String,
    val revalidateAfterEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(id.isNotBlank())
        require(registryVersion > 0)
        require(evidenceReference.isNotBlank())
        require(introducedInAppVersion.isNotBlank())
        require(revalidateAfterEpochMs < expiresAtEpochMs)
    }
}

internal data class AppliedAndroidPlaybackQuirk(
    val quirk: AndroidPlaybackQuirk,
    val revalidationDue: Boolean,
)

/** The only clean-player authority allowed to match Android device identity. */
internal object AndroidPlaybackQuirkRegistry {
    const val VERSION: Int = 1

    private val records = listOf(
        AndroidPlaybackQuirk(
            id = "amazon-aftkm-embedded-surface-texture-v1",
            registryVersion = VERSION,
            // Deliberately narrower than the historical family heuristic: AFTKM is the exact model
            // physically verified in the 2026-08-26 RCA. Unverified Amazon/MTK models do not match.
            match = AndroidPlaybackQuirkMatch(manufacturer = "Amazon", model = "AFTKM"),
            domain = AndroidPlaybackQuirkDomain.VIDEO_RENDERER_SURFACE,
            override = AndroidPlaybackQuirkOverride.ForceEmbeddedSurface(
                profile = SessionProfile.GUIDE,
                surfaceMode = SurfaceMode.TEXTURE_VIEW,
            ),
            evidenceReference = "research/livetv-freeze-rca-2026-08-26.md#2-f1--fire-tv-black-guide-fixed-verified",
            introducedInAppVersion = "clean-slate-wp2",
            revalidateAfterEpochMs = 1_803_600_000_000L, // 2027-02-26T00:00:00Z
            expiresAtEpochMs = 1_819_238_400_000L, // 2027-08-26T00:00:00Z
        ),
        AndroidPlaybackQuirk(
            id = "emulator-ranchu-software-video-decode-v1",
            registryVersion = VERSION,
            // The Android Emulator family, exactly: ro.hardware == "ranchu" (qemu). Its goldfish
            // C2 decoders deadlock in native teardown on repeated sessions (androidx/media#2461;
            // thread-dump-confirmed on API 36 arm64 AVD, 2026-08-28), wedging BOTH engines'
            // release paths. Software decode removes MediaCodec from the pipeline entirely.
            // Emulators are development surfaces — far expiry, never a shipped-device time bomb.
            match = AndroidPlaybackQuirkMatch(manufacturer = "Google", hardware = "ranchu"),
            domain = AndroidPlaybackQuirkDomain.VIDEO_DECODER,
            override = AndroidPlaybackQuirkOverride.ForceSoftwareVideoDecode,
            evidenceReference =
                "playback-provenance/implementation-log.md#2026-08-28 (goldfish teardown wedge) + androidx/media#2461",
            introducedInAppVersion = "1.5.9",
            revalidateAfterEpochMs = 2_082_758_400_000L, // 2036-01-01T00:00:00Z
            expiresAtEpochMs = 2_114_380_800_000L, // 2037-01-01T00:00:00Z
        ),
    )

    fun resolve(
        device: AndroidDeviceFacts,
        codecStableIds: Set<String>,
        nowEpochMs: Long,
        apiLevel: Int,
    ): List<AppliedAndroidPlaybackQuirk> = records
        .asSequence()
        .filter { nowEpochMs < it.expiresAtEpochMs }
        .filter { it.match.matches(device, codecStableIds, apiLevel) }
        .map { AppliedAndroidPlaybackQuirk(it, revalidationDue = nowEpochMs >= it.revalidateAfterEpochMs) }
        .toList()

    /**
     * Human-readable findings for quirks whose evidence has gone stale on THIS device: expired
     * entries silently stop applying (reverting device behavior with no code change), and
     * revalidation-due entries are running on old evidence. Callers must surface these — a quirk
     * expiry must never again be a silent shipped-fleet time bomb (AFTKM, 2027-08-26).
     */
    fun revalidationFindings(
        device: AndroidDeviceFacts,
        codecStableIds: Set<String>,
        nowEpochMs: Long,
        apiLevel: Int,
    ): List<String> = records
        .filter { it.match.matches(device, codecStableIds, apiLevel) }
        .mapNotNull {
            when {
                nowEpochMs >= it.expiresAtEpochMs ->
                    "${it.id} EXPIRED — its override no longer applies on this device"
                nowEpochMs >= it.revalidateAfterEpochMs ->
                    "${it.id} revalidation due — evidence ${it.evidenceReference} is stale"
                else -> null
            }
        }
}

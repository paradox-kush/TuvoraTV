package com.nuvio.tv.playback.android

import com.nuvio.tv.playback.core.SessionProfile
import com.nuvio.tv.playback.core.SurfaceMode

internal enum class AndroidPlaybackQuirkDomain { VIDEO_DECODER, VIDEO_RENDERER_SURFACE, AUDIO_OUTPUT }

internal sealed interface AndroidPlaybackQuirkOverride {
    data class ForceEmbeddedSurface(
        val profile: SessionProfile,
        val surfaceMode: SurfaceMode,
    ) : AndroidPlaybackQuirkOverride
}

internal data class AndroidPlaybackQuirkMatch(
    val manufacturer: String,
    val model: String,
    val codecStableId: String? = null,
    val apiLevel: Int? = null,
    val firmware: String? = null,
) {
    fun matches(device: AndroidDeviceFacts, codecStableIds: Set<String>, runtimeApiLevel: Int): Boolean =
        manufacturer.equals(device.manufacturer.trim(), ignoreCase = true) &&
            model.equals(device.model.trim(), ignoreCase = true) &&
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
}

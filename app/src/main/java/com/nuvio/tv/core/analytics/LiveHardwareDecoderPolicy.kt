package com.nuvio.tv.core.analytics

/**
 * ⚠️ SUPERSEDED — NOT WIRED (since 1.5.8). This per-SoC gate shipped wired in 1.5.6 ("Fix 2")
 * and was replaced in 1.5.8 by [LiveDefaultEnginePolicy]: live now opens on the ffmpeg/libmpv
 * engine EVERYWHERE the lane exists, not just on the worst decoders, so gating by SoC is moot.
 * The pure decision + the fleet telemetry below are kept, test-pinned, as the record of which
 * decoders drove that product decision (and in case a per-SoC exception is ever needed again).
 *
 * Original intent: whether live playback should open on the software engine (libmpv) instead of
 * ExoPlayer's hardware decoder, for a device whose video decoder video-stalls on live MPEG-TS far
 * above the fleet baseline.
 *
 * Derived from PostHog fleet telemetry (project 494529, 30 d, 2026-08-25). Live freeze rate =
 * `(live_preview_stall + live_playback_freeze) / playback_started`:
 *
 *  - Google TV Streamer (MediaTek MT8696) — 0.56
 *  - Fire TV Stick 4K Max / AFTMM (MediaTek MT8696) — 1.0
 *  - Skyworth UHD Google TV STB — 0.45
 *  - **onn. 4K Streaming Box (Amlogic) — 0.38**
 *  - TCL Smart TV Pro — 0.36, RockChip X88 — 0.30, Chromecast (Amlogic) — 0.21, SHIELD — 0.19
 *  - onn. 4K **Pro** — only 0.08
 *
 * The freeze is a plain `video_stalled` on raw `.ts` (not backward-PTS, `position_jumped_back = 0`)
 * and is not codec-specific — it correlates with the **hardware decoder / SoC**, so the neutral
 * capability to gate on is the decoder's identity (its name), with a small device-model allowlist
 * for the worst non-MediaTek offender. It is deliberately **narrow and tunable**: libmpv carries a
 * live startup cost on budget TVs (the reason live no longer force-selects it — see
 * `PlayerRuntimeControllerInitialization`), so this only fires for decoders/devices the data marks
 * as the worst, not "all hardware decoders" or "all budget TVs".
 *
 * Scope: **live only** (VOD is unaffected — the freeze is a live-TS symptom). Applies where the
 * ExoPlayer default is in force; a user's explicit engine choice still wins upstream.
 *
 * Pure: the decoder name and model are passed in, so every decision is pinned by tests without a
 * device, a `MediaCodecList`, or a player. The caller resolves the would-be live video decoder name
 * from `MediaCodecList` at startup (before playback) and the model from `Build.MODEL`.
 */
internal object LiveHardwareDecoderPolicy {

    /**
     * Decoder-name fragments (lower-cased, `contains`) for SoC decoder families that video-stall on
     * live `.ts` above the fleet baseline. MediaTek is the worst family — both MT8696 devices
     * (Google TV Streamer, Fire TV 4K Max) top the fleet — and MediaTek's live-TS/HEVC decoder bugs
     * are well documented (androidx/media #2765, ExoPlayer #678). Its decoders name themselves
     * `c2.mtk.*` (Codec2) or `OMX.MTK.*` (legacy). MediaTek phones already default to libmpv, so a
     * broad MediaTek match is effectively TV-only here.
     */
    private val PROBLEM_DECODER_FRAGMENTS = listOf("c2.mtk.", "omx.mtk.", "mediatek")

    /**
     * Exact device models (case-insensitive) whose hardware decoder is a top fleet offender but
     * whose decoder name is shared with well-behaved siblings, so it cannot be told apart by decoder
     * name alone. The Amlogic `onn. 4K Streaming Box` freezes at 0.38 while the `onn. 4K Pro`
     * (0.08) and Chromecast (0.21) do not, yet all are `c2.amlogic.*` — so this one is pinned by
     * model. Extend as telemetry warrants; keep it minimal.
     */
    private val PROBLEM_DEVICE_MODELS = setOf("onn. 4k streaming box")

    /**
     * True when live should open on libmpv for this device/decoder.
     *
     * @param deviceModel `Build.MODEL`, or null if unknown.
     * @param videoDecoderName the name of the video decoder ExoPlayer would select for this live
     *   stream (e.g. `c2.mtk.avc.decoder`), or null if it could not be resolved.
     */
    fun preferLibmpvForLive(deviceModel: String?, videoDecoderName: String?): Boolean {
        val decoder = videoDecoderName?.lowercase()?.trim().orEmpty()
        if (decoder.isNotEmpty() && PROBLEM_DECODER_FRAGMENTS.any { decoder.contains(it) }) return true

        val model = deviceModel?.lowercase()?.trim().orEmpty()
        return model.isNotEmpty() && model in PROBLEM_DEVICE_MODELS
    }
}

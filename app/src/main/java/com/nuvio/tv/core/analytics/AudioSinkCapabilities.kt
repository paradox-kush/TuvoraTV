package com.nuvio.tv.core.analytics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

/**
 * Reads the DEVICE audio-sink truth that decides whether a passthrough codec can be heard: which
 * encodings the active output accepts, its max channel count, and the route. This is the correlate
 * the "some channels are silent" reports were missing — a channel's E-AC-3/DTS is silent when the
 * sink can neither passthrough it (not in [SinkCaps.supportedEncodings]) nor soft-decode it.
 *
 * Source is [AudioDeviceInfo.getEncodings]/`getChannelCounts` over the real output devices (API 23+),
 * folded to [AudioOutputTelemetry]'s closed vocabulary. All reads are defensive: diagnostics must
 * never take down playback, and vendor ROMs return odd device inventories.
 */
object AudioSinkCapabilities {

    /** Best-effort sink capabilities; empty encodings + null fields when nothing is readable. */
    fun read(context: Context): AudioOutputTelemetry.SinkCaps = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@runCatching AudioOutputTelemetry.SinkCaps(emptySet(), null, null)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val active = outputs.firstOrNull { it.type in WIRED_DIGITAL_TYPES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_HDMI }
            ?: outputs.firstOrNull()
        val encodings = outputs
            .flatMap { it.encodings?.toList().orEmpty() }
            .mapNotNull(::encodingName)
            .toSet()
        val maxChannels = outputs
            .flatMap { it.channelCounts?.toList().orEmpty() }
            .maxOrNull()
            ?.takeIf { it > 0 }
        AudioOutputTelemetry.SinkCaps(
            supportedEncodings = encodings,
            maxChannels = maxChannels,
            route = active?.type?.let(::routeName),
        )
    }.getOrDefault(AudioOutputTelemetry.SinkCaps(emptySet(), null, null))

    private val WIRED_DIGITAL_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
    )

    /** Maps an [AudioFormat] ENCODING_* int to [AudioOutputTelemetry]'s codec vocabulary; null = drop. */
    internal fun encodingName(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT -> "pcm"
        AudioFormat.ENCODING_AC3 -> "ac3"
        AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC -> "eac3"
        AudioFormat.ENCODING_DTS -> "dts"
        AudioFormat.ENCODING_DTS_HD -> "dts_hd"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "truehd"
        AudioFormat.ENCODING_AAC_LC,
        AudioFormat.ENCODING_AAC_HE_V1,
        AudioFormat.ENCODING_AAC_HE_V2 -> "aac"
        AudioFormat.ENCODING_OPUS -> "opus"
        else -> null // ENCODING_DEFAULT/INVALID and vendor extensions we don't classify
    }

    private fun routeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_earc"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_AUX_LINE -> "spdif"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
        else -> "other"
    }
}

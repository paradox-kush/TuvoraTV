package com.nuvio.tv.core.analytics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import com.nuvio.tv.core.analytics.AudioOutputTelemetry.SinkCaps
import com.nuvio.tv.core.analytics.AudioOutputTelemetry.SinkCapsSource

/**
 * Reads DEVICE-ENUMERATED audio-sink capabilities from [AudioDeviceInfo] — the connected outputs and
 * what each advertises. This is the ENUMERATED source; it is NOT the same as the ACTIVE ROUTE the
 * audio actually uses (the player adapter computes active-route caps via media3 AudioCapabilities and
 * prefers them). Provenance is recorded on every result so the two are never conflated.
 *
 * Android-documented empty-array semantics are honoured: [AudioDeviceInfo.getEncodings] /
 * getChannelCounts return a ZERO-LENGTH array to mean "supports ARBITRARY encodings/channel counts",
 * NOT "supports none". Such a device is recorded as UNKNOWN capabilities (null), never as an empty
 * supported-set that would falsely flag every codec as unsupported. Encoding ints we do not classify
 * mark the set PARTIAL so an absence is not read as proof of non-support.
 */
object AudioSinkCapabilities {

    /** Enumerated capabilities of the best-guess active output device; UNKNOWN when nothing readable. */
    fun readEnumerated(context: Context): SinkCaps = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@runCatching UNKNOWN
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val device = outputs.firstOrNull { it.type in WIRED_DIGITAL_TYPES }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_HDMI }
            ?: outputs.firstOrNull()
            ?: return@runCatching UNKNOWN
        val (encodings, partial) = classifyEncodings(device.encodings)
        SinkCaps(
            source = SinkCapsSource.ENUMERATED_DEVICES,
            supportedEncodings = encodings,
            hasUnclassifiedEncodings = partial,
            maxChannels = classifyMaxChannels(device.channelCounts),
            route = routeName(device.type),
        )
    }.getOrDefault(UNKNOWN)

    private val UNKNOWN = SinkCaps(
        source = SinkCapsSource.UNKNOWN,
        supportedEncodings = null,
        hasUnclassifiedEncodings = false,
        maxChannels = null,
        route = null,
    )

    /**
     * Classifies an [AudioDeviceInfo.getEncodings] array to (supported-set, partial). null/empty ->
     * (null, false) meaning UNKNOWN/arbitrary per Android docs. Otherwise recognized ints fold to the
     * codec vocabulary and any unrecognized int (a vendor/newer encoding) sets partial = true.
     */
    internal fun classifyEncodings(encodings: IntArray?): Pair<Set<String>?, Boolean> {
        if (encodings == null || encodings.isEmpty()) return null to false // arbitrary / unspecified
        var partial = false
        val out = LinkedHashSet<String>()
        for (e in encodings) {
            val name = encodingName(e)
            if (name == null) partial = true else out.add(name)
        }
        return out to partial
    }

    /** null/empty channel-counts array -> null (arbitrary/unknown per docs); else the max advertised. */
    internal fun classifyMaxChannels(channelCounts: IntArray?): Int? =
        channelCounts?.filter { it > 0 }?.maxOrNull()

    /** Maps an [AudioFormat] ENCODING_* int to the codec vocabulary; null = unclassified (drop/partial). */
    internal fun encodingName(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT -> "pcm"
        AudioFormat.ENCODING_AC3 -> "ac3"
        AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC -> "eac3"
        AudioFormat.ENCODING_AC4 -> "ac4"
        AudioFormat.ENCODING_DTS -> "dts"
        AudioFormat.ENCODING_DTS_HD -> "dts_hd"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "truehd"
        AudioFormat.ENCODING_AAC_LC,
        AudioFormat.ENCODING_AAC_HE_V1,
        AudioFormat.ENCODING_AAC_HE_V2 -> "aac"
        AudioFormat.ENCODING_OPUS -> "opus"
        else -> null // ENCODING_DEFAULT/INVALID, IEC61937, and vendor/newer encodings we don't classify
    }

    private val WIRED_DIGITAL_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC,
    )

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

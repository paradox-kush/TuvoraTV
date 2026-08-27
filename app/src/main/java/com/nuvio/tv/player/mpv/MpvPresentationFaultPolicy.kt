package com.nuvio.tv.player.mpv

/**
 * Classifies libmpv VO log lines that prove frames are no longer reaching the Android surface.
 * `estimated-vf-fps` only proves decode/filter output and stays positive during this failure.
 */
internal object MpvPresentationFaultPolicy {
    fun isPresentationFault(prefix: String, message: String): Boolean {
        val imageReaderVo = prefix.contains("aimagereader", ignoreCase = true)
        if (!imageReaderVo) return false
        return message.contains("Waiting for frame timed out", ignoreCase = true) ||
            message.contains("acquireLatestImage failed", ignoreCase = true)
    }
}

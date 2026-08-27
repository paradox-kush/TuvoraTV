package com.nuvio.tv.playback.mediasession

/**
 * Display-only metadata accepted at the clean playback ingress.
 *
 * The type deliberately cannot carry a playback URL, request headers, provider credentials, or
 * artwork URL. Callers provide only a pre-redacted stable fingerprint and human labels. Suspicious
 * transport-shaped labels are replaced or omitted before they can reach Android's MediaSession.
 */
class CleanMediaSessionMetadata private constructor(
    val safeMediaId: String,
    val title: String,
    val subtitle: String?,
    val station: String?,
) {
    override fun toString(): String =
        "CleanMediaSessionMetadata(hasSubtitle=${subtitle != null}, hasStation=${station != null})"

    companion object {
        private const val FALLBACK_MEDIA_ID = "clean-playback"
        private const val FALLBACK_TITLE = "Tuvora"
        private const val MAX_LABEL_LENGTH = 256
        private val redactedFingerprintPattern = Regex("[a-fA-F0-9]{16,64}")
        private val whitespace = Regex("\\s+")
        private val secretMarkers = listOf(
            "://",
            "authorization:",
            "bearer ",
            "username=",
            "password=",
            "token=",
            "auth=",
            "cookie:",
        )

        fun fromIngress(
            redactedContentFingerprint: String,
            title: String,
            subtitle: String? = null,
            station: String? = null,
        ): CleanMediaSessionMetadata = CleanMediaSessionMetadata(
            safeMediaId = redactedContentFingerprint.trim()
                .takeIf(redactedFingerprintPattern::matches)
                ?.lowercase()
                ?.let { "clean-${it.take(32)}" }
                ?: FALLBACK_MEDIA_ID,
            title = sanitizeLabel(title) ?: FALLBACK_TITLE,
            subtitle = sanitizeLabel(subtitle),
            station = sanitizeLabel(station),
        )

        private fun sanitizeLabel(value: String?): String? {
            val normalized = value
                ?.filterNot { it.code < 0x20 || it.code == 0x7f }
                ?.trim()
                ?.replace(whitespace, " ")
                ?.take(MAX_LABEL_LENGTH)
                ?.takeIf(String::isNotEmpty)
                ?: return null
            val lowercase = normalized.lowercase()
            return normalized.takeUnless { secretMarkers.any(lowercase::contains) }
        }
    }
}

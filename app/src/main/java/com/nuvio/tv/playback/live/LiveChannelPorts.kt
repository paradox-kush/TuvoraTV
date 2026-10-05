package com.nuvio.tv.playback.live

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackProfileId
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import com.nuvio.tv.playback.core.ProviderSelectionId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class LiveZapDirection { PREVIOUS, NEXT }

enum class LiveInitialFailure { UNAVAILABLE, PROFILE_CHANGED, INVALID_TARGET }

enum class LiveRelativeFailure { UNAVAILABLE, PROFILE_CHANGED, INVALID_TARGET }

/** Provider-neutral initial live-channel request. Stable identities are never printable. */
class LiveInitialRequest(
    val contentId: ProviderSelectionId,
    val boundProfileId: PlaybackProfileId,
) {
    override fun toString(): String = "LiveInitialRequest(profileBound=true)"
}

sealed interface LiveInitialResult {
    class Target(val target: LiveChannelTarget) : LiveInitialResult {
        override fun toString(): String = "LiveInitialResult.Target(target=[REDACTED])"
    }

    data class Rejected(val reason: LiveInitialFailure) : LiveInitialResult
}

fun interface LiveChannelSelectionPort {
    suspend fun select(request: LiveInitialRequest): LiveInitialResult
}

/** Provider-neutral relative-channel query. Stable identities are deliberately never printable. */
class LiveRelativeRequest(
    val currentContentId: ProviderSelectionId,
    val direction: LiveZapDirection,
    val boundProfileId: PlaybackProfileId,
) {
    override fun toString(): String =
        "LiveRelativeRequest(direction=$direction, profileBound=true)"
}

/** One atomic selection + display identity captured from the same playlist snapshot. */
class LiveChannelTarget private constructor(
    val selection: ProviderPlaybackSelection,
    val contentId: ProviderSelectionId,
    val title: String,
    val logo: String?,
    val playlistVersion: Long?,
    val mediaFingerprint: String,
    /**
     * T3: false when [title] is the "Live TV" stand-in (no usable name was known). Display may use
     * the stand-in; persistence must not — it once replaced real favourite / recent names.
     */
    val titleKnown: Boolean = true,
) {
    init {
        require(selection.contentType == ContentType.LIVE) { "Live channel target must be LIVE" }
        require(selection.contentKey == contentId) { "Live target identity must match its selection" }
        require(title.isNotBlank()) { "Live target title must not be blank" }
        require(playlistVersion == null || playlistVersion > 0) {
            "Playlist version must be positive when known"
        }
        require(FINGERPRINT.matches(mediaFingerprint)) { "Live media fingerprint must be SHA-256" }
    }

    override fun toString(): String =
        "LiveChannelTarget(hasLogo=${logo != null}, playlistVersionKnown=${playlistVersion != null})"

    companion object {
        private const val FALLBACK_TITLE = "Live TV"
        private const val MAX_TITLE_LENGTH = 256
        private const val MAX_LOGO_LENGTH = 2_048
        private val FINGERPRINT = Regex("[a-f0-9]{64}")
        private val WHITESPACE = Regex("\\s+")
        private val SECRET_MARKERS = listOf(
            "authorization:",
            "bearer ",
            "username=",
            "password=",
            "token=",
            "auth=",
            "cookie:",
        )

        fun sanitized(
            selection: ProviderPlaybackSelection,
            contentId: ProviderSelectionId,
            title: String,
            logo: String?,
            playlistVersion: Long?,
            boundProfileId: PlaybackProfileId,
            /** False when the caller's [title] is itself a stand-in (T3). */
            titleKnown: Boolean = true,
        ): LiveChannelTarget {
            val usable = usableTitle(title)
            return LiveChannelTarget(
                selection = selection,
                contentId = contentId,
                title = usable ?: FALLBACK_TITLE,
                logo = sanitizeLogo(logo),
                playlistVersion = playlistVersion,
                mediaFingerprint = LiveMediaFingerprint.create(selection, boundProfileId),
                titleKnown = titleKnown && usable != null,
            )
        }

        /** The cleaned title, or null when there is none worth showing (blank, a URL, a secret). */
        private fun usableTitle(value: String): String? {
            val normalized = clean(value, MAX_TITLE_LENGTH)?.replace(WHITESPACE, " ")
            return normalized
                ?.takeUnless { candidate ->
                    val lowercase = candidate.lowercase()
                    "://" in lowercase || SECRET_MARKERS.any(lowercase::contains)
                }
        }

        private fun sanitizeLogo(value: String?): String? {
            val normalized = clean(value, MAX_LOGO_LENGTH) ?: return null
            val lowercase = normalized.lowercase()
            if (SECRET_MARKERS.any(lowercase::contains)) return null
            // Reject URL user-info. Display artwork never needs credentials embedded in authority.
            if (Regex("^[a-z][a-z0-9+.-]*://[^/@]+@", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)
            ) {
                return null
            }
            return normalized
        }

        private fun clean(value: String?, maximumLength: Int): String? = value
            ?.filterNot { it.code < 0x20 || it.code == 0x7f }
            ?.trim()
            ?.take(maximumLength)
            ?.takeIf(String::isNotEmpty)
    }
}

sealed interface LiveRelativeResult {
    class Target(val target: LiveChannelTarget) : LiveRelativeResult {
        override fun toString(): String = "LiveRelativeResult.Target(target=[REDACTED])"
    }

    data class Rejected(val reason: LiveRelativeFailure) : LiveRelativeResult
}

fun interface LiveChannelNavigationPort {
    suspend fun relative(request: LiveRelativeRequest): LiveRelativeResult
}

/** History evidence is accepted only after this exact playback generation renders video. */
class LivePlayedIdentity(
    val target: LiveChannelTarget,
    val boundProfileId: PlaybackProfileId,
    val generation: Long,
) {
    init {
        require(generation > 0) { "Played generation must be positive" }
    }

    override fun toString(): String =
        "LivePlayedIdentity(generation=$generation, profileBound=true, target=[REDACTED])"
}

fun interface LivePlayedHistoryPort {
    suspend fun record(identity: LivePlayedIdentity)
}

/** Shared exact fingerprint algorithm for initial launches and later relative-channel targets. */
object LiveMediaFingerprint {
    fun create(
        selection: ProviderPlaybackSelection,
        boundProfileId: PlaybackProfileId,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            boundProfileId.value,
            selection.sourceType.name,
            selection.accountId.value,
            selection.itemId.value,
            selection.contentKey.value,
            selection.contentType.name,
            selection.catchUpWindow?.startEpochMs?.toString().orEmpty(),
            selection.catchUpWindow?.endEpochMs?.toString().orEmpty(),
        ).forEach { component ->
            val bytes = component.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

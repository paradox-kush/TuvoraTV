package com.nuvio.tv.ui.navigation

import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.ProviderPlaybackSelection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

enum class CleanLiveLaunchOrigin { SEARCH, LIBRARY }

/** Display-only launch metadata. Transport-shaped labels are discarded before storage. */
class CleanLiveLaunchMetadata private constructor(
    val title: String,
    val subtitle: String?,
    val station: String?,
) {
    override fun toString(): String =
        "CleanLiveLaunchMetadata(hasSubtitle=${subtitle != null}, hasStation=${station != null})"

    companion object {
        private const val FALLBACK_TITLE = "Tuvora"
        private const val MAX_LABEL_LENGTH = 256
        private val whitespace = Regex("\\s+")
        private val forbiddenMarkers = listOf(
            "://",
            "authorization:",
            "bearer ",
            "username=",
            "password=",
            "token=",
            "auth=",
            "cookie:",
        )

        fun sanitized(
            title: String,
            subtitle: String? = null,
            station: String? = null,
        ): CleanLiveLaunchMetadata = CleanLiveLaunchMetadata(
            title = sanitizeLabel(title) ?: FALLBACK_TITLE,
            subtitle = sanitizeLabel(subtitle),
            station = sanitizeLabel(station),
        )

        private fun sanitizeLabel(value: String?): String? {
            val normalized = value
                ?.filterNot { it.code < 0x20 || it.code == 0x7f }
                ?.trim()
                ?.replace(whitespace, " ")
                ?.takeIf(String::isNotEmpty)
                ?: return null
            val lowercase = normalized.lowercase()
            if (forbiddenMarkers.any(lowercase::contains)) return null
            return normalized.take(MAX_LABEL_LENGTH)
        }
    }
}

/** The only launch value allowed to cross a navigation argument. */
class CleanLiveLaunchToken internal constructor(
    val routeValue: String,
) {
    override fun equals(other: Any?): Boolean =
        other is CleanLiveLaunchToken && routeValue == other.routeValue

    override fun hashCode(): Int = routeValue.hashCode()
    override fun toString(): String = "CleanLiveLaunchToken([REDACTED])"
}

/** URL-free in-memory launch material consumed by exactly one clean fullscreen destination. */
class CleanLiveLaunchEntry internal constructor(
    val selection: ProviderPlaybackSelection,
    val activeProfileId: Int,
    val metadata: CleanLiveLaunchMetadata,
    val origin: CleanLiveLaunchOrigin,
    val mediaFingerprint: String,
) {
    override fun toString(): String =
        "CleanLiveLaunchEntry(origin=$origin, profileBound=true, " +
            "hasSubtitle=${metadata.subtitle != null}, hasStation=${metadata.station != null})"
}

enum class CleanLiveLaunchConsumeFailure { MISSING, EXPIRED, PROFILE_MISMATCH }

sealed interface CleanLiveLaunchConsumeResult {
    class Ready(val entry: CleanLiveLaunchEntry) : CleanLiveLaunchConsumeResult {
        override fun toString(): String = "CleanLiveLaunchConsumeResult.Ready(entry=[REDACTED])"
    }

    data class Rejected(
        val reason: CleanLiveLaunchConsumeFailure,
    ) : CleanLiveLaunchConsumeResult
}

internal fun interface CleanLiveLaunchClock {
    fun nowMs(): Long
}

internal fun interface CleanLiveLaunchEntropy {
    fun nextBytes(size: Int): ByteArray
}

/**
 * Process-local launch handoff. It persists nothing and consumes every resolved token exactly once.
 * Stable provider identity remains inside the entry; only random capability tokens cross Nav args.
 */
@Singleton
class CleanLiveLaunchStore internal constructor(
    private val clock: CleanLiveLaunchClock,
    private val entropy: CleanLiveLaunchEntropy,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
) {
    @Inject
    constructor() : this(
        clock = CleanLiveLaunchClock { System.nanoTime() / 1_000_000L },
        entropy = SecureRandomLaunchEntropy(),
    )

    init {
        require(ttlMs > 0) { "Launch TTL must be positive" }
        require(maximumEntries > 0) { "Launch entry limit must be positive" }
    }

    private data class StoredEntry(
        val entry: CleanLiveLaunchEntry,
        val createdAtMs: Long,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, StoredEntry>()

    fun put(
        selection: ProviderPlaybackSelection,
        activeProfileId: Int,
        origin: CleanLiveLaunchOrigin,
        title: String,
        subtitle: String? = null,
        station: String? = null,
    ): CleanLiveLaunchToken {
        require(selection.contentType == ContentType.LIVE) {
            "The Search/Library clean-live destination accepts live selections only"
        }
        require(activeProfileId > 0) { "Active profile id must be positive" }
        val now = clock.nowMs()
        val entry = CleanLiveLaunchEntry(
            selection = selection,
            activeProfileId = activeProfileId,
            metadata = CleanLiveLaunchMetadata.sanitized(title, subtitle, station),
            origin = origin,
            mediaFingerprint = mediaFingerprint(selection, activeProfileId),
        )
        return synchronized(lock) {
            pruneExpired(now)
            while (entries.size >= maximumEntries) {
                entries.remove(entries.keys.first())
            }
            val token = uniqueToken()
            entries[token] = StoredEntry(entry, now)
            CleanLiveLaunchToken(token)
        }
    }

    /** Invalid token text is indistinguishable from an absent token. */
    fun consume(
        routeToken: String,
        currentProfileId: Int,
    ): CleanLiveLaunchConsumeResult {
        require(currentProfileId > 0) { "Current profile id must be positive" }
        if (!TOKEN_PATTERN.matches(routeToken)) return rejected(CleanLiveLaunchConsumeFailure.MISSING)
        val now = clock.nowMs()
        val stored = synchronized(lock) { entries.remove(routeToken) }
            ?: return rejected(CleanLiveLaunchConsumeFailure.MISSING)
        if (isExpired(stored, now)) return rejected(CleanLiveLaunchConsumeFailure.EXPIRED)
        if (stored.entry.activeProfileId != currentProfileId) {
            return rejected(CleanLiveLaunchConsumeFailure.PROFILE_MISMATCH)
        }
        return CleanLiveLaunchConsumeResult.Ready(stored.entry)
    }

    private fun uniqueToken(): String {
        repeat(MAXIMUM_TOKEN_ATTEMPTS) {
            val bytes = entropy.nextBytes(TOKEN_BYTES)
            require(bytes.size == TOKEN_BYTES) { "Launch entropy returned an invalid byte count" }
            val candidate = bytes.toHex()
            if (candidate !in entries) return candidate
        }
        throw IllegalStateException("Unable to allocate a unique clean-live launch token")
    }

    private fun pruneExpired(nowMs: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value, nowMs)) iterator.remove()
        }
    }

    private fun isExpired(stored: StoredEntry, nowMs: Long): Boolean =
        (nowMs - stored.createdAtMs).coerceAtLeast(0L) >= ttlMs

    private fun mediaFingerprint(
        selection: ProviderPlaybackSelection,
        activeProfileId: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            activeProfileId.toString(),
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
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun rejected(reason: CleanLiveLaunchConsumeFailure) =
        CleanLiveLaunchConsumeResult.Rejected(reason)

    private class SecureRandomLaunchEntropy : CleanLiveLaunchEntropy {
        private val random = SecureRandom()

        override fun nextBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
    }

    private companion object {
        const val TOKEN_BYTES = 32
        const val MAXIMUM_TOKEN_ATTEMPTS = 8
        const val DEFAULT_TTL_MS = 2L * 60L * 1_000L
        const val DEFAULT_MAXIMUM_ENTRIES = 16
        val TOKEN_PATTERN = Regex("[a-f0-9]{64}")
    }
}

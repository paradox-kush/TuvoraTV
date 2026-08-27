package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.StoredLiveChannelIdentity
import com.nuvio.tv.data.local.XtreamLiveStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal fun interface InitialLivePlaylistPresentationSource {
    fun presentationFor(contentId: String): LiveChannelPresentation?
}

internal fun interface InitialLiveRegistryItemSource {
    fun itemFor(contentId: String): XtreamResolvedItem?
}

internal fun interface ExplicitProfileStoredLiveIdentitySource {
    suspend fun identityFor(profileId: Int, contentId: String): StoredLiveChannelIdentity?
}

/** URL-free display material for one verified initial live identity. */
class IptvInitialLivePresentation internal constructor(
    val title: String,
    val logo: String?,
) {
    override fun toString(): String =
        "IptvInitialLivePresentation(hasLogo=${logo != null})"
}

/**
 * Reads display-only material for a future clean live ingress.
 *
 * The current immutable playlist wins, followed by an identity-verified registry entry and then
 * the exact persisted profile. No source in this reader accepts or returns playback transport.
 */
@Singleton
class IptvInitialLivePresentationReader internal constructor(
    private val playlist: InitialLivePlaylistPresentationSource,
    private val registry: InitialLiveRegistryItemSource,
    private val persisted: ExplicitProfileStoredLiveIdentitySource,
) {
    @Inject
    constructor(
        livePlaylist: XtreamLivePlaylist,
        itemRegistry: XtreamItemRegistry,
        liveStore: XtreamLiveStore,
    ) : this(
        playlist = InitialLivePlaylistPresentationSource(livePlaylist::presentationFor),
        registry = InitialLiveRegistryItemSource(itemRegistry::get),
        persisted = ExplicitProfileStoredLiveIdentitySource(liveStore::identityForProfile),
    )

    suspend fun read(
        profileId: Int,
        contentId: String,
    ): IptvInitialLivePresentation? {
        if (profileId <= 0 || contentId.isBlank() || contentId.length > MAX_CONTENT_ID_LENGTH) {
            return null
        }
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return null
        if (parsed.kind != LIVE_KIND) return null
        val streamId = parsed.streamId.toIntOrNull()?.takeIf { it > 0 } ?: return null

        readSafely { playlist.presentationFor(contentId) }
            ?.takeIf { it.contentId.value == contentId }
            ?.let { return sanitized(it.title, it.logo) }

        readSafely { registry.itemFor(contentId) }
            ?.takeIf { item -> item.matches(contentId, parsed.accountId, streamId) }
            ?.let { return sanitized(it.name, it.poster) }

        val stored = try {
            persisted.identityFor(profileId, contentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return stored
            ?.takeIf { it.contentId == contentId }
            ?.let { sanitized(it.title, it.logo) }
    }

    private inline fun <T> readSafely(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun XtreamResolvedItem.matches(
        contentId: String,
        accountId: String,
        streamId: Int,
    ): Boolean =
        id == contentId &&
            kind == XtreamKind.LIVE &&
            this.accountId == accountId &&
            this.streamId == streamId

    private fun sanitized(title: String, logo: String?): IptvInitialLivePresentation =
        IptvInitialLivePresentation(
            title = sanitizeTitle(title),
            logo = sanitizeLogo(logo),
        )

    private fun sanitizeTitle(value: String): String {
        val normalized = clean(value, MAX_TITLE_LENGTH)?.replace(WHITESPACE, " ")
        return normalized
            ?.takeUnless { candidate ->
                val lowercase = candidate.lowercase()
                "://" in lowercase || SECRET_MARKERS.any(lowercase::contains)
            }
            ?: FALLBACK_TITLE
    }

    private fun sanitizeLogo(value: String?): String? {
        val normalized = clean(value, MAX_LOGO_LENGTH) ?: return null
        val lowercase = normalized.lowercase()
        if (SECRET_MARKERS.any(lowercase::contains)) return null
        if (URL_USER_INFO.containsMatchIn(normalized)) return null
        return normalized
    }

    private fun clean(value: String?, maximumLength: Int): String? = value
        ?.filterNot { it.code < 0x20 || it.code == 0x7f }
        ?.trim()
        ?.take(maximumLength)
        ?.takeIf(String::isNotEmpty)

    private companion object {
        const val LIVE_KIND = "live"
        const val FALLBACK_TITLE = "Live TV"
        const val MAX_TITLE_LENGTH = 256
        const val MAX_LOGO_LENGTH = 2_048
        const val MAX_CONTENT_ID_LENGTH = 4_096
        val WHITESPACE = Regex("\\s+")
        val URL_USER_INFO = Regex(
            "^[a-z][a-z0-9+.-]*://[^/@]+@",
            RegexOption.IGNORE_CASE,
        )
        val SECRET_MARKERS = listOf(
            "authorization:",
            "bearer ",
            "username=",
            "password=",
            "token=",
            "auth=",
            "cookie:",
        )
    }
}

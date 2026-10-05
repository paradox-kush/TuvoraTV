package com.nuvio.tv.core.iptv.playback

import com.nuvio.tv.core.iptv.LiveChannelPresentation
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.XtreamKind
import com.nuvio.tv.core.iptv.XtreamLivePlaylist
import com.nuvio.tv.core.iptv.XtreamResolvedItem
import com.nuvio.tv.data.local.StoredLiveChannelIdentity
import com.nuvio.tv.data.local.XtreamLiveStore
import com.nuvio.tv.playback.core.PlaybackProfileId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal fun interface InitialLivePlaylistPresentationSource {
    fun presentationFor(profileId: Int, contentId: String): LiveChannelPresentation?
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
    /** T3: false when [title] is the "Live TV" stand-in (no source knew a name). */
    val titleKnown: Boolean = true,
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
        playlist = InitialLivePlaylistPresentationSource { profileId, contentId ->
            livePlaylist.presentationFor(
                profileId = PlaybackProfileId(profileId.toString()),
                contentId = contentId,
            )
        },
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

        // T3 (W2 device pass): a source that matches but has no usable name (a registry entry rebuilt
        // nameless, a playlist row whose name was a URL) no longer wins with the "Live TV" stand-in —
        // that stand-in was then saved as the channel's name and Favourites/Recent preferred it. The
        // next source is asked; only when none has a name is the stand-in used, marked as such.
        var fallbackLogo: String? = null
        var matched = false

        readSafely { playlist.presentationFor(profileId, contentId) }
            ?.takeIf { it.contentId.value == contentId }
            ?.let { found ->
                matched = true
                fallbackLogo = fallbackLogo ?: sanitizeLogo(found.logo)
                if (found.titleKnown) usableTitle(found.title)?.let { return named(it, found.logo) }
            }

        readSafely { registry.itemFor(contentId) }
            ?.takeIf { item -> item.matches(contentId, parsed.accountId, streamId) }
            ?.let { found ->
                matched = true
                fallbackLogo = fallbackLogo ?: sanitizeLogo(found.poster)
                usableTitle(found.name)?.let { return named(it, found.poster) }
            }

        val stored = try {
            persisted.identityFor(profileId, contentId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        stored
            ?.takeIf { it.contentId == contentId }
            ?.let { found ->
                matched = true
                fallbackLogo = fallbackLogo ?: sanitizeLogo(found.logo)
                usableTitle(found.title)?.let { return named(it, found.logo) }
            }
        return if (matched) {
            IptvInitialLivePresentation(title = FALLBACK_TITLE, logo = fallbackLogo, titleKnown = false)
        } else {
            null
        }
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

    private fun named(title: String, logo: String?): IptvInitialLivePresentation =
        IptvInitialLivePresentation(
            title = title,
            logo = sanitizeLogo(logo),
        )

    /** The cleaned title, or null when it is blank, a URL or carries a secret. */
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

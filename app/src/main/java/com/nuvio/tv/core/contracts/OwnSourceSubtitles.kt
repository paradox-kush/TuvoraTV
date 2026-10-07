package com.nuvio.tv.core.contracts

import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException

/**
 * Subtitles an own source supplies for ITS items itself (a media server's sidecar text subtitles beside the file).
 * Third-party subtitle add-ons are never asked for such an id (it embeds the server's and user's ids - see
 * [OwnSourcePolicy.isSubtitleScopedId]); this port is how its own subtitles still reach the player. Plural, duplicate
 * refused; a source that fails supplies nothing.
 */
interface OwnSourceSubtitleProvider {
    val name: String

    fun handles(videoId: String): Boolean

    suspend fun subtitles(videoId: String): List<Subtitle>
}

object OwnSourceSubtitleRegistry {
    private val providers = NamedRegistry<OwnSourceSubtitleProvider>("OwnSourceSubtitleProvider")

    fun register(provider: OwnSourceSubtitleProvider) = providers.register(provider.name, provider)

    /** The subtitles of every provider that owns [videoId] (empty for add-on / IPTV items). */
    suspend fun subtitlesFor(videoId: String?): List<Subtitle> {
        if (videoId.isNullOrBlank()) return emptyList()
        return providers.all.filter { it.handles(videoId) }.flatMap { provider ->
            try {
                provider.subtitles(videoId)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    fun resetForTest() = providers.resetForTest()
}

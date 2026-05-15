package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridStreamFormatter @Inject constructor(
    private val engine: DebridStreamTemplateEngine
) {
    fun format(stream: Stream, settings: DebridSettings): Stream {
        if (!stream.isDirectDebrid()) return stream
        val values = buildValues(stream)
        val formattedName = engine.render(settings.streamNameTemplate, values)
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val formattedDescription = engine.render(settings.streamDescriptionTemplate, values)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()

        return stream.copy(
            name = formattedName.ifBlank { stream.name ?: DebridProviders.instantName(stream.clientResolve?.service) },
            description = formattedDescription.ifBlank { stream.description ?: stream.title }
        )
    }

    private fun buildValues(stream: Stream): Map<String, Any?> {
        val resolve = stream.clientResolve
        val raw = resolve?.stream?.raw
        val parsed = raw?.parsed
        val season = resolve?.season
        val episode = resolve?.episode
        val seasons = parsed?.seasons.orEmpty()
        val episodes = parsed?.episodes.orEmpty()
        val visualTags = buildList {
            addAll(parsed?.hdr.orEmpty())
            parsed?.bitDepth?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val edition = parsed?.edition ?: buildEdition(parsed)

        return linkedMapOf(
            "stream.title" to (parsed?.parsedTitle ?: resolve?.title ?: stream.title),
            "stream.year" to parsed?.year,
            "stream.season" to season,
            "stream.episode" to episode,
            "stream.seasons" to seasons,
            "stream.episodes" to episodes,
            "stream.seasonEpisode" to buildSeasonEpisodeList(season, episode, seasons, episodes),
            "stream.formattedEpisodes" to formatEpisodes(episodes),
            "stream.formattedSeasons" to formatSeasons(seasons),
            "stream.resolution" to parsed?.resolution,
            "stream.library" to false,
            "stream.quality" to parsed?.quality,
            "stream.visualTags" to visualTags,
            "stream.audioTags" to parsed?.audio.orEmpty(),
            "stream.audioChannels" to parsed?.channels.orEmpty(),
            "stream.languages" to parsed?.languages.orEmpty(),
            "stream.languageEmojis" to parsed?.languages.orEmpty().map { languageEmoji(it) },
            "stream.size" to (raw?.size ?: stream.behaviorHints?.videoSize),
            "stream.folderSize" to raw?.folderSize,
            "stream.encode" to parsed?.codec?.uppercase(),
            "stream.indexer" to (raw?.indexer ?: raw?.tracker),
            "stream.network" to (parsed?.network ?: raw?.network),
            "stream.releaseGroup" to parsed?.group,
            "stream.duration" to parsed?.duration,
            "stream.edition" to edition,
            "stream.filename" to (raw?.filename ?: resolve?.filename ?: stream.behaviorHints?.filename),
            "stream.regexMatched" to null,
            "stream.type" to streamType(resolve),
            "service.cached" to resolve?.isCached,
            "service.shortName" to serviceShortName(resolve),
            "service.name" to serviceName(resolve),
            "addon.name" to "Nuvio Direct Debrid"
        )
    }

    private fun streamType(resolve: StreamClientResolve?): String {
        return when {
            resolve?.type.equals("debrid", ignoreCase = true) -> "Debrid"
            resolve?.type.equals("torrent", ignoreCase = true) -> "p2p"
            else -> resolve?.type.orEmpty()
        }
    }

    private fun serviceShortName(resolve: StreamClientResolve?): String {
        val extension = resolve?.serviceExtension?.takeIf { it.isNotBlank() }
        if (extension != null) return extension
        return DebridProviders.shortName(resolve?.service)
    }

    private fun serviceName(resolve: StreamClientResolve?): String {
        return DebridProviders.displayName(resolve?.service)
    }

    private fun buildEdition(parsed: StreamClientResolveParsed?): String? {
        if (parsed == null) return null
        return buildList {
            if (parsed.extended == true) add("extended")
            if (parsed.theatrical == true) add("theatrical")
            if (parsed.remastered == true) add("remastered")
            if (parsed.unrated == true) add("unrated")
        }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun buildSeasonEpisodeList(
        season: Int?,
        episode: Int?,
        seasons: List<Int>,
        episodes: List<Int>
    ): List<String> {
        if (season != null && episode != null) return listOf("S${season.twoDigits()}E${episode.twoDigits()}")
        if (seasons.isEmpty() || episodes.isEmpty()) return emptyList()
        return seasons.flatMap { s -> episodes.map { e -> "S${s.twoDigits()}E${e.twoDigits()}" } }
    }

    private fun formatEpisodes(episodes: List<Int>): String {
        return episodes.joinToString(" • ") { "E${it.twoDigits()}" }
    }

    private fun formatSeasons(seasons: List<Int>): String {
        return seasons.joinToString(" • ") { "S${it.twoDigits()}" }
    }

    private fun Int.twoDigits(): String = toString().padStart(2, '0')

    private fun languageEmoji(language: String): String {
        return when (language.lowercase()) {
            "en", "eng", "english" -> "🇬🇧"
            "hi", "hin", "hindi" -> "🇮🇳"
            "ml", "mal", "malayalam" -> "🇮🇳"
            "ta", "tam", "tamil" -> "🇮🇳"
            "te", "tel", "telugu" -> "🇮🇳"
            "ja", "jpn", "japanese" -> "🇯🇵"
            "ko", "kor", "korean" -> "🇰🇷"
            "fr", "fre", "fra", "french" -> "🇫🇷"
            "es", "spa", "spanish" -> "🇪🇸"
            "de", "ger", "deu", "german" -> "🇩🇪"
            "it", "ita", "italian" -> "🇮🇹"
            "multi" -> "Multi"
            else -> language
        }
    }
}

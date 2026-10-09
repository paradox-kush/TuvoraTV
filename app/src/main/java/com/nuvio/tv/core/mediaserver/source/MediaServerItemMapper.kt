package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaCastMember
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaBrowserUrls
import com.nuvio.tv.core.mediaserver.client.mediabrowser.MediaSourceDto
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds
import com.nuvio.tv.core.mediaserver.policy.MediaServerIds.Kind
import com.nuvio.tv.core.mediaserver.policy.PlaybackDecisionPolicy
import com.nuvio.tv.core.mediaserver.policy.VersionPickPolicy

/**
 * Server DTOs -> Tuvora's neutral models (design 5.4). Pure: no I/O, no state - a server entry (for ids and
 * image URLs) and a DTO in, a card / detail / registry record out. Image URLs are ALWAYS credential-free
 * ([MediaBrowserUrls.image]): both products serve images anonymously, and a URL with a token in it would
 * end up in Coil's disk cache journal.
 */
internal object MediaServerItemMapper {
    /** The Tuvora kind of a server item type, or null for a type Tuvora does not surface (music, live TV, ...). */
    fun kindOf(type: String?): Kind? = when (type) {
        "Movie" -> Kind.MOVIE
        "Series" -> Kind.SERIES
        "Season" -> Kind.SEASON
        "Episode" -> Kind.EPISODE
        else -> null
    }

    /** The `MetaPreview.type` the app's catalog models use for a kind. */
    fun previewType(kind: Kind): String = if (kind == Kind.MOVIE) "movie" else "series"

    /**
     * The card for [item]. An episode (Continue Watching / Next Up shelves) maps to its SERIES card: the card
     * opens the series page, and the episode's own title rides the description. Null for an unsupported type.
     */
    fun preview(entry: MediaServerEntry, item: ItemDto): MetaPreview? {
        val kind = kindOf(item.type) ?: return null
        val address = entry.address.orEmpty()
        if (kind == Kind.SEASON) return null // a season is never a standalone card
        val (cardKind, cardId, cardName) = if (kind == Kind.EPISODE) {
            val seriesId = item.seriesId ?: return null
            Triple(Kind.SERIES, seriesId, item.seriesName ?: item.name ?: return null)
        } else {
            Triple(kind, item.id ?: return null, item.name ?: return null)
        }
        val posterId: String
        val posterTag: String?
        if (kind == Kind.EPISODE) {
            posterId = cardId
            posterTag = item.seriesPrimaryImageTag
        } else {
            posterId = cardId
            posterTag = item.imageTags["Primary"]
        }
        // no Primary tag = no artwork (Emby answers an image request for such an item with a 500): a placeholder, not a broken URL
        val poster = posterTag?.let { MediaBrowserUrls.image(address, posterId, "Primary", tag = it, maxWidth = POSTER_WIDTH) }
        val (backdropId, backdropTag) = backdropOf(item)
        val banner = backdropTag?.let { MediaBrowserUrls.image(address, backdropId, "Backdrop", tag = it, maxWidth = BACKDROP_WIDTH) }
        val episodeNote = if (kind == Kind.EPISODE) {
            listOfNotNull(
                item.parentIndexNumber?.let { s -> item.indexNumber?.let { e -> "S$s:E$e" } },
                item.name,
            ).joinToString(" · ").ifBlank { null }
        } else null
        val type = previewType(cardKind)
        return MetaPreview(
            id = MediaServerIds.contentId(entry, cardKind, cardId),
            type = ContentType.fromString(type),
            rawType = type,
            name = cardName,
            poster = poster,
            posterShape = PosterShape.POSTER,
            background = banner,
            logo = null,
            description = episodeNote ?: item.overview,
            releaseInfo = item.productionYear?.toString(),
            imdbRating = item.communityRating?.toFloat(),
            genres = item.genres,
            released = item.premiereDate,
        )
    }

    private fun backdropOf(item: ItemDto): Pair<String, String?> {
        item.backdropImageTags.firstOrNull()?.let { return (item.id ?: "") to it }
        val parent = item.parentBackdropItemId
        val parentTag = item.parentBackdropImageTags.firstOrNull()
        if (parent != null && parentTag != null) return parent to parentTag
        return (item.id ?: "") to null
    }

    /** One decimal ("7.8") the way the app shows an external rating. */
    fun oneDecimal(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    }

    /** What a series/movie page shows: native metadata from the server (the TMDB enrichment is layered on by the caller when an id is present). */
    fun details(
        entry: MediaServerEntry,
        item: ItemDto,
        episodes: List<ItemDto>,
    ): Meta? {
        val kind = kindOf(item.type) ?: return null
        if (kind != Kind.MOVIE && kind != Kind.SERIES) return null
        val id = item.id ?: return null
        val address = entry.address.orEmpty()
        val (backdropId, backdropTag) = backdropOf(item)
        val people = item.people
        val type = previewType(kind)
        val background = backdropTag?.let { MediaBrowserUrls.image(address, backdropId, "Backdrop", tag = it, maxWidth = BACKDROP_WIDTH) }
        return Meta(
            id = MediaServerIds.contentId(entry, kind, id),
            type = ContentType.fromString(type),
            rawType = type,
            name = item.name ?: return null,
            poster = item.imageTags["Primary"]?.let { MediaBrowserUrls.image(address, id, "Primary", tag = it, maxWidth = POSTER_WIDTH) },
            posterShape = PosterShape.POSTER,
            background = background,
            logo = item.imageTags["Logo"]?.let { MediaBrowserUrls.image(address, id, "Logo", tag = it, maxWidth = LOGO_WIDTH) },
            description = item.overview,
            releaseInfo = item.productionYear?.toString(),
            status = item.status,
            imdbRating = item.communityRating?.toFloat(),
            genres = item.genres,
            runtime = item.runTimeTicks?.let { runtimeMinutes(PlaybackDecisionPolicy.ticksToMs(it))?.toString() },
            director = people.filter { it.type.equals("Director", true) }.mapNotNull { it.name },
            writer = people.filter { it.type.equals("Writer", true) }.mapNotNull { it.name },
            cast = people.filter { it.type.equals("Actor", true) }.mapNotNull { it.name },
            castMembers = people.filter { it.type.equals("Actor", true) }.mapNotNull { p ->
                p.name?.let { MetaCastMember(name = it, character = p.role, photo = p.id?.let { pid -> p.primaryImageTag?.let { tag -> MediaBrowserUrls.image(address, pid, "Primary", tag = tag, maxWidth = 200) } }) }
            },
            videos = if (kind == Kind.SERIES) episodes.mapNotNull { episodeVideo(entry, it) } else emptyList(),
            ageRating = item.officialRating,
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            imdbId = item.providerIds.entries.firstOrNull { it.key.equals("Imdb", ignoreCase = true) }?.value?.takeIf { it.isNotBlank() },
            released = item.premiereDate,
        )
    }

    private fun episodeVideo(entry: MediaServerEntry, ep: ItemDto): Video? {
        if (kindOf(ep.type) != Kind.EPISODE) return null
        val epId = ep.id ?: return null
        return Video(
            id = MediaServerIds.contentId(entry, Kind.EPISODE, epId),
            title = ep.name ?: "Episode ${ep.indexNumber ?: ""}".trim(),
            released = ep.premiereDate,
            thumbnail = ep.imageTags["Primary"]?.let { MediaBrowserUrls.image(entry.address.orEmpty(), epId, "Primary", tag = it, maxWidth = THUMB_WIDTH) },
            season = ep.parentIndexNumber,
            episode = ep.indexNumber,
            overview = ep.overview,
            runtime = ep.runTimeTicks?.let { runtimeMinutes(PlaybackDecisionPolicy.ticksToMs(it)) },
            // streams stay empty: server episodes resolve through the direct lane (handlesId -> registry), never as embedded streams
        )
    }

    private fun runtimeMinutes(ms: Long): Int? = (ms / 60_000L).toInt().takeIf { it > 0 }

    /** The registry record of a playable item (movie / episode): what the direct lane needs to offer streams without another request. */
    fun registered(entry: MediaServerEntry, item: ItemDto, versions: List<MediaSourceDto> = item.mediaSources): MediaServerItemRegistry.Item? {
        val kind = kindOf(item.type) ?: return null
        val id = item.id ?: return null
        if (kind != Kind.MOVIE && kind != Kind.EPISODE && kind != Kind.SERIES) return null
        val sources = sourcesOf(id, versions)
        return MediaServerItemRegistry.Item(
            contentId = MediaServerIds.contentId(entry, kind, id),
            serverKey = entry.serverKey,
            kind = kind,
            itemId = id,
            name = item.name ?: "Video",
            poster = item.imageTags["Primary"]?.let { MediaBrowserUrls.image(entry.address.orEmpty(), id, "Primary", tag = it, maxWidth = POSTER_WIDTH) },
            sources = sources,
            durationMs = item.runTimeTicks?.let(PlaybackDecisionPolicy::ticksToMs),
            serverPositionMs = item.userData?.playbackPositionTicks?.takeIf { it > 0 }?.let(PlaybackDecisionPolicy::ticksToMs),
            serverLastPlayedAt = item.userData?.lastPlayedDate,
        )
    }

    /** The versions of item [itemId] a viewer can pick, in the server's order (stand-ins left out, see [VersionPickPolicy.isPlaceholder]). */
    fun sourcesOf(itemId: String, versions: List<MediaSourceDto>): List<MediaServerItemRegistry.Source> =
        versions.filterNot { VersionPickPolicy.isPlaceholder(it.type) }.mapNotNull { s -> s.id?.let { sourceFacts(itemId, s) } }

    fun sourceFacts(itemId: String, s: MediaSourceDto): MediaServerItemRegistry.Source {
        val label = sourceLabel(s)
        return MediaServerItemRegistry.Source(
            id = VersionPickPolicy.pickId(itemId, s.id.orEmpty(), s.path),
            label = label,
            container = s.container,
            subtitles = sidecarSubtitles(s),
            description = VersionPickPolicy.description(s.name, label),
        )
    }

    /** External TEXT subtitles of [s] (a bitmap sidecar - `.sup`, `.sub` image - cannot be converted to text and is left out). */
    fun sidecarSubtitles(s: MediaSourceDto): List<MediaServerItemRegistry.SidecarSubtitle> = s.mediaStreams.mapNotNull { m ->
        if (!m.type.equals("Subtitle", true) || !m.isExternal) return@mapNotNull null
        if (m.codec?.lowercase() !in TEXT_SUBTITLE_CODECS) return@mapNotNull null
        val language = m.language?.takeIf { it.isNotBlank() } ?: "und"
        val label = (m.displayTitle ?: m.title)?.takeIf { it.isNotBlank() } ?: language.uppercase()
        MediaServerItemRegistry.SidecarSubtitle(m.index, language, label)
    }

    private val TEXT_SUBTITLE_CODECS = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml", "smi")

    /** "1080p · HEVC · 4.2 GB" - one entry per `MediaSource` on a title page ("Home Server · 4K HEVC · 12.4 GB" in the matched lane). */
    fun sourceLabel(s: MediaSourceDto): String {
        val video = s.mediaStreams.firstOrNull { it.type.equals("Video", true) }
        val resolution = video?.height?.let { h ->
            when {
                h >= 2000 -> "4K"
                h >= 1000 -> "1080p"
                h >= 700 -> "720p"
                h > 0 -> "${h}p"
                else -> null
            }
        }
        val codec = video?.codec?.uppercase()?.let { if (it == "H264") "H.264" else it }
        val size = s.size?.takeIf { it > 0 }?.let(::sizeLabel)
        // Nothing probed yet (an on-demand library lists versions before it has opened a file): the server's own name for the
        // version is the only description there is - better than a bare container.
        if (video == null) {
            s.name?.let(::flattenName)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return listOfNotNull(resolution, codec, s.container?.uppercase()?.takeIf { video == null }, size)
            .joinToString(" · ")
            .ifBlank { "Direct play" }
    }

    /**
     * A server that prepares streams on demand may give a version a name of several lines ("Server A\n1080p\nWEB-DL 4.2 GB"; name and
     * description joined with a newline) - one line for a button label.
     */
    fun flattenName(name: String): String = name.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    fun sizeLabel(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) return "${oneDecimal(gb)} GB"
        return "${bytes / 1_048_576} MB"
    }

    private const val POSTER_WIDTH = 400
    private const val BACKDROP_WIDTH = 1280
    private const val LOGO_WIDTH = 600
    private const val THUMB_WIDTH = 480
}

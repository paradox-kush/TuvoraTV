package com.nuvio.tv.core.recordings.internal

/**
 * Which episode a programme is, so a repeat is not recorded twice. Reads the forms guides use:
 * `S02E05`, `s2e5`, `S2 E5`, `S02.E05`, `2x05`, `Season 2 Episode 5`, `Ep. 12`, `E12`, and XMLTV
 * `xmltv_ns` (`1.4.`, `1.4/10.0/1`, zero-based). Rejects what is not an episode: a year used as a
 * season (`S2026E609`) and a database id (`609178259`).
 *
 * Adapted from homelessbrian/NuvioPlusIPTV (GPL-3.0), `XmltvParser.formatEpisode` / `cleanEpisode`.
 */
object SeriesEpisodeKey {

    data class Key(val season: Int?, val episode: Int) {
        /** "S02E05", or "E12" when there is no season. */
        val normalized: String
            get() = if (season != null) "S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            else "E${episode.toString().padStart(2, '0')}"
    }

    private val I = RegexOption.IGNORE_CASE
    private val XMLTV_SHAPE = Regex("""^[\d\s./]*$""")
    private val LONG_DIGITS = Regex("""\d{5,}""")
    private val BIG_SEASON = Regex("""\bS(?:eason)?\s*\.?\s*\d{3,}""", I)
    private val SEASON_EPISODE = Regex(
        """\bS(?:eason)?\s*\.?\s*(\d{1,2})\s*[.,\-:]?\s*E(?:p(?:isode)?)?\s*\.?\s*(\d{1,4})\b""", I,
    )
    private val NXM = Regex("""\b(\d{1,2})x(\d{1,3})\b""", I)
    private val EPISODE_ONLY = Regex("""\b(?:E|Ep|Episode)\s*\.?\s*(\d{1,4})\b""", I)
    private const val MAX_SEASON = 99
    private const val MAX_EPISODE = 9999

    fun fromText(raw: String?): Key? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (XMLTV_SHAPE.matches(t)) return if ('.' in t) fromXmltvNs(t) else null // bare digits: a plain id
        if (t.length <= 24 && LONG_DIGITS.containsMatchIn(t)) return null // "EP012345678"
        if (BIG_SEASON.containsMatchIn(t)) return null // a year as the season
        SEASON_EPISODE.find(t)?.let { return key(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        NXM.find(t)?.let { return key(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        EPISODE_ONLY.find(t)?.let { return key(null, it.groupValues[1].toInt()) }
        return null
    }

    /** XMLTV `xmltv_ns`: "season.episode.part", each optionally "/total", zero-based. */
    fun fromXmltvNs(raw: String?): Key? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || !XMLTV_SHAPE.matches(t)) return null
        val parts = t.split('.')
        if (parts.size < 2) return null
        val season = parts[0].substringBefore('/').trim().toIntOrNull()
        val episode = parts[1].substringBefore('/').trim().toIntOrNull() ?: return null
        if (season != null && season < 0 || episode < 0) return null
        return key(season?.plus(1), episode + 1)
    }

    /** Tries the guide's episode field first, then the title, then the description. */
    fun fromProgramme(title: String?, description: String?, xmltvEpisode: String? = null): Key? =
        fromText(xmltvEpisode) ?: fromText(title) ?: fromText(description)

    private fun key(season: Int?, episode: Int): Key? {
        if (season != null && season > MAX_SEASON) return null
        if (episode !in 1..MAX_EPISODE) return null
        return Key(season, episode)
    }
}

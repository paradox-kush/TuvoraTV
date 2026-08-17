package com.nuvio.tv.core.epg

/**
 * The regions offered by the EPG mirror, derived from what the backend actually published.
 *
 * The mirror indexes every source it carries, but a household uses a fraction of it: on a real
 * 11k-channel panel only 2,035 of 15,397 indexed EPG channels were ever matched — 87% of the
 * index was dead weight that still cost storage on every device and a Levenshtein walk on every
 * re-match. Letting the viewer say "I watch UK and India" turns that off at the source.
 *
 * Region identity is the country NAME string the backend publishes per source (`countries`,
 * comma-separated), because that is the only key both sides agree on. [flagFor] maps it to an
 * emoji flag for display; an unmapped name still works, it just shows no flag.
 */
data class EpgRegion(
    val name: String,
    val flag: String,
    /** Mirror sources covering this region. */
    val slugs: Set<String>,
    /** EPG channels these sources carry (sum across slugs; sources may overlap). */
    val channelCount: Int,
)

/** One published mirror source, as the client stores it. */
data class EpgSourceInfo(
    val slug: String,
    val label: String,
    /** Comma-separated country names, exactly as published; null/blank = unclassified. */
    val countries: String?,
    val channelCount: Int,
)

object EpgRegionCatalog {

    /** Shown for sources the backend published without a country (never hidden by a filter). */
    const val UNCLASSIFIED = "Other"

    /**
     * ISO 3166-1 alpha-2 for the country names the mirror publishes. Deliberately a lookup of
     * real published values rather than a general-purpose geo table — an unknown name degrades
     * to "no flag", never to a wrong flag.
     */
    private val CODES = mapOf(
        "united kingdom" to "GB", "great britain" to "GB", "england" to "GB",
        "united states" to "US", "usa" to "US",
        "ireland" to "IE", "canada" to "CA", "australia" to "AU", "new zealand" to "NZ",
        "spain" to "ES", "portugal" to "PT", "france" to "FR", "italy" to "IT",
        "germany" to "DE", "austria" to "AT", "switzerland" to "CH", "netherlands" to "NL",
        "belgium" to "BE", "poland" to "PL", "czechia" to "CZ", "czech republic" to "CZ",
        "slovakia" to "SK", "hungary" to "HU", "romania" to "RO", "bulgaria" to "BG",
        "greece" to "GR", "cyprus" to "CY", "turkey" to "TR", "türkiye" to "TR",
        "albania" to "AL", "serbia" to "RS", "croatia" to "HR",
        "bosnia and herzegovina" to "BA", "north macedonia" to "MK", "slovenia" to "SI",
        "montenegro" to "ME", "russia" to "RU", "ukraine" to "UA",
        "sweden" to "SE", "norway" to "NO", "denmark" to "DK", "finland" to "FI",
        "iceland" to "IS", "israel" to "IL", "india" to "IN", "pakistan" to "PK",
        "bangladesh" to "BD", "nepal" to "NP", "sri lanka" to "LK",
        "brazil" to "BR", "mexico" to "MX", "argentina" to "AR", "chile" to "CL",
        "colombia" to "CO", "peru" to "PE", "vietnam" to "VN", "thailand" to "TH",
        "malaysia" to "MY", "singapore" to "SG", "philippines" to "PH", "indonesia" to "ID",
        "japan" to "JP", "south korea" to "KR", "china" to "CN", "taiwan" to "TW",
        "hong kong" to "HK", "south africa" to "ZA", "egypt" to "EG", "morocco" to "MA",
        "algeria" to "DZ", "tunisia" to "TN", "saudi arabia" to "SA",
        "united arab emirates" to "AE", "qatar" to "QA", "kuwait" to "KW", "iraq" to "IQ",
        "iran" to "IR", "lebanon" to "LB", "jordan" to "JO", "syria" to "SY",
    )

    /**
     * Emoji flag for a country name, or "" when unknown. Built from regional-indicator
     * codepoints (A = U+1F1E6), so no image assets and no per-platform font work.
     */
    fun flagFor(countryName: String): String {
        val code = CODES[countryName.trim().lowercase()] ?: return ""
        return regionalIndicator(code[0]) + regionalIndicator(code[1])
    }

    /**
     * One regional-indicator letter. These live above U+FFFF, so the surrogate pair is built by
     * hand — `appendCodePoint` is JVM-only and this file is common code.
     */
    private fun regionalIndicator(letter: Char): String {
        val codePoint = 0x1F1E6 + (letter - 'A')
        val offset = codePoint - 0x10000
        val high = 0xD800 + (offset shr 10)
        val low = 0xDC00 + (offset and 0x3FF)
        return charArrayOf(high.toChar(), low.toChar()).concatToString()
    }

    /** Split a published `countries` value into its individual region names. */
    fun regionsOf(countries: String?): List<String> {
        val raw = countries?.split(',').orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return raw.ifEmpty { listOf(UNCLASSIFIED) }
    }

    /**
     * The picker's rows: one per region, with the sources behind it and how many EPG channels
     * those carry. Sorted by coverage so the regions worth having sit at the top.
     */
    fun catalogFrom(sources: List<EpgSourceInfo>): List<EpgRegion> {
        val slugs = HashMap<String, MutableSet<String>>()
        val counts = HashMap<String, Int>()
        for (src in sources) {
            for (region in regionsOf(src.countries)) {
                slugs.getOrPut(region) { mutableSetOf() }.add(src.slug)
                counts[region] = (counts[region] ?: 0) + src.channelCount
            }
        }
        return slugs.keys
            .map { region ->
                EpgRegion(
                    name = region,
                    flag = flagFor(region),
                    slugs = slugs[region].orEmpty(),
                    channelCount = counts[region] ?: 0,
                )
            }
            .sortedWith(compareByDescending<EpgRegion> { it.channelCount }.thenBy { it.name })
    }

    /**
     * Which source slugs to keep for [selection]. An EMPTY selection means "no preference" and
     * keeps everything — the picker is opt-in, so an untouched install behaves exactly as before.
     * Unclassified sources are always kept: hiding a source the backend never labelled would
     * silently drop coverage the viewer never chose to drop.
     */
    fun slugsFor(selection: Set<String>, sources: List<EpgSourceInfo>): Set<String> {
        if (selection.isEmpty()) return sources.map { it.slug }.toSet()
        val wanted = selection.map { it.trim().lowercase() }.toSet()
        return sources.filter { src ->
            val regions = regionsOf(src.countries)
            regions.any { it == UNCLASSIFIED || it.lowercase() in wanted }
        }.map { it.slug }.toSet()
    }
}

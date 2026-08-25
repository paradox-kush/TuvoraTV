package com.nuvio.tv.core.radar

/**
 * Region alignment for the broadcaster-listing lane.
 *
 * TheSportsDB lists a fixture's broadcasters as many international feeds (e.g. an NFL game → NFL
 * Network US, DAZN Australia, DAZN Brasil, ESPN 2 in a dozen countries). The matcher strips the
 * country tail so it can match "NFL Network US" to a user's "NFL Network" — but that same strip lets
 * one regional station ("DAZN Australia") match EVERY same-brand channel the user owns, each stamped
 * with the station's country. This gate keeps a brand-level listing match only when the user's channel
 * is region-neutral (no country hint) or shares the station's region; an exact whole-name match never
 * needs the gate. Regions are compared as coarse codes derived from country names and channel prefixes.
 */
internal object SportsBroadcastRegionPolicy {

    /** Full country names (as TheSportsDB spells them) → a coarse region code. */
    private val COUNTRY_TO_CODE: Map<String, String> = mapOf(
        "united states" to "us", "usa" to "us", "us" to "us", "america" to "us",
        "united kingdom" to "uk", "uk" to "uk", "great britain" to "uk", "england" to "uk", "britain" to "uk",
        "the netherlands" to "nl", "netherlands" to "nl", "holland" to "nl",
        "australia" to "au", "new zealand" to "nz", "brazil" to "br", "brasil" to "br",
        "spain" to "es", "germany" to "de", "france" to "fr", "italy" to "it", "portugal" to "pt",
        "belgium" to "be", "poland" to "pl", "turkey" to "tr", "greece" to "gr", "serbia" to "rs",
        "croatia" to "hr", "slovenia" to "si", "czechia" to "cz", "czech republic" to "cz",
        "romania" to "ro", "bulgaria" to "bg", "denmark" to "dk", "sweden" to "se", "norway" to "no",
        "finland" to "fi", "ireland" to "ie", "iceland" to "is", "canada" to "ca", "mexico" to "mx",
        "india" to "in", "pakistan" to "pk", "argentina" to "ar", "south africa" to "za", "egypt" to "eg",
        "saudi arabia" to "sa", "united arab emirates" to "ae", "qatar" to "qa", "kuwait" to "kw",
        "japan" to "jp", "china" to "cn", "south korea" to "kr", "philippines" to "ph", "malaysia" to "my",
        "singapore" to "sg", "thailand" to "th", "vietnam" to "vn", "hong kong" to "hk", "taiwan" to "tw",
        "austria" to "at", "switzerland" to "ch", "russia" to "ru", "hungary" to "hu", "slovakia" to "sk",
    )

    /** Short region tokens as they appear in channel-name prefixes/suffixes ("USA:", "|AU|", "…FR"). */
    private val TOKEN_TO_CODE: Map<String, String> = mapOf(
        "usa" to "us", "us" to "us", "uk" to "uk", "gb" to "uk", "eng" to "uk",
        "nl" to "nl", "ned" to "nl", "au" to "au", "aus" to "au", "nz" to "nz",
        "br" to "br", "bra" to "br", "es" to "es", "esp" to "es", "de" to "de", "ger" to "de",
        "fr" to "fr", "fra" to "fr", "it" to "it", "ita" to "it", "pt" to "pt", "por" to "pt",
        "be" to "be", "pl" to "pl", "pol" to "pl", "tr" to "tr", "tur" to "tr", "gr" to "gr", "gre" to "gr",
        "rs" to "rs", "srb" to "rs", "hr" to "hr", "cro" to "hr", "si" to "si", "slo" to "si",
        "cz" to "cz", "cze" to "cz", "ro" to "ro", "rou" to "ro", "bg" to "bg", "bul" to "bg",
        "dk" to "dk", "den" to "dk", "se" to "se", "swe" to "se", "no" to "no", "nor" to "no",
        "fi" to "fi", "fin" to "fi", "ie" to "ie", "ire" to "ie", "is" to "is", "isl" to "is",
        "ca" to "ca", "can" to "ca", "mx" to "mx", "mex" to "mx", "in" to "in", "ind" to "in",
        "pk" to "pk", "pak" to "pk", "ar" to "ar", "arg" to "ar", "za" to "za", "rsa" to "za",
        "sa" to "sa", "ksa" to "sa", "ae" to "ae", "uae" to "ae", "qa" to "qa", "kw" to "kw",
        "jp" to "jp", "cn" to "cn", "kr" to "kr", "ph" to "ph", "my" to "my", "sg" to "sg",
        "th" to "th", "vn" to "vn", "hk" to "hk", "tw" to "tw", "at" to "at", "ch" to "ch",
        "ru" to "ru", "hu" to "hu", "sk" to "sk",
    )

    /** The region a station broadcasts to, from its country field. */
    fun regionOfCountry(country: String?): String? {
        val key = country?.lowercase()?.trim()?.removePrefix("the ")?.trim() ?: return null
        return COUNTRY_TO_CODE[key]
    }

    /** The region a channel name declares, via a leading or trailing token ("USA: …", "DAZN Australia"). */
    fun regionOfChannel(name: String?): String? {
        val toks = name?.lowercase()?.split(Regex("[^a-z]+"))?.filter { it.isNotEmpty() } ?: return null
        if (toks.isEmpty()) return null
        tokenRegion(toks.first())?.let { return it }
        return tokenRegion(toks.last())
    }

    private fun tokenRegion(tok: String): String? = TOKEN_TO_CODE[tok] ?: COUNTRY_TO_CODE[tok]

    /**
     * Whether a brand-level (country-tail-dropped) listing match to [channelName] is region-safe for a
     * station broadcasting to [stationRegion]. Permissive when either region is unknown; rejects only a
     * confident cross-region pairing (station=NL, channel=US).
     */
    fun listingAccepts(stationRegion: String?, channelName: String?): Boolean {
        if (stationRegion == null) return true
        val channelRegion = regionOfChannel(channelName) ?: return true
        return channelRegion == stationRegion
    }

    /**
     * A home-country broadcaster (or one whose region we can't tell) genuinely airs the fixture, so it
     * confirms it; an out-of-country listing (ESPN NL for a US game) only proves the channel carries the
     * competition somewhere, so it drops to LEAGUE — the sheet shows it under "Carries <league>".
     */
    fun listingConfidence(stationRegion: String?, homeRegion: String?): MatchConfidence =
        if (homeRegion != null && stationRegion != null && stationRegion != homeRegion) MatchConfidence.LEAGUE
        else MatchConfidence.CONFIRMED

    /** Rank nudge so the home broadcaster leads and out-of-country listings sink below the confirmed tier. */
    fun listingScoreDelta(stationRegion: String?, homeRegion: String?): Int = when {
        homeRegion == null || stationRegion == null -> 0
        stationRegion == homeRegion -> HOME_LISTING_BOOST
        else -> OUT_OF_COUNTRY_LISTING_PENALTY
    }

    /**
     * A rank nudge for a confirmed channel/feed whose own name is in the fixture's home country, so a
     * home-country event feed ("US … Bills vs Steelers") leads ahead of an out-of-country feed of the
     * same game. No penalty for out-of-country — it still names both teams, so it stays confirmed.
     */
    fun homeRegionBoost(channelName: String?, homeRegion: String?): Int =
        if (homeRegion != null && regionOfChannel(channelName) == homeRegion) HOME_LISTING_BOOST else 0

    private const val HOME_LISTING_BOOST = 10
    private const val OUT_OF_COUNTRY_LISTING_PENALTY = -30
}

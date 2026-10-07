package com.nuvio.tv.core.radar

import java.text.Normalizer
import kotlin.math.abs

/** One coherent source record. Ranking must never graft confidence or a programme from another record. */
data class SportsMatchEvidence(
    val source: SportsEvidenceSource,
    val confidence: MatchConfidence,
    val strength: Int,
    val reasons: List<String>,
)

enum class SportsEvidenceSource { EVENT_NAME, PROGRAMME, LISTING }

/** Pure, source-independent matching. Preparation is once per fixture; there is no IO or clock read. */
internal object SportsEventMatchEngine {
    private val generic = setOf("fc", "cf", "sc", "afc", "club", "team", "city", "united", "town", "real", "cricket", "football", "sports", "sport", "grand", "prix", "practice", "qualifying", "race")
    private val sportWords = setOf("cricket", "football", "soccer", "basketball", "hockey", "rugby", "fc", "cf", "sc", "afc", "club")
    private val leagues = setOf("nba", "wnba", "nfl", "nhl", "mlb", "nrl", "afl", "ipl", "bbl", "cfl")
    private val nonLive = Regex("\\b(replay|highlights?|preview|press conference|weigh in|weigh ins|ended|end|24 7|resumen|repeticion|reprise|melhores momentos|conferencia de prensa)\\b")
    private val variant = Regex("\\b(women|womens|ladies|reserves|u(?:1[6-9]|2[013]))\\b")
    private val card = Regex("\\bufc (?:fight night )?\\d+\\b")
    private val isoDate = Regex("\\b(20\\d{2}-\\d{2}-\\d{2})\\b")
    private val numericDate = Regex("\\b(\\d{1,2})[./-](\\d{1,2})(?:[./-](20\\d{2}))?\\b")
    private val wordDate = Regex("\\b(?:mon|tue|wed|thu|fri|sat|sun)?\\w*\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\b|\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\w*\\s+(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val segments = Regex("[|;]|(?<!\\d):(?!\\d)")
    private val sessions = listOf("sprint qualifying", "sprint shootout", "practice 1", "practice 2", "practice 3", "qualifying", "sprint", "warm up")
    private val teamCities = listOf("new york", "new orleans", "oklahoma city", "golden state", "los angeles", "san antonio", "san jose", "tampa bay", "st louis", "salt lake", "vegas", "washington", "pittsburgh", "minnesota", "indiana", "phoenix", "chicago", "memphis", "orlando", "milwaukee", "portland", "boston", "cleveland", "miami", "dallas", "denver", "detroit", "atlanta", "brooklyn", "houston", "toronto", "philadelphia", "sacramento", "utah", "winnipeg", "colorado", "anaheim", "edmonton", "ottawa", "montreal", "nashville", "vancouver", "seattle", "calgary", "florida", "carolina", "buffalo", "baltimore", "cincinnati", "arizona", "jacksonville", "tennessee", "green bay", "new england", "kansas city", "las vegas", "san francisco", "sydney", "newcastle", "brisbane", "melbourne", "canberra", "parramatta", "penrith", "cronulla", "manly warringah", "south sydney", "north queensland", "gold coast", "st george illawarra", "wests")

    fun normalize(raw: String?): String {
        val input = raw.orEmpty()
        val compatibility = if (input.all { it.code < 128 }) input else input.map { c ->
            if (c in '\uFF01'..'\uFF5E') (c.code - 0xFEE0).toChar() else c
        }.joinToString("")
        val folded = if (compatibility.all { it.code < 128 }) compatibility else Normalizer.normalize(compatibility, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return buildString {
            for (c in folded.lowercase()) {
                when {
                    c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF' -> Unit
                    c.isLetterOrDigit() -> append(c)
                    isNotEmpty() && last() != ' ' -> append(' ')
                }
            }
        }.trim()

    }

    private fun has(text: String, phrase: String): Boolean = phrase.isNotBlank() && " $text ".contains(" $phrase ")
    private fun session(text: String): String = sessions.firstOrNull { has(text, it) } ?: "race"

    class Prepared internal constructor(val fixture: RadarFixture, keywords: List<String>) {
        val sport = normalize(fixture.sport)
        val event = normalize(fixture.event)
        val keywords = (keywords + listOfNotNull(fixture.league)).map(::normalize).filter { it.isNotBlank() }.distinct()
        val leagueMarkers = (this.keywords.flatMap { it.split(' ') } + when (sport) {
            "basketball" -> listOf(if (has(event, "wnba")) "wnba" else "nba")
            "american football" -> listOf("nfl", "cfl")
            "ice hockey" -> listOf("nhl")
            "rugby" -> listOf("nrl")
            "cricket" -> listOf("ipl", "bbl")
            else -> emptyList()
        }).toSet()
        val parsedPair = if (fixture.home.isNullOrBlank() || fixture.away.isNullOrBlank())
            Regex("\\s+vs?\\.?\\s+", RegexOption.IGNORE_CASE).split(fixture.event.orEmpty()).takeIf { it.size == 2 } else null
        val weakPair = parsedPair != null
        val homeAliases = aliases(fixture.home, fixture.homeAliases, sport).ifEmpty { parsedPair?.get(0)?.let { listOf(personSuffix(it)) } ?: emptyList() }
        val awayAliases = aliases(fixture.away, fixture.awayAliases, sport).ifEmpty { parsedPair?.get(1)?.let { listOf(personSuffix(it)) } ?: emptyList() }
        val searchTokens = (homeAliases + awayAliases + listOf(event)).flatMap { it.split(' ') }
            .filter { it.length > 3 && it !in generic && it !in sportWords && it !in setOf("vs", "night", "fight", "open", "championships") }.distinct().take(8)
    }

    private fun personSuffix(raw: String): String {
        val tokens = normalize(raw).split(' ')
        return if (tokens.size >= 2 && tokens[tokens.lastIndex - 1] in setOf("de", "del", "van", "von")) tokens.takeLast(2).joinToString(" ") else tokens.lastOrNull().orEmpty()
    }

    private fun aliases(name: String?, supplied: List<String>, sport: String): List<String> {
        val full = normalize(name)
        if (full.isBlank()) return emptyList()
        val clean = full.split(' ').filterNot { it in sportWords }.joinToString(" ")
        if (clean.split(' ').all { it in generic }) return emptyList()
        return buildList {
            add(full); if (clean.isNotBlank()) add(clean)
            addAll(supplied.map(::normalize).filter { a -> a.split(' ').any { it !in generic && it !in sportWords } })
            // City/surname aliases are scoped to the fixture's sport AND both opponents.
            if (sport in setOf("basketball", "ice hockey", "american football", "rugby")) {
                val city = teamCities.sortedByDescending { it.length }.firstOrNull { full.startsWith("$it ") }
                if (city != null) {
                    val nickname = full.removePrefix("$city ")
                    if (nickname.split(' ').any { it !in generic }) add(nickname)
                    if (sport == "ice hockey") add(city)
                }
            }
        }.distinct()
    }

    fun prepare(fixture: RadarFixture, keywords: List<String>): Prepared = Prepared(fixture, keywords)

    fun name(p: Prepared, raw: String): SportsMatchEvidence = evaluate(p, raw, SportsEvidenceSource.EVENT_NAME)

    fun programme(p: Prepared, title: String, description: String, from: Long, to: Long): SportsMatchEvidence {
        val kickoff = p.fixture.startEpochMs ?: return reject(SportsEvidenceSource.PROGRAMME, "unknown_kickoff")
        if (from <= 0 || to <= from || kickoff < from - 30 * 60_000L || kickoff >= to || abs(kickoff - from) > 2 * 60 * 60_000L || to - from > 12 * 60 * 60_000L)
            return reject(SportsEvidenceSource.PROGRAMME, "programme_time_mismatch")
        val primary = evaluate(p, title, SportsEvidenceSource.PROGRAMME)
        // A description can mention several games: do not promote prose-only co-occurrence.
        val descriptionOnly = primary.strength < 60
        val found = if (!descriptionOnly) primary else {
            val combined = evaluate(p, "$title | $description", SportsEvidenceSource.PROGRAMME)
            if (combined.strength >= 60 && p.keywords.any { has(normalize(title), it) }) combined else primary
        }
        if (found.strength < 60) return found
        if (p.weakPair || descriptionOnly || "card_segment_only" in found.reasons) return found.copy(confidence = MatchConfidence.POSSIBLE, reasons = found.reasons + "unresolved_entity_or_segment")
        return found.copy(confidence = MatchConfidence.CONFIRMED, strength = 100, reasons = found.reasons + "programme_time_aligned")
    }

    private fun evaluate(p: Prepared, raw: String, source: SportsEvidenceSource): SportsMatchEvidence {
        val text = normalize(raw)
        if (nonLive.containsMatchIn(text)) return reject(source, "non_live_or_ended")
        val words = text.split(' ').toSet()
        if ((words intersect leagues).any { it !in p.leagueMarkers }) return reject(source, "competing_league")
        if (variant.findAll(text).any { !has(p.event + " " + normalize(p.fixture.home) + " " + normalize(p.fixture.away), it.value) }) return reject(source, "different_team_variant")
        val ownCard = card.find(p.event)?.value
        val otherCard = card.find(text)?.value
        if (ownCard != null && otherCard != null && ownCard != otherCard) return reject(source, "different_combat_card")
        val motorsport = p.sport == "motorsport" || has(p.event, "grand prix")
        if (motorsport) {
            val series = mapOf("f1" to listOf("f1", "formula 1", "formula one"), "motogp" to listOf("motogp"), "nascar" to listOf("nascar"), "indycar" to listOf("indycar"), "formula e" to listOf("formula e"))
            val own = series.filterValues { names -> names.any { has(p.event + " " + p.keywords.joinToString(" "), it) } }.keys
            val other = series.filterValues { names -> names.any { has(text, it) } }.keys
            if (own.isNotEmpty() && other.isNotEmpty() && (own intersect other).isEmpty()) return reject(source, "different_motorsport_series")
        }
        val venue = p.event.substringBefore(" grand prix")
        val race = motorsport && has(text, "$venue grand prix")
        if (race && session(text) != session(p.event)) return reject(source, "different_motorsport_session")
        // Most catalog rows contain neither opponent. Avoid segment parsing/regex work
        // until both participant identities appear in the normalized full name.
        val pair = p.homeAliases.any { has(text, it) } && p.awayAliases.any { has(text, it) } &&
            segments.split(raw).any { segment ->
                val s = normalize(segment.replace(Regex("\\b[A-Z]\\.(?=\\s)"), ""))
                val connectors = Regex("\\b(vs?|at)\\b").findAll(s).count()
                p.homeAliases.any { h -> p.awayAliases.any { a ->
                    if (h == a || !has(s, h) || !has(s, a)) false else {
                        val overlapping = has(h, a) || has(a, h)
                        if (!overlapping && connectors <= 1) true else {
                            val relations = Regex("\\b(?:${Regex.escape(h)} (?:vs?|at) (?:[a-z]{1,3} )?${Regex.escape(a)}|${Regex.escape(a)} (?:vs?|at) (?:[a-z]{1,3} )?${Regex.escape(h)})\\b").findAll(s).count()
                            relations > 0 && relations == connectors
                        }
                    }
                } }
            }
        val cardHit = ownCard != null && ownCard == otherCard
        if (pair || race || cardHit) {
            val temporal = dateEvidence(p, raw)
            if (temporal == "different_event_date") return reject(source, temporal)
            val prelim = cardHit && Regex("\\b(prelims?|preliminares|preliminary)\\b").containsMatchIn(text)
            return SportsMatchEvidence(source, MatchConfidence.POSSIBLE, 60, listOf(
                if (pair) "participant_pair" else if (race) "event_and_session" else "exact_card",
                temporal, if (prelim) "card_segment_only" else "airing_unverified",
            ))
        }
        if (p.keywords.any { has(text, it) }) return SportsMatchEvidence(source, MatchConfidence.LEAGUE, 20, listOf("competition_only"))
        return reject(source, "insufficient_identity")
    }

    private fun dateEvidence(p: Prepared, raw: String): String {
        val expected = p.fixture.ts?.take(10)?.let(::validDate) ?: return "unknown_fixture_date"
        val iso = isoDate.find(raw)?.groupValues?.get(1)?.let(::validDate)
        if (iso != null) return if (abs(iso - expected) > 86_400_000L) "different_event_date" else if (iso == expected) "matching_calendar_date" else "timezone_boundary_unresolved"
        // Explicit day/month names have no date-order ambiguity. Infer a nearby year only
        // for checking contradictions, never for claiming a scheduled occurrence.
        val named = wordDate.find(raw)
        if (named != null) {
            val day = (named.groupValues[1].ifEmpty { named.groupValues[4] }).toIntOrNull()
            val month = months.indexOf(named.groupValues[2].ifEmpty { named.groupValues[3] }.lowercase()) + 1
            val year = p.fixture.ts?.take(4)?.toIntOrNull()
            if (day != null && year != null) {
                val nearest = (year - 1..year + 1).mapNotNull { y -> validDate("$y-${month.toString().padStart(2,'0')}-${day.toString().padStart(2,'0')}") }.minByOrNull { abs(it - expected) }
                if (nearest != null && abs(nearest - expected) > 86_400_000L) return "different_event_date"
            }
            return "yearless_date"
        }
        val n = numericDate.find(raw)
        if (n != null) {
            val a=n.groupValues[1].toInt(); val b=n.groupValues[2].toInt(); val year=n.groupValues[3].toIntOrNull()
            if (year != null && (a > 12 || b > 12)) {
                val month=if (a>12) b else a; val day=if(a>12) a else b
                val date=validDate("$year-${month.toString().padStart(2,'0')}-${day.toString().padStart(2,'0')}")
                if (date != null && abs(date-expected)>86_400_000L) return "different_event_date"
            }
            return "ambiguous_date"
        }
        return "no_event_date"
    }

    private fun validDate(raw: String): Long? {
        val parts=raw.split('-').map { it.toIntOrNull() ?: return null }
        if(parts.size != 3 || parts[1] !in 1..12) return null
        val y=parts[0];val m=parts[1];val d=parts[2]
        val max=when(m){2->if(y%4==0 && (y%100!=0 || y%400==0))29 else 28;4,6,9,11->30;else->31}
        if(d !in 1..max)return null
        return radarDateToEpochMs(raw)
    }

    private fun reject(source: SportsEvidenceSource, reason: String) = SportsMatchEvidence(source, MatchConfidence.LEAGUE, 0, listOf(reason))

    /** Identity tiers outrank preference; a preference never admits a rejected candidate. */
    fun rank(confidence: MatchConfidence): Int = when(confidence) { MatchConfidence.CONFIRMED -> 3; MatchConfidence.POSSIBLE -> 2; MatchConfidence.LEAGUE -> 1 }

    /** Preserve feed numbers and delay offsets even when an upstream mapper drops them. */
    fun compatibleStation(raw: String, station: String): Boolean {
        fun numbers(value: String) = Regex("\\b\\d+\\b").findAll(normalize(value)).map { it.value }.toList()
        fun delayed(value: String) = Regex("\\+\\s*1\\b").containsMatchIn(value)
        return numbers(raw)==numbers(station) && delayed(raw)==delayed(station)
    }
}

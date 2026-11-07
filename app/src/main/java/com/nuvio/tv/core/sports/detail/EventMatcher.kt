package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.JsonElement
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

data class EspnCandidate(
    val id: String,
    val homeName: String,
    val awayName: String,
    val startMs: Long,
    val name: String = "",
)

data class MatchQuery(
    val gameId: String,
    val homeName: String,
    val awayName: String,
    val startsAtMs: Long,
    val eventName: String? = null,
) {
    val rawId: String get() = gameId.substringAfter(':')
}

/**
 * Finds the ESPN event for a TheSportsDB game: manual override table first, then date window plus
 * team-name normalisation (accents, club suffixes, alias table). Both teams must match, so a one-team
 * coincidence can't link the wrong game. Event-style games (F1, UFC) match on distinctive name tokens
 * over a wider window. Pure and unit-tested; the result is cached per game by the caller.
 */
object EventMatcher {
    private val suffixTokens = setOf("fc", "cf", "afc", "sc", "ac")
    private val genericEventTokens = setOf("grand", "prix", "gp", "formula", "f1", "ufc", "fight", "night", "the", "of", "vs", "at", "fc")
    private const val EVENT_WINDOW_HOURS = 96

    private fun clean(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase().replace(Regex("[^a-z0-9\\- ]"), " ").split(' ').filter { it.isNotBlank() && it !in suffixTokens }.joinToString(" ")
    }

    fun normalize(name: String, aliases: Map<String, String>): String {
        val c = clean(name)
        val aliasKey = aliases.entries.firstOrNull { clean(it.key) == c }
        return aliasKey?.let { clean(it.value) } ?: c
    }

    private fun tokens(s: String) = s.split(' ', '-').filter { it.isNotBlank() }.toSet()

    private fun teamsMatch(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true
        val ta = tokens(a); val tb = tokens(b)
        return ta.all { it in tb } || tb.all { it in ta }
    }

    fun match(q: MatchQuery, candidates: List<EspnCandidate>, mapping: SportsMapping): String? {
        (mapping.eventOverrides[q.rawId] ?: mapping.eventOverrides[q.gameId])?.let { return it }
        if (q.homeName.isNotBlank() && q.awayName.isNotBlank()) {
            val window = mapping.match.dateWindowHours * 3_600_000L
            val qh = normalize(q.homeName, mapping.teamAliases)
            val qa = normalize(q.awayName, mapping.teamAliases)
            return candidates
                .filter { abs(it.startMs - q.startsAtMs) <= window }
                .filter { c ->
                    val ch = normalize(c.homeName, mapping.teamAliases); val ca = normalize(c.awayName, mapping.teamAliases)
                    (teamsMatch(qh, ch) && teamsMatch(qa, ca)) || (teamsMatch(qh, ca) && teamsMatch(qa, ch))
                }
                .minByOrNull { abs(it.startMs - q.startsAtMs) }?.id
        }
        val name = q.eventName?.takeIf { it.isNotBlank() } ?: return null
        val qt = distinctive(name)
        if (qt.isEmpty()) return null
        return candidates
            .filter { abs(it.startMs - q.startsAtMs) <= EVENT_WINDOW_HOURS * 3_600_000L }
            .filter { c ->
                val ct = distinctive(c.name)
                ct.isNotEmpty() && ct.intersect(qt).size.toDouble() / minOf(ct.size, qt.size) >= 0.6
            }
            .minByOrNull { abs(it.startMs - q.startsAtMs) }?.id
    }

    private fun distinctive(name: String) = tokens(clean(name)).filter { it !in genericEventTokens && !it.all(Char::isDigit) }.toSet()

    /** ESPN timestamps look like `2026-11-07T12:30Z` (no seconds) as well as full ISO. */
    fun parseEspnTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    fun candidatesFromScoreboard(scoreboard: JsonElement?, mapping: SportsMapping): List<EspnCandidate> {
        val r = JsonPathReader
        val eventPath = mapping.commonPath("event") ?: "events[*]"
        return r.read(scoreboard, eventPath).mapNotNull { ev ->
            val id = r.firstText(ev, mapping.commonPath("id") ?: "id") ?: return@mapNotNull null
            fun teamName(key: String): String? {
                val node = mapping.commonPath(key)?.let { r.first(ev, it) } ?: return null
                return mapping.commonTeamPath("name")?.let { r.firstText(node, it) }
            }
            val home = teamName("home") ?: r.firstText(ev, "competitions[0].competitors[0].athlete.displayName").orEmpty()
            val away = teamName("away") ?: r.firstText(ev, "competitions[0].competitors[1].athlete.displayName").orEmpty()
            EspnCandidate(id, home, away, parseEspnTime(r.firstText(ev, "date")) ?: return@mapNotNull null, r.firstText(ev, "name").orEmpty())
        }
    }
}

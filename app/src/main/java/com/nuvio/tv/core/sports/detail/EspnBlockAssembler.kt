package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The two ESPN documents a game can be assembled from: the scoreboard's event object and the summary. */
data class EspnDocs(val event: JsonElement?, val summary: JsonElement?)

/**
 * Mapping-driven assemblers: ESPN JSON in, contract blocks out. Every path lives in the mapping, so a
 * renamed ESPN field is fixed by editing data. Nothing here throws; a bad or missing path is an absent block.
 */
class EspnBlockAssembler(private val mapping: SportsMapping) {
    private val r = JsonPathReader

    // ---- header / state / live ------------------------------------------------------------

    fun header(profile: String, docs: EspnDocs): GameHeader? {
        val ev = docs.event ?: return null
        return try {
            when (profile) {
                "motorsport", "fighting" -> eventHeader(ev)
                "tennis" -> versusHeader(ev)
                else -> teamsHeader(ev)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun teamsHeader(ev: JsonElement): GameHeader.Teams? {
        val home = mapping.commonPath("home")?.let { r.first(ev, it) }
        val away = mapping.commonPath("away")?.let { r.first(ev, it) }
        val h = home?.let { side(it) } ?: return null
        val a = away?.let { side(it) } ?: return null
        return GameHeader.Teams(h, a)
    }

    private fun side(node: JsonElement): Side? {
        val name = mapping.commonTeamPath("name")?.let { r.firstText(node, it) }?.takeIf { it.isNotBlank() } ?: return null
        fun t(key: String) = mapping.commonTeamPath(key)?.let { r.firstText(node, it) }?.takeIf { it.isNotBlank() }
        return Side(
            name = name, short = t("short"), badgeUrl = t("badge"), score = t("score"), record = t("record"),
            form = t("form")?.map { it.toString() },
        )
    }

    private fun eventHeader(ev: JsonElement): GameHeader.Event? {
        val name = r.firstText(ev, "name")?.takeIf { it.isNotBlank() } ?: return null
        val live = r.read(ev, "competitions[*]").firstOrNull { r.firstText(it, "status.type.state") == "in" }
        return GameHeader.Event(
            name = name,
            session = live?.let { r.firstText(it, "type.abbreviation") },
            venue = r.firstText(ev, "competitions[0].venue.fullName") ?: r.firstText(ev, "venue.fullName"),
        )
    }

    private fun versusHeader(ev: JsonElement): GameHeader? {
        val comps = r.read(ev, "competitions[0].competitors[*]")
        if (comps.size < 2) return eventHeader(ev)
        fun s(n: JsonElement) = r.firstText(n, "athlete.displayName")?.let { Side(it, short = r.firstText(n, "athlete.shortName"), score = r.firstText(n, "score")) }
        return GameHeader.Versus(s(comps[0]) ?: return eventHeader(ev), s(comps[1]) ?: return eventHeader(ev))
    }

    fun gameState(docs: EspnDocs): GameState? =
        mapping.commonPath("state")?.let { r.firstText(docs.event, it) }?.let { mapping.stateFor(it) }

    fun live(docs: EspnDocs, profile: String? = null): LiveInfo? {
        val ev = docs.event ?: return null
        val isLive = gameState(docs) == GameState.LIVE
        val clock = if (isLive) mapping.commonPath("clock")?.let { r.firstText(ev, it) }?.takeIf { it.isNotBlank() } else null
        val period = if (isLive) mapping.commonPath("period")?.let { r.firstText(ev, it) }?.toIntOrNull() else null
        val label = period?.let { p -> mapping.profile(profile)?.periodLabel?.takeIf { it == "Q" || it == "P" }?.let { ordinal(p) } }
        val detail = mapping.commonPath("detail")?.let { r.firstText(ev, it) }?.takeIf { it.isNotBlank() }
        return if (clock == null && label == null && detail == null) null else LiveInfo(clock, label, detail)
    }

    private fun ordinal(n: Int): String = when {
        n in 11..13 -> "${n}th"
        n % 10 == 1 -> "${n}st"; n % 10 == 2 -> "${n}nd"; n % 10 == 3 -> "${n}rd"
        else -> "${n}th"
    }

    // ---- blocks ---------------------------------------------------------------------------

    fun all(profile: String, docs: EspnDocs): Map<BlockType, GameBlock> =
        BlockType.assemblable.mapNotNull { t -> block(t, profile, docs)?.let { t to it } }.toMap()

    fun block(type: BlockType, profile: String, docs: EspnDocs): GameBlock? {
        val p = mapping.profile(profile) ?: return null
        if (type !in p.blockTypes) return null
        return try {
            when (type) {
                BlockType.LINESCORE -> linescore(p, docs)
                BlockType.STATS -> stats(p, docs)
                BlockType.LEADERS -> leaders(p, docs)
                BlockType.TIMELINE -> timeline(p, docs)
                BlockType.WIN_PROBABILITY -> winProbability(p, docs)
                BlockType.SITUATION -> situation(p, docs)
                BlockType.FORM -> form(p, docs)
                BlockType.RESULTS -> results(profile, p, docs)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun root(spec: JsonObject, docs: EspnDocs): JsonElement? =
        when (str(spec["from"])) { "summary" -> docs.summary; else -> docs.event }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun sideKey(s: String?): SideKey? = when (s) { "home" -> SideKey.HOME; "away" -> SideKey.AWAY; else -> null }

    /** Splits at the first `[*]`: the row nodes (with their home/away) and the path under each node. */
    private data class Group(val homeAway: String?, val node: JsonElement, val rest: String)

    private fun groups(root: JsonElement?, path: String): List<Group> {
        val i = path.indexOf("[*]")
        val gs = if (i < 0) listOf(Group(null, root ?: return emptyList(), path)) else {
            val prefix = path.substring(0, i + 3)
            val rest = path.substring(i + 3).removePrefix(".")
            r.readTracked(root, prefix).map { Group(it.homeAway, it.value, rest) }
        }
        return gs.sortedBy { if (it.homeAway == "home") 0 else 1 }
    }

    private fun linescore(p: SportProfile, docs: EspnDocs): GameBlock.Linescore? {
        val spec = p.spec("linescore") ?: return null
        val path = str(spec["path"]) ?: return null
        val extra = (spec["extra"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val gs = groups(root(spec, docs), path)
        val periodRows = gs.map { g -> r.readTracked(g.node, g.rest).mapNotNull { r.text(it.value) } }
        val width = periodRows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return null
        val label = p.periodLabel.orEmpty()
        val regulation = when (label) { "Q" -> 4; "P" -> 3; else -> Int.MAX_VALUE }
        val cols = (0 until width).map { i ->
            when {
                i < regulation -> "$label${i + 1}"
                i == regulation -> "OT"
                else -> "${i - regulation + 1}OT"
            }
        }.toMutableList()
        val rows = periodRows.mapIndexed { idx, vals ->
            val row = vals.toMutableList()
            while (row.size < width) row += ""
            if (extra.isNotEmpty()) {
                row += r.firstText(gs[idx].node, "score").orEmpty()
                extra.forEach { k -> row += r.firstText(gs[idx].node, k).orEmpty() }
            }
            row
        }
        if (extra.isNotEmpty()) { cols += "R"; extra.forEach { cols += it.first().uppercase() } }
        return GameBlock.Linescore(SourceId.ESPN, cols, rows)
    }

    private fun stats(p: SportProfile, docs: EspnDocs): GameBlock.Stats? {
        val spec = p.spec("stats") ?: return null
        val path = str(spec["path"]) ?: return null
        val labelKey = str(spec["label"]) ?: "label"
        val valueKey = str(spec["value"]) ?: "displayValue"
        val allow = (spec["allow"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val rename = (spec["rename"] as? JsonObject).orEmpty()
        val units = (spec["units"] as? JsonObject).orEmpty()
        val gs = groups(root(spec, docs), path)
        if (gs.isEmpty()) return null
        fun table(g: Group): Map<String, String> {
            val m = LinkedHashMap<String, String>()
            r.readTracked(g.node, g.rest).forEach { n ->
                val label = r.firstText(n.value, labelKey)
                val value = r.firstText(n.value, valueKey)
                if (label != null && value != null) m.putIfAbsent(label, value)
            }
            return m
        }
        val tables = gs.map { table(it) }
        val home = tables.getOrNull(0).orEmpty()
        val away = tables.getOrNull(1).orEmpty()
        val order = if (allow.isNotEmpty()) allow else (home.keys + away.keys).distinct()
        val rows = order.mapNotNull { l ->
            val h = home[l]; val a = away[l]
            if (h == null && a == null) null
            else StatRow(str(rename[l]) ?: l, h ?: "-", a ?: "-", str(units[l]))
        }
        return rows.takeIf { it.isNotEmpty() }?.let { GameBlock.Stats(SourceId.ESPN, it) }
    }

    private fun competitorIds(docs: EspnDocs): Pair<String?, String?> {
        val ev = docs.event ?: return null to null
        fun id(key: String) = mapping.commonPath(key)?.let { r.firstText(ev, "$it.id") }
        return id("home") to id("away")
    }

    private fun sideById(id: String?, ids: Pair<String?, String?>): SideKey? = when {
        id == null -> null
        id == ids.first -> SideKey.HOME
        id == ids.second -> SideKey.AWAY
        else -> null
    }

    private fun leaders(p: SportProfile, docs: EspnDocs): GameBlock.Leaders? {
        val spec = p.spec("leaders") ?: return null
        val path = str(spec["path"]) ?: return null
        val ids = competitorIds(docs)
        val items = r.readTracked(root(spec, docs), path).mapNotNull { m ->
            val athlete = str(spec["athlete"])?.let { r.firstText(m.value, it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = str(spec["value"])?.let { r.firstText(m.value, it) }.orEmpty()
            val category = str(spec["category"])?.let { r.firstText(m.value, it) }.orEmpty()
            val side = sideKey(m.homeAway)
                ?: sideById(r.firstText(m.value, "leaders[0].athlete.team.id") ?: r.firstText(m.value, "team.id"), ids)
            LeaderItem(category, athlete, value, side)
        }
        return items.takeIf { it.isNotEmpty() }?.let { GameBlock.Leaders(SourceId.ESPN, it) }
    }

    private fun kindOf(type: String?, scoring: Boolean): TimelineKind? {
        val t = type?.lowercase().orEmpty()
        return when {
            "goal" in t -> TimelineKind.GOAL
            "card" in t -> TimelineKind.CARD
            "substitution" in t -> TimelineKind.SUB
            "penalty" in t -> TimelineKind.PENALTY
            scoring -> TimelineKind.SCORE
            else -> null
        }
    }

    private fun timeline(p: SportProfile, docs: EspnDocs): GameBlock.Timeline? {
        val spec = p.spec("timeline") ?: return null
        val path = str(spec["path"]) ?: return null
        val ids = competitorIds(docs)
        var items = r.readTracked(root(spec, docs), path).mapNotNull { m ->
            val text = str(spec["text"])?.let { r.firstText(m.value, it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            TimelineItem(
                time = str(spec["time"])?.let { r.firstText(m.value, it) }.orEmpty(),
                period = str(spec["period"])?.let { r.firstText(m.value, it) },
                side = sideKey(m.homeAway) ?: sideById(r.firstText(m.value, "team.id"), ids),
                text = text,
                kind = kindOf(str(spec["type"])?.let { r.firstText(m.value, it) }, r.firstText(m.value, "scoringPlay") == "true" || path.contains("scoring", true)),
            )
        }
        val last = (spec["last"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (last != null) items = items.takeLast(last)
        return items.takeIf { it.isNotEmpty() }?.let { GameBlock.Timeline(SourceId.ESPN, it) }
    }

    private fun winProbability(p: SportProfile, docs: EspnDocs): GameBlock.WinProbability? {
        val spec = p.spec("winProbability") ?: return null
        val v = str(spec["path"])?.let { r.firstText(root(spec, docs), it) }?.toDoubleOrNull() ?: return null
        val pct = if (v in 0.0..1.0) v * 100.0 else v
        return if (pct in 0.0..100.0) GameBlock.WinProbability(SourceId.ESPN, pct) else null
    }

    private fun situation(p: SportProfile, docs: EspnDocs): GameBlock.Situation? {
        val spec = p.spec("situation") ?: return null
        if (gameState(docs) != GameState.LIVE) return null
        val node = str(spec["path"])?.let { r.first(root(spec, docs), it) } ?: return null
        if (node is JsonPrimitive) return r.text(node)?.takeIf { it.isNotBlank() }?.let { GameBlock.Situation(SourceId.ESPN, it) }
        val text = listOf("shortDownDistanceText", "downDistanceText", "text", "summary", "displayValue")
            .firstNotNullOfOrNull { k -> r.firstText(node, k)?.takeIf { it.isNotBlank() } }
        if (text != null) return GameBlock.Situation(SourceId.ESPN, text)
        return baseballSituation(node)
    }

    private fun baseballSituation(n: JsonElement): GameBlock.Situation? {
        val on = listOf("onFirst" to "1st", "onSecond" to "2nd", "onThird" to "3rd")
        if (on.none { r.first(n, it.first) != null } && r.first(n, "balls") == null) return null
        val bases = on.filter { r.firstText(n, it.first) == "true" }.map { it.second }
        val runners = if (bases.isEmpty()) "Bases empty" else "Runners on ${bases.joinToString(", ")}"
        val balls = r.firstText(n, "balls"); val strikes = r.firstText(n, "strikes"); val outs = r.firstText(n, "outs")
        val text = buildList {
            add(runners)
            if (balls != null && strikes != null) add("$balls-$strikes")
            if (outs != null) add("$outs out")
        }.joinToString(" · ")
        return GameBlock.Situation(SourceId.ESPN, text, buildMap { balls?.let { put("balls", it) }; strikes?.let { put("strikes", it) }; outs?.let { put("outs", it) } })
    }

    private fun form(p: SportProfile, docs: EspnDocs): GameBlock.Form? {
        val spec = p.spec("form") ?: return null
        val ms = r.readTracked(root(spec, docs), str(spec["path"]) ?: return null)
        fun chars(side: String) = ms.firstOrNull { it.homeAway == side }?.let { r.text(it.value) }?.map { it.toString() }.orEmpty()
        val home = chars("home"); val away = chars("away")
        return if (home.isEmpty() && away.isEmpty()) null else GameBlock.Form(SourceId.ESPN, home, away)
    }

    private fun results(profile: String, p: SportProfile, docs: EspnDocs): GameBlock.Results? {
        val spec = p.spec("results") ?: return null
        val comps = r.read(root(spec, docs), str(spec["path"]) ?: return null)
        val sessionPath = str(spec["session"]) ?: "type.abbreviation"
        val statePath = str(spec["state"]) ?: "status.type.state"
        val rows = comps.mapNotNull { c ->
            val names = r.read(c, "competitors[*].athlete.displayName").mapNotNull { r.text(it) }
            val who = when { names.isEmpty() -> ""; names.size <= 2 -> names.joinToString(" vs "); else -> names.take(3).joinToString(", ") }
            val session = r.firstText(c, sessionPath).orEmpty()
            val status = r.firstText(c, "status.type.shortDetail") ?: r.firstText(c, statePath).orEmpty()
            if (session.isEmpty() && who.isEmpty()) null else listOf(session, who, status)
        }
        if (rows.isEmpty()) return null
        val cols = if (profile == "fighting") listOf("Bout", "Fighters", "Status") else listOf("Session", "Top", "Status")
        return GameBlock.Results(SourceId.ESPN, cols, rows)
    }
}

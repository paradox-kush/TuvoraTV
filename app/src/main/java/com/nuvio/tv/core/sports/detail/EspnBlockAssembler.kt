package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.nuvio.tv.core.sports.detail.screen.BasketballRunPolicy
import com.nuvio.tv.core.sports.detail.screen.HockeyPlay
import com.nuvio.tv.core.sports.detail.screen.HockeyStrength
import com.nuvio.tv.core.sports.detail.screen.PowerPlayClock
import com.nuvio.tv.core.sports.detail.screen.RunPlay
import kotlin.math.roundToInt

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
            color = hexColor(r.firstText(node, mapping.commonTeamPath("color") ?: "team.color")),
            altColor = hexColor(r.firstText(node, mapping.commonTeamPath("altColor") ?: "team.alternateColor")),
            rank = r.firstText(node, mapping.commonTeamPath("rank") ?: "curatedRank.current")?.toIntOrNull()?.takeIf { it in 1..98 }, // ESPN: 99 = unranked
        )
    }

    /** `team.color` as 6 lower-case hex digits, or null when it is anything else. */
    private fun hexColor(raw: String?): String? =
        raw?.trim()?.removePrefix("#")?.takeIf { it.length == 6 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }?.lowercase()

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
                BlockType.PLAYS -> plays(profile, p, docs)
                BlockType.STANDINGS_TABLE -> standingsTable(p, docs)
                BlockType.FIELD -> field(p, docs)
                BlockType.SOCCER_STRIP -> soccerStrip(p, docs)
                BlockType.LINEUP -> lineup(profile, docs)
                BlockType.BASKETBALL_STRIP -> basketballStrip(p, docs)
                BlockType.HOCKEY_STRIP -> hockeyStrip(p, docs)
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

    // ---- plays (Phase A: the full list for the Plays section) --------------------------------------------------------------

    private fun plays(profile: String, p: SportProfile, docs: EspnDocs): GameBlock.Plays? {
        val spec = p.spec("plays") ?: return null
        val root = root(spec, docs) ?: return null
        val path = str(spec["path"]) ?: return null
        val raw = r.readTracked(root, path) + (str(spec["pathAlso"])?.let { r.readTracked(root, it) }.orEmpty())
        val allow = (spec["allow"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val ids = competitorIds(docs)
        // Feeds list plays oldest first; the Plays section is newest first.
        val items = raw.mapNotNull { m -> playItem(spec, profile, docs, ids, m.value, allow) }.reversed()
        val cap = ((spec["extra"] as? JsonObject)?.get("last") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PLAYS_CAP
        return items.take(cap).takeIf { it.isNotEmpty() }?.let { GameBlock.Plays(SourceId.ESPN, it) }
    }

    private fun playItem(spec: JsonObject, profile: String, docs: EspnDocs, ids: Pair<String?, String?>, p: JsonElement, allow: List<String>): TimelineItem? {
        fun at(key: String) = str(spec[key])?.let { r.firstText(p, it) }
        val typeText = at("type")
        if (allow.isNotEmpty() && (typeText == null || allow.none { typeText.startsWith(it, ignoreCase = true) })) return null
        val text = (at("text") ?: r.firstText(p, "text"))?.takeIf { it.isNotBlank() }
            ?: r.firstText(p, "shortText")?.takeIf { it.isNotBlank() } ?: return null
        val scoring = r.firstText(p, "scoringPlay") == "true"
        val baseKind = if (r.firstText(p, "type.penaltyType") != null) TimelineKind.PENALTY else playKind(typeText, scoring)
        // A goal scored on the power play carries the feed's own `strength` ("Power Play"): tagged PPG, never from our clock.
        val kind = if (baseKind == TimelineKind.GOAL && at("strength")?.contains("power", ignoreCase = true) == true) TimelineKind.PPG else baseKind
        val home = at("homeScore"); val away = at("awayScore")
        return TimelineItem(
            time = at("time").orEmpty(),
            period = playPeriod(profile, at("period")),
            side = sideOfRef(docs, ids, at("team")),
            text = text, kind = kind,
            id = at("id"),
            wallclockMs = at("wallclock")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            score = if (home != null && away != null) "$home\u2013$away" else null,
        )
    }

    private fun playKind(typeText: String?, scoring: Boolean): TimelineKind? {
        val t = typeText?.lowercase().orEmpty()
        return when {
            "penalty" in t && "scored" in t -> TimelineKind.GOAL
            "corner" in t -> TimelineKind.CORNER
            "shot" in t -> TimelineKind.SHOT
            "offside" in t -> TimelineKind.OFFSIDE
            t.startsWith("var") -> TimelineKind.VAR
            else -> kindOf(typeText, scoring)
        }
    }

    /** Numeric periods read "Q1" / "P2" (periodLabel) or, for soccer, "1st Half"; a feed display value ("1st Quarter") passes through. */
    private fun playPeriod(profile: String, raw: String?): String? {
        val v = raw?.takeIf { it.isNotBlank() } ?: return null
        val n = v.toIntOrNull() ?: return v
        val label = mapping.profile(profile)?.periodLabel
        return when {
            profile == "soccer" -> when (n) { 1 -> "1st Half"; 2 -> "2nd Half"; 3 -> "Extra time 1"; 4 -> "Extra time 2"; else -> "Shootout" }
            !label.isNullOrBlank() -> "$label$n"
            else -> v
        }
    }

    /** HOME/AWAY for a team id or, when the feed gives only a name (soccer commentary), that name. */
    private fun sideOfRef(docs: EspnDocs, ids: Pair<String?, String?>, ref: String?): SideKey? {
        if (ref == null) return null
        sideById(ref, ids)?.let { return it }
        val ev = docs.event ?: return null
        fun name(key: String) = mapping.commonPath(key)?.let { r.first(ev, it) }?.let { n -> mapping.commonTeamPath("name")?.let { r.firstText(n, it) } }
        return when (ref) { name("home") -> SideKey.HOME; name("away") -> SideKey.AWAY; else -> null }
    }

    // ---- standings table ---------------------------------------------------------------------------------------------------

    private fun standingsTable(p: SportProfile, docs: EspnDocs): GameBlock.StandingsTable? {
        val spec = p.spec("standingsTable") ?: return null
        val root = root(spec, docs) ?: return null
        val allow = (spec["allow"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        val labels = ((spec["extra"] as? JsonObject)?.get("labels") as? JsonArray)?.mapNotNull { str(it) }.orEmpty()
        if (allow.isEmpty() || labels.size != allow.size) return null
        val ids = competitorIds(docs)
        val gameTeams = listOfNotNull(ids.first, ids.second)
        val entriesPath = str(spec["entries"]) ?: "standings.entries[*]"
        val groups = r.read(root, str(spec["path"]) ?: return null).mapNotNull { g ->
            val rows = r.read(g, entriesPath).mapNotNull { e ->
                val name = r.firstText(e, str(spec["team"]) ?: "team") ?: return@mapNotNull null
                val id = r.firstText(e, str(spec["teamId"]) ?: "id")
                StandingsTableRow(
                    teamId = id, name = name,
                    cells = allow.map { stat -> r.firstText(r.read(e, "stats[name=$stat]").firstOrNull(), "displayValue") ?: "\u2013" },
                    isGameTeam = id != null && id in gameTeams,
                )
            }
            if (rows.isEmpty()) null else StandingsGroup(groupName(str(spec["group"])?.let { r.firstText(g, it) }), labels, rows)
        }
        return groups.takeIf { it.isNotEmpty() }?.let { GameBlock.StandingsTable(SourceId.ESPN, it) }
    }

    /** "2026-27 Central Standings" -> "Central". */
    private fun groupName(raw: String?): String =
        raw.orEmpty().replace(Regex("""^\d{4}(-\d{2})?\s+"""), "").removeSuffix(" Standings").trim()

    // ---- Phase B: football field strip ---------------------------------------------------------------------------------------

    /**
     * Reads the live `situation` (single-event scoreboard; verified on live CFB captures 2026-11-14/15 to carry possession, downDistanceText,
     * yardLine from the HOME goal line, isRedZone and both timeouts). Without `possession` the last play's team stands in, but only while that
     * drive has no `result` (a finished drive may have changed hands); otherwise possession stays null: no arrow, no guess.
     */
    private fun field(p: SportProfile, docs: EspnDocs): GameBlock.Field? {
        val spec = p.spec("field") ?: return null
        val root = root(spec, docs) ?: return null
        val sit = r.first(root, str(spec["path"]) ?: return null) as? JsonObject ?: return null
        val yard = r.firstText(sit, "yardLine")?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
        val ids = competitorIds(docs)
        val possessionId = r.firstText(sit, "possession")
        val possession = if (possessionId != null) sideById(possessionId, ids)
        else if (r.firstText(sit, "lastPlay.drive.result").isNullOrBlank()) sideById(r.firstText(sit, "lastPlay.team.id"), ids) else null
        val drive = (str(spec["drive"])?.let { r.first(root, it) } ?: r.first(sit, "lastPlay.drive"))?.let { d ->
            FieldDrive(
                startYardLine = r.firstText(d, "start.yardLine")?.toIntOrNull()?.takeIf { it in 0..100 },
                summary = r.firstText(d, "description")?.takeIf { it.isNotBlank() },
            ).takeIf { it.startYardLine != null || it.summary != null }
        }
        return GameBlock.Field(
            source = SourceId.ESPN, yardLine = yard, possession = possession,
            down = r.firstText(sit, "down")?.toIntOrNull()?.takeIf { it > 0 },
            distance = r.firstText(sit, "distance")?.toIntOrNull()?.takeIf { it > 0 },
            line = (r.firstText(sit, "downDistanceText") ?: r.firstText(sit, "shortDownDistanceText"))?.takeIf { it.isNotBlank() },
            redZone = r.firstText(sit, "isRedZone") == "true",
            homeTimeouts = r.firstText(sit, "homeTimeouts")?.toIntOrNull(),
            awayTimeouts = r.firstText(sit, "awayTimeouts")?.toIntOrNull(),
            drive = drive,
        )
    }

    // ---- Phase B: soccer strip -----------------------------------------------------------------------------------------------

    private fun soccerStrip(p: SportProfile, docs: EspnDocs): GameBlock.SoccerStrip? {
        val spec = p.spec("soccerStrip") ?: return null
        val event = docs.event ?: return null
        val ids = competitorIds(docs)
        val details = r.read(event, str(spec["details"]) ?: return null)
        fun flag(d: JsonElement, key: String) = r.firstText(d, key) == "true"
        fun minuteValue(d: JsonElement) = r.firstText(d, "clock.value")?.toDoubleOrNull() ?: 0.0
        fun sideOf(d: JsonElement) = sideById(r.firstText(d, "team.id"), ids)
        fun who(d: JsonElement) = r.firstText(d, "athletesInvolved[0].shortName") ?: r.firstText(d, "athletesInvolved[0].displayName")
        fun surname(n: String) = n.substringAfter(". ", n).trim()

        val goals = details.filter { flag(it, "scoringPlay") }.sortedBy(::minuteValue)
        fun scorers(side: SideKey) = goals.filter { sideOf(it) == side }.map { g ->
            listOfNotNull(who(g)?.let(::surname), r.firstText(g, "clock.displayValue")).joinToString(" ") +
                (if (flag(g, "penaltyKick")) " (pen)" else "") + (if (flag(g, "ownGoal")) " (og)" else "")
        }
        fun cards(side: SideKey, key: String) = details.count { flag(it, key) && sideOf(it) == side }

        // Team statistics per competitor: possession (home share) and corners.
        val competitors = r.readTracked(event, str(spec["stats"])?.substringBefore("[*].statistics")?.let { "$it[*]" } ?: "competitions[0].competitors[*]")
        fun stat(side: SideKey, name: String): Double? {
            val key = if (side == SideKey.HOME) "home" else "away"
            val owner = competitors.firstOrNull { it.homeAway == key } ?: return null
            return r.firstText(r.read(owner.value, "statistics[name=$name]").firstOrNull(), "displayValue")?.toDoubleOrNull()
        }
        val homePoss = stat(SideKey.HOME, "possessionPct")?.roundToInt() ?: stat(SideKey.AWAY, "possessionPct")?.let { 100 - it.roundToInt() }

        val live = gameState(docs) == GameState.LIVE
        val lastEvent = if (!live) null else {
            val fromBoard = details.filter { flag(it, "scoringPlay") || flag(it, "yellowCard") || flag(it, "redCard") }
                .withIndex().maxWithOrNull(compareBy({ minuteValue(it.value) }, { it.index }))?.value
            val boardEvent = fromBoard?.let { d ->
                SoccerEvent(
                    kind = if (flag(d, "scoringPlay")) "goal" else if (flag(d, "redCard")) "red" else "yellow",
                    side = sideOf(d), minute = r.firstText(d, "clock.displayValue"),
                    text = r.firstText(d, "athletesInvolved[0].displayName") ?: r.firstText(d, "type.text").orEmpty(),
                )
            }
            val fresherCommentary = str(spec["commentary"])?.let { path ->
                r.read(docs.summary, path).withIndex().mapNotNull { (i, c) ->
                    val kind = commentaryKind(r.firstText(c, "play.type.text")) ?: return@mapNotNull null
                    val t = r.firstText(c, "time.value")?.toDoubleOrNull() ?: return@mapNotNull null
                    val text = r.firstText(c, "text")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Triple(t, i, SoccerEvent(kind, sideOfRef(docs, ids, r.firstText(c, "play.team.displayName")), r.firstText(c, "time.displayValue"), text))
                }.maxWithOrNull(compareBy({ it.first }, { it.second }))
            }
            if (fresherCommentary != null && (fromBoard == null || fresherCommentary.first > minuteValue(fromBoard))) fresherCommentary.third else boardEvent
        }
        val block = GameBlock.SoccerStrip(
            source = SourceId.ESPN, lastEvent = lastEvent, homePossession = homePoss,
            homeCorners = stat(SideKey.HOME, "wonCorners")?.roundToInt(), awayCorners = stat(SideKey.AWAY, "wonCorners")?.roundToInt(),
            homeYellow = cards(SideKey.HOME, "yellowCard"), awayYellow = cards(SideKey.AWAY, "yellowCard"),
            homeRed = cards(SideKey.HOME, "redCard"), awayRed = cards(SideKey.AWAY, "redCard"),
            homeScorers = scorers(SideKey.HOME), awayScorers = scorers(SideKey.AWAY),
        )
        val empty = block.lastEvent == null && block.homePossession == null && block.homeCorners == null && block.awayCorners == null &&
            block.homeYellow + block.awayYellow + block.homeRed + block.awayRed == 0 && block.homeScorers.isEmpty() && block.awayScorers.isEmpty()
        return if (empty) null else block
    }

    /** Commentary types that count as a strip event; everything else (fouls, aerials, delays...) is chatter. */
    private fun commentaryKind(type: String?): String? {
        val t = type?.lowercase() ?: return null
        return when {
            t == "goal" || ("penalty" in t && "scored" in t) -> "goal"
            t == "yellow card" -> "yellow"
            t == "red card" -> "red"
            t == "substitution" -> "sub"
            t == "corner awarded" -> "corner"
            t == "shot on target" -> "shot"
            t == "offside" -> "offside"
            t.startsWith("var") -> "var"
            t.startsWith("penalty") -> "penalty"
            else -> null
        }
    }

    // ---- Phase B: lineups (home first, then away) incl. ESPN's formation place -----------------------------------------------

    private fun lineup(profile: String, docs: EspnDocs): GameBlock.Lineup? {
        val summary = docs.summary ?: return null
        val rosters = r.readTracked(summary, "rosters[*]").sortedBy { if (it.homeAway == "home") 0 else 1 }.filter { it.homeAway == "home" || it.homeAway == "away" }
        val sides = rosters.mapNotNull { ro ->
            val players = r.read(ro.value, "roster[*]").mapNotNull { pl ->
                val name = r.firstText(pl, "athlete.displayName") ?: return@mapNotNull null
                LineupPlayer(
                    number = r.firstText(pl, "jersey"), name = name, pos = r.firstText(pl, "position.abbreviation"),
                    starter = r.firstText(pl, "starter") == "true", place = r.firstText(pl, "formationPlace")?.toIntOrNull()?.takeIf { it > 0 },
                )
            }
            if (players.isEmpty()) null else LineupSide(r.firstText(ro.value, "formation"), players.sortedBy { !it.starter })
        }
        return sides.takeIf { it.isNotEmpty() }?.let { GameBlock.Lineup(SourceId.ESPN, it) }
    }

    // ---- Phase C: basketball strip (summary, 60 s) -----------------------------------------------------------------------------

    /** The competitor/team node's side: its own `homeAway`, else its team id against the event's competitors. */
    private fun ownerSide(node: JsonElement, homeAway: String?, ids: Pair<String?, String?>): SideKey? = when (homeAway) {
        "home" -> SideKey.HOME
        "away" -> SideKey.AWAY
        else -> sideById(r.firstText(node, "team.id") ?: r.firstText(node, "id"), ids)
    }

    /**
     * Fouls (`fouls.teamFoulsCurrent`), `fouls.bonusState`, `timeoutsRemaining` and `possession` (a boolean on the competitor) read straight
     * from the summary's header competitors. The 8+ point run is derived and exists only while the `basketballRun` switch is ON.
     */
    private fun basketballStrip(p: SportProfile, docs: EspnDocs): GameBlock.BasketballStrip? {
        val spec = p.spec("basketballStrip") ?: return null
        val root = root(spec, docs) ?: return null
        val ids = competitorIds(docs)
        val comps = r.readTracked(root, str(spec["path"]) ?: return null)
        fun comp(side: SideKey) = comps.firstOrNull { ownerSide(it.value, it.homeAway, ids) == side }?.value
        val h = comp(SideKey.HOME); val a = comp(SideKey.AWAY)
        fun int(c: JsonElement?, path: String) = c?.let { r.firstText(it, path)?.toIntOrNull() }
        val possession = when {
            h != null && r.firstText(h, "possession") == "true" -> SideKey.HOME
            a != null && r.firstText(a, "possession") == "true" -> SideKey.AWAY
            else -> null
        }
        val run: ScoringRun? = if (!mapping.switchOn("basketballRun")) null else str(spec["plays"])?.let { path ->
            BasketballRunPolicy.run(r.read(root, path).map { pl ->
                RunPlay(
                    side = sideById(r.firstText(pl, "team.id"), ids)?.let { if (it == SideKey.HOME) "home" else "away" },
                    points = r.firstText(pl, "scoreValue")?.toIntOrNull() ?: 0,
                    scoring = r.firstText(pl, "scoringPlay") == "true",
                )
            })
        }
        val block = GameBlock.BasketballStrip(
            source = SourceId.ESPN,
            homeFouls = int(h, "fouls.teamFoulsCurrent"), awayFouls = int(a, "fouls.teamFoulsCurrent"),
            homeBonus = h?.let { r.firstText(it, "fouls.bonusState") }, awayBonus = a?.let { r.firstText(it, "fouls.bonusState") },
            homeTimeouts = int(h, "timeoutsRemaining"), awayTimeouts = int(a, "timeoutsRemaining"),
            possession = possession, run = run,
        )
        val empty = block.homeFouls == null && block.awayFouls == null && block.homeBonus == null && block.awayBonus == null &&
            block.homeTimeouts == null && block.awayTimeouts == null && block.possession == null && block.run == null
        return if (empty) null else block
    }

    // ---- Phase C: hockey strip (summary, 60 s) -----------------------------------------------------------------------------------

    private fun hockeyStrip(p: SportProfile, docs: EspnDocs): GameBlock.HockeyStrip? {
        val spec = p.spec("hockeyStrip") ?: return null
        val root = root(spec, docs) ?: return null
        val ids = competitorIds(docs)
        fun sideStr(id: String?) = sideById(id, ids)?.let { if (it == SideKey.HOME) "home" else "away" }
        val plays = r.read(root, str(spec["plays"]) ?: "plays[*]").map { pl ->
            val clock = r.firstText(pl, "clock.displayValue")?.split(":")?.mapNotNull { it.toIntOrNull() }
            HockeyPlay(
                period = r.firstText(pl, "period.number")?.toIntOrNull() ?: 0,
                elapsedSec = clock?.takeIf { it.size == 2 }?.let { it[0] * 60 + it[1] },
                side = sideStr(r.firstText(pl, "team.id")),
                penaltyMinutes = r.firstText(pl, "type.penaltyMinutes")?.toIntOrNull(),
                penaltyType = r.firstText(pl, "type.penaltyType"),
                goal = r.firstText(pl, "scoringPlay") == "true",
                strength = r.firstText(pl, "strength.text"),
            )
        }
        val side = HockeyStrength.powerPlaySide(plays)
        val statsPath = str(spec["stats"]) ?: "boxscore.teams[*].statistics[*]"
        val owners = r.readTracked(root, statsPath.substringBefore("[*].statistics").let { "$it[*]" })
        fun stat(s: SideKey, label: String): String? {
            val owner = owners.firstOrNull { ownerSide(it.value, it.homeAway, ids) == s }?.value ?: return null
            return r.firstText(r.read(owner, "statistics[label=$label]").firstOrNull(), "displayValue")
        }
        fun pp(s: SideKey): String? {
            val g = stat(s, "Power Play Goals")?.toIntOrNull() ?: return null
            val o = stat(s, "Power Play Opportunities")?.toIntOrNull() ?: return null
            return "$g/$o"
        }
        // Derived: only with the switch ON, only for an unambiguous situation, always "as of the last update".
        val secondsLeft = if (!mapping.switchOn("hockeyPowerPlayClock") || side == null) null else {
            val period = mapping.commonPath("period")?.let { r.firstText(docs.event, it) }?.toIntOrNull()
            val remaining = r.firstText(docs.event, "status.clock")?.toDoubleOrNull()?.toInt()
            if (period == null || remaining == null) null else PowerPlayClock.secondsLeft(plays, period, remaining, side)
        }
        val block = GameBlock.HockeyStrip(
            source = SourceId.ESPN,
            powerPlaySide = side?.let { if (it == "home") SideKey.HOME else SideKey.AWAY }, powerPlaySecondsLeft = secondsLeft,
            homeShots = stat(SideKey.HOME, "Shots")?.toIntOrNull(), awayShots = stat(SideKey.AWAY, "Shots")?.toIntOrNull(),
            homePowerPlay = pp(SideKey.HOME), awayPowerPlay = pp(SideKey.AWAY),
            homeFaceoffs = stat(SideKey.HOME, "Faceoffs Won")?.toIntOrNull(), awayFaceoffs = stat(SideKey.AWAY, "Faceoffs Won")?.toIntOrNull(),
        )
        val empty = block.powerPlaySide == null && block.homeShots == null && block.awayShots == null && block.homePowerPlay == null &&
            block.awayPowerPlay == null && block.homeFaceoffs == null && block.awayFaceoffs == null
        return if (empty) null else block
    }

    private companion object {
        const val DEFAULT_PLAYS_CAP = 60
    }
}

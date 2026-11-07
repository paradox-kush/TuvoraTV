package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class RefreshConfig(val liveMs: Long = 15_000, val summaryMs: Long = 60_000, val idleMs: Long = 0, val minMs: Long = 6_000)

/** Remote kill switches: the whole ESPN source, per sport profile, per TheSportsDB league id. */
data class KillSwitch(val espn: Boolean = false, val sports: Set<String> = emptySet(), val leagues: Set<String> = emptySet())

data class LeagueMapping(
    val tsdbId: String,
    val name: String,
    val sport: String?,
    val espnPath: String?,
    val profile: String?,
    /** false: ESPN has no usable summary endpoint for this league (F1, UFC, tennis). */
    val summary: Boolean = true,
    /** Per-league chain order override; null = default TheSportsDB then ESPN. */
    val chain: List<SourceId>? = null,
)

data class MatchConfig(val dateWindowHours: Int = 6, val minTeamsMatching: Int = 2)

class SportProfile(val name: String, val blocks: List<String>, private val specs: JsonObject) {
    fun spec(block: String): JsonObject? = specs[block] as? JsonObject

    /** Blocks this profile can show: the listed order first, then overlay blocks that only have a spec (e.g. winProbability). */
    val blockTypes: List<BlockType> by lazy {
        (blocks + specs.keys).mapNotNull { BlockType.fromWire(it) }.filter { it in BlockType.assemblable }.distinct()
    }

    fun orderOf(type: BlockType): Int = blocks.indexOf(type.wire).let { if (it < 0) Int.MAX_VALUE else it }
    val periodLabel: String? get() = (specs["periodLabel"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The mapping document (research/sports-mapping/sports-mapping.v1.json): league map, event-match
 * rules, per-block provider paths and kill switches. Data, not code, so a renamed provider field is
 * a config push. Unknown top-level fields are kept in [extra].
 */
data class SportsMapping(
    val schemaVersion: Int,
    val version: String,
    val killSwitch: KillSwitch,
    val espnScoreboardUrl: String,
    val espnSummaryUrl: String,
    val refresh: RefreshConfig,
    val deadlineMs: Long,
    val states: Map<String, String>,
    val leagues: List<LeagueMapping>,
    val teamAliases: Map<String, String>,
    val eventOverrides: Map<String, String>,
    val match: MatchConfig,
    val common: JsonObject,
    val profiles: Map<String, SportProfile>,
    val extra: JsonObject,
) {
    fun leagueFor(tsdbId: String?): LeagueMapping? = leagues.firstOrNull { it.tsdbId == tsdbId }
    fun profile(name: String?): SportProfile? = name?.let { profiles[it] }

    fun stateFor(espnState: String?): GameState? = when (states[espnState]) {
        "upcoming" -> GameState.UPCOMING
        "live" -> GameState.LIVE
        "final" -> GameState.FINAL
        "postponed" -> GameState.POSTPONED
        else -> null
    }

    fun profileNameFor(leagueId: String?, sport: String?): String? {
        leagueFor(leagueId)?.profile?.takeIf { it in profiles }?.let { return it }
        return sportToProfile[sport?.trim()?.lowercase()]?.takeIf { it in profiles }
    }

    /** ESPN usable for this game: not switched off globally, for the sport profile, or for the league. */
    fun espnEnabled(profile: String?, leagueId: String?): Boolean =
        !killSwitch.espn && profile !in killSwitch.sports && leagueId !in killSwitch.leagues

    fun commonPath(key: String): String? = (common[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun commonTeamPath(key: String): String? =
        ((common["team"] as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val sportToProfile = mapOf(
            "soccer" to "soccer", "basketball" to "basketball", "american football" to "football",
            "baseball" to "baseball", "ice hockey" to "hockey", "motorsport" to "motorsport",
            "fighting" to "fighting", "tennis" to "tennis", "cricket" to "cricket",
        )
    }
}

sealed interface MappingParseResult {
    data class Parsed(val mapping: SportsMapping) : MappingParseResult
    data class Invalid(val reason: String) : MappingParseResult
}

object SportsMappingParser {
    private val known = setOf(
        "schemaVersion", "version", "killSwitch", "sources", "pathSyntax", "states", "leagues", "aliases",
        "eventOverrides", "match", "common", "profiles",
    )

    /** Never throws. Structural problems become [MappingParseResult.Invalid]; unknown fields are kept. */
    fun parse(text: String): MappingParseResult = try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return MappingParseResult.Invalid("root is not an object")
        val schema = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: return MappingParseResult.Invalid("no schemaVersion")
        val profilesObj = root["profiles"] as? JsonObject ?: return MappingParseResult.Invalid("no profiles")
        val common = root["common"] as? JsonObject ?: return MappingParseResult.Invalid("no common")
        val leaguesArr = root["leagues"] as? JsonArray ?: return MappingParseResult.Invalid("no leagues")
        val espn = (root["sources"] as? JsonObject)?.get("espn") as? JsonObject
        val refresh = espn?.get("refresh") as? JsonObject
        val ks = root["killSwitch"] as? JsonObject
        val defaults = RefreshConfig()
        MappingParseResult.Parsed(
            SportsMapping(
                schemaVersion = schema,
                version = str(root["version"]) ?: "",
                killSwitch = KillSwitch(
                    espn = (ks?.get("espn") as? JsonPrimitive)?.booleanOrNull ?: false,
                    sports = strings(ks?.get("sports")),
                    leagues = strings(ks?.get("leagues")),
                ),
                espnScoreboardUrl = str(espn?.get("scoreboard")) ?: DEFAULT_SCOREBOARD,
                espnSummaryUrl = str(espn?.get("summary")) ?: DEFAULT_SUMMARY,
                refresh = RefreshConfig(
                    liveMs = long(refresh?.get("liveMs")) ?: defaults.liveMs,
                    summaryMs = long(refresh?.get("summaryMs")) ?: defaults.summaryMs,
                    idleMs = long(refresh?.get("idleMs")) ?: defaults.idleMs,
                    minMs = long(refresh?.get("minMs")) ?: defaults.minMs,
                ),
                deadlineMs = long(espn?.get("deadlineMs")) ?: 8_000,
                states = (root["states"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap().orEmpty(),
                leagues = leaguesArr.mapNotNull { league(it) },
                teamAliases = ((root["aliases"] as? JsonObject)?.get("teams") as? JsonObject)
                    ?.mapNotNull { (k, v) -> str(v)?.let { k.lowercase() to it } }?.toMap().orEmpty(),
                eventOverrides = (root["eventOverrides"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap().orEmpty(),
                match = (root["match"] as? JsonObject).let {
                    MatchConfig(
                        dateWindowHours = (it?.get("dateWindowHours") as? JsonPrimitive)?.intOrNull ?: 6,
                        minTeamsMatching = (it?.get("minTeamsMatching") as? JsonPrimitive)?.intOrNull ?: 2,
                    )
                },
                common = common,
                profiles = profilesObj.mapNotNull { (name, v) ->
                    (v as? JsonObject)?.let { o -> name to SportProfile(name, (o["blocks"] as? JsonArray)?.mapNotNull { str(it) }.orEmpty(), o) }
                }.toMap(),
                extra = JsonObject(root.filterKeys { it !in known }),
            ),
        )
    } catch (e: Exception) {
        MappingParseResult.Invalid("unparseable: ${e.javaClass.simpleName}")
    }

    private fun league(el: JsonElement): LeagueMapping? {
        val o = el as? JsonObject ?: return null
        val id = str(o["tsdb"]) ?: return null
        return LeagueMapping(
            tsdbId = id,
            name = str(o["name"]).orEmpty(),
            sport = str(o["sport"]),
            espnPath = str(o["espn"]),
            profile = str(o["profile"]),
            summary = (o["summary"] as? JsonPrimitive)?.booleanOrNull ?: true,
            chain = (o["chain"] as? JsonArray)?.mapNotNull { SourceId.fromWire(str(it)) }?.takeIf { it.isNotEmpty() },
        )
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.content != "null" || it.isString }?.content
    private fun long(e: JsonElement?): Long? = (e as? JsonPrimitive)?.longOrNull
    private fun strings(e: JsonElement?): Set<String> = (e as? JsonArray)?.mapNotNull { str(it) }?.toSet().orEmpty()

    const val DEFAULT_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/{path}/scoreboard?dates={yyyymmdd}"
    const val DEFAULT_SUMMARY = "https://site.api.espn.com/apis/site/v2/sports/{path}/summary?event={id}"
}

/** The mapping shipped in the APK: a verbatim copy of research/sports-mapping/sports-mapping.v1.json. */
object BundledSportsMapping {
    const val RESOURCE = "sports/sports-mapping.v1.json"

    private val cached: SportsMapping by lazy {
        val text = BundledSportsMapping::class.java.classLoader!!.getResourceAsStream(RESOURCE)!!.bufferedReader().use { it.readText() }
        (SportsMappingParser.parse(text) as? MappingParseResult.Parsed)?.mapping
            ?: error("bundled sports mapping is invalid")
    }

    fun load(): SportsMapping = cached
}

/** A bundled ESPN event used to smoke-parse any mapping before it is adopted. */
class SmokeFixture(val event: JsonElement, val summary: JsonElement? = null, val profile: String = "basketball") {
    companion object {
        fun bundled(): SmokeFixture {
            val text = SmokeFixture::class.java.classLoader!!.getResourceAsStream("sports/smoke/nba_event.json")!!.bufferedReader().use { it.readText() }
            return SmokeFixture(Json.parseToJsonElement(text))
        }
    }
}

sealed interface OfferResult {
    data class Adopted(val version: String) : OfferResult
    data class Rejected(val reason: String) : OfferResult
}

/** Where the last-good mapping text and the remote ETag live between launches. */
interface MappingPersistence {
    fun loadMapping(): String?
    fun saveMapping(text: String)
    fun loadEtag(): String?
    fun saveEtag(etag: String?)
}

class InMemoryMappingPersistence : MappingPersistence {
    private var mapping: String? = null
    private var etag: String? = null
    override fun loadMapping() = mapping
    override fun saveMapping(text: String) { mapping = text }
    override fun loadEtag() = etag
    override fun saveEtag(etag: String?) { this.etag = etag }
}

/** Plain-file persistence (pass `context.filesDir`-based directory). */
class FileMappingPersistence(private val dir: java.io.File) : MappingPersistence {
    private val mappingFile get() = java.io.File(dir, "sports-mapping.json")
    private val etagFile get() = java.io.File(dir, "sports-mapping.etag")
    override fun loadMapping() = runCatching { mappingFile.takeIf { it.exists() }?.readText() }.getOrNull()
    override fun saveMapping(text: String) { runCatching { dir.mkdirs(); mappingFile.writeText(text) } }
    override fun loadEtag() = runCatching { etagFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() } }.getOrNull()
    override fun saveEtag(etag: String?) {
        runCatching { dir.mkdirs(); if (etag == null) etagFile.delete() else etagFile.writeText(etag) }
    }
}

/**
 * Holds the active mapping. A downloaded mapping is adopted only after schema + smoke-parse
 * validation; anything invalid leaves the last good one in place, so a bad push can never crash a
 * screen. The smoke parse requires the header to read from the bundled event (blocks may legitimately
 * be empty: a mapping repairing a renamed field is judged on the header, not on every block).
 */
class SportsMappingStore(
    private val bundled: SportsMapping,
    private val persistence: MappingPersistence,
    private val smoke: SmokeFixture,
) {
    @Volatile
    var current: SportsMapping = bundled
        private set

    /** Load the persisted last-good copy, if it still validates. */
    @Synchronized
    fun restore() {
        val text = persistence.loadMapping() ?: return
        val parsed = (SportsMappingParser.parse(text) as? MappingParseResult.Parsed)?.mapping ?: return
        if (validate(parsed) == null) current = parsed
    }

    @Synchronized
    fun offer(text: String): OfferResult {
        val parsed = when (val r = SportsMappingParser.parse(text)) {
            is MappingParseResult.Parsed -> r.mapping
            is MappingParseResult.Invalid -> return OfferResult.Rejected(r.reason)
        }
        validate(parsed)?.let { return OfferResult.Rejected(it) }
        persistence.saveMapping(text)
        current = parsed
        return OfferResult.Adopted(parsed.version)
    }

    private fun validate(m: SportsMapping): String? {
        if (m.schemaVersion !in 1..SUPPORTED_SCHEMA) return "unsupported schemaVersion ${m.schemaVersion}"
        if (m.profile(smoke.profile) == null) return "smoke parse failed: profile ${smoke.profile} missing"
        val asm = EspnBlockAssembler(m)
        val docs = EspnDocs(smoke.event, smoke.summary)
        val header = asm.header(smoke.profile, docs) as? GameHeader.Teams
        if (header == null || header.home.name.isBlank() || header.away.name.isBlank() ||
            header.home.score == null || asm.gameState(docs) == null
        ) return "smoke parse failed: header does not read from the bundled fixture"
        return null
    }

    companion object { const val SUPPORTED_SCHEMA = 1 }
}

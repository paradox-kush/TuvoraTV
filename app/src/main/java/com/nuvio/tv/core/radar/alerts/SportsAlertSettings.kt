package com.nuvio.tv.core.radar.alerts

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A per-game choice made on the game page: "all" or "off", forgotten after [expMs] (kickoff + 24 h). */
data class GameAlertOverride(val id: String, val level: String, val expMs: Long) {
    val isOff: Boolean get() = level == LEVEL_OFF

    companion object {
        const val LEVEL_ALL = "all"
        const val LEVEL_OFF = "off"
    }
}

/** What the game page's Alerts chip says: Default -> All -> Off -> Default. */
enum class GameAlertChoice(val label: String) {
    Default("Default"), All("All"), Off("Off");

    fun next(): GameAlertChoice = entries[(ordinal + 1) % entries.size]
}

/**
 * Sports alerts settings (per profile, one object in [com.nuvio.tv.core.radar.RadarPrefs], synced as `alerts`).
 * Defaults are the product defaults; every unknown or out-of-range value reads as the default, so a newer or a
 * hand-edited blob can never break a launch. Twin of the Mobile/Desktop class of the same name.
 */
data class SportsAlertSettings(
    val enabled: Boolean = true,
    val teamLevel: AlertLevel = AlertLevel.All,
    val leagueLevel: AlertLevel = AlertLevel.FinalOnly,
    val quietOn: Boolean = true,
    val quietStartMin: Int = 1320,
    val quietEndMin: Int = 480,
    val maxPerHour: Int = 6,
    val batchSeconds: Int = 60,
    val duringPlayback: DuringPlayback = DuringPlayback.LiveTvOnly,
    val mutedLeagues: Set<String> = emptySet(),
    val mutedTeams: Set<String> = emptySet(),
    val games: List<GameAlertOverride> = emptyList(),
) {
    val batchMs: Long get() = batchSeconds * 1000L

    fun gameOverride(eventId: String, nowMs: Long): GameAlertOverride? =
        games.firstOrNull { it.id == eventId && it.expMs > nowMs }

    fun choiceFor(eventId: String, nowMs: Long): GameAlertChoice = when (gameOverride(eventId, nowMs)?.level) {
        GameAlertOverride.LEVEL_ALL -> GameAlertChoice.All
        GameAlertOverride.LEVEL_OFF -> GameAlertChoice.Off
        else -> GameAlertChoice.Default
    }

    /** Applies a per-game choice (Default removes the override) and prunes expired overrides. */
    fun withGameChoice(eventId: String, choice: GameAlertChoice, expMs: Long, nowMs: Long): SportsAlertSettings {
        val kept = games.filter { it.id != eventId && it.expMs > nowMs }
        val next = when (choice) {
            GameAlertChoice.Default -> kept
            GameAlertChoice.All -> kept + GameAlertOverride(eventId, GameAlertOverride.LEVEL_ALL, expMs)
            GameAlertChoice.Off -> kept + GameAlertOverride(eventId, GameAlertOverride.LEVEL_OFF, expMs)
        }
        return copy(games = next.takeLast(MAX_GAME_OVERRIDES))
    }

    fun toggleMutedLeague(id: String): SportsAlertSettings =
        copy(mutedLeagues = if (id in mutedLeagues) mutedLeagues - id else mutedLeagues + id)

    fun toggleMutedTeam(id: String): SportsAlertSettings =
        copy(mutedTeams = if (id in mutedTeams) mutedTeams - id else mutedTeams + id)

    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("team_level", teamLevel.wire)
        put("league_level", leagueLevel.wire)
        put("quiet_on", quietOn)
        put("quiet_start_min", sanitizeMinute(quietStartMin, DEFAULT_QUIET_START))
        put("quiet_end_min", sanitizeMinute(quietEndMin, DEFAULT_QUIET_END))
        put("max_per_hour", sanitizeMaxPerHour(maxPerHour))
        put("batch_s", sanitizeBatch(batchSeconds))
        put("during_playback", duringPlayback.wire)
        put("muted_leagues", buildJsonArray { mutedLeagues.sorted().forEach { add(JsonPrimitive(it)) } })
        put("muted_teams", buildJsonArray { mutedTeams.sorted().forEach { add(JsonPrimitive(it)) } })
        put("games", buildJsonArray {
            games.forEach { g ->
                add(buildJsonObject { put("id", g.id); put("level", g.level); put("exp", g.expMs) })
            }
        })
    }

    companion object {
        const val DEFAULT_QUIET_START = 1320
        const val DEFAULT_QUIET_END = 480
        const val MAX_GAME_OVERRIDES = 100
        const val MAX_SYNC_BYTES = 8 * 1024
        val ALLOWED_MAX_PER_HOUR: List<Int> = listOf(3, 6, 12, 0)
        val ALLOWED_BATCH_SECONDS: List<Int> = listOf(0, 60, 300)

        fun sanitizeMaxPerHour(v: Int): Int = if (v in ALLOWED_MAX_PER_HOUR) v else 6
        fun sanitizeBatch(v: Int): Int = if (v in ALLOWED_BATCH_SECONDS) v else 60
        fun sanitizeMinute(v: Int, default: Int): Int = if (v in 0..1439) v else default

        fun nextMaxPerHour(current: Int): Int {
            val i = ALLOWED_MAX_PER_HOUR.indexOf(current)
            return ALLOWED_MAX_PER_HOUR[(i + 1) % ALLOWED_MAX_PER_HOUR.size]
        }

        fun nextBatchSeconds(current: Int): Int {
            val i = ALLOWED_BATCH_SECONDS.indexOf(current)
            return ALLOWED_BATCH_SECONDS[(i + 1) % ALLOWED_BATCH_SECONDS.size]
        }

        /** A JSON value that is not an object (or is absent/null) is "nothing to say": null, so a pull keeps the local value. */
        fun decodeOrNull(element: JsonElement?): SportsAlertSettings? = (element as? JsonObject)?.let(::decode)

        /** Tolerant decode: a missing key, a wrong type or an unknown choice reads as the default for that field. */
        fun decode(obj: JsonObject?): SportsAlertSettings {
            if (obj == null) return SportsAlertSettings()
            val d = SportsAlertSettings()
            fun prim(name: String): JsonPrimitive? = obj[name] as? JsonPrimitive
            fun bool(name: String, default: Boolean) = prim(name)?.booleanOrNull ?: default
            fun int(name: String): Int? = prim(name)?.takeIf { !it.isString }?.intOrNull
            fun ids(name: String): Set<String> =
                (obj[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }?.toSet().orEmpty()
            val games = (obj["games"] as? JsonArray)?.mapNotNull { el ->
                val g = el as? JsonObject ?: return@mapNotNull null
                val id = (g["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val level = (g["level"] as? JsonPrimitive)?.contentOrNull
                if (level != GameAlertOverride.LEVEL_ALL && level != GameAlertOverride.LEVEL_OFF) return@mapNotNull null
                val exp = (g["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return@mapNotNull null
                GameAlertOverride(id, level, exp)
            }?.takeLast(MAX_GAME_OVERRIDES).orEmpty()
            return SportsAlertSettings(
                enabled = bool("enabled", d.enabled),
                teamLevel = AlertLevel.parse(prim("team_level")?.contentOrNull, d.teamLevel),
                leagueLevel = AlertLevel.parse(prim("league_level")?.contentOrNull, d.leagueLevel),
                quietOn = bool("quiet_on", d.quietOn),
                quietStartMin = sanitizeMinute(int("quiet_start_min") ?: d.quietStartMin, DEFAULT_QUIET_START),
                quietEndMin = sanitizeMinute(int("quiet_end_min") ?: d.quietEndMin, DEFAULT_QUIET_END),
                maxPerHour = sanitizeMaxPerHour(int("max_per_hour") ?: d.maxPerHour),
                batchSeconds = sanitizeBatch(int("batch_s") ?: d.batchSeconds),
                duringPlayback = DuringPlayback.parse(prim("during_playback")?.contentOrNull),
                mutedLeagues = ids("muted_leagues"),
                mutedTeams = ids("muted_teams"),
                games = games,
            )
        }
    }
}

/**
 * Stores [SportsAlertSettings] inside the RadarPrefs blob as the same JSON object that is synced. Decoding never
 * throws: a value that is not an object reads as the defaults, so a bad blob can never reset the whole radar state.
 */
object SportsAlertSettingsSerializer : KSerializer<SportsAlertSettings> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): SportsAlertSettings =
        SportsAlertSettings.decode(decoder.decodeSerializableValue(JsonElement.serializer()) as? JsonObject)

    override fun serialize(encoder: Encoder, value: SportsAlertSettings) =
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJson())
}

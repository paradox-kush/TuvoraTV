package com.nuvio.tv.core.radar.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsAlertSettingsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `an empty object, null or nothing decode to the product defaults`() {
        val d = SportsAlertSettings()
        assertEquals("empty", d, SportsAlertSettings.decode(obj("{}")))
        assertEquals("null", d, SportsAlertSettings.decode(null))
        assertEquals("defaults", true, d.enabled)
        assertEquals("team level", AlertLevel.All, d.teamLevel)
        assertEquals("league level", AlertLevel.FinalOnly, d.leagueLevel)
        assertEquals("quiet", Triple(true, 1320, 480), Triple(d.quietOn, d.quietStartMin, d.quietEndMin))
        assertEquals("cap", 6, d.maxPerHour)
        assertEquals("batch", 60, d.batchSeconds)
        assertEquals("during playback", DuringPlayback.LiveTvOnly, d.duringPlayback)
    }

    @Test
    fun `unknown values and wrong types read as the default for that field only`() {
        val s = SportsAlertSettings.decode(obj("""{"enabled":false,"team_level":"loud","league_level":7,"quiet_start_min":5000,"max_per_hour":4,"batch_s":"60","during_playback":"sometimes","muted_leagues":"x"}"""))
        assertEquals("a valid field survives", false, s.enabled)
        assertEquals("team level", AlertLevel.All, s.teamLevel)
        assertEquals("league level", AlertLevel.FinalOnly, s.leagueLevel)
        assertEquals("quiet start", 1320, s.quietStartMin)
        assertEquals("cap", 6, s.maxPerHour)
        assertEquals("batch as a string", 60, s.batchSeconds)
        assertEquals("during playback", DuringPlayback.LiveTvOnly, s.duringPlayback)
        assertEquals("muted", emptySet<String>(), s.mutedLeagues)
    }

    @Test
    fun `a full object round trips`() {
        val s = SportsAlertSettings(
            enabled = false, teamLevel = AlertLevel.StartAndFinal, leagueLevel = AlertLevel.Off, quietOn = false,
            quietStartMin = 60, quietEndMin = 120, maxPerHour = 0, batchSeconds = 300, duringPlayback = DuringPlayback.Never,
            mutedLeagues = setOf("L1", "L2"), mutedTeams = setOf("T1"),
            games = listOf(GameAlertOverride("e1", "all", 99L), GameAlertOverride("e2", "off", 100L)),
        )
        assertEquals("round trip", s, SportsAlertSettings.decode(s.toJson()))
        assertEquals("allowed cap values", listOf(3, 6, 12, 0), SportsAlertSettings.ALLOWED_MAX_PER_HOUR)
    }

    @Test
    fun `malformed game overrides are skipped`() {
        val s = SportsAlertSettings.decode(obj("""{"games":[{"id":"e1","level":"all","exp":5},{"id":"e2","level":"maybe","exp":5},{"level":"off","exp":5},{"id":"e3","level":"off"},7]}"""))
        assertEquals("only the valid one", listOf(GameAlertOverride("e1", "all", 5)), s.games)
    }

    @Test
    fun `only an object counts as a remote value`() {
        assertNull("json null", SportsAlertSettings.decodeOrNull(JsonNull))
        assertNull("a number", SportsAlertSettings.decodeOrNull(JsonPrimitive(5)))
        assertNull("absent", SportsAlertSettings.decodeOrNull(null))
        assertEquals("an object", SportsAlertSettings(), SportsAlertSettings.decodeOrNull(obj("{}")))
    }

    @Test
    fun `game choices cycle, expire and prune`() {
        val now = 1_000L
        var s = SportsAlertSettings()
        s = s.withGameChoice("e1", GameAlertChoice.All, expMs = now + 100, nowMs = now)
        assertEquals("all", GameAlertChoice.All, s.choiceFor("e1", now))
        s = s.withGameChoice("e1", GameAlertChoice.Off, expMs = now + 100, nowMs = now)
        assertEquals("off replaces all", listOf(GameAlertOverride("e1", "off", now + 100)), s.games)
        assertEquals("expired reads as default", GameAlertChoice.Default, s.choiceFor("e1", now + 100))
        s = s.withGameChoice("e1", GameAlertChoice.Default, expMs = 0, nowMs = now)
        assertTrue("default removes it", s.games.isEmpty())
        assertEquals("cycle", listOf(GameAlertChoice.All, GameAlertChoice.Off, GameAlertChoice.Default), listOf(GameAlertChoice.Default.next(), GameAlertChoice.All.next(), GameAlertChoice.Off.next()))
        val old = SportsAlertSettings(games = listOf(GameAlertOverride("old", "off", now - 1)))
        assertTrue("expired are pruned on write", old.withGameChoice("e2", GameAlertChoice.All, now + 5, now).games.none { it.id == "old" })
    }

    @Test
    fun `cycles for the rows`() {
        assertEquals("level cycle", listOf(AlertLevel.StartAndFinal, AlertLevel.FinalOnly, AlertLevel.Off, AlertLevel.All), AlertLevel.entries.map { AlertLevel.next(it) })
        assertEquals("cap cycle", listOf(6, 12, 0, 3), listOf(3, 6, 12, 0).map { SportsAlertSettings.nextMaxPerHour(it) })
        assertEquals("batch cycle", listOf(60, 300, 0), listOf(0, 60, 300).map { SportsAlertSettings.nextBatchSeconds(it) })
        assertEquals("playback cycle", listOf(DuringPlayback.LiveTvOnly, DuringPlayback.Never, DuringPlayback.Always), DuringPlayback.entries.map { DuringPlayback.next(it) })
    }

    @Test
    fun `muting toggles`() {
        val s = SportsAlertSettings().toggleMutedLeague("L1").toggleMutedTeam("T1")
        assertEquals("muted", setOf("L1") to setOf("T1"), s.mutedLeagues to s.mutedTeams)
        val back = s.toggleMutedLeague("L1").toggleMutedTeam("T1")
        assertEquals("unmuted", SportsAlertSettings(), back)
    }

    @Test
    fun `overrides keep the last 50 and muted ids are capped`() {
        var s = SportsAlertSettings()
        repeat(60) { i -> s = s.withGameChoice("e$i", GameAlertChoice.Off, expMs = 10_000L + i, nowMs = 1_000L) }
        assertEquals("50 kept", 50, s.games.size)
        assertEquals("the newest survive", "e59", s.games.last().id)
        assertEquals("the oldest are gone", "e10", s.games.first().id)
        var m = SportsAlertSettings()
        repeat(150) { i -> m = m.toggleMutedLeague("L$i").toggleMutedTeam("T$i") }
        assertEquals("leagues capped", SportsAlertSettings.MAX_MUTED_IDS, m.mutedLeagues.size)
        assertEquals("teams capped", SportsAlertSettings.MAX_MUTED_IDS, m.mutedTeams.size)
        assertTrue("removing always works at the cap", m.toggleMutedLeague("L0").mutedLeagues.size == SportsAlertSettings.MAX_MUTED_IDS - 1)
    }

    @Test
    fun `a decoded blob is capped too`() {
        val ids = (1..300).joinToString(",") { "\"$it\"" }
        val games = (1..300).joinToString(",") { """{"id":"g$it","level":"off","exp":$it}""" }
        val s = SportsAlertSettings.decode(obj("""{"muted_leagues":[$ids],"muted_teams":[$ids],"games":[$games]}"""))
        assertEquals("leagues", SportsAlertSettings.MAX_MUTED_IDS, s.mutedLeagues.size)
        assertEquals("teams", SportsAlertSettings.MAX_MUTED_IDS, s.mutedTeams.size)
        assertEquals("games", SportsAlertSettings.MAX_GAME_OVERRIDES, s.games.size)
        assertEquals("takeLast", "g300", s.games.last().id)
    }

    @Test
    fun `the worst-case serialized object stays under 8 KB`() {
        val long = "123456789012"
        val s = SportsAlertSettings(
            mutedLeagues = (1..SportsAlertSettings.MAX_MUTED_IDS).map { "$long$it".take(14) }.toSet(),
            mutedTeams = (1..SportsAlertSettings.MAX_MUTED_IDS).map { "$long$it".take(14) }.toSet(),
            games = (1..SportsAlertSettings.MAX_GAME_OVERRIDES).map { GameAlertOverride("$long$it".take(14), "off", 1_800_000_000_000L + it) },
        )
        val bytes = s.toJson().toString().toByteArray().size
        assertTrue("$bytes bytes", bytes < SportsAlertSettings.MAX_SYNC_BYTES)
    }

    @Test
    fun `an oversized object is trimmed to the 7000 byte budget and its jsonb text stays under 8192`() {
        val id64 = "x".repeat(SportsAlertSettings.MAX_ID_CHARS)
        val s = SportsAlertSettings(
            mutedLeagues = (1..SportsAlertSettings.MAX_MUTED_IDS).map { "$id64$it".take(SportsAlertSettings.MAX_ID_CHARS - 3) + "%03d".format(it) }.toSet(),
            mutedTeams = (1..SportsAlertSettings.MAX_MUTED_IDS).map { "$id64$it".take(SportsAlertSettings.MAX_ID_CHARS - 3) + "%03d".format(it) }.toSet(),
            games = (1..SportsAlertSettings.MAX_GAME_OVERRIDES).map { GameAlertOverride(id64.take(SportsAlertSettings.MAX_ID_CHARS - 3) + "%03d".format(it), "off", 1_800_000_000_000L + it) },
        )
        val json = s.toJson()
        assertTrue("compact ${json.toString().length}", json.toString().toByteArray().size <= SportsAlertSettings.CLIENT_BUDGET_BYTES)
        assertTrue("jsonb text ${SportsAlertSettings.jsonbTextLength(json)}", SportsAlertSettings.jsonbTextLength(json) < SportsAlertSettings.MAX_SYNC_BYTES)
    }

    @Test
    fun `a fitting object is sent untouched`() {
        val games = (1..SportsAlertSettings.MAX_GAME_OVERRIDES).map { GameAlertOverride("g".repeat(60) + "%03d".format(it), "off", 1_800_000_000_000L + it) }
        val s = SportsAlertSettings(games = games)
        assertEquals("not trimmed", s, SportsAlertSettings.decode(s.toJson()))
    }

    @Test
    fun `jsonb text length counts a space after every colon and comma outside strings`() {
        val j = obj("""{"a":1,"b":"x:y,z","c":[1,2]}""")
        // compact: {"a":1,"b":"x:y,z","c":[1,2]} ; spaces after 3 colons and 3 commas outside strings = 6 (inside the string none).
        assertEquals("length", j.toString().length + 6, SportsAlertSettings.jsonbTextLength(j))
    }

    @Test
    fun `ids longer than 64 characters are ignored`() {
        val long = "z".repeat(65)
        val s = SportsAlertSettings.decode(obj("""{"muted_leagues":["$long","ok"],"games":[{"id":"$long","level":"off","exp":5}]}"""))
        assertEquals("long id skipped", setOf("ok"), s.mutedLeagues)
        assertTrue("long game id skipped", s.games.isEmpty())
        assertTrue("toggle ignores it too", SportsAlertSettings().toggleMutedTeam(long).mutedTeams.isEmpty())
    }
}

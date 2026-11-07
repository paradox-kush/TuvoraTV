package com.nuvio.tv.core.sports.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsMappingTest {
    private val bundledText = SportsFixtures::class.java.classLoader!!
        .getResourceAsStream("sports/sports-mapping.v1.json")!!.bufferedReader().readText()

    private fun store(persistence: MappingPersistence = InMemoryMappingPersistence()) =
        SportsMappingStore(BundledSportsMapping.load(), persistence, SmokeFixture.bundled())

    @Test fun `bundled default parses and has the v1 shape`() {
        val m = BundledSportsMapping.load()
        assertEquals("schema", 1, m.schemaVersion)
        assertTrue("has leagues", m.leagues.size >= 15)
        assertNotNull("basketball profile", m.profile("basketball"))
        assertEquals("nba espn path", "basketball/nba", m.leagueFor("4387")?.espnPath)
        assertEquals("state map", GameState.LIVE, m.stateFor("in"))
        assertEquals("refresh live", 15_000L, m.refresh.liveMs)
        assertEquals("deadline", 8_000L, m.deadlineMs)
    }

    @Test fun `unknown fields are kept not rejected`() {
        val r = SportsMappingParser.parse(bundledText.replaceFirst("{", """{"futureThing":{"x":1},"""))
        assertTrue("still valid", r is MappingParseResult.Parsed)
        assertNotNull("raw kept", (r as MappingParseResult.Parsed).mapping.extra["futureThing"])
    }

    @Test fun `parser reports garbage instead of throwing`() {
        assertTrue("not json", SportsMappingParser.parse("<html>") is MappingParseResult.Invalid)
        assertTrue("wrong root", SportsMappingParser.parse("[1,2]") is MappingParseResult.Invalid)
        assertTrue("no schema", SportsMappingParser.parse("{}") is MappingParseResult.Invalid)
    }

    @Test fun `kill switches cover espn then sport then league`() {
        val base = BundledSportsMapping.load()
        assertTrue("enabled by default", base.espnEnabled("basketball", "4387"))
        assertFalse("global", base.copy(killSwitch = KillSwitch(espn = true)).espnEnabled("basketball", "4387"))
        val bySport = base.copy(killSwitch = KillSwitch(sports = setOf("basketball")))
        assertFalse("sport off", bySport.espnEnabled("basketball", "4387"))
        assertTrue("other sport on", bySport.espnEnabled("football", "4391"))
        val byLeague = base.copy(killSwitch = KillSwitch(leagues = setOf("4387")))
        assertFalse("league off", byLeague.espnEnabled("basketball", "4387"))
        assertTrue("other league on", byLeague.espnEnabled("football", "4391"))
    }

    @Test fun `kill switch parses from json`() {
        val m = (SportsMappingParser.parse(bundledText.replace(""""killSwitch": { "espn": false, "sports": [], "leagues": [] }""", """"killSwitch": { "espn": false, "sports": ["hockey"], "leagues": ["4328"] }""")) as MappingParseResult.Parsed).mapping
        assertFalse("hockey off", m.espnEnabled("hockey", "4380"))
        assertFalse("epl off", m.espnEnabled("soccer", "4328"))
        assertTrue("nba on", m.espnEnabled("basketball", "4387"))
    }

    @Test fun `valid newer mapping is adopted and persisted`() {
        val p = InMemoryMappingPersistence()
        val s = store(p)
        val next = bundledText.replace("2026-11-07.1", "2026-11-08.1")
        val r = s.offer(next)
        assertTrue("adopted $r", r is OfferResult.Adopted)
        assertEquals("current", "2026-11-08.1", s.current.version)
        assertEquals("persisted", next, p.loadMapping())
        assertEquals("restart picks last good", "2026-11-08.1", store(p).also { it.restore() }.current.version)
    }

    @Test fun `unsupported schema is rejected and last good kept`() {
        val s = store()
        val r = s.offer(bundledText.replace(""""schemaVersion": 1""", """"schemaVersion": 99"""))
        assertTrue("rejected", r is OfferResult.Rejected)
        assertEquals("kept", "2026-11-07.1", s.current.version)
    }

    @Test fun `mapping that breaks the header smoke parse is rejected`() {
        val s = store()
        val broken = bundledText.replace(""""home": "competitions[0].competitors[homeAway=home]"""", """"home": "competitions[0].gone[homeAway=home]"""")
            .replace("2026-11-07.1", "2026-11-09.1")
        val r = s.offer(broken)
        assertTrue("rejected by smoke: $r", r is OfferResult.Rejected && r.reason.contains("smoke"))
        assertEquals("kept", "2026-11-07.1", s.current.version)
    }

    @Test fun `rejected body is never persisted`() {
        val p = InMemoryMappingPersistence()
        store(p).offer("not json")
        assertNull("nothing saved", p.loadMapping())
    }

    @Test fun `corrupt persisted copy falls back to bundled`() {
        val p = InMemoryMappingPersistence().apply { saveMapping("{corrupt") }
        val s = store(p).also { it.restore() }
        assertEquals("bundled", "2026-11-07.1", s.current.version)
    }

    @Test fun `game detail kill switch parses and gates the backend source`() {
        val text = bundledText.replace(""""killSwitch": { "espn": false, "sports": [], "leagues": [] }""", """"killSwitch": { "espn": false, "sports": [], "leagues": [], "gameDetail": { "all": false, "sports": ["Soccer"], "leagues": ["4387"] } }""")
        val m = (SportsMappingParser.parse(text) as MappingParseResult.Parsed).mapping
        assertFalse("soccer off", m.gameDetailEnabled("soccer", "4328"))
        assertFalse("nba league off", m.gameDetailEnabled("Basketball", "4387"))
        assertTrue("nfl on", m.gameDetailEnabled("American Football", "4391"))
        assertTrue("espn switch untouched", m.espnEnabled("soccer", "4328"))
    }

    @Test fun `profile falls back from sport name`() {
        val m = BundledSportsMapping.load()
        assertEquals("by league", "basketball", m.profileNameFor("4387", "Whatever"))
        assertEquals("by sport", "hockey", m.profileNameFor("999999", "Ice Hockey"))
        assertNull("unknown", m.profileNameFor("999999", "Curling"))
    }
}

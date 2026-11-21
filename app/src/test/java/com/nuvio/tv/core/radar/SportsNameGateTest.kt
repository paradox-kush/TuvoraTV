package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name pass runs over every channel of every playlist (55k on the owner's Onn) on each game open. Almost no
 * channel names either opponent or the competition, so [SportsEventMatchEngine.nameMayMatch] rejects those with
 * a few substring checks and only the survivors pay for the full [SportsEventMatchEngine.name] evaluation.
 * The gate must be SOUND: it may let a non-match through, it may never drop a name [SportsEventMatchEngine.name]
 * would have scored.
 */
class SportsNameGateTest {
    private val nba = RadarFixture(home = "Atlanta Hawks", away = "Charlotte Hornets", sport = "Basketball",
        event = "Atlanta Hawks vs Charlotte Hornets", league = "NBA", ts = "2026-11-20T23:00:00")
    private val ufc = RadarFixture(home = "", away = "", sport = "Fighting", event = "UFC 333 Volkanovski vs Evloev", ts = "2026-11-20T23:00:00")
    private val f1 = RadarFixture(sport = "Motorsport", event = "Las Vegas Grand Prix", ts = "2026-11-20T23:00:00")
    private val soccer = RadarFixture(home = "Atlético Madrid", away = "Real Sociedad", sport = "Soccer",
        event = "Atlético Madrid vs Real Sociedad", league = "La Liga", ts = "2026-11-20T20:00:00")

    private val corpus = listOf(
        "US: NBA TV", "NBA League Pass 07", "Hawks vs Hornets", "Hornets @ Hawks", "USA | Hawks-Hornets 19:00",
        "Atlanta Hawks vs Charlotte Hornets (11/20)", "ＮＢＡ ＴＶ", "Live | Hawks vs Hornets Highlights",
        "UFC 333 Volkanovski vs Evloev", "UFC 333 Prelims", "UFC 322", "F1: Las Vegas Grand Prix", "Las Vegas Grand Prix Qualifying",
        "Atletico Madrid vs Real Sociedad", "ATLÉTICO MADRID - REAL SOCIEDAD", "LaLiga TV", "La Liga 2", "DAZN LaLiga 1",
        "US: ESPN 2", "UK: Sky Sports Main Event", "FR: TF1 HD", "DE: Sky Sport Bundesliga 3", "Bally Sports Southeast",
        "WNBA League Pass", "NFL Network", "Hawks Nest Radio", "Charlotte Local News", "ES: Movistar LaLiga",
    )

    private fun prepared(f: RadarFixture, keywords: List<String>) = SportsEventMatchEngine.prepare(f, keywords)

    @Test
    fun `the gate never drops a name the full evaluation would score`() {
        for ((fixture, keywords) in listOf(nba to listOf("nba"), ufc to listOf("ufc"), f1 to listOf("f1", "formula 1"), soccer to listOf("la liga", "laliga"))) {
            val p = prepared(fixture, keywords)
            for (raw in corpus) {
                val scored = SportsEventMatchEngine.name(p, raw).strength > 0
                if (scored) assertTrue("gate dropped '$raw' for ${fixture.event}", SportsEventMatchEngine.nameMayMatch(p, raw))
            }
        }
    }

    @Test
    fun `channels naming neither opponent nor the competition are rejected without a full evaluation`() {
        val p = prepared(nba, listOf("nba"))
        for (raw in listOf("US: ESPN 2", "UK: Sky Sports Main Event", "FR: TF1 HD", "Hawks Nest Radio", "Charlotte Local News", "NFL Network")) {
            assertFalse("gate let '$raw' through", SportsEventMatchEngine.nameMayMatch(p, raw))
        }
        assertTrue("the game's own channel", SportsEventMatchEngine.nameMayMatch(p, "Atlanta Hawks vs Charlotte Hornets"))
        assertTrue("the competition's channel", SportsEventMatchEngine.nameMayMatch(p, "US: NBA TV"))
    }

    @Test
    fun `a 55k-channel lineup leaves only a handful of names for the full evaluation`() {
        val p = prepared(nba, listOf("nba"))
        val lineup = syntheticLineup(55_000)
        val survivors = lineup.count { SportsEventMatchEngine.nameMayMatch(p, it) }
        assertEquals("only the NBA-branded and the game's own channels survive", 1_100, survivors)
    }

    companion object {
        /** Realistic panel-shaped names: country prefix, brand, number, quality tag. 2% NBA-branded. */
        fun syntheticLineup(size: Int): List<String> {
            val brands = listOf("ESPN", "Sky Sports", "beIN Sports", "DAZN", "TSN", "Fox Sports", "Canal+ Sport", "Eurosport",
                "BBC One", "TF1", "Movistar", "Sport TV", "SuperSport", "Star Sports", "Willow", "TNT Sports", "CBS", "NBC",
                "Hulu", "Peacock", "Paramount+", "Prime Video", "Telemundo", "Univision", "RTL", "ZDF", "RAI", "Mediaset")
            val countries = listOf("US", "UK", "CA", "FR", "DE", "ES", "IT", "PT", "AR", "MX", "BR", "IN", "AU", "NL", "TR", "PL")
            val tags = listOf("HD", "FHD", "4K", "SD", "HEVC", "(backup)", "ᴴᴰ", "")
            return List(size) { i ->
                if (i % 50 == 0) "US: NBA League Pass ${i / 50}"
                else "${countries[i % countries.size]}: ${brands[(i / 7) % brands.size]} ${i % 97} ${tags[i % tags.size]}".trim()
            }
        }
    }
}

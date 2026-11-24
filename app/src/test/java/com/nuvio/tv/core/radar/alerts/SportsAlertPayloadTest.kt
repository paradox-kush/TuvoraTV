package com.nuvio.tv.core.radar.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SportsAlertPayloadTest {
    private fun parse(text: String) = SportsAlertPayload.parse(Json.parseToJsonElement(text) as JsonObject)

    private val full = """{"v":1,"key":"e1:score:1-0:1","eventId":"e1","kind":"score","sport":"soccer","leagueId":"4328","league":"Premier League",
        |"homeId":"133604","awayId":"133610","home":"Arsenal","away":"Chelsea","homeScore":1,"awayScore":0,"progress":"67'","corrected":false,"at":1800000000000}""".trimMargin()

    @Test
    fun `a v1 payload decodes`() {
        val a = parse(full)!!
        assertEquals("kind", AlertKind.Score, a.kind)
        assertEquals("scores", 1 to 0, a.homeScore to a.awayScore)
        assertEquals("server time", 1_800_000_000_000L, a.atMs)
        assertEquals("team id", "133604", a.homeId)
    }

    @Test
    fun `scores as strings and ids as numbers still decode`() {
        val a = parse("""{"v":1,"at":7,"key":"k","eventId":55,"kind":"final","sport":"baseball","home":"A","away":"B","homeScore":"3","awayScore":"2","leagueId":12}""")!!
        assertEquals("event id", "55", a.eventId)
        assertEquals("scores", 3 to 2, a.homeScore to a.awayScore)
        assertEquals("league id", "12", a.leagueId)
        assertEquals("at", 7L, a.atMs)
    }

    @Test
    fun `anything outside the contract is dropped and never throws`() {
        assertNull("future version", parse(full.replace("\"v\":1", "\"v\":2")))
        assertNull("unknown kind", parse(full.replace("\"score\"", "\"red_card\"")))
        assertNull("no key", parse("""{"v":1,"at":1,"eventId":"e","kind":"start","sport":"soccer","home":"A","away":"B"}"""))
        assertNull("no home", parse("""{"v":1,"at":1,"key":"k","eventId":"e","kind":"start","sport":"soccer"}"""))
        assertNotNull("non-numeric score is just no score", parse(full.replace("\"homeScore\":1", "\"homeScore\":\"x\"")))
        assertNull("non-numeric score value", parse(full.replace("\"homeScore\":1", "\"homeScore\":\"x\""))!!.homeScore)
    }

    @Test
    fun `v must be 1, at is required and away is optional`() {
        assertNull("no v", parse(full.replace("\"v\":1,", "")))
        assertNull("no at", parse(full.replace(",\"at\":1800000000000", "")))
        assertNull("non-numeric at", parse(full.replace("1800000000000", "\"soon\"")))
        val noAway = parse(full.replace("\"away\":\"Chelsea\",", ""))
        assertNotNull("away is optional", noAway)
        assertEquals("a missing away reads blank", "", noAway!!.away)
    }
}

package com.nuvio.tv.core.sports.detail

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonPathReaderTest {
    private val doc = Json.parseToJsonElement(
        """{"a":{"b":[{"c":1,"k":"x"},{"c":2,"k":"Y"},{"c":3,"k":"x","on":true}],"n":null},"s":"hi","l":[10,20,30]}""",
    )

    private fun texts(path: String) = JsonPathReader.read(doc, path).map { JsonPathReader.text(it) }

    @Test fun `walks keys and indexes`() = assertEquals("nested key then index", listOf("2"), texts("a.b[1].c"))
    @Test fun `wildcard fans out`() = assertEquals("every element", listOf("1", "2", "3"), texts("a.b[*].c"))
    @Test fun `negative index reads from the end`() = assertEquals("last", listOf("30"), texts("l[-1]"))
    @Test fun `filter matches case-insensitively`() = assertEquals("k=y", listOf("2"), texts("a.b[k=y].c"))
    @Test fun `filter on boolean text`() = assertEquals("on=true", listOf("3"), texts("a.b[on=true].c"))
    @Test fun `missing step yields nothing not an error`() = assertEquals("missing", emptyList<String?>(), texts("a.zzz.c[0].d"))
    @Test fun `json null is treated as missing`() = assertEquals("null", emptyList<String?>(), texts("a.n"))
    @Test fun `index out of range yields nothing`() = assertEquals("range", emptyList<String?>(), texts("l[7]"))
    @Test fun `wrong container type yields nothing`() = assertEquals("type", emptyList<String?>(), texts("s[0]") + texts("l.x") + texts("a.b.c"))
    @Test fun `null root and garbage paths never throw`() {
        assertEquals("null root", emptyList<Any>(), JsonPathReader.read(null, "a.b"))
        assertEquals("garbage", emptyList<Any>(), JsonPathReader.read(doc, "[[[===]]]..)("))
        assertEquals("blank path is the root", 1, JsonPathReader.read(doc, "").size)
    }
    @Test fun `text is null for containers`() = assertNull("object has no text", JsonPathReader.text(JsonPathReader.read(doc, "a").first()))
    @Test fun `tracked reads carry the nearest homeAway ancestor`() {
        val d = Json.parseToJsonElement("""{"t":[{"homeAway":"home","s":[{"v":"1"}]},{"homeAway":"away","s":[{"v":"2"},{"v":"3"}]}]}""")
        val m = JsonPathReader.readTracked(d, "t[*].s[*].v")
        assertEquals("sides", listOf("home", "away", "away"), m.map { it.homeAway })
        assertEquals("values", listOf("1", "2", "3"), m.map { JsonPathReader.text(it.value) })
    }
    @Test fun `matches the reference reader on the real nba fixture`() {
        val ev = SportsFixtures.espnEvent("nba")
        assertEquals(
            "linescores flatten like validate.py",
            listOf("Atlanta Hawks"),
            JsonPathReader.read(ev, "competitions[0].competitors[homeAway=home].team.displayName").map { JsonPathReader.text(it) },
        )
    }
}

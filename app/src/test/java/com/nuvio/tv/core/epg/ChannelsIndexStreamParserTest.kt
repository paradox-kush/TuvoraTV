package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure streaming-parser tests — twin of the KMP ChannelsIndexStreamParserTest (JUnit idiom). */
class ChannelsIndexStreamParserTest {
    private class Recorder : ChannelsIndexStreamParser.Handler {
        val events = ArrayList<String>()
        override fun onSourceBegin() { events.add("begin") }
        override fun onSourceScalar(key: String, value: String?) { events.add("scalar:$key=${value ?: "<null>"}") }
        override fun onChannel(channelJson: String) { events.add("ch:$channelJson") }
        override fun onSourceEnd() { events.add("end") }
    }

    private fun run(vararg chunks: String, scalar: Int = 8192, channel: Int = 65536, depth: Int = 32): List<String> {
        val r = Recorder()
        val p = ChannelsIndexStreamParser(scalar, channel, depth, r)
        chunks.forEach { p.accept(it) }
        p.finish()
        return r.events
    }

    @Test
    fun `emits metadata then each channel of each source`() {
        val doc = """{"generatedAt":"x","sources":[
            {"slug":"a","label":"A","countries":"United Kingdom","channels":[{"id":"1","names":["One"]},{"id":"2"}]},
            {"slug":"b","label":"B","countries":null,"channels":[]}
        ]}"""
        assertEquals(
            listOf(
                "begin", "scalar:slug=a", "scalar:label=A", "scalar:countries=United Kingdom",
                """ch:{"id":"1","names":["One"]}""", """ch:{"id":"2"}""", "end",
                "begin", "scalar:slug=b", "scalar:label=B", "scalar:countries=<null>", "end",
            ),
            run(doc),
        )
    }

    @Test
    fun `survives chunk splits at every boundary`() {
        val doc = """{"sources":[{"slug":"a","countries":"Spain","channels":[{"id":"x","names":["X","Y"]}]}]}"""
        val r = Recorder()
        val p = ChannelsIndexStreamParser(8192, 65536, 32, r)
        for (c in doc) p.accept(c.toString())
        p.finish()
        assertEquals(
            listOf("begin", "scalar:slug=a", "scalar:countries=Spain", """ch:{"id":"x","names":["X","Y"]}""", "end"),
            r.events,
        )
    }

    @Test
    fun `skips unknown fields of any JSON type`() {
        val doc = """{"note":{"nested":[1,2,{"k":"v"}]},"sources":[
            {"n":123,"slug":"a","flag":true,"obj":{"a":[1]},"arr":[{"x":1}],"channels":[{"id":"1"}],"trailing":null}
        ]}"""
        assertEquals(listOf("begin", "scalar:slug=a", """ch:{"id":"1"}""", "end"), run(doc))
    }

    @Test
    fun `handles fields in an unexpected order`() {
        val doc = """{"sources":[{"slug":"a","countries":"India","label":"A","channels":[{"id":"1"}]}]}"""
        assertEquals(
            listOf("begin", "scalar:slug=a", "scalar:countries=India", "scalar:label=A", """ch:{"id":"1"}""", "end"),
            run(doc),
        )
    }

    @Test
    fun `keeps brackets braces and commas that appear inside strings`() {
        val doc = """{"sources":[{"slug":"a","label":"x],[y}{z","channels":[{"id":"1","names":["a,b]"]}]}]}"""
        assertEquals(
            listOf("begin", "scalar:slug=a", "scalar:label=x],[y}{z", """ch:{"id":"1","names":["a,b]"]}""", "end"),
            run(doc),
        )
    }

    @Test
    fun `unescapes scalar values`() {
        val doc = """{"sources":[{"slug":"a","label":"A \"B\" \\ \/ \n A","channels":[]}]}"""
        assertEquals(
            listOf("begin", "scalar:slug=a", "scalar:label=A \"B\" \\ / \n A", "end"),
            run(doc),
        )
    }

    @Test
    fun `an oversized channel object throws`() {
        val doc = """{"sources":[{"slug":"a","channels":[{"id":"${"x".repeat(200)}"}]}]}"""
        assertThrows(EpgElementTooLargeException::class.java) { run(doc, channel = 64) }
    }

    @Test
    fun `an oversized scalar throws`() {
        val doc = """{"sources":[{"slug":"${"a".repeat(200)}","channels":[]}]}"""
        assertThrows(EpgElementTooLargeException::class.java) { run(doc, scalar = 32) }
    }

    @Test
    fun `over-deep nesting inside a channel throws`() {
        val deep = "[".repeat(40)
        val doc = """{"sources":[{"slug":"a","channels":[{"id":"1","x":$deep}]}]}"""
        assertThrows(EpgElementTooLargeException::class.java) { run(doc, depth = 8) }
    }

    @Test
    fun `a truncated sources array throws on finish`() {
        val p = ChannelsIndexStreamParser(8192, 65536, 32, Recorder())
        p.accept("""{"sources":[{"slug":"a","channels":[{"id":"1"}]}""")
        assertThrows(IllegalStateException::class.java) { p.finish() }
    }

    @Test
    fun `an absent sources array throws on finish`() {
        val p = ChannelsIndexStreamParser(8192, 65536, 32, Recorder())
        p.accept("""{"other":[{"x":1}]}""")
        assertThrows(IllegalStateException::class.java) { p.finish() }
    }

    @Test
    fun `an empty sources array yields nothing and finishes`() {
        assertEquals(emptyList<String>(), run("""{"sources":[]}"""))
    }
}

package com.nuvio.tv.core.telemetry.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QaMirrorTest {

    @Test
    fun `only the literal 1 enables the mirror`() {
        assertTrue(QaMirror.parseEnabled("1"))
        assertTrue(QaMirror.parseEnabled(" 1\n"))
        listOf(null, "", "0", "true", "11", "yes").forEach { assertFalse("'$it' leaves it off", QaMirror.parseEnabled(it)) }
    }

    @Test
    fun `a disabled mirror writes nothing`() {
        val lines = ArrayList<String>()
        QaMirror(false) { lines += it }.emit("ttff", mapOf("kind" to "live", "ms" to 1840))
        assertTrue("nothing written", lines.isEmpty())
    }

    @Test
    fun `an enabled mirror writes one json line with exact values`() {
        val lines = ArrayList<String>()
        QaMirror(true) { lines += it }.emit("ttff", linkedMapOf("kind" to "live", "engine" to "media3", "ms" to 1840L))
        assertEquals("one line", listOf("""{"ev":"ttff","kind":"live","engine":"media3","ms":1840}"""), lines)
    }

    @Test
    fun `a line is a single line and strings are escaped`() {
        val line = QaMirror.line("x", mapOf("a" to "he said \"hi\"\nnext", "b" to true, "c" to null))!!
        assertFalse("no newline inside", line.contains('\n'))
        assertEquals("""{"ev":"x","a":"he said \"hi\" next","b":true,"c":null}""", line)
    }

    @Test
    fun `a field with a forbidden name is dropped entirely`() {
        listOf("url", "token", "code", "state", "host", "stream_url").forEach {
            assertNull("$it must not be mirrored", QaMirror.line("ttff", mapOf(it to "x")))
        }
        val lines = ArrayList<String>()
        QaMirror(true) { lines += it }.emit("ttff", mapOf("url" to "http://x"))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a failing sink never throws into playback`() {
        QaMirror(true) { error("logcat gone") }.emit("ttff", mapOf("ms" to 1))
    }
}

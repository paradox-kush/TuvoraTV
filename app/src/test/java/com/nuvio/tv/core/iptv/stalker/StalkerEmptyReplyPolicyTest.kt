package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.stalker.StalkerEmptyReplyPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B02 (twin of Mobile's test): an empty reply after a fresh handshake is named by its shape. */
class StalkerEmptyReplyPolicyTest {

    private val series = mapOf("type" to "series", "action" to "get_categories")

    @Test
    fun `an empty body is the other device`() {
        assertEquals("null", Kind.HELD_ELSEWHERE, StalkerEmptyReplyPolicy.classify(null))
        assertEquals("blank", Kind.HELD_ELSEWHERE, StalkerEmptyReplyPolicy.classify("  \n"))
        assertTrue("cooldown", StalkerEmptyReplyPolicy.startsCooldown(Kind.HELD_ELSEWHERE))
        assertTrue("wording", StalkerEmptyReplyPolicy.message(Kind.HELD_ELSEWHERE, "P", series, null).contains("in use elsewhere"))
    }

    @Test
    fun `an empty envelope is a section the portal does not offer`() {
        for (body in listOf("""{"js":null}""", """{"js":false}""", """{"js":{}}""", """{}""")) {
            assertEquals(body, Kind.NOTHING_FOR_SECTION, StalkerEmptyReplyPolicy.classify(body))
        }
        assertFalse("no cooldown", StalkerEmptyReplyPolicy.startsCooldown(Kind.NOTHING_FOR_SECTION))
        val msg = StalkerEmptyReplyPolicy.message(Kind.NOTHING_FOR_SECTION, "My portal", series, """{"js":null}""")
        assertTrue(msg, msg.contains("Series"))
        assertFalse(msg, msg.contains("elsewhere"))
    }

    @Test
    fun `an html page is a portal error quoted without secrets`() {
        val body = "<html><body><b>Fatal error</b>: boom token=ABCDEF0123456789ABCDEF0123456789 mac=00:1A:79:58:B3:A6</body></html>"
        assertEquals("kind", Kind.PORTAL_ERROR, StalkerEmptyReplyPolicy.classify(body))
        val msg = StalkerEmptyReplyPolicy.message(Kind.PORTAL_ERROR, "P", series, body)
        assertTrue(msg, msg.contains("Fatal error"))
        assertFalse(msg, msg.contains("<b>"))
        assertFalse(msg, msg.contains("ABCDEF0123456789"))
        assertFalse(msg, msg.contains("58:B3:A6"))
    }

    @Test
    fun `the excerpt redacts and caps`() {
        assertEquals("encoded mac", "mac=<redacted> ok", StalkerEmptyReplyPolicy.excerpt("mac=00%3A1A%3A79%3A58%3AB3%3AA6 ok"))
        assertEquals("bare mac", "<mac> seen", StalkerEmptyReplyPolicy.excerpt("00:1A:79:58:B3:A6 seen"))
        assertEquals("credential", "password=<redacted>&x=1", StalkerEmptyReplyPolicy.excerpt("password=hunter2&x=1"))
        val long = StalkerEmptyReplyPolicy.excerpt("word ".repeat(100), max = 20)
        assertTrue(long, long.length <= 20 && long.endsWith("…"))
    }
}

package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catch-up preferences are primitives, which is the shape Gson cannot tell "missing" from
 * "false"/"0" for — the trap [withDecodeDefaults] exists to close. Every playlist on every device
 * predates these two fields, so the missing case is the ONLY case on first run after an update.
 */
class XtreamAccountCatchUpDecodeTest {

    private val gson = Gson()

    private val legacy = """
        [{"id":"http://host:8080|u1","name":"Panel","baseUrl":"http://host:8080",
          "username":"u1","password":"p1","enabled":true}]
    """.trimIndent()

    @Test
    fun `json written before catch-up shipped decodes with the defaults`() {
        val acc = decodeXtreamAccountsJson(gson, legacy).single()
        assertFalse("TS-first is the default", acc.preferM3u8CatchUp)
        assertEquals("no correction by default", 0, acc.catchUpCorrectionMinutes)
    }

    /**
     * The default must send exactly what Tuvora has always sent. A non-null offset takes
     * formatStart's panel-offset branch, so "0" has to read as "unset" rather than as "+0",
     * or every provider that works today would take a different code path on upgrade.
     */
    @Test
    fun `no correction means no offset at all rather than a zero offset`() {
        val acc = decodeXtreamAccountsJson(gson, legacy).single()
        assertNull("unset, not zero", acc.catchUpOffsetMs)
    }

    @Test
    fun `a stored correction survives the round trip as milliseconds`() {
        val json = gson.toJson(
            listOf(
                XtreamAccount(
                    id = "a", name = "n", baseUrl = "http://h", username = "u", password = "p",
                    preferM3u8CatchUp = true, catchUpCorrectionMinutes = -90,
                )
            )
        )
        val acc = decodeXtreamAccountsJson(gson, json).single()
        assertTrue("preference kept", acc.preferM3u8CatchUp)
        assertEquals("minutes kept", -90, acc.catchUpCorrectionMinutes)
        assertEquals("as milliseconds", -90 * 60_000L, acc.catchUpOffsetMs)
    }

    /** A deliberately-stored false/0 must survive, not be re-defaulted every read. */
    @Test
    fun `an explicitly stored default is not mistaken for a missing field`() {
        val json = """
            [{"id":"a","name":"n","baseUrl":"http://h","username":"u","password":"p","enabled":true,
              "preferM3u8CatchUp":false,"catchUpCorrectionMinutes":0}]
        """.trimIndent()
        val acc = decodeXtreamAccountsJson(gson, json).single()
        assertFalse("kept false", acc.preferM3u8CatchUp)
        assertEquals("kept 0", 0, acc.catchUpCorrectionMinutes)
    }
}

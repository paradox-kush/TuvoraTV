package com.nuvio.tv.data.local

import com.google.gson.Gson
import com.nuvio.tv.core.iptv.XtreamAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F10 — the channel-name clean-up toggle and the extra tags are stored on the playlist. Device pass
 * 2026-10-05 (emulator): "Clean up channel names" never turned On — every read rebuilds the account
 * field by field ([withDecodeDefaults], the Gson-Unsafe guard) and these two fields were not carried,
 * so the stored values were dropped on the very next read.
 */
class XtreamAccountChannelNamesDecodeTest {

    private val gson = Gson()

    @Test
    fun `clean-up toggle and tags survive the store round trip`() {
        val json = gson.toJson(
            listOf(
                XtreamAccount(
                    id = "a", name = "n", baseUrl = "http://h", username = "u", password = "p",
                    cleanChannelNames = true, channelNameTags = "VIP, |PRIME|",
                )
            )
        )
        val acc = decodeXtreamAccountsJson(gson, json).single()
        assertTrue("clean-up kept", acc.cleanChannelNames)
        assertEquals("tags kept", "VIP, |PRIME|", acc.channelNameTags)
    }

    @Test
    fun `a field-level update of the toggle is what the next read sees`() {
        val json = gson.toJson(listOf(XtreamAccount(id = "a", name = "n", baseUrl = "http://h", username = "u", password = "p")))
        val updated = applyAccountUpdate(gson, json, "a") { it.copy(cleanChannelNames = true) }
        assertTrue(decodeXtreamAccountsJson(gson, updated).single().cleanChannelNames)
    }

    @Test
    fun `json written before F10 reads as off with no tags`() {
        val legacy = """[{"id":"a","name":"n","baseUrl":"http://h","username":"u","password":"p","enabled":true}]"""
        val acc = decodeXtreamAccountsJson(gson, legacy).single()
        assertFalse(acc.cleanChannelNames)
        assertNull(acc.channelNameTags)
    }
}

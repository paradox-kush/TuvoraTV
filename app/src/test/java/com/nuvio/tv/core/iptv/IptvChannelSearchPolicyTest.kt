package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV twin of NuvioMobile's IptvChannelSearchPolicyTest (F01; JUnit: message first). */
class IptvChannelSearchPolicyTest {

    @Test
    fun `every query word must appear in any order ignoring case and punctuation`() {
        assertTrue("words", IptvChannelSearchPolicy.matches("bbc hd", "UK: BBC ONE HD"))
        assertTrue("any order", IptvChannelSearchPolicy.matches("one bbc", "UK | BBC-ONE"))
        assertFalse("missing word", IptvChannelSearchPolicy.matches("bbc two", "UK: BBC ONE HD"))
    }

    @Test
    fun `a blank query matches nothing`() {
        assertFalse("blank", IptvChannelSearchPolicy.matches("  ", "BBC One"))
        assertEquals("blank search", emptyList<String>(), IptvChannelSearchPolicy.search(listOf("BBC One"), " ") { it })
    }

    @Test
    fun `names starting with the query rank first then word starts then the rest`() {
        val names = listOf("CBBC", "UK: BBC One", "BBC Two", "Sky Sports", "ABBC News")
        assertEquals("ranking", listOf("BBC Two", "UK: BBC One", "CBBC", "ABBC News"), IptvChannelSearchPolicy.search(names, "bbc") { it })
    }

    @Test
    fun `ties keep the playlist order`() {
        val names = listOf("BBC Two", "BBC One", "BBC Four")
        assertEquals("stable", names, IptvChannelSearchPolicy.search(names, "bbc") { it })
    }

    @Test
    fun `letters outside ascii are matched`() {
        assertTrue("accents", IptvChannelSearchPolicy.matches("télé", "FR: Télé Loisirs"))
        assertTrue("arabic name", IptvChannelSearchPolicy.matches("mbc", "AR: MBC مصر"))
    }
}

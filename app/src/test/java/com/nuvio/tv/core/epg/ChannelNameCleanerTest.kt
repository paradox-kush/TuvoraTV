package com.nuvio.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F10 golden vectors — the SAME table as NuvioMobile's commonTest ChannelNameCleanerTest, ported by
 * hand (JUnit puts the message FIRST, then expected, actual). Change one, change both.
 */
class ChannelNameCleanerTest {

    private val golden = listOf(
        "UK: BBC One HD" to "BBC One",
        "UK | FHD | BBC One" to "BBC One",
        "|UK| SKY SPORTS F1 ᴴᴰ" to "SKY SPORTS F1",
        "[UK] Sky Atlantic FHD" to "Sky Atlantic",
        "(US) ESPN 2 4K" to "ESPN 2",
        "US| FOX SPORTS UHD" to "FOX SPORTS",
        "IN| Star Plus HD" to "Star Plus",
        "FR ▎ TF1 HEVC" to "TF1",
        "DE: Das Erste H.265" to "Das Erste",
        "EX-YU: RTS 1" to "RTS 1",
        "UKHD: ITV 2" to "ITV 2",
        "UK: ITV +1 HD" to "ITV +1",
        "BBC One (1080p) [Not 24/7]" to "BBC One",
        "Aathavan TV (720p) [Geo-blocked]" to "Aathavan TV",
        "Sky Sports Main Event ★" to "Sky Sports Main Event",
        "⚽ beIN SPORTS 1 ⚽" to "beIN SPORTS 1",
        "Sky Cinema ʀᴀᴡ" to "Sky Cinema",
        "CNN International" to "CNN International",
        "AL JAZEERA" to "AL JAZEERA",
        "Fox News (East)" to "Fox News (East)",
        "Disney Plus" to "Disney Plus",
        "HD" to "HD",
        "4K" to "4K",
        "UK: HD" to "HD",
        "Channel 4 HD+" to "Channel 4 HD+",
        "TOGGO plus -HD" to "TOGGO plus",
        "  " to "",
    )

    @Test
    fun `golden vectors with the default rules`() {
        for ((raw, want) in golden) {
            assertEquals("clean(\"$raw\")", want, ChannelNameCleaner.clean(raw))
        }
    }

    @Test
    fun `user tags strip whole words and literals`() {
        val rules = ChannelNameCleaner.Rules(userTags = ChannelNameCleaner.parseTags("VIP, |PRIME|\nmulti"))
        assertEquals("Sky Cinema", ChannelNameCleaner.clean("VIP Sky Cinema |PRIME|", rules))
        assertEquals("Multiverse TV", ChannelNameCleaner.clean("Multiverse TV MULTI", rules))
        assertEquals("VIPER TV", ChannelNameCleaner.clean("VIPER TV", rules))
    }

    @Test
    fun `each rule can be turned off`() {
        val raw = "UK: BBC One HD ★"
        assertEquals("BBC One HD", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripQuality = false)))
        assertEquals("UK: BBC One", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripCountryPrefix = false)))
        assertEquals("BBC One ★", ChannelNameCleaner.clean(raw, ChannelNameCleaner.Rules(stripDecorations = false)))
    }

    @Test
    fun `parseTags splits and dedupes`() {
        assertEquals(listOf("VIP", "|PRIME|", "RAW"), ChannelNameCleaner.parseTags(" VIP ,|PRIME|;RAW\n\nVIP "))
        assertEquals(emptyList<String>(), ChannelNameCleaner.parseTags(null))
    }
}

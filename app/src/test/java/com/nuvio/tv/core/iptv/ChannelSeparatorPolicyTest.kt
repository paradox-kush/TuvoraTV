package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B179 regression: `BG: ####… Bulgaria Channels` was listed as a tunable channel and failed with
 * "This device cannot play this stream". The policy must catch real-world divider shapes and must
 * NEVER hide a real channel, so the near-miss table is as important as the separator table.
 */
class ChannelSeparatorPolicyTest {

    private fun sep(name: String, epg: String? = null, archive: Boolean = false) =
        ChannelSeparatorPolicy.isSeparator(name, epg, archive)

    @Test
    fun `real world divider rows are separators`() {
        listOf(
            "BG: ################ Bulgaria Channels ################",
            "BG: #### Bulgaria Channels",
            "BG | ##### BULGARIA #####",
            "[BG] ##### BULGARIA",
            "#### UK ####",
            "######",
            "*** UK ***",
            "**** SPORT ****",
            "==== MOVIES ====",
            "UK: ======",
            "---- NEWS ----",
            "____ KIDS ____",
            "~~~~ RADIO ~~~~",
            "—— SPORT ——",
            "── SPORT ──",
            "━━━━━━━━",
            "★★★ UK ★★★",
            "••• DE •••",
            "BG: ----------",
            "  ##########  ",
            "+++++ PPV +++++",
        ).forEach { assertTrue("should be a separator: '$it'", sep(it)) }
    }

    @Test
    fun `real channels are never separators`() {
        listOf(
            "ZDF HD",
            "#0 Sport",
            "Channel #5",
            "#1 Hits",
            "BG: Bnt 2",
            "BG: Nova Tv",
            "UK: BBC One - HD",
            "Sky Sports - 1",
            "E! Entertainment",
            "A+E HD",
            "24/7 Friends",
            "Sport ###",
            "TV --",
            "-- Kids",
            "Fox*",
            "CNN+",
            "Disney_Junior",
            "M6 - Replay",
            "★ Premium",
            "·Arte",
            "5*",
            "BG: 24 Kitchen",
            "",
            "   ",
        ).forEach { assertFalse("must stay a channel: '$it'", sep(it)) }
    }

    @Test
    fun `a row that carries a guide id or an archive is a real channel whatever it is called`() {
        assertFalse(sep("#### UK ####", epg = "bbc1.uk"))
        assertFalse(sep("#### UK ####", archive = true))
        assertTrue("a blank epg id says nothing", sep("#### UK ####", epg = "  "))
    }

    @Test
    fun `withoutSeparators keeps order and every real row`() {
        data class Row(val name: String, val epg: String? = null)
        val rows = listOf(Row("BG: #### Bulgaria Channels"), Row("BG: Bnt 2"), Row("####"), Row("Nova Tv"), Row("*** x ***", "x.bg"))
        val kept = ChannelSeparatorPolicy.withoutSeparators(rows, { it.name }, { it.epg }, { false })
        assertEquals("order kept, every real row kept", listOf("BG: Bnt 2", "Nova Tv", "*** x ***"), kept.map { it.name })
    }
}

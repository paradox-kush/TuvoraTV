package com.nuvio.tv.core.radar

import org.junit.Assert.assertEquals
import org.junit.Test

/** B141. JUnit here, so the argument order is (message, expected, actual). */
class SportsGenericChannelPolicyTest {

    private val lineup = listOf(
        "UK: Sky Sports Main Event FHD",
        "DE: RTL HD",
        "|AR| beIN Sports 3",
        "FR: TF1",
        "US: ESPN 2",
        "Ｆｏｘ Ｓｐｏｒｔｓ 1",
        "NL: Ziggo Sport Select",
        "PL: Polsat",
        "BG: Arena Sport 4",
        "IT: Rai 1",
    )

    @Test
    fun `each channel name is normalised once per lookup, not once per marker`() {
        var calls = 0
        SportsGenericChannelPolicy.genericSportChannels(lineup, { it }) { calls++; SportsEventMatchEngine.normalize(it) }
        assertEquals(
            "one normalisation per channel (the old loop did up to 12 for every non-sports channel)",
            lineup.size,
            calls,
        )
    }

    @Test
    fun `the generic sports channels are the same ones the marker scan found`() {
        assertEquals(
            "same selection as normalising inside the marker loop",
            lineup.filter { n ->
                SportsGenericChannelPolicy.MARKERS.any { SportsEventMatchEngine.normalize(n).contains(it) }
            },
            SportsGenericChannelPolicy.genericSportChannels(lineup, { it }),
        )
    }

    @Test
    fun `sports brands are found and general channels are not`() {
        assertEquals(
            listOf(
                "UK: Sky Sports Main Event FHD", "|AR| beIN Sports 3", "US: ESPN 2",
                "Ｆｏｘ Ｓｐｏｒｔｓ 1", "NL: Ziggo Sport Select", "BG: Arena Sport 4",
            ),
            SportsGenericChannelPolicy.genericSportChannels(lineup, { it }),
        )
    }
}

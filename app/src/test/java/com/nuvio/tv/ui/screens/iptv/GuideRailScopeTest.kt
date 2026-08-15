package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.XtreamItemRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live guide's Favorites/Recent rails sit inside ONE provider's guide, but the stores behind
 * them keep a single flat profile-wide list spanning every playlist. Before this was scoped, the
 * rails showed other providers' channels — while the auto-resume a few lines above already
 * filtered by the same account. These pin the rule both rails now share.
 */
class GuideRailScopeTest {

    private val accA = "http://a.example|userA"
    private val accB = "http://b.example|userB"

    private fun liveIdsAcross() = listOf(
        XtreamItemRegistry.liveId(accA, 11),
        XtreamItemRegistry.liveId(accB, 22),
        XtreamItemRegistry.liveId(accA, 33),
    )

    @Test
    fun `a rail keeps only the selected account's channels`() {
        val kept = liveIdsAcross().filter { it.startsWith(XtreamItemRegistry.accountPrefix(accA)) }
        assertEquals("only account A's two channels survive", 2, kept.size)
        assertEquals(
            "the surviving ids are A's",
            listOf(XtreamItemRegistry.liveId(accA, 11), XtreamItemRegistry.liveId(accA, 33)),
            kept,
        )
    }

    @Test
    fun `another account's channels never leak into the rail`() {
        val kept = liveIdsAcross().filter { it.startsWith(XtreamItemRegistry.accountPrefix(accB)) }
        assertEquals("only account B's single channel survives", 1, kept.size)
        assertEquals(XtreamItemRegistry.liveId(accB, 22), kept.single())
    }

    /**
     * Account ids are "baseUrl|username", so one can be a literal prefix of another
     * ("…|user" vs "…|user2"). The trailing separator in [XtreamItemRegistry.accountPrefix] is
     * what stops the shorter account from swallowing the longer one's rows.
     */
    @Test
    fun `an account id that prefixes another does not swallow its channels`() {
        val shortAcc = "http://a.example|user"
        val longAcc = "http://a.example|user2"
        val ids = listOf(XtreamItemRegistry.liveId(shortAcc, 1), XtreamItemRegistry.liveId(longAcc, 2))
        val kept = ids.filter { it.startsWith(XtreamItemRegistry.accountPrefix(shortAcc)) }
        assertEquals("the longer account's channel must not appear", 1, kept.size)
        assertEquals(XtreamItemRegistry.liveId(shortAcc, 1), kept.single())
    }
}

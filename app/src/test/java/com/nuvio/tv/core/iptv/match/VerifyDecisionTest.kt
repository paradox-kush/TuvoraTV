package com.nuvio.tv.core.iptv.match


import com.nuvio.tv.core.iptv.match.VerifyVerdict.CONFIRMED
import com.nuvio.tv.core.iptv.match.VerifyVerdict.PROVISIONAL
import com.nuvio.tv.core.iptv.match.VerifyVerdict.REJECTED
import org.junit.Test
import org.junit.Assert.assertEquals

/** Acceptance rules distilled from the live-panel campaign — each case maps to a real incident. */
class VerifyDecisionTest {

    private fun decide(
        signalTmdb: Int? = null,
        signalYear: Int? = null,
        targetTmdb: Int = 155,
        targetYear: Int? = 2008,
        nameYear: Int? = null,
        exactTier: Boolean = true,
        via: String = "primary",
    ) = XtreamTmdbResolver.verifyDecision(VerifySignal(signalTmdb, signalYear), targetTmdb, targetYear, nameYear, exactTier, via)

    @Test
    fun matchingPanelTmdbIdConfirmsOutright() {
        assertEquals(CONFIRMED, decide(signalTmdb = 155))
        // even on inexact tiers and alt titles — the panel named the exact film
        assertEquals(CONFIRMED, decide(signalTmdb = 155, via = "alt+trunc", exactTier = false))
    }

    @Test
    fun mismatchingPanelTmdbIdRejectsUnlessTheYearContradictsIt() {
        // provider mis-tags exist ("Blind und Haesslich" carried Damage's id) — but so do
        // junk feeds: xsc.loruhon.com ships the constant tmdb_id 1111 for its ENTIRE catalog,
        // and the old reject-outright rule made every title on that panel unresolvable.
        // An exact-tier primary hit whose year confirms exactly downgrades to PROVISIONAL:
        // it wins only if no candidate CONFIRMS, so real-id panels are unaffected.
        assertEquals(PROVISIONAL, decide(signalTmdb = 1111, signalYear = 2008))
        // the XSC regression as observed live: Oppenheimer, exact name, exact year, id 1111
        assertEquals(PROVISIONAL, decide(signalTmdb = 1111, signalYear = 2023, targetTmdb = 872585, targetYear = 2023))
        // name-year is an acceptable stand-in when the info endpoint has no year
        assertEquals(PROVISIONAL, decide(signalTmdb = 1111, nameYear = 2008))
    }

    @Test
    fun provisionalDemandsExactYearAndExactPrimaryName() {
        // ±1 never bridges an id mismatch — "Wanted" (2008) vs "Wanted" (2009) are different films
        assertEquals(REJECTED, decide(signalTmdb = 1111, signalYear = 2009))
        // no year signal at all: the id mismatch stands
        assertEquals(REJECTED, decide(signalTmdb = 1111))
        // alt titles can't ride the override (O11CE's alt title "11" incident)
        assertEquals(REJECTED, decide(signalTmdb = 1111, signalYear = 2008, via = "alt"))
        // neither can inexact tiers
        assertEquals(REJECTED, decide(signalTmdb = 1111, signalYear = 2008, via = "primary+trunc", exactTier = false))
        // info year contradicting the target blocks the override even when nameYear agrees
        assertEquals(REJECTED, decide(signalTmdb = 1111, signalYear = 2017, nameYear = 2008))
    }

    @Test
    fun exactTierAllowsOffByOneYear() {
        assertEquals(CONFIRMED, decide(signalYear = 2008))
        assertEquals(CONFIRMED, decide(signalYear = 2009)) // panels get years wrong by one all the time
        assertEquals(REJECTED, decide(signalYear = 2011))
    }

    @Test
    fun inexactTiersDemandExactYear() {
        // trunc/skeleton/nodigit matches are guesses — a year off by one is not confirmation
        assertEquals(REJECTED, decide(signalYear = 2009, exactTier = false))
        assertEquals(CONFIRMED, decide(signalYear = 2008, exactTier = false))
    }

    @Test
    fun nameYearIsTheFallbackSignal() {
        assertEquals(CONFIRMED, decide(nameYear = 2008))
        // "Wanted (2008)" Jolie vs "Wanted (2009)" Salman Khan: same name, ±1 apart,
        // different films — inexact evidence must NOT bridge them
        assertEquals(REJECTED, decide(nameYear = 2009, exactTier = false))
    }

    @Test
    fun noSignalAcceptsOnlyExactPrimaryOrOriginal() {
        assertEquals(CONFIRMED, decide(via = "primary"))
        assertEquals(CONFIRMED, decide(via = "original"))
        // O11CE's alt title "11" once matched an unrelated show with nothing to refute it
        assertEquals(REJECTED, decide(via = "alt"))
        assertEquals(REJECTED, decide(via = "primary+trunc", exactTier = false))
    }

    @Test
    fun infoYearWinsOverNameYear() {
        // panel metadata said 2017 for a "Criminal Minds" entry targeted at the 2005 show
        assertEquals(REJECTED, decide(signalYear = 2017, nameYear = 2005))
    }
}

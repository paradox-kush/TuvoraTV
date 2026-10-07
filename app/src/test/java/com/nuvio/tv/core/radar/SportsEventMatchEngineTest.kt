package com.nuvio.tv.core.radar

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class SportsEventMatchEngineTest {
    private fun fixture(home: String="Indiana Pacers", away: String="Minnesota Timberwolves", sport: String="Basketball", event: String="$home vs $away", ts: String="2026-10-07T23:00:00") =
        RadarFixture(home=home,away=away,sport=sport,event=event,ts=ts)
    private fun name(raw: String, fixture: RadarFixture=fixture(), keywords: List<String> = listOf("nba")) = SportsEventMatchEngine.name(SportsEventMatchEngine.prepare(fixture,keywords),raw)

    @Test fun both_names_without_date_remain_possible() { assertEquals(MatchConfidence.POSSIBLE,name("Indiana Pacers vs Minnesota Timberwolves").confidence) }
    @Test fun scoped_nicknames_and_reversed_order_work() { assertEquals(60,name("Timberwolves vs Pacers").strength) }
    @Test fun generic_sports_never_eligible() { assertEquals(0,name("UK Sky Sports").strength) }
    @Test fun league_only_is_labelled_separately() { assertEquals(MatchConfidence.LEAGUE,name("NBA TV").confidence);assertEquals(20,name("NBA TV").strength) }
    @Test fun shared_cricket_word_cannot_match_both_teams() {
        val f=fixture("India Cricket","West Indies Cricket","Cricket")
        assertEquals(20,name("Sky Sports Cricket",f,listOf("cricket")).strength)
        assertEquals(60,name("West Indies vs India",f,listOf("cricket")).strength)
    }
    @Test fun same_city_does_not_identify_both_clubs() {
        assertEquals(0,name("ABC Sydney",fixture("Sydney FC","Western Sydney Wanderers","Soccer"),listOf("a league")).strength)
    }
    @Test fun participants_must_share_a_segment() { assertEquals(0,name("Pacers vs Bulls | Suns vs Timberwolves").strength) }
    @Test fun multi_game_blurbs_do_not_confirm_a_pair() { assertEquals(0,name("Pacers vs Bulls and Suns vs Timberwolves").strength) }
    @Test fun repeated_pair_text_is_not_multiple_different_games() { assertEquals(60,name("Pacers vs Timberwolves (Pacers vs Timberwolves)").strength) }
    @Test fun overlapping_city_alias_requires_explicit_pair_relation() {
        val f=fixture("Sydney FC","Western Sydney Wanderers","Soccer")
        assertEquals(60,name("Western Sydney Wanderers v Sydney",f,listOf("a league")).strength)
        assertEquals(0,name("Western Sydney Wanderers Live",f,listOf("a league")).strength)
    }
    @Test fun clock_colons_survive_segmentation() { assertEquals(60,name("19:00 Pacers vs Timberwolves").strength) }
    @Test fun replay_overrides_live() { assertEquals(0,name("Live | Pacers vs Timberwolves Highlights").strength) }
    @Test fun ended_event_is_rejected() { assertEquals(0,name("End | Pacers vs Timberwolves").strength) }
    @Test fun wrong_card_number_is_rejected() {
        val f=fixture("","","Fighting","UFC 333 Volkanovski vs Evloev")
        assertEquals(0,name("UFC 322 Volkanovski vs Evloev",f,listOf("ufc")).strength)
        assertEquals(0,name("UFC 3330",f,listOf("ufc")).strength)
    }
    @Test fun prelim_card_is_not_the_main_bout() {
        val f=fixture("","","Fighting","UFC 333 Volkanovski vs Evloev")
        assertTrue("card_segment_only" in name("UFC 333 FIGHT PASS PRELIMS",f,listOf("ufc")).reasons)
    }
    @Test fun grand_prix_words_alone_cannot_identify_a_race() {
        val f=fixture("","","Motorsport","Singapore Grand Prix Practice 1")
        assertEquals(0,name("Monaco Grand Prix Practice 1",f,listOf("formula 1")).strength)
        assertEquals(0,name("SR GRAND 1",f,listOf("formula 1")).strength)
    }
    @Test fun wrong_session_and_series_are_rejected() {
        val f=fixture("","","Motorsport","Singapore Grand Prix Practice 1")
        assertEquals(0,name("Singapore Grand Prix Qualifying",f,listOf("formula 1")).strength)
        assertEquals(0,name("MotoGP Singapore Grand Prix Practice 1",f,listOf("formula 1")).strength)
        assertEquals(60,name("F1 Singapore Grand Prix Practice 1",f,listOf("formula 1")).strength)
    }
    @Test fun old_explicit_dates_are_rejected() { assertEquals(0,name("Pacers vs Timberwolves | 2025-10-07").strength) }
    @Test fun local_day_boundary_stays_unresolved() { assertTrue("timezone_boundary_unresolved" in name("Pacers vs Timberwolves | 2026-10-08").reasons) }
    @Test fun matching_date_alone_does_not_claim_airing() { assertEquals(MatchConfidence.POSSIBLE,name("Pacers vs Timberwolves | 2026-10-07").confidence) }
    @Test fun ambiguous_day_month_is_not_guessed() { assertTrue("ambiguous_date" in name("Pacers vs Timberwolves | 07-10-2026").reasons) }
    @Test fun stale_yearless_word_date_is_rejected() {
        val f=fixture("Sydney FC","Western Sydney Wanderers","Soccer",ts="2026-10-16T08:00:00")
        assertEquals(0,name("Western Sydney Wanderers v Sydney FC | Sat 19th Oct 9:35AM UK",f,listOf("a league")).strength)
    }
    @Test fun invalid_dates_do_not_bind_an_event() { assertEquals(MatchConfidence.POSSIBLE,name("Pacers vs Timberwolves | 2026-02-31").confidence) }
    @Test fun cross_league_homonyms_are_rejected() { assertEquals(0,name("WNBA Pacers vs Timberwolves").strength) }
    @Test fun youth_and_women_variants_are_not_mens_first_teams() { assertEquals(0,name("Pacers U16 vs Timberwolves U16").strength) }
    @Test fun supplied_aliases_are_scoped_to_fixture_participants() {
        val f=fixture("Red Bull Bragantino","Mirassol","Soccer").copy(homeAliases=listOf("RB Bragantino"))
        assertEquals(60,name("RB Bragantino vs Mirassol",f).strength)
    }
    @Test fun unicode_accents_combining_marks_and_fullwidth_match() {
        val f=fixture("Remo","Grêmio","Soccer")
        assertEquals(60,name("Remo vs Gre\u0302mio",f).strength)
        assertEquals(60,name("Ｒｅｍｏ vs Gremio",f).strength)
    }
    @Test fun invisible_characters_do_not_split_identity() { assertEquals(60,name("Pa\u200Bcers vs Timberwolves").strength) }
    @Test fun non_latin_identity_survives_normalization() {
        assertEquals(60,name("東京 vs 大阪",fixture("東京","大阪","Soccer")).strength)
    }
    @Test fun city_and_rugby_nicknames_are_sport_scoped() {
        assertEquals(60,name("PITTSBURGH at WASHINGTON",fixture("Washington Capitals","Pittsburgh Penguins","Ice Hockey"),listOf("nhl")).strength)
        assertEquals(60,name("Roosters vs Knights",fixture("Sydney Roosters","Newcastle Knights","Rugby"),listOf("nrl")).strength)
    }
    @Test fun programme_needs_plausible_time_alignment() {
        val f=fixture();val p=SportsEventMatchEngine.prepare(f,listOf("nba"));val k=f.startEpochMs!!
        assertEquals(MatchConfidence.CONFIRMED,SportsEventMatchEngine.programme(p,"Pacers vs Timberwolves","",k-30*60_000,k+3*60*60_000).confidence)
        assertEquals(0,SportsEventMatchEngine.programme(p,"Pacers vs Timberwolves","",k+3*60*60_000,k+6*60*60_000).strength)
        assertEquals(0,SportsEventMatchEngine.programme(p,"Pacers vs Timberwolves","",k-8*60*60_000,k+2*60*60_000).strength)
    }
    @Test fun description_only_identity_does_not_confirm() {
        val f=fixture();val p=SportsEventMatchEngine.prepare(f,listOf("nba"));val k=f.startEpochMs!!
        assertEquals(MatchConfidence.POSSIBLE,SportsEventMatchEngine.programme(p,"NBA","Pacers vs Timberwolves",k,k+3*60*60_000).confidence)
    }
    @Test fun station_numbers_and_delays_are_identity() {
        assertFalse(SportsEventMatchEngine.compatibleStation("ESPN 2 HD","ESPN 1"))
        assertFalse(SportsEventMatchEngine.compatibleStation("Sky Sports +1 HD","Sky Sports"))
        assertTrue(SportsEventMatchEngine.compatibleStation("US ESPN 2 FHD","ESPN 2"))
    }
    @Test fun old_fixture_payloads_need_no_new_fields() {
        assertTrue(fixture().homeAliases.isEmpty());assertEquals(null,fixture().homeId)
    }
}

package com.nuvio.tv.core.telemetry

import com.nuvio.tv.core.iptv.PlaylistSaveError
import com.nuvio.tv.core.iptv.funnelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pure pieces of the `first_leave` step (contract Addendum 1): screen sanitising, the per-process visit, the funnel's
 * gating and note rules, the persisted-state codec. JUnit order: (message, expected, actual).
 */
class FirstLeaveTest {

    private val t0 = 1_000_000L

    private fun newState(): FirstRunState = checkNotNull(FirstRunFunnel.start(null, t0) { false }.state)

    // ---- last_screen ----

    @Test
    fun sanitizeKeepsOnlyLowerAlnumUnderscoreDotAndCapsLength() {
        assertEquals("spaces, case and slashes", "home_screen_foo", FirstLeavePolicy.sanitize("Home Screen/Foo"))
        assertEquals("a query string cannot ride along", "detail_x_1", FirstLeavePolicy.sanitize("detail?x=1"))
        assertEquals("hyphen becomes underscore", "clean_live", FirstLeavePolicy.sanitize("clean-live"))
        assertEquals("a dot survives", "a.b", FirstLeavePolicy.sanitize("a.b"))
        assertEquals("runs of junk collapse to one underscore", "a_b", FirstLeavePolicy.sanitize("a///   b"))
        assertEquals("length cap", 40, FirstLeavePolicy.sanitize("x".repeat(100)).length)
        assertEquals("empty", FirstLeavePolicy.OTHER, FirstLeavePolicy.sanitize(""))
        assertEquals("null", FirstLeavePolicy.OTHER, FirstLeavePolicy.sanitize(null))
        assertEquals("only junk", FirstLeavePolicy.OTHER, FirstLeavePolicy.sanitize("///  ?"))
    }

    @Test
    fun screenIdIsAFixedVocabularyAnythingElseIsOther() {
        assertEquals("a real route head", "home", FirstLeavePolicy.screenId("home"))
        assertEquals("hyphenated route head is normalised then matched", "clean_live", FirstLeavePolicy.screenId("clean-live"))
        assertEquals("the fallback name of an unknown destination", FirstLeavePolicy.OTHER, FirstLeavePolicy.screenId("dest_12345"))
        assertEquals("a title that somehow reached here", FirstLeavePolicy.OTHER, FirstLeavePolicy.screenId("Breaking Bad S01"))
        assertEquals("a channel-looking string", FirstLeavePolicy.OTHER, FirstLeavePolicy.screenId("espn_hd"))
        assertEquals("empty", FirstLeavePolicy.OTHER, FirstLeavePolicy.screenId(""))
    }

    @Test
    fun everyRouteHeadInScreenKtIsInTheVocabulary() {
        // Drift guard: a new Screen route must be added to KNOWN_SCREENS or it reports as `other`.
        val source = File("src/main/java/com/nuvio/tv/ui/navigation/Screen.kt").readText()
        val heads = Regex("""Screen\(\s*"([^"]+)"""").findAll(source).map { it.groupValues[1].substringBefore('/').substringBefore('?') }.toSet()
        assertTrue("parsed some routes from Screen.kt", heads.size > 30)
        for (head in heads) {
            assertEquals("route head '$head' must be in KNOWN_SCREENS", FirstLeavePolicy.sanitize(head), FirstLeavePolicy.screenId(head))
        }
        for (known in FirstLeavePolicy.KNOWN_SCREENS) {
            assertEquals("'$known' is already in sanitised form", known, FirstLeavePolicy.sanitize(known))
        }
    }

    @Test
    fun visitCountsDistinctScreensAndRemembersTheLast() {
        val v = FirstLeaveVisit(t0)
        v.screen("home"); v.screen("settings"); v.screen("home"); v.screen("addon_manager")
        val p = v.props(t0 + 45_000)
        assertEquals("last", "addon_manager", p["last_screen"])
        assertEquals("distinct", 3, p["screens_seen"])
        assertEquals("secs", 45, p["secs_in_app"])
    }

    @Test
    fun visitWithNoScreenReportsOtherAndZero() {
        val p = FirstLeaveVisit(t0).props(t0)
        assertEquals("last", "other", p["last_screen"])
        assertEquals("seen", 0, p["screens_seen"])
        assertEquals("secs", 0, p["secs_in_app"])
    }

    @Test
    fun visitUnknownScreenCountsButReportsOther() {
        val v = FirstLeaveVisit(t0)
        v.screen("home"); v.screen("dest_9")
        val p = v.props(t0)
        assertEquals("last", "other", p["last_screen"])
        assertEquals("both counted", 2, p["screens_seen"])
    }

    @Test
    fun secsInAppRestartsWhenTheAppReturnsToTheForeground() {
        val v = FirstLeaveVisit(t0)
        v.foreground(t0 + 600_000)
        assertEquals("only the current stint", 30, v.props(t0 + 630_000)["secs_in_app"])
        assertEquals("never negative", 0, v.props(t0)["secs_in_app"])
    }

    // ---- gating ----

    @Test
    fun firstLeaveEmitsOnceWithTheExtraProps() {
        val state = newState().copy(openedSourceForm = true, triedSave = true, saveError = "unreachable")
        val extra = mapOf<String, Any>("last_screen" to "xtream_hub", "secs_in_app" to 12, "screens_seen" to 4)
        val t = FirstRunFunnel.record(state, FirstRunStep.FIRST_LEAVE, t0 + 20_000, extra)
        val p = checkNotNull(t.emit)
        assertEquals("step", "first_leave", p["step"])
        assertEquals("schema", 1, p["schema_version"])
        assertEquals("launch_index", 1, p["launch_index"])
        assertEquals("secs_since_first_launch", 20, p["secs_since_first_launch"])
        assertEquals("last_screen", "xtream_hub", p["last_screen"])
        assertEquals("secs_in_app", 12, p["secs_in_app"])
        assertEquals("screens_seen", 4, p["screens_seen"])
        assertEquals("opened_source_form", true, p["opened_source_form"])
        assertEquals("tried_save", true, p["tried_save"])
        assertEquals("save_error", "unreachable", p["save_error"])
        assertNull("second time: nothing", FirstRunFunnel.record(checkNotNull(t.state), FirstRunStep.FIRST_LEAVE, t0 + 30_000, extra).emit)
    }

    @Test
    fun aMissingOrHostileExtraIsSanitised() {
        val t = FirstRunFunnel.record(newState(), FirstRunStep.FIRST_LEAVE, t0, mapOf("last_screen" to "ESPN HD / Live!", "secs_in_app" to -5))
        val p = checkNotNull(t.emit)
        assertEquals("not in the vocabulary", "other", p["last_screen"])
        assertEquals("negative clamps", 0, p["secs_in_app"])
        assertEquals("missing count", 0, p["screens_seen"])
        assertEquals("no failure", "none", p["save_error"])
        assertEquals("form", false, p["opened_source_form"])
        assertEquals("tried", false, p["tried_save"])
    }

    @Test
    fun noLeaveAfterFirstPlayback() {
        val played = checkNotNull(FirstRunFunnel.record(newState(), FirstRunStep.FIRST_PLAYBACK, t0, mapOf("kind" to "vod")).state)
        val t = FirstRunFunnel.record(played, FirstRunStep.FIRST_LEAVE, t0 + 1, emptyMap())
        assertNull("not a drop-off", t.emit)
        assertNull("and nothing to persist", t.state)
    }

    @Test
    fun noLeaveForAnExistingInstallOrBeforeStart() {
        val existing = checkNotNull(FirstRunFunnel.start(null, t0) { true }.state)
        assertNull("existing", FirstRunFunnel.record(existing, FirstRunStep.FIRST_LEAVE, t0, emptyMap()).emit)
        assertNull("before start", FirstRunFunnel.record(null, FirstRunStep.FIRST_LEAVE, t0, emptyMap()).emit)
    }

    @Test
    fun leaveBeforePlaybackDoesNotBlockLaterPlayback() {
        val left = checkNotNull(FirstRunFunnel.record(newState(), FirstRunStep.FIRST_LEAVE, t0, emptyMap()).state)
        assertEquals("playback after a leave still fires", "first_playback",
            FirstRunFunnel.record(left, FirstRunStep.FIRST_PLAYBACK, t0, mapOf("kind" to "vod")).emit!!["step"])
    }

    // ---- notes ----

    @Test
    fun notesSetTheFlagsAndOnlyReportChanges() {
        var s = newState()
        s = checkNotNull(FirstRunFunnel.note(s, FirstRunNote.SourceFormOpened))
        assertTrue("form", s.openedSourceForm)
        assertNull("unchanged => nothing to persist", FirstRunFunnel.note(s, FirstRunNote.SourceFormOpened))
        s = checkNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveTried))
        assertTrue("tried", s.triedSave)
        s = checkNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("wrong_credentials")))
        assertEquals("last failure", "wrong_credentials", s.saveError)
        s = checkNotNull(FirstRunFunnel.note(s, FirstRunNote.SaveFailed("UNREACHABLE")))
        assertEquals("the LAST failure wins, lower-cased", "unreachable", s.saveError)
    }

    @Test
    fun anUnknownFailureKindBecomesOther() {
        val s = checkNotNull(FirstRunFunnel.note(newState(), FirstRunNote.SaveFailed("http://evil.example/x?token=abc")))
        assertEquals("closed vocabulary", "other", s.saveError)
    }

    @Test
    fun aScreenNoteIsNotPersisted() {
        assertNull("per-process only", FirstRunFunnel.note(newState(), FirstRunNote.Screen("home")))
    }

    @Test
    fun notesAreIgnoredOnceTheLeaveGateIsClosed() {
        val left = checkNotNull(FirstRunFunnel.record(newState(), FirstRunStep.FIRST_LEAVE, t0, emptyMap()).state)
        assertNull("after first_leave", FirstRunFunnel.note(left, FirstRunNote.SaveTried))
        val played = checkNotNull(FirstRunFunnel.record(newState(), FirstRunStep.FIRST_PLAYBACK, t0, mapOf("kind" to "vod")).state)
        assertNull("after first_playback", FirstRunFunnel.note(played, FirstRunNote.SaveTried))
        val existing = checkNotNull(FirstRunFunnel.start(null, t0) { true }.state)
        assertNull("existing install", FirstRunFunnel.note(existing, FirstRunNote.SaveTried))
        assertNull("before start", FirstRunFunnel.note(null, FirstRunNote.SaveTried))
    }

    // ---- vocabulary ----

    @Test
    fun saveErrorVocabularyIsExactlyTheEnumNamesPlusOther() {
        val expected = PlaylistSaveError.values().map { it.name.lowercase() }.toSet() + "other"
        assertEquals("pinned to PlaylistSaveError so the two cannot drift", expected, FirstRunVocab.saveErrors)
        for (e in PlaylistSaveError.values()) assertEquals("kind of $e", e.name.lowercase(), e.funnelKind())
        assertFalse("none is not an error kind", "none" in FirstRunVocab.saveErrors)
    }

    // ---- codec ----

    @Test
    fun theNewFlagsSurviveARestartThroughTheCodec() {
        val s = newState().copy(openedSourceForm = true, triedSave = true, saveError = "provider_blocked")
        assertEquals("round trip", s, FirstRunStateCodec.decode(FirstRunStateCodec.encode(s)))
    }

    @Test
    fun aStateWrittenBeforeThisChangeStillDecodes() {
        val old = """{"existing":false,"firstLaunchAtMs":5,"launchIndex":2,"done":["first_launch","source_added"]}"""
        val s = checkNotNull(FirstRunStateCodec.decode(old))
        assertEquals("done kept", setOf("first_launch", "source_added"), s.done)
        assertFalse("form default", s.openedSourceForm)
        assertFalse("tried default", s.triedSave)
        assertNull("error default", s.saveError)
    }
}

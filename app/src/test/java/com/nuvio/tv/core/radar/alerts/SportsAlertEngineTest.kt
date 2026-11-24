package com.nuvio.tv.core.radar.alerts

import com.nuvio.tv.core.radar.ScoreSpoilerSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val t0 = 1_800_000_000_000L

/**
 * The engine end to end on a virtual clock: a goal arrives while live TV plays an unknown channel, is held, the
 * lookup answers (or does not), the policy re-evaluates, the timer fires, the banner shows and goes away.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsAlertEngineTest {
    private class FakeWatch : AlertWatchPort {
        var states: Map<String, WatchState> = emptyMap()
        /** null = never answers; else answers after the delay with this result. */
        var answerAfterMs: Long? = null
        var answerState: WatchState = WatchState.NoChannel
        var watchTarget: String? = null
        var resolveCalls = 0
        override fun statesFor(playing: Set<String>, nowMs: Long) = states
        override suspend fun resolve(eventId: String): Boolean {
            resolveCalls++
            val after = answerAfterMs ?: return false
            delay(after)
            states = states + (eventId to answerState)
            return true
        }
        override fun watchContentId(eventId: String, nowMs: Long) = watchTarget
    }

    private fun alert(kind: AlertKind = AlertKind.Score, key: String = "k1", eventId: String = "e1") = SportsAlert(
        key, eventId, kind, "soccer", "L1", "Premier League", "H1", "A1", "Arsenal", "Chelsea", 1, 0, null, false, t0,
    )

    private class Rig(scope: TestScope, val watch: FakeWatch, spoiler: ScoreSpoilerSettings) {
        val events = mutableListOf<Map<String, Any>>()
        val engine = SportsAlertEngine(
            scope = scope.backgroundScope,
            clock = { t0 + scope.currentTime },
            settings = { SportsAlertSettings(batchSeconds = 0, maxPerHour = 0, quietOn = false) },
            context = { now ->
                AlertContext(
                    nowMs = now, localMinuteOfDay = 720, playback = PlaybackContext.LiveTv, openGameEventId = null,
                    playingChannelIds = setOf("c1"), watchStates = watch.states, followedLeagueIds = emptySet(),
                    followedTeamIds = setOf("H1"), spoiler = spoiler, revealed = emptySet(),
                )
            },
            watch = watch,
            telemetry = { events += it },
        )
    }

    @Test
    fun `a goal on live tv is held, the lookup answers, and the banner shows at once`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = 2_000; answerState = WatchState.NoChannel }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert())
        runCurrent()
        assertNull("held for the check", rig.engine.banner.value)
        assertEquals("a pending watch check", 1, rig.engine.ledgerSnapshot().pending.size)
        advanceTimeBy(2_001)
        assertEquals("shown as soon as the lookup answered, long before the 6 s deadline", "Goal · Arsenal 1–0 Chelsea", rig.engine.banner.value?.title)
        assertTrue("no hold left", rig.engine.ledgerSnapshot().pending.isEmpty())
        assertEquals("a shown event was sent", "shown", rig.events.first { it["outcome"] == "shown" }["outcome"])
    }

    @Test
    fun `the lookup answering with the playing channel drops the goal`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = 1_000; answerState = WatchState.Channel(setOf("c1")) }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert())
        advanceTimeBy(1_500)
        assertNull("the viewer is watching this game", rig.engine.banner.value)
        assertEquals("counted, not sent", 1, rig.engine.ledgerSnapshot().counts["dropped:watching_this_game"])
        assertTrue("only count events reached telemetry", rig.events.all { it["outcome"] == "dropped" || it["outcome"] == "held" })
    }

    @Test
    fun `no answer by the deadline drops the score`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = null }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert())
        advanceTimeBy(5_900)
        assertNull("still waiting", rig.engine.banner.value)
        advanceTimeBy(200)
        assertNull("deadline: dropped", rig.engine.banner.value)
        assertEquals("watch_unknown", 1, rig.engine.ledgerSnapshot().counts["dropped:watch_unknown"])
        assertEquals("the lookup ran once", 1, watch.resolveCalls)
    }

    @Test
    fun `a final with no answer shows at the deadline without numbers`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = null }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert(AlertKind.Final))
        advanceTimeBy(6_100)
        assertEquals("no numbers", "Full time · Arsenal v Chelsea", rig.engine.banner.value?.title)
    }

    @Test
    fun `a banner auto-dismisses after ten seconds with no event, a user dismissal sends dismissed`() = runTest {
        val watch = FakeWatch().apply { states = mapOf("e1" to WatchState.NoChannel) }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert())
        runCurrent()
        assertNotNull("shown", rig.engine.banner.value)
        advanceTimeBy(10_001)
        assertNull("auto-dismissed", rig.engine.banner.value)
        assertTrue("a timeout is not a dismissal event", rig.events.none { it["action"] == "dismissed" })
        rig.engine.onAlert(alert(key = "k2"))
        runCurrent()
        rig.engine.dismissByUser()
        runCurrent()
        assertNull("gone", rig.engine.banner.value)
        assertEquals("the user's dismissal is an action", 1, rig.events.count { it["action"] == "dismissed" })
    }

    @Test
    fun `the action runs once and a banner replaced mid-press does not fire its successor`() = runTest {
        val watch = FakeWatch().apply { states = mapOf("e1" to WatchState.NoChannel, "e2" to WatchState.NoChannel) }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        val got = mutableListOf<AlertAction>()
        backgroundScope.launchCollect(rig.engine, got)
        rig.engine.onAlert(alert())
        runCurrent()
        val first = rig.engine.bannerSequence
        rig.engine.onAlert(alert(key = "k2", eventId = "e2"))
        runCurrent()
        assertTrue("replaced", rig.engine.bannerSequence > first)
        rig.engine.runAction(expectedSequence = first)
        runCurrent()
        assertTrue("the press began on the old banner: nothing fires", got.isEmpty())
        assertNotNull("the new banner is still up", rig.engine.banner.value)
        rig.engine.runAction(expectedSequence = rig.engine.bannerSequence)
        runCurrent()
        assertEquals("open game", listOf<AlertAction>(AlertAction.OpenGame("e2")), got)
        assertNull("the banner is gone after its action", rig.engine.banner.value)
    }

    @Test
    fun `watch is offered when the lookup finds a confirmed channel and never for a final`() = runTest {
        val watch = FakeWatch().apply { states = mapOf("e1" to WatchState.NoChannel); answerAfterMs = 100; watchTarget = "chan" }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        val got = mutableListOf<AlertAction>()
        backgroundScope.launchCollect(rig.engine, got)
        rig.engine.onAlert(alert())
        advanceTimeBy(200)
        assertEquals("watch target", "chan", rig.engine.watchContentId.value)
        rig.engine.runAction()
        runCurrent()
        assertEquals("watch action", listOf<AlertAction>(AlertAction.Watch("e1", "chan")), got)
        got.clear()
        val callsBefore = watch.resolveCalls
        rig.engine.onAlert(alert(AlertKind.Final, key = "f"))
        advanceTimeBy(50)
        assertNull("no lookup for a final banner", rig.engine.watchContentId.value)
        assertEquals("no extra lookup", callsBefore, watch.resolveCalls)
    }

    @Test
    fun `going to the background clears the holds and the banner`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = null }
        val rig = Rig(this, watch, ScoreSpoilerSettings())
        rig.engine.onAlert(alert())
        runCurrent()
        assertEquals("held", 1, rig.engine.ledgerSnapshot().pending.size)
        rig.engine.onBackground()
        runCurrent()
        assertTrue("cleared", rig.engine.ledgerSnapshot().pending.isEmpty())
        advanceTimeBy(7_000)
        assertNull("nothing resurfaces", rig.engine.banner.value)
    }

    @Test
    fun `the score delay is counted from receipt through the check`() = runTest {
        val watch = FakeWatch().apply { answerAfterMs = 3_000; answerState = WatchState.NoChannel }
        val rig = Rig(this, watch, ScoreSpoilerSettings(shown = true, delaySeconds = 30))
        rig.engine.onAlert(alert())
        advanceTimeBy(29_000)
        assertNull("still delayed", rig.engine.banner.value)
        advanceTimeBy(1_100)
        assertNotNull("shown at receipt + 30 s", rig.engine.banner.value)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(engine: SportsAlertEngine, into: MutableList<AlertAction>) {
        kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            engine.actions.collect { into += it }
        }
    }
}

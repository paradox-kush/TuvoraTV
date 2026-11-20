package com.nuvio.tv.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** The stateful half: once-gate across restarts, held-until-ready, consent at send time, concurrency, support code. */
class FirstRunFunnelServiceTest {

    private class MemoryStore(var value: String? = null, var writes: Int = 0) : FirstRunStateStore {
        override fun read() = value
        override fun write(value: String) { this.value = value; writes++ }
    }

    private class Rig(
        val store: MemoryStore = MemoryStore(),
        storeOverride: FirstRunStateStore? = null,
        var consent: Boolean = true,
        var now: Long = 1_000_000L,
        supportCodeExisted: Boolean = false,
    ) {
        val sent = CopyOnWriteArrayList<Map<String, Any>>()
        val events = CopyOnWriteArrayList<String>()
        val service = FirstRunFunnelService(
            store = storeOverride ?: store,
            nowMs = { now },
            consentEnabled = { consent },
            sink = { event, props -> events += event; sent += props },
            supportCodeExistedAtStart = supportCodeExisted,
        )
        fun ready() = apply { service.markSdkReady() }
        fun steps() = sent.map { it["step"] }
    }

    @Test
    fun newInstallEmitsFirstLaunchOnceAndNothingElseBeforeTheSdkIsReady() {
        val rig = Rig()
        rig.service.start { false }
        assertTrue("held until the SDK is up", rig.sent.isEmpty())
        rig.ready()
        assertEquals("then delivered", listOf<Any?>("first_launch"), rig.steps())
        assertEquals("event name", listOf("first_run_step"), rig.events)
        assertEquals("launch_index", 1, rig.sent[0]["launch_index"])
        assertEquals("secs", 0, rig.sent[0]["secs_since_first_launch"])
    }

    @Test
    fun aSecondStartInTheSameProcessIsIgnored() {
        val rig = Rig().ready()
        rig.service.start { false }
        rig.service.start { false }
        assertEquals("one first_launch", listOf<Any?>("first_launch"), rig.steps())
    }

    @Test
    fun everyStepFiresOnceAcrossARestart() {
        val store = MemoryStore()
        val first = Rig(store = store).ready()
        first.service.start { false }
        first.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "xtream", "via" to "manual"))
        first.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        assertEquals("source_added once in run 1", listOf<Any?>("first_launch", "source_added"), first.steps())

        // New process, same persisted state.
        val second = Rig(store = store, now = 1_090_000L).ready()
        second.service.start { error("the install was decided in run 1") }
        second.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        second.service.step(FirstRunStep.FIRST_CONTENT_LOADED, mapOf("surface" to "hub_categories"))
        second.service.step(FirstRunStep.FIRST_CONTENT_LOADED, mapOf("surface" to "hub_categories"))
        second.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "live", "engine" to "exo"))
        assertEquals("run 2 sends only the steps not yet done, once", listOf<Any?>("first_content_loaded", "first_playback"), second.steps())
        assertEquals("second launch index", 2, second.sent[0]["launch_index"])
        assertEquals("elapsed since first launch", 90, second.sent[0]["secs_since_first_launch"])

        val third = Rig(store = store).ready()
        third.service.start { error("decided") }
        FirstRunStep.values().forEach { third.service.step(it, mapOf("kind" to "vod")) }
        assertTrue("run 3: everything is done", third.sent.isEmpty())
    }

    @Test
    fun aSupportCodeAlreadyOnDiskMeansExistingAndSilent() {
        val rig = Rig(supportCodeExisted = true).ready()
        rig.service.start { error("not asked: the support code already decided it") }
        rig.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        rig.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "live", "engine" to "exo"))
        assertTrue("nothing sent", rig.sent.isEmpty())
        assertTrue("persisted as existing so it stays silent after a restart", FirstRunStateCodec.decode(rig.store.value)!!.existing)
    }

    @Test
    fun eachOtherExistingSignalSuppressesEverything() {
        // The four signals (support code above; playlist / add-on / watch history arrive through the probe): any true => silent.
        val rig = Rig().ready()
        rig.service.start { true }
        FirstRunStep.values().forEach { rig.service.step(it, emptyMap()) }
        assertTrue("silent", rig.sent.isEmpty())
        assertTrue("existing", FirstRunStateCodec.decode(rig.store.value)!!.existing)
    }

    @Test
    fun aProbeThatThrowsIsTreatedAsExistingNeverAFalseFirstLaunch() {
        val rig = Rig().ready()
        rig.service.start { error("DataStore unreadable") }
        assertTrue("silent", rig.sent.isEmpty())
        assertTrue("existing", FirstRunStateCodec.decode(rig.store.value)!!.existing)
    }

    @Test
    fun consentOffMarksDoneAndNeverSendsLater() {
        val rig = Rig(consent = false).ready()
        rig.service.start { false }
        rig.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        assertTrue("nothing sent while consent is off", rig.sent.isEmpty())
        val done = FirstRunStateCodec.decode(rig.store.value)!!.done
        assertTrue("first_launch marked done", "first_launch" in done)
        assertTrue("source_added marked done", "source_added" in done)

        rig.consent = true
        rig.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        rig.service.start { false }
        assertTrue("turning consent on does not release or repeat them", rig.sent.isEmpty())
        rig.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "live", "engine" to "exo"))
        assertEquals("a LATER step happening with consent on is sent", listOf<Any?>("first_playback"), rig.steps())
    }

    @Test
    fun consentIsReadWhenTheHeldEventIsSentNotWhenItHappened() {
        val rig = Rig(consent = true)
        rig.service.start { false } // held
        rig.consent = false          // user turns reports off before the SDK is up
        rig.ready()
        assertTrue("a held event is dropped when consent is off at send time", rig.sent.isEmpty())
    }

    @Test
    fun stepsBeforeStartAreDroppedNotQueued() {
        val rig = Rig().ready()
        rig.service.step(FirstRunStep.SOURCE_ADDED, mapOf("source_kind" to "m3u", "via" to "manual"))
        assertTrue("dropped", rig.sent.isEmpty())
        assertNull("no state invented", rig.store.value)
    }

    @Test
    fun aFailingStoreWriteKeepsTheInMemoryGate() {
        val throwing = object : FirstRunStateStore {
            override fun read(): String? = null
            override fun write(value: String) = throw java.io.IOException("disk full")
        }
        val rig = Rig(storeOverride = throwing).ready()
        rig.service.start { false }
        rig.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "vod", "engine" to "exo"))
        rig.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "vod", "engine" to "exo"))
        assertEquals("still once", listOf<Any?>("first_launch", "first_playback"), rig.steps())
    }

    @Test
    fun twoConcurrentCallsForTheSameStepEmitOneEvent() {
        repeat(200) { round ->
            val rig = Rig().ready()
            rig.service.start { false }
            val barrier = CyclicBarrier(8)
            val done = CountDownLatch(8)
            repeat(8) {
                thread {
                    barrier.await()
                    rig.service.step(FirstRunStep.FIRST_PLAYBACK, mapOf("kind" to "live", "engine" to "exo"))
                    done.countDown()
                }
            }
            done.await()
            assertEquals("round $round: exactly one first_playback", 1, rig.steps().count { it == "first_playback" })
        }
    }
}

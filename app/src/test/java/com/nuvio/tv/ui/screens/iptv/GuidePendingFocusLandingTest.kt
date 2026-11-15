package com.nuvio.tv.ui.screens.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device pass 1 of the TV IPTV redesign, findings 1 and 2: the entry landing and the group-open landing retired
 * their one-shot intent BEFORE asking for focus. The intent is a key of the effect doing the asking, so retiring it
 * recomposed and cancelled that effect before its first ask; focus stayed wherever a disposed row dropped it (the
 * Live TV tab). The intent is now retired only once the ask has run. JUnit: (message, expected, actual).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuidePendingFocusLandingTest {

    @Test
    fun `the intent is retired only after focus has been asked for`() = runBlocking {
        var retired = false
        val retiredAtAsk = mutableListOf<Boolean>()
        val landed = GuidePendingFocusLanding.land(
            request = { retiredAtAsk += retired; true },
            fallback = { false },
            retire = { retired = true },
            awaitFrame = {},
        )
        assertTrue("landed", landed)
        assertEquals("no ask ran after the intent was retired", listOf(false, false, false), retiredAtAsk)
        assertTrue("retired once landed", retired)
    }

    @Test
    fun `a landing cancelled mid-flight keeps its intent so the relaunched effect lands it`() = runTest(UnconfinedTestDispatcher()) {
        var retired = false
        val frame = CompletableDeferred<Unit>()
        val job = launch {
            GuidePendingFocusLanding.land(
                request = { false },
                fallback = { false },
                retire = { retired = true },
                awaitFrame = { frame.await() },
            )
        }
        // The effect's key changed (the list landed, a recomposition) before the first frame came.
        job.cancel()
        assertFalse("a cancelled landing never retires its intent", retired)
    }

    @Test
    fun `a row that never attaches falls back to the list and then retires`() = runBlocking {
        val events = mutableListOf<String>()
        val landed = GuidePendingFocusLanding.land(
            request = { false },
            fallback = { events += "fallback"; true },
            retire = { events += "retire" },
            maxFrames = 5,
            awaitFrame = {},
        )
        assertFalse("the row never took focus", landed)
        assertEquals("fallback first, then the intent is retired", listOf("fallback", "retire"), events)
    }
}

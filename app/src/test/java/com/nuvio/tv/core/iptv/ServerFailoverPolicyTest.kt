package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs [ServerFailoverGolden] (Step 0.3) — the SAME table NuvioMobile/NuvioDesktop's commonTest runs.
 * JUnit here, so every assertion is `assertEquals(message, expected, actual)`.
 */
class ServerFailoverPolicyTest {

    @Test
    fun `golden policy table`() {
        assertEquals("window constant", ServerFailoverGolden.W, ServerFailoverPolicy.MAIN_RETRY_WINDOW_MS)
        for (case in ServerFailoverGolden.policyCases) {
            var state = case.initial
            case.steps.forEachIndexed { i, step ->
                val label = "${case.name} / step $i"
                val order = ServerFailoverPolicy.order(state, case.serverCount, step.nowMs)
                assertEquals("$label: order", step.expectedOrder, order)
                val served = order.firstOrNull { it in step.up }
                assertEquals("$label: served", step.expectedServed, served)
                state = if (served == null) ServerFailoverPolicy.onAllFailed(state)
                else ServerFailoverPolicy.onSuccess(state, served, step.nowMs, case.serverCount)
                assertEquals("$label: state", step.expectedState, state)
            }
        }
    }

    @Test
    fun `golden failure classification`() {
        for ((kind, status, expected) in ServerFailoverGolden.classifierCases) {
            assertEquals("$kind $status", expected, FailoverFailureClassifier.shouldFailOver(FailoverFailure(kind, status)))
        }
    }

    @Test
    fun `backup label names the backup by its index`() {
        assertNull(ServerFailoverPolicy.backupLabel(0))
        assertEquals("Using backup server 1", ServerFailoverPolicy.backupLabel(1))
        assertEquals("Using backup server 3", ServerFailoverPolicy.backupLabel(3))
    }
}

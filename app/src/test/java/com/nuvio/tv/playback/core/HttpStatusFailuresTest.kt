package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** JUnit order: assertEquals(message, expected, actual). */
class HttpStatusFailuresTest {

    @Test
    fun `458 and 509 are a provider connection limit and stop the reconnect loop`() {
        listOf(429, 458, 509).forEach { status ->
            val failure = HttpStatusFailures.forStatus(status, FailurePhase.PLAYBACK)
            assertEquals("code for $status", FailureCode.PROVIDER_CONNECTION_LIMIT, failure.code)
            assertEquals("domain for $status", FailureDomain.AUTHORIZATION_PROVIDER_LIMIT, failure.domain)
            // Reconnecting into a full connection slot only hammers the provider.
            assertEquals("retryability for $status", Retryability.FATAL, failure.retryability)
            assertEquals("status kept for $status", status, failure.httpStatus)
        }
    }

    @Test
    fun `server errors and unmapped statuses keep their status for the reason line`() {
        assertEquals("503 keeps its status", 503, HttpStatusFailures.forStatus(503, FailurePhase.PLAYBACK).httpStatus)
        assertEquals("456 keeps its status", 456, HttpStatusFailures.forStatus(456, FailurePhase.PLAYBACK).httpStatus)
    }

    @Test
    fun `456 still reconnects with a fresh request`() {
        val failure = HttpStatusFailures.forStatus(456, FailurePhase.PLAYBACK)
        assertEquals("456 code unchanged", FailureCode.NETWORK_UNREACHABLE, failure.code)
        assertEquals("456 retryability unchanged", Retryability.RETRYABLE_WITH_FRESH_REQUEST, failure.retryability)
    }
}

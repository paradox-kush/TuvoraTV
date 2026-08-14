package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAuthRefusalTest {

    @Test
    fun `recognises a locally refused push`() {
        assertTrue(SyncNotAuthenticatedException().isSyncAuthRefusal())
    }

    @Test
    fun `recognises a server side permission denial`() {
        assertTrue(
            RuntimeException("permission denied for function sync_pull_watched_items")
                .isSyncAuthRefusal(),
        )
        assertTrue(RuntimeException("PostgrestRestException: 42501").isSyncAuthRefusal())
    }

    @Test
    fun `unwraps nested causes`() {
        val wrapped = IllegalStateException(
            "startup sync failed",
            RuntimeException("permission denied for function sync_pull_profiles"),
        )
        assertTrue(wrapped.isSyncAuthRefusal())
    }

    @Test
    fun `leaves retryable failures alone`() {
        assertFalse(RuntimeException("connection reset by peer").isSyncAuthRefusal())
        assertFalse(RuntimeException("timeout").isSyncAuthRefusal())
        assertFalse(RuntimeException("HTTP 503 Service Unavailable").isSyncAuthRefusal())
        // A bare 401 stays retryable on purpose: supabase-kt refreshes the access token underneath
        // us, which is what withJwtRefreshRetry depends on.
        assertFalse(RuntimeException("HTTP 401 Unauthorized").isSyncAuthRefusal())
        assertFalse(RuntimeException(null as String?).isSyncAuthRefusal())
    }
}

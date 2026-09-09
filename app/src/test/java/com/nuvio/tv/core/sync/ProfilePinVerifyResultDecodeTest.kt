package com.nuvio.tv.core.sync

import com.nuvio.tv.data.remote.supabase.SupabaseProfilePinVerifyResult
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * verify_profile_pin returns a single JSON OBJECT, e.g. {"unlocked":true,"retry_after_seconds":0}.
 *
 * ProfileSyncService.verifyProfilePin used decodeList, which (supabase-kt 3.6.0) deserializes the
 * body as a List and THROWS on an object — the catch then turned every verify, right PIN or wrong,
 * into Result.failure and the screen showed the generic verify-error. This pins the shape: the
 * object decodes as itself, and decoding the same body as a List throws (proving decodeList was the
 * wrong call).
 */
class ProfilePinVerifyResultDecodeTest {

    // Matches the supabase-kt postgrest default: unknown keys tolerated.
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the RPC object decodes as the result type`() {
        val decoded = json.decodeFromString<SupabaseProfilePinVerifyResult>(
            """{"unlocked":true,"retry_after_seconds":0}"""
        )
        assertTrue("a correct PIN unlocks", decoded.unlocked)
        assertEquals("no retry delay when unlocked", 0, decoded.retryAfterSeconds)
    }

    @Test
    fun `a rejecting object still decodes cleanly`() {
        val decoded = json.decodeFromString<SupabaseProfilePinVerifyResult>(
            """{"unlocked":false,"retry_after_seconds":30}"""
        )
        assertFalse("a wrong PIN does not unlock", decoded.unlocked)
        assertEquals("carries the lockout delay", 30, decoded.retryAfterSeconds)
    }

    @Test
    fun `decoding the same object AS A LIST throws — this was the bug`() {
        var threw = false
        try {
            json.decodeFromString<List<SupabaseProfilePinVerifyResult>>(
                """{"unlocked":true,"retry_after_seconds":0}"""
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("decodeList over a JSON object must throw", threw)
    }
}

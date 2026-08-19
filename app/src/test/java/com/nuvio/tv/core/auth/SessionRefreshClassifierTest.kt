package com.nuvio.tv.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * recover-not-eject: a failed session refresh signs the user out ONLY when GoTrue actually says the
 * refresh token is dead. A bare 4xx — a Cloudflare edge-403, a rate-limit, an ambiguous refusal —
 * must keep the session so the auto-refresh loop can recover, instead of ejecting mid-session.
 * The pre-fix classifier ejected on any 400/401/403 status; these tests pin the reason-based rule.
 * See supabase/auth-js#213.
 */
class SessionRefreshClassifierTest {

    @Test
    fun `genuine gotrue invalid-session replies eject`() {
        val genuine = listOf(
            "client request invalid: 400 bad request. text: {\"error\":\"invalid_grant\",\"error_description\":\"refresh_token_not_found\"}",
            "io.github.jan.supabase.auth.exception.authrestexception: refresh_token_not_found",
            "invalid refresh token: refresh token not found",
            "session_not_found",
        )
        for (message in genuine) {
            assertEquals(
                "must eject on a genuine invalid-session marker: $message",
                SessionRefreshResult.INVALID_SESSION,
                sessionRefreshResultForMessage(message.lowercase()),
            )
        }
    }

    @Test
    fun `a bare cloudflare or rate-limit or transient refusal keeps the session`() {
        val recoverable = listOf(
            "client request invalid: 403 forbidden. text: error 1020 access denied cloudflare",
            "client request invalid: 429 too many requests",
            "client request invalid: 400 bad request. text: bad gateway",
            "client request invalid: 401 unauthorized. text: jwt expired",
            "connection reset by peer",
            "unable to resolve host",
        )
        for (message in recoverable) {
            assertEquals(
                "must NOT eject (recover) on a non-invalid-session failure: $message",
                SessionRefreshResult.TRANSIENT_FAILURE,
                sessionRefreshResultForMessage(message.lowercase()),
            )
        }
    }
}

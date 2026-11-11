package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class AccountErrorMapperTest {
    private fun net(e: Throwable) = AccountErrorMapper.networkResId(e)

    @Test fun `DNS failure reads as no internet even when its text mentions the QR function`() {
        // B220: message text used to win, so this read as "QR login unavailable".
        val e = UnknownHostException("could not find the function start_tv_login_session")
        assertEquals(R.string.account_error_no_internet, net(e))
    }

    @Test fun `socket timeout reads as timed out`() {
        assertEquals(R.string.account_error_connection_timeout, net(SocketTimeoutException("read")))
    }

    @Test fun `connect failure reads as could not connect`() {
        assertEquals(R.string.account_error_connection_refused, net(ConnectException("invalid tv login")))
    }

    @Test fun `TLS failure reads as could not connect`() {
        assertEquals(R.string.account_error_connection_refused, net(SSLHandshakeException("handshake")))
    }

    @Test fun `a wrapped network cause still wins over the wrapper text`() {
        val e = RuntimeException("404 not found", UnknownHostException("host"))
        assertEquals(R.string.account_error_no_internet, net(e))
    }

    @Test fun `an unrelated exception has no network mapping`() {
        assertNull(net(IllegalStateException("incorrect pin")))
        assertNull(net(IOException("disk")))
    }

    @Test fun `HTTP status maps by code`() {
        fun status(code: Int) = AccountErrorMapper.statusResId(restException(code))
        assertEquals(R.string.account_error_not_authenticated, status(401))
        assertEquals(R.string.account_error_not_authenticated, status(403))
        assertEquals(R.string.account_error_service_unavailable, status(404))
        assertEquals(R.string.account_error_invalid_request, status(400))
        assertEquals(R.string.account_error_rate_limited, status(429))
        assertEquals(R.string.account_error_service_unavailable, status(503))
        assertNull(status(418))
    }
}

internal fun restException(code: Int): io.github.jan.supabase.exceptions.RestException {
    val response = io.mockk.mockk<io.ktor.client.statement.HttpResponse>(relaxed = true)
    io.mockk.every { response.status } returns io.ktor.http.HttpStatusCode.fromValue(code)
    return io.github.jan.supabase.exceptions.RestException("e", "d", response)
}

package com.nuvio.tv.core.telemetry

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorClassPolicyTest {
    // Stands in for an R8-renamed class: the runtime name is meaningless ("u7"), the JDK parent is not.
    private class u7(m: String) : java.io.IOException(m)

    @Test fun `jdk exception keeps its own name`() {
        assertEquals("UnknownHostException", ErrorClassPolicy.of(UnknownHostException("host.example")))
        assertEquals("SocketTimeoutException", ErrorClassPolicy.of(SocketTimeoutException("t")))
    }

    @Test fun `renamed class reports its nearest readable ancestor, not the minified name`() {
        assertEquals("IOException", ErrorClassPolicy.of(u7("secret")))
    }

    @Test fun `never contains message text`() {
        val out = ErrorClassPolicy.of(IllegalStateException("user:pass@host"))
        assertEquals("IllegalStateException", out)
    }
}

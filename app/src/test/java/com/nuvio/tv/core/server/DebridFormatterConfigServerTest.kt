package com.nuvio.tv.core.server

import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class DebridFormatterConfigServerTest {
    @Test
    fun `saves formatter templates as utf8 json`() {
        var saved: DebridFormatterSettings? = null
        val server = DebridFormatterConfigServer(
            currentSettingsProvider = {
                DebridFormatterSettings(
                    nameTemplate = "",
                    descriptionTemplate = ""
                )
            },
            onSettingsChanged = { saved = it }
        )
        val body = Gson().toJson(
            mapOf(
                "nameTemplate" to "🔥4K UHD ☁️",
                "descriptionTemplate" to "🍿 Loki ⚡Ready 🗣️"
            )
        )

        val response = server.serve(FakePostSession(body))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("🔥4K UHD ☁️", saved?.nameTemplate)
        assertEquals("🍿 Loki ⚡Ready 🗣️", saved?.descriptionTemplate)
    }

    private class FakePostSession(body: String) : NanoHTTPD.IHTTPSession {
        private val bytes = body.toByteArray(StandardCharsets.UTF_8)

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf(
            "content-length" to bytes.size.toString(),
            "content-type" to "application/json; charset=utf-8"
        )
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = "/api/settings"
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}

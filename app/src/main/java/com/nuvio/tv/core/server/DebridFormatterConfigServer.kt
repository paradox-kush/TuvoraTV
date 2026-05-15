package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.debrid.DebridStreamFormatterDefaults
import fi.iki.elonen.NanoHTTPD
import java.nio.charset.StandardCharsets

class DebridFormatterConfigServer(
    private val currentSettingsProvider: () -> DebridFormatterSettings,
    private val onSettingsChanged: (DebridFormatterSettings) -> Unit,
    port: Int = 8090
) : NanoHTTPD(port) {
    private val gson = Gson()
    private val settingsMapType = object : TypeToken<Map<String, String>>() {}.type

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.method == Method.GET && session.uri == "/" -> serveWebPage()
            session.method == Method.GET && session.uri == "/api/settings" -> serveSettings()
            session.method == Method.POST && session.uri == "/api/settings" -> handleSettingsUpdate(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveWebPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            DebridFormatterWebPage.html()
        )
    }

    private fun serveSettings(): Response {
        val response = DebridFormatterSettingsResponse(
            settings = currentSettingsProvider(),
            defaults = DebridFormatterSettings(
                nameTemplate = DebridStreamFormatterDefaults.NAME_TEMPLATE,
                descriptionTemplate = DebridStreamFormatterDefaults.DESCRIPTION_TEMPLATE
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(response))
    }

    private fun handleSettingsUpdate(session: IHTTPSession): Response {
        val body = readUtf8Body(session)
        val parsed = runCatching {
            gson.fromJson<Map<String, String>>(body, settingsMapType)
        }.getOrNull()
        val nameTemplate = parsed?.get("nameTemplate")?.takeIf { it.isNotBlank() }
        val descriptionTemplate = parsed?.get("descriptionTemplate")?.takeIf { it.isNotBlank() }
        if (nameTemplate == null || descriptionTemplate == null) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json; charset=utf-8",
                gson.toJson(mapOf("error" to "Both templates are required"))
            )
        }

        onSettingsChanged(
            DebridFormatterSettings(
                nameTemplate = nameTemplate,
                descriptionTemplate = descriptionTemplate
            )
        )
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(mapOf("status" to "saved")))
    }

    private fun readUtf8Body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return ""
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = session.inputStream.read(buffer, offset, length - offset)
            if (read <= 0) break
            offset += read
        }
        return String(buffer, 0, offset, StandardCharsets.UTF_8)
    }

    companion object {
        fun startOnAvailablePort(
            currentSettingsProvider: () -> DebridFormatterSettings,
            onSettingsChanged: (DebridFormatterSettings) -> Unit,
            startPort: Int = 8090,
            maxAttempts: Int = 10
        ): DebridFormatterConfigServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = DebridFormatterConfigServer(
                        currentSettingsProvider = currentSettingsProvider,
                        onSettingsChanged = onSettingsChanged,
                        port = port
                    )
                    server.start(SOCKET_READ_TIMEOUT, false)
                    return server
                } catch (e: Exception) {
                }
            }
            return null
        }
    }
}

data class DebridFormatterSettings(
    val nameTemplate: String,
    val descriptionTemplate: String
)

private data class DebridFormatterSettingsResponse(
    val settings: DebridFormatterSettings,
    val defaults: DebridFormatterSettings
)

package com.nuvio.tv.core.iptv.stalker

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Incremental parser for Stalker's bulk-EPG response, `{"js":{…,"data":{"<chId>":[prog,…],…}}}`,
 * fed by whatever chunks the transport hands over (boundaries can fall anywhere — mid-token,
 * mid-string, mid-escape). Twin of NuvioMobile's StalkerEpgStreamParser (kotlinx there, Gson here).
 *
 * [StalkerClient]'s bulk path used to read the WHOLE body into one String and then build a full
 * Gson tree over it — two copies of a response that a large panel can make enormous (a real
 * client trace pulled 174.5 MB from our research mock in one `get_epg_info`; see
 * research/iptv-catalog-loading.md). Here nothing is retained but the current programme element,
 * the current channel key, and the caller's insert batch, so peak memory no longer scales with
 * guide size. Each captured element is parsed with Gson, so field handling matches the old tree
 * walk exactly.
 *
 * Not thread-safe — one instance per response, driven from the transport's reader thread.
 */
internal class StalkerEpgStreamParser(
    private val onProgramme: (channelId: Int, title: String, descr: String, startMs: Long, endMs: Long) -> Unit,
) {
    private var inData = false
    private var afterData = false
    private var sawDataObject = false

    private var inString = false
    private var escaped = false

    private val lastString = StringBuilder()
    private var pendingDataColon = false

    private var dataDepth = 0
    private val keyBuf = StringBuilder()
    private var currentChannelId: Int? = null
    private var inProgrammeArray = false
    private var elementDepth = 0
    private val element = StringBuilder()

    var programmeCount = 0
        private set

    /** True when the body carried a parsed `data` object — the "portal supports it" signal. */
    val sawData: Boolean get() = sawDataObject

    fun feed(chunk: String) {
        for (c in chunk) {
            if (afterData) return
            if (inData) dataChar(c) else outerChar(c)
        }
    }

    private fun outerChar(c: Char) {
        if (inString) {
            when {
                escaped -> { lastString.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> inString = false
                else -> lastString.append(c)
            }
            return
        }
        when {
            c == '"' -> { inString = true; lastString.clear() }
            c == ':' -> pendingDataColon = lastString.toString() == "data"
            c == '{' && pendingDataColon -> {
                inData = true
                sawDataObject = true
                pendingDataColon = false
                dataDepth = 0
            }
            c.isWhitespace() -> Unit
            else -> if (c != ',') pendingDataColon = false
        }
    }

    private fun dataChar(c: Char) {
        if (inProgrammeArray && (elementDepth > 0 || c == '{')) {
            element.append(c)
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                return
            }
            when (c) {
                '"' -> inString = true
                '{' -> elementDepth++
                '}' -> {
                    elementDepth--
                    if (elementDepth == 0) emitElement()
                }
            }
            return
        }

        if (inString) {
            when {
                escaped -> { keyBuf.append(c); escaped = false }
                c == '\\' -> escaped = true
                c == '"' -> inString = false
                else -> keyBuf.append(c)
            }
            return
        }

        when (c) {
            '"' -> { inString = true; if (dataDepth == 0 && !inProgrammeArray) keyBuf.clear() }
            ':' -> if (dataDepth == 0) currentChannelId = keyBuf.toString().trim().toIntOrNull()
            '[' -> if (dataDepth == 0) { inProgrammeArray = true; dataDepth++ } else dataDepth++
            ']' -> { dataDepth--; if (dataDepth == 0) inProgrammeArray = false }
            '{' -> dataDepth++
            '}' -> {
                if (dataDepth == 0) { inData = false; afterData = true }
                else dataDepth--
            }
        }
    }

    private fun emitElement() {
        val text = element.toString()
        element.clear()
        val id = currentChannelId ?: return
        val obj = runCatching { JsonParser.parseString(text) as? JsonObject }.getOrNull() ?: return
        val startMs = (obj.str("start_timestamp")?.toLongOrNull() ?: 0L) * 1000
        val endMs = (obj.str("stop_timestamp")?.toLongOrNull() ?: 0L) * 1000
        if (endMs <= 0) return
        programmeCount++
        onProgramme(id, obj.str("name").orEmpty(), obj.str("descr").orEmpty(), startMs, endMs)
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
}

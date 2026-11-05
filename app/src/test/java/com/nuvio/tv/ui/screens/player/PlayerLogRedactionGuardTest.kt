package com.nuvio.tv.ui.screens.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B116: IPTV URLs carry `user/pass` (and Stalker `mac`/`token`), add-on URLs carry debrid keys, IPTV ids
 * embed the account username, and exception messages repeat the URL that failed. Every `Log.*(` call in
 * the VOD player (upstream's files included) that interpolates a url/uri, a content/video id, a command
 * line or an exception message must route it through `core.diagnostics.LogRedaction`, or log only a
 * host / hash / length. This scans the sources so a merge from upstream can't quietly bring one back.
 */
class PlayerLogRedactionGuardTest {

    @Test
    fun `player log lines never interpolate a url, id or exception message unredacted`() {
        val offenders = playerSources().flatMap { file ->
            unredactedInterpolations(file.readText()).map { (line, expr) -> "${file.name}:$line  \${$expr}" }
        }
        assertEquals("wrap these in LogRedaction.url(...) / LogRedaction.text(...):\n" + offenders.joinToString("\n"), 0, offenders.size)
    }

    @Test
    fun `the scanner itself flags a bare url and accepts a redacted one`() {
        val bad = "Log.d(TAG, \"open url=\$url id=\${stream.videoId} err=\${e.message}\")"
        val good = "Log.d(TAG, \"open url=\${LogRedaction.url(url)} host=\${url.toUri().host} err=\${LogRedaction.text(e.message)}\")"
        assertEquals(listOf("url", "stream.videoId", "e.message"), unredactedInterpolations(bad).map { it.second })
        assertEquals(emptyList<Pair<Int, String>>(), unredactedInterpolations(good))
    }

    private fun unredactedInterpolations(source: String): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, String>>()
        for (call in LOG_CALL.findAll(source)) {
            var i = call.range.last + 1
            var depth = 1
            while (depth > 0 && i < source.length) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            val text = source.substring(call.range.first, i)
            val line = source.substring(0, call.range.first).count { it == '\n' } + 1
            for (m in INTERPOLATION.findAll(text)) {
                val expr = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (SENSITIVE.containsMatchIn(expr) && !SAFE.containsMatchIn(expr)) hits += line to expr
            }
        }
        return hits
    }

    private fun playerSources(): List<File> {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory } ?: error("Cannot locate NuvioTV root")
        val dir = File(root, "app/src/main/java/com/nuvio/tv/ui/screens/player")
        // clean/ is the live pipeline (its own logging rules), not the VOD player.
        return dir.walkTopDown()
            .onEnter { it.name != "clean" }
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private companion object {
        val LOG_CALL = Regex("""\bLog\.[dewiv]\(""")
        val INTERPOLATION = Regex("""\$\{((?:[^{}]|\{[^{}]*\})*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")
        val SENSITIVE = Regex("""(?i)(url|uri\b|uri\.|videoid|contentid|\bcmd\b|location|\.message\b|localizedMessage)""")
        val SAFE = Regex(
            """(LogRedaction|safeHost|safeStartupHost|safePlaybackEventsHost|\.host\b|hashCode|\.length|\.size|""" +
                """isBlank|isNullOrBlank|isNotBlank|isEmpty|isNotEmpty|!=|==|startsWith|contains\(|\.scheme|Mime)"""
        )
    }
}

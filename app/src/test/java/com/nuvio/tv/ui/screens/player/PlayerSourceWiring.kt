package com.nuvio.tv.ui.screens.player

import java.io.File

/** Reads a VOD player source file for wiring guards (carried fork fixes, 2026-11-04 convergence). */
internal object PlayerSourceWiring {
    fun source(name: String): String = File(playerDir(), name).readText()

    /** Any main-source file by its path under `com/nuvio/tv/` (e.g. `ui/screens/iptv/player/IptvLivePlayback.kt`). */
    fun mainSource(relativePath: String): String = File(mainRoot(), relativePath).readText()

    fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        val next = Regex("""\n(internal |private )?(suspend )?fun """).find(text, start + signature.length)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    private fun mainRoot(): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory } ?: error("Cannot locate NuvioTV root")
        return File(root, "app/src/main/java/com/nuvio/tv")
    }

    private fun playerDir(): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir"))).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory } ?: error("Cannot locate NuvioTV root")
        return File(root, "app/src/main/java/com/nuvio/tv/ui/screens/player")
    }
}

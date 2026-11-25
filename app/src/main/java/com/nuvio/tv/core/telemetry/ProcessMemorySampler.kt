package com.nuvio.tv.core.telemetry

import android.os.Debug
import com.nuvio.tv.core.telemetry.playback.MemorySample
import com.nuvio.tv.core.telemetry.playback.ProcMemoryParser
import java.io.File
import java.io.FileInputStream

/**
 * Reads one [MemorySample] from this process: a few tiny `/proc` files and the runtime's own counters.
 * Android actual of the shared sample shape; never called on the main thread (the telemetry worker takes
 * a sample at most every 10 s while playing). Every read is best effort: an unreadable file leaves that
 * field unknown (-1) rather than invented.
 */
internal class ProcessMemorySampler(private val nowMs: () -> Long = System::currentTimeMillis) {

    fun sample(): MemorySample {
        val runtime = Runtime.getRuntime()
        val pssKb = readText("/proc/self/smaps_rollup")?.let(ProcMemoryParser::pssKb)
            // Older kernels have no smaps_rollup; the framework call does the same walk.
            ?: runCatching { Debug.getPss() }.getOrNull()
        return MemorySample(
            atMs = nowMs(),
            pssMb = pssKb?.let(ProcMemoryParser::kbToMb) ?: -1,
            javaMb = ((runtime.totalMemory() - runtime.freeMemory()) / MB).toInt(),
            nativeMb = (Debug.getNativeHeapAllocatedSize() / MB).toInt(),
            availMb = readText("/proc/meminfo")?.let(ProcMemoryParser::memAvailableKb)?.let(ProcMemoryParser::kbToMb) ?: -1,
            threads = readText("/proc/self/status")?.let(ProcMemoryParser::threads) ?: -1,
            fds = runCatching { File("/proc/self/fd").list()?.size }.getOrNull() ?: -1,
        )
    }

    // /proc files report length 0, so read the stream rather than the file size.
    private fun readText(path: String): String? =
        runCatching { FileInputStream(path).bufferedReader().use { it.readText() } }.getOrNull()

    private companion object {
        const val MB = 1024L * 1024L
    }
}

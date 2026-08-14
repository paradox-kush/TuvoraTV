package com.nuvio.tv.core.analytics

import android.system.Os
import java.io.File

/** Privacy-safe inventory of the current process' Linux file descriptors. */
internal data class FileDescriptorSnapshot(
    val total: Int,
    val softLimit: Long?,
    val sockets: Int,
    val pipes: Int,
    val eventFds: Int,
    val fences: Int,
    val dmaBuffers: Int,
    val devices: Int,
    val files: Int,
    val other: Int,
) {
    val utilizationPercent: Double?
        get() = softLimit?.takeIf { it > 0L }?.let { total * 100.0 / it }

    fun inventory(): String = listOf(
        "socket=$sockets",
        "pipe=$pipes",
        "eventfd=$eventFds",
        "fence=$fences",
        "dmabuf=$dmaBuffers",
        "device=$devices",
        "file=$files",
        "other=$other",
    ).joinToString(",")
}

internal enum class FileDescriptorKind {
    SOCKET,
    PIPE,
    EVENT_FD,
    FENCE,
    DMA_BUFFER,
    DEVICE,
    FILE,
    OTHER,
}

/** Classifies only broad resource types; raw descriptor targets never leave the device. */
internal fun classifyFileDescriptorTarget(target: String?): FileDescriptorKind {
    val value = target?.lowercase().orEmpty()
    return when {
        value.startsWith("socket:[") -> FileDescriptorKind.SOCKET
        value.startsWith("pipe:[") -> FileDescriptorKind.PIPE
        "sync_file" in value || "fence" in value -> FileDescriptorKind.FENCE
        "dmabuf" in value || "dma_heap" in value -> FileDescriptorKind.DMA_BUFFER
        "eventfd" in value || "eventpoll" in value || "timerfd" in value ||
            "signalfd" in value || "inotify" in value -> FileDescriptorKind.EVENT_FD
        value.startsWith("/dev/") -> FileDescriptorKind.DEVICE
        value.startsWith("/") || value.startsWith("memfd:") -> FileDescriptorKind.FILE
        else -> FileDescriptorKind.OTHER
    }
}

internal fun parseOpenFileSoftLimit(limits: String): Long? {
    val line = limits.lineSequence().firstOrNull { it.startsWith("Max open files") } ?: return null
    return Regex("^Max open files\\s+(\\d+)\\s+")
        .find(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
}

internal fun readFileDescriptorSnapshot(): FileDescriptorSnapshot? = runCatching {
    val descriptors = File("/proc/self/fd").listFiles()?.toList() ?: return@runCatching null
    val counts = FileDescriptorKind.entries.associateWith { 0 }.toMutableMap()
    descriptors.forEach { descriptor ->
        val target = runCatching { Os.readlink(descriptor.absolutePath) }.getOrNull()
        val kind = classifyFileDescriptorTarget(target)
        counts[kind] = counts.getValue(kind) + 1
    }
    val softLimit = runCatching {
        File("/proc/self/limits").bufferedReader().use { parseOpenFileSoftLimit(it.readText()) }
    }.getOrNull()
    FileDescriptorSnapshot(
        total = descriptors.size,
        softLimit = softLimit,
        sockets = counts.getValue(FileDescriptorKind.SOCKET),
        pipes = counts.getValue(FileDescriptorKind.PIPE),
        eventFds = counts.getValue(FileDescriptorKind.EVENT_FD),
        fences = counts.getValue(FileDescriptorKind.FENCE),
        dmaBuffers = counts.getValue(FileDescriptorKind.DMA_BUFFER),
        devices = counts.getValue(FileDescriptorKind.DEVICE),
        files = counts.getValue(FileDescriptorKind.FILE),
        other = counts.getValue(FileDescriptorKind.OTHER),
    )
}.getOrNull()

internal fun appendMpvLifecycleHistory(
    existing: String?,
    timestampMs: Long,
    instanceId: Long,
    stage: String,
    activeInstances: Int,
    maxChars: Int = 768,
): String {
    val entry = "$timestampMs:$instanceId:$stage:$activeInstances"
    return (existing?.takeIf { it.isNotBlank() }?.let { "$it,$entry" } ?: entry)
        .takeLast(maxChars.coerceAtLeast(0))
        .trimStart(',')
}

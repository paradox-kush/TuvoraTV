// Ported from homelessbrian/NuvioPlusIPTV @ a86756891 (GPL-3.0), MultiviewSessionCore.kt (MultiviewBufferBudget).
package com.nuvio.tv.core.multiview

/** Download-ahead targets share one budget, including empty/hidden slots. */
internal object MultiviewBufferBudget {
    private const val MIB = 1024L * 1024L
    const val MAX_PANES = 4

    fun totalBytes(effectiveHeapMb: Long): Int =
        (effectiveHeapMb.coerceAtLeast(1).coerceAtMost(4096) * MIB * 3 / 16)
            .coerceIn(24 * MIB, 96 * MIB).toInt()

    fun bytesPerPane(totalBytes: Int): Int = totalBytes / MAX_PANES
}

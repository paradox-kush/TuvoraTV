package com.nuvio.tv.core.memory

import android.content.Context
import com.nuvio.tv.core.contracts.DemuxerBudgetBytes
import com.nuvio.tv.core.contracts.PlayerMemoryBudget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Resolves the player memory budget from the device tier ([AndroidMemoryTierProbe], cached). */
@Singleton
class AndroidPlayerMemoryBudget @Inject constructor(
    @ApplicationContext private val context: Context,
) : PlayerMemoryBudget {
    private fun tier(): MemoryTier = AndroidMemoryTierProbe.tier(context)
    override fun demuxerBytes(): DemuxerBudgetBytes = demuxerBytesFor(tier())
    override fun exoTargetBufferBytes(maxHeapBytes: Long): Int = exoTargetBufferBytesFor(tier(), maxHeapBytes)
    override fun isLowMemoryTier(): Boolean = tier() == MemoryTier.LOW
}

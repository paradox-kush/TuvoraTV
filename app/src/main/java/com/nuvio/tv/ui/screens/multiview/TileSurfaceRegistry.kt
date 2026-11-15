package com.nuvio.tv.ui.screens.multiview

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle

/**
 * The video surface owners of the multiview tiles, one [FrameLayout] per tile id. A tile id follows its tile
 * (a removal moves the tile down a slot, its surface stays), and a reopened tile gets a new id, so a host that is
 * still releasing never shares a surface owner with its replacement (the host requires an empty owner).
 */
internal class TileSurfaceRegistry(private val appContext: Context) {
    var activity: Activity? = null
    var lifecycle: Lifecycle? = null

    private val owners = HashMap<Int, FrameLayout>()

    fun owner(tileId: Int): FrameLayout = owners.getOrPut(tileId) {
        FrameLayout(activity ?: appContext).apply { keepScreenOn = true }
    }

    /** Forgets the surfaces of tiles that are gone. A host still releasing keeps its own reference. */
    fun retain(tileIds: Set<Int>) {
        owners.keys.retainAll(tileIds)
    }
}

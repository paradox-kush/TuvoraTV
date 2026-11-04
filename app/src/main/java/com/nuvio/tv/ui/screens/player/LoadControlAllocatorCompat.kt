package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.Allocator

/**
 * Fork media3 1.11 adaptation. Upstream's player reads `DefaultLoadControl.allocator`, which in
 * media3 1.8 resolved to `getAllocator()`; 1.11 replaced that with `getAllocator(PlayerId)` and the
 * Kotlin property now names the private field. The load control owns exactly one allocator (the one
 * passed to `Builder.setAllocator`, or its default), so read that field. Diagnostics only: null when
 * the field cannot be read.
 */
private val allocatorField by lazy {
    runCatching {
        DefaultLoadControl::class.java.getDeclaredField("allocator").apply { isAccessible = true }
    }.getOrNull()
}

@OptIn(UnstableApi::class)
internal val DefaultLoadControl.ownAllocator: Allocator?
    get() = runCatching { allocatorField?.get(this) as? Allocator }.getOrNull()

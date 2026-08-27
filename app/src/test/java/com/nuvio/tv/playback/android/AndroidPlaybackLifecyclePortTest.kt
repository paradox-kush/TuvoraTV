package com.nuvio.tv.playback.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.nuvio.tv.playback.core.PlaybackLifecycleEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidPlaybackLifecyclePortTest {
    @Test
    fun `collector receives initial state and visible lifecycle transitions once`() = runTest {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        val values = async { AndroidPlaybackLifecyclePort(owner.lifecycle).events().take(4).toList() }
        runCurrent()

        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCurrent()

        assertEquals(
            listOf(
                PlaybackLifecycleEvent.INACTIVE,
                PlaybackLifecycleEvent.ACTIVE,
                PlaybackLifecycleEvent.INACTIVE,
                PlaybackLifecycleEvent.DESTROYED,
            ),
            values.await(),
        )
    }

    @Test
    fun `collector starts active when owner is already started`() = runTest {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.STARTED

        assertEquals(
            listOf(PlaybackLifecycleEvent.ACTIVE),
            AndroidPlaybackLifecyclePort(owner.lifecycle).events().take(1).toList(),
        )
    }

    @Test
    fun `collector starts destroyed and remains terminal`() = runTest {
        val owner = TestOwner()
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals(
            listOf(PlaybackLifecycleEvent.DESTROYED),
            AndroidPlaybackLifecyclePort(owner.lifecycle).events().take(1).toList(),
        )
    }

    private class TestOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }
}

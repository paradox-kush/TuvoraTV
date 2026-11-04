package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSearchSessionCacheForkTest {
    private fun key(profile: Int = 1) = StreamSearchRequestKey(profile, "movie", "tt1", null, null, "configuration")
    private val groups = listOf(AddonStreams("fixture", null, emptyList()))

    @Test fun twoObserversShareWorkAndCancellingOneDoesNotCancelTheOther() = runTest {
        val cache = StreamSearchSessionCache(backgroundScope, nowMs = { testScheduler.currentTime })
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val producer = { flow<NetworkResult<List<AddonStreams>>> { calls++; gate.await(); emit(NetworkResult.Success(groups)) } }
        val first = async { cache.observe(key(), false, producer).toList() }
        val second = async { cache.observe(key(), false, producer).toList() }
        runCurrent()
        assertEquals(1, calls)
        first.cancel()
        gate.complete(Unit)
        assertEquals(groups, (second.await().last() as NetworkResult.Success).data)
        assertEquals(1, calls)
    }

    @Test fun aProducerCancellationDoesNotLeaveAPoisonedReusableEntry() = runTest {
        val cache = StreamSearchSessionCache(backgroundScope, nowMs = { testScheduler.currentTime })
        cache.observe(key(), false) { flow { throw CancellationException("source replaced") } }.toList()
        var calls = 0
        val results = cache.observe(key(), false) { flow { calls++; emit(NetworkResult.Success(groups)) } }.toList()
        assertEquals(1, calls)
        assertEquals(groups, (results.last() as NetworkResult.Success).data)
    }

    @Test fun explicitRefreshReplacesTheOldSessionAndOldCleanupCannotRemoveTheNewOne() = runTest {
        val cache = StreamSearchSessionCache(backgroundScope, nowMs = { testScheduler.currentTime })
        val first = async { cache.observe(key(), false) { flow { emit(NetworkResult.Loading); awaitCancellation() } }.toList() }
        runCurrent()
        cache.observe(key(), true) { flow { emit(NetworkResult.Success(groups)) } }.toList()
        first.await()
        runCurrent()
        assertEquals(groups, cache.peek(key()))
    }

    @Test fun switchingProfilesDoesNotReuseTheOtherProfilesSources() = runTest {
        val cache = StreamSearchSessionCache(backgroundScope, nowMs = { testScheduler.currentTime })
        var calls = 0
        repeat(2) { profile ->
            cache.observe(key(profile), false) { flow { calls++; emit(NetworkResult.Success(groups)) } }.toList()
        }
        assertEquals(2, calls)
        assertEquals(null, cache.peek(key(0)))
    }
}

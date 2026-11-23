package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TraktApi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TV twin of upstream Mobile 1e4157492 (unbounded Trakt library refresh queue). Automatic
 * `ensureFresh()` callers (every observed list, tab and per-item membership) used to queue behind a
 * running refresh; when it failed with nothing cached, EACH queued waiter then ran its own full
 * fetch, one after the other - offline that is a pile of retained waiters and repeated requests.
 * An automatic caller must not queue behind a refresh that is already running.
 *
 * NOTE: JUnit argument order is assertX(message, expected, actual) here.
 */
class TraktLibraryRefreshQueueTest {
    @Test
    fun `automatic callers do not queue behind a running refresh`() = runBlocking {
        val calls = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val firstCall = CompletableDeferred<Unit>()
        val auth = mockk<TraktAuthService>()
        coEvery { auth.executeAuthorizedRequest<Any>(any()) } coAnswers {
            calls.incrementAndGet()
            firstCall.complete(Unit)
            gate.await()
            null // network down: the watchlist fetch fails
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val service = TraktLibraryService(
            appContext = mockk<Context>(relaxed = true),
            traktApi = mockk<TraktApi>(relaxed = true),
            traktAuthService = auth,
            profileManager = profileManager,
        )
        // The profile collector's forced refresh is in flight and stuck on the network.
        withTimeout(TIMEOUT_MS) { firstCall.await() }

        val waiters = (1..5).map { async(Dispatchers.IO) { service.ensureFresh() } }
        delay(SETTLE_MS)
        gate.complete(Unit)
        withTimeout(TIMEOUT_MS) { waiters.awaitAll() }
        delay(SETTLE_MS)

        assertEquals("only the running refresh may reach the network", 1, calls.get())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 200L
    }
}

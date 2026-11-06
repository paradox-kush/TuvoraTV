package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/** B175: the repository must not call GitHub while a known rate limit has not reset. */
class UpdateRepositoryRateLimitTest {

    private val nowSeconds get() = System.currentTimeMillis() / 1000L

    @Test
    fun `no request is made before the stored reset`() = runBlocking {
        val api = mockk<GitHubReleaseApi>()
        val prefs = mockk<UpdatePreferences>(relaxed = true)
        every { prefs.rateLimitedUntilEpochSeconds } returns flowOf(nowSeconds + 1_800L)

        val result = UpdateRepository(api, prefs).getLatestUpdate(UpdateChannel.STABLE)

        assertTrue("blocked", result.exceptionOrNull() is UpdateRateLimitedException)
        coVerify(exactly = 0) { api.getLatestRelease(any(), any()) }
        coVerify(exactly = 0) { api.getReleases(any(), any(), any()) }
    }

    @Test
    fun `a stale stored block far in the future does not block`() = runBlocking {
        val api = mockk<GitHubReleaseApi>()
        val prefs = mockk<UpdatePreferences>(relaxed = true)
        every { prefs.rateLimitedUntilEpochSeconds } returns flowOf(nowSeconds + 10 * 86_400L)
        coEvery { api.getLatestRelease(any(), any()) } returns Response.error(
            500,
            okhttp3.ResponseBody.create(null, ""),
        )

        val result = UpdateRepository(api, prefs).getLatestUpdate(UpdateChannel.STABLE)

        coVerify(exactly = 1) { api.getLatestRelease(any(), any()) }
        assertEquals("falls through to the normal error", "GitHub API error: 500", result.exceptionOrNull()?.message)
    }
}

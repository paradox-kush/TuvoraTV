package com.nuvio.tv.data.mdblist

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MdbListRatingsClient is a @Singleton built during startup injection. Creating its Moshi adapters
 * there ran KotlinJsonAdapterFactory (kotlin-reflect metadata parsing) on Main (~4% of cold start).
 */
class MdbListRatingsClientConstructionTest {

    @Test
    fun `construction creates no Moshi adapter`() {
        var adapterRequests = 0
        val counting = JsonAdapter.Factory { _, _, _ ->
            adapterRequests++
            null
        }
        val moshi = Moshi.Builder().add(counting).addLast(KotlinJsonAdapterFactory()).build()

        MdbListRatingsClient(
            api = mockk(relaxed = true),
            accountApi = mockk(relaxed = true),
            authStore = mockk(relaxed = true),
            moshi = moshi,
        )

        assertEquals("adapters requested while constructing MdbListRatingsClient", 0, adapterRequests)
    }
}

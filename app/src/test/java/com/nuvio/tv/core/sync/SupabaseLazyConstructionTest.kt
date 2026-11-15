package com.nuvio.tv.core.sync

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.DeviceSessionRegistration
import com.nuvio.tv.domain.model.AuthState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These classes are constructed during NuvioApplication/MainActivity injection on Main. Resolving the
 * Supabase client there built it on Main (~8% of cold-start CPU). Construction must resolve nothing.
 */
class SupabaseLazyConstructionTest {

    private class CountingLazy<T>(private val value: () -> T) : dagger.Lazy<T> {
        var resolves = 0
        override fun get(): T {
            resolves++
            return value()
        }
    }

    private val authManager = mockk<AuthManager>(relaxed = true) {
        every { authState } returns MutableStateFlow<AuthState>(AuthState.Loading)
    }

    @Test
    fun `constructing RealtimeSyncInvalidationService does not resolve the client`() {
        val client = CountingLazy { mockk<SupabaseClient>(relaxed = true) }

        RealtimeSyncInvalidationService(
            authManager = authManager,
            profileManager = mockk(relaxed = true),
            supabaseClientLazy = client,
            syncClientIdentity = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            surfaceCatchUp = mockk(relaxed = true),
            deviceSources = mockk(relaxed = true),
        )

        assertEquals("SupabaseClient resolves during construction", 0, client.resolves)
    }

    @Test
    fun `constructing DeviceSessionRegistration does not resolve Postgrest`() {
        val postgrest = CountingLazy { mockk<Postgrest>(relaxed = true) }

        DeviceSessionRegistration(
            context = mockk<Context>(relaxed = true),
            authManager = authManager,
            postgrestLazy = postgrest,
            syncClientIdentity = mockk(relaxed = true),
        )

        assertEquals("Postgrest resolves during construction", 0, postgrest.resolves)
    }

    @Test
    fun `constructing a Postgrest sync service does not resolve Postgrest`() {
        val postgrest = CountingLazy { mockk<Postgrest>(relaxed = true) }

        AddonSyncService(
            postgrestLazy = postgrest,
            authManager = authManager,
            addonPreferences = mockk(relaxed = true),
            profileManager = mockk(relaxed = true),
            syncClientIdentity = mockk(relaxed = true),
        )

        assertEquals("Postgrest resolves during construction", 0, postgrest.resolves)
    }
}

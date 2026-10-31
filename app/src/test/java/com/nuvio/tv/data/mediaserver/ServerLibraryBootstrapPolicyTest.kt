package com.nuvio.tv.data.mediaserver

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ServerLibraryBootstrapPolicyTest {
    private val base = ServerConnection("c", "jellyfin", "Den", "https://h", "m", "u", "viewer", "k")

    @Test
    fun `an imported server with no library list refreshes once it can sign in`() {
        assertTrue(ServerLibraryBootstrapPolicy.needsRefresh(base, hasToken = true))
    }

    @Test
    fun `servers that already list libraries or cannot sign in or are disabled do not`() {
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base.copy(libraries = listOf(ServerLibrary("l", "Movies", ServerMediaKind.MOVIE))), true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base, hasToken = false))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(base.copy(enabled = false), hasToken = true))
    }
    @Test
    fun `legacy managed collections are discovered without overriding disabled libraries`() {
        val legacy = base.copy(managedBridgeId = "bridge", libraries = listOf(ServerLibrary("c", "Collections", ServerMediaKind.COLLECTION)))
        assertTrue(ServerLibraryBootstrapPolicy.needsRefresh(legacy, true, availableOnDevice = true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(legacy, false, availableOnDevice = true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(legacy, true, availableOnDevice = false))
        val movieOnly = legacy.copy(libraries = listOf(ServerLibrary("m", "Movies", ServerMediaKind.MOVIE)))
        assertTrue(ServerLibraryBootstrapPolicy.needsRefresh(movieOnly, true, availableOnDevice = true))
        val disabled = legacy.copy(libraries = listOf(
            ServerLibrary("m", "Movies", ServerMediaKind.MOVIE, selected = false),
            ServerLibrary("s", "Shows", ServerMediaKind.SERIES, selected = false),
        ))
        val mixed = legacy.copy(libraries = listOf(ServerLibrary("x", "Mixed", ServerMediaKind.MIXED)))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(mixed, true, availableOnDevice = true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(disabled, true, availableOnDevice = true))
        assertFalse(ServerLibraryBootstrapPolicy.needsRefresh(legacy.copy(managedBridgeId = null), true, availableOnDevice = true))
    }
}

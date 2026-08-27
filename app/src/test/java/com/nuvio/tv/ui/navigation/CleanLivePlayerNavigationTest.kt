package com.nuvio.tv.ui.navigation

import com.nuvio.tv.ui.screens.player.clean.CleanLiveExitGate
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanLivePlayerNavigationTest {
    @Test
    fun `clean route accepts only a typed opaque launch token`() {
        val tokenValue = "ab".repeat(32)
        val token = CleanLiveLaunchToken(tokenValue)

        assertEquals("clean-live/{launchToken}", Screen.CleanLivePlayer.route)
        assertEquals("clean-live/$tokenValue", Screen.CleanLivePlayer.createRoute(token))
        val createMethods = Screen.CleanLivePlayer::class.java.declaredMethods
            .filter { it.name == "createRoute" }
        assertEquals(1, createMethods.size)
        assertEquals(listOf(CleanLiveLaunchToken::class.java), createMethods.single().parameterTypes.toList())
    }

    @Test
    fun `destination is registered without switching any production live caller`() {
        val navSource = source("ui/navigation/NuvioNavHost.kt")

        assertTrue(navSource.contains("route = Screen.CleanLivePlayer.route"))
        assertTrue(navSource.contains("CleanLivePlayerRoute("))
        assertFalse(navSource.contains("Screen.CleanLivePlayer.createRoute("))
    }

    @Test
    fun `route gates pop behind an idempotent affirmative release`() {
        val routeSource = source("ui/screens/player/clean/CleanLivePlayerRoute.kt")
        val releaseCall = routeSource.indexOf("viewModel.releaseBeforeExit()")
        val exitCall = routeSource.indexOf("latestReleasedExit()")

        assertTrue(releaseCall >= 0)
        assertTrue(exitCall > releaseCall)
        assertTrue(routeSource.contains("if (!exitGate.tryStart()) return"))
        assertTrue(routeSource.contains("if (released)"))
        assertTrue(routeSource.contains("exitGate.resetAfterFailure()"))
        assertFalse(routeSource.contains("popBackStack"))
    }

    @Test
    fun `exit gate is single flight and resets only after failure`() {
        val gate = CleanLiveExitGate()

        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertTrue(gate.isStarted())
        gate.resetAfterFailure()
        assertFalse(gate.isStarted())
        assertTrue(gate.tryStart())
    }

    private fun source(relative: String): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(projectRoot, "app/src/main/java/com/nuvio/tv/$relative").readText()
    }
}

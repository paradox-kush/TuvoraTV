package com.nuvio.tv.ui.screens.player

import androidx.lifecycle.Lifecycle
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlayerDestinationLifecyclePolicyTest {
    @Test
    fun `destroyed destination releases retained player`() {
        assertTrue(
            LegacyPlayerDestinationLifecyclePolicy.shouldRelease(
                event = Lifecycle.Event.ON_DESTROY,
                activityIsChangingConfigurations = false,
            ),
        )
    }

    @Test
    fun `pause and stop preserve normal background resume`() {
        listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP).forEach { event ->
            assertFalse(
                LegacyPlayerDestinationLifecyclePolicy.shouldRelease(
                    event = event,
                    activityIsChangingConfigurations = false,
                ),
            )
        }
    }

    @Test
    fun `configuration change does not terminally release destination`() {
        assertFalse(
            LegacyPlayerDestinationLifecyclePolicy.shouldRelease(
                event = Lifecycle.Event.ON_DESTROY,
                activityIsChangingConfigurations = true,
            ),
        )
    }

    @Test
    fun `player screen wires terminal lifecycle decision to release`() {
        val source = playerScreenSource()
        val destroyBranch = Regex(
            """Lifecycle\.Event\.ON_DESTROY\s*->\s*\{[\s\S]{0,700}?""" +
                """LegacyPlayerDestinationLifecyclePolicy\.shouldRelease\([\s\S]{0,700}?""" +
                """viewModel\.stopAndRelease\(\)""",
        )

        assertTrue(
            "PlayerScreen must release a navigation-saved legacy player when its destination is destroyed",
            destroyBranch.containsMatchIn(source),
        )
    }

    private fun playerScreenSource(): String {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        val projectRoot = generateSequence(File(userDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").isDirectory }
            ?: error("Cannot locate NuvioTV project root")
        return File(
            projectRoot,
            "app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerScreen.kt",
        ).readText()
    }
}

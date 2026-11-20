package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reorder Home must be reachable on every flavor and in every settings mode. The store flavor hides
 * add-ons (the other entry point), so Layout settings is the only way in there.
 */
class LayoutReorderHomeEntryTest {

    private fun entrySections(essential: Boolean, focusedPosterOptions: Boolean) =
        visibleLayoutSections(essential, focusedPosterOptions).filter { it.offersReorderHome() }

    @Test
    fun `entry is offered in every settings mode`() {
        for (essential in listOf(false, true)) {
            for (focused in listOf(false, true)) {
                assertEquals(
                    "exactly one visible Layout section offers Reorder Home (essential=$essential focused=$focused)",
                    listOf(LayoutSection.HOME_LAYOUT),
                    entrySections(essential, focused)
                )
            }
        }
    }

    @Test
    fun `layout category stays visible without add-ons in both modes`() {
        for (essential in listOf(false, true)) {
            for (primary in listOf(false, true)) {
                for (addonsOrPlugins in listOf(false, true)) {
                    val categories = visibleSettingsCategories(
                        isPrimaryProfile = primary,
                        isEssentialMode = essential,
                        isDebugBuild = false,
                        contentDiscoveryAvailable = addonsOrPlugins
                    )
                    assertTrue(
                        "Layout category visible (essential=$essential primary=$primary addons=$addonsOrPlugins)",
                        SettingsCategory.LAYOUT in categories
                    )
                }
            }
        }
    }
}

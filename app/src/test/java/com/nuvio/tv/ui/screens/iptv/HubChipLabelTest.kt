package com.nuvio.tv.ui.screens.iptv

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Sim pass 2026-11-16 (UX): a long playlist name in the hub's tab row wrapped mid-word ("MockStal / ker8911"). A chip
 * label is one line, ellipsized.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
// Real text measuring (the legacy shadow neither wraps nor ellipsizes).
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HubChipLabelTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `a long playlist name stays on one line`() {
        val label = "MockStalker8911 portal with a very long playlist name"
        rule.setContent {
            NuvioTheme {
                Box(Modifier.width(180.dp)) {
                    HubChip(label = label, selected = false, showDropdownIcon = true, onClick = {})
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("the chip label is one line", 1, layouts.first().lineCount)
        val layout = layouts.first()
        assertTrue(
            "a name too long for the chip is ellipsized, not drawn past it (width=${layout.size.width}px, " +
                "constraint=${layout.layoutInput.constraints}, lines=${layout.lineCount}, overflow=${layout.hasVisualOverflow})",
            layout.isLineEllipsized(0),
        )
    }
}

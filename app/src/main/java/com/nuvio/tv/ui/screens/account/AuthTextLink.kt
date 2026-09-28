@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A clickable text link for the auth screens. Being clickable makes it a D-pad focus stop, so it
 * carries the theme focus ring + focus fill (same treatment as the sign-up privacy notice) —
 * the default indication tint alone is invisible from the sofa.
 */
@Composable
internal fun AuthTextLink(
    text: String,
    onClick: () -> Unit,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Text(
        text = text,
        style = style,
        color = if (isFocused) NuvioTheme.colors.TextPrimary else color,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
                else Modifier
            )
            .background(
                color = if (isFocused) NuvioTheme.colors.FocusBackground else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
    )
}

package com.nuvio.tv.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text

/**
 * Whether focused text is allowed to scroll (marquee) when it overflows. Backed by the
 * "scroll long titles on focus" layout setting and provided at the app root; defaults to disabled
 * (the feature is off by default).
 */
val LocalFocusMarqueeEnabled = compositionLocalOf { false }

/**
 * Single-line text that scrolls (marquees) horizontally while [focused] if the content overflows,
 * and otherwise ellipsizes. Lets long titles/labels become fully readable when their card or row is
 * focused, while staying visually identical to a normal ellipsized [Text] when unfocused.
 *
 * Scrolling only happens when [focused], the [LocalFocusMarqueeEnabled] setting is on, and the text
 * actually overflows (Compose's [basicMarquee] is a no-op when it already fits).
 */
@Composable
fun FocusMarqueeText(
    text: String,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    // The setting (not focus) decides the layout: when on, the text is forced to a single line
    // (and scrolls while focused); when off, it falls back to the caller's [maxLines] so the
    // original layout is restored — e.g. a 2-line episode title stays 2 lines.
    val singleLine = LocalFocusMarqueeEnabled.current
    val marquee = focused && singleLine
    Text(
        text = text,
        modifier = if (marquee) modifier.basicMarquee(iterations = Int.MAX_VALUE) else modifier,
        style = style,
        color = color,
        maxLines = if (singleLine) 1 else maxLines,
        softWrap = !singleLine,
        overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
        textAlign = textAlign,
    )
}

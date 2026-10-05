package com.nuvio.tv.ui.components.player

import android.view.KeyEvent
import androidx.annotation.RawRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioTheme

/*
 * F28: the regular player's control pieces, moved out of PlayerScreen unchanged so the live overlay
 * (clean playback UI) can be built from them too. Neutral ground: the architecture firewall keeps
 * clean and legacy playback from importing each other, and this package is neither.
 */

/** One auto-hide delay for every player overlay: VOD and live used 3 s and 4 s. VOD's value is kept. */
object PlayerControlsTiming {
    const val AUTO_HIDE_MS = 3_000L
}

@Composable
internal fun ControlButton(
    icon: ImageVector,
    iconPainter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    upFocusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onDownKey: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(NuvioTheme.spacing.xxxl)
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .then(
                if (upFocusRequester != null) {
                    Modifier.focusProperties { up = upFocusRequester }
                } else {
                    Modifier
                }
            )
            .onPreviewKeyEvent { keyEvent ->
                if (
                    upFocusRequester != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_UP
                ) {
                    try {
                        upFocusRequester.requestFocus()
                    } catch (_: Exception) {}
                    true
                } else if (
                    onDownKey != null &&
                    keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onDownKey.invoke()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White,
            contentColor = Color.White,
            focusedContentColor = Color.Black
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape)
    ) {
        if (iconPainter != null) {
            Icon(
                painter = iconPainter,
                contentDescription = contentDescription,
                modifier = Modifier.size(NuvioTheme.spacing.xl)
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
internal fun rememberRawSvgPainter(@RawRes iconRes: Int): Painter {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { NuvioTheme.spacing.xl.roundToPx() }
    val request = remember(iconRes, context, sizePx) {
        ImageRequest.Builder(context)
            .data(iconRes)
            .size(sizePx)
            .build()
    }
    return rememberAsyncImagePainter(model = request)
}

@Composable
internal fun DialogButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (isPrimary) NuvioTheme.colors.Secondary else NuvioTheme.colors.BackgroundCard,
            contentColor = if (isPrimary) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextSecondary,
            focusedContainerColor = if (isPrimary) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusBackground,
            focusedContentColor = if (isPrimary) NuvioTheme.colors.OnSecondaryVariant else NuvioTheme.colors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, if (isPrimary) NuvioTheme.colors.SecondaryVariant else NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        scale = ButtonDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

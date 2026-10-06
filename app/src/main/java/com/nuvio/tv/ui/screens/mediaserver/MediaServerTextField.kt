@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.nuvio.tv.ui.screens.mediaserver

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.account.InputFieldKeys
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * A single-line text field for the media-server forms, TV-style (the same row-then-OK pattern the playlist form
 * uses, UX30/UX76): while D-pad focus passes over it, it is a plain row with the theme focus ring; OK / ENTER
 * starts editing (and opens the keyboard), UP/DOWN while editing leave it, BACK while editing returns to the row.
 * jellyfin-androidtv uses the system IME with Next/Done actions on its address and credential fields - same here.
 *
 * [onImeAction] is what the keyboard's Next/Done does (move to the next field, or submit the form).
 */
@Composable
internal fun MediaServerTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    focusRequester: FocusRequester? = null,
    onImeAction: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    var rowFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val ownRowFocus = remember { FocusRequester() }
    val rowFocus = focusRequester ?: ownRowFocus
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing && fieldFocus.requestFocusAfterFrames(frames = 1)) keyboard?.show()
    }
    val shape = RoundedCornerShape(10.dp)
    val active = rowFocused || fieldFocused
    Column(modifier = modifier.fillMaxWidth().padding(top = NuvioTheme.spacing.md)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NuvioTheme.spacing.xs)
                .focusRequester(rowFocus)
                .onFocusChanged { rowFocused = it.isFocused }
                .onKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (InputFieldKeys.startsEditing(editing, native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0, native.keyCode)) {
                        editing = true
                        true
                    } else false
                }
                .focusable()
                .background(NuvioTheme.colors.BackgroundElevated, shape)
                // House TV focus rule: the D-pad-focused row shows the theme focus ring; typing keeps a thinner one.
                .border(
                    when {
                        rowFocused -> NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs)
                        fieldFocused -> NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline)
                        else -> BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
                    },
                    shape,
                )
                .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md),
        ) {
            // The Android TV / Fire TV keyboard runs fullscreen and shows the field name only from EditorInfo.hintText.
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    val labelled = PlatformTextInputMethodRequest { outAttributes ->
                        request.createInputConnection(outAttributes).also { outAttributes.hintText = label }
                    }
                    nextHandler.startInputMethod(labelled)
                },
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus)
                        .focusProperties { canFocus = editing }
                        .onFocusChanged {
                            val nowFocused = it.isFocused || it.hasFocus
                            if (fieldFocused && !nowFocused) editing = false
                            fieldFocused = nowFocused
                        }
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (InputFieldKeys.stopsEditingOnBack(editing, native.keyCode)) {
                                if (native.action == KeyEvent.ACTION_UP) {
                                    keyboard?.hide()
                                    runCatching { rowFocus.requestFocus() }
                                }
                                return@onPreviewKeyEvent true
                            }
                            val direction = InputFieldKeys.exitDirection(editing, native.action == KeyEvent.ACTION_DOWN, native.keyCode)
                                ?: return@onPreviewKeyEvent false
                            keyboard?.hide()
                            focusManager.moveFocus(direction)
                            true
                        }
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            when {
                                native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && native.action == KeyEvent.ACTION_DOWN -> true
                                (native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                                    native.action == KeyEvent.ACTION_DOWN -> { onImeAction(); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = imeAction, keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
                    keyboardActions = KeyboardActions(onDone = { onImeAction() }, onNext = { onImeAction() }),
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (fieldFocused) NuvioTheme.colors.TextPrimary else Color.Transparent),
                    decorationBox = { inner ->
                        if (value.isEmpty() && hint.isNotEmpty()) {
                            Text(hint, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                        }
                        inner()
                    },
                )
            }
        }
    }
}

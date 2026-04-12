@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.tracker

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioColors

/**
 * Thin mount points for each tracker's Settings subscreen. Each wires its
 * Hilt-provided ViewModel to the shared [TrackerSettingsContent] composable
 * and owns its own debug-auth dialog (tracker-specific input shapes).
 */

// --- MAL --- //

@Composable
fun MalSettingsContent(
    viewModel: MalSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDebugDialog by remember { mutableStateOf(false) }

    TrackerSettingsContent(
        state = state,
        callbacks = TrackerSettingsCallbacks(
            onConnect = viewModel::onConnect,
            onCancelConnect = viewModel::onCancelConnect,
            onDisconnect = viewModel::onDisconnect,
            onSendProgressToggled = viewModel::onSendProgressToggled,
            onStatusToggled = viewModel::onStatusToggled,
            onDismissMessage = viewModel::onDismissMessage,
            onDebugConnect = { showDebugDialog = true }
        ),
        initialFocusRequester = initialFocusRequester
    )

    if (showDebugDialog) {
        MalDebugDialog(
            authorizeUrl = remember { viewModel.debugBuildAuthorizeUrl() },
            redirectUri = MalSettingsViewModel.MAL_DEBUG_REDIRECT_URI,
            onSubmit = { code ->
                viewModel.debugCompleteAuth(code)
                showDebugDialog = false
            },
            onDismiss = { showDebugDialog = false }
        )
    }
}

// --- AniList --- //

@Composable
fun AniListSettingsContent(
    viewModel: AniListSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDebugDialog by remember { mutableStateOf(false) }

    TrackerSettingsContent(
        state = state,
        callbacks = TrackerSettingsCallbacks(
            onConnect = viewModel::onConnect,
            onCancelConnect = viewModel::onCancelConnect,
            onDisconnect = viewModel::onDisconnect,
            onSendProgressToggled = viewModel::onSendProgressToggled,
            onStatusToggled = viewModel::onStatusToggled,
            onDismissMessage = viewModel::onDismissMessage,
            onDebugConnect = { showDebugDialog = true }
        ),
        initialFocusRequester = initialFocusRequester
    )

    if (showDebugDialog) {
        AniListDebugDialog(
            authorizeUrl = remember { viewModel.debugBuildAuthorizeUrl() },
            onSubmit = { token ->
                viewModel.debugSaveToken(token)
                showDebugDialog = false
            },
            onDismiss = { showDebugDialog = false }
        )
    }
}

// --- Kitsu --- //

@Composable
fun KitsuSettingsContent(
    viewModel: KitsuSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDebugDialog by remember { mutableStateOf(false) }

    TrackerSettingsContent(
        state = state,
        callbacks = TrackerSettingsCallbacks(
            onConnect = viewModel::onConnect,
            onCancelConnect = viewModel::onCancelConnect,
            onDisconnect = viewModel::onDisconnect,
            onSendProgressToggled = viewModel::onSendProgressToggled,
            onStatusToggled = viewModel::onStatusToggled,
            onDismissMessage = viewModel::onDismissMessage,
            onDebugConnect = { showDebugDialog = true }
        ),
        initialFocusRequester = initialFocusRequester
    )

    if (showDebugDialog) {
        KitsuDebugDialog(
            onSubmit = { email, password ->
                viewModel.debugSignInWithPassword(email, password)
                showDebugDialog = false
            },
            onDismiss = { showDebugDialog = false }
        )
    }
}

// --- Per-tracker debug dialogs --- //

@Composable
private fun MalDebugDialog(
    authorizeUrl: String,
    redirectUri: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = "MyAnimeList — local auth",
        subtitle = "1. Open the URL below on any browser. 2. Sign in. 3. When redirected to localhost, copy the `code=…` from the URL and paste it here.",
        width = 720.dp
    ) {
        ReadOnlyUrlRow(
            url = authorizeUrl,
            onCopy = { clip.setText(AnnotatedString(authorizeUrl)) }
        )
        Text(
            text = "Redirect URI set to: $redirectUri",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioColors.TextTertiary
        )
        Spacer(Modifier.height(6.dp))
        InputField(
            value = code,
            placeholder = "Paste the `code` value here",
            onValueChange = { code = it }
        )
        DialogButtonRow(
            onCancel = onDismiss,
            onSubmit = { if (code.isNotBlank()) onSubmit(code.trim()) },
            submitLabel = "Exchange"
        )
    }
}

@Composable
private fun AniListDebugDialog(
    authorizeUrl: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var token by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current

    NuvioDialog(
        onDismiss = onDismiss,
        title = "AniList — local auth",
        subtitle = "1. Open the URL below on any browser. 2. Sign in and authorize. 3. AniList will display the access token — paste it here.",
        width = 720.dp
    ) {
        ReadOnlyUrlRow(
            url = authorizeUrl,
            onCopy = { clip.setText(AnnotatedString(authorizeUrl)) }
        )
        Spacer(Modifier.height(6.dp))
        InputField(
            value = token,
            placeholder = "Paste access_token here",
            onValueChange = { token = it }
        )
        DialogButtonRow(
            onCancel = onDismiss,
            onSubmit = { if (token.isNotBlank()) onSubmit(token.trim()) },
            submitLabel = "Save"
        )
    }
}

@Composable
private fun KitsuDebugDialog(
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    NuvioDialog(
        onDismiss = onDismiss,
        title = "Kitsu — local sign in",
        subtitle = "Kitsu supports password-grant OAuth directly. No dev account or redirect needed.",
        width = 560.dp
    ) {
        InputField(
            value = email,
            placeholder = "Email",
            onValueChange = { email = it },
            keyboardType = KeyboardType.Email
        )
        Spacer(Modifier.height(8.dp))
        InputField(
            value = password,
            placeholder = "Password",
            onValueChange = { password = it },
            keyboardType = KeyboardType.Password,
            obscure = true
        )
        DialogButtonRow(
            onCancel = onDismiss,
            onSubmit = {
                if (email.isNotBlank() && password.isNotBlank()) onSubmit(email.trim(), password)
            },
            submitLabel = "Sign in"
        )
    }
}

// --- Shared dialog primitives --- //

@Composable
private fun ReadOnlyUrlRow(url: String, onCopy: () -> Unit) {
    Card(
        onClick = onCopy,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioColors.TextPrimary
            )
        }
    }
}

@Composable
private fun InputField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    obscure: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    Card(
        onClick = { requester.requestFocus() },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(10.dp)
            ),
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(10.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .onKeyEvent { event ->
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                cursorBrush = SolidColor(NuvioColors.Primary),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextTertiary
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun DialogButtonRow(
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
    submitLabel: String
) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundElevated,
                contentColor = NuvioColors.TextPrimary
            )
        ) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSubmit,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            )
        ) { Text(submitLabel) }
    }
}

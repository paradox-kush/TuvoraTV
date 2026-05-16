@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings.locallibrary

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.locallibrary.SourceKind
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.settings.SettingsDetailHeader
import com.nuvio.tv.ui.screens.settings.SettingsGroupCard
import com.nuvio.tv.ui.screens.settings.SettingsStandaloneScaffold
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun AddSourceScreen(
    onDone: () -> Unit,
    onBackPress: () -> Unit,
    viewModel: LocalLibrarySettingsViewModel = hiltViewModel()
) {
    var selected by remember { mutableStateOf(SourceKind.JELLYFIN) }
    val addResult by viewModel.addResult.collectAsStateWithLifecycle()

    LaunchedEffect(addResult) {
        if (addResult is LocalLibrarySettingsViewModel.AddResult.Success) {
            viewModel.clearAddResult()
            onDone()
        }
    }

    LaunchedEffect(selected) { viewModel.clearTestResult() }
    DisposableEffect(Unit) { onDispose { viewModel.clearTestResult() } }

    SettingsStandaloneScaffold(
        title = "Add Source",
        subtitle = "Pick a backend, then enter its connection details."
    ) {
        SettingsDetailHeader(
            title = "Backend",
            subtitle = "Choose where this content is served from."
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BackendChip("Jellyfin", selected == SourceKind.JELLYFIN) { selected = SourceKind.JELLYFIN }
            BackendChip("SMB / CIFS", selected == SourceKind.SMB) { selected = SourceKind.SMB }
            BackendChip("On-device", selected == SourceKind.LOCAL_FILE) { selected = SourceKind.LOCAL_FILE }
        }

        when (selected) {
            SourceKind.JELLYFIN -> JellyfinForm(viewModel, addResult)
            SourceKind.SMB -> SmbForm(viewModel, addResult)
            SourceKind.LOCAL_FILE -> LocalFileForm(viewModel, addResult)
        }
    }
}

@Composable
private fun BackendChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioColors.FocusRing else NuvioColors.Background,
            focusedContainerColor = if (selected) NuvioColors.FocusRing else NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(999.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(999.dp))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            color = if (selected) Color.Black else NuvioColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun JellyfinForm(
    viewModel: LocalLibrarySettingsViewModel,
    addResult: LocalLibrarySettingsViewModel.AddResult?
) {
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val canSubmit = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    val scanning = discoveryState is LocalLibrarySettingsViewModel.DiscoveryState.Scanning

    LaunchedEffect(addResult) {
        if (addResult != null) submitting = false
    }

    // On a successful auto-detect, prefill the URL and reset discovery state.
    LaunchedEffect(discoveryState) {
        val state = discoveryState
        if (state is LocalLibrarySettingsViewModel.DiscoveryState.Found) {
            url = state.url
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.clearDiscoveryState() } }

    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.discoverJellyfin() },
                enabled = !scanning && !submitting,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Background,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.Background,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) {
                Text(
                    text = if (scanning) "Scanning network…" else "Auto-detect server",
                    color = NuvioColors.TextPrimary
                )
            }
            DiscoveryStatusBanner(discoveryState)
            TextRow(label = "Display name", value = displayName, onValueChange = { displayName = it })
            TextRow(label = "Server URL", value = url, onValueChange = { url = it }, keyboard = KeyboardType.Uri)
            TextRow(label = "Username", value = username, onValueChange = { username = it })
            TextRow(label = "Password", value = password, onValueChange = { password = it }, isPassword = true)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    submitting = true
                    viewModel.clearAddResult()
                    viewModel.addJellyfin(displayName, url, username, password)
                },
                enabled = canSubmit && !submitting && !scanning,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.FocusRing,
                    contentColor = Color.Black,
                    focusedContainerColor = NuvioColors.FocusRing,
                    focusedContentColor = Color.Black
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.TextPrimary),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) {
                Text(
                    text = if (submitting) "Testing connection…" else "Test & Save",
                    color = Color.Black
                )
            }
            ResultBanner(addResult)
        }
    }
}

@Composable
private fun DiscoveryStatusBanner(state: LocalLibrarySettingsViewModel.DiscoveryState) {
    when (state) {
        is LocalLibrarySettingsViewModel.DiscoveryState.Found -> {
            val label = state.serverName?.let { "Found Jellyfin server: $it (${state.url})" }
                ?: "Found Jellyfin server: ${state.url}"
            Text(
                text = label,
                color = Color(0xFF6BCB77),
                style = MaterialTheme.typography.bodySmall
            )
        }
        LocalLibrarySettingsViewModel.DiscoveryState.NotFound -> {
            Text(
                text = "No Jellyfin server found on the local network. Please enter the URL manually.",
                color = Color(0xFFFF6B6B),
                style = MaterialTheme.typography.bodySmall
            )
        }
        else -> Unit
    }
}

@Composable
private fun SmbForm(
    viewModel: LocalLibrarySettingsViewModel,
    addResult: LocalLibrarySettingsViewModel.AddResult?
) {
    var displayName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("smb://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val canSubmit = url.startsWith("smb://") || url.startsWith("//")

    LaunchedEffect(addResult) {
        if (addResult != null) submitting = false
    }

    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextRow(label = "Display name", value = displayName, onValueChange = { displayName = it })
            TextRow(
                label = "Share path (smb://host/share[/sub/dir])",
                value = url,
                onValueChange = { url = it },
                keyboard = KeyboardType.Uri
            )
            TextRow(label = "Username (optional)", value = username, onValueChange = { username = it })
            TextRow(label = "Password (optional)", value = password, onValueChange = { password = it }, isPassword = true)
            TextRow(label = "Domain (optional)", value = domain, onValueChange = { domain = it })
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    submitting = true
                    viewModel.clearAddResult()
                    viewModel.addSmb(
                        displayName,
                        url,
                        username.takeIf { it.isNotBlank() },
                        password.takeIf { it.isNotBlank() },
                        domain.takeIf { it.isNotBlank() }
                    )
                },
                enabled = canSubmit && !submitting,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.FocusRing,
                    contentColor = Color.Black,
                    focusedContainerColor = NuvioColors.FocusRing,
                    focusedContentColor = Color.Black
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.TextPrimary),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) {
                Text(
                    text = if (submitting) "Testing connection…" else "Test & Save",
                    color = Color.Black
                )
            }
            ResultBanner(addResult)
        }
    }
}

@Composable
private fun LocalFileForm(
    viewModel: LocalLibrarySettingsViewModel,
    addResult: LocalLibrarySettingsViewModel.AddResult?
) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf("On-device files") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pickedUri = uri
        }
    }
    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextRow(label = "Display name", value = displayName, onValueChange = { displayName = it })
            Button(
                onClick = { launcher.launch(null) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.Background,
                    contentColor = NuvioColors.TextPrimary,
                    focusedContainerColor = NuvioColors.Background,
                    focusedContentColor = NuvioColors.TextPrimary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.FocusRing),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text(if (pickedUri == null) "Choose folder…" else "Folder selected", color = NuvioColors.TextPrimary) }
            if (pickedUri != null) {
                Text(
                    text = pickedUri.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { pickedUri?.let { viewModel.addLocalFile(displayName, it.toString()) } },
                enabled = pickedUri != null,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioColors.FocusRing,
                    contentColor = Color.Black,
                    focusedContainerColor = NuvioColors.FocusRing,
                    focusedContentColor = Color.Black
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                border = ButtonDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, NuvioColors.TextPrimary),
                        shape = RoundedCornerShape(50)
                    )
                )
            ) { Text("Test & Save", color = Color.Black) }
            ResultBanner(addResult)
        }
    }
}

/**
 * Inline click-to-edit text input for TV.
 *
 * Mirrors the proven pattern in `AddonManagerScreen.kt:273` — a focusable TV
 * `Surface` always wraps a permanently-mounted `BasicTextField`. During D-pad
 * navigation the Surface owns focus (so the IME stays closed). Pressing select
 * fires `Surface.onClick`, which flips [editing] true; a `LaunchedEffect`
 * then calls `requestFocus()` on the inner field and `keyboardController.show()`.
 * Losing focus or pressing Done flips it back, and the system hides the IME.
 *
 * Why this works where my prior attempts didn't: TV `Card.onClick` doesn't
 * cleanly hand focus to an inner BasicTextField, and conditional rendering of
 * the field meant the focusRequester wasn't laid out when `requestFocus()` ran.
 */
@Composable
private fun TextRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isPassword: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    var editing by remember { mutableStateOf(false) }
    val surfaceFocusRequester = remember { FocusRequester() }
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(editing) {
        if (editing) {
            fieldFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioColors.TextSecondary)
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            onClick = { editing = true },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(surfaceFocusRequester),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = NuvioColors.Background,
                focusedContainerColor = NuvioColors.Background
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NuvioColors.Border),
                    shape = RoundedCornerShape(8.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioColors.FocusRing),
                    shape = RoundedCornerShape(8.dp)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioColors.TextPrimary),
                    cursorBrush = SolidColor(if (editing) NuvioColors.FocusRing else Color.Transparent),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        editing = false
                        keyboardController?.hide()
                        surfaceFocusRequester.requestFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocusRequester)
                        .onFocusChanged { state ->
                            if (!state.isFocused && editing) {
                                editing = false
                                keyboardController?.hide()
                            }
                        },
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Tap to edit",
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
}

@Composable
private fun ResultBanner(result: LocalLibrarySettingsViewModel.AddResult?) {
    if (result is LocalLibrarySettingsViewModel.AddResult.Failure) {
        Text(
            text = result.message,
            color = Color(0xFFFF6B6B),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun TestResultBanner(result: LocalLibrarySettingsViewModel.TestResult?) {
    when (result) {
        is LocalLibrarySettingsViewModel.TestResult.Success -> Text(
            text = "✓ Connection successful",
            color = Color(0xFF6BCB77),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
        is LocalLibrarySettingsViewModel.TestResult.Failure -> Text(
            text = "Test failed: ${result.message}",
            color = Color(0xFFFF6B6B),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
        LocalLibrarySettingsViewModel.TestResult.Running,
        null -> Unit
    }
}

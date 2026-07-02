package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Xtream IPTV accounts settings (inline section, like Debrid). Single paste field:
 * the user pastes their portal/M3U URL, we verify it live, then it's saved.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamSettingsContent(
    viewModel: XtreamSettingsViewModel = hiltViewModel(),
    onBrowseVod: (accountId: String) -> Unit = {},
    onBrowseLive: (accountId: String) -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<XtreamAccount?>(null) }
    var editFor by remember { mutableStateOf<XtreamAccount?>(null) }
    // Content & Categories: track ids (not snapshots) so the dialogs always render the
    // freshest account from the store after each toggle.
    var contentForId by remember { mutableStateOf<String?>(null) }
    var checklistType by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NuvioTheme.spacing.xl)
    ) {
        Text(
            text = "IPTV (Xtream Codes)",
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = "Paste your IPTV portal or M3U URL to add a provider.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.md)
        )

        SettingsActionRow(
            title = "Add IPTV account",
            subtitle = "Paste a portal / M3U URL",
            onClick = { showAddDialog = true },
            leadingIcon = Icons.Default.Add,
            modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
        )

        uiState.accounts.forEach { account ->
            // Lazily fetch "Active · 0/1 connections · Expires …" for the row (silent on failure).
            LaunchedEffect(account.id) { viewModel.ensureAccountStatus(account) }
            SettingsActionRow(
                title = account.name,
                subtitle = listOfNotNull(account.baseUrl, uiState.accountStatus[account.id]).joinToString("\n"),
                value = if (account.enabled) "On" else "Off",
                onClick = { actionsFor = account }
            )
        }
    }

    if (showAddDialog) {
        XtreamAddDialog(
            isValidating = uiState.isValidating,
            error = uiState.error,
            onSubmitUrl = { url -> viewModel.addFromUrl(url, name = null) { showAddDialog = false } },
            onSubmitManual = { server, user, pass, name ->
                viewModel.addManual(server, user, pass, name) { showAddDialog = false }
            },
            onDismiss = {
                viewModel.clearError()
                showAddDialog = false
            }
        )
    }

    editFor?.let { account ->
        XtreamAddDialog(
            isValidating = uiState.isValidating,
            error = uiState.error,
            initial = account,
            onSubmitUrl = { url -> viewModel.editFromUrl(account, url) { editFor = null } },
            onSubmitManual = { server, user, pass, name ->
                viewModel.editManual(account, server, user, pass, name) { editFor = null }
            },
            onDismiss = {
                viewModel.clearError()
                editFor = null
            }
        )
    }

    actionsFor?.let { account ->
        NuvioDialog(
            onDismiss = { actionsFor = null },
            title = account.name,
            subtitle = account.baseUrl
        ) {
            SettingsActionRow(
                title = "Browse Live TV",
                subtitle = null,
                onClick = {
                    val id = account.id
                    actionsFor = null
                    onBrowseLive(id)
                }
            )
            SettingsActionRow(
                title = "Browse Movies (VOD)",
                subtitle = null,
                onClick = {
                    val id = account.id
                    actionsFor = null
                    onBrowseVod(id)
                }
            )
            SettingsActionRow(
                title = "Content & Categories",
                subtitle = "Choose which content types and categories to show",
                onClick = {
                    val id = account.id
                    actionsFor = null
                    contentForId = id
                }
            )
            SettingsActionRow(
                title = "Edit URL / credentials",
                subtitle = null,
                onClick = {
                    actionsFor = null
                    editFor = account
                }
            )
            SettingsActionRow(
                title = if (account.enabled) "Disable" else "Enable",
                subtitle = null,
                onClick = {
                    viewModel.setEnabled(account.id, !account.enabled)
                    actionsFor = null
                }
            )
            SettingsActionRow(
                title = "Remove account",
                subtitle = null,
                onClick = {
                    viewModel.remove(account.id)
                    actionsFor = null
                }
            )
        }
    }

    // Content & Categories: content-type toggles + per-type category checklist.
    contentForId?.let { id ->
        val account = uiState.accounts.firstOrNull { it.id == id }
        if (account == null) {
            contentForId = null; checklistType = null
        } else {
            LaunchedEffect(id) { viewModel.loadCategoryLists(account) }
            XtreamContentTypesDialog(
                account = account,
                categoryLists = uiState.categoryLists,
                onToggleType = { type, enabled -> viewModel.setContentTypeEnabled(id, type, enabled) },
                onOpenChecklist = { type -> checklistType = type },
                onDismiss = { contentForId = null; checklistType = null }
            )
            checklistType?.let { type ->
                XtreamCategoryChecklistDialog(
                    account = account,
                    type = type,
                    categories = uiState.categoryLists["$id|$type"],
                    onSetSelection = { selection -> viewModel.setCategorySelection(id, type, selection) },
                    onDismiss = { checklistType = null }
                )
            }
        }
    }
}

private val CONTENT_TYPES = listOf(
    XtreamAccount.TYPE_LIVE to "Live TV",
    XtreamAccount.TYPE_MOVIES to "Movies",
    XtreamAccount.TYPE_SERIES to "Series"
)

/** Level 1: the three content-type rows (checkbox toggles the type, body opens its checklist). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamContentTypesDialog(
    account: XtreamAccount,
    categoryLists: Map<String, List<com.nuvio.tv.core.iptv.XtreamCategory>>,
    onToggleType: (type: String, enabled: Boolean) -> Unit,
    onOpenChecklist: (type: String) -> Unit,
    onDismiss: () -> Unit
) {
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstRowFocus.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "Content & Categories",
        subtitle = account.name
    ) {
        CONTENT_TYPES.forEachIndexed { index, (type, label) ->
            val enabled = account.typeEnabled(type)
            val selection = account.categorySelections.forType(type)
            val total = categoryLists["${account.id}|$type"]?.size
            val countText = when {
                !enabled -> "Hidden"
                selection == null -> "All categories"
                total != null -> "${selection.size}/$total"
                else -> "${selection.size} selected"
            }
            ContentTypeRow(
                label = label,
                enabled = enabled,
                countText = countText,
                focusRequester = if (index == 0) firstRowFocus else null,
                onToggle = { onToggleType(type, !enabled) },
                onOpen = { onOpenChecklist(type) }
            )
        }
        Text(
            text = "Checkbox shows/hides the content type. Select a type to choose its categories.",
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

/** Level 2: the category checklist for one content type. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamCategoryChecklistDialog(
    account: XtreamAccount,
    type: String,
    categories: List<com.nuvio.tv.core.iptv.XtreamCategory>?,
    onSetSelection: (List<String>?) -> Unit,
    onDismiss: () -> Unit
) {
    val label = CONTENT_TYPES.firstOrNull { it.first == type }?.second ?: type
    val selection = account.categorySelections.forType(type)
    val selectAllFocus = remember { FocusRequester() }
    LaunchedEffect(categories != null) { runCatching { selectAllFocus.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "$label categories",
        subtitle = when {
            categories == null -> "Loading categories…"
            else -> "${selection?.size ?: categories.size}/${categories.size} selected"
        }
    ) {
        if (categories == null) return@NuvioDialog
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            ChecklistAction("Select All", focusRequester = selectAllFocus) { onSetSelection(null) }
            ChecklistAction("Deselect All") { onSetSelection(emptyList()) }
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 380.dp),
            contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
        ) {
            items(categories, key = { it.id }) { category ->
                val checked = selection == null || category.id in selection
                CategoryCheckRow(
                    name = category.name.ifBlank { "Other" },
                    checked = checked,
                    onToggle = {
                        // Toggling away from "all" materializes today's full id list first, so
                        // only the toggled category changes (future categories arrive unselected).
                        val current = selection ?: categories.map { it.id }
                        onSetSelection(if (category.id in current) current - category.id else current + category.id)
                    }
                )
            }
        }
    }
}

/** A content-type row: [checkbox] toggles the type, [body] opens the category checklist. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ContentTypeRow(
    label: String,
    enabled: Boolean,
    countText: String,
    focusRequester: FocusRequester? = null,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FocusableBox(onSelect = onToggle, focusRequester = focusRequester) { focused ->
            Text(
                text = if (enabled) "☑" else "☐",
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    focused -> NuvioTheme.colors.TextPrimary
                    enabled -> NuvioTheme.colors.Primary
                    else -> NuvioTheme.colors.TextTertiary
                }
            )
        }
        Spacer(Modifier.width(NuvioTheme.spacing.sm))
        FocusableBox(onSelect = onOpen, modifier = Modifier.weight(1f)) { focused ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled || focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$countText  ›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChecklistAction(label: String, focusRequester: FocusRequester? = null, onSelect: () -> Unit) {
    FocusableBox(onSelect = onSelect, focusRequester = focusRequester) { focused ->
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryCheckRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    FocusableBox(onSelect = onToggle, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (checked) "☑" else "☐",
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    focused -> NuvioTheme.colors.TextPrimary
                    checked -> NuvioTheme.colors.Primary
                    else -> NuvioTheme.colors.TextTertiary
                }
            )
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/** Minimal focusable container with the app's focus border/background styling (dialog rows). */
@Composable
private fun FocusableBox(
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable (focused: Boolean) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) NuvioTheme.colors.Primary else Color.Transparent)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                    it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                ) { onSelect(); true } else false
            }
            .padding(horizontal = 10.dp, vertical = NuvioTheme.spacing.sm)
    ) {
        content(focused)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamAddDialog(
    isValidating: Boolean,
    error: String?,
    onSubmitUrl: (String) -> Unit,
    onSubmitManual: (server: String, user: String, pass: String, name: String?) -> Unit,
    onDismiss: () -> Unit,
    initial: XtreamAccount? = null
) {
    var manualMode by remember { mutableStateOf(initial != null) }
    // paste mode
    var url by remember { mutableStateOf("") }
    // manual mode (prefilled when editing an existing account)
    var server by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }

    val firstFieldFocus = remember { FocusRequester() }
    LaunchedEffect(manualMode) { runCatching { firstFieldFocus.requestFocus() } }

    val submit = {
        if (!isValidating) {
            if (manualMode) {
                if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                    onSubmitManual(server.trim(), user.trim(), pass.trim(), name.trim().ifEmpty { null })
                }
            } else if (url.isNotBlank()) {
                onSubmitUrl(url.trim())
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (initial != null) "Edit IPTV account" else "Add IPTV account",
        subtitle = if (manualMode) "Enter your panel details" else "Paste your portal or M3U URL (it contains your username & password)",
        width = 760.dp,
        suppressFirstKeyUp = false
    ) {
        // mode toggle
        Row(modifier = Modifier.padding(top = NuvioTheme.spacing.sm)) {
            XtreamModeTab("Paste link", selected = !manualMode) { manualMode = false }
            Spacer(Modifier.width(NuvioTheme.spacing.sm))
            XtreamModeTab("Enter details", selected = manualMode) { manualMode = true }
        }

        if (manualMode) {
            XtreamField(server, { server = it }, "Server URL  (http://host:port)", firstFieldFocus, onSubmit = submit)
            XtreamField(user, { user = it }, "Username", onSubmit = submit)
            XtreamField(pass, { pass = it }, "Password", isPassword = true, onSubmit = submit)
            XtreamField(name, { name = it }, "Name (optional)", onSubmit = submit)
            XtreamAddButton(isValidating, label = if (initial != null) "Save changes" else "Add account", onClick = submit)
        } else {
            XtreamField(url, { url = it }, "http://host:port/get.php?username=…&password=…", firstFieldFocus, onSubmit = submit)
        }

        val status = when {
            isValidating -> "Verifying…"
            error != null -> error
            else -> null
        }
        if (status != null) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null && !isValidating) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundElevated)
            .border(
                width = 1.dp,
                color = if (focused) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                    it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                ) { onClick(); true } else false
            }
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester? = null,
    isPassword: Boolean = false,
    onSubmit: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.md)
            .background(NuvioTheme.colors.BackgroundElevated, RoundedCornerShape(10.dp))
            .border(
                width = 1.dp,
                color = if (focused) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                when {
                    native.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && native.action == KeyEvent.ACTION_DOWN -> true
                    (native.keyCode == KeyEvent.KEYCODE_ENTER || native.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                        native.action == KeyEvent.ACTION_DOWN -> { onSubmit(); true }
                    else -> false
                }
            },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
        cursorBrush = SolidColor(if (focused) NuvioTheme.colors.Primary else Color.Transparent),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
            }
            inner()
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamAddButton(isValidating: Boolean, label: String = "Add account", onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.md)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundElevated)
            .border(1.dp, if (focused) NuvioTheme.colors.Primary else NuvioTheme.colors.Border, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent {
                if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                    it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                ) { onClick(); true } else false
            }
            .padding(vertical = NuvioTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isValidating) "Verifying…" else label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary
        )
    }
}

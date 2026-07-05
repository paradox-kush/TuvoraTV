package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.parseXtreamAccount
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
    onPairFromPhone: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val indexingAccounts by viewModel.indexingAccounts.collectAsStateWithLifecycle()
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

        // Pair from a phone: typing on a TV remote is painful (and the TV may not be signed in),
        // so this opens a QR + code the user enters a playlist against on their phone (P5).
        SettingsActionRow(
            title = stringResource(R.string.iptv_pairing_entry_title),
            subtitle = stringResource(R.string.iptv_pairing_entry_subtitle),
            onClick = onPairFromPhone,
            leadingIcon = Icons.Default.PhoneAndroid
        )

        uiState.accounts.forEach { account ->
            // Lazily fetch "Active · 0/1 connections · Expires …" for the row (silent on failure).
            LaunchedEffect(account.id) { viewModel.ensureAccountStatus(account) }
            SettingsActionRow(
                title = account.name,
                subtitle = listOfNotNull(
                    account.baseUrl,
                    "Preparing catalog for search & playback…".takeIf { account.id in indexingAccounts },
                    uiState.accountStatus[account.id]
                ).joinToString("\n"),
                value = if (account.enabled) "On" else "Off",
                onClick = { actionsFor = account }
            )
        }
    }

    if (showAddDialog) {
        XtreamAddDialog(
            isValidating = uiState.isValidating,
            error = uiState.error,
            onSubmitUrl = { url, options -> viewModel.addFromUrl(url, name = null, options = options) { showAddDialog = false } },
            onSubmitManual = { server, user, pass, name, options ->
                viewModel.addManual(server, user, pass, name, options) { showAddDialog = false }
            },
            onSubmitM3U = { playlistUrl, userAgent, name, options ->
                viewModel.addM3UUrl(playlistUrl, userAgent, name, options) { showAddDialog = false }
            },
            onSubmitFile = { uri, fileName, name, options ->
                viewModel.addM3UFile(uri, fileName, name, options) { showAddDialog = false }
            },
            onSubmitStalker = { fields, name, options ->
                viewModel.addStalker(fields, name, options) { showAddDialog = false }
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
            onSubmitUrl = { url, options -> viewModel.editFromUrl(account, url, options) { editFor = null } },
            onSubmitManual = { server, user, pass, name, options ->
                viewModel.editManual(account, server, user, pass, name, options) { editFor = null }
            },
            onSubmitM3U = { playlistUrl, userAgent, _, options ->
                viewModel.editM3UUrl(account, playlistUrl, userAgent, options) { editFor = null }
            },
            onSubmitFile = { uri, fileName, _, _ ->
                // Editing a file playlist = re-pick its file (same id, keeps saved content/options).
                viewModel.addM3UFile(uri, fileName, name = null, reimportFor = account) { editFor = null }
            },
            onSubmitStalker = { fields, name, options ->
                viewModel.editStalker(account, fields, name, options) { editFor = null }
            },
            onDismiss = {
                viewModel.clearError()
                editFor = null
            }
        )
    }

    actionsFor?.let { account ->
        val needsReimport = viewModel.needsReimport(account)
        NuvioDialog(
            onDismiss = { actionsFor = null },
            title = account.name,
            subtitle = when {
                needsReimport -> "Imported file not on this device — re-import to browse"
                account.fileName != null -> account.fileName
                else -> account.baseUrl
            }
        ) {
            // A file playlist with no local copy on this device (synced from elsewhere) can't browse
            // until it's re-imported here — offer that first, hide the dead browse entries.
            if (needsReimport) {
                SettingsActionRow(
                    title = "Re-import file",
                    subtitle = "Pick the playlist file again on this device",
                    leadingIcon = Icons.Default.Add,
                    onClick = {
                        actionsFor = null
                        editFor = account
                    }
                )
            }
            // Browse entries respect the content-type toggles — a disabled type is hidden here
            // too, not just in the hub (this was a bypass around "Hidden").
            if (!needsReimport && account.typeEnabled(XtreamAccount.TYPE_LIVE)) {
                SettingsActionRow(
                    title = "Browse Live TV",
                    subtitle = null,
                    onClick = {
                        val id = account.id
                        actionsFor = null
                        onBrowseLive(id)
                    }
                )
            }
            if (!needsReimport && account.typeEnabled(XtreamAccount.TYPE_MOVIES)) {
                SettingsActionRow(
                    title = "Browse Movies (VOD)",
                    subtitle = null,
                    onClick = {
                        val id = account.id
                        actionsFor = null
                        onBrowseVod(id)
                    }
                )
            }
            SettingsActionRow(
                title = "Content & Categories",
                subtitle = "Choose which content types and categories to show",
                onClick = {
                    val id = account.id
                    actionsFor = null
                    contentForId = id
                }
            )
            // File playlists have no URL/creds to edit — re-picking the file IS the edit. Only show
            // the plain "Edit" entry when the file is present (a missing file shows Re-import above).
            if (!(account.fileName != null && needsReimport)) {
                SettingsActionRow(
                    title = if (account.fileName != null) "Change / re-import file" else "Edit URL / credentials",
                    subtitle = null,
                    onClick = {
                        actionsFor = null
                        editFor = account
                    }
                )
            }
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
                    onToggleCategory = { categoryId, isChecked -> viewModel.toggleCategory(id, type, categoryId, isChecked) },
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

/** Matches SettingsMultiChoiceDialog's default list height so the checklist scrolls like other pickers. */
private val SettingsDialogListMaxHeight = 420.dp

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
    LaunchedEffect(Unit) { firstRowFocus.requestFocusAfterFrames() }
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
            text = "The toggle shows or hides a content type. Select a type to choose its categories.",
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
    onToggleCategory: (categoryId: String, isChecked: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val label = CONTENT_TYPES.firstOrNull { it.first == type }?.second ?: type
    val selection = account.categorySelections.forType(type)
    val selectAllFocus = remember { FocusRequester() }
    LaunchedEffect(categories != null) { selectAllFocus.requestFocusAfterFrames() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = "$label categories",
        subtitle = when {
            categories == null -> "Loading categories…"
            else -> "${selection?.size ?: categories.size}/${categories.size} selected"
        }
    ) {
        if (categories == null) return@NuvioDialog
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
                SettingsDialogActionButton("Select All", onClick = { onSetSelection(null) }, primary = true)
                SettingsDialogActionButton("Deselect All", onClick = { onSetSelection(emptyList()) })
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = SettingsDialogListMaxHeight),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs)
            ) {
                items(categories, key = { it.id }) { category ->
                    val checked = selection == null || category.id in selection
                    CategoryCheckRow(
                        name = category.name.ifBlank { "Other" },
                        checked = checked,
                        // Send the OPERATION, not a recomputed list: composed state can be stale for
                        // a beat after a fast previous toggle, and a whole-list write would clobber
                        // it. The VM composes the toggle against the store's latest selection.
                        onToggle = { onToggleCategory(category.id, !checked) }
                    )
                }
            }
        }
    }
}

/**
 * A content-type row built on the settings Card vocabulary: the row body opens the category
 * checklist (value + chevron, like SettingsActionRow); a trailing [SettingsTogglePill] Card
 * toggles the type on/off. Two focus targets, both using the FocusRing border, no Primary flood.
 */
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        SettingsRowCard(
            onClick = onOpen,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = if (enabled) 1f else 0.4f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = countText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextSecondary
                )
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = NuvioTheme.colors.TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        SettingsRowCard(onClick = onToggle) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.md)) {
                SettingsTogglePill(checked = enabled, enabled = true)
            }
        }
    }
}

/** A category row using the SettingsMultiChoiceDialog Card+Check idiom (immediate-commit toggle). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryCheckRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = if (checked) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (checked) {
                Spacer(Modifier.width(NuvioTheme.spacing.md))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Focusable Card matching the settings row focus vocabulary (FocusRing border, no scale, no flood). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRowCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    content: @Composable () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.Background,
            focusedContainerColor = NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        content = { content() }
    )
}

// --- "Add Playlist" form (playlist manager P1/P2) ---------------------------
// Xtream, URL, and File are functional; Stalker renders with a "Soon" badge and is non-selectable.
// The File tile IS shown on TV: SAF's ACTION_OPEN_DOCUMENT is often unavailable on Android TV, so
// the file source degrades gracefully — if no documents UI resolves the intent, it shows an inline
// "add by URL or from your phone" message instead of crashing (see FileSourceFields). To enable a
// future source, flip its `enabled` flag in [PLAYLIST_SOURCES] and fill in its field layout.

/** A selectable source-type tile. `enabled=false` -> shows a "Soon" badge and can't be picked. */
private data class PlaylistSource(
    val id: String,
    val label: String,
    val enabled: Boolean
)

private val PLAYLIST_SOURCES = listOf(
    PlaylistSource(XtreamAccount.SOURCE_URL, "URL", enabled = true),
    PlaylistSource(XtreamAccount.SOURCE_FILE, "File", enabled = true),
    PlaylistSource(XtreamAccount.SOURCE_XTREAM, "Xtream", enabled = true),
    PlaylistSource(XtreamAccount.SOURCE_STALKER, "Stalker", enabled = true)
)

private data class DnsOption(val id: String, val label: String)

private val DNS_OPTIONS = listOf(
    DnsOption(XtreamAccount.DNS_SYSTEM, "System"),
    DnsOption(XtreamAccount.DNS_CLOUDFLARE, "Cloudflare"),
    DnsOption(XtreamAccount.DNS_GOOGLE, "Google"),
    DnsOption(XtreamAccount.DNS_MULLVAD, "Mullvad"),
    DnsOption(XtreamAccount.DNS_QUAD9, "Swiss"),
    DnsOption(XtreamAccount.DNS_DNSSB, "DNS.SB")
)

private fun autoRefreshLabel(hours: Int): String = if (hours == 0) "Off" else "${hours}h"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun XtreamAddDialog(
    isValidating: Boolean,
    error: String?,
    onSubmitUrl: (String, XtreamSettingsViewModel.PlaylistOptions) -> Unit,
    onSubmitManual: (server: String, user: String, pass: String, name: String?, XtreamSettingsViewModel.PlaylistOptions) -> Unit,
    onSubmitM3U: (playlistUrl: String, userAgent: String?, name: String?, XtreamSettingsViewModel.PlaylistOptions) -> Unit,
    onSubmitFile: (uri: Uri, fileName: String, name: String?, XtreamSettingsViewModel.PlaylistOptions) -> Unit,
    onSubmitStalker: (fields: XtreamSettingsViewModel.StalkerFields, name: String?, XtreamSettingsViewModel.PlaylistOptions) -> Unit,
    onDismiss: () -> Unit,
    initial: XtreamAccount? = null
) {
    val context = LocalContext.current
    // Source type — Xtream is the default; URL (M3U) + File are selectable too.
    var sourceType by remember { mutableStateOf(initial?.sourceType ?: XtreamAccount.SOURCE_XTREAM) }

    // File source: the picked document uri + its display name (null until the user picks one).
    var pickedFileUri by remember { mutableStateOf<Uri?>(null) }
    var pickedFileName by remember { mutableStateOf(if (initial?.sourceType == XtreamAccount.SOURCE_FILE) initial.fileName.orEmpty() else "") }
    // ACTION_OPEN_DOCUMENT is often absent on Android TV — detect via resolveActivity and, if so,
    // show an honest inline message instead of launching an intent that no activity can handle.
    val openDocIntent = remember {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
    }
    val filePickerAvailable = remember {
        openDocIntent.resolveActivity(context.packageManager) != null
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedFileUri = uri
            pickedFileName = queryDisplayName(context, uri) ?: pickedFileName.ifBlank { "playlist.m3u" }
        }
    }

    // Xtream is portal + username + password (the reference form). The three fields are the
    // default; "Paste link" stays as a secondary convenience. Pasting a get.php URL into the
    // Server URL field also auto-fills user/pass, so most users never touch the toggle.
    var manualMode by remember { mutableStateOf(true) }
    var url by remember { mutableStateOf("") }
    val isEditingM3U = initial?.sourceType == XtreamAccount.SOURCE_URL
    var server by remember { mutableStateOf(if (isEditingM3U) "" else initial?.baseUrl ?: "") }
    var user by remember { mutableStateOf(if (isEditingM3U) "" else initial?.username ?: "") }
    var pass by remember { mutableStateOf(initial?.password ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }

    // M3U URL source fields: the playlist URL lives in baseUrl, the optional User-Agent in username.
    var m3uUrl by remember { mutableStateOf(if (isEditingM3U) initial.baseUrl else "") }
    var m3uUserAgent by remember { mutableStateOf(if (isEditingM3U) initial.username else "") }

    // Stalker portal source fields.
    var portalUrl by remember { mutableStateOf(initial?.portalUrl ?: "") }
    var mac by remember { mutableStateOf(initial?.macAddress?.ifBlank { "00:1A:79:" } ?: "00:1A:79:") }
    var stalkerUser by remember { mutableStateOf(initial?.stalkerUsername ?: "") }
    var stalkerPass by remember { mutableStateOf(initial?.stalkerPassword ?: "") }
    var serial by remember { mutableStateOf(initial?.serialNumber ?: "") }
    var deviceId by remember { mutableStateOf(initial?.deviceId ?: "") }
    var sendDeviceId by remember { mutableStateOf(initial?.sendDeviceId ?: true) }

    // Shared options (all source types).
    var epgUrl by remember { mutableStateOf(initial?.epgUrl ?: "") }
    var dnsProvider by remember { mutableStateOf(initial?.dnsProvider ?: XtreamAccount.DNS_SYSTEM) }
    var autoRefreshHours by remember { mutableStateOf(initial?.autoRefreshHours ?: XtreamAccount.DEFAULT_AUTO_REFRESH_HOURS) }
    var showRefreshPicker by remember { mutableStateOf(false) }

    val firstFieldFocus = remember { FocusRequester() }
    LaunchedEffect(manualMode, sourceType) { runCatching { firstFieldFocus.requestFocus() } }

    val options = {
        XtreamSettingsViewModel.PlaylistOptions(
            epgUrl = epgUrl.trim().ifEmpty { null },
            dnsProvider = dnsProvider,
            autoRefreshHours = autoRefreshHours
        )
    }
    val submit = {
        if (!isValidating) when (sourceType) {
            XtreamAccount.SOURCE_XTREAM -> {
                if (manualMode) {
                    if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                        onSubmitManual(server.trim(), user.trim(), pass.trim(), name.trim().ifEmpty { null }, options())
                    }
                } else if (url.isNotBlank()) {
                    onSubmitUrl(url.trim(), options())
                }
            }
            XtreamAccount.SOURCE_URL -> {
                if (m3uUrl.isNotBlank()) {
                    onSubmitM3U(m3uUrl.trim(), m3uUserAgent.trim().ifEmpty { null }, name.trim().ifEmpty { null }, options())
                }
            }
            XtreamAccount.SOURCE_FILE -> {
                pickedFileUri?.let { uri ->
                    onSubmitFile(uri, pickedFileName.ifBlank { "playlist.m3u" }, name.trim().ifEmpty { null }, options())
                }
            }
            XtreamAccount.SOURCE_STALKER -> {
                if (portalUrl.isNotBlank() && mac.isNotBlank()) {
                    onSubmitStalker(
                        XtreamSettingsViewModel.StalkerFields(
                            portalUrl = portalUrl.trim(),
                            macAddress = mac.trim(),
                            username = stalkerUser.trim(),
                            password = stalkerPass.trim(),
                            serialNumber = serial.trim(),
                            deviceId = deviceId.trim(),
                            sendDeviceId = sendDeviceId
                        ),
                        name.trim().ifEmpty { null },
                        options()
                    )
                }
            }
        }
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = if (initial != null) "Edit Playlist" else "Add Playlist",
        subtitle = if (initial != null) "Update this playlist's source and options" else "Add an IPTV source and choose its options",
        width = 760.dp,
        suppressFirstKeyUp = false
    ) {
        // The form is tall; scroll it so every field stays D-pad reachable inside the dialog.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = PlaylistFormMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            // --- Source Type -------------------------------------------------
            FormSectionLabel("Source Type")
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                PLAYLIST_SOURCES.forEach { source ->
                    SourceTypeTile(
                        source = source,
                        selected = sourceType == source.id,
                        onClick = { if (source.enabled) sourceType = source.id }
                    )
                }
            }

            when (sourceType) {
                XtreamAccount.SOURCE_XTREAM -> {
                    // Xtream = portal (Server URL) + username + password. Enter details is primary;
                    // Paste link is a secondary convenience.
                    Row(modifier = Modifier.padding(top = NuvioTheme.spacing.xs)) {
                        SettingsChoiceChip("Enter details", selected = manualMode, onClick = { manualMode = true })
                        Spacer(Modifier.width(NuvioTheme.spacing.sm))
                        SettingsChoiceChip("Paste link", selected = !manualMode, onClick = { manualMode = false })
                    }
                    if (manualMode) {
                        // Pasting a full get.php URL into Server URL auto-fills username/password.
                        XtreamField(server, { input ->
                            val parsed = parseXtreamAccount(input)
                            if (parsed != null) { server = parsed.baseUrl; user = parsed.username; pass = parsed.password }
                            else server = input
                        }, "Server URL  (portal, e.g. http://host:port)", firstFieldFocus, onSubmit = submit)
                        XtreamField(user, { user = it }, "Username", onSubmit = submit)
                        XtreamField(pass, { pass = it }, "Password", isPassword = true, onSubmit = submit)
                        XtreamField(name, { name = it }, "Name (optional)", onSubmit = submit)
                    } else {
                        XtreamField(url, { url = it }, "http://host:port/get.php?username=…&password=…", firstFieldFocus, onSubmit = submit)
                    }
                }
                XtreamAccount.SOURCE_URL -> UrlSourceFields(
                    url = m3uUrl,
                    onUrlChange = { m3uUrl = it },
                    userAgent = m3uUserAgent,
                    onUserAgentChange = { m3uUserAgent = it },
                    name = name,
                    onNameChange = { name = it },
                    firstFieldFocus = firstFieldFocus,
                    onSubmit = submit
                )
                XtreamAccount.SOURCE_FILE -> FileSourceFields(
                    pickerAvailable = filePickerAvailable,
                    pickedFileName = pickedFileName,
                    reimport = initial?.sourceType == XtreamAccount.SOURCE_FILE,
                    onChooseFile = { runCatching { filePicker.launch(arrayOf("*/*")) } },
                    name = name,
                    onNameChange = { name = it },
                    onSubmit = submit
                )
                XtreamAccount.SOURCE_STALKER -> StalkerSourceFields(
                    portalUrl = portalUrl, onPortalUrlChange = { portalUrl = it },
                    mac = mac, onMacChange = { mac = it },
                    username = stalkerUser, onUsernameChange = { stalkerUser = it },
                    password = stalkerPass, onPasswordChange = { stalkerPass = it },
                    serial = serial, onSerialChange = { serial = it },
                    deviceId = deviceId, onDeviceIdChange = { deviceId = it },
                    sendDeviceId = sendDeviceId, onSendDeviceIdChange = { sendDeviceId = it },
                    firstFieldFocus = firstFieldFocus,
                    onSubmit = submit
                )
            }

            // --- EPG URL (shared) --------------------------------------------
            FormSectionLabel("EPG URL (optional)")
            XtreamField(epgUrl, { epgUrl = it }, "http://host:port/xmltv.php?username=…&password=…", onSubmit = submit)

            // --- DNS Provider (shared) ---------------------------------------
            FormSectionLabel("DNS Provider")
            DnsProviderTiles(selected = dnsProvider, onSelect = { dnsProvider = it })
            FormHelperText("Choose a DNS server for resolving this playlist's addresses. Cloudflare or Google can improve connection reliability.")

            // --- Auto-Refresh (shared) ---------------------------------------
            FormSectionLabel("Auto-Refresh")
            SettingsActionRow(
                title = "Auto-Refresh",
                subtitle = null,
                value = autoRefreshLabel(autoRefreshHours),
                onClick = { showRefreshPicker = true }
            )
            FormHelperText("Periodically check this playlist for new movies and series.")

            // --- Save --------------------------------------------------------
            XtreamAddButton(
                isValidating = isValidating,
                label = if (initial != null) "Save changes" else "Add Playlist",
                enabled = when (sourceType) {
                    XtreamAccount.SOURCE_XTREAM, XtreamAccount.SOURCE_URL -> true
                    // File: only submittable once a document is actually picked.
                    XtreamAccount.SOURCE_FILE -> pickedFileUri != null
                    else -> false
                },
                onClick = submit
            )

            val status = when {
                isValidating -> "Verifying…"
                error != null -> error
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null && !isValidating) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary
                )
            }
        }
    }

    if (showRefreshPicker) {
        SettingsSingleChoiceDialog(
            title = "Auto-Refresh",
            options = XtreamAccount.AUTO_REFRESH_OPTIONS.map {
                SettingsPickerOption(value = it, title = autoRefreshLabel(it))
            },
            selectedValue = autoRefreshHours,
            onOptionSelected = { autoRefreshHours = it; showRefreshPicker = false },
            onDismiss = { showRefreshPicker = false }
        )
    }
}

/** Max height for the scrollable form body inside the dialog (dialog itself is height-capped too). */
private val PlaylistFormMaxHeight = 460.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextPrimary
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FormHelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary
    )
}

/**
 * A source-type tile built on the settings Card vocabulary (FocusRing border, no scale/flood).
 * Disabled tiles dim their label and show a "Soon" badge; they can be focused (so D-pad traversal
 * isn't trapped) but selecting is a no-op.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceTypeTile(
    source: PlaylistSource,
    selected: Boolean,
    onClick: () -> Unit
) {
    val alpha = if (source.enabled) 1f else 0.4f
    Card(
        onClick = onClick,
        colors = CardDefaults.colors(
            containerColor = if (selected) NuvioTheme.colors.FocusRing.copy(alpha = 0.2f) else NuvioTheme.colors.Background,
            focusedContainerColor = if (selected) NuvioTheme.colors.FocusRing.copy(alpha = 0.2f) else NuvioTheme.colors.Background
        ),
        border = CardDefaults.border(
            border = if (selected) Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            ) else Border.None,
            focusedBorder = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.FocusRing),
                shape = RoundedCornerShape(SettingsPillRadius)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(SettingsPillRadius)),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = source.label,
                style = MaterialTheme.typography.labelMedium,
                color = (if (selected) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary).copy(alpha = alpha)
            )
            if (!source.enabled) {
                Spacer(Modifier.width(NuvioTheme.spacing.sm))
                SoonBadge()
            }
        }
    }
}

/** Small "Soon" pill for not-yet-enabled source tiles. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SoonBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(SettingsPillRadius))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
    ) {
        Text(
            text = "Soon",
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

/** DNS provider tiles wrapped across rows (3 per row) — reuses the SettingsChoiceChip pattern. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsProviderTiles(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        DNS_OPTIONS.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                rowItems.forEach { option ->
                    SettingsChoiceChip(
                        label = option.label,
                        selected = selected == option.id,
                        onClick = { onSelect(option.id) }
                    )
                }
            }
        }
    }
}

/**
 * Field layout for a plain M3U/URL playlist source: the playlist URL (required) plus an optional
 * User-Agent and display name. The parsed playlist becomes the catalog (streamed into the content
 * DB on save) — there's no Xtream API, so no username/password. Uses the same [XtreamField] design
 * vocabulary as the Xtream form.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UrlSourceFields(
    url: String,
    onUrlChange: (String) -> Unit,
    userAgent: String,
    onUserAgentChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    firstFieldFocus: FocusRequester,
    onSubmit: () -> Unit
) {
    XtreamField(url, onUrlChange, "M3U URL  (http://host/get.php?…&type=m3u_plus  or  …/playlist.m3u)", firstFieldFocus, onSubmit = onSubmit)
    XtreamField(userAgent, onUserAgentChange, "User-Agent (optional)", onSubmit = onSubmit)
    XtreamField(name, onNameChange, "Name (optional)", onSubmit = onSubmit)
    FormHelperText("Paste the playlist URL. The whole list is downloaded and indexed once so it browses fast; large lists take a moment after saving.")
}

/**
 * Field layout for an M3U FILE source. When a documents UI is available, a "Choose file" button
 * launches ACTION_OPEN_DOCUMENT (via the parent's launcher) and the picked file's name is shown;
 * the file is copied into app storage on save and ingested like a URL playlist. When NO documents
 * UI resolves the intent (common on Android TV), the button is replaced by an honest message
 * pointing the user at the URL source or their phone (file playlists sync as an account, so a phone
 * import shows up here to re-import). [reimport] tweaks the copy for a lost-local-file re-pick.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FileSourceFields(
    pickerAvailable: Boolean,
    pickedFileName: String,
    reimport: Boolean,
    onChooseFile: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    if (!pickerAvailable) {
        FormHelperText("No file picker on this device — add the file by URL or from your phone (it syncs).")
        return
    }
    if (reimport && pickedFileName.isNotBlank()) {
        FormHelperText("This file playlist was added on another device. Re-import \"$pickedFileName\" to browse it here.")
    }
    SettingsActionRow(
        title = if (pickedFileName.isBlank()) "Choose file" else "Change file",
        subtitle = pickedFileName.ifBlank { "Pick an .m3u / .m3u8 (or .gz) playlist file" },
        value = null,
        onClick = onChooseFile
    )
    XtreamField(name, onNameChange, "Name (optional)", onSubmit = onSubmit)
    FormHelperText("The file is copied into the app so it keeps working if the original moves or is deleted. File contents don't sync across devices.")
}

/** Best-effort human display name for a picked document uri (falls back to null). */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    }.getOrNull()

/** Field layout for a Stalker portal (MAG/Ministra) source: portal URL + virtual STB MAC + optionals. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StalkerSourceFields(
    portalUrl: String, onPortalUrlChange: (String) -> Unit,
    mac: String, onMacChange: (String) -> Unit,
    username: String, onUsernameChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    serial: String, onSerialChange: (String) -> Unit,
    deviceId: String, onDeviceIdChange: (String) -> Unit,
    sendDeviceId: Boolean, onSendDeviceIdChange: (Boolean) -> Unit,
    firstFieldFocus: FocusRequester,
    onSubmit: () -> Unit
) {
    XtreamField(portalUrl, onPortalUrlChange, "Portal URL  (http://host:port)", firstFieldFocus, onSubmit = onSubmit)
    XtreamField(mac, onMacChange, "MAC Address  (00:1A:79:xx:xx:xx)", onSubmit = onSubmit)
    SettingsActionRow(
        title = "Randomize MAC",
        subtitle = "Generate a virtual STB MAC (Infomir range)",
        value = null,
        onClick = { onMacChange(randomStbMac()) }
    )
    XtreamField(username, onUsernameChange, "Username (optional)", onSubmit = onSubmit)
    XtreamField(password, onPasswordChange, "Password (optional)", isPassword = true, onSubmit = onSubmit)
    XtreamField(serial, onSerialChange, "Serial Number (optional)", onSubmit = onSubmit)
    XtreamField(deviceId, onDeviceIdChange, "Device ID (optional)", onSubmit = onSubmit)
    SettingsActionRow(
        title = "Send Device ID",
        subtitle = "Include device identifier in portal requests",
        value = if (sendDeviceId) "On" else "Off",
        onClick = { onSendDeviceIdChange(!sendDeviceId) }
    )
    FormHelperText("Enter your portal URL and the MAC registered with the provider. Serial / Device ID override the values derived from the MAC.")
}

/** A virtual STB MAC in Infomir's 00:1A:79 range (the OUI most Stalker portals accept). */
private fun randomStbMac(): String {
    val tail = (0 until 3).joinToString(":") { "%02X".format(kotlin.random.Random.nextInt(0, 256)) }
    return "00:1A:79:$tail"
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
private fun XtreamAddButton(
    isValidating: Boolean,
    label: String = "Add account",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val contentAlpha = if (enabled) 1f else 0.4f
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
                if (enabled &&
                    (it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
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
            color = NuvioTheme.colors.TextPrimary.copy(alpha = contentAlpha)
        )
    }
}

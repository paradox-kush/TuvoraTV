package com.nuvio.tv.ui.screens.iptv

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Top-level IPTV hub (a main-nav destination). Defaults to the first account with a dropdown to
 * switch playlists; Live/Movies/Series tabs; native category rows. Empty state sends the user to
 * Settings to add a provider.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamHubScreen(
    onPlayChannel: (title: String, streamUrl: String, contentId: String) -> Unit,
    onOpenDetail: (contentId: String, type: String) -> Unit,
    onAddProvider: () -> Unit,
    viewModel: XtreamHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAccountPicker by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    // Per-tab focus targets so D-pad UP from the first content row lands back on the
    // active tab, and focusRestorer restores focus to it when returning to the header.
    val liveTab = remember { FocusRequester() }
    val moviesTab = remember { FocusRequester() }
    val seriesTab = remember { FocusRequester() }
    val selectedTabRequester = when (uiState.section) {
        XtreamSection.LIVE -> liveTab
        XtreamSection.MOVIES -> moviesTab
        XtreamSection.SERIES -> seriesTab
    }

    // Movies/Series tile -> native detail. (Live is handled by the TiViMate guide below.)
    val onActivate: (XtreamHubItem) -> Unit = { hit ->
        hit.contentId?.let { onOpenDetail(it, hit.detailType) }
    }

    // Empty state -> prompt to add a provider in Settings.
    if (!uiState.loading && uiState.accounts.isEmpty()) {
        LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
        Column(
            modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No IPTV provider yet", style = MaterialTheme.typography.headlineSmall, color = NuvioTheme.colors.TextPrimary)
            Spacer(Modifier.padding(top = NuvioTheme.spacing.sm))
            Text(
                "Add your Xtream Codes portal or M3U URL to watch Live TV, Movies and Series.",
                style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.padding(top = NuvioTheme.spacing.lg))
            HubChip("Add IPTV provider", selected = true, focusRequester = firstFocus, onClick = onAddProvider)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = NuvioTheme.spacing.xl)) {
        // Header: account dropdown + section tabs
        Row(
            modifier = Modifier
                .padding(start = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
                .focusRestorer(selectedTabRequester),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            HubChip(
                label = (uiState.selectedAccount?.name ?: "Account") + (if (uiState.accounts.size > 1) "  ▾" else ""),
                selected = true,
                focusRequester = firstFocus,
                onClick = { if (uiState.accounts.size > 1) showAccountPicker = true }
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            HubChip("Live TV", uiState.section == XtreamSection.LIVE, focusRequester = liveTab) { viewModel.selectSection(XtreamSection.LIVE) }
            HubChip("Movies", uiState.section == XtreamSection.MOVIES, focusRequester = moviesTab) { viewModel.selectSection(XtreamSection.MOVIES) }
            HubChip("Series", uiState.section == XtreamSection.SERIES, focusRequester = seriesTab) { viewModel.selectSection(XtreamSection.SERIES) }
        }

        // Live TV = TiViMate-style guide (category col + live preview + EPG channel list).
        // Movies/Series = native poster rows.
        val liveAccount = uiState.selectedAccount
        if (uiState.section == XtreamSection.LIVE && liveAccount != null) {
            LiveGuide(account = liveAccount, onPlayChannel = onPlayChannel, selectedTabRequester = selectedTabRequester)
        } else {
            val status = when {
                uiState.error != null -> uiState.error
                uiState.loading -> "Loading…"
                uiState.categories.isEmpty() -> "Nothing here"
                else -> null
            }
            if (status != null) {
                Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
            ) {
                itemsIndexed(uiState.categories, key = { _, it -> "${uiState.section}_${it.id}" }) { index, category ->
                    LaunchedEffect(uiState.selectedAccountId, uiState.section, category.id) {
                        viewModel.loadCategory(category.id)
                    }
                    val loaded = uiState.itemsByCategory.containsKey(category.id)
                    val items = uiState.itemsByCategory[category.id].orEmpty()
                    if (!loaded || items.isNotEmpty()) {
                        HubChannelRow(
                            title = category.name, catalogId = category.id,
                            items = items, isLoading = !loaded, onActivate = onActivate,
                            // Only the first row routes UP back to the active tab.
                            upFocusRequester = if (index == 0) selectedTabRequester else null
                        )
                    }
                }
            }
        }
    }

    if (showAccountPicker) {
        NuvioDialog(onDismiss = { showAccountPicker = false }, title = "Choose provider") {
            uiState.accounts.forEach { acc ->
                com.nuvio.tv.ui.screens.settings.SettingsActionRow(
                    title = acc.name,
                    subtitle = acc.baseUrl,
                    value = if (acc.id == uiState.selectedAccountId) "Current" else null,
                    onClick = { viewModel.selectAccount(acc.id); showAccountPicker = false }
                )
            }
        }
    }
}

@Composable
private fun HubChannelRow(
    title: String,
    catalogId: String,
    items: List<XtreamHubItem>,
    isLoading: Boolean,
    onActivate: (XtreamHubItem) -> Unit,
    upFocusRequester: FocusRequester? = null,
) {
    CatalogRowSection(
        catalogRow = CatalogRow(
            addonId = "xtream", addonName = "", addonBaseUrl = "",
            catalogId = catalogId, catalogName = title,
            type = ContentType.MOVIE,
            items = items.map { it.toMetaPreview() },
            isLoading = isLoading
        ),
        onItemClick = { itemId, _, _ -> items.firstOrNull { it.cardId == itemId }?.let(onActivate) },
        showSeeAll = false,
        showAddonName = false,
        showCatalogTypeSuffix = false,
        upFocusRequester = upFocusRequester,
    )
}

private fun XtreamHubItem.toMetaPreview(): MetaPreview = MetaPreview(
    id = cardId,
    type = if (isLive) ContentType.TV else ContentType.MOVIE,
    name = name,
    poster = poster,
    posterShape = if (isLive) PosterShape.LANDSCAPE else PosterShape.POSTER,
    background = null, logo = null, description = null, releaseInfo = null,
    imdbRating = null, genres = emptyList()
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HubChip(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .clip(RoundedCornerShape(8.dp))
                // Filled primary tint marks the active section even when focus is elsewhere;
                // focus adds a brighter fill on top so the two states stay distinguishable.
                .background(
                    when {
                        focused -> NuvioTheme.colors.Primary
                        selected -> NuvioTheme.colors.Primary.copy(alpha = 0.28f)
                        else -> NuvioTheme.colors.BackgroundElevated
                    }
                )
                .border(
                    1.dp,
                    if (focused || selected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border,
                    RoundedCornerShape(8.dp)
                )
                .focusable()
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent {
                    if ((it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            it.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER) &&
                        it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                    ) { onClick(); true } else false
                }
                .padding(horizontal = 16.dp, vertical = NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected || focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
            )
        }
        // Underline indicator so the active tab is obvious at a glance.
        Spacer(Modifier.padding(top = 3.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) NuvioTheme.colors.Primary else Color.Transparent)
        )
    }
}

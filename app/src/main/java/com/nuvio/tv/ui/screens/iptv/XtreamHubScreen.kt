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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.view.KeyEvent
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.R
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
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable

/**
 * Top-level IPTV hub (a main-nav destination). Defaults to the first account with a dropdown to
 * switch playlists; Live/Movies/Series tabs; native category rows. Empty state sends the user to
 * Settings to add a provider.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamHubScreen(
    onOpenDetail: (contentId: String, type: String) -> Unit,
    onAddProvider: () -> Unit,
    onPairFromPhone: () -> Unit = {},
    viewModel: XtreamHubViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val heroItems by viewModel.heroItems.collectAsStateWithLifecycle()
    var showAccountPicker by remember { mutableStateOf(false) }
    // B10: the Live guide's preview player expanded to fullscreen — hide the header row so the
    // video really covers the whole screen. Focus is locked inside the guide while true.
    var liveFullscreen by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    // Hero banner (Movies/Series): D-pad UP from row 0 lands here; UP from here lands on the tab.
    val heroFocus = remember { FocusRequester() }
    // First content row target so D-pad DOWN from the hero lands on the posters (not stuck on the banner).
    val firstRowFocus = remember { FocusRequester() }

    // Per-tab focus targets so D-pad UP from the first content row lands back on the
    // active tab, and focusRestorer restores focus to it when returning to the header.
    val liveTab = remember { FocusRequester() }
    val moviesTab = remember { FocusRequester() }
    val seriesTab = remember { FocusRequester() }
    // Disabled content types (per-playlist Content & Categories) hide their tab entirely.
    val account = uiState.selectedAccount
    val showLive = account?.typeEnabled(XtreamAccount.TYPE_LIVE) != false
    val showMovies = account?.typeEnabled(XtreamAccount.TYPE_MOVIES) != false
    val showSeries = account?.typeEnabled(XtreamAccount.TYPE_SERIES) != false
    val anyTypeEnabled = showLive || showMovies || showSeries
    val selectedTabRequester = when (uiState.section) {
        XtreamSection.LIVE -> liveTab
        XtreamSection.MOVIES -> moviesTab
        XtreamSection.SERIES -> seriesTab
    }
    // With every type disabled no tab requester is attached — restore focus to the account chip.
    val headerRestoreTarget = if (anyTypeEnabled) selectedTabRequester else firstFocus

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
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                HubChip("Add IPTV provider", selected = true, focusRequester = firstFocus, onClick = onAddProvider)
                // Typing on a TV is painful (and the TV may be signed out) — offer the phone-pairing
                // path as an equal-weight alternative right on the empty state (P5).
                HubChip(stringResource(R.string.iptv_pairing_entry_title), selected = false, onClick = onPairFromPhone)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = if (liveFullscreen) 0.dp else NuvioTheme.spacing.xl)) {
        // Header: account dropdown + section tabs
        if (!liveFullscreen) Row(
            modifier = Modifier
                .padding(start = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
                .focusRestorer(headerRestoreTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            // ponytail: the provider chip is NOT a section — render it un-selected so it doesn't
            // share the active-tab's primary tint (that read as "this is the selected section").
            HubChip(
                label = (uiState.selectedAccount?.name ?: "Account") + (if (uiState.accounts.size > 1) "  ▾" else ""),
                selected = false,
                focusRequester = firstFocus,
                onClick = { if (uiState.accounts.size > 1) showAccountPicker = true }
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            if (showLive) HubChip("Live TV", uiState.section == XtreamSection.LIVE, focusRequester = liveTab,
                onFocusSelect = { if (uiState.section != XtreamSection.LIVE) viewModel.selectSection(XtreamSection.LIVE) }) { viewModel.selectSection(XtreamSection.LIVE) }
            if (showMovies) HubChip("Movies", uiState.section == XtreamSection.MOVIES, focusRequester = moviesTab,
                onFocusSelect = { if (uiState.section != XtreamSection.MOVIES) viewModel.selectSection(XtreamSection.MOVIES) }) { viewModel.selectSection(XtreamSection.MOVIES) }
            if (showSeries) HubChip("Series", uiState.section == XtreamSection.SERIES, focusRequester = seriesTab,
                onFocusSelect = { if (uiState.section != XtreamSection.SERIES) viewModel.selectSection(XtreamSection.SERIES) }) { viewModel.selectSection(XtreamSection.SERIES) }
        }

        // Live TV = TiViMate-style guide (category col + live preview + EPG channel list).
        // Movies/Series = native poster rows.
        val liveAccount = uiState.selectedAccount
        if (!anyTypeEnabled) {
            Box(Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
                Text(
                    "All content types are hidden for this playlist. Enable them in Settings → IPTV → Content & Categories.",
                    style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary
                )
            }
        } else if (uiState.section == XtreamSection.LIVE && liveAccount != null) {
            LiveGuide(
                account = liveAccount,
                fullscreen = liveFullscreen,
                onFullscreenChange = { liveFullscreen = it },
                selectedTabRequester = selectedTabRequester
            )
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

            val showHero = heroItems.isNotEmpty()
            LazyColumn(
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
            ) {
                if (showHero) {
                    item(key = "${uiState.section}_hero") {
                        HeroCarousel(
                            items = heroItems.map { it.toMetaPreview() }.asStable(),
                            onItemClick = { preview -> heroItems.firstOrNull { it.cardId == preview.id }?.let(onActivate) },
                            focusRequester = heroFocus,
                            // UP from the hero returns to the active section tab; DOWN lands on the first poster row.
                            modifier = Modifier.focusProperties { up = selectedTabRequester; down = firstRowFocus }
                        )
                    }
                }
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
                            // First row routes UP to the hero when present, otherwise to the active tab.
                            upFocusRequester = if (index == 0) (if (showHero) heroFocus else selectedTabRequester) else null,
                            // DOWN from the hero lands on this first row's LazyRow.
                            rowFocusRequester = if (index == 0 && showHero) firstRowFocus else null
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
    rowFocusRequester: FocusRequester? = null,
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
        rowFocusRequester = rowFocusRequester,
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
    onFocusSelect: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocusSelect by rememberUpdatedState(onFocusSelect)
    // ponytail: section tabs switch on FOCUS (TiViMate-style) — arrow onto a tab and it
    // selects; no OK needed. OK/click still works via onKeyEvent below.
    LaunchedEffect(focused) { if (focused) latestOnFocusSelect?.invoke() }
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
                // ponytail: onFocusChanged before focusable, else the tab never shows its
                // focused highlight (can't tell which tab you're on).
                .onFocusChanged { focused = it.isFocused }
                .focusable()
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

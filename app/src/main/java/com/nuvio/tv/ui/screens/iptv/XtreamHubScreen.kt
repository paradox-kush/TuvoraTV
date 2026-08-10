package com.nuvio.tv.ui.screens.iptv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.placeholderCardShimmer
import com.nuvio.tv.ui.components.rememberPlaceholderShimmerOffsetState
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.abs

/**
 * Top-level IPTV hub (a main-nav destination). Defaults to the first account with a dropdown to
 * switch playlists; Live/Movies/Series tabs; native category rows. Empty state sends the user to
 * Settings to add a provider.
 *
 * The Movies/Series rails deliberately mirror the Modern home layout metrics (52dp gutter,
 * 16sp SemiBold headers, 12dp item gap, the same poster-size-preference-derived card size) so the
 * hub reads as the same product as the home screen. No hero here — rows start immediately.
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
    var showAccountPicker by remember { mutableStateOf(false) }
    // B10: the Live guide's preview player expanded to fullscreen — hide the header row so the
    // video really covers the whole screen. Focus is locked inside the guide while true.
    var liveFullscreen by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

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
        HubNoProviderState(
            firstFocus = firstFocus,
            onAddProvider = onAddProvider,
            onPairFromPhone = onPairFromPhone
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = if (liveFullscreen) 0.dp else NuvioTheme.spacing.xl)) {
        // Header: account dropdown + section tabs
        if (!liveFullscreen) Row(
            modifier = Modifier
                .padding(start = HubRowStartPadding, bottom = NuvioTheme.spacing.md)
                .focusRestorer(headerRestoreTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            // ponytail: the provider chip is NOT a section — render it un-selected so it doesn't
            // share the active-tab's primary tint (that read as "this is the selected section").
            HubChip(
                label = uiState.selectedAccount?.name ?: stringResource(R.string.iptv_hub_account_fallback),
                selected = false,
                focusRequester = firstFocus,
                showDropdownIcon = uiState.accounts.size > 1,
                onClick = { if (uiState.accounts.size > 1) showAccountPicker = true }
            )
            Spacer(Modifier.width(NuvioTheme.spacing.md))
            if (showLive) HubChip(stringResource(R.string.iptv_hub_tab_live), uiState.section == XtreamSection.LIVE, focusRequester = liveTab,
                onFocusSelect = { if (uiState.section != XtreamSection.LIVE) viewModel.selectSection(XtreamSection.LIVE) }) { viewModel.selectSection(XtreamSection.LIVE) }
            if (showMovies) HubChip(stringResource(R.string.type_movies), uiState.section == XtreamSection.MOVIES, focusRequester = moviesTab,
                onFocusSelect = { if (uiState.section != XtreamSection.MOVIES) viewModel.selectSection(XtreamSection.MOVIES) }) { viewModel.selectSection(XtreamSection.MOVIES) }
            if (showSeries) HubChip(stringResource(R.string.type_series), uiState.section == XtreamSection.SERIES, focusRequester = seriesTab,
                onFocusSelect = { if (uiState.section != XtreamSection.SERIES) viewModel.selectSection(XtreamSection.SERIES) }) { viewModel.selectSection(XtreamSection.SERIES) }
        }

        // Live TV = TiViMate-style guide (category col + live preview + EPG channel list).
        // Movies/Series = native poster rows.
        val liveAccount = uiState.selectedAccount
        if (!anyTypeEnabled) {
            EmptyScreenState(
                title = stringResource(R.string.iptv_hub_types_hidden_title),
                subtitle = stringResource(R.string.iptv_hub_types_hidden_subtitle),
                height = 320.dp
            )
        } else if (uiState.section == XtreamSection.LIVE && liveAccount != null) {
            LiveGuide(
                account = liveAccount,
                fullscreen = liveFullscreen,
                onFullscreenChange = { liveFullscreen = it },
                selectedTabRequester = selectedTabRequester
            )
        } else {
            HubBrowseContent(
                uiState = uiState,
                onActivate = onActivate,
                onRetry = viewModel::retry,
                onLoadCategory = viewModel::loadCategory,
                onPrefetchCategory = viewModel::prefetchCategory,
                onLoadMoreCategory = viewModel::loadMoreCategory,
                selectedTabRequester = selectedTabRequester
            )
        }
    }

    if (showAccountPicker) {
        NuvioDialog(onDismiss = { showAccountPicker = false }, title = stringResource(R.string.iptv_hub_choose_provider)) {
            uiState.accounts.forEach { acc ->
                com.nuvio.tv.ui.screens.settings.SettingsActionRow(
                    title = acc.name,
                    subtitle = acc.baseUrl,
                    value = if (acc.id == uiState.selectedAccountId) stringResource(R.string.iptv_hub_provider_current) else null,
                    onClick = { viewModel.selectAccount(acc.id); showAccountPicker = false }
                )
            }
        }
    }
}

/**
 * The Movies/Series browse area below the header: Modern-home-parity category rails with shimmer
 * placeholders while loading, the shared [ErrorState]/[EmptyScreenState] for failures/emptiness.
 */
@Composable
private fun HubBrowseContent(
    uiState: XtreamHubUiState,
    onActivate: (XtreamHubItem) -> Unit,
    onRetry: () -> Unit,
    onLoadCategory: (String) -> Unit,
    onPrefetchCategory: (String) -> Unit,
    onLoadMoreCategory: (String) -> Unit,
    selectedTabRequester: FocusRequester
) {
    // The hub's card size derives from the SAME poster-size preference as Modern home, scaled by
    // Modern's catalog factors, so both surfaces resize together from Layout settings.
    val portraitStyle = remember(uiState.posterCardWidthDp, uiState.posterCardHeightDp, uiState.posterCardCornerRadiusDp) {
        PosterCardDefaults.Style.copy(
            width = uiState.posterCardWidthDp.dp * HUB_PORTRAIT_CARD_SCALE,
            height = uiState.posterCardHeightDp.dp * HUB_PORTRAIT_CARD_SCALE,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp
        )
    }
    val landscapeStyle = remember(uiState.posterCardWidthDp, uiState.posterCardCornerRadiusDp) {
        val width = uiState.posterCardWidthDp.dp * HUB_LANDSCAPE_CARD_SCALE
        PosterCardDefaults.Style.copy(
            width = width,
            height = width / HUB_LANDSCAPE_CARD_ASPECT,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp
        )
    }

    when {
        uiState.error != null -> ErrorState(message = uiState.error, onRetry = onRetry)
        uiState.loading && uiState.categories.isEmpty() -> HubSkeletonRows(cardStyle = portraitStyle)
        uiState.categories.isEmpty() -> EmptyScreenState(
            title = stringResource(R.string.iptv_hub_empty_title),
            subtitle = stringResource(R.string.iptv_hub_empty_subtitle)
        )
        else -> {
            val sharedShimmerOffsetState = rememberPlaceholderShimmerOffsetState(label = "hubRowShimmer")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
                contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxxl)
            ) {
                itemsIndexed(uiState.categories, key = { _, it -> "${uiState.section}_${it.id}" }) { index, category ->
                    // Keyed on the account/section too: a category id that survives a section
                    // switch would otherwise keep its stale effect and never reload.
                    LaunchedEffect(uiState.selectedAccountId, uiState.section, category.id) {
                        onLoadCategory(category.id)
                        // Give the next few rows a head start so they land with posters and names
                        // instead of shimmer. The view model caps how many of these can actually be
                        // in flight and drops the rest.
                        for (offset in 1..CATEGORY_PREFETCH_LOOKAHEAD) {
                            val next = uiState.categories.getOrNull(index + offset) ?: break
                            onPrefetchCategory(next.id)
                        }
                    }
                    val loaded = uiState.itemsByCategory.containsKey(category.id)
                    val items = uiState.itemsByCategory[category.id].orEmpty()
                    if (!loaded || items.isNotEmpty()) {
                        HubPosterRow(
                            title = category.name,
                            rowKey = "${uiState.section}_${category.id}",
                            items = items,
                            isLoading = !loaded,
                            hasMore = uiState.hasMoreByCategory[category.id] == true,
                            onNearEnd = { onLoadMoreCategory(category.id) },
                            portraitStyle = portraitStyle,
                            landscapeStyle = landscapeStyle,
                            showLabels = uiState.posterLabelsEnabled,
                            placeholderShimmerOffsetState = sharedShimmerOffsetState,
                            onActivate = onActivate,
                            // First row routes UP to the active tab.
                            upFocusRequester = if (index == 0) selectedTabRequester else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * One hub rail with the Modern home row metrics: 16sp SemiBold [MaterialTheme.typography.titleMedium]
 * header at the 52dp gutter with a 14dp bottom gap, 52dp rail content padding, 12dp item gap.
 * While loading it renders "placeholder://empty" previews, which [ContentCard] draws as the same
 * shimmer cards the home rows use.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun HubPosterRow(
    title: String,
    rowKey: String,
    items: List<XtreamHubItem>,
    isLoading: Boolean,
    hasMore: Boolean = false,
    onNearEnd: (() -> Unit)? = null,
    portraitStyle: PosterCardStyle,
    landscapeStyle: PosterCardStyle,
    showLabels: Boolean,
    placeholderShimmerOffsetState: State<Float>,
    onActivate: (XtreamHubItem) -> Unit,
    upFocusRequester: FocusRequester? = null,
) {
    // Loading rows render shimmer placeholders through the same "placeholder://empty" contract
    // the home pipeline uses (HomeViewModelCatalogPipeline), so ContentCard shows its shimmer.
    val previews = remember(rowKey, items, isLoading) {
        if (isLoading && items.isEmpty()) {
            List(HUB_PLACEHOLDER_CARDS) { i ->
                MetaPreview(
                    id = "__placeholder_${rowKey}_$i",
                    type = ContentType.MOVIE,
                    name = " ",
                    poster = "placeholder://empty",
                    posterShape = PosterShape.POSTER,
                    background = null, logo = null, description = null,
                    releaseInfo = null, imdbRating = null, genres = emptyList()
                )
            }
        } else {
            items.map { it.toMetaPreview() }
        }
    }

    Column {
        val titleMediumStyle = MaterialTheme.typography.titleMedium
        val rowTitleStyle = remember(titleMediumStyle) { titleMediumStyle.copy(fontWeight = FontWeight.SemiBold) }
        Text(
            text = title,
            style = rowTitleStyle,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = HubRowStartPadding, bottom = HubRowTitleBottom)
        )

        val itemFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }

        // Placeholder -> data swap replaces the LazyRow keys; if focus was sitting on a
        // placeholder it would be dropped mid-swap. Same rescue trick as CatalogRowSection:
        // notice the swap while the row has focus and re-request the first real card.
        val rowHasFocusRef = remember { mutableStateOf(false) }
        val wasPlaceholderRef = remember { mutableStateOf(previews.firstOrNull()?.id?.startsWith("__placeholder_") == true) }
        val isNowReal = previews.firstOrNull()?.id?.startsWith("__placeholder_") != true
        val needsFocusRescue = remember { mutableStateOf(false) }
        if (wasPlaceholderRef.value && isNowReal && rowHasFocusRef.value) {
            needsFocusRescue.value = true
        }
        wasPlaceholderRef.value = !isNowReal
        LaunchedEffect(needsFocusRescue.value) {
            if (!needsFocusRescue.value) return@LaunchedEffect
            repeat(15) {
                val ok = itemFocusRequesters[0]?.let { req ->
                    runCatching { req.requestFocus() }.isSuccess
                } == true
                if (ok) {
                    needsFocusRescue.value = false
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
            needsFocusRescue.value = false
        }

        // Left-align the focused card to the 52dp gutter while scrolling, like the Modern rows.
        val density = LocalDensity.current
        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val horizontalBringIntoViewSpec = remember(density, defaultBringIntoViewSpec, isRtl) {
            val startPx = with(density) { HubRowStartPadding.roundToPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    val childSize = abs(size)
                    val childSmallerThanParent = childSize <= containerSize
                    if (isRtl) {
                        val initialTarget = containerSize - startPx.toFloat()
                        val targetForTrailingEdge =
                            if (childSmallerThanParent && initialTarget < childSize) childSize else initialTarget
                        return (offset + size) - targetForTrailingEdge
                    }
                    val target = startPx.toFloat()
                    val space = containerSize - target
                    val leading = if (childSmallerThanParent && space < childSize) containerSize - childSize else target
                    return offset - leading
                }
            }
        }

        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalBringIntoViewSpec) {
            LazyRow(
                modifier = Modifier
                    .onFocusChanged { rowHasFocusRef.value = it.hasFocus }
                    .focusRestorer { itemFocusRequesters[0] ?: FocusRequester.Default }
                    .focusGroup(),
                contentPadding = PaddingValues(horizontal = HubRowStartPadding),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                itemsIndexed(previews, key = { _, preview -> preview.id }) { index, preview ->
                    // Item-5 window append: composing the LAST loaded tile of a longer category
                    // pulls the next window in — endless-scroll inside the row.
                    if (hasMore && onNearEnd != null && index == previews.lastIndex) {
                        LaunchedEffect(rowKey, previews.size) { onNearEnd() }
                    }
                    val isPlaceholder = preview.id.startsWith("__placeholder_")
                    // ContentCard sizes LANDSCAPE cards from a fixed constant — hand it a
                    // POSTER-shaped item with landscape dimensions instead so live tiles track the
                    // Modern landscape variant sizing (and the user's card-size preference).
                    val isLandscape = preview.posterShape == PosterShape.LANDSCAPE
                    val cardItem = if (isLandscape) preview.copy(posterShape = PosterShape.POSTER) else preview
                    val requester = itemFocusRequesters.getOrPut(index) { FocusRequester() }
                    ContentCard(
                        item = cardItem,
                        posterCardStyle = if (isLandscape) landscapeStyle else portraitStyle,
                        showLabels = showLabels,
                        placeholderShimmerOffsetState = placeholderShimmerOffsetState,
                        focusRequester = requester,
                        onClick = {
                            if (!isPlaceholder) items.firstOrNull { it.cardId == preview.id }?.let(onActivate)
                        },
                        modifier = Modifier
                            .then(
                                if (upFocusRequester != null) {
                                    Modifier.focusProperties { up = upFocusRequester }
                                } else Modifier
                            )
                            .then(
                                if (isPlaceholder && index > 0) {
                                    Modifier.focusProperties { canFocus = false }
                                } else Modifier
                            )
                    )
                }
            }
        }
    }
}

/**
 * Full-screen loading stand-in for the first category fetch (no titles known yet): shimmer title
 * bars over shimmer card rows at the exact rail metrics, so the real rows land without a shift.
 */
@Composable
private fun HubSkeletonRows(cardStyle: PosterCardStyle) {
    val shimmerOffsetState = rememberPlaceholderShimmerOffsetState(label = "hubSkeleton")
    val cardShape = RoundedCornerShape(cardStyle.cornerRadius)
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
        repeat(HUB_SKELETON_ROWS) {
            Column {
                Box(
                    modifier = Modifier
                        .padding(start = HubRowStartPadding, bottom = HubRowTitleBottom)
                        .width(160.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.xs))
                        .placeholderCardShimmer(
                            shimmerOffsetState = shimmerOffsetState,
                            backgroundColor = NuvioTheme.colors.SurfaceVariant
                        )
                )
                Row(
                    modifier = Modifier.padding(horizontal = HubRowStartPadding),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    repeat(HUB_PLACEHOLDER_CARDS) {
                        Box(
                            modifier = Modifier
                                .width(cardStyle.width)
                                .height(cardStyle.height)
                                .clip(cardShape)
                                .placeholderCardShimmer(
                                    shimmerOffsetState = shimmerOffsetState,
                                    backgroundColor = NuvioTheme.colors.BackgroundCard
                                )
                        )
                    }
                }
            }
        }
    }
}

/** "No IPTV provider yet": the shared empty-state visuals plus the two provider-adding actions. */
@Composable
private fun HubNoProviderState(
    firstFocus: FocusRequester,
    onAddProvider: () -> Unit,
    onPairFromPhone: () -> Unit
) {
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EmptyScreenState(
            title = stringResource(R.string.iptv_hub_no_provider_title),
            subtitle = stringResource(R.string.iptv_hub_no_provider_subtitle),
            icon = Icons.Default.LiveTv,
            height = 240.dp
        )
        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            HubActionButton(
                text = stringResource(R.string.iptv_hub_add_provider),
                focusRequester = firstFocus,
                onClick = onAddProvider
            )
            // Typing on a TV is painful (and the TV may be signed out) — offer the phone-pairing
            // path as an equal-weight alternative right on the empty state (P5).
            HubActionButton(
                text = stringResource(R.string.iptv_pairing_entry_title),
                onClick = onPairFromPhone
            )
        }
    }
}

/** The app's standard button treatment (matches ErrorState's Retry): card fill, Primary on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HubActionButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Button(
        onClick = onClick,
        modifier = focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Text(text)
    }
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

/**
 * Header chip for the provider picker + section tabs. Section tabs switch on FOCUS
 * (TiViMate-style) — arrow onto a tab and it selects, no OK needed; OK/click still works.
 *
 * Focus and selection stay visually distinct (hard product rule): focus = solid Primary fill with
 * a 2dp FocusRing border; the selected-but-unfocused tab = translucent Primary fill, Primary
 * hairline border, and the underline bar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HubChip(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onFocusSelect: (() -> Unit)? = null,
    showDropdownIcon: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val latestOnFocusSelect by rememberUpdatedState(onFocusSelect)
    LaunchedEffect(focused) { if (focused) latestOnFocusSelect?.invoke() }
    val chipShape = NuvioTheme.shapes.chip
    val contentColor = when {
        focused -> NuvioTheme.colors.OnPrimary
        selected -> NuvioTheme.colors.TextPrimary
        else -> NuvioTheme.colors.TextSecondary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .onFocusChanged { focused = it.isFocused },
            shape = CardDefaults.shape(shape = chipShape),
            colors = CardDefaults.colors(
                containerColor = if (selected) NuvioTheme.colors.Primary.copy(alpha = 0.26f) else NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.Primary
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(
                        NuvioTheme.spacing.hairline,
                        if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.Border
                    ),
                    shape = chipShape
                ),
                focusedBorder = Border(
                    border = BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing),
                    shape = chipShape
                )
            ),
            scale = CardDefaults.scale(focusedScale = 1.02f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor
                )
                if (showDropdownIcon) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(NuvioTheme.spacing.lg)
                    )
                }
            }
        }
        // Underline marks the ACTIVE section even while focus is elsewhere — deliberately
        // different from the focus treatment (solid fill + ring).
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(24.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xxs))
                .background(if (selected) NuvioTheme.colors.Primary else Color.Transparent)
        )
    }
}

// Modern home's rail metrics (ModernHomeRowsList/ModernHomeRows) — the hub mirrors them exactly.
private val HubRowStartPadding = 52.dp
private val HubRowTitleBottom = 14.dp
// Modern home's card-size derivation (ModernHomeContent): poster-size preference x catalog scale.
private const val HUB_PORTRAIT_CARD_SCALE = 0.84f * 1.08f
private const val HUB_LANDSCAPE_CARD_SCALE = 1.24f * 1.34f
private const val HUB_LANDSCAPE_CARD_ASPECT = 1.77f
private const val HUB_PLACEHOLDER_CARDS = 8
private const val HUB_SKELETON_ROWS = 3

/**
 * How many rows past the one that just composed get their items fetched early. Deliberately small:
 * category responses are large, so the win (rows arriving filled in) has to stay cheap. The hard
 * bounds live in XtreamHubViewModel, which caps concurrent fetches and drops prefetches once
 * enough are outstanding.
 */
private const val CATEGORY_PREFETCH_LOOKAHEAD = 3

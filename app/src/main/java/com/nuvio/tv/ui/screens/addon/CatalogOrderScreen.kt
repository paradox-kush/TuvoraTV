@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import com.nuvio.tv.core.home.HomeSectionOrderPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.TvReorderList
import com.nuvio.tv.ui.components.tvReorderDisabledFocusBorder
import com.nuvio.tv.ui.screens.settings.SettingsVerticalScrollIndicators
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.delay

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Fork: Up / Down focus handles per Home section row. When a move parks a row at the top or bottom, its Up or
    // Down button is disabled, which would drop focus; `refocus` hands focus to the other arrow after the redraw.
    val sectionRequesters = remember {
        object {
            private val map = mutableMapOf<String, Pair<FocusRequester, FocusRequester>>()
            fun getValue(id: String) = map.getOrPut(id) { FocusRequester() to FocusRequester() }
        }
    }
    var refocus by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(uiState.sections) {
        val (id, pressedDown) = refocus ?: return@LaunchedEffect
        refocus = null
        val requesters = sectionRequesters.getValue(id)
        runCatching { (if (pressedDown) requesters.first else requesters.second).requestFocus() }
    }
    val homeSections: @Composable () -> Unit = {
        HomeSectionsBlock(
            heroEnabled = uiState.heroEnabled,
            sections = uiState.sections,
            enabled = uiState.movingKey == null,
            requesterFor = { sectionRequesters.getValue(it) },
            onReset = viewModel::resetSections,
            onToggle = viewModel::toggleSectionEnabled,
            onMoveUp = { index, id ->
                if (index - 1 == 0) refocus = id to false
                viewModel.moveSectionUp(id)
            },
            onMoveDown = { index, id ->
                if (index + 1 == uiState.sections.lastIndex) refocus = id to true
                viewModel.moveSectionDown(id)
            }
        )
    }
    val pinToMoveNotice = stringResource(R.string.catalog_order_pin_to_move_toast)
    var pinnedNoticeMessage by remember { mutableStateOf<String?>(null) }
    var pinnedNoticeNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(pinnedNoticeNonce) {
        if (pinnedNoticeMessage != null) {
            delay(3_000)
            pinnedNoticeMessage = null
        }
    }

    BackHandler(enabled = uiState.movingKey == null) {
        onBackPress()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
    ) {
        when {
            uiState.isLoading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
                ) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                        homeSections = homeSections,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            uiState.items.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
                ) {
                    CatalogOrderHeader(
                        followAddonsOrder = uiState.followAddonsOrder,
                        movingKey = uiState.movingKey,
                        onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                        homeSections = homeSections,
                    )
                    Text(
                        text = stringResource(R.string.catalog_order_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary,
                    )
                }
            }

            else -> {
                val reorderScrollState = rememberScrollState()
                val movingItem = uiState.movingKey?.let { movingKey ->
                    uiState.items.firstOrNull { it.key == movingKey }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                TvReorderList(
                    items = uiState.items,
                    key = { it.key },
                    canMove = { it.canMove && uiState.movingKey == null },
                    movingKey = uiState.movingKey,
                    onStartMove = viewModel::enterMoveMode,
                    onMoveBy = viewModel::moveDuringDrag,
                    onConfirmMove = { viewModel.confirmMove() },
                    onCancelMove = { viewModel.cancelMove() },
                    modifier = Modifier.fillMaxSize(),
                    scrollState = reorderScrollState,
                    contentPadding = PaddingValues(
                        start = NuvioTheme.spacing.xxxl,
                        end = NuvioTheme.spacing.xxxl,
                        top = NuvioTheme.spacing.xl,
                        bottom = NuvioTheme.spacing.xxxl,
                    ),
                    header = {
                        CatalogOrderHeader(
                            followAddonsOrder = uiState.followAddonsOrder,
                            movingKey = uiState.movingKey,
                            onToggleFollowAddons = viewModel::toggleFollowAddonsOrder,
                            homeSections = homeSections,
                        )
                    },
                    useHeaderAwareBringIntoView = true,
                    scrollToTopOnEdgeItem = true,
                ) { item, isMoving, onStartMove, onConfirmMove ->
                    CatalogOrderCard(
                        item = item,
                        isMoving = isMoving,
                        onStartMove = onStartMove,
                        onConfirmMove = onConfirmMove,
                        onPinnedMoveAttempt = {
                            pinnedNoticeMessage = pinToMoveNotice
                            pinnedNoticeNonce++
                        },
                        onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) },
                        actionsEnabled = uiState.movingKey == null || isMoving,
                    )
                }
                SettingsVerticalScrollIndicators(
                    state = reorderScrollState,
                    suppressBackward = movingItem != null && !movingItem.canMoveUp,
                    suppressForward = movingItem != null && !movingItem.canMoveDown,
                    forceBackward = movingItem?.canMoveUp == true,
                    forceForward = movingItem?.canMoveDown == true
                )
                }
            }
        }

        AnimatedVisibility(
            visible = pinnedNoticeMessage != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        ) {
            val visibleMessage = pinnedNoticeMessage ?: return@AnimatedVisibility
            Surface(
                shape = RoundedCornerShape(NuvioTheme.radii.md),
                colors = SurfaceDefaults.colors(containerColor = NuvioTheme.colors.BackgroundElevated),
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(NuvioTheme.radii.md),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = NuvioTheme.colors.Secondary,
                    )
                    Text(
                        text = visibleMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderHeader(
    followAddonsOrder: Boolean,
    movingKey: String?,
    onToggleFollowAddons: (Boolean) -> Unit,
    homeSections: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    // Fork: Home's own sections (hero, Continue Watching, Recent Channels, ...) come first, then the catalogs.
    homeSections()
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    Text(
        text = stringResource(R.string.home_order_catalogs_heading),
        style = MaterialTheme.typography.titleLarge,
        color = NuvioTheme.colors.TextPrimary,
    )
    Text(
        text = stringResource(R.string.catalog_order_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextSecondary,
    )
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.catalog_order_follow_addons),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.catalog_order_follow_addons_desc),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        Surface(
            onClick = {
                if (movingKey == null) {
                    onToggleFollowAddons(!followAddonsOrder)
                }
            },
            modifier = Modifier.semantics {
                role = Role.Switch
                toggleableState = if (followAddonsOrder) ToggleableState.On else ToggleableState.Off
            },
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = NuvioTheme.colors.FocusBackground
            ),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                )
            ),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Switch(
                    checked = followAddonsOrder,
                    onCheckedChange = null,
                    enabled = movingKey == null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NuvioTheme.colors.Secondary,
                        checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                    ),
                )
            }
        }
    }
    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    isMoving: Boolean,
    onStartMove: () -> Unit,
    onConfirmMove: () -> Unit,
    onPinnedMoveAttempt: () -> Unit,
    onToggleEnabled: () -> Unit,
    actionsEnabled: Boolean,
) {
    val cardColor by animateColorAsState(
        targetValue = if (isMoving) {
            NuvioTheme.colors.FocusBackground
        } else {
            NuvioTheme.colors.BackgroundCard
        },
        animationSpec = NuvioMotion.focusTween(),
        label = "catalogOrderCardColor",
    )
    val cardBorder = if (isMoving) {
        BorderStroke(NuvioTheme.spacing.xxs, NuvioTheme.colors.FocusRing)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(NuvioTheme.radii.md),
        border = cardBorder,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            ) {
                Column {
                    Text(
                        text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (item.isDisabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    val subtitle = if (item.isPinnedToTop) {
                        "${item.addonName} • ${stringResource(R.string.catalog_order_pinned_to_top)}"
                    } else {
                        item.addonName
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    if (item.isDisabled) {
                        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                        Text(
                            text = stringResource(R.string.catalog_order_disabled_on_home),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.Error
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val toggleLabel = stringResource(
                    if (item.isDisabled) R.string.catalog_order_enable else R.string.catalog_order_disable
                )
                Surface(
                    onClick = {
                        if (actionsEnabled && !isMoving) onToggleEnabled()
                    },
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        contentDescription = toggleLabel
                        toggleableState = if (item.isDisabled) ToggleableState.Off else ToggleableState.On
                    },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Switch(
                            checked = !item.isDisabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NuvioTheme.colors.Secondary,
                                checkedTrackColor = NuvioTheme.colors.Secondary.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
                if (item.isPinnedToTop) {
                    Button(
                        onClick = onPinnedMoveAttempt,
                        enabled = actionsEnabled,
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextSecondary,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.Primary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.catalog_order_pinned),
                        )
                    }
                } else {
                    Button(
                        onClick = if (isMoving) onConfirmMove else onStartMove,
                        enabled = isMoving || (item.canMove && actionsEnabled),
                        colors = if (isMoving) {
                            ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.Secondary,
                                contentColor = NuvioTheme.colors.OnSecondary,
                                focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                                focusedContentColor = NuvioTheme.colors.OnSecondary,
                            )
                        } else {
                            ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                contentColor = NuvioTheme.colors.TextSecondary,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                focusedContentColor = NuvioTheme.colors.Primary
                            )
                        },
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            ),
                            focusedDisabledBorder = tvReorderDisabledFocusBorder(RoundedCornerShape(NuvioTheme.radii.md))
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Reorder,
                            contentDescription = stringResource(R.string.cd_reorder)
                        )
                    }
                }

            }
        }
    }
}

private fun String.toDisplayTypeLabel(): String {
    return replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

/** Fork: Home's own sections, above the catalogs. Same Move up / Move down / Disable controls as before TvReorderList. */
@Composable
private fun HomeSectionsBlock(
    heroEnabled: Boolean,
    sections: List<HomeSectionOrderItem>,
    enabled: Boolean,
    requesterFor: (String) -> Pair<FocusRequester, FocusRequester>,
    onReset: () -> Unit,
    onToggle: (String) -> Unit,
    onMoveUp: (Int, String) -> Unit,
    onMoveDown: (Int, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_order_title),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.home_order_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            OrderTextButton(
                label = stringResource(R.string.home_order_reset),
                onClick = { if (enabled) onReset() }
            )
        }
        SectionOrderCard(
            title = stringResource(R.string.home_section_hero),
            description = stringResource(R.string.home_section_hero_desc),
            enabled = heroEnabled,
            canMoveUp = false,
            canMoveDown = false,
            showMoveButtons = false,
            onMoveUp = {},
            onMoveDown = {},
            onToggleEnabled = { if (enabled) onToggle(HomeSectionOrderPolicy.HERO) },
            upRequester = requesterFor(HomeSectionOrderPolicy.HERO).first,
            downRequester = requesterFor(HomeSectionOrderPolicy.HERO).second
        )
        sections.forEachIndexed { index, section ->
            val requesters = requesterFor(section.id)
            SectionOrderCard(
                title = sectionTitle(section.id),
                description = sectionDescription(section.id),
                enabled = section.enabled,
                canMoveUp = section.canMoveUp && enabled,
                canMoveDown = section.canMoveDown && enabled,
                showMoveButtons = true,
                onMoveUp = { onMoveUp(index, section.id) },
                onMoveDown = { onMoveDown(index, section.id) },
                onToggleEnabled = { if (enabled) onToggle(section.id) },
                upRequester = requesters.first,
                downRequester = requesters.second
            )
        }
    }
}

@Composable
private fun sectionTitle(id: String): String = when (id) {
    HomeSectionOrderPolicy.CONTINUE_WATCHING -> stringResource(R.string.continue_watching)
    HomeSectionOrderPolicy.LIVE_RECENTS -> stringResource(R.string.home_recent_channels)
    HomeSectionOrderPolicy.UPCOMING -> stringResource(R.string.upcoming_section_title)
    HomeSectionOrderPolicy.CATALOGS -> stringResource(R.string.home_section_catalogs)
    else -> id
}

@Composable
private fun sectionDescription(id: String): String = when (id) {
    HomeSectionOrderPolicy.CONTINUE_WATCHING -> stringResource(R.string.home_section_continue_watching_desc)
    HomeSectionOrderPolicy.LIVE_RECENTS -> stringResource(R.string.home_section_live_recents_desc)
    HomeSectionOrderPolicy.UPCOMING -> stringResource(R.string.home_section_upcoming_desc)
    HomeSectionOrderPolicy.CATALOGS -> stringResource(R.string.home_section_catalogs_desc)
    else -> ""
}

/** One of Home's own sections: the same Move up / Move down / Disable controls as a catalog row. */
@Composable
private fun SectionOrderCard(
    title: String,
    description: String,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showMoveButtons: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit,
    upRequester: FocusRequester,
    downRequester: FocusRequester
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NuvioTheme.colors.BackgroundCard),
        shape = RoundedCornerShape(NuvioTheme.radii.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (enabled) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                if (!enabled) {
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                    Text(
                        text = stringResource(R.string.catalog_order_disabled_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.Error
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showMoveButtons) {
                    OrderIconButton(
                        icon = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.cd_move_up),
                        enabled = canMoveUp,
                        onClick = onMoveUp,
                        modifier = Modifier.focusRequester(upRequester)
                    )
                    OrderIconButton(
                        icon = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.cd_move_down),
                        enabled = canMoveDown,
                        onClick = onMoveDown,
                        modifier = Modifier.focusRequester(downRequester)
                    )
                }
                OrderTextButton(
                    label = if (enabled) stringResource(R.string.catalog_order_disable) else stringResource(R.string.catalog_order_enable),
                    onClick = onToggleEnabled,
                    contentColor = if (enabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Success,
                    focusedContentColor = if (enabled) NuvioTheme.colors.Error else NuvioTheme.colors.Success
                )
            }
        }
    }
}

@Composable
private fun OrderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextSecondary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

@Composable
private fun OrderTextButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: androidx.compose.ui.graphics.Color = NuvioTheme.colors.TextSecondary,
    focusedContentColor: androidx.compose.ui.graphics.Color = NuvioTheme.colors.Primary
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = contentColor,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = focusedContentColor
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
    ) {
        Text(text = label)
    }
}

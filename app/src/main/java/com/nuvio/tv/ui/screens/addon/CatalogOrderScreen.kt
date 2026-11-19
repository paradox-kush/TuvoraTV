@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.addon

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.nuvio.tv.core.home.HomeSectionOrderPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.LoadingIndicator
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import kotlinx.coroutines.launch

@Composable
fun CatalogOrderScreen(
    viewModel: CatalogOrderViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Up / Down focus handles per section row. When a move parks a row at the top or bottom, its Up or Down button
    // is disabled, which would drop focus; `refocus` hands focus to the other arrow after the new order is drawn.
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

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            item(key = "header") {
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
                        onClick = {
                            viewModel.resetSections()
                            // Reset puts the rows back in their default places, so the list starts from the top again.
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                    )
                }
            }

            item(key = "section_hero") {
                SectionOrderCard(
                    title = stringResource(R.string.home_section_hero),
                    description = stringResource(R.string.home_section_hero_desc),
                    enabled = uiState.heroEnabled,
                    canMoveUp = false,
                    canMoveDown = false,
                    showMoveButtons = false,
                    onMoveUp = {},
                    onMoveDown = {},
                    onToggleEnabled = { viewModel.toggleSectionEnabled(HomeSectionOrderPolicy.HERO) },
                    upRequester = sectionRequesters.getValue(HomeSectionOrderPolicy.HERO).first,
                    downRequester = sectionRequesters.getValue(HomeSectionOrderPolicy.HERO).second
                )
            }

            itemsIndexed(
                items = uiState.sections,
                key = { _, section -> "section_${section.id}" }
            ) { index, section ->
                val requesters = sectionRequesters.getValue(section.id)
                SectionOrderCard(
                    title = sectionTitle(section.id),
                    description = sectionDescription(section.id),
                    enabled = section.enabled,
                    canMoveUp = section.canMoveUp,
                    canMoveDown = section.canMoveDown,
                    showMoveButtons = true,
                    onMoveUp = {
                        // The row is about to sit at the top: the Up button goes dark under the user's thumb, so
                        // focus moves on to Down once the new order is on screen.
                        if (index - 1 == 0) refocus = section.id to false // pressed Up
                        viewModel.moveSectionUp(section.id)
                    },
                    onMoveDown = {
                        if (index + 1 == uiState.sections.lastIndex) refocus = section.id to true
                        viewModel.moveSectionDown(section.id)
                    },
                    onToggleEnabled = { viewModel.toggleSectionEnabled(section.id) },
                    upRequester = requesters.first,
                    downRequester = requesters.second
                )
            }

            item(key = "catalogs_heading") {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                Text(
                    text = stringResource(R.string.home_order_catalogs_heading),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.catalog_order_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            item(key = "follow_addons") {
                val followAddonsLabel = stringResource(R.string.catalog_order_follow_addons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = followAddonsLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.catalog_order_follow_addons_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                    Switch(
                        checked = uiState.followAddonsOrder,
                        onCheckedChange = { viewModel.toggleFollowAddonsOrder(it) },
                        modifier = Modifier.semantics { contentDescription = followAddonsLabel },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NuvioTheme.colors.Primary,
                            checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            when {
                uiState.isLoading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = NuvioTheme.spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator()
                        }
                    }
                }

                uiState.items.isEmpty() -> {
                    item {
                        Text(
                            text = stringResource(R.string.catalog_order_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }

                else -> {
                    itemsIndexed(
                        items = uiState.items,
                        key = { _, item -> item.key }
                    ) { index, item ->
                        CatalogOrderCard(
                            item = item,
                            onMoveUp = {
                                viewModel.moveUp(item.key)
                                scope.launch {
                                    listState.animateScrollToItem((index - 1).coerceAtLeast(0))
                                }
                            },
                            onMoveDown = {
                                viewModel.moveDown(item.key)
                                scope.launch {
                                    listState.animateScrollToItem(
                                        (index + 1).coerceAtMost(uiState.items.lastIndex)
                                    )
                                }
                            },
                            onToggleEnabled = { viewModel.toggleCatalogEnabled(item.disableKey) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogOrderCard(
    item: CatalogOrderItem,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleEnabled: () -> Unit
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
                    text = "${item.catalogName} - ${item.typeLabel.toDisplayTypeLabel()}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (item.isDisabled) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = item.addonName,
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

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onMoveUp,
                    enabled = item.canMoveUp,
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
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = stringResource(R.string.cd_move_up)
                    )
                }

                Button(
                    onClick = onMoveDown,
                    enabled = item.canMoveDown,
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
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.cd_move_down)
                    )
                }

                Button(
                    onClick = onToggleEnabled,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = if (item.isDisabled) NuvioTheme.colors.Success else NuvioTheme.colors.TextSecondary,
                        focusedContainerColor = NuvioTheme.colors.FocusBackground,
                        focusedContentColor = if (item.isDisabled) NuvioTheme.colors.Success else NuvioTheme.colors.Error
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                        )
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md))
                ) {
                    Text(text = if (item.isDisabled) stringResource(R.string.catalog_order_enable) else stringResource(R.string.catalog_order_disable))
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

package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.iptv.XtreamChannel
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Live TV browse, Nuvio-style: one horizontal row per live category. Channels show their logo
 * on a landscape tile; selecting one plays its live .ts stream directly via [onChannelSelected].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamLiveScreen(
    onBackPress: () -> Unit,
    onChannelSelected: (title: String, streamUrl: String) -> Unit,
    viewModel: XtreamLiveViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler { onBackPress() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.xxl)
    ) {
        item {
            Text(
                text = uiState.accountName.ifEmpty { "IPTV" } + " · Live TV",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
            )
        }

        val statusMessage = when {
            uiState.error != null -> uiState.error
            uiState.loading -> "Loading…"
            uiState.categories.isEmpty() -> "No channels available"
            else -> null
        }
        if (statusMessage != null) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(NuvioTheme.spacing.xxxl)) {
                    Text(statusMessage, style = MaterialTheme.typography.bodyLarge, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }

        items(uiState.categories, key = { it.id }) { category ->
            LaunchedEffect(category.id) { viewModel.loadCategory(category.id) }
            val loaded = uiState.channelsByCategory.containsKey(category.id)
            val channels = uiState.channelsByCategory[category.id].orEmpty()
            if (!loaded || channels.isNotEmpty()) {
                CatalogRowSection(
                    catalogRow = CatalogRow(
                        addonId = "xtream",
                        addonName = "",
                        addonBaseUrl = "",
                        catalogId = category.id,
                        catalogName = category.name,
                        type = ContentType.TV,
                        items = channels.map { it.toMetaPreview() },
                        isLoading = !loaded
                    ),
                    onItemClick = { itemId, _, _ ->
                        channels.firstOrNull { "live:${it.streamId}" == itemId }
                            ?.let { onChannelSelected(it.name, it.streamUrl) }
                    },
                    showSeeAll = false,
                    showAddonName = false,
                )
            }
        }
    }
}

private fun XtreamChannel.toMetaPreview(): MetaPreview = MetaPreview(
    id = "live:$streamId",
    type = ContentType.TV,
    name = name,
    poster = logo,
    posterShape = PosterShape.LANDSCAPE,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList()
)

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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.iptv.XtreamMovie
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Nuvio-style VOD browse: one horizontal poster row per VOD category, stacked vertically —
 * the same arrangement as the home screen, using the home's own [CatalogRowSection]. Each
 * row lazy-loads its movies when it scrolls into view.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun XtreamVodScreen(
    onBackPress: () -> Unit,
    onMovieSelected: (contentId: String) -> Unit,
    viewModel: XtreamVodViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler { onBackPress() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.xl, bottom = NuvioTheme.spacing.xxl)
    ) {
        item {
            Text(
                text = uiState.accountName.ifEmpty { "IPTV" } + " · Movies",
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.padding(start = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md)
            )
        }

        val statusMessage = when {
            uiState.error != null -> uiState.error
            uiState.loading -> "Loading…"
            uiState.categories.isEmpty() -> "No movies available"
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
            val loaded = uiState.moviesByCategory.containsKey(category.id)
            val movies = uiState.moviesByCategory[category.id].orEmpty()
            // Show while loading, hide once a category resolves to empty (keeps the list tidy).
            if (!loaded || movies.isNotEmpty()) {
                CatalogRowSection(
                    catalogRow = CatalogRow(
                        addonId = "xtream",
                        addonName = "",
                        addonBaseUrl = "",
                        catalogId = category.id,
                        catalogName = category.name,
                        type = ContentType.MOVIE,
                        items = movies.map { it.toMetaPreview(uiState.accountId) },
                        isLoading = !loaded
                    ),
                    onItemClick = { itemId, _, _ -> onMovieSelected(itemId) },
                    showSeeAll = false,
                    showAddonName = false
                )
            }
        }
    }
}

private fun XtreamMovie.toMetaPreview(accountId: String): MetaPreview = MetaPreview(
    id = com.nuvio.tv.core.iptv.XtreamItemRegistry.vodId(accountId, streamId),
    type = ContentType.MOVIE,
    name = name,
    poster = poster,
    posterShape = PosterShape.POSTER,
    background = null,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = rating?.toFloatOrNull(),
    genres = emptyList()
)

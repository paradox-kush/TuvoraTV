package com.nuvio.tv.ui.screens.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.PlaylistDisplayPolicy
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * F48 on TV: the keyboard step. OK on the hub's Search chip opens this; submitting (the keyboard's Search key or
 * the button) runs the search. The text field is the D-pad-safe one the guide's F01 search uses (UP/DOWN leave it,
 * so the Search button below is reachable), and it opens focused and prefilled with the last query.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HubSearchDialog(
    playlistName: String,
    section: XtreamSection,
    initialQuery: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_hub_search_dialog_title),
        subtitle = PlaylistDisplayPolicy.displayName(playlistName),
        width = 640.dp,
    ) {
        GuideTextField(
            value = query,
            onValueChange = { query = it },
            onSubmit = { onSubmit(query) },
            placeholder = stringResource(
                if (section == XtreamSection.SERIES) R.string.iptv_hub_search_hint_series else R.string.iptv_hub_search_hint_movies,
            ),
            modifier = Modifier.focusRequester(field),
        )
        Button(onClick = { onSubmit(query) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.iptv_hub_search_action))
        }
    }
}

/**
 * The results page's non-grid states (the grid itself is the hub's [HubCategoryPage]). Every state ends — shimmer
 * while asking, "No matches" with Search again, or a failure with Retry — and each leaves something focusable, so
 * the remote is never dead (a failed search never reads as "no matches"). Back closes the search.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HubSearchStatePage(
    playlistName: String,
    search: HubSearchUiState,
    status: LoadStatus,
    skeleton: @Composable () -> Unit,
    onSearchAgain: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val action = remember { FocusRequester() }
    val name = PlaylistDisplayPolicy.displayName(playlistName)
    Column(Modifier.fillMaxSize().padding(top = NuvioTheme.spacing.xl)) {
        Text(
            text = stringResource(R.string.iptv_hub_search_results_title, search.query, name),
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(start = 52.dp, bottom = NuvioTheme.spacing.lg),
        )
        when (status) {
            is LoadStatus.Failed -> ErrorState(
                message = stringResource(R.string.iptv_hub_search_failed_message, name),
                onRetry = onRetry,
                requestInitialFocus = true,
            )
            LoadStatus.Empty -> {
                LaunchedEffect(Unit) { runCatching { action.requestFocus() } }
                Column(Modifier.padding(start = 52.dp)) {
                    Text(
                        text = stringResource(R.string.iptv_hub_search_empty_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary,
                    )
                    Spacer(Modifier.height(NuvioTheme.spacing.sm))
                    Text(
                        text = stringResource(R.string.iptv_hub_search_empty_message, name, search.query),
                        color = NuvioTheme.colors.TextSecondary,
                    )
                    Spacer(Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onSearchAgain, modifier = Modifier.focusRequester(action)) {
                        Text(stringResource(R.string.iptv_hub_search_again))
                    }
                }
            }
            else -> skeleton()
        }
    }
}

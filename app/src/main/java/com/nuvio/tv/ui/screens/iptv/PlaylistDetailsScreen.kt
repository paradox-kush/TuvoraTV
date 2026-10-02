@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.ManagedDetailsModel
import com.nuvio.tv.core.iptv.ManagedDetailsModel.DetailsAction
import com.nuvio.tv.core.iptv.ManagedDetailsModel.Expiry
import com.nuvio.tv.core.iptv.ManagedDetailsModel.ShelfGroup
import com.nuvio.tv.core.iptv.ManagedPlaylistInfo
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.XtreamAccountInfo
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

/** Everything the details page shows, resolved by the caller so this file holds no I/O. */
data class PlaylistDetailsState(
    val account: XtreamAccount,
    val managed: ManagedPlaylistInfo?,
    val info: XtreamAccountInfo?,
    /** "12,000 channels" style local catalog counts, when known. */
    val catalogLine: String?,
    val needsReimport: Boolean,
    /** "Using backup server 2 (host)" when the playlist is not on its main server. */
    val backupLine: String?,
    /** Set right after a redeem: "<provider> added this playlist" until the page is closed. */
    val justAddedBy: String?,
    /** The re-match card flips to "started" once pressed. */
    val rematchStarted: Boolean,
    val nowEpochSec: Long,
)

private val FocusOutline = Color.White
private val DestructiveContainer = Color(0xFF3A1F1F)
private val DestructiveFocused = Color(0xFF5C2727)

/**
 * The playlist DETAILS page: a full-screen two-pane page replacing the old dialog, for every playlist.
 *
 *  - LEFT: read-only facts (never focusable): name, "Managed by X", days left (bar only when the
 *    provider reports an expiry), connections, the lock note for a managed playlist's server and login.
 *  - RIGHT: three labelled shelves of cards (PROVIDER / YOUR LIBRARY / REMOVE). Up/Down changes shelf,
 *    Left/Right moves along it, focus starts on the first card, each shelf remembers its last card, the
 *    labels are never focus stops.
 *
 * Safe margins 48 dp sides / 27 dp top and bottom (5%). The focus outline is white, not the accent, so it
 * never reads as "selected". Back closes the page (dialogs opened from it stack on top and return here).
 */
@Composable
fun PlaylistDetailsScreen(
    state: PlaylistDetailsState,
    onAction: (DetailsAction) -> Unit,
    onClose: () -> Unit,
) {
    val shelves = remember(state.account.id, state.account.sourceType, state.managed, state.needsReimport) {
        ManagedDetailsModel.shelves(state.account, state.managed, state.needsReimport)
    }
    val facts = ManagedDetailsModel.facts(state.account, state.managed, state.info, state.catalogLine, state.nowEpochSec)
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstCardFocus.requestFocusAfterFrames() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
                .padding(horizontal = 48.dp, vertical = 27.dp),
        ) {
            // Both panes are vertically centred when they fit and scroll when they do not.
            Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                FactsPane(state, facts, Modifier.weight(0.36f))
                Spacer(Modifier.width(40.dp))
                Column(
                    modifier = Modifier
                        .weight(0.64f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    shelves.forEachIndexed { shelfIndex, shelf ->
                        Shelf(
                            label = when (shelf.group) {
                                ShelfGroup.PROVIDER -> state.managed?.providerName.orEmpty()
                                ShelfGroup.LIBRARY -> stringResource(R.string.iptv_shelf_library)
                                ShelfGroup.REMOVE -> stringResource(R.string.iptv_shelf_remove)
                            },
                            cards = shelf.cards,
                            state = state,
                            // Initial focus: the first card of the first shelf.
                            firstCardFocus = if (shelfIndex == 0) firstCardFocus else null,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

// --- left pane: facts, never focusable -------------------------------------------------------

@Composable
private fun FactsPane(state: PlaylistDetailsState, facts: ManagedDetailsModel.Facts, modifier: Modifier) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Only while it is still managed: after a Detach the banner would be stale.
        state.justAddedBy?.takeIf { state.managed != null }?.let { provider ->
            Row(
                modifier = Modifier
                    .background(NuvioTheme.colors.Secondary.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Link, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.iptv_details_added_banner, provider),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                )
            }
        }
        Text(
            text = facts.name,
            fontSize = 30.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        hostLine(state.account)?.let {
            Text(it, fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.backupLine?.let {
            Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2)
        }
        facts.managedBy?.let { provider ->
            val updated = ManagedDetailsModel.updatedLabel(state.managed?.serviceUpdatedAt)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Link, contentDescription = null, tint = NuvioTheme.colors.Secondary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (updated != null) stringResource(R.string.iptv_details_managed_by_updated, provider, updated)
                    else stringResource(R.string.iptv_details_managed_by, provider),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ExpiryBlock(facts.expiry)
        facts.connections?.let { c ->
            Text(
                text = if (c.active != null) stringResource(R.string.iptv_details_connections, c.active, c.max)
                else stringResource(R.string.iptv_details_connections_max, c.max),
                fontSize = 20.sp, color = NuvioTheme.colors.TextPrimary,
            )
        }
        if (!state.account.enabled) {
            Text(stringResource(R.string.iptv_details_disabled), fontSize = 18.sp, color = NuvioTheme.colors.Warning)
        }
        facts.catalogLine?.let {
            Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2)
        }
        if (facts.serverLoginLocked) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.iptv_details_lock_note, facts.managedBy.orEmpty()),
                    fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ExpiryBlock(expiry: Expiry) {
    when (expiry) {
        is Expiry.Days -> {
            Text(
                pluralStringResource(R.plurals.iptv_details_days_left, expiry.daysLeft, expiry.daysLeft),
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
            )
            // A thin bar, only because the provider reports an expiry.
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(6.dp)
                    .background(NuvioTheme.colors.Border, RoundedCornerShape(3.dp)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(expiry.fraction.coerceAtLeast(0.03f))
                        .height(6.dp)
                        .background(NuvioTheme.colors.Secondary, RoundedCornerShape(3.dp)),
                )
            }
        }
        Expiry.Expired -> Text(
            stringResource(R.string.iptv_details_expired),
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Error,
        )
        is Expiry.Text -> Text(
            stringResource(R.string.iptv_details_expires_text, expiry.text),
            fontSize = 20.sp, color = NuvioTheme.colors.TextPrimary,
        )
        Expiry.NeverExpires -> Text(
            stringResource(R.string.iptv_details_never_expires),
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
        )
        Expiry.NotReported -> Text(
            stringResource(R.string.iptv_details_expiry_unknown),
            fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary,
        )
    }
}

/** The host to show under the name (never the credentials a playlist URL may carry in its query). */
internal fun hostLine(account: XtreamAccount): String? = when {
    account.fileName != null -> account.fileName
    account.portalUrl.isNotBlank() -> hostOf(account.portalUrl)
    account.baseUrl.isNotBlank() -> hostOf(account.baseUrl)
    else -> null
}

private fun hostOf(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.substringAfter("://").substringBefore('/').substringBefore('?')

// --- right pane: shelves ----------------------------------------------------------------------

@Composable
private fun Shelf(
    label: String,
    cards: List<DetailsAction>,
    state: PlaylistDetailsState,
    firstCardFocus: FocusRequester?,
    onAction: (DetailsAction) -> Unit,
) {
    // The group label is text only: it is never a focus stop.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label.uppercase(),
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp,
            color = NuvioTheme.colors.TextSecondary,
        )
        val rowFirst = remember { FocusRequester() }
        // focusRestorer: Up/Down back into a shelf lands on the card last focused there, first time on card 1.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .focusRestorer(firstCardFocus ?: rowFirst)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            cards.forEachIndexed { i, action ->
                ShelfCard(
                    spec = cardSpec(action, state),
                    onClick = { onAction(action) },
                    modifier = if (i == 0) Modifier.focusRequester(firstCardFocus ?: rowFirst) else Modifier,
                )
            }
        }
    }
}

private data class CardSpec(val title: String, val subtitle: String, val icon: ImageVector, val destructive: Boolean = false)

@Composable
private fun cardSpec(action: DetailsAction, state: PlaylistDetailsState): CardSpec = when (action) {
    DetailsAction.CONTACT -> CardSpec(stringResource(R.string.iptv_card_contact), stringResource(R.string.iptv_card_contact_sub), Icons.Default.Chat)
    DetailsAction.CONTENT -> CardSpec(stringResource(R.string.iptv_card_content), stringResource(R.string.iptv_card_content_sub), Icons.Default.Tune)
    DetailsAction.HIDDEN -> CardSpec(stringResource(R.string.iptv_card_hidden), stringResource(R.string.iptv_card_hidden_sub), Icons.Default.VisibilityOff)
    DetailsAction.REMATCH -> CardSpec(
        stringResource(R.string.iptv_card_rematch),
        stringResource(if (state.rematchStarted) R.string.iptv_card_rematch_started else R.string.iptv_card_rematch_sub),
        Icons.Default.Refresh,
    )
    DetailsAction.CATCHUP -> CardSpec(stringResource(R.string.iptv_card_catchup), stringResource(R.string.iptv_card_catchup_sub), Icons.Default.History)
    DetailsAction.EDIT -> CardSpec(
        stringResource(R.string.iptv_card_edit),
        stringResource(if (state.account.fileName != null) R.string.iptv_card_edit_file_sub else R.string.iptv_card_edit_sub),
        Icons.Default.Edit,
    )
    DetailsAction.REIMPORT -> CardSpec(stringResource(R.string.iptv_card_reimport), stringResource(R.string.iptv_card_reimport_sub), Icons.Default.UploadFile)
    DetailsAction.TOGGLE_ENABLED -> if (state.account.enabled)
        CardSpec(stringResource(R.string.iptv_card_disable), stringResource(R.string.iptv_card_disable_sub), Icons.Default.PowerSettingsNew)
    else
        CardSpec(stringResource(R.string.iptv_card_enable), stringResource(R.string.iptv_card_enable_sub), Icons.Default.PowerSettingsNew)
    DetailsAction.DETACH -> CardSpec(stringResource(R.string.iptv_card_detach), stringResource(R.string.iptv_card_detach_sub), Icons.Default.LinkOff, destructive = true)
    DetailsAction.REMOVE -> CardSpec(stringResource(R.string.iptv_card_remove), stringResource(R.string.iptv_card_remove_sub), Icons.Default.Delete, destructive = true)
}

/** One card = one focus target. A white outline + a brighter fill + a small scale on focus. */
@Composable
private fun ShelfCard(spec: CardSpec, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Card(
        onClick = onClick,
        modifier = modifier.width(244.dp).height(104.dp),
        colors = CardDefaults.colors(
            containerColor = if (spec.destructive) DestructiveContainer else NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = if (spec.destructive) DestructiveFocused else NuvioTheme.colors.FocusBackground,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, FocusOutline), shape = shape),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.05f, pressedScale = 1.0f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    spec.icon, contentDescription = null, modifier = Modifier.size(28.dp),
                    tint = if (spec.destructive) Color(0xFFFF8A80) else NuvioTheme.colors.TextPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    spec.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp,
                )
            }
            Text(
                spec.subtitle, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

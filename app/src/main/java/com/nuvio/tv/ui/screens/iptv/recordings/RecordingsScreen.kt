package com.nuvio.tv.ui.screens.iptv.recordings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.recordings.api.RecordingAction
import com.nuvio.tv.core.recordings.api.RecordingActionsPolicy
import com.nuvio.tv.core.recordings.api.RecordingProblem
import com.nuvio.tv.core.recordings.api.RecordingRow
import com.nuvio.tv.core.recordings.api.RecordingsAndroid
import com.nuvio.tv.core.recordings.api.RecordingSection
import com.nuvio.tv.core.recordings.api.RecordingStatus
import com.nuvio.tv.core.recordings.api.RecordingsLoadAttempt
import com.nuvio.tv.core.recordings.api.RecordingsLoadPolicy
import com.nuvio.tv.core.recordings.api.RecordingsPagePolicy
import com.nuvio.tv.core.recordings.api.RecordingsView
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.iptv.GuideFocusRequestLoop
import com.nuvio.tv.ui.screens.iptv.launchExactAlarmSettings
import com.nuvio.tv.ui.screens.iptv.guideDayLabel
import com.nuvio.tv.ui.screens.iptv.guideHhMm
import com.nuvio.tv.ui.screens.iptv.requestFocusOrFalse
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The last row the cursor was on; survives leaving for the player and coming back. */
private class FocusMemo(var id: String?)

private val FocusMemoSaver = Saver<FocusMemo, String>(
    save = { it.id.orEmpty() },
    restore = { FocusMemo(it.takeIf { id -> id.isNotEmpty() }) },
)

private sealed interface Entry {
    data class Header(val section: RecordingSection, val count: Int) : Entry
    data class Item(val row: RecordingRow) : Entry
}

private const val REMOVAL_WAIT_MS = 3_000L

/**
 * Scheduled / Recording / Done / Failed recordings. Every state has a focus target: the first row
 * (content), a button (empty, failed), or a holder while the read is in flight.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onBack: () -> Unit,
    onPlayFile: (uri: String, title: String) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val view = rememberRecordingsView(ui.loaded, ui.attempt)
    var actionFailed by remember { mutableStateOf(false) }

    val latestPlay by rememberUpdatedState(onPlayFile)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is RecordingsEvent.Play -> latestPlay(event.uri, event.title)
                RecordingsEvent.ActionFailed -> actionFailed = true
            }
        }
    }

    val entries = remember(ui.page) {
        buildList {
            for (section in RecordingsPagePolicy.ORDER) {
                val rows = ui.page.rows(section)
                if (rows.isEmpty()) continue
                add(Entry.Header(section, rows.size))
                rows.forEach { add(Entry.Item(it)) }
            }
        }
    }
    val ids = remember(entries) { entries.filterIsInstance<Entry.Item>().map { it.row.id } }
    val rowsById = remember(entries) { entries.filterIsInstance<Entry.Item>().associate { it.row.id to it.row } }

    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    val memo = rememberSaveable(saver = FocusMemoSaver) { FocusMemo(null) }
    var entered by remember { mutableStateOf(false) }
    val holder = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }

    suspend fun focusRow(id: String): Boolean {
        val index = entries.indexOfFirst { it is Entry.Item && it.row.id == id }
        if (index < 0) return false
        listState.scrollToItem(index)
        return GuideFocusRequestLoop.requestUntilLanded { requesterFor(id).requestFocusOrFalse() }
    }

    // Entry: the row the viewer was on (coming back from the player), else the first. The flag is set
    // from the result, never before it.
    LaunchedEffect(view, ids, entered) {
        if (entered || view !is RecordingsView.Ready) return@LaunchedEffect
        val target = RecordingsFocusPolicy.restore(memo.id, ids) ?: return@LaunchedEffect
        if (focusRow(target)) entered = true
    }
    // The states without rows still hold the cursor somewhere.
    val rowless = view !is RecordingsView.Ready || ids.isEmpty()
    LaunchedEffect(view, rowless) {
        if (!rowless) return@LaunchedEffect
        val target = if (view is RecordingsView.Loading) holder else actionFocus
        GuideFocusRequestLoop.requestUntilLanded { target.requestFocusOrFalse() }
    }

    // Details sheet + where the cursor goes when its action removes the row.
    var openRowId by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val openRow = openRowId?.let { rowsById[it] }
    // The row left the page (a capture ended and moved sections): close its sheet.
    LaunchedEffect(openRow == null) { if (openRow == null) openRowId = null }
    LaunchedEffect(ids, removal) {
        val (removedId, before) = removal ?: return@LaunchedEffect
        if (removedId in ids) {
            // Still there: the action has not landed (or failed). Give it a moment, then put the cursor back.
            delay(REMOVAL_WAIT_MS)
            if (removal?.first == removedId && removedId in ids) {
                removal = null
                focusRow(removedId)
            }
            return@LaunchedEffect
        }
        removal = null
        val target = RecordingsFocusPolicy.afterRemoval(before, removedId, ids)
        if (target != null) focusRow(target) else actionFocus.requestFocusOrFalse()
    }

    val nowMs = remember(ui.page) { System.currentTimeMillis() }

    // The "Allow exact times" offer lives here too, so closing the record confirmation without choosing
    // never loses it. Re-read on resume: the viewer may have just allowed it in the system screen.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var systemAllowsExact by remember { mutableStateOf(canScheduleExactNow(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) systemAllowsExact = canScheduleExactNow(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val exactSettings = remember { RecordingsAndroid.exactAlarmSettingsIntent(context) }
    val offerExact = ExactAlarmOfferPolicy.showOnRecordingsScreen(
        exactAllowed = ui.exactAllowed || systemAllowsExact,
        scheduledCount = ui.page.rows(RecordingSection.SCHEDULED).size,
        settingsAvailable = exactSettings != null,
    )

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.rec_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            if (offerExact) {
                Spacer(Modifier.height(NuvioTheme.spacing.sm))
                ExactTimesOffer(onClick = { exactSettings?.let { launchExactAlarmSettings(context, it) } })
            } else if (ui.page.rows(RecordingSection.SCHEDULED).isNotEmpty() && !(ui.exactAllowed || systemAllowsExact)) {
                Spacer(Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.rec_hint_may_start_late),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                )
            }
            Spacer(Modifier.height(NuvioTheme.spacing.lg))

            when (view) {
                is RecordingsView.Loading -> Box(
                    Modifier.fillMaxSize().focusRequester(holder).clickable { },
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator() }

                is RecordingsView.Failed -> CenteredMessage(
                    title = stringResource(if (view.timedOut) R.string.rec_load_timed_out else R.string.rec_load_failed),
                    actionLabel = stringResource(R.string.action_retry),
                    actionFocus = actionFocus,
                    onAction = { entered = false; viewModel.load() },
                )

                is RecordingsView.Ready -> if (ids.isEmpty()) {
                    CenteredMessage(
                        title = stringResource(R.string.rec_empty_title),
                        body = stringResource(R.string.rec_empty_body),
                        actionLabel = stringResource(R.string.rec_empty_action),
                        actionFocus = actionFocus,
                        onAction = onBack,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl),
                    ) {
                        items(entries, key = { e -> when (e) { is Entry.Header -> "h:${e.section}"; is Entry.Item -> e.row.id } }) { entry ->
                            when (entry) {
                                is Entry.Header -> SectionHeader(entry)
                                is Entry.Item -> RecordingRowItem(
                                    row = entry.row,
                                    nowMs = nowMs,
                                    requester = requesterFor(entry.row.id),
                                    onFocused = { memo.id = entry.row.id; entered = true },
                                    onClick = { memo.id = entry.row.id; openRowId = entry.row.id },
                                )
                            }
                        }
                    }
                }
            }
        }

        openRow?.let { row ->
            RecordingDetailsDialog(
                row = row,
                fileAvailable = row.recordingId?.let { it in ui.playable } == true,
                nowMs = nowMs,
                onAction = { action ->
                    openRowId = null
                    when (action) {
                        RecordingAction.PLAY -> viewModel.play(row)
                        RecordingAction.CANCEL -> {
                            removal = row.id to ids
                            row.scheduleId?.let(viewModel::cancel)
                        }
                        RecordingAction.RETRY -> {
                            removal = row.id to ids
                            row.recordingId?.let(viewModel::retry)
                        }
                        RecordingAction.DELETE -> {
                            removal = row.id to ids
                            row.recordingId?.let(viewModel::delete)
                        }
                    }
                },
                onDismiss = {
                    // Nothing changed: put the cursor back on the row that opened the sheet.
                    memo.id = row.id
                    openRowId = null
                    entered = false
                },
            )
        }

        if (actionFailed) {
            // Persistent, like the details sheet: a toast that timed out moved the cursor under the next OK.
            ActionFailedDialog(onDismiss = { actionFailed = false; entered = false })
        }
    }
}

/** The wait ends: Ready, or failed with Retry - at the attempt's own deadline even if the read never returns. */
@Composable
private fun rememberRecordingsView(loaded: Boolean, attempt: RecordingsLoadAttempt): RecordingsView {
    var now by remember(attempt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(loaded, attempt) {
        if (!loaded && !attempt.failed) {
            val wait = attempt.startedAtMs + RecordingsLoadPolicy.DEADLINE_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            now = System.currentTimeMillis()
        }
    }
    return RecordingsLoadPolicy.viewAt(loaded, attempt, now)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CenteredMessage(
    title: String,
    actionLabel: String,
    actionFocus: FocusRequester,
    onAction: () -> Unit,
    body: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(NuvioTheme.spacing.sm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(640.dp),
            )
        }
        Spacer(Modifier.height(NuvioTheme.spacing.lg))
        Button(onClick = onAction, modifier = Modifier.focusRequester(actionFocus)) { Text(actionLabel) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionHeader(header: Entry.Header) {
    Text(
        text = stringResource(
            when (header.section) {
                RecordingSection.SCHEDULED -> R.string.rec_section_scheduled
                RecordingSection.RECORDING -> R.string.rec_section_recording
                RecordingSection.DONE -> R.string.rec_section_done
                RecordingSection.FAILED -> R.string.rec_section_failed
            },
        ) + "  ·  ${header.count}",
        style = MaterialTheme.typography.titleSmall,
        color = NuvioTheme.colors.TextSecondary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordingRowItem(
    row: RecordingRow,
    nowMs: Long,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val primary = if (focused) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary
    val secondary = if (focused) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextSecondary
    val statusColor = when {
        focused -> secondary
        row.section == RecordingSection.FAILED || row.section == RecordingSection.RECORDING -> NuvioTheme.colors.Error
        else -> secondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundElevated)
            .border(if (focused) 2.dp else 0.dp, if (focused) NuvioTheme.colors.BorderFocused else Color.Transparent, shape)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(row.title, row.episode).joinToString("  "),
                style = MaterialTheme.typography.titleMedium,
                color = primary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = rowMeta(row, nowMs),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = rowStatus(row),
            style = MaterialTheme.typography.labelLarge,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(280.dp),
            textAlign = TextAlign.End,
        )
    }
}

private fun rowMeta(row: RecordingRow, nowMs: Long): String =
    "${row.channelName}  ·  ${guideDayLabel(row.programmeStartMs, nowMs)} ${guideHhMm(row.programmeStartMs)}–${guideHhMm(row.programmeEndMs)}"

@Composable
private fun rowStatus(row: RecordingRow): String {
    val size = RecordingRowFormat.size(row.sizeBytes)
    return when (row.status) {
        RecordingStatus.SCHEDULED ->
            stringResource(if (row.retryPending) R.string.rec_status_retry_pending else R.string.rec_status_scheduled)
        RecordingStatus.RECORDING ->
            if (size.isEmpty()) stringResource(R.string.rec_status_recording)
            else stringResource(R.string.rec_status_recording_size, size)
        RecordingStatus.DONE -> size.ifEmpty { stringResource(R.string.rec_status_done) }
        RecordingStatus.FAILED -> row.problem?.let { problemText(it) } ?: stringResource(R.string.rec_status_failed)
        RecordingStatus.CANCELLED -> stringResource(R.string.rec_status_cancelled)
    }
}

/** The problem in plain words; never an exception message or a provider host. */
@Composable
private fun problemText(problem: RecordingProblem): String = stringResource(
    when (problem) {
        RecordingProblem.NO_SPACE -> R.string.rec_problem_no_space
        RecordingProblem.NO_CATCH_UP -> R.string.rec_problem_no_catch_up
        RecordingProblem.PROVIDER_REFUSED -> R.string.rec_problem_provider_refused
        RecordingProblem.NETWORK -> R.string.rec_problem_network
        RecordingProblem.STALLED -> R.string.rec_problem_stalled
        RecordingProblem.MISSED -> R.string.rec_problem_missed
        RecordingProblem.CANCELLED_BY_USER -> R.string.rec_problem_cancelled_by_user
        RecordingProblem.UNSUPPORTED_FORMAT -> R.string.rec_problem_unsupported_format
        RecordingProblem.LIVE_CAPTURE_PENDING -> R.string.rec_problem_live_pending
        RecordingProblem.UNKNOWN -> R.string.rec_problem_unknown
    },
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RecordingDetailsDialog(
    row: RecordingRow,
    fileAvailable: Boolean,
    nowMs: Long,
    onAction: (RecordingAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val actions = RecordingActionsPolicy.actionsFor(row, fileAvailable)
    var confirmDelete by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val keep = remember { FocusRequester() }
    // Play for a saved recording, Close otherwise; Cancel recording / Delete / Try again never take the first OK.
    val defaultTarget = RecordingDetailsFocusPolicy.defaultFocus(row.status, actions)
    LaunchedEffect(confirmDelete) {
        GuideFocusRequestLoop.requestUntilLanded(minimumAsks = 2) { (if (confirmDelete) keep else first).requestFocusOrFalse() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = listOfNotNull(row.title, row.episode).joinToString("  "),
        subtitle = rowMeta(row, nowMs),
        width = 560.dp,
        scrollable = true,
    ) {
        Text(
            text = rowStatus(row),
            style = MaterialTheme.typography.titleSmall,
            color = if (row.section == RecordingSection.FAILED) NuvioTheme.colors.Error else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        row.problem?.takeIf { row.section == RecordingSection.FAILED }?.let {
            Text(text = problemText(it), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
        }
        if (row.status == RecordingStatus.DONE && !fileAvailable) {
            Text(
                text = stringResource(R.string.rec_file_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
            )
        }
        row.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (confirmDelete) {
            Text(
                text = stringResource(R.string.rec_delete_confirm),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
            )
            Button(onClick = { confirmDelete = false }, modifier = Modifier.fillMaxWidth().focusRequester(keep)) {
                Text(stringResource(R.string.rec_delete_keep))
            }
            Button(onClick = { onAction(RecordingAction.DELETE) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.rec_action_delete))
            }
        } else {
            actions.forEach { action ->
                Button(
                    onClick = { if (action == RecordingAction.DELETE) confirmDelete = true else onAction(action) },
                    modifier = Modifier.fillMaxWidth().then(
                        if (action == RecordingAction.PLAY && defaultTarget == RecordingDetailsFocusPolicy.Target.PLAY) Modifier.focusRequester(first) else Modifier,
                    ),
                ) {
                    Text(
                        stringResource(
                            when (action) {
                                RecordingAction.PLAY -> R.string.rec_action_play
                                RecordingAction.CANCEL ->
                                    if (row.status == RecordingStatus.RECORDING) R.string.rec_sheet_stop else R.string.rec_sheet_cancel
                                RecordingAction.RETRY -> R.string.rec_action_retry
                                RecordingAction.DELETE -> R.string.rec_action_delete
                            },
                        ),
                    )
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().then(
                    if (defaultTarget == RecordingDetailsFocusPolicy.Target.CLOSE) Modifier.focusRequester(first) else Modifier,
                ),
            ) { Text(stringResource(R.string.rec_sheet_close)) }
        }
    }
}

/** The viewer's way to allow exact times when they closed the confirmation without choosing. Highlights on focus. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExactTimesOffer(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.FocusBackground else NuvioTheme.colors.BackgroundElevated)
            .border(if (focused) 2.dp else 1.dp, if (focused) NuvioTheme.colors.BorderFocused else NuvioTheme.colors.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
    ) {
        Text(
            text = stringResource(R.string.rec_hint_allow_exact),
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.rec_notice_allow_exact),
            style = MaterialTheme.typography.labelLarge,
            color = if (focused) NuvioTheme.colors.FocusContent else NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionFailedDialog(onDismiss: () -> Unit) {
    val ok = remember { FocusRequester() }
    LaunchedEffect(Unit) { GuideFocusRequestLoop.requestUntilLanded(minimumAsks = 2) { ok.requestFocusOrFalse() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.rec_action_failed), width = 480.dp) {
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().focusRequester(ok)) { Text(stringResource(R.string.rec_ok)) }
    }
}

/** Android 12+ gates exact alarms behind a user setting; earlier versions always allow them. */
private fun canScheduleExactNow(context: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager)?.canScheduleExactAlarms() == true

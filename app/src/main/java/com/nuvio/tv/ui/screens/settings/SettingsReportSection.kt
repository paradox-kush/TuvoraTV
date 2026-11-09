@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.core.telemetry.ReportCategory

private val reportCategories = listOf(
    ReportCategory.WONT_PLAY to R.string.report_category_wont_play,
    ReportCategory.BLACK_SCREEN to R.string.report_category_black_screen,
    ReportCategory.FREEZES to R.string.report_category_freezes,
    ReportCategory.APP_CLOSED to R.string.report_category_app_closed,
    ReportCategory.OTHER to R.string.report_category_other,
)

/**
 * "Send a report" row plus the in-place chooser / result rows beneath it.
 *
 * Focus contract: Idle -> the Send row; Choosing -> first category; Sending and every result ->
 * the status row (focusable, so the user never loses focus when the chooser rows disappear);
 * Back or OK on a result returns to the Send row. The Send row is disabled only while a send
 * is in flight.
 */
@Composable
internal fun ColumnScope.SettingsReportSection(
    flow: SettingsReportFlow,
    consentToggleFocusRequester: FocusRequester,
) {
    val state by flow.state.collectAsStateWithLifecycle()
    val sendRowFocus = remember { FocusRequester() }
    val firstCategoryFocus = remember { FocusRequester() }
    val statusFocus = remember { FocusRequester() }
    var wasActive by remember { mutableStateOf(false) }
    var skipRestore by remember { mutableStateOf(false) }

    BackHandler(
        enabled = state != SettingsReportUiState.Idle && state != SettingsReportUiState.Sending
    ) { flow.dismiss() }

    LaunchedEffect(state) {
        when (state) {
            SettingsReportUiState.Idle -> {
                if (wasActive && !skipRestore) runCatching { sendRowFocus.requestFocus() }
                skipRestore = false
                wasActive = false
            }
            SettingsReportUiState.Choosing -> {
                wasActive = true
                if (!runCatching { firstCategoryFocus.requestFocus() }.getOrDefault(false)) {
                    runCatching { sendRowFocus.requestFocus() }
                }
            }
            else -> {
                wasActive = true
                if (!runCatching { statusFocus.requestFocus() }.getOrDefault(false)) {
                    runCatching { sendRowFocus.requestFocus() }
                }
            }
        }
    }

    SettingsActionRow(
        title = stringResource(R.string.report_send),
        subtitle = stringResource(R.string.report_send_subtitle),
        enabled = state != SettingsReportUiState.Sending,
        modifier = Modifier.focusRequester(sendRowFocus),
        onClick = { flow.open() }
    )

    when (val s = state) {
        SettingsReportUiState.Idle -> Unit
        SettingsReportUiState.Choosing -> {
            SettingsGroupCard(title = stringResource(R.string.report_category_title)) {
                reportCategories.forEachIndexed { index, (category, label) ->
                    SettingsActionRow(
                        title = stringResource(label),
                        subtitle = null,
                        modifier = if (index == 0) Modifier.focusRequester(firstCategoryFocus) else Modifier,
                        onClick = { flow.pick(category) }
                    )
                }
            }
        }
        SettingsReportUiState.Sending -> StatusRow(
            title = stringResource(R.string.report_sending),
            subtitle = null,
            modifier = Modifier.focusRequester(statusFocus),
            onClick = {} // in flight: OK does nothing
        )
        is SettingsReportUiState.Sent -> StatusRow(
            title = stringResource(R.string.report_sent, s.displayCode),
            subtitle = stringResource(R.string.report_sent_hint),
            modifier = Modifier.focusRequester(statusFocus),
            onClick = { flow.dismiss() }
        )
        SettingsReportUiState.ConsentOff -> StatusRow(
            title = stringResource(R.string.report_consent_off),
            subtitle = null,
            value = stringResource(R.string.report_consent_open_settings),
            modifier = Modifier.focusRequester(statusFocus),
            onClick = {
                skipRestore = true
                flow.dismiss()
                runCatching { consentToggleFocusRequester.requestFocus() }
            }
        )
        is SettingsReportUiState.RateLimited -> StatusRow(
            title = stringResource(R.string.report_rate_limited),
            subtitle = null,
            modifier = Modifier.focusRequester(statusFocus),
            onClick = { flow.dismiss() }
        )
        SettingsReportUiState.Failed -> StatusRow(
            title = stringResource(R.string.report_failed),
            subtitle = null,
            modifier = Modifier.focusRequester(statusFocus),
            onClick = { flow.dismiss() }
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    subtitle: String?,
    modifier: Modifier,
    onClick: () -> Unit,
    value: String? = null,
) {
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        value = value,
        modifier = modifier,
        onClick = onClick
    )
}

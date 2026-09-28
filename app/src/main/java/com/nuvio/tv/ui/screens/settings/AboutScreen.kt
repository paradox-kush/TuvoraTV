@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.ui.components.QrHandOffDialog
import com.nuvio.tv.ui.components.RestoreFocusOnClose
import com.nuvio.tv.ui.components.rememberExternalLinkOpener
import com.nuvio.tv.updater.UpdateViewModel

// Permanent Discord invite. On TV this is never opened as a URL - most Android TV devices ship no
// browser at all, so ACTION_VIEW would throw ActivityNotFoundException. It's rendered as a QR for
// the viewer's phone instead, the same way the addon and donate flows hand a URL off the telly.
private const val DISCORD_URL = "https://discord.gg/wFu9T2nS8X"
private const val PRIVACY_URL = "https://tuvora.co/privacy"

/** A URL currently shown as a QR hand-off. */
private data class AboutQrLink(val url: String, val instructionRes: Int)

@Composable
fun AboutScreen(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    onBackPress: () -> Unit = {}
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.about_title),
        subtitle = stringResource(R.string.about_subtitle)
    ) {
        AboutSettingsContent(
            onNavigateToSupportersContributors = onNavigateToSupportersContributors,
            onNavigateToLicensesAttributions = onNavigateToLicensesAttributions
        )
    }
}

@Composable
fun AboutSettingsContent(
    onNavigateToSupportersContributors: () -> Unit = {},
    onNavigateToLicensesAttributions: () -> Unit = {},
    initialFocusRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    var qrLink by remember { mutableStateOf<AboutQrLink?>(null) }
    var lastQrOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    val discordFocusRequester = remember { FocusRequester() }
    val privacyFocusRequester = remember { FocusRequester() }
    val donateFocusRequester = remember { FocusRequester() }
    fun showQr(url: String, instructionRes: Int, origin: FocusRequester) {
        lastQrOrigin = origin // focus returns to the row that opened the QR
        qrLink = AboutQrLink(url, instructionRes)
    }
    // Links try the browser first; a TV without one gets the QR hand-off instead of a crash.
    val openLink = rememberExternalLinkOpener()
    fun openLinkFrom(url: String, origin: FocusRequester) {
        if (!openLink(url)) showQr(url, R.string.link_qr_no_browser_instruction, origin)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsDetailHeader(
            title = stringResource(R.string.about_title),
            subtitle = stringResource(R.string.about_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            title = null
        ) {
            val aboutScrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(aboutScrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))

                Image(
                    painter = painterResource(id = R.drawable.app_logo_wordmark),
                    contentDescription = stringResource(R.string.cd_nuvio_logo),
                    modifier = Modifier
                        .width(180.dp)
                        .height(40.dp),
                    contentScale = ContentScale.Fit
                )

                Text(
                    text = stringResource(R.string.about_made_with_love),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))

                if (AppFeaturePolicy.inAppUpdatesEnabled) {
                    val updateViewModel: UpdateViewModel = hiltViewModel(context as ComponentActivity)
                    val updateState by updateViewModel.uiState.collectAsStateWithLifecycle()

                    SettingsToggleRow(
                        title = stringResource(R.string.about_update_banner_title),
                        subtitle = stringResource(R.string.about_update_banner_subtitle),
                        checked = updateState.updateBannerEnabled,
                        onToggle = {
                            updateViewModel.setUpdateBannerEnabled(!updateState.updateBannerEnabled)
                        },
                        modifier = if (initialFocusRequester != null) {
                            Modifier.focusRequester(initialFocusRequester)
                        } else {
                            Modifier
                        }
                    )

                    SettingsActionRow(
                        title = stringResource(R.string.about_check_updates),
                        subtitle = stringResource(R.string.about_check_updates_subtitle),
                        trailingIcon = Icons.Default.OpenInNew,
                        onClick = {
                            updateViewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
                        }
                    )
                }

                SettingsActionRow(
                    title = stringResource(R.string.about_discord),
                    subtitle = stringResource(R.string.about_discord_subtitle),
                    trailingIcon = Icons.Default.OpenInNew,
                    modifier = Modifier.focusRequester(discordFocusRequester),
                    onClick = {
                        showQr(DISCORD_URL, R.string.about_discord_qr_instruction, discordFocusRequester)
                    }
                )

                SettingsActionRow(
                    title = stringResource(R.string.about_privacy_policy),
                    subtitle = stringResource(R.string.about_privacy_policy_subtitle),
                    trailingIcon = Icons.Default.OpenInNew,
                    modifier = if (!AppFeaturePolicy.inAppUpdatesEnabled && initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }.focusRequester(privacyFocusRequester),
                    onClick = { openLinkFrom(PRIVACY_URL, privacyFocusRequester) }
                )

                // Upstream's Supporters & Contributors screen fetches THEIR donation/contributor
                // feeds, which this fork doesn't configure - it could only ever show an error.
                // Replaced by a Donate row that appears once DONATIONS_DONATE_URL is set.
                val donateUrl = BuildConfig.DONATIONS_DONATE_URL.trim()
                if (donateUrl.isNotBlank()) {
                    SettingsActionRow(
                        title = stringResource(R.string.supporters_contributors_donate_button),
                        subtitle = stringResource(R.string.settings_donate_description),
                        trailingIcon = Icons.Default.ChevronRight,
                        modifier = Modifier.focusRequester(donateFocusRequester),
                        onClick = { openLinkFrom(donateUrl, donateFocusRequester) }
                    )
                }

                SettingsActionRow(
                    title = stringResource(R.string.about_licenses_attributions),
                    subtitle = stringResource(R.string.about_licenses_attributions_subtitle),
                    trailingIcon = Icons.Default.ChevronRight,
                    onClick = onNavigateToLicensesAttributions
                )
            }
            SettingsVerticalScrollIndicators(state = aboutScrollState)
            qrLink?.let { link ->
                QrHandOffDialog(
                    url = link.url,
                    instruction = stringResource(link.instructionRes),
                    onClose = { qrLink = null }
                )
            }
            lastQrOrigin?.let { origin ->
                RestoreFocusOnClose(open = qrLink != null, target = origin)
            }
            }
        }
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.announcements.AnnouncementPolicy
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.domain.model.Announcement
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Dismissible announcement card for the top of Home. It never requests focus itself (the layouts
 * own initial focus on the first content row); it is reached with D-pad up.
 *
 * TV has no reliable browser, so the CTA hands the link to the viewer's phone as a QR code, the
 * same way About → Discord does ([QrCodeOverlay]).
 */
@Composable
internal fun HomeAnnouncementCard(
    announcement: Announcement,
    onShowCta: (AnnouncementPolicy.Cta) -> Unit,
    onDismiss: () -> Unit,
    ctaFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val cta = remember(announcement) { AnnouncementPolicy.cta(announcement) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)

    Column(
        modifier = modifier
            .width(460.dp)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border.copy(alpha = 0.35f), shape)
            .background(NuvioTheme.colors.BackgroundElevated, shape)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
    ) {
        Text(
            text = announcement.title,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (announcement.body.isNotBlank()) {
            Text(
                text = announcement.body,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            if (cta != null) {
                AnnouncementButton(
                    text = cta.label,
                    onClick = { onShowCta(cta) },
                    modifier = Modifier.focusRequester(ctaFocusRequester),
                )
            }
            AnnouncementButton(
                text = stringResource(R.string.home_announcement_dismiss),
                onClick = {
                    // Hand focus to the content below before this card leaves the tree, so the
                    // D-pad never ends up on a removed node.
                    focusManager.moveFocus(FocusDirection.Down)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun AnnouncementButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.Secondary,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContentColor = NuvioTheme.colors.OnSecondary,
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(50),
            )
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
    ) {
        Text(text = text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Full-screen QR hand-off for an announcement CTA; restores focus to the CTA on close. */
@Composable
internal fun HomeAnnouncementCtaQr(
    cta: AnnouncementPolicy.Cta,
    onClose: () -> Unit,
) {
    val qr = remember(cta.url) { runCatching { QrCodeGenerator.generate(cta.url, 420) }.getOrNull() }
    QrCodeOverlay(
        qrBitmap = qr,
        serverUrl = cta.url,
        instruction = stringResource(R.string.home_announcement_qr_instruction),
        onClose = onClose,
    )
}

/** Returns focus to the CTA after the QR overlay closes (the overlay's own button is gone). */
@Composable
internal fun RestoreAnnouncementCtaFocus(qrOpen: Boolean, ctaFocusRequester: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(qrOpen) {
        if (qrOpen) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            runCatching { ctaFocusRequester.requestFocus() }
        }
    }
}

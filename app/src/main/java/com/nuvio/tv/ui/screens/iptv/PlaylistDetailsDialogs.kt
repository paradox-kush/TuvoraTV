@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.iptv.HoldToConfirmPolicy
import com.nuvio.tv.core.iptv.ProviderContact
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

private val DestructiveFill = Color(0xFF4A2323)
private val DestructiveHeld = Color(0xFF8C3A3A)

/**
 * Contact opens a sub-dialog with text AND a QR code per contact (the TV cannot open WhatsApp or
 * Telegram itself): scan with a phone. Only the contacts the provider set are shown; the single focus
 * target is Close (the QR rows are read-only).
 */
@Composable
fun ContactDialog(providerName: String, support: ProviderSupport, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { closeFocus.requestFocusAfterFrames() }
    val contacts = remember(support) { support.contacts() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.iptv_contact_title, providerName),
        subtitle = stringResource(R.string.iptv_contact_subtitle),
        width = (if (contacts.size > 2) 780 else 560).dp,
        usePlatformDefaultWidth = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            contacts.forEach { ContactColumn(it) }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(closeFocus),
            scale = ButtonDefaults.scale(focusedScale = 1f),
        ) { Text(stringResource(R.string.iptv_contact_close), fontSize = 18.sp) }
    }
}

@Composable
private fun ContactColumn(contact: ProviderContact) {
    val qr = remember(contact.url) { runCatching { QrCodeGenerator.generate(contact.url, 360, margin = 1) }.getOrNull() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(132.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(6.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            when (contact.kind) {
                ProviderContact.Kind.WHATSAPP -> "WhatsApp"
                ProviderContact.Kind.TELEGRAM -> "Telegram"
                ProviderContact.Kind.EMAIL -> "Email"
                ProviderContact.Kind.WEBSITE -> "Website"
            },
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary,
        )
        Text(contact.text, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * Detach / Remove confirm: Cancel is focused first; the destructive action is HOLD OK for 2 s with a
 * filling ring — no typing on a remote (decision 6.7). A quick press or an early release does nothing.
 */
@Composable
fun HoldConfirmDialog(
    title: String,
    message: String,
    holdLabel: String,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    extraMessage: String? = null,
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancelFocus.requestFocusAfterFrames() }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = message, width = 560.dp, usePlatformDefaultWidth = false) {
        extraMessage?.let { Text(it, fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary) }
        // Focus starts on Cancel so a stray OK can never delete.
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().focusRequester(cancelFocus),
            scale = ButtonDefaults.scale(focusedScale = 1f),
        ) { Text(stringResource(R.string.iptv_cancel), fontSize = 18.sp) }
        HoldToConfirmButton(label = holdLabel, onConfirmed = onConfirmed, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.iptv_hold_hint),
            fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A button that confirms only after OK is HELD for [HoldToConfirmPolicy.DEFAULT_HOLD_MS]. A ring fills
 * while held; releasing early (or moving focus away) resets it. The press must START on this button, so
 * the key-up of the click that opened the dialog can never count.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    policy: HoldToConfirmPolicy = remember { HoldToConfirmPolicy() },
) {
    var heldMs by remember { mutableLongStateOf(0L) }
    var holdStartedAt by remember { mutableLongStateOf(-1L) }
    var focused by remember { mutableStateOf(false) }
    val confirmed by rememberUpdatedState(onConfirmed)

    LaunchedEffect(holdStartedAt) {
        if (holdStartedAt < 0) {
            heldMs = 0L
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { }
            heldMs = SystemClock.uptimeMillis() - holdStartedAt
            if (policy.isConfirmed(heldMs)) {
                holdStartedAt = -1L
                confirmed()
                return@LaunchedEffect
            }
        }
    }

    val progress = policy.progress(heldMs)
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(DestructiveFill)
            .drawBehind {
                // The fill sweeps across the pill as OK is held.
                drawRect(DestructiveHeld, size = Size(size.width * progress, size.height))
            }
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) holdStartedAt = -1L
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val select = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                if (!select) return@onPreviewKeyEvent false
                when (native.action) {
                    AndroidKeyEvent.ACTION_DOWN -> if (native.repeatCount == 0 && holdStartedAt < 0) {
                        holdStartedAt = SystemClock.uptimeMillis()
                    }
                    AndroidKeyEvent.ACTION_UP -> holdStartedAt = -1L
                }
                true
            }
            .focusable(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(Color.White.copy(alpha = 0.28f), 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            drawArc(Color.White, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
    }
}

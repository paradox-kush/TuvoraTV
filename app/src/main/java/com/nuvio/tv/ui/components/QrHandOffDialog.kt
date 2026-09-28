package com.nuvio.tv.ui.components

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nuvio.tv.core.links.ExternalLinkPolicy
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.screens.addon.QrCodeOverlay

/**
 * Full-screen QR hand-off for a URL, hosted in its own [Dialog] window so the D-pad can never
 * wander onto the screen behind it (the dialog window owns input focus) and Back closes it.
 * Pair with [RestoreFocusOnClose] so focus lands back on the element that opened it.
 */
@Composable
fun QrHandOffDialog(
    url: String,
    instruction: String,
    onClose: () -> Unit,
) {
    val qr = remember(url) { runCatching { QrCodeGenerator.generate(url, 420) }.getOrNull() }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        QrCodeOverlay(
            qrBitmap = qr,
            serverUrl = url,
            instruction = instruction,
            onClose = onClose,
        )
    }
}

/**
 * Returns a function that tries to open a URL in a browser. It returns `false` — instead of
 * crashing or handing the intent to a system "no app can do this" toast — when no activity
 * resolves the link or the launch is refused ([ExternalLinkPolicy]); the caller then shows a
 * [QrHandOffDialog] so the page can still be read on a phone.
 */
@Composable
fun rememberExternalLinkOpener(): (String) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { url ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val canResolve = runCatching {
                context.packageManager
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .isNotEmpty()
            }.getOrDefault(false)
            ExternalLinkPolicy.open(url, canResolve) {
                context.startActivity(intent)
            } == ExternalLinkPolicy.Outcome.Opened
        }
    }
}

/** Requests focus on [target] once an overlay that was [open] closes again. */
@Composable
fun RestoreFocusOnClose(open: Boolean, target: FocusRequester) {
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            wasOpen = true
        } else if (wasOpen) {
            wasOpen = false
            runCatching { target.requestFocus() }
        }
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.iptv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.iptv.ProviderSetupConfig
import com.nuvio.tv.core.iptv.SetupCode
import com.nuvio.tv.core.iptv.SetupMessage
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme

private val KeyOutline = Color.White

/** Keypad order: the 31 code characters in alphabet order, then Delete (32 keys = 4 rows of 8). */
private val KEYPAD_CHARS: List<Char> = SetupCode.ALPHABET.toList()
private const val KEYS_PER_ROW = 8

/**
 * "Enter setup code" (Step 2). Left (not focusable): a QR to the web page + the steps + which account
 * the code will be added to. Right: twelve code boxes and a compact 31-key keypad (focus starts on the
 * first key; the dashes are automatic; Continue enables at 12 valid characters), then the preview with a
 * profile choice. A phone that redeems the code finishes this screen by itself.
 */
@Composable
fun IptvSetupCodeScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onFinished: () -> Unit,
    viewModel: IptvSetupCodeViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { if (it is SetupCodeEvent.Finished) onFinished() }
    }
    // Lifecycle-bound: the phone-redeem wait runs only while this screen is RESUMED and signed in, and
    // stops for good once a character is typed on the TV.
    LaunchedEffect(ui.signedIn, ui.phase == SetupPhase.ENTRY && ui.typed.isEmpty()) {
        if (ui.signedIn && ui.phase == SetupPhase.ENTRY && ui.typed.isEmpty() && !viewModel.typedOnTv) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.waitForPhoneRedeem() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = 48.dp, vertical = 27.dp)
            // A hardware keyboard (or a remote's number keys) types into the boxes too.
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (ui.phase != SetupPhase.ENTRY || native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_DEL -> { viewModel.backspace(); true }
                    native.unicodeChar != 0 && native.unicodeChar.toChar().uppercaseChar() in SetupCode.ALPHABET -> {
                        viewModel.type(native.unicodeChar.toChar()); true
                    }
                    else -> false
                }
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            InfoPane(accountLabel = ui.accountLabel, modifier = Modifier.weight(0.38f).fillMaxHeight())
            Spacer(Modifier.width(40.dp))
            Box(modifier = Modifier.weight(0.62f).fillMaxHeight()) {
                when (ui.phase) {
                    // Signed out people can still type: Continue then asks them to sign in (decision 6.1) and
                    // the code stays in memory meanwhile.
                    SetupPhase.ENTRY, SetupPhase.CHECKING -> EntryPane(ui, viewModel)
                    SetupPhase.NEEDS_SIGN_IN -> SignInPane(onSignIn)
                    SetupPhase.PREVIEW, SetupPhase.ADDING -> PreviewPane(ui, viewModel)
                    SetupPhase.ADDED_OTHER_PROFILE -> AddedElsewherePane(ui, onBack)
                    SetupPhase.NOTHING_ADDED -> NothingAddedPane(ui, onBack)
                }
            }
        }
    }
}

// --- left: QR + steps (read-only) -------------------------------------------------------------

@Composable
private fun InfoPane(accountLabel: String?, modifier: Modifier) {
    val url = ProviderSetupConfig.CLAIM_ENTRY_URL
    val qr = remember(url) { runCatching { QrCodeGenerator.generate(url, 480, margin = 1) }.getOrNull() }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.iptv_setup_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_iptv_setup_qr),
                modifier = Modifier.size(150.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(6.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(stringResource(R.string.iptv_setup_step_phone), fontSize = 17.sp, color = NuvioTheme.colors.TextPrimary)
        Text(
            stringResource(R.string.iptv_setup_open_url, url.removePrefix("https://").removePrefix("http://")),
            fontSize = 16.sp, color = NuvioTheme.colors.TextSecondary,
        )
        accountLabel?.let {
            Text(
                stringResource(R.string.iptv_setup_adding_to, it),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.Secondary, maxLines = 2,
            )
        }
    }
}

// --- right: entry ------------------------------------------------------------------------------

@Composable
private fun EntryPane(ui: SetupCodeUiState, vm: IptvSetupCodeViewModel) {
    val firstKeyFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    val checking = ui.phase == SetupPhase.CHECKING
    LaunchedEffect(Unit) { firstKeyFocus.requestFocusAfterFrames() }
    // The 12th character moves focus straight to Continue: no hunt for it below the keypad.
    LaunchedEffect(ui.isComplete) { if (ui.isComplete && !checking) continueFocus.requestFocusAfterFrames() }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CodeBoxes(ui.typed)
        val message = ui.message
        if (message != null) {
            Text(setupMessageText(message), fontSize = 18.sp, color = NuvioTheme.colors.Error)
        } else if (checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NuvioTheme.colors.Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.iptv_setup_checking), fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary)
            }
        }
        Keypad(
            enabled = !checking,
            firstKeyFocus = firstKeyFocus,
            onChar = vm::type,
            onDelete = vm::backspace,
        )
        Button(
            onClick = vm::continueTapped,
            enabled = ui.isComplete && !checking,
            modifier = Modifier.width(220.dp).focusRequester(continueFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary,
                contentColor = NuvioTheme.colors.OnSecondary,
                focusedContainerColor = NuvioTheme.colors.Secondary,
                focusedContentColor = NuvioTheme.colors.OnSecondary,
            ),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
        ) { Text(stringResource(R.string.iptv_setup_continue), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun setupMessageText(message: SetupMessage): String = stringResource(
    when (message) {
        SetupMessage.EMPTY -> R.string.setup_code_msg_empty
        SetupMessage.BAD_CHARACTERS -> R.string.setup_code_msg_bad_characters
        SetupMessage.WRONG_LENGTH -> R.string.setup_code_msg_wrong_length
        SetupMessage.EXPIRED -> R.string.setup_code_msg_expired
        SetupMessage.RATE_LIMITED -> R.string.setup_code_msg_rate_limited
        SetupMessage.NETWORK -> R.string.setup_code_msg_network
        SetupMessage.UNUSABLE -> R.string.setup_code_msg_unusable
        SetupMessage.PROFILE_NOT_FOUND -> R.string.setup_code_msg_profile_not_found
        SetupMessage.NOTHING_NO_LOGIN -> R.string.setup_code_msg_nothing_no_login
        SetupMessage.NOTHING_BAD_ADDRESS -> R.string.setup_code_msg_nothing_bad_address
        SetupMessage.ALREADY_SET_UP -> R.string.setup_code_msg_already_set_up
        SetupMessage.NOTHING_ADDED -> R.string.setup_code_msg_nothing_added
    }
)

/** `TUV-` then twelve boxes in groups of four; the dashes are drawn, never typed. Read-only. */
@Composable
private fun CodeBoxes(typed: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(SetupCode.PREFIX, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary)
        for (group in 0 until 3) {
            Text("-", fontSize = 20.sp, color = NuvioTheme.colors.TextSecondary)
            for (i in 0 until 4) {
                val index = group * 4 + i
                val ch = typed.getOrNull(index)
                val isNext = index == typed.length
                Box(
                    modifier = Modifier
                        .size(width = 28.dp, height = 44.dp)
                        .background(NuvioTheme.colors.BackgroundCard, RoundedCornerShape(8.dp))
                        .border(
                            if (isNext) 2.dp else 1.dp,
                            if (isNext) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ch != null) Text(ch.toString(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun Keypad(enabled: Boolean, firstKeyFocus: FocusRequester, onChar: (Char) -> Unit, onDelete: () -> Unit) {
    // 31 characters + a Delete key = 32 keys, four rows of eight.
    val keys: List<Char?> = KEYPAD_CHARS + listOf(null)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(KEYS_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEachIndexed { colIndex, key ->
                    val first = rowIndex == 0 && colIndex == 0
                    Key(
                        label = key?.toString() ?: stringResource(R.string.iptv_setup_key_delete),
                        wide = key == null,
                        enabled = enabled,
                        modifier = if (first) Modifier.focusRequester(firstKeyFocus) else Modifier,
                        onClick = { if (key == null) onDelete() else onChar(key) },
                    )
                }
            }
        }
    }
}

/** One keypad key: a white outline + brighter fill + a small scale on focus. */
@Composable
private fun Key(label: String, wide: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = if (wide) 108.dp else 54.dp, height = 50.dp)
            .focusProperties { canFocus = enabled },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, KeyOutline), shape = shape),
        ),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.08f, pressedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label, fontSize = if (wide) 16.sp else 22.sp, fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1,
            )
        }
    }
}

// --- right: signed out -------------------------------------------------------------------------

@Composable
private fun SignInPane(onSignIn: () -> Unit) {
    val signInFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { signInFocus.requestFocusAfterFrames() }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.iptv_setup_signed_out_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.iptv_setup_signed_out_body), fontSize = 20.sp, color = NuvioTheme.colors.TextSecondary)
        Button(
            onClick = onSignIn,
            modifier = Modifier.width(240.dp).focusRequester(signInFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary, contentColor = NuvioTheme.colors.OnSecondary,
                focusedContainerColor = NuvioTheme.colors.Secondary, focusedContentColor = NuvioTheme.colors.OnSecondary,
            ),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
        ) { Text(stringResource(R.string.iptv_setup_sign_in), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

// --- right: preview + profile choice -----------------------------------------------------------

@Composable
private fun PreviewPane(ui: SetupCodeUiState, vm: IptvSetupCodeViewModel) {
    val preview = ui.preview ?: return
    val addFocus = remember { FocusRequester() }
    val adding = ui.phase == SetupPhase.ADDING
    LaunchedEffect(Unit) { addFocus.requestFocusAfterFrames() }
    val chosen = ui.profiles.firstOrNull { it.id == ui.chosenProfileId } ?: ui.profiles.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.iptv_setup_preview_title, preview.providerName), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary)
        Text(stringResource(R.string.iptv_setup_will_add).uppercase(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = NuvioTheme.colors.TextSecondary)
        preview.playlists.take(5).forEach { Text(it.name, fontSize = 20.sp, color = NuvioTheme.colors.TextPrimary, maxLines = 1) }
        if (preview.playlists.size > 5) {
            Text("+${preview.playlists.size - 5}", fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary)
        }
        // Store builds hide add-ons: the preview must not name any (decision 6.5).
        if (AppFeaturePolicy.addonsEnabled && preview.addons.isNotEmpty()) {
            Text(stringResource(R.string.iptv_setup_addons, preview.addons.joinToString(", ")), fontSize = 18.sp, color = NuvioTheme.colors.TextSecondary)
        }
        ui.message?.let { Text(setupMessageText(it), fontSize = 18.sp, color = NuvioTheme.colors.Error) }
        if (ui.profiles.size > 1) {
            Text(stringResource(R.string.iptv_setup_add_to_profile).uppercase(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = NuvioTheme.colors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ui.profiles.forEach { p ->
                    val selected = p.id == ui.chosenProfileId
                    Button(
                        onClick = { vm.chooseProfile(p.id) },
                        enabled = !adding,
                        colors = ButtonDefaults.colors(
                            containerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary,
                            focusedContainerColor = if (selected) NuvioTheme.colors.Secondary.copy(alpha = 0.3f) else NuvioTheme.colors.FocusBackground,
                            focusedContentColor = NuvioTheme.colors.TextPrimary,
                        ),
                        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
                        shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                    ) { Text(p.name, fontSize = 18.sp, maxLines = 1) }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = vm::confirmAdd,
            enabled = !adding,
            modifier = Modifier.fillMaxWidth(0.8f).focusRequester(addFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary, contentColor = NuvioTheme.colors.OnSecondary,
                focusedContainerColor = NuvioTheme.colors.Secondary, focusedContentColor = NuvioTheme.colors.OnSecondary,
            ),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
        ) {
            Text(
                if (adding) stringResource(R.string.iptv_setup_adding)
                else stringResource(R.string.iptv_setup_add_to, chosen?.name.orEmpty()),
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (!adding) {
            Button(
                onClick = vm::useDifferentCode,
                modifier = Modifier.fillMaxWidth(0.8f),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard, contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground, focusedContentColor = NuvioTheme.colors.TextPrimary,
                ),
                border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
            ) { Text(stringResource(R.string.iptv_setup_back), fontSize = 18.sp) }
        }
    }
}

@Composable
private fun AddedElsewherePane(ui: SetupCodeUiState, onDone: () -> Unit) {
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { doneFocus.requestFocusAfterFrames() }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(R.string.iptv_setup_added_other_profile, ui.addedProviderName.orEmpty(), ui.addedProfileName.orEmpty()),
            fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary, textAlign = TextAlign.Start,
        )
        Button(
            onClick = onDone,
            modifier = Modifier.width(220.dp).focusRequester(doneFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary, contentColor = NuvioTheme.colors.OnSecondary,
                focusedContainerColor = NuvioTheme.colors.Secondary, focusedContentColor = NuvioTheme.colors.OnSecondary,
            ),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
        ) { Text(stringResource(R.string.iptv_setup_done), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** The code was accepted but added nothing (the provider's login/address is not ready, or it was already set up). */
@Composable
private fun NothingAddedPane(ui: SetupCodeUiState, onDone: () -> Unit) {
    val doneFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { doneFocus.requestFocusAfterFrames() }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ui.message?.let {
            Text(setupMessageText(it), fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = NuvioTheme.colors.TextPrimary)
        }
        Button(
            onClick = onDone,
            modifier = Modifier.width(220.dp).focusRequester(doneFocus),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.Secondary, contentColor = NuvioTheme.colors.OnSecondary,
                focusedContainerColor = NuvioTheme.colors.Secondary, focusedContentColor = NuvioTheme.colors.OnSecondary,
            ),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(3.dp, KeyOutline))),
            scale = ButtonDefaults.scale(focusedScale = 1.03f),
        ) { Text(stringResource(R.string.iptv_setup_done), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
    }
}

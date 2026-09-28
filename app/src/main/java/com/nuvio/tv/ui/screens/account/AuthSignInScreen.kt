@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.BorderStroke
import androidx.tv.material3.Border
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.QrHandOffDialog
import com.nuvio.tv.ui.components.RestoreFocusOnClose
import com.nuvio.tv.ui.components.rememberExternalLinkOpener
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun AuthSignInScreen(
    onBackPress: () -> Unit = {},
    onNavigateToQrSignIn: () -> Unit = {},
    onSuccess: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSignUpEligibilityConfirmation by remember { mutableStateOf(false) }
    // Many Android TV devices ship no browser: links try it first and otherwise hand the page to
    // the viewer's phone as a QR (in its own dialog window, above the sign-up dialog).
    val openLink = rememberExternalLinkOpener()
    var noBrowserQrUrl by remember { mutableStateOf<String?>(null) }
    var noBrowserQrOrigin by remember { mutableStateOf<FocusRequester?>(null) }
    val termsLinkFocusRequester = remember { FocusRequester() }
    val privacyLinkFocusRequester = remember { FocusRequester() }
    fun openLinkFrom(url: String, origin: FocusRequester) {
        if (!openLink(url)) {
            noBrowserQrOrigin = origin
            noBrowserQrUrl = url
        }
    }

    BackHandler { onBackPress() }

    // B74: land D-pad focus on Email so the screen opens with a visible focus ring, and start
    // clean. On first launch this screen shares its AccountViewModel with the QR screen, whose
    // error ("No internet connection.") would otherwise show under an empty form; clear again on
    // the way out so ours doesn't leak back to QR.
    val emailFocusRequester = remember { FocusRequester() }
    DisposableEffect(Unit) {
        viewModel.clearError()
        onDispose { viewModel.clearError() }
    }
    LaunchedEffect(Unit) {
        runCatching { emailFocusRequester.requestFocus() }
    }

    // Sign-in / sign-up flips authState to FullAccount on success.
    LaunchedEffect(uiState.authState) {
        if (uiState.authState is AuthState.FullAccount) onSuccess()
    }

    val canSubmit = email.isNotBlank() && password.isNotBlank() && !uiState.isLoading
    fun submit() {
        if (canSubmit) viewModel.signIn(email.trim(), password)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .background(
                    color = NuvioTheme.colors.BackgroundElevated,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(NuvioTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.auth_signin_title),
                style = MaterialTheme.typography.headlineSmall,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(18.dp))

            InputField(
                value = email,
                onValueChange = { email = it },
                placeholder = "Email",
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
                modifier = Modifier.focusRequester(emailFocusRequester)
            )
            Spacer(modifier = Modifier.height(12.dp))
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = { submit() }
            )

            uiState.error?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF6B6B),
                    textAlign = TextAlign.Center
                )
            }

            if (uiState.debugBackendSwitchEnabled) {
                Spacer(modifier = Modifier.height(18.dp))
                DebugSyncBackendSwitchCard(
                    uiState = uiState,
                    requireConfirmation = false,
                    onSwitchBackend = viewModel::switchDebugBackend
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            Button(
                onClick = { submit() },
                enabled = canSubmit,
                colors = authButtonColors(),
                border = authButtonBorder(primary = true),
                scale = AUTH_BUTTON_SCALE,
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.isLoading) stringResource(R.string.auth_signin_loading)
                    else stringResource(R.string.auth_signin_btn),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { if (canSubmit) showSignUpEligibilityConfirmation = true },
                enabled = canSubmit,
                colors = authButtonColors(),
                border = authButtonBorder(primary = false),
                scale = AUTH_BUTTON_SCALE,
                shape = ButtonDefaults.shape(RoundedCornerShape(50)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.auth_signin_create_btn),
                    modifier = Modifier.padding(vertical = NuvioTheme.spacing.xs),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    if (showSignUpEligibilityConfirmation) {
        NuvioDialog(
            onDismiss = { showSignUpEligibilityConfirmation = false },
            title = stringResource(R.string.auth_signup_eligibility_title),
            subtitle = stringResource(R.string.auth_signup_eligibility_message),
            suppressFirstKeyUp = false,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                // Catch a typo'd domain (gmail.con) before the server refuses it; selecting the
                // suggestion fixes the address and hands focus to the next control.
                EmailTypoSuggestion.suggestEmail(email)?.let { suggested ->
                    AuthTextLink(
                        text = stringResource(R.string.auth_signup_email_typo_suggestion, suggested),
                        onClick = {
                            email = suggested
                            termsLinkFocusRequester.requestFocus()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.Secondary,
                    )
                }
                AuthTextLink(
                    text = stringResource(R.string.auth_signup_view_terms),
                    onClick = { openLinkFrom(TUVORA_TERMS_URL, termsLinkFocusRequester) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.focusRequester(termsLinkFocusRequester),
                )
                SignUpEmailNotice(
                    onOpenPrivacy = { openLinkFrom(TUVORA_PRIVACY_URL, privacyLinkFocusRequester) },
                    modifier = Modifier.focusRequester(privacyLinkFocusRequester),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { showSignUpEligibilityConfirmation = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            if (canCreateAccount(email, password, uiState.isLoading, eligibilityConfirmed = true)) {
                                showSignUpEligibilityConfirmation = false
                                viewModel.signUp(email.trim(), password)
                            }
                        },
                        enabled = canCreateAccount(email, password, uiState.isLoading, eligibilityConfirmed = true),
                    ) {
                        Text(stringResource(R.string.auth_signup_confirm_create))
                    }
                }
            }
        }
    }

    noBrowserQrUrl?.let { url ->
        QrHandOffDialog(
            url = url,
            instruction = stringResource(R.string.link_qr_no_browser_instruction),
            onClose = { noBrowserQrUrl = null },
        )
    }
    noBrowserQrOrigin?.let { origin ->
        RestoreFocusOnClose(open = noBrowserQrUrl != null, target = origin)
    }
}

/**
 * Muted marketing/privacy notice shown only in the sign-up confirmation, directly under the
 * Terms link. The Privacy Policy is opened the same way the Terms link is (browser, else QR);
 * because it is clickable it is focusable, so it carries a visible D-pad focus treatment.
 */
@Composable
private fun SignUpEmailNotice(onOpenPrivacy: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Text(
        text = stringResource(R.string.auth_signup_email_notice),
        style = MaterialTheme.typography.bodySmall,
        color = if (isFocused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (isFocused) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape)
                else Modifier
            )
            .background(
                color = if (isFocused) NuvioTheme.colors.FocusBackground else Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onOpenPrivacy)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
    )
}

// B70: only the focused button is filled (plus the focus ring), so focus is never ambiguous; the
// primary action keeps an accent outline at rest instead of a solid white fill that read as focused.
// B73: full-width buttons don't grow on focus, so they stay inside the card.
private val AUTH_BUTTON_SCALE = ButtonDefaults.scale(focusedScale = 1f)

@Composable
private fun authButtonColors() = ButtonDefaults.colors(
    containerColor = NuvioTheme.colors.BackgroundCard,
    focusedContainerColor = NuvioTheme.colors.Secondary,
    contentColor = NuvioTheme.colors.TextPrimary,
    focusedContentColor = NuvioTheme.colors.OnSecondary,
    disabledContainerColor = NuvioTheme.colors.BackgroundCard,
    disabledContentColor = NuvioTheme.colors.TextTertiary
)

@Composable
private fun authButtonBorder(primary: Boolean) = ButtonDefaults.border(
    border = Border(
        border = BorderStroke(
            if (primary) 1.5.dp else NuvioTheme.spacing.hairline,
            if (primary) NuvioTheme.colors.Secondary else NuvioTheme.colors.Border
        ),
        shape = RoundedCornerShape(50)
    ),
    focusedBorder = Border(
        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
        shape = RoundedCornerShape(50)
    ),
    disabledBorder = Border(
        border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
        shape = RoundedCornerShape(50)
    )
)

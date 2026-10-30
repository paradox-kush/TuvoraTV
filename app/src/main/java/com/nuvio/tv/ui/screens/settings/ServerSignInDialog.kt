@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.QuickConnectCadence
import com.nuvio.tv.data.mediaserver.QuickConnectState
import com.nuvio.tv.data.mediaserver.ServerCertificateException
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.formatFingerprint
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme

internal data class ServerSignInRequest(
    val provider: ServerProvider,
    val address: String = "",
    val username: String = ""
)

@Composable
internal fun ServerSignInDialog(
    request: ServerSignInRequest,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.signIn.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(request.address) }
    var username by remember { mutableStateOf(request.username) }
    var password by remember { mutableStateOf("") }
    val provider = request.provider
    val canConnect = !state.connecting && address.isNotBlank() && username.isNotBlank()
    val connectTo = { target: String ->
        viewModel.connect(provider, target, username, password) { connection ->
            password = ""
            onConnected(connection)
        }
    }
    val connect = { if (canConnect) connectTo(address) }
    val quickConnectTo = { target: String ->
        viewModel.startQuickConnect(provider, target) { connection -> onConnected(connection) }
    }
    val startQuickConnect = { quickConnectTo(address) }

    LaunchedEffect(Unit) { viewModel.resetSignIn() }
    // Closing the dialog ends the Quick Connect poll.
    DisposableEffect(Unit) { onDispose { viewModel.stopQuickConnect() } }

    val plainHttp = state.plainHttpAddress
    if (plainHttp != null) {
        PlainHttpPrompt(
            httpAddress = plainHttp,
            onContinue = {
                // Only now, with the user's yes, is anything sent over http: retry exactly what hit the prompt.
                val viaQuickConnect = state.quickConnect != null
                address = plainHttp
                if (viaQuickConnect) quickConnectTo(plainHttp) else connectTo(plainHttp)
            },
            onCancel = { viewModel.resetSignIn() }
        )
        return
    }
    val certificate = state.certificate
    if (certificate != null) {
        CertificatePrompt(
            certificate = certificate,
            onTrust = {
                viewModel.trustCertificate(certificate)
                // Retry exactly what hit the certificate.
                if (state.quickConnect != null) startQuickConnect() else connect()
            },
            onCancel = { viewModel.resetSignIn() }
        )
        return
    }
    val quickConnect = state.quickConnect
    if (quickConnect != null) {
        QuickConnectPanel(
            provider = provider,
            state = quickConnect,
            onRetry = startQuickConnect,
            onUsePassword = { viewModel.resetSignIn() },
            onCancel = { viewModel.resetSignIn(); onDismiss() }
        )
        return
    }

    NuvioDialog(
        onDismiss = { if (!state.connecting) onDismiss() },
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        subtitle = stringResource(R.string.servers_sign_in_subtitle, provider.displayName),
        width = 640.dp
    ) {
        SignInField(label = stringResource(R.string.servers_address)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                InputField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = stringResource(R.string.servers_address_hint),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        SignInField(label = stringResource(R.string.servers_username)) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.servers_username),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_password)) {
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.servers_password),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = connect,
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.error?.let { failure ->
            Text(
                text = failure.signInMessage(provider),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
        SettingsDialogActionRow {
            if (provider.supportsQuickConnect) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_qc_button),
                    onClick = startQuickConnect,
                    enabled = !state.connecting && address.isNotBlank()
                )
            }
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                enabled = !state.connecting
            )
            SettingsDialogActionButton(
                text = stringResource(if (state.connecting) R.string.servers_connecting else R.string.servers_connect),
                onClick = connect,
                primary = true,
                enabled = canConnect
            )
        }
    }
}

@Composable
private fun SignInField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        field()
    }
}

@Composable
private fun ServerFailure.signInMessage(provider: ServerProvider): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.UNSUPPORTED -> stringResource(R.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.CERTIFICATE_UNTRUSTED -> stringResource(R.string.servers_failure_cert_untrusted)
    ServerFailure.CERTIFICATE_CHANGED -> stringResource(R.string.servers_failure_cert_changed)
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}

@Composable
private fun CertificatePrompt(
    certificate: ServerCertificateException,
    onTrust: () -> Unit,
    onCancel: () -> Unit
) {
    val changed = certificate.failure == ServerFailure.CERTIFICATE_CHANGED
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(if (changed) R.string.servers_cert_changed_title else R.string.servers_cert_title),
        subtitle = stringResource(
            if (changed) R.string.servers_cert_changed_body else R.string.servers_cert_body,
            certificate.authority
        ),
        width = 720.dp
    ) {
        if (changed) {
            certificate.pinned?.let { pinned ->
                FingerprintLine(stringResource(R.string.servers_cert_previous), pinned)
            }
            FingerprintLine(stringResource(R.string.servers_cert_presented), certificate.fingerprint)
        } else {
            FingerprintLine(stringResource(R.string.servers_cert_fingerprint), certificate.fingerprint)
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
            SettingsDialogActionButton(
                text = stringResource(if (changed) R.string.servers_cert_trust_new else R.string.servers_cert_trust),
                onClick = onTrust,
                primary = true
            )
        }
    }
}

@Composable
private fun PlainHttpPrompt(
    httpAddress: String,
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.servers_plain_http_title),
        subtitle = stringResource(R.string.servers_plain_http_message, httpAddress),
        width = 720.dp
    ) {
        SettingsDialogActionRow {
            SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
            SettingsDialogActionButton(
                text = stringResource(R.string.servers_plain_http_continue),
                onClick = onContinue,
                primary = true
            )
        }
    }
}

@Composable
private fun FingerprintLine(label: String, fingerprint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextSecondary)
        Text(text = formatFingerprint(fingerprint), style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextPrimary)
    }
}

@Composable
private fun QuickConnectPanel(
    provider: ServerProvider,
    state: QuickConnectState,
    onRetry: () -> Unit,
    onUsePassword: () -> Unit,
    onCancel: () -> Unit
) {
    NuvioDialog(
        onDismiss = onCancel,
        title = stringResource(R.string.servers_qc_title),
        subtitle = stringResource(R.string.servers_qc_instructions),
        width = 640.dp
    ) {
        when (state) {
            QuickConnectState.Requesting -> Text(
                text = stringResource(R.string.servers_qc_requesting),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            is QuickConnectState.Waiting -> {
                Text(
                    text = QuickConnectCadence.displayCode(state.code),
                    style = MaterialTheme.typography.displayMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    text = stringResource(R.string.servers_qc_expires, QuickConnectCadence.countdownLabel(state.remainingMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }
            QuickConnectState.Completing,
            is QuickConnectState.Authorised -> Text(
                text = stringResource(R.string.servers_qc_completing),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            QuickConnectState.Expired -> QuickConnectError(stringResource(R.string.servers_qc_expired))
            is QuickConnectState.Failed -> QuickConnectError(
                when (state.error.failure) {
                    ServerFailure.AUTH_REQUIRED, ServerFailure.FORBIDDEN -> stringResource(R.string.servers_qc_disabled)
                    ServerFailure.UNSUPPORTED -> stringResource(R.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
                    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
                    else -> stringResource(R.string.servers_qc_failed)
                }
            )
        }
        val terminal = state is QuickConnectState.Expired || state is QuickConnectState.Failed
        SettingsDialogActionRow {
            if (terminal) {
                SettingsDialogActionButton(text = stringResource(R.string.servers_qc_use_password), onClick = onUsePassword)
                SettingsDialogActionButton(text = stringResource(R.string.servers_qc_retry), onClick = onRetry, primary = true)
            } else {
                SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel, primary = true)
            }
        }
    }
}

@Composable
private fun QuickConnectError(message: String) {
    Text(text = message, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.Error)
}

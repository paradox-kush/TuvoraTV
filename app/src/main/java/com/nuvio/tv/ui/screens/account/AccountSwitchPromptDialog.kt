@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AccountSwitchPrompt
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * D1: a different account signed in while a lost session's data is still on this TV. Focus starts on
 * the safe choice ("Keep data"); Back also keeps the data.
 */
@Composable
internal fun AccountSwitchPromptDialog(
    prompt: AccountSwitchPrompt,
    onConfirm: () -> Unit,
    onKeep: () -> Unit
) {
    val keepFocusRequester = remember { FocusRequester() }
    val unknownAccount = stringResource(R.string.account_switch_unknown_account)

    LaunchedEffect(prompt.newUserId) {
        keepFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onKeep,
        title = stringResource(R.string.account_switch_title),
        subtitle = stringResource(
            R.string.account_switch_message,
            prompt.previousOwner.email ?: unknownAccount,
            prompt.newEmail ?: unknownAccount
        ),
        suppressFirstKeyUp = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Button(
                onClick = onKeep,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(keepFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.account_switch_keep))
            }
            Button(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFFC62828).copy(alpha = 0.25f),
                    contentColor = Color(0xFFF44336)
                )
            ) {
                Text(stringResource(R.string.account_switch_confirm))
            }
        }
    }
}

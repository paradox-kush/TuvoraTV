package com.nuvio.tv.ui.screens.iptv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The number being typed (redesign change 7 + B1). Held in its own state object so a digit recomposes only the
 * readout, never the guide (B180). Main thread only.
 */
internal class NumberEntryState {
    var buffer by mutableStateOf("")
        private set
    var tick by mutableIntStateOf(0)
        private set
    /** "No channel 99" after a commit that matched nothing; cleared by the next digit or its own timeout. */
    var noMatch by mutableStateOf<String?>(null)

    val active: Boolean get() = buffer.isNotEmpty()

    fun digit(d: Int) {
        buffer = NumberEntryPolicy.append(buffer, d)
        noMatch = null
        tick++
    }

    /** Takes the buffer for a commit and clears it. */
    fun take(): String = buffer.also { buffer = "" }

    fun cancel() {
        buffer = ""
        noMatch = null
    }
}

/** 0-9 on the remote (and the number pad). */
internal fun androidx.compose.ui.input.key.Key.remoteDigit(): Int? = when (this) {
    androidx.compose.ui.input.key.Key.Zero, androidx.compose.ui.input.key.Key.NumPad0 -> 0
    androidx.compose.ui.input.key.Key.One, androidx.compose.ui.input.key.Key.NumPad1 -> 1
    androidx.compose.ui.input.key.Key.Two, androidx.compose.ui.input.key.Key.NumPad2 -> 2
    androidx.compose.ui.input.key.Key.Three, androidx.compose.ui.input.key.Key.NumPad3 -> 3
    androidx.compose.ui.input.key.Key.Four, androidx.compose.ui.input.key.Key.NumPad4 -> 4
    androidx.compose.ui.input.key.Key.Five, androidx.compose.ui.input.key.Key.NumPad5 -> 5
    androidx.compose.ui.input.key.Key.Six, androidx.compose.ui.input.key.Key.NumPad6 -> 6
    androidx.compose.ui.input.key.Key.Seven, androidx.compose.ui.input.key.Key.NumPad7 -> 7
    androidx.compose.ui.input.key.Key.Eight, androidx.compose.ui.input.key.Key.NumPad8 -> 8
    androidx.compose.ui.input.key.Key.Nine, androidx.compose.ui.input.key.Key.NumPad9 -> 9
    else -> null
}

/**
 * Top-right readout of the number being typed, with the live match ("12 · Sky Sports"), "Previous channel" for a
 * lone 0, or "No channel 99". Not focusable. [nameAt] names the row a number lands on.
 */
@Composable
internal fun BoxScope.NumberEntryReadout(state: NumberEntryState, count: Int, nameAt: (Int) -> String?) {
    val text = when {
        state.active && NumberEntryPolicy.isPreviousChannel(state.buffer) ->
            stringResource(R.string.iptv_number_previous)
        state.active -> NumberEntryPolicy.match(state.buffer, count)?.let { index ->
            stringResource(R.string.iptv_number_match, (index + 1).toString(), nameAt(index).orEmpty())
        } ?: stringResource(R.string.iptv_number_no_match, state.buffer)
        state.noMatch != null -> stringResource(R.string.iptv_number_no_match, state.noMatch!!)
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .align(Alignment.TopEnd)
            // The 5% TV safe area: 48 dp sides, 27 dp top.
            .padding(top = 27.dp, end = 48.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
    )
}

/**
 * Commits the typed number [NumberEntryPolicy.COMMIT_MS] after the last digit, and clears "No channel N" after the
 * same wait.
 */
@Composable
internal fun NumberEntryTimers(state: NumberEntryState, onCommit: () -> Unit) {
    // Device pass 2, finding B: the effect outlives compositions, so it must call the CURRENT commit (which reads the
    // list on screen now). It kept the first composition's, when the list was still empty: "9" said "No channel 9".
    val latestCommit by androidx.compose.runtime.rememberUpdatedState(onCommit)
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.tick }.collectLatest {
            if (!state.active) return@collectLatest
            kotlinx.coroutines.delay(NumberEntryPolicy.COMMIT_MS)
            latestCommit()
        }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.noMatch }.collectLatest {
            if (it == null) return@collectLatest
            kotlinx.coroutines.delay(NumberEntryPolicy.COMMIT_MS)
            state.noMatch = null
        }
    }
}

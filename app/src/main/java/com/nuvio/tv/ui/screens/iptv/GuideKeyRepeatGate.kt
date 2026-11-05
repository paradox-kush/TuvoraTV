package com.nuvio.tv.ui.screens.iptv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

/** B56: gates held vertical D-pad repeats on a guide list through [GuideKeyRepeatPolicy]. */
internal fun Modifier.guideKeyRepeatGate(): Modifier = composed {
    val policy = remember { GuideKeyRepeatPolicy() }
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
        val key = when (native.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> GuideKeyRepeatPolicy.Key.Vertical
            else -> return@onPreviewKeyEvent false
        }
        val decision = policy.decide(
            key = key,
            repeatCount = native.repeatCount,
            eventTimeMs = native.eventTime,
            nowMs = SystemClock.uptimeMillis(),
        )
        decision == GuideKeyRepeatPolicy.Decision.Drop
    }
}

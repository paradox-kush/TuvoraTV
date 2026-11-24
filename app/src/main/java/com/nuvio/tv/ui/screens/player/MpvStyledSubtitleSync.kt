package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Fork (ANR rule, sync 17 follow-up): upstream's styled-subtitle sync reads the subtitle codec, `sid`
 * and `sub-text` from libmpv on every `sid`/`sub-text` change, and its caller runs on Main. The read
 * (and its style writes) run on the serial mpv lane ([MpvReads]); the text is shown back on the
 * caller's scope, and dropped when the player was released or swapped meanwhile ([isCurrent]).
 */
internal object MpvStyledSubtitleSync {
    fun launch(
        scope: CoroutineScope,
        read: () -> String?,
        isCurrent: () -> Boolean,
        show: (String?) -> Unit,
    ): Job = scope.launch {
        val text = MpvReads.read(read)
        if (isCurrent()) show(text)
    }
}

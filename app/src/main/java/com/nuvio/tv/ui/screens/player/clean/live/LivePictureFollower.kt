package com.nuvio.tv.ui.screens.player.clean.live

import com.nuvio.tv.core.picture.LivePicturePort
import com.nuvio.tv.core.picture.PictureChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * F28: the live picture (aspect + manual zoom) follows the channel on screen. Each channel opens with
 * its own remembered picture ([LivePicturePort], lane F's model per channel); a change applies at
 * once and is kept for THAT channel. A slow load for a channel the viewer already left is dropped,
 * so one channel's crop never lands on the next. Shared by the guide's playback owner and the clean
 * live player; [apply] is their host command.
 */
internal class LivePictureFollower(
    private val port: LivePicturePort,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(LivePicturePort.DEFAULT_CHOICE)
    val picture: StateFlow<PictureChoice> = state.asStateFlow()
    private var channelId: String? = null
    private var loadJob: Job? = null

    fun follow(channelId: String, apply: suspend (PictureChoice) -> Unit) {
        if (channelId == this.channelId) return
        this.channelId = channelId
        loadJob?.cancel()
        loadJob = scope.launch {
            val choice = try {
                port.initial(channelId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                LivePicturePort.DEFAULT_CHOICE
            }
            if (this@LivePictureFollower.channelId != channelId) return@launch
            state.value = choice
            apply(choice)
        }
    }

    fun change(update: (PictureChoice) -> PictureChoice, apply: suspend (PictureChoice) -> Unit) {
        val id = channelId ?: return
        val next = update(state.value)
        if (next == state.value) return
        state.value = next
        scope.launch {
            apply(next)
            try {
                port.save(id, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Picture memory is best effort; the picture on screen already changed.
            }
        }
    }
}

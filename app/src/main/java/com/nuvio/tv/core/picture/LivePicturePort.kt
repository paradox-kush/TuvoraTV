package com.nuvio.tv.core.picture

/**
 * F28: where the live player gets and keeps a channel's picture (aspect + manual zoom). Neutral, so
 * both the guide's playback owner and the clean live player can depend on it without touching the
 * legacy player or the data layer directly.
 */
interface LivePicturePort {
    suspend fun initial(channelId: String): PictureChoice
    suspend fun save(channelId: String, choice: PictureChoice)

    companion object {
        val DEFAULT_CHOICE = PictureChoice(AspectMode.ORIGINAL, VideoZoom.IDENTITY)

        /** No memory: every channel starts fitted with no zoom, nothing is kept (tests, previews). */
        val Unremembered: LivePicturePort = object : LivePicturePort {
            override suspend fun initial(channelId: String): PictureChoice = DEFAULT_CHOICE
            override suspend fun save(channelId: String, choice: PictureChoice) = Unit
        }
    }
}

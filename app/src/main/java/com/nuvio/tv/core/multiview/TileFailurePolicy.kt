package com.nuvio.tv.core.multiview

/** What went wrong on a tile, as the player layer reports it. No exception messages, hosts or URLs. */
internal sealed interface TileSignal {
    data class Http(val status: Int) : TileSignal

    /** A codec/decoder error code as a plain int (Media3 4001..4005, MediaCodec 1100/1101 and friends). */
    data class Codec(val code: Int) : TileSignal

    /** No progress for [noProgressMs] while playing or loading. */
    data class Stall(val noProgressMs: Long) : TileSignal

    /** The stream did not open within [afterMs]. */
    data class OpenTimeout(val afterMs: Long) : TileSignal

    /** DNS, connect or TLS failure. */
    data object Network : TileSignal

    data object UnsupportedFormat : TileSignal

    /** The player reported a problem with no cause that maps to the others, or a tile could not be set up. */
    data object Unknown : TileSignal

    /** Channel selection said the channel is not one of the profile's playlist channels (key unknown or gone). */
    data object ChannelUnavailable : TileSignal
}

/** What the viewer can do about a failed tile. */
internal enum class TileAction { RETRY, REPLACE, OPEN_IN_PLAYER, REMOVE }

/**
 * A typed tile failure. [shortTitle] is what fits on a small tile; [text] is the fact (kept exactly). [actions] is the
 * failed tile's menu in order, and its first entry is the [primary] action: what OK on the tile does.
 */
internal enum class TileFailure(val shortTitle: String, val text: String, val actions: List<TileAction>) {
    ProviderRefused(
        "The provider said no",
        "The provider refused this stream, maybe a connection limit.",
        listOf(TileAction.RETRY, TileAction.REPLACE, TileAction.OPEN_IN_PLAYER, TileAction.REMOVE),
    ),
    DecoderUnavailable(
        "Out of video decoders",
        "This device ran out of video decoders for this one.",
        listOf(TileAction.RETRY, TileAction.REPLACE, TileAction.OPEN_IN_PLAYER, TileAction.REMOVE),
    ),
    NoData(
        "No data",
        "The stream stopped sending data.",
        listOf(TileAction.RETRY, TileAction.REPLACE, TileAction.OPEN_IN_PLAYER, TileAction.REMOVE),
    ),
    /** Tile mode is Media3 only; the full player has one more engine to try, so it is this failure's main action. */
    Unsupported(
        "Can't play this format here",
        "This device can't play this stream's format.",
        listOf(TileAction.OPEN_IN_PLAYER, TileAction.REPLACE, TileAction.REMOVE),
    ),
    Network(
        "Can't reach the provider",
        "Couldn't reach the provider.",
        listOf(TileAction.RETRY, TileAction.REPLACE, TileAction.OPEN_IN_PLAYER, TileAction.REMOVE),
    ),

    /** The provider answered 404 or 410: it was reached and says the stream is not there. Retrying the same link won't help. */
    StreamGone(
        "Channel not found",
        "The provider says this stream doesn't exist.",
        listOf(TileAction.REPLACE, TileAction.REMOVE),
    ),

    /**
     * The channel key does not resolve to a channel the profile's playlists contain (channel selection said
     * UNAVAILABLE). A fact about the playlist, not the stream: the provider was never asked, so nothing here retries.
     */
    ChannelUnavailable(
        "Not in your playlist anymore",
        "This channel is no longer in the playlist.",
        listOf(TileAction.REPLACE, TileAction.REMOVE),
    ),

    /** Honest catch-all: the cause is not one of the above. Never presented as a decoder problem. */
    Other(
        "Couldn't open this one",
        "Something went wrong opening this stream.",
        listOf(TileAction.RETRY, TileAction.REPLACE, TileAction.OPEN_IN_PLAYER, TileAction.REMOVE),
    );

    /** What OK on the failed tile runs. */
    val primary: TileAction get() = actions.first()
}

internal object TileFailurePolicy {
    /** MediaCodec.ERROR_INSUFFICIENT_RESOURCE */
    const val CODEC_INSUFFICIENT_RESOURCE = 1100

    /** MediaCodec.ERROR_RECLAIMED */
    const val CODEC_RECLAIMED = 1101
    const val MEDIA3_DECODER_INIT_FAILED = 4001
    const val MEDIA3_DECODER_QUERY_FAILED = 4002
    const val MEDIA3_DECODING_FAILED = 4003
    const val MEDIA3_FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val MEDIA3_FORMAT_UNSUPPORTED = 4005

    val providerRefusedStatuses: Set<Int> = setOf(401, 403, 429, 456, 458, 509)

    /** Not found / gone. 5xx and every other status stay [TileFailure.Network]. */
    val streamGoneStatuses: Set<Int> = setOf(404, 410)

    fun classify(signal: TileSignal): TileFailure = when (signal) {
        is TileSignal.Http ->
            when (signal.status) {
                in providerRefusedStatuses -> TileFailure.ProviderRefused
                in streamGoneStatuses -> TileFailure.StreamGone
                else -> TileFailure.Network
            }
        is TileSignal.Codec ->
            if (signal.code == MEDIA3_FORMAT_UNSUPPORTED) TileFailure.Unsupported else TileFailure.DecoderUnavailable
        is TileSignal.Stall, is TileSignal.OpenTimeout -> TileFailure.NoData
        TileSignal.Network -> TileFailure.Network
        TileSignal.UnsupportedFormat -> TileFailure.Unsupported
        TileSignal.Unknown -> TileFailure.Other
        TileSignal.ChannelUnavailable -> TileFailure.ChannelUnavailable
    }

    /**
     * Whether the same link may be tried again without the viewer asking. A provider that said no is not asked again
     * on a timer (hammering a full account makes it worse), a stream that is gone stays gone, and a format this device
     * cannot play will not play on the second go either. Those go straight to a backup channel, then to Failed.
     */
    fun autoRetryAllowed(failure: TileFailure): Boolean = when (failure) {
        TileFailure.ProviderRefused, TileFailure.StreamGone, TileFailure.ChannelUnavailable, TileFailure.Unsupported -> false
        else -> true
    }

    /** A backup channel is only worth finding for a stream that failed; a channel missing from the playlist has none. */
    fun backupAllowed(failure: TileFailure): Boolean = failure != TileFailure.ChannelUnavailable

    /** Whether this failure is evidence for the "decoders this device can run at once" ledger. */
    fun countsTowardDecoderCeiling(failure: TileFailure): Boolean = failure == TileFailure.DecoderUnavailable
}

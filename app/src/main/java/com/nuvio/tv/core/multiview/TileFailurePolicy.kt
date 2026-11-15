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
}

internal enum class TileAction { RETRY, REMOVE }

internal enum class TileFailure(val text: String, val actions: List<TileAction>) {
    ProviderRefused("The provider refused this stream, maybe a connection limit.", listOf(TileAction.RETRY)),
    DecoderUnavailable("This device ran out of video decoders for this one.", listOf(TileAction.RETRY, TileAction.REMOVE)),
    NoData("The stream stopped sending data.", listOf(TileAction.RETRY)),
    Unsupported("This device can't play this stream's format.", listOf(TileAction.REMOVE)),
    Network("Couldn't reach the provider.", listOf(TileAction.RETRY)),
    /** Honest catch-all: the cause is not one of the above. Never presented as a decoder problem. */
    Other("Something went wrong opening this stream.", listOf(TileAction.RETRY, TileAction.REMOVE)),
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

    fun classify(signal: TileSignal): TileFailure = when (signal) {
        is TileSignal.Http ->
            if (signal.status in providerRefusedStatuses) TileFailure.ProviderRefused else TileFailure.Network
        is TileSignal.Codec ->
            if (signal.code == MEDIA3_FORMAT_UNSUPPORTED) TileFailure.Unsupported else TileFailure.DecoderUnavailable
        is TileSignal.Stall, is TileSignal.OpenTimeout -> TileFailure.NoData
        TileSignal.Network -> TileFailure.Network
        TileSignal.UnsupportedFormat -> TileFailure.Unsupported
        TileSignal.Unknown -> TileFailure.Other
    }

    /** A provider that said no is not asked again on a timer: hammering a full account makes it worse. */
    fun autoRetryAllowed(failure: TileFailure): Boolean = failure != TileFailure.ProviderRefused

    /** Whether this failure is evidence for the "decoders this device can run at once" ledger. */
    fun countsTowardDecoderCeiling(failure: TileFailure): Boolean = failure == TileFailure.DecoderUnavailable
}

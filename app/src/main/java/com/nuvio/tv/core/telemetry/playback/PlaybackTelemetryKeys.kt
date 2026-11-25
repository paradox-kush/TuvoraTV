package com.nuvio.tv.core.telemetry.playback

/** Closed vocabularies of the playback telemetry contract. Every value that leaves the device is one of these. */
enum class PlayKind(val wire: String) {
    LIVE("live"), VOD("vod"), PREVIEW("preview"), MULTIVIEW_TILE("multiview_tile");
}

enum class PlayEngine(val wire: String) {
    MEDIA3("media3"), LIBMPV("libmpv"), AVPLAYER("avplayer"), OTHER("other");

    companion object {
        /** Breadcrumb / engine labels (`media3`, `libmpv`, legacy `exoplayer`, `mpv`) to the enum; anything else is `other`. */
        fun fromLabel(label: String?): PlayEngine = when (label?.lowercase()) {
            "media3", "exoplayer", "exo" -> MEDIA3
            "libmpv", "mpv" -> LIBMPV
            "avplayer" -> AVPLAYER
            else -> OTHER
        }
    }
}

enum class FallbackTrigger(val wire: String) {
    MANIFEST_INVALID("manifest_invalid"),
    DEMUX_FAILED("demux_failed"),
    DECODER_FAILED("decoder_failed"),
    RENDERER_FAILED("renderer_failed"),
    NETWORK("network"),
    TIMEOUT("timeout"),
    WATCHDOG("watchdog"),
    USER("user"),
    OTHER("other");
}

/** `abandoned`: the viewer left (Back / zapped away) while the picture was still stalled. */
enum class StallOutcome(val wire: String) { RECOVERED("recovered"), GAVE_UP("gave_up"), ABANDONED("abandoned") }
enum class TeardownOutcome(val wire: String) { CLEAN("clean"), SLOW("slow"), FAILED("failed") }
enum class MultiviewEvent(val wire: String) { OPENED("opened"), CLOSED("closed"), TILE_FAILED("tile_failed") }
enum class MultiviewEndReason(val wire: String) {
    USER("user"), ERROR("error"), MEMORY("memory"), APP_BACKGROUND("app_background"), OTHER("other");
}

/** What the playback telemetry builds count keys from. Same strings on every platform. */
object PlaybackTelemetryKeys {
    const val MULTIVIEW_TILES_MIN = 1
    const val MULTIVIEW_TILES_MAX = 9

    fun ttff(kind: PlayKind, engine: PlayEngine, bucket: String) = "ttff:${kind.wire}:${engine.wire}:$bucket"
    fun fallback(from: PlayEngine, to: PlayEngine, trigger: FallbackTrigger) = "fallback:${from.wire}:${to.wire}:${trigger.wire}"
    fun stall(kind: PlayKind, outcome: StallOutcome) = "stall:${kind.wire}:${outcome.wire}"
    fun stallMs(kind: PlayKind, bucket: String) = "stall_ms:${kind.wire}:$bucket"
    fun teardown(engine: PlayEngine, outcome: TeardownOutcome) = "teardown:${engine.wire}:${outcome.wire}"
    fun multiview(event: MultiviewEvent) = "multiview:${event.wire}"
    fun multiviewTilesMax(tiles: Int) =
        "multiview_tiles_max:${tiles.coerceIn(MULTIVIEW_TILES_MIN, MULTIVIEW_TILES_MAX)}"

    /**
     * Maps a failure name to a fallback trigger. Accepts the clean pipeline's `FailureCode` names, Media3's
     * `ERROR_CODE_*` names and libmpv's `MPV_ERROR_*` names. Only the enum comes out; the input is never kept.
     */
    fun triggerForFailureName(name: String?): FallbackTrigger {
        val n = name?.uppercase() ?: return FallbackTrigger.OTHER
        return when {
            "WATCHDOG" in n -> FallbackTrigger.WATCHDOG
            "MANIFEST" in n -> FallbackTrigger.MANIFEST_INVALID
            "DEMUX" in n || "PARSING_CONTAINER" in n || "CONTAINER_MALFORMED" in n || "UNKNOWN_FORMAT" in n ->
                FallbackTrigger.DEMUX_FAILED
            "RENDERER" in n || "SURFACE" in n -> FallbackTrigger.RENDERER_FAILED
            "DECODER" in n || "DECODING" in n || "DECODE" in n -> FallbackTrigger.DECODER_FAILED
            "TIMEOUT" in n || "NO_PROGRESS" in n -> FallbackTrigger.TIMEOUT
            "NETWORK" in n || "ERROR_CODE_IO" in n || "HTTP" in n || "TLS" in n || "UNREACHABLE" in n ||
                "AUTHORIZATION" in n || "CONNECTION" in n || "NOT_FOUND" in n -> FallbackTrigger.NETWORK
            else -> FallbackTrigger.OTHER
        }
    }

    /** `ext_class` from a container / extension code (`m3u8`, `ts`, `mp4`, `mkv`; `unknown` or null means none). */
    fun extClass(containerCode: String?): String = when (containerCode?.lowercase()) {
        "m3u8", "hls" -> "m3u8"
        "ts" -> "ts"
        "mp4" -> "mp4"
        "mkv" -> "mkv"
        null, "", "unknown" -> "none"
        else -> "other"
    }

    /** `content_type_class` from a response Content-Type header value; null when there is none. */
    fun contentTypeClass(mime: String?): String? {
        val m = mime?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            "mpegurl" in m -> "hls"
            "mp2t" in m -> "mp2t"
            "octet-stream" in m -> "octet"
            "html" in m -> "html"
            m.startsWith("text/") -> "text"
            m.startsWith("video/") -> "video"
            else -> "other"
        }
    }

    /** The wire name of a clean-pipeline source type for `source_type` (`xtream`, `stalker`, `m3u`). */
    fun sourceType(name: String?): String? = when (name?.lowercase()) {
        "xtream" -> "xtream"
        "stalker" -> "stalker"
        "m3u" -> "m3u"
        "file" -> "file"
        "addon" -> "addon"
        "media_server", "mediaserver" -> "media_server"
        else -> null
    }
}

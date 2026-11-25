package com.nuvio.tv.core.telemetry.playback

/**
 * Bucket boundaries for the playback telemetry contract. Pure, so the same names and edges can be
 * mirrored in NuvioMobile / NuvioDesktop and tested without Android. Every upper bound is exclusive:
 * 250 ms lands in `lt500`, not `lt250`.
 */
object PlaybackTelemetryBuckets {
    /** A teardown (player release) slower than this is `slow`. */
    const val SLOW_TEARDOWN_MS = 1_500L

    /** Milliseconds from play request to the first rendered frame. */
    fun ttff(ms: Long): String = when {
        ms < 250 -> "lt250"
        ms < 500 -> "lt500"
        ms < 1_000 -> "lt1000"
        ms < 2_000 -> "lt2000"
        ms < 4_000 -> "lt4000"
        ms < 8_000 -> "lt8000"
        else -> "ge8000"
    }

    /** How long a mid-playback stall lasted. */
    fun stall(ms: Long): String = when {
        ms < 2_000 -> "lt2000"
        ms < 5_000 -> "lt5000"
        ms < 15_000 -> "lt15000"
        ms < 45_000 -> "lt45000"
        else -> "ge45000"
    }

    /** Multiview session length, in whole seconds. */
    fun durationSeconds(seconds: Long): String = when {
        seconds < 30 -> "lt30"
        seconds < 120 -> "lt120"
        seconds < 600 -> "lt600"
        else -> "ge600"
    }

    /**
     * Device RAM class from the total memory the system reports (`hw_ram_total_mb`). The system reports
     * a little under the marketing size (a 2 GB box shows ~1.9 GB), so `lt2` means "a 2 GB-class device".
     */
    fun ramClass(totalMb: Int): String = when {
        totalMb < 1_229 -> "lt1_2"
        totalMb < 2_048 -> "lt2"
        totalMb < 3_072 -> "lt3"
        else -> "ge3"
    }

    /** The response status of a failed request as a class; `none` when there was no HTTP answer. */
    fun httpClass(status: Int?): String = when (status) {
        null -> "none"
        in 200..299 -> "2xx"
        in 300..399 -> "3xx"
        401, 403 -> "401_403"
        404 -> "404"
        456 -> "456"
        in 400..499 -> "4xx_other"
        in 500..599 -> "5xx"
        else -> "none"
    }

    fun teardownOutcome(durationMs: Long, failed: Boolean): TeardownOutcome = when {
        failed -> TeardownOutcome.FAILED
        durationMs > SLOW_TEARDOWN_MS -> TeardownOutcome.SLOW
        else -> TeardownOutcome.CLEAN
    }

    /** Video height (shorter side counts for portrait-ish encodes) as a resolution class. */
    fun resClass(width: Int?, height: Int?): String {
        if (width == null || height == null || width <= 0 || height <= 0) return "unknown"
        val lines = minOf(width, height)
        return when {
            lines < 700 -> "sd"
            lines < 1_000 -> "hd720"
            lines <= 1_200 -> "fhd"
            else -> "uhd"
        }
    }
}

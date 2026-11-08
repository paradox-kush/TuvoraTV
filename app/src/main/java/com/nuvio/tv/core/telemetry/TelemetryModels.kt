package com.nuvio.tv.core.telemetry

/** Where in the app something happened. Codes are enums/identifiers, never free text. */
enum class DiagArea(val wire: String) {
    PLAYBACK("playback"),
    IPTV_LOAD("iptv_load"),
    EPG("epg"),
    SCREEN("screen"),
    MEMORY("memory"),
    LIFECYCLE("lifecycle"),
    SYNC("sync"),
    SOURCES("sources"),
    NETWORK("network"),
    STORAGE("storage"),
    UI("ui"),
}

/** One step in the on-device flight recorder. Fields pass [DiagTrailPolicy] before they are stored. */
data class DiagEntry(
    val atMs: Long,
    val area: DiagArea,
    val code: String,
    val fields: Map<String, Any> = emptyMap(),
)

enum class ReportCategory(val wire: String) {
    WONT_PLAY("wont_play"),
    BLACK_SCREEN("black_screen"),
    FREEZES("freezes"),
    APP_CLOSED("app_closed"),
    OTHER("other"),
}

enum class ReportSurface(val wire: String) {
    LIVE("live"),
    VOD("vod"),
    SETTINGS("settings"),
}

sealed interface ReportResult {
    data class Sent(val supportCode: String) : ReportResult
    data object ConsentOff : ReportResult
    data class RateLimited(val retryInSec: Int) : ReportResult
    data object Failed : ReportResult
}

/** What feature code and screens depend on. Implemented by [TelemetryService]. */
interface TelemetryPort {
    /** `K7Q29MPX`; show with [SupportCodeGenerator.display]. */
    val supportCode: String

    /** Adds a step to the on-device log. Never sent by itself. */
    fun record(entry: DiagEntry)

    /**
     * One-press report. [failureCode] is the error already on screen, if any; it names the problem
     * so the user is not asked to.
     */
    suspend fun sendUserReport(
        category: ReportCategory,
        surface: ReportSurface,
        failureCode: String? = null,
    ): ReportResult
}

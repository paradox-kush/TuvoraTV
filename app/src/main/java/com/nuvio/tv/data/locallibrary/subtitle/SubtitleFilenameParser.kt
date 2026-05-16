package com.nuvio.tv.data.locallibrary.subtitle

import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import java.util.Locale

/**
 * Pulls language / forced / SDH metadata out of sidecar subtitle filenames such as:
 *  - `Inception.srt`              → language null
 *  - `Inception.en.srt`           → language "en"
 *  - `Inception.eng.srt`          → language "en"
 *  - `Inception.English.srt`      → language "en"
 *  - `Inception.pt-BR.srt`        → language "pt-br"
 *  - `Inception.en.forced.srt`    → language "en", forced
 *  - `Inception.English.SDH.srt`  → language "en", sdh
 */
object SubtitleFilenameParser {
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    private val FORCED_FLAGS = setOf("forced", "foreign")
    private val SDH_FLAGS = setOf("sdh", "cc", "hi", "hearingimpaired", "hearing-impaired")

    data class ParsedInfo(
        val language: String?,
        val isForced: Boolean,
        val isSdh: Boolean,
        val displayName: String
    )

    fun isSubtitleFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return ext.isNotEmpty() && ext in SUBTITLE_EXTENSIONS
    }

    /** True when [subtitleFileName] looks like a sidecar for a video file named [videoBaseName].* */
    fun matchesVideo(subtitleFileName: String, videoBaseName: String): Boolean {
        if (!isSubtitleFile(subtitleFileName)) return false
        if (videoBaseName.isBlank()) return false
        val subBase = subtitleFileName.substringBeforeLast('.')
        if (subBase.equals(videoBaseName, ignoreCase = true)) return true
        if (subBase.length <= videoBaseName.length) return false
        if (!subBase.regionMatches(0, videoBaseName, 0, videoBaseName.length, ignoreCase = true)) return false
        val sep = subBase[videoBaseName.length]
        return sep == '.' || sep == '_' || sep == '-' || sep == ' '
    }

    fun parse(subtitleFileName: String, videoBaseName: String): ParsedInfo {
        val subBase = subtitleFileName.substringBeforeLast('.')
        val tail = when {
            subBase.equals(videoBaseName, ignoreCase = true) -> ""
            subBase.length > videoBaseName.length &&
                subBase.regionMatches(0, videoBaseName, 0, videoBaseName.length, ignoreCase = true) ->
                subBase.substring(videoBaseName.length + 1)
            else -> ""
        }

        val tokens = tail.split('.', '_', '-', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }

        var isForced = false
        var isSdh = false
        val remaining = mutableListOf<String>()
        for (token in tokens) {
            val low = token.lowercase(Locale.ROOT)
            when (low) {
                in FORCED_FLAGS -> isForced = true
                in SDH_FLAGS -> isSdh = true
                else -> remaining += token
            }
        }

        val language = detectLanguage(remaining)
        val displayName = buildDisplayName(language, remaining, subtitleFileName, isForced, isSdh)
        return ParsedInfo(language = language, isForced = isForced, isSdh = isSdh, displayName = displayName)
    }

    fun mimeTypeFor(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt", "webvtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    /**
     * Try the trailing tokens first (most common: `Movie.2023.1080p.WEB-DL.en.srt`)
     * and walk backward. Also try joined two-token combos for locale-like codes that
     * may have been split (e.g. ["pt", "BR"] → "pt-BR").
     */
    private fun detectLanguage(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null

        for (i in tokens.indices.reversed()) {
            val single = tokens[i]
            PlayerSubtitleUtils.normalizeLanguage(single)?.let { return it.tag }
            if (i > 0) {
                val pair = "${tokens[i - 1]}-${tokens[i]}"
                PlayerSubtitleUtils.normalizeLanguage(pair)?.let { return it.tag }
            }
        }

        val joined = tokens.joinToString(" ")
        return PlayerSubtitleUtils.normalizeLanguage(joined)?.tag
    }

    private fun buildDisplayName(
        language: String?,
        remainingTokens: List<String>,
        fileName: String,
        isForced: Boolean,
        isSdh: Boolean
    ): String {
        val base = when {
            language != null -> displayNameFromLanguage(language)
            remainingTokens.isNotEmpty() -> remainingTokens.joinToString(" ")
            else -> fileName
        }
        val flags = buildList {
            if (isForced) add("Forced")
            if (isSdh) add("SDH")
        }
        return if (flags.isEmpty()) base else "$base (${flags.joinToString(", ")})"
    }

    private fun displayNameFromLanguage(tag: String): String {
        val base = tag.substringBefore('-')
        val region = tag.substringAfter('-', missingDelimiterValue = "")
        val locale = if (region.isBlank()) Locale(base) else Locale(base, region.uppercase(Locale.ROOT))
        val name = locale.getDisplayLanguage(Locale.ENGLISH)
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        if (name.isBlank() || name.equals(base, ignoreCase = true)) return tag
        return if (region.isBlank()) name else "$name (${region.uppercase(Locale.ROOT)})"
    }
}

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import com.nuvio.tv.ui.util.LANGUAGE_OVERRIDES
import java.text.Normalizer
import java.util.Locale

internal object PlayerSubtitleUtils {
    /** Result of [normalizeLanguage]: the normalized BCP-47-ish language [tag]. */
    data class NormalizedLanguage(val tag: String)

    /**
     * Resolves [language] to a normalized language tag (e.g. "en", "pt-br",
     * "es-419"), or null when the input is not recognizable as a language.
     *
     * Unlike [normalizeLanguageCode] — which always echoes unknown input back —
     * this returns null for non-language tokens, so callers can probe arbitrary
     * filename tokens or metadata fields (release tags like "1080p", "x264",
     * "WEBDL") and only act on genuine languages.
     */
    fun normalizeLanguage(language: String): NormalizedLanguage? {
        val raw = language.trim()
        if (raw.isBlank()) return null

        val code = raw.lowercase(Locale.ROOT).replace('_', '-')
        val override = LANGUAGE_OVERRIDES[code]?.lowercase(Locale.ROOT)
        val canonicalCode = if (code == "pt-pt") code else override ?: code
        val compact = canonicalCode.replace(Regex("[^a-z0-9]+"), "")
        val text = searchableLanguageText(raw)

        fun result(tag: String) = NormalizedLanguage(tag)

        when (canonicalCode) {
            "pt-br" -> return result("pt-br")
            "pt-pt" -> return result("pt-pt")
            "pt", "por" -> return result("pt")
            "pob" -> return result("pt-br")
            "es-419" -> return result("es-419")
            "es-la", "es-lat", "es-mx" -> return result("es-419")
            "es-es" -> return result("es-es")
            "es", "spa" -> return result("es")
            "zh-hans", "zh-cn", "zh-sg" -> return result("zh-hans")
            "zh-hant", "zh-tw", "zh-hk", "zh-mo" -> return result("zh-hant")
            "zh-yue", "yue" -> return result("zh-yue")
            "cmn" -> return result("zh-cmn")
            "zh", "chi", "zho" -> return result("zh")
            "fr-ca" -> return result("fr-ca")
            "fr-fr" -> return result("fr-fr")
            "fr", "fre", "fra" -> return result("fr")
            "nb", "nb-no" -> return result("nb")
            "nn", "nn-no" -> return result("nn")
            "no", "nor" -> return result("no")
            "sr-cyrl" -> return result("sr-cyrl")
            "sr-latn" -> return result("sr-latn")
            "sr", "srp" -> return result("sr")
            "de", "de-de" -> return result("de")
            "de-at" -> return result("de-at")
            "de-ch" -> return result("de-ch")
            "nl-be" -> return result("nl-be")
            "nl-nl" -> return result("nl-nl")
            "nl", "dut", "nld" -> return result("nl")
            "en", "eng" -> return result("en")
            "ar", "ara" -> return result("ar")
            "fa", "fas", "per" -> return result("fa")
            "he", "iw" -> return result("he")
            "id", "in" -> return result("id")
            "ms", "msa", "may" -> return result("ms")
            "jv", "jw" -> return result("jv")
            "fil", "tl" -> return result("fil")
            "el", "gr" -> return result("el")
            "ro", "mo" -> return result("ro")
        }

        if (compact in setOf("ptbr", "pob") ||
            text.containsAny(
                "portuguese brazil",
                "portuguese br",
                "portuguese (br)",
                "portugues brasil",
                "portugues br",
                "brazilian portuguese",
                "portugues brasileiro"
            )
        ) return result("pt-br")
        if (compact == "ptpt" ||
            text.containsAny("portuguese portugal", "portugues portugal", "european portuguese", "portugues europeu")
        ) return result("pt-pt")
        if (text.containsAny("portuguese", "portugues")) return result("pt")

        if (compact in setOf("es419", "esla", "eslat", "esmx") ||
            text.containsAny("latin american spanish", "spanish latin america", "spanish latino", "espanol latino", "latinoamerica")
        ) return result("es-419")
        if (text.containsAny("spanish spain", "castilian", "castellano", "espanol espana")) {
            return result("es-es")
        }
        if (text.containsAny("spanish", "espanol")) return result("es")

        if (text.containsAny("chinese simplified", "mandarin simplified", "simplified", "简体")) {
            return result("zh-hans")
        }
        if (text.containsAny("chinese traditional", "traditional", "繁體", "繁体")) {
            return result("zh-hant")
        }
        if (text.containsAny("cantonese", "yue", "粵語", "广东话", "廣東話")) return result("zh-yue")
        if (text.containsAny("mandarin", "cmn", "putonghua", "普通话", "國語")) return result("zh-cmn")
        if (text.contains("chinese")) return result("zh")

        if (text.containsAny("french canadian", "canadian french", "francais canada", "quebecois", "quebec french")) {
            return result("fr-ca")
        }
        if (text.containsAny("french france", "francais france")) return result("fr-fr")
        if (text.containsAny("french", "francais")) return result("fr")

        if (text.containsAny("nynorsk", "norwegian nynorsk", "norsk nynorsk")) return result("nn")
        if (text.containsAny("bokmal", "bokmål", "norwegian bokmal", "norwegian bokmål", "norsk bokmal", "norsk bokmål")) {
            return result("nb")
        }
        if (text.containsAny("norwegian", "norsk")) return result("no")

        if (text.containsAny("serbian cyrillic", "српски", "cyrillic")) return result("sr-cyrl")
        if (text.containsAny("serbian latin", "srpski latin")) return result("sr-latn")
        if (text.contains("serbian")) return result("sr")

        if (text.containsAny("flemish", "vlaams", "belgian dutch")) return result("nl-be")
        if (text.containsAny("dutch netherlands", "nederlands")) return result("nl-nl")
        if (text.contains("dutch")) return result("nl")

        if (text.containsAny("persian", "farsi", "فارسی")) return result("fa")
        if (text.containsAny("hebrew", "עברית")) return result("he")
        if (text.containsAny("indonesian", "bahasa indonesia")) return result("id")
        if (text.containsAny("malay", "bahasa melayu")) return result("ms")
        if (text.contains("javanese")) return result("jv")
        if (text.containsAny("filipino", "pilipino", "tagalog")) return result("fil")
        if (text.containsAny("greek", "ελληνικά")) return result("el")
        if (text.contains("romanian")) return result("ro")
        if (text.contains("german")) return result("de")
        if (text.contains("english")) return result("en")
        if (text.contains("arabic")) return result("ar")

        // Unknown: only treat as a language when it looks like an ISO-639 code
        // (2-3 letter base, optional region/script subtags). Anything else
        // (release tags, junk tokens) returns null.
        val parts = canonicalCode.split('-').filter { it.isNotBlank() }
        val base = parts.firstOrNull() ?: return null
        if (base.length !in 2..3) return null
        return result(canonicalCode)
    }

    private fun searchableLanguageText(value: String): String {
        val lower = value.lowercase(Locale.ROOT)
        val ascii = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return "$lower $ascii"
            .replace('-', ' ')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.containsAny(vararg values: String): Boolean {
        return values.any { value -> contains(value) }
    }

    fun normalizeLanguageCode(lang: String): String {
        val code = lang.trim().lowercase()
        if (code.isBlank()) return ""

        val normalizedCode = code.replace('_', '-')
        val tokenized = normalizedCode
            .replace('-', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        fun containsAny(vararg values: String): Boolean = values.any { value ->
            tokenized.contains(value)
        }

        if (containsAny("portuguese", "portugues")) {
            if (containsAny("brazil", "brasil", "brazilian", "brasileiro", "pt br", "ptbr", "pob", "(br)")) {
                return "pt-br"
            }
            if (containsAny("portugal", "european", "europeu", "iberian", "pt pt", "ptpt")) {
                return "pt"
            }
            return "pt"
        }

        if (containsAny("spanish", "espanol", "español", "castellano")) {
            if (containsAny("latin", "latino", "latinoamerica", "latinoamericano", "lat am", "latam", "es 419", "es419", "la", "(419)")) {
                return "es-419"
            }
            return "es"
        }

        // LANGUAGE_OVERRIDES uses pt-BR (mixed case) — normalize to lowercase for consistency
        return LANGUAGE_OVERRIDES[code]?.lowercase() ?: normalizedCode
    }

    fun matchesLanguageCode(language: String?, target: String): Boolean {
        if (language.isNullOrBlank()) return false
        val normalizedLanguage = normalizeLanguageCode(language)
        val normalizedTarget = normalizeLanguageCode(target)
        if (matchesNormalizedLanguage(normalizedLanguage, normalizedTarget)) {
            return true
        }

        val subtags = language.trim().lowercase()
            .replace('_', '-')
            .split('-', '.', '/', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (subtags.size <= 1) {
            return false
        }
        for (subtag in subtags.drop(1)) {
            if (subtag.length != 3) continue
            val normalizedSubtag = normalizeLanguageCode(subtag)
            if (matchesNormalizedLanguage(normalizedSubtag, normalizedTarget)) {
                return true
            }
        }
        return false
    }

    private fun matchesNormalizedLanguage(
        normalizedLanguage: String,
        normalizedTarget: String
    ): Boolean {
        // Exact regional targets: "pt" should not match "pt-br", "es" should not match "es-419"
        if (normalizedTarget == "pt") {
            return normalizedLanguage == "pt"
        }
        if (normalizedTarget == "es") {
            return normalizedLanguage == "es"
        }
        return normalizedLanguage == normalizedTarget ||
            normalizedLanguage.startsWith("$normalizedTarget-") ||
            normalizedLanguage.startsWith("${normalizedTarget}_")
    }

    /**
     * Detects the regional variant of an embedded subtitle track by inspecting
     * its name, language, and trackId fields. Returns a normalized language key
     * that preserves the accent (e.g. "pt-br", "es-419") when detectable,
     * or falls back to the base language code.
     */
    fun detectTrackLanguageVariant(language: String?, name: String?, trackId: String?): String {
        val baseLang = normalizeLanguageCode(language ?: "")
        val haystack = listOfNotNull(name, language, trackId)
            .joinToString(" ")
            .lowercase()

        // Portuguese: detect Brazilian vs European from tags
        if (baseLang == "pt" || baseLang == "por") {
            val hasBrazilian = BRAZILIAN_TAGS.any { haystack.contains(it) }
            val hasEuropean = EUROPEAN_PT_TAGS.any { haystack.contains(it) }
            if (hasBrazilian && !hasEuropean) return "pt-br"
            if (hasEuropean && !hasBrazilian) return "pt"
            return baseLang
        }

        // Spanish: detect Latin American from tags
        if (baseLang == "es" || baseLang == "spa") {
            val hasLatino = LATINO_TAGS.any { haystack.contains(it) }
            val hasCastilian = CASTILIAN_TAGS.any { haystack.contains(it) }
            if (hasLatino && !hasCastilian) return "es-419"
            if (hasCastilian && !hasLatino) return "es"
            return baseLang
        }

        return baseLang
    }

    internal val BRAZILIAN_TAGS = listOf(
        "pt-br", "pt_br", "pob", "brazilian", "brazil", "brasil", "brasileiro", " br", "(br)"
    )
    internal val EUROPEAN_PT_TAGS = listOf(
        "pt-pt", "pt_pt", "iberian", "european", "portugal", "europeu", " eu", "(eu)"
    )
    internal val LATINO_TAGS = listOf(
        "es-419", "es_419", "es-la", "es-lat", "latino", "latinoamerica",
        "latinoamericano", "latam", "lat am", "latin america"
    )
    internal val CASTILIAN_TAGS = listOf(
        "es-es", "es_es", "castilian", "castellano", "spain", "españa", "espana", "iberian"
    )

    fun mimeTypeFromUrl(url: String): String {
        val normalizedPath = url
            .substringBefore('#')
            .substringBefore('?')
            .trimEnd('/')
            .lowercase()

        return when {
            normalizedPath.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            normalizedPath.endsWith(".vtt") || normalizedPath.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            normalizedPath.endsWith(".ass") || normalizedPath.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            normalizedPath.endsWith(".ttml") || normalizedPath.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }
}

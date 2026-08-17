package com.nuvio.tv.core.iptv.match

/**
 * Normalizes provider VOD/series names and TMDB titles into exact-match lookup keys.
 *
 * Port of the JS prototype validated against 3 live panels over 8 test rounds
 * (~3,700 ground-truth checks, 100% recall on everything algorithmically reachable).
 * Every rule below exists because a real panel name needed it — see the edge-case
 * catalogue in the round notes before "simplifying" any of them:
 *   "Spirited Away - 2001 | MultiAudio | TS"   pipe-tag suffixes (p1)
 *   "4K-TOP - 96.Ikiru.1952"                   rank/scene dotted names (p2)
 *   "P+ -CSI: Crime Scene Investigation (US)"  tag strip must keep EVERY stage
 *   "IR - Backrooms (2026) اتاق های پشتی"      appended native-script translation
 *   "محمد سعد : بوشكاش"                        actor-colon prefix
 *   "[REC] (2007)"                              title lives inside brackets
 *   "MTV Splitsvilla X6 (2026)"                 season tokens
 *   "Heart Beat _ Tamil (2024)"                 trailing language word
 *   "راجل و 6 ستات" / "٢"                       arabic-indic digits
 *   "Kisi Ke Pyaar Mein" ~ "Kisikey Pyaar Meiin" romanization skeleton
 */
object TitleNormalizer {

    // quality/rip tags that never carry title meaning (kept minimal on purpose: words like
    // "cam"/"ts"/"web" ARE movie titles and live inside parens anyway, which get stripped)
    private val TAG_WORDS = Regex(
        "\\b(4k|uhd|fhd|hd|sd|hevc|x265|h265|hdr|dv|multi|multisub|multiaudio|dualaudio|dual|vostfr|dubbed|remastered|extended|unrated|imax)\\b"
    )
    private val LEADING_TAG = Regex("^[A-Z0-9][A-Z0-9.+\\-]{0,11}\\s*[|–\\-:]\\s*")
    private val GROUP = Regex("\\[[^\\]]*]|\\([^)]*\\)")
    private val TRAILING_DASH_YEAR = Regex("\\s[|–\\-]\\s*(19|20)\\d{2}\\s*$")
    private val PIPE_CUT = Regex("\\s\\|\\s")
    private val PAREN_YEAR = Regex("\\(((19|20)\\d{2})\\)")
    private val TRAILING_YEAR_TOKEN = Regex("^(.*)\\s((19|20)\\d{2})$")
    private val DOTTED_RANK = Regex("^\\d{1,3}\\.")
    private val DOTTED_YEAR = Regex("\\.(19|20)\\d{2}\\s*$")
    private val SEASON_TOKENS = Regex("\\b(s|x|season)\\s*\\d{1,2}\\b")
    private val TRAILING_SEASON_WORD = Regex("\\s+(act|season|series)$")
    private val STANDALONE_DIGITS = Regex("\\b\\d{1,2}\\b")
    private val TRAILING_LANG_WORDS = Regex(
        "(\\s(hindi|tamil|telugu|malayalam|kannada|bengali|marathi|gujarati|punjabi|urdu|english|french|german|spanish|italian|arabic|turkish|korean|japanese|chinese|russian|portuguese|polish|dutch|greek|persian|farsi|dub|dubbed|sub|subbed))+$"
    )
    private val ARABIC_MEDIA_PREFIX = Regex("^(فيلم|مسلسل|وثائقي)\\s+")
    private val SPACES = Regex("\\s+")
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    private fun isAscii(s: String): Boolean {
        for (c in s) if (c.code >= 0x80) return false
        return true
    }

    /**
     * NFD decomposition of a pure-ASCII string is the identity, and `\p{Mn}` cannot match an ASCII
     * code point — so for the common case this skips a Unicode normalisation AND a regex pass.
     * [fold] runs 4-8 times per catalog item, i.e. millions of times on a large build.
     */
    fun fold(s: String): String =
        if (isAscii(s)) s.lowercase()
        else COMBINING_MARKS.replace(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD), "").lowercase()

    // ---- extracted rules ------------------------------------------------------------------
    // Pulled out of keysOf so each can be optimised and proven equivalent independently.
    // TitleNormalizerEquivalenceTest holds the ORIGINAL regexes as the reference and asserts these
    // agree with them over a corpus of real panel names — do not change one without running it.

    /** Every word the original `(\s(alt|alt|...))+$` alternation could strip. */
    private val LANG_WORDS = hashSetOf(
        "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi", "gujarati",
        "punjabi", "urdu", "english", "french", "german", "spanish", "italian", "arabic",
        "turkish", "korean", "japanese", "chinese", "russian", "portuguese", "polish", "dutch",
        "greek", "persian", "farsi", "dub", "dubbed", "sub", "subbed",
    )

    /**
     * "heart beat tamil" -> "heart beat"; repeated suffixes strip together, as the original `+`
     * quantifier did.
     *
     * Replaces a `(\s(27 alternatives))+$` regex — a quantified alternation, the shape most prone
     * to backtracking — that ran on EVERY key of every item. Walking trailing tokens against a hash
     * set is the same rule without the engine. The leading `\s` requirement is preserved: a string
     * that is nothing but a language word has no preceding space and so survives.
     */
    internal fun stripTrailingLanguageWords(k: String): String {
        var end = k.length
        while (true) {
            val space = k.lastIndexOf(' ', end - 1)
            if (space < 0) break
            if (k.substring(space + 1, end) !in LANG_WORDS) break
            end = space
        }
        return k.substring(0, end).trim()
    }

    /** The pattern requires `\d{1,2}`, so a key with no digit cannot match — skip the engine. */
    internal fun stripSeasonTokens(k: String): String =
        if (k.none { it in '0'..'9' }) k else SEASON_TOKENS.replace(k, " ")

    /** Every alternative is Arabic script, so a pure-ASCII key cannot match — skip the engine. */
    internal fun stripArabicMediaPrefix(k: String): String =
        if (isAscii(k)) k else ARABIC_MEDIA_PREFIX.replaceFirst(k, "")

    /** ٢/۲ -> 2 : both Arabic-Indic ranges end in nibble 0-9, so code%16 is the digit. */
    private fun asciiDigits(s: String): String {
        if (s.none { it in '٠'..'٩' || it in '۰'..'۹' }) return s
        return buildString(s.length) {
            for (c in s) append(if (c in '٠'..'٩' || c in '۰'..'۹') ('0' + (c.code % 16)) else c)
        }
    }

    /**
     * The lookup key: folded, junk-free, letters+digits separated by single spaces.
     * [keepGroups] treats brackets as separators instead of junk — for titles that
     * LIVE inside brackets ("[REC]", "(500) Days of Summer").
     */
    fun normKey(s: String, keepGroups: Boolean = false): String {
        var t = asciiDigits(fold(s))
        t = if (keepGroups) t.replace('[', ' ').replace(']', ' ').replace('(', ' ').replace(')', ' ')
        else GROUP.replace(t, " ")
        t = TRAILING_DASH_YEAR.replace(t, " ")
        t = t.replace("'", "").replace("’", "").replace("ʼ", "")
        t = t.replace("&", " and ")
        t = TAG_WORDS.replace(t, " ")
        // collapse everything that isn't a letter/digit — Char-based, not regex, so it
        // behaves identically on JVM and Kotlin/Native regardless of \p{L} support
        val sb = StringBuilder(t.length)
        var lastSpace = true
        for (c in t) {
            if (c.isLetterOrDigit()) { sb.append(c); lastSpace = false }
            else if (!lastSpace) { sb.append(' '); lastSpace = true }
        }
        return sb.toString().trim()
    }

    /** Year from "(2008)" anywhere, else trailing " - 2008" (also before " | tags"). */
    fun yearOf(name: String): Int? {
        PAREN_YEAR.find(name)?.let { return it.groupValues[1].toInt() }
        val cut = name.split(PIPE_CUT).first()
        return TRAILING_DASH_YEAR.find(cut)?.value?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toInt() }
    }

    /** "P+ - CSI: Crime..." -> every strip stage, so an over-eager strip still leaves the right form. */
    private fun tagStripStages(s: String): List<String> {
        val stages = mutableListOf(s.trim())
        while (true) {
            val cur = stages.last()
            val next = LEADING_TAG.replaceFirst(cur, "")
            if (next == cur || next.length <= 2) return stages
            stages.add(next)
        }
    }

    private fun hasLetter(s: String) = s.any { it.isLetter() }
    private fun isAsciiLetter(c: Char) = c in 'a'..'z'
    private fun hasNonLatinLetter(s: String) = s.any { it.isLetter() && !isAsciiLetter(it) }

    /** Every normalized key a catalog item should be findable under (~3-6 per item). */
    fun keysOf(rawName: String): Set<String> {
        if (rawName.isBlank()) return emptySet()
        val forms = LinkedHashSet<String>(tagStripStages(rawName))
        for (f in forms.toList()) {
            val cut = f.split(PIPE_CUT).first()          // "X - 2001 | MultiAudio | TS" -> "X - 2001"
            forms.addAll(tagStripStages(cut))
        }
        for (f in forms.toList()) {                       // scene-style "96.Ikiru.1952"
            if (f.count { it == '.' } >= 2) {
                forms.add(DOTTED_YEAR.replace(DOTTED_RANK.replaceFirst(f, ""), ""))
            }
        }
        for (f in forms.toList()) {                       // "محمد سعد : بوشكاش" (actor : title)
            val i = f.lastIndexOf(':')
            if (i > 0 && i < f.length - 2) forms.add(f.substring(i + 1).trim())
        }

        val keys = LinkedHashSet<String>()
        for (f in forms) {
            val k = normKey(f)
            if (k.isNotEmpty()) keys.add(k)
            // title lives inside brackets ("[REC] (2007)") -> plain key has no letters
            if (!hasLetter(k) && f.any { it == '[' || it == '(' }) {
                val kg = normKey(f, keepGroups = true)
                if (hasLetter(kg)) keys.add(kg)
            }
        }
        // arabic panels prepend the media type: "فيلم حريم كريم" = "film Kareem's Women"
        for (k in keys.toList()) {
            val v = stripArabicMediaPrefix(k)
            if (v != k && hasLetter(v)) keys.add(v)
        }
        // mixed-script concatenations: "the legend of hei 2 افسانه هی 2" -> latin + native halves,
        // positionally split so digits stay with their own half; both halves must carry letters
        for (k in keys.toList()) {
            val toks = k.split(' ')
            val cls = toks.map { t ->
                when {
                    t.any(::isAsciiLetter) -> 'L'
                    t.any { it.isLetter() } -> 'N'
                    else -> 'D'
                }
            }
            val fL = cls.indexOf('L'); val fN = cls.indexOf('N')
            if (fL == -1 || fN == -1) continue
            val (a, b) = if (fL < fN) toks.subList(fL, fN) to toks.subList(fN, toks.size)
            else toks.subList(fL, toks.size) to toks.subList(fN, fL)
            val latin = a.joinToString(" ").trim(); val other = b.joinToString(" ").trim()
            if (hasLetter(latin) && hasLetter(other)) { keys.add(latin); keys.add(other) }
        }
        // "pinoquio 2026" (year glued by dot/space, no separator)
        for (k in keys.toList()) {
            val m = TRAILING_YEAR_TOKEN.find(k) ?: continue
            val head = m.groupValues[1].trim()
            if (hasLetter(head)) keys.add(head)
        }
        // season tokens: "laughter chefs s2 s3", "mtv splitsvilla x6", trailing "act"
        for (k in keys.toList()) {
            val v = SPACES.replace(TRAILING_SEASON_WORD.replace(stripSeasonTokens(k), ""), " ").trim()
            if (v != k && hasLetter(v)) keys.add(v)
        }
        // non-Latin names often drop sequel numbers ("...والثعبان ٢..." vs "...والثعبان...")
        for (k in keys.toList()) {
            if (!hasNonLatinLetter(k)) continue
            val v = SPACES.replace(STANDALONE_DIGITS.replace(k, " "), " ").trim()
            if (v != k && hasLetter(v)) keys.add(v)
        }
        // trailing language words outside parens: "heart beat tamil"
        for (k in keys.toList()) {
            val v = stripTrailingLanguageWords(k)
            if (v != k && hasLetter(v)) keys.add(v)
        }
        for (k in keys.toList()) skeletonKey(k)?.let { keys.add(it) }
        return keys
    }

    /**
     * Romanization skeleton: spaces+vowels+y removed, pure-ascii keys only, prefixed so it
     * can never collide with a real key. "kisikey pyaar meiin"/"kisi ke pyaar mein" -> "sk:kskprmn".
     */
    fun skeletonKey(k: String): String? {
        if (k.isEmpty() || !k.all { it in 'a'..'z' || it in '0'..'9' || it == ' ' }) return null
        val sk = buildString(k.length) {
            for (c in k) if (c !in "aeiouy ") append(c)
        }
        return if (sk.length >= 5) "sk:$sk" else null
    }

    /**
     * Ordered lookup probes for a TMDB title bundle. Probe keys are O(1) index gets; the
     * resolver walks them in order and verifies candidates. [ProbeKey.exactTier] = false
     * marks inexact transforms (truncation, skeleton, digit-drop) that must NEVER be
     * accepted without a tmdb-id or year confirmation.
     */
    fun probesFor(titles: List<TitleVariant>): List<ProbeKey> {
        val seen = HashSet<String>()
        val out = ArrayList<ProbeKey>()
        fun add(key: String, via: String, exact: Boolean) {
            if (key.isNotEmpty() && seen.add(key)) out.add(ProbeKey(key, via, exact))
        }
        for ((title, via) in titles) {
            add(normKey(title), via, exact = true)
            if (title.any { it == '[' || it == '(' }) add(normKey(title, keepGroups = true), "$via+brackets", exact = true)
            if (':' in title) add(normKey(title.substringBefore(':')), "$via+colon", exact = true)
            if (hasNonLatinLetter(fold(title))) {
                val nd = SPACES.replace(STANDALONE_DIGITS.replace(normKey(title), " "), " ").trim()
                add(nd, "$via+nodigit", exact = false)
            }
        }
        // last-resort tiers on primary/original only
        for ((title, via) in titles.take(2)) {
            val toks = normKey(title).split(' ')
            for (cut in toks.size - 1 downTo 2) {
                add(toks.subList(0, cut).joinToString(" "), "$via+trunc", exact = false)
            }
        }
        for ((title, via) in titles.take(2)) {
            skeletonKey(normKey(title))?.let { add(it, "$via+skeleton", exact = false) }
        }
        return out
    }
}

data class TitleVariant(val title: String, val via: String)

data class ProbeKey(val key: String, val via: String, val exactTier: Boolean)

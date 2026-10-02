package com.nuvio.tv.core.iptv

/**
 * Step 2 — a provider's setup code (`TUV-XXXX-XXXX-XXXX`). Mirror of nuvio-web `src/lib/providers/code.ts`
 * (the golden vectors in `SetupCodeTest` are the contract's, shared verbatim with every platform).
 *
 * The server stores only sha256 of the NORMALIZED code, so every client must normalize identically:
 * uppercase, drop whitespace and dashes, drop the `TUV` prefix only when what is left is exactly a code,
 * and refuse any character outside [ALPHABET] BEFORE a request is made (a typo never costs a rate-limit
 * strike).
 *
 * A code is a secret: it is never persisted, logged, put in analytics or in a saved navigation argument
 * (see [SetupCodeHolder]).
 */
object SetupCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 12
    const val PREFIX = "TUV"

    /** Why an input cannot be a code. */
    enum class Problem { EMPTY, BAD_CHARACTERS, WRONG_LENGTH }

    sealed interface Normalized {
        /** [value] is the 12 code characters. */
        data class Valid(val value: String) : Normalized
        data class Invalid(val problem: Problem) : Normalized
    }

    private fun clean(input: String): String =
        buildString { for (c in input.uppercase()) if (!c.isWhitespace() && c != '-') append(c) }

    fun normalize(input: String?): Normalized {
        var s = clean(input.orEmpty())
        if (s.isEmpty()) return Normalized.Invalid(Problem.EMPTY)
        // Only when what is left is exactly a code — a bare 12-char code that starts with TUV survives.
        if (s.length == LENGTH + PREFIX.length && s.startsWith(PREFIX)) s = s.drop(PREFIX.length)
        if (s.any { it !in ALPHABET }) return Normalized.Invalid(Problem.BAD_CHARACTERS)
        if (s.length != LENGTH) return Normalized.Invalid(Problem.WRONG_LENGTH)
        return Normalized.Valid(s)
    }

    /** The 12 normalized characters, or null. */
    fun parse(input: String?): String? = (normalize(input) as? Normalized.Valid)?.value

    /** `TUV-ABCD-EFGH-JKMN` for a valid code; [input] unchanged otherwise. */
    fun format(input: String): String {
        val code = parse(input) ?: return input
        return (listOf(PREFIX) + code.chunked(4)).joinToString("-")
    }

    /**
     * The entry field's text while typing: the characters typed so far, uppercase, grouped as
     * `TUV-XXXX-XXXX-XXXX`, with the `TUV-` lead shown as soon as anything is typed. A leading `TUV`
     * followed by a separator ("TUV-AB...", "tuv ab...") or making a 15-character run is the prefix and is
     * not doubled; a bare "TUVW..." is code characters. Characters outside [ALPHABET] are KEPT (so the
     * person sees what they typed and [normalize] names the problem), and the field never holds more than
     * [LENGTH] code characters. (Same behaviour as the Mobile/Desktop reference.)
     */
    fun liveFormat(typing: String): String {
        val upper = typing.uppercase().trimStart()
        var s = clean(upper)
        val hasSeparatedPrefix = upper.startsWith(PREFIX) && upper.length > PREFIX.length &&
            (upper[PREFIX.length].isWhitespace() || upper[PREFIX.length] == '-')
        if (hasSeparatedPrefix || (s.length == LENGTH + PREFIX.length && s.startsWith(PREFIX))) s = s.drop(PREFIX.length)
        s = s.take(LENGTH)
        if (s.isEmpty()) return ""
        return (listOf(PREFIX) + s.chunked(4)).joinToString("-")
    }

    fun isComplete(typing: String): Boolean = normalize(typing) is Normalized.Valid
}

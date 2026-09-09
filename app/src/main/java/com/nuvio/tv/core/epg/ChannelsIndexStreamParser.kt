package com.nuvio.tv.core.epg

/** Thrown when a single streamed unit (a source's metadata string, or one channel object) exceeds
 *  its per-unit cap, or nesting exceeds the depth cap — the caller rejects the whole update and keeps
 *  the previous valid generation (never a partial replacement). */
internal class EpgElementTooLargeException(val chars: Int) : RuntimeException()

/**
 * Fully streaming parser for the EPG channels-index document:
 *
 * ```
 * { "generatedAt": "..", "sources": [
 *     { "slug": "..", "label": "..", "countries": ".."|null, "channels": [ {"id":"..","names":[..]}, .. ] },
 *     ..
 * ] }
 * ```
 *
 * Unlike a "split each array element into a string" splitter, this NEVER buffers a whole source: a
 * source's scalar metadata is emitted as it is read, and its `channels` array is split element by
 * element, so the peak retained by the parser is one channel object plus the current input chunk —
 * independent of how many channels a source has or how many sources the document has.
 *
 * The handler receives, in document order:
 *  - [Handler.onSourceBegin] / [Handler.onSourceEnd] around each source,
 *  - [Handler.onSourceScalar] for `slug`/`label`/`countries` (value already JSON-unescaped; null for a
 *    JSON null), as they appear — the backend emits all three before `channels`
 *    (nuvio-backend/supabase/functions/epg-sync/index.ts), so a keep/skip decision is available before
 *    the first channel,
 *  - [Handler.onChannel] with each channel object's raw JSON (small; the caller decodes it).
 *
 * Chunks may split anywhere (mid-token, mid-string, mid-escape, mid-number) — all scanner state lives
 * on the instance. Bounds (working memory, independent of catalog size):
 *  - [maxScalarChars]: a single metadata string larger than this throws.
 *  - [maxChannelChars]: a single channel object larger than this throws.
 *  - [maxDepth]: nesting (in a channel object or a skipped value) deeper than this throws.
 *  - [finish] throws if the `sources` array never opened or never closed (absent/truncated body must
 *    not commit a partial replacement).
 */
internal class ChannelsIndexStreamParser(
    private val maxScalarChars: Int,
    private val maxChannelChars: Int,
    private val maxDepth: Int,
    private val handler: Handler,
) {
    interface Handler {
        fun onSourceBegin()
        fun onSourceScalar(key: String, value: String?)
        fun onChannel(channelJson: String)
        fun onSourceEnd()
    }

    private enum class State {
        SEEK, ARR_BETWEEN, OBJ_BETWEEN, KEY, COLON, VALUE_START,
        SVAL, LIT, CH_BETWEEN, CH_OBJ, SKIP, DONE,
    }

    private var state = State.SEEK
    private var started = false
    private var finished = false

    // Shared string-scan flags for whichever string is being consumed (key, scalar value, channel).
    private var inString = false
    private var escaped = false

    // Seek phase: find `"sources"` at top-object depth 1, then ':' then '['.
    private var seekDepth = 0
    private var seekInString = false
    private var seekEscaped = false
    private var seekAwaitColon = false
    private var seekArmed = false
    private val seekStr = StringBuilder()

    private val keyBuf = StringBuilder()
    private val svalBuf = StringBuilder()      // raw JSON string body of a captured scalar (escapes intact)
    private var captureKey = ""                // which scalar key we're reading (slug/label/countries)

    // Literal skip (null after a capture key, e.g. "countries": null).
    private var litRemaining = ""

    private val chBuf = StringBuilder()         // one channel object's raw JSON
    private var chDepth = 0

    // Generic value skip (unknown field).
    private var skipStarted = false
    private var skipScalar = false
    private var skipInString = false
    private var skipEscaped = false
    private var skipDepth = 0

    fun accept(chunk: CharSequence) {
        for (c in chunk) {
            if (state == State.DONE) return
            var reprocess = step(c)
            // A step may hand the char to the next state (bounded: at most one hand-off).
            while (reprocess && state != State.DONE) reprocess = step(c)
        }
    }

    /** Returns true if [c] should be re-fed to the new state. */
    private fun step(c: Char): Boolean {
        when (state) {
            State.SEEK -> seek(c)
            State.ARR_BETWEEN -> when {
                c == '{' -> { handler.onSourceBegin(); state = State.OBJ_BETWEEN }
                c == ']' -> { finished = true; state = State.DONE }
                // whitespace / ',' / stray tokens ignored
                else -> {}
            }
            State.OBJ_BETWEEN -> when {
                c == '"' -> { keyBuf.clear(); inString = true; escaped = false; state = State.KEY }
                c == '}' -> { handler.onSourceEnd(); state = State.ARR_BETWEEN }
                else -> {}
            }
            State.KEY -> {
                when {
                    escaped -> { escaped = false }
                    c == '\\' -> escaped = true
                    c == '"' -> { inString = false; state = State.COLON }
                    else -> if (keyBuf.length <= MAX_KEY_CHARS) keyBuf.append(c)
                }
            }
            State.COLON -> if (c == ':') state = State.VALUE_START // skip whitespace until colon
            State.VALUE_START -> return valueStart(c)
            State.SVAL -> scalarString(c)
            State.LIT -> literal(c)
            State.CH_BETWEEN -> when {
                c == '{' -> { chBuf.clear(); chBuf.append('{'); chDepth = 1; inString = false; escaped = false; state = State.CH_OBJ }
                c == ']' -> state = State.OBJ_BETWEEN
                else -> {}
            }
            State.CH_OBJ -> channelObject(c)
            State.SKIP -> return skip(c)
            State.DONE -> {}
        }
        return false
    }

    private fun seek(c: Char) {
        if (seekInString) {
            when {
                seekEscaped -> seekEscaped = false
                c == '\\' -> seekEscaped = true
                c == '"' -> {
                    seekInString = false
                    if (seekDepth == 1 && seekStr.toString() == "sources") seekAwaitColon = true
                }
                else -> if (seekStr.length <= 7) seekStr.append(c) // "sources".length; longer can't match
            }
            return
        }
        when (c) {
            '"' -> { seekInString = true; seekEscaped = false; seekStr.clear() }
            '[' -> if (seekArmed) { started = true; seekArmed = false; state = State.ARR_BETWEEN } else { seekDepth++; seekAwaitColon = false }
            '{' -> { seekDepth++; seekAwaitColon = false }
            '}', ']' -> { seekDepth--; seekAwaitColon = false; seekArmed = false }
            ':' -> { seekArmed = seekAwaitColon; seekAwaitColon = false }
            ',' -> { seekAwaitColon = false; seekArmed = false }
            else -> if (!c.isWhitespace()) { seekAwaitColon = false; seekArmed = false }
        }
    }

    private fun valueStart(c: Char): Boolean {
        if (c.isWhitespace()) return false
        val key = keyBuf.toString()
        return when {
            key == "channels" -> when (c) {
                '[' -> { state = State.CH_BETWEEN; false }
                else -> { beginSkip(); true } // null or unexpected: skip the value
            }
            key == "slug" || key == "label" || key == "countries" -> when (c) {
                '"' -> { captureKey = key; svalBuf.clear(); inString = true; escaped = false; state = State.SVAL; false }
                'n' -> { captureKey = key; litRemaining = "ull"; state = State.LIT; false } // null → emit null at end
                else -> { handler.onSourceScalar(key, null); beginSkip(); true } // non-string value: null + skip
            }
            else -> { beginSkip(); true } // unknown field
        }
    }

    private fun scalarString(c: Char) {
        when {
            escaped -> { appendScalar('\\'); appendScalar(c); escaped = false } // keep raw escape pair
            c == '\\' -> escaped = true
            c == '"' -> {
                inString = false
                handler.onSourceScalar(captureKey, decodeJsonString(svalBuf.toString()))
                state = State.OBJ_BETWEEN
            }
            else -> appendScalar(c)
        }
    }

    private fun appendScalar(c: Char) {
        if (svalBuf.length >= maxScalarChars) throw EpgElementTooLargeException(svalBuf.length + 1)
        svalBuf.append(c)
    }

    private fun literal(c: Char) {
        // Consuming the rest of a `null` literal after the leading 'n'.
        if (litRemaining.isNotEmpty() && c == litRemaining[0]) {
            litRemaining = litRemaining.substring(1)
            if (litRemaining.isEmpty()) {
                if (captureKey.isNotEmpty()) handler.onSourceScalar(captureKey, null)
                captureKey = ""
                state = State.OBJ_BETWEEN
            }
        } else {
            // Not the expected literal — tolerate by returning to field iteration.
            if (captureKey.isNotEmpty()) handler.onSourceScalar(captureKey, null)
            captureKey = ""
            state = State.OBJ_BETWEEN
        }
    }

    private fun channelObject(c: Char) {
        if (inString) {
            appendChannel(c)
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            return
        }
        when (c) {
            '"' -> { inString = true; appendChannel(c) }
            '{', '[' -> { chDepth++; if (chDepth > maxDepth) throw EpgElementTooLargeException(chDepth); appendChannel(c) }
            '}' -> { chDepth--; appendChannel(c); if (chDepth == 0) { handler.onChannel(chBuf.toString()); state = State.CH_BETWEEN } }
            ']' -> { chDepth--; appendChannel(c) }
            else -> appendChannel(c)
        }
    }

    private fun appendChannel(c: Char) {
        if (chBuf.length >= maxChannelChars) throw EpgElementTooLargeException(chBuf.length + 1)
        chBuf.append(c)
    }

    private fun beginSkip() {
        skipStarted = false; skipScalar = false; skipInString = false; skipEscaped = false; skipDepth = 0
        state = State.SKIP
    }

    /** Skips exactly one JSON value; returns true when a scalar's trailing delimiter must be re-fed. */
    private fun skip(c: Char): Boolean {
        if (!skipStarted) {
            if (c.isWhitespace()) return false
            skipStarted = true
            when (c) {
                '"' -> { skipInString = true; skipEscaped = false }
                '{', '[' -> skipDepth = 1
                else -> skipScalar = true
            }
            return false
        }
        if (skipScalar) {
            if (c == ',' || c == '}' || c == ']' || c.isWhitespace()) { state = State.OBJ_BETWEEN; return true }
            return false
        }
        if (skipInString) {
            when {
                skipEscaped -> skipEscaped = false
                c == '\\' -> skipEscaped = true
                c == '"' -> { skipInString = false; if (skipDepth == 0) state = State.OBJ_BETWEEN }
            }
            return false
        }
        when (c) {
            '"' -> { skipInString = true; skipEscaped = false }
            '{', '[' -> { skipDepth++; if (skipDepth > maxDepth) throw EpgElementTooLargeException(skipDepth) }
            '}', ']' -> { skipDepth--; if (skipDepth == 0) state = State.OBJ_BETWEEN }
        }
        return false
    }

    /** Throws if the `sources` array never opened or never closed. */
    fun finish() {
        check(started) { "channels-index: 'sources' array not found" }
        check(finished) { "channels-index: 'sources' array ended mid-stream (truncated)" }
    }

    private companion object {
        const val MAX_KEY_CHARS = 64
    }
}

/** Decodes a JSON string body (the characters between the quotes, escapes intact) into its value. */
private fun decodeJsonString(rawBody: String): String {
    if ('\\' !in rawBody) return rawBody
    val sb = StringBuilder(rawBody.length)
    var i = 0
    while (i < rawBody.length) {
        val c = rawBody[i]
        if (c != '\\' || i + 1 >= rawBody.length) { sb.append(c); i++; continue }
        when (val e = rawBody[i + 1]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                val code = if (i + 6 <= rawBody.length) rawBody.substring(i + 2, i + 6).toIntOrNull(16) else null
                if (code != null) { sb.append(code.toChar()); i += 6; continue }
                sb.append(e)
            }
            else -> sb.append(e)
        }
        i += 2
    }
    return sb.toString()
}

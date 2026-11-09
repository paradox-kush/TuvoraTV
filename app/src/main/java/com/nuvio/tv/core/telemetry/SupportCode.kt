package com.nuvio.tv.core.telemetry

import android.content.Context
import java.security.SecureRandom
import java.util.Random

/**
 * A short code a user can read off the screen and tell us, so we can find their device's
 * telemetry. Random per install, never derived from the analytics distinct id.
 */
object SupportCodeGenerator {
    /** No 0/O and 1/I/L look-alikes. */
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    const val LENGTH = 8

    fun generate(random: Random = SecureRandom()): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun isValid(code: String?): Boolean =
        code != null && code.length == LENGTH && code.all { it in ALPHABET }

    /** `K7Q29MPX` -> `K7Q2-9MPX`. */
    fun display(code: String): String =
        if (code.length == LENGTH) code.substring(0, 4) + "-" + code.substring(4) else code
}

/** Stores the code once per install. Safe to call from any thread. */
object SupportCodeStore {
    private const val PREFS = "telemetry"
    private const val KEY = "support_code"

    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val stored = prefs.getString(KEY, null)
                val code = if (SupportCodeGenerator.isValid(stored)) stored!! else
                    SupportCodeGenerator.generate().also { prefs.edit().putString(KEY, it).commit() }
                cached = code
                code
            }
        }
    }
}

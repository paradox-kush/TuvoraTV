package com.nuvio.tv.core.mediaserver

import kotlin.reflect.KClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Small assertion helpers the ported media-server tests use (the KMP twins use kotlin.test, which the TV test
 * classpath does not carry). Both take their message LAST, like the kotlin.test calls they stand in for, and are
 * the only assertions here that do - everything else is JUnit's `(message, expected, actual)`.
 */
inline fun <reified T : Any> assertIs(value: Any?, message: String? = null): T {
    assertTrue(message ?: "expected ${T::class.simpleName} but was ${value?.let { it::class.simpleName }}", value is T)
    return value as T
}

inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        fail((message ?: "") + " expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
    }
    fail((message ?: "") + " expected ${T::class.simpleName} but nothing was thrown")
    throw AssertionError()
}

/** JUnit's assertNotNull returns nothing; the ported tests use the checked value (kotlin.test's contract). Message FIRST, like JUnit. */
fun <T : Any> assertNotNull(value: T?): T {
    org.junit.Assert.assertNotNull(value)
    return value!!
}

fun <T : Any> assertNotNull(message: String, value: T?): T {
    org.junit.Assert.assertNotNull(message, value)
    return value!!
}

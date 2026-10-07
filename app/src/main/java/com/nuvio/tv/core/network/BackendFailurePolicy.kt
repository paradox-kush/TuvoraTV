package com.nuvio.tv.core.network

import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ResponseException

/** Permanent contract/auth failures must not enter transient network retries. */
object BackendFailurePolicy {
    fun status(error: Throwable): Int? = generateSequence(error) { it.cause }.take(16)
        .mapNotNull { cause -> when (cause) {
            is RestException -> cause.statusCode
            is ResponseException -> cause.response.status.value
            else -> null
        } }.firstOrNull()

    fun retryable(status: Int?): Boolean = status == null || status == 408 || status == 429 || status in 500..599
    fun membershipCooldownMs(status: Int?): Long =
        if (status == 404) 24L * 60 * 60 * 1000 else 15L * 60 * 1000
}

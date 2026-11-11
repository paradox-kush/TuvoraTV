package com.nuvio.tv.ui.screens.account

import com.nuvio.tv.R
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.ResponseException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps an exception to a user-facing string by its TYPE. Message text is only a fallback in
 * AccountViewModel (B220: a DNS failure whose text contained "could not find" read as "QR unavailable").
 */
internal object AccountErrorMapper {
    /** Network-class exceptions anywhere in the cause chain. They never carry a message worth reading. */
    fun networkResId(e: Throwable): Int? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < 8) {
            when (current) {
                is UnknownHostException -> return R.string.account_error_no_internet
                is SocketTimeoutException,
                is io.ktor.client.network.sockets.ConnectTimeoutException,
                is io.ktor.client.network.sockets.SocketTimeoutException,
                is io.ktor.client.plugins.HttpRequestTimeoutException -> return R.string.account_error_connection_timeout
                is ConnectException, is NoRouteToHostException, is SSLException -> return R.string.account_error_connection_refused
            }
            current = current.cause?.takeIf { it !== current }
        }
        return null
    }

    /** HTTP status of a REST failure, used only after no message rule matched. */
    fun statusResId(e: Throwable): Int? {
        val code = statusOf(e) ?: return null
        return when {
            code == 401 || code == 403 -> R.string.account_error_not_authenticated
            code == 429 -> R.string.account_error_rate_limited
            code == 400 -> R.string.account_error_invalid_request
            code == 404 || code in 500..599 -> R.string.account_error_service_unavailable
            else -> null
        }
    }

    private fun statusOf(e: Throwable): Int? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current is RestException) return current.statusCode
            if (current is ResponseException) return current.response.status.value
            current = current.cause?.takeIf { it !== current }
        }
        return null
    }
}

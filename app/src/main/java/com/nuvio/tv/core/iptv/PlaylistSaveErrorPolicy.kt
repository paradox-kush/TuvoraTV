package com.nuvio.tv.core.iptv

import java.io.IOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Why saving (adding / editing) a playlist failed, in the categories the viewer can act on. */
enum class PlaylistSaveError {
    /** A required field is truly empty. */
    MISSING_XTREAM_FIELDS,

    /** The server address can't be parsed (bad port, stray characters). */
    INVALID_ADDRESS,

    /** DNS, timeout, connection refused — or a server that answered with something that isn't a panel. */
    UNREACHABLE,

    /** The panel answered and refused the credentials (Xtream auth != 1, HTTP 401/403). */
    WRONG_CREDENTIALS,

    /** The HTTPS/TLS handshake or certificate check failed (B23 class). */
    SECURE_CONNECTION_FAILED,

    /** The credentials are right but the panel reports the account as not active (e.g. Expired). */
    ACCOUNT_INACTIVE,
}

/** The panel answered `user_info.auth != 1`: it refused the username/password. */
class XtreamAuthRejectedException : IllegalStateException("Authentication failed")

/** The panel accepted the credentials but reports a non-active account [status] (e.g. "Expired"). */
class XtreamAccountInactiveException(val status: String?) : IllegalStateException("Account status: $status")

/**
 * UX11 / UX20 / UX21 — the one place that turns a playlist add/edit failure into the sentence the
 * viewer sees (TV twin of the KMP `PlaylistSaveErrorPolicy`, same categories and wording).
 *
 * Before: an invalid port said "Enter a server URL, username and password" although all three were
 * filled; an unreachable host said "Authentication failed" or showed the raw socket text
 * ("Failed to connect to /10.0.2.2:8999"), including in the save-anyway warning on an edited row.
 *
 * Pure and type-driven: classifies by exception TYPE along the cause chain (never by parsing a
 * message) and holds no Context, so it tests without Android or a panel. The mapped sentence never
 * contains exception text — except [PlaylistSaveError.ACCOUNT_INACTIVE], which names the status the
 * panel itself reported.
 */
object PlaylistSaveErrorPolicy {

    const val MISSING_FIELDS_MESSAGE = "Enter a server URL, username and password"
    const val INVALID_ADDRESS_MESSAGE = "That server address isn't valid"
    const val UNREACHABLE_MESSAGE = "Couldn't reach the server — check the address"
    const val WRONG_CREDENTIALS_MESSAGE = "Wrong username or password"
    const val SECURE_CONNECTION_MESSAGE = "Secure connection failed — try http:// or check the certificate"

    /**
     * The manual Xtream form's own check, before any network: null when the three fields make a
     * usable account, else [PlaylistSaveError.MISSING_XTREAM_FIELDS] (something truly empty) or
     * [PlaylistSaveError.INVALID_ADDRESS] (everything filled, the address doesn't parse).
     */
    fun formError(serverUrl: String, username: String, password: String): PlaylistSaveError? {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) return PlaylistSaveError.MISSING_XTREAM_FIELDS
        return if (xtreamAccountFromFields(serverUrl, username, password) == null) PlaylistSaveError.INVALID_ADDRESS else null
    }

    /** The category of a failed provider check ([XtreamClient.verify] result). */
    fun classify(error: Throwable): PlaylistSaveError {
        val chain = generateSequence(error) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH).toList()
        // Order matters: SSLException IS an IOException, and a 401 is not "unreachable".
        chain.firstOrNull { it is XtreamAuthRejectedException }?.let { return PlaylistSaveError.WRONG_CREDENTIALS }
        chain.firstOrNull { it is XtreamAccountInactiveException }?.let { return PlaylistSaveError.ACCOUNT_INACTIVE }
        chain.filterIsInstance<HttpStatusException>().firstOrNull()?.let { http ->
            if (http.status == 401 || http.status == 403) return PlaylistSaveError.WRONG_CREDENTIALS
        }
        if (chain.any { it is SSLException || it is CertificateException }) return PlaylistSaveError.SECURE_CONNECTION_FAILED
        if (chain.any { it is IOException }) return PlaylistSaveError.UNREACHABLE
        // Anything else is a server that answered with something that isn't a working panel
        // (HTTP 5xx/404, an empty or non-JSON body): from the viewer's side, the address is wrong.
        return PlaylistSaveError.UNREACHABLE
    }

    /** The viewer-facing sentence for [error]; [cause] only supplies the reported account status. */
    fun message(error: PlaylistSaveError, cause: Throwable? = null): String = when (error) {
        PlaylistSaveError.MISSING_XTREAM_FIELDS -> MISSING_FIELDS_MESSAGE
        PlaylistSaveError.INVALID_ADDRESS -> INVALID_ADDRESS_MESSAGE
        PlaylistSaveError.UNREACHABLE -> UNREACHABLE_MESSAGE
        PlaylistSaveError.WRONG_CREDENTIALS -> WRONG_CREDENTIALS_MESSAGE
        PlaylistSaveError.SECURE_CONNECTION_FAILED -> SECURE_CONNECTION_MESSAGE
        PlaylistSaveError.ACCOUNT_INACTIVE -> {
            val status = generateSequence(cause) { it.cause.takeIf { c -> c !== it } }.take(MAX_CAUSE_DEPTH)
                .filterIsInstance<XtreamAccountInactiveException>().firstOrNull()?.status?.trim()
            if (status.isNullOrEmpty()) "Account status: inactive" else "Account status: $status"
        }
    }

    /** [classify] + [message] for a failed provider check. */
    fun messageFor(error: Throwable): String = message(classify(error), error)

    private const val MAX_CAUSE_DEPTH = 8
}

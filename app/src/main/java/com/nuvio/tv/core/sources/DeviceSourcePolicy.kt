package com.nuvio.tv.core.sources

/** Pure policy; callers must gate both discovery and source execution, including cached/deep links. */
enum class DeviceSourceAvailability {
    AVAILABLE, SETUP_REQUIRED, SOURCE_REMOVED, ACCOUNT_DISABLED, UNSUPPORTED_BUILD,
    DEVICE_DISABLED, SIGN_IN_REQUIRED,
}

data class DeviceSourceInputs(
    val setupResolved: Boolean,
    val sourceExists: Boolean,
    val accountEnabled: Boolean,
    val buildSupported: Boolean,
    val acknowledgedChoice: Boolean? = null,
    val pendingChoice: Boolean? = null,
    val hasPendingChoice: Boolean = false,
    val pendingConflict: Boolean = false,
    val locallyAuthenticated: Boolean = true,
)

object DeviceSourcePolicy {
    fun addonReference(raw: String): String {
        val uri = raw.trim().substringBefore('#')
        val path = uri.substringBefore('?').trimEnd('/')
        val base = if (path.endsWith("/manifest.json",ignoreCase = true)) path.dropLast(14).trimEnd('/') else path
        return base + if ('?' in uri) "?" + uri.substringAfter('?') else ""
    }

    fun availability(input: DeviceSourceInputs): DeviceSourceAvailability = with(input) {
        when {
            !setupResolved -> DeviceSourceAvailability.SETUP_REQUIRED
            !sourceExists -> DeviceSourceAvailability.SOURCE_REMOVED
            !accountEnabled -> DeviceSourceAvailability.ACCOUNT_DISABLED
            !buildSupported -> DeviceSourceAvailability.UNSUPPORTED_BUILD
            !effectiveChoice(input) -> DeviceSourceAvailability.DEVICE_DISABLED
            !locallyAuthenticated -> DeviceSourceAvailability.SIGN_IN_REQUIRED
            else -> DeviceSourceAvailability.AVAILABLE
        }
    }

    /** NULL means inherit. An unresolved stale enable/reset never overrides a newer saved off. */
    fun effectiveChoice(input: DeviceSourceInputs): Boolean = with(input) {
        if (!hasPendingChoice) return acknowledgedChoice ?: true
        if (pendingConflict && pendingChoice != false) return acknowledgedChoice ?: true
        pendingChoice ?: true
    }
}

enum class DevicePreferenceRefresh { NONE, SNAPSHOT, DELTA }

object DevicePreferenceRefreshPolicy {
    fun hasLocalWork(pendingChoices: Boolean, registrationPending: Boolean, snapshotPending: Boolean): Boolean =
        pendingChoices || registrationPending || snapshotPending

    /** Event-driven only. No timer, full source payload or recurring background work. */
    fun decide(
        resumed: Boolean,
        hasSession: Boolean,
        setupResolved: Boolean,
        cursor: Long?,
        knownRemoteRevision: Long?,
        reconnect: Boolean = false,
    ): DevicePreferenceRefresh = when {
        !resumed || !hasSession || !setupResolved -> DevicePreferenceRefresh.NONE
        cursor == null -> DevicePreferenceRefresh.SNAPSHOT
        reconnect || (knownRemoteRevision != null && knownRemoteRevision > cursor) -> DevicePreferenceRefresh.DELTA
        else -> DevicePreferenceRefresh.NONE
    }
}

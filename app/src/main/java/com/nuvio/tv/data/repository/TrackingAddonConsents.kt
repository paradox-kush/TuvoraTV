package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.domain.model.Addon
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tracking addons (Stremio `player` / `library` resources) this device's user explicitly agreed to
 * share their activity with. Consent is per device and per addon, defaults to NONE, is recorded only by
 * the install dialog's checkbox flow ([TrackingAddonConsentController.install]) and is never synced.
 */
interface TrackingAddonConsents {
    fun isGranted(baseUrl: String): Boolean
    fun grant(baseUrl: String)

    /** The add-on was removed: a later re-install (or a sync that brings it back) needs a new consent. */
    fun revoke(baseUrl: String)
}

/**
 * Pure decision: what leaves the device. A tracking addon that reached this device without the consent
 * dialog (synced from the account, restored, pre-existing) receives NOTHING until the user installs it
 * here and ticks the box. A non-tracking addon never receives these events anyway.
 */
object TrackingAddonConsentPolicy {
    fun mayReceiveEvents(addon: Addon, consents: TrackingAddonConsents): Boolean =
        addon.isTrackingAddon && consents.isGranted(addon.baseUrl)

    /**
     * Stored as a hash: an addon URL commonly carries the user's own token in its path. Canonicalised the way
     * the addon store derives `Addon.baseUrl` (no trailing slash, no `/manifest.json`, query kept), so a
     * manifest URL and the installed addon agree.
     */
    fun consentKey(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith("/manifest.json", ignoreCase = true)) {
            path.dropLast("/manifest.json".length).trimEnd('/')
        } else {
            path
        }
        return MessageDigest.getInstance("SHA-256").digest((cleanPath + query).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class SharedPrefsTrackingAddonConsents @Inject constructor(
    @ApplicationContext context: Context
) : TrackingAddonConsents {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun isGranted(baseUrl: String): Boolean =
        prefs.getBoolean(TrackingAddonConsentPolicy.consentKey(baseUrl), false)

    override fun grant(baseUrl: String) {
        prefs.edit().putBoolean(TrackingAddonConsentPolicy.consentKey(baseUrl), true).apply()
    }

    override fun revoke(baseUrl: String) {
        prefs.edit().remove(TrackingAddonConsentPolicy.consentKey(baseUrl)).apply()
    }

    private companion object {
        const val PREFS = "tracking_addon_consents"
    }
}

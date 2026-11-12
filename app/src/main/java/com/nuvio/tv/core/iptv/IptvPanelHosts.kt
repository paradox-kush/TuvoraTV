package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.PlaybackTlsPolicy
import java.util.Collections

/**
 * The hosts that are the user's IPTV panels: every playlist's server, portal and backup servers, plus the hosts of
 * stream URLs the IPTV lanes handed out this session (a panel often serves streams from a different host than its
 * API). Only these may fall back to unverified TLS for playback; see [com.nuvio.tv.core.contracts.PlaybackTlsPolicy].
 * Limit: a stream host with a bad certificate that never passed [note] (or an account) is strict.
 */
object IptvPanelHosts {
    @Volatile
    private var accountHosts: Set<String> = emptySet()

    /** Playlist display names: a TMDB-matched IPTV stream carries one as its addonName. */
    @Volatile
    private var accountNames: Set<String> = emptySet()

    private val noted: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > MAX_NOTED
        }
    )

    /** Pure: every host of [accounts] (server, portal, backups). */
    fun hostsOf(accounts: List<XtreamAccount>): Set<String> = buildSet {
        accounts.forEach { account ->
            hostOf(account.baseUrl)?.let(::add)
            hostOf(account.portalUrl)?.let(::add)
            account.backupUrls.orEmpty().forEach { hostOf(it)?.let(::add) }
        }
    }

    /** Pure: every non-blank display name of [accounts] (trimmed), the label a matched IPTV stream carries. */
    fun namesOf(accounts: List<XtreamAccount>): Set<String> =
        accounts.mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }.toSet()

    fun setAccounts(accounts: List<XtreamAccount>) {
        accountHosts = hostsOf(accounts)
        accountNames = namesOf(accounts)
    }

    /** True when [label] is one of the user's playlist names, i.e. a stream the matched IPTV lane handed out. */
    fun isAccountName(label: String): Boolean = label.trim() in accountNames

    /** Records the host of a stream URL an IPTV lane handed to the player. */
    fun note(url: String?) {
        val host = hostOf(url) ?: return
        noted[host] = true
    }

    fun isPanelHost(host: String): Boolean {
        val normalized = PlaybackTlsPolicy.normalize(host) ?: return false
        return normalized in accountHosts || noted.containsKey(normalized)
    }

    internal fun clearForTest() {
        accountHosts = emptySet()
        accountNames = emptySet()
        noted.clear()
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val text = url.trim().let { if ("://" in it) it else "http://$it" }
        val rest = text.substringAfter("://")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return PlaybackTlsPolicy.normalize(host)
    }

    private const val MAX_NOTED = 512
}

package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.diagnostics.LogRedaction

/**
 * Pure display decisions for a playlist and its saved channels (W2 device pass, TV twin of the
 * NuvioMobile `savedChannelDisplayName` / `guideSourcesChanged` / masked playlist URL).
 */
object PlaylistDisplayPolicy {

    /**
     * T7 (privacy): a playlist address as it may be SHOWN — an M3U link carries the login in its
     * query (`get.php?username=…&password=…`), an Xtream route in its path, some hosts as `user:pass@`.
     * Every login part is masked with the one redaction policy the logs use (B116), so a provider
     * picker, a settings row or a backup-server row never prints a password on the TV screen.
     */
    fun maskedUrl(raw: String?): String = if (raw.isNullOrBlank()) raw.orEmpty() else LogRedaction.url(raw.trim())

    /**
     * P6 / F10: the name to SHOW for a saved live channel (a favourite or a recent). Those rows carry
     * the name stored with the item, so they get the same clean-up as the playlist's own rows.
     * [contentId] names the playlist; an item of an unknown playlist keeps its stored name.
     */
    fun savedChannelDisplayName(raw: String, contentId: String, accounts: List<XtreamAccount>): String {
        val accountId = XtreamItemRegistry.parseId(contentId)?.accountId ?: return raw
        return accounts.firstOrNull { it.id == accountId }?.displayChannelName(raw) ?: raw
    }

    /**
     * P6 / F14: whether an edit changed where this playlist's guide comes from (its EPG URL list, or
     * the server / login its own xmltv.php and url-tvg are reached with). Such an edit re-reads the
     * guide at once; otherwise the old sources' guide stayed until the 12-hour refresh.
     */
    fun guideSourcesChanged(old: XtreamAccount, new: XtreamAccount): Boolean =
        old.epgUrl?.trim().orEmpty() != new.epgUrl?.trim().orEmpty() ||
            old.baseUrl != new.baseUrl || old.username != new.username || old.password != new.password
}

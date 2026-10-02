package com.nuvio.tv.core.iptv

/**
 * Step 2 — what a managed playlist is and what the customer may do with it. A playlist is managed iff
 * its key is in the profile's managed map (see [ManagedInfoStore]); the pulled playlist rows carry no
 * managed flag.
 */
object ManagedPlaylistPolicy {

    enum class EditableField { NAME, SERVER_LOGIN, OPTIONS }

    fun isManaged(key: String, managed: Map<String, ManagedPlaylistInfo>): Boolean = key in managed

    /**
     * What an edit may change. A managed playlist's server, login, EPG, UA and backups belong to the
     * provider (a pushed change would silently detach it): only the name and non-provider options
     * (DNS, refresh, content/category choices) stay with the customer.
     */
    fun editableFields(managed: Boolean): Set<EditableField> =
        if (managed) setOf(EditableField.NAME, EditableField.OPTIONS)
        else setOf(EditableField.NAME, EditableField.SERVER_LOGIN, EditableField.OPTIONS)

    /** False for a managed playlist: "Server and login" shows as a locked note, never an editor. */
    fun showEditServerLogin(managed: Boolean): Boolean = !managed

    /** The source type is chosen when a playlist is added and never changes in an edit, for any playlist. */
    fun sourceTypeSelectableInEdit(): Boolean = false

    /** "Managed by <provider>" renders from this provider name (the sentence lives in resources). */
    fun ownerName(key: String, managed: Map<String, ManagedPlaylistInfo>): String? = managed[key]?.providerName
}

/**
 * Step 2 — ships FIRST. The server silently DETACHES a managed row when a pushed row changes ANY
 * provider-owned field: `base_url/url/portal_url, username, password, mac_address, stalker_username,
 * stalker_password, backup_urls, epg_url, user_agent`. The edit form re-normalizes everything it
 * rebuilds (trailing slash, host case, default port, UA, backups), so an innocent rename of a managed
 * playlist would have severed it from its provider.
 *
 * For a managed playlist every edit is therefore built from the PULLED account with the provider-owned
 * fields byte-identical (no re-normalizing, no re-defaulting, no trimming). Only the name and the
 * non-provider options of [candidate] are taken. An unmanaged playlist is returned untouched.
 */
object ManagedEditPolicy {

    /** The account's provider-owned fields, exactly as stored (for tests and the invariant check). */
    fun providerOwned(acc: XtreamAccount): List<Any?> = listOf(
        acc.sourceType, acc.baseUrl, acc.username, acc.password, acc.portalUrl, acc.macAddress,
        acc.stalkerUsername, acc.stalkerPassword, acc.backupUrls, acc.epgUrl, acc.userAgent,
    )

    fun applyEdit(old: XtreamAccount, candidate: XtreamAccount, managed: Boolean): XtreamAccount {
        if (!managed) return candidate
        return old.copy(
            name = candidate.name.takeIf { it.isNotBlank() } ?: old.name,
            dnsProvider = candidate.dnsProvider,
            autoRefreshHours = candidate.autoRefreshHours,
        )
    }
}

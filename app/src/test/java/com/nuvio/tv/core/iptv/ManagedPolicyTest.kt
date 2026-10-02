package com.nuvio.tv.core.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step 2 contract sections 5 and 6: who owns a playlist, and the edit that must never detach one. */
class ManagedPolicyTest {

    private val info = ManagedPlaylistInfo("k1", "Acme TV", "Main", ProviderSupport(telegram = "acme_tv"))

    @Test
    fun `a playlist is managed iff its key is in the map`() {
        val map = mapOf("k1" to info)
        assertTrue(ManagedPlaylistPolicy.isManaged("k1", map))
        assertFalse(ManagedPlaylistPolicy.isManaged("k2", map))
        assertFalse(ManagedPlaylistPolicy.isManaged("k1", emptyMap()))
        assertEquals("Acme TV", ManagedPlaylistPolicy.ownerName("k1", map))
        assertEquals(null, ManagedPlaylistPolicy.ownerName("k2", map))
    }

    @Test
    fun `a managed playlist exposes only the name and the non-provider options`() {
        assertEquals(
            setOf(ManagedPlaylistPolicy.EditableField.NAME, ManagedPlaylistPolicy.EditableField.OPTIONS),
            ManagedPlaylistPolicy.editableFields(managed = true),
        )
        assertTrue(ManagedPlaylistPolicy.EditableField.SERVER_LOGIN in ManagedPlaylistPolicy.editableFields(managed = false))
        assertFalse(ManagedPlaylistPolicy.showEditServerLogin(managed = true))
        assertTrue(ManagedPlaylistPolicy.showEditServerLogin(managed = false))
    }

    @Test
    fun `the source type is never selectable in an edit`() {
        assertFalse(ManagedPlaylistPolicy.sourceTypeSelectableInEdit())
    }

    // --- ManagedEditPolicy (silent-detach guard) -----------------------------------------------

    /** What the edit form does: rebuild the account from typed fields (re-normalizing everything). */
    private fun formRebuild(old: XtreamAccount, newName: String): XtreamAccount = when (old.sourceType) {
        XtreamAccount.SOURCE_XTREAM -> xtreamAccountFromFields(old.baseUrl, old.username, old.password, newName)!!
        XtreamAccount.SOURCE_URL -> m3uAccountFromUrl(old.baseUrl, old.username, newName)!!
        else -> old.copy(
            name = newName,
            portalUrl = old.portalUrl.trim().lowercase(),
            baseUrl = old.portalUrl.trim().lowercase(),
            macAddress = old.macAddress.trim().lowercase(),
            stalkerUsername = old.stalkerUsername.trim(),
            stalkerPassword = old.stalkerPassword.trim(),
        )
    }.copy(
        // the form re-normalizes backups, re-defaults the UA and trims the EPG URL too
        userAgent = old.userAgent?.trim()?.lowercase()?.ifEmpty { null },
        epgUrl = old.epgUrl?.trim()?.removeSuffix("/"),
        backupUrls = old.backupUrls?.map { it.trim().lowercase().removeSuffix("/") },
    )

    private val xtream = XtreamAccount(
        id = "http://Panel.Example.com:80|Bob", name = "Acme", baseUrl = "HTTP://Panel.Example.com:80/", username = " Bob ",
        password = "Pa55 word ", sourceType = XtreamAccount.SOURCE_XTREAM, userAgent = "VLC/3.0.20 LibVLC/3.0.20",
        epgUrl = "http://EPG.Example.com/xmltv.php?u=1/", backupUrls = listOf("HTTP://Backup.Example.com:80/", "http://B2.example.com/"),
    )
    private val m3u = XtreamAccount(
        id = "m3u|HTTP://Host.Example.com:80/list.m3u?x=1", name = "Acme M3U", baseUrl = "HTTP://Host.Example.com:80/list.m3u?x=1",
        username = "My-Player/1.0", password = "", sourceType = XtreamAccount.SOURCE_URL, backupUrls = listOf("HTTP://Backup.Example.com:80/l.m3u/"),
    )
    private val stalker = XtreamAccount(
        id = "stalker|HTTP://Portal.Example.com/c|00:1A:79:AA:BB:CC", name = "Acme Stalker", baseUrl = "HTTP://Portal.Example.com/c",
        username = "", password = "", sourceType = XtreamAccount.SOURCE_STALKER, portalUrl = "HTTP://Portal.Example.com/c",
        macAddress = "00:1A:79:AA:BB:CC", stalkerUsername = " sUser ", stalkerPassword = " sPass ", userAgent = "MAG250",
        backupUrls = listOf("HTTP://Backup.Example.com:80/c/"),
    )

    @Test
    fun `the old edit path really does change provider-owned fields (the bug)`() {
        // Characterization of today's rebuild: this is what would silently detach a managed playlist.
        listOf(xtream, m3u, stalker).forEach { old ->
            val saved = formRebuild(old, "Renamed").asEditOf(old)
            assertNotEquals(
                "${old.sourceType}: an unguarded rename rewrote a provider-owned field",
                ManagedEditPolicy.providerOwned(old), ManagedEditPolicy.providerOwned(saved),
            )
        }
    }

    @Test
    fun `renaming a managed playlist keeps every provider-owned field byte-identical`() {
        listOf(xtream, m3u, stalker).forEach { old ->
            val saved = ManagedEditPolicy.applyEdit(old, formRebuild(old, "Renamed").asEditOf(old), managed = true)
            assertEquals("${old.sourceType}: provider-owned fields", ManagedEditPolicy.providerOwned(old), ManagedEditPolicy.providerOwned(saved))
            assertEquals("${old.sourceType}: the id never changes", old.id, saved.id)
            assertEquals("${old.sourceType}: the name is the one thing that changes", "Renamed", saved.name)
        }
    }

    @Test
    fun `a managed edit keeps the trailing slash uppercase host and explicit default port of a backup url`() {
        val saved = ManagedEditPolicy.applyEdit(xtream, formRebuild(xtream, "Renamed").asEditOf(xtream), managed = true)
        assertEquals(listOf("HTTP://Backup.Example.com:80/", "http://B2.example.com/"), saved.backupUrls)
        assertEquals("HTTP://Panel.Example.com:80/", saved.baseUrl)
        assertEquals(" Bob ", saved.username)
        assertEquals("Pa55 word ", saved.password)
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", saved.userAgent)
    }

    @Test
    fun `a managed edit takes the non-provider options and keeps the customer's choices`() {
        val candidate = formRebuild(xtream, "Renamed").copy(dnsProvider = XtreamAccount.DNS_GOOGLE, autoRefreshHours = 6)
        val saved = ManagedEditPolicy.applyEdit(xtream.copy(enabled = false), candidate.asEditOf(xtream.copy(enabled = false)), managed = true)
        assertEquals(XtreamAccount.DNS_GOOGLE, saved.dnsProvider)
        assertEquals(6, saved.autoRefreshHours)
        assertFalse("enabled is the customer's", saved.enabled)
    }

    @Test
    fun `a blank name never replaces a managed playlist's name`() {
        val saved = ManagedEditPolicy.applyEdit(xtream, xtream.copy(name = " "), managed = true)
        assertEquals("Acme", saved.name)
    }

    @Test
    fun `an unmanaged edit behaves exactly as today`() {
        listOf(xtream, m3u, stalker).forEach { old ->
            val edited = formRebuild(old, "Renamed").asEditOf(old)
            assertEquals("${old.sourceType}: untouched", edited, ManagedEditPolicy.applyEdit(old, edited, managed = false))
        }
    }
}

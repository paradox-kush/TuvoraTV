package com.nuvio.tv.core.mediaserver.store

import com.nuvio.tv.core.mediaserver.FakeSecureTokenStore
import org.junit.Test
import org.junit.Assert.assertEquals
import com.nuvio.tv.core.mediaserver.assertFailsWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class MediaServerCredentialStoreTest {
    @Test
    fun aCredentialRoundTripsPerServerKey() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        store.save("jellyfin:m:u", StoredCredential("tok-1", "kid"))
        store.save("emby:m:u", StoredCredential("tok-2"))
        assertEquals("tok-1", store.token("jellyfin:m:u"))
        assertEquals("kid", store.credential("jellyfin:m:u")?.userName)
        assertEquals("tok-2", store.token("emby:m:u"))
        store.remove("jellyfin:m:u")
        assertNull(store.token("jellyfin:m:u"))
        assertEquals("tok-2", store.token("emby:m:u"))
    }

    @Test
    fun aBlankTokenCannotBeSavedAndAnUnreadableItemReadsAsSignedOut() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        assertFailsWith<IllegalArgumentException> { store.save("k", StoredCredential("  ")) }
        secure.items["cred:k"] = "{not json"
        assertNull(store.token("k"))
        secure.items["cred:k2"] = """{"accessToken":""}"""
        assertNull(store.token("k2"))
    }

    @Test
    fun theDeviceIdIsMintedOnceAndStable() {
        val secure = FakeSecureTokenStore()
        val a = MediaServerCredentialStore(secure).deviceId()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("survives a restart", a, MediaServerCredentialStore(secure).deviceId())
        assertNotEquals("unique per install", a, MediaServerCredentialStore(FakeSecureTokenStore()).deviceId())
        val salt = MediaServerCredentialStore(secure).installSalt()
        assertNotEquals(a, salt)
        assertEquals(salt, MediaServerCredentialStore(secure).installSalt())
    }

    @Test
    fun aFailingSecureStoreSurfacesInsteadOfPretendingToBeSignedIn() {
        val secure = FakeSecureTokenStore().apply { failWrites = true }
        assertFailsWith<IllegalStateException> { MediaServerCredentialStore(secure).save("k", StoredCredential("t")) }
        assertTrue(secure.items.isEmpty())
    }

    @Test
    fun clearAllRemovesEverythingIncludingTheDeviceId() {
        val secure = FakeSecureTokenStore()
        val store = MediaServerCredentialStore(secure)
        store.save("k", StoredCredential("t"))
        val id = store.deviceId()
        store.clearAll()
        assertFalse(secure.items.isNotEmpty())
        assertNotEquals("a wiped device is a new install", id, store.deviceId())
    }
}

package com.nuvio.tv.core.mediaserver.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Where a media-server session token lives: per device, in the platform's protected storage - the iOS/tvOS
 * Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud), Android Keystore AES-GCM +
 * private preferences, and on desktop an AES-GCM file whose key sits in the OS secret store (design 5.3,
 * D1). NEVER plaintext, NEVER synced, NEVER logged. Keys are opaque strings (a server key, `device-id`...).
 */
internal interface SecureTokenStore {
    fun read(key: String): String?

    /** A null [value] removes the item. Throws when the platform could not persist it - a silent failure would look signed in. */
    fun write(key: String, value: String?)

    fun clearAll()
}

@Serializable
internal data class StoredCredential(val accessToken: String, val userName: String? = null)

/**
 * Typed access over a [SecureTokenStore]: one credential per server key (`{type}:{machineId}:{userId}`) plus
 * two install-wide values - the MediaBrowser `DeviceId` (Jellyfin and Emby key sessions and tokens by it, so
 * it must be stable per install and unique per install) and a salt for telemetry ids. None of it syncs.
 */
internal class MediaServerCredentialStore(private val secure: SecureTokenStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun credential(serverKey: String): StoredCredential? =
        secure.read(credentialKey(serverKey))?.let { runCatching { json.decodeFromString<StoredCredential>(it) }.getOrNull() }
            ?.takeIf { it.accessToken.isNotBlank() }

    fun token(serverKey: String): String? = credential(serverKey)?.accessToken

    fun save(serverKey: String, credential: StoredCredential) {
        require(credential.accessToken.isNotBlank())
        secure.write(credentialKey(serverKey), json.encodeToString(credential))
    }

    fun remove(serverKey: String) = secure.write(credentialKey(serverKey), null)

    fun clearAll() = secure.clearAll()

    /** A stable per-install id, minted once. Random (not derived from the hardware), so it identifies an install, not a person. */
    fun deviceId(): String = secure.read(DEVICE_ID_KEY)?.takeIf { it.isNotBlank() } ?: mint(32).also { secure.write(DEVICE_ID_KEY, it) }

    fun installSalt(): String = secure.read(SALT_KEY)?.takeIf { it.isNotBlank() } ?: mint(32).also { secure.write(SALT_KEY, it) }

    private fun mint(length: Int): String {
        val alphabet = "0123456789abcdef"
        return buildString(length) { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private fun credentialKey(serverKey: String) = "cred:$serverKey"

    private companion object {
        const val DEVICE_ID_KEY = "device-id"
        const val SALT_KEY = "install-salt"
    }
}

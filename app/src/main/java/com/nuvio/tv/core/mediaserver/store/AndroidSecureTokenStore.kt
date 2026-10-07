package com.nuvio.tv.core.mediaserver.store

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM over private SharedPreferences (the same construction the MDBList credentials
 * use; `security-crypto` is deprecated). The key never leaves the Keystore; each value is bound to its own
 * item key as AAD, so a blob copied to another key does not decrypt. An undecryptable value (restored backup
 * on a new device, a wiped Keystore) is removed and reads as absent - the user simply signs in again.
 */
internal object PlatformSecureTokenStore : SecureTokenStore {
    private const val keyAlias = "com.nuvio.media.mediaserver.tokens.v1"
    private var preferences: SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("nuvio_mediaserver_tokens", Context.MODE_PRIVATE)
    }

    override fun read(key: String): String? = synchronized(lock) {
        val prefs = preferences ?: return@synchronized null
        val value = prefs.getString("item.$key", null) ?: return@synchronized null
        try {
            val parts = value.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decode(parts[0])))
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[1])).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove("item.$key").commit()
            null
        }
    }

    override fun write(key: String, value: String?) = synchronized(lock) {
        val prefs = preferences ?: throw IOException("secure token store not initialised")
        val editor = prefs.edit()
        if (value == null) {
            editor.remove("item.$key")
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD("$keyAlias:$key".toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString("item.$key", "${encode(cipher.iv)}.${encode(encrypted)}")
        }
        if (!editor.commit()) throw IOException("unable to save the protected sign-in")
    }

    override fun clearAll() = synchronized(lock) {
        val prefs = preferences ?: return@synchronized
        if (!prefs.edit().clear().commit()) throw IOException("unable to clear the protected sign-ins")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}

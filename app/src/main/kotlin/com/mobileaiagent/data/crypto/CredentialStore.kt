package com.mobileaiagent.data.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.mobileaiagent.domain.repo.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys encrypted with an AES-256-GCM key held in the Android Keystore.
 *
 * - The key never leaves secure hardware where available (TEE/StrongBox).
 * - Ciphertext + IV are stored in a private SharedPreferences file with
 *   backups excluded (see backup_rules.xml).
 * - If the Keystore is unavailable (rare broken devices), the store reports the
 *   failure rather than silently falling back to plaintext.
 */
class AndroidKeystoreCredentialStore(private val context: Context) : CredentialStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyAlias = "mobileaiagent_api_keys_v1"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    private fun secretKey(): SecretKey {
        keyStore.getEntry(keyAlias, null)?.let { entry ->
            (entry as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-auth requirement: background agent tasks must be able to
                // read keys while the screen is off.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(":")
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("CredentialStore", "Could not decrypt stored key: ${e.message}")
            null
        }
    }

    override suspend fun getKey(providerId: String): String? {
        val stored = prefs.getString(key(providerId), null) ?: return null
        return decrypt(stored)
    }

    override suspend fun setKey(providerId: String, key: String?) {
        if (key.isNullOrBlank()) {
            prefs.edit().remove(key(providerId)).apply()
            return
        }
        prefs.edit().putString(key(providerId), encrypt(key.trim())).apply()
    }

    override suspend fun hasKey(providerId: String): Boolean = getKey(providerId)?.isNotBlank() == true

    private fun key(providerId: String) = "api_key_$providerId"

    companion object {
        private const val PREFS = "secure_api_keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

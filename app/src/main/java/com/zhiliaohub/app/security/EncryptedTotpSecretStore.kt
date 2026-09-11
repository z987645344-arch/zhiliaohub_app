package com.zhiliaohub.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

// This file lives in the app "file" domain, which data_extraction_rules.xml excludes in full.
private val Context.totpSecretDataStore by preferencesDataStore(name = "totp_secret")

class EncryptedTotpSecretStore(private val context: Context) {
    suspend fun isBound(): Boolean {
        val preferences = context.totpSecretDataStore.data.first()
        return preferences[Keys.iv] != null &&
            preferences[Keys.ciphertext] != null &&
            keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)
    }

    /** Stores only AES-GCM ciphertext in DataStore and overwrites all plaintext buffers. */
    suspend fun bind(secretInput: CharArray) {
        val normalizedSecret = Base32Codec.normalizeAndValidate(secretInput)
        var payload: EncryptedTotpSecret? = null
        try {
            payload = TotpSecretCrypto.encrypt(normalizedSecret, getOrCreateEncryptionKey())
            val encodedIv = Base64.getEncoder().encodeToString(payload.iv)
            val encodedCiphertext = Base64.getEncoder().encodeToString(payload.ciphertext)
            context.totpSecretDataStore.edit { preferences ->
                preferences[Keys.iv] = encodedIv
                preferences[Keys.ciphertext] = encodedCiphertext
            }
        } finally {
            normalizedSecret.fill(0)
            payload?.iv?.fill(0)
            payload?.ciphertext?.fill(0)
        }
    }

    suspend fun codeAt(unixTimeSeconds: Long): String? {
        val preferences = context.totpSecretDataStore.data.first()
        val encodedIv = preferences[Keys.iv] ?: return null
        val encodedCiphertext = preferences[Keys.ciphertext] ?: return null
        val key = existingEncryptionKey() ?: return null
        val iv = Base64.getDecoder().decode(encodedIv)
        val ciphertext = Base64.getDecoder().decode(encodedCiphertext)
        var plaintext: ByteArray? = null
        try {
            plaintext = TotpSecretCrypto.decrypt(EncryptedTotpSecret(iv, ciphertext), key)
            return TotpGenerator.generate(plaintext, unixTimeSeconds)
        } finally {
            plaintext?.fill(0)
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    suspend fun clear() {
        var failure: Throwable? = null
        try {
            context.totpSecretDataStore.edit { preferences ->
                preferences.remove(Keys.iv)
                preferences.remove(Keys.ciphertext)
            }
        } catch (error: Throwable) {
            failure = error
        }
        try {
            val store = keyStore
            if (store.containsAlias(ENCRYPTION_KEY_ALIAS)) store.deleteEntry(ENCRYPTION_KEY_ALIAS)
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    internal fun hasEncryptionKey(): Boolean = keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingEncryptionKey(): SecretKey? =
        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateEncryptionKey(): SecretKey {
        existingEncryptionKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private object Keys {
        val iv = stringPreferencesKey("iv")
        val ciphertext = stringPreferencesKey("ciphertext")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        internal const val ENCRYPTION_KEY_ALIAS = "zhiliaohub_totp_secret_encryption_key_v1"
    }
}

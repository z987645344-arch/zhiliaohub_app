package com.zhiliaohub.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@SuppressLint("ApplySharedPref", "UseKtx")
class EncryptedSessionCookieJar(context: Context) : CookieJar {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val current = readCookies().filter { it.expiresAt > now }.toMutableList()
            cookies.filter { it.name == SESSION_COOKIE_NAME }.forEach { incoming ->
                current.removeAll {
                    it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path
                }
                if (incoming.expiresAt > now) current += incoming
            }
            writeCookies(current)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val all = readCookies()
        val valid = all.filter { it.expiresAt > now }
        if (valid.size != all.size) writeCookies(valid)
        valid.filter { it.matches(url) }
    }

    fun clear() {
        synchronized(lock) {
            preferences.edit().clear().commit()
        }
    }

    private fun writeCookies(cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            preferences.edit().clear().commit()
            return
        }
        val array = JSONArray()
        cookies.forEach { cookie ->
            array.put(JSONObject().apply {
                put("name", cookie.name)
                put("value", cookie.value)
                put("expiresAt", cookie.expiresAt)
                put("domain", cookie.domain)
                put("path", cookie.path)
                put("secure", cookie.secure)
                put("httpOnly", cookie.httpOnly)
                put("hostOnly", cookie.hostOnly)
            })
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val ciphertext = cipher.doFinal(array.toString().toByteArray(StandardCharsets.UTF_8))
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
    }

    private fun readCookies(): List<Cookie> {
        val encodedIv = preferences.getString(KEY_IV, null) ?: return emptyList()
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return emptyList()
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val iv = Base64.decode(encodedIv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
            val array = JSONArray(String(plaintext, StandardCharsets.UTF_8))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val builder = Cookie.Builder()
                        .name(item.getString("name"))
                        .value(item.getString("value"))
                        .expiresAt(item.getLong("expiresAt"))
                        .path(item.getString("path"))
                    if (item.getBoolean("hostOnly")) builder.hostOnlyDomain(item.getString("domain"))
                    else builder.domain(item.getString("domain"))
                    if (item.getBoolean("secure")) builder.secure()
                    if (item.getBoolean("httpOnly")) builder.httpOnly()
                    add(builder.build())
                }
            }
        } catch (_: Exception) {
            preferences.edit().clear().commit()
            emptyList()
        }
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
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

    companion object {
        const val SESSION_COOKIE_NAME = "zhiliaohub.admin.sid"
        private const val PREFERENCES_NAME = "encrypted_session_cookie"
        private const val KEY_IV = "iv"
        private const val KEY_CIPHERTEXT = "ciphertext"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "zhiliaohub_session_cookie_encryption_key_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package com.zhiliaohub.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class DeviceKeyManager {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    fun hasSigningKey(): Boolean = keyStore.containsAlias(SIGNING_KEY_ALIAS)

    fun getOrCreatePublicKeyPem(): String {
        val keyPair = getOrCreateKeyPair()
        val base64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val body = base64.chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----"
    }

    fun createBiometricSignature(): Signature {
        val privateKey = keyStore.getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("设备签名密钥不存在。")
        return Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey)
        }
    }

    fun deleteSigningKey() {
        val store = keyStore
        if (store.containsAlias(SIGNING_KEY_ALIAS)) store.deleteEntry(SIGNING_KEY_ALIAS)
    }

    private fun getOrCreateKeyPair(): KeyPair {
        val store = keyStore
        val existing = store.getEntry(SIGNING_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            SIGNING_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.initialize(builder.build())
        return generator.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNING_KEY_ALIAS = "zhiliaohub_device_signing_key_v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }
}


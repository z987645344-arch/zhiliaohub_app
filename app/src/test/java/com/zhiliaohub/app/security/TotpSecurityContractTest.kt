package com.zhiliaohub.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

class TotpSecurityContractTest {
    @Test
    fun aesGcmPersistencePayloadContainsCiphertextNotPlaintext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "JBSWY3DPEHPK3PXP".toByteArray()

        val encrypted = TotpSecretCrypto.encrypt(plaintext, key)
        val restored = TotpSecretCrypto.decrypt(encrypted, key)

        assertFalse(encrypted.ciphertext.contentEquals(plaintext))
        assertFalse(containsSequence(encrypted.ciphertext, plaintext))
        assertArrayEquals(plaintext, restored)
        plaintext.fill(0)
        restored.fill(0)
        encrypted.iv.fill(0)
        encrypted.ciphertext.fill(0)
    }

    @Test
    fun dataStoreStorageIsExcludedFromBackupAndDeviceTransfer() {
        val rules = projectFile("app/src/main/res/xml/data_extraction_rules.xml").readText()
        val manifest = projectFile("app/src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"false\""))
        assertTrue(rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
            .contains("<exclude domain=\"file\" path=\".\" />"))
        assertTrue(rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
            .contains("<exclude domain=\"file\" path=\".\" />"))
    }

    @Test
    fun totpSecretStorageHasNoNetworkOrLoggingDependency() {
        val source = projectFile(
            "app/src/main/java/com/zhiliaohub/app/security/EncryptedTotpSecretStore.kt",
        ).readText()
        val networkSources = projectFile("app/src/main/java/com/zhiliaohub/app/network")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .joinToString("\n") { it.readText() }

        assertFalse(source.contains("okhttp", ignoreCase = true))
        assertFalse(source.contains("Request.Builder"))
        assertFalse(source.contains("Log."))
        assertFalse(source.contains("println("))
        assertFalse(networkSources.contains("totp", ignoreCase = true))
    }

    @Test
    fun storePersistsOnlyEncryptedPayloadAndClearRemovesBothLayers() {
        val source = projectFile(
            "app/src/main/java/com/zhiliaohub/app/security/EncryptedTotpSecretStore.kt",
        ).readText()

        assertTrue(source.contains("TotpSecretCrypto.encrypt(normalizedSecret"))
        assertTrue(source.contains("preferences[Keys.ciphertext] = encodedCiphertext"))
        assertTrue(source.contains("preferences.remove(Keys.iv)"))
        assertTrue(source.contains("preferences.remove(Keys.ciphertext)"))
        assertTrue(source.contains("store.deleteEntry(ENCRYPTION_KEY_ALIAS)"))
        assertFalse(source.contains("preferences[Keys.ciphertext] = normalizedSecret"))
    }

    private fun containsSequence(container: ByteArray, candidate: ByteArray): Boolean {
        if (candidate.isEmpty() || candidate.size > container.size) return false
        return (0..container.size - candidate.size).any { start ->
            candidate.indices.all { offset -> container[start + offset] == candidate[offset] }
        }
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(5) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Cannot locate project file: $relativePath")
    }
}

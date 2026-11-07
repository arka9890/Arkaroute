package com.omniroute.app.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreCryptoEngine : CryptoEngine {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain)
        return cipher.iv + ciphertext
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_SIZE)
        val ciphertext = blob.copyOfRange(IV_SIZE, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(ALIAS)) {
            val entry = keyStore.getEntry(ALIAS, null) as javax.crypto.SecretKeyEntry
            return entry.secretKey as SecretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private companion object {
        const val ALIAS = "omniroute_config"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}

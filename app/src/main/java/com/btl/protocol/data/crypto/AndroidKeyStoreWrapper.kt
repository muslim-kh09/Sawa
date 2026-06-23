package com.btl.protocol.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KeyStoreWrapper"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val MASTER_KEY_ALIAS = "BTL_MASTER_KEY_02"  // bumped alias to force fresh keygen
private const val GCM_IV_LENGTH = 12
private const val GCM_TAG_LENGTH = 128

/**
 * AES-256-GCM encryption backed by the Android Keystore.
 *
 * Key generation policy:
 * 1. Attempts to generate the key in StrongBox (dedicated security chip).
 * 2. On [StrongBoxUnavailableException] (device lacks StrongBox hardware),
 *    falls back gracefully to the standard TEE-backed keystore.
 *    The previous implementation crashed unconditionally on ~80% of Android devices
 *    because StrongBox was required with no fallback.
 *
 * Output format: [IV (12 bytes)] + [Ciphertext + GCM Tag]
 */
@Singleton
class AndroidKeyStoreWrapper @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    init {
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            generateMasterKey()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext  // [IV (12)] + [Ciphertext + Tag]
    }

    fun decrypt(encryptedData: ByteArray): ByteArray {
        require(encryptedData.size > GCM_IV_LENGTH) {
            "Encrypted data too short (${encryptedData.size} bytes) — cannot extract IV."
        }
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    fun wrapKey(keyData: ByteArray): ByteArray = encrypt(keyData)

    fun unwrapKey(wrappedData: ByteArray): ByteArray = decrypt(wrappedData)

    // ──────────────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────────────

    private fun getKey(): SecretKey =
        keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey

    private fun generateMasterKey() {
        // Attempt 1: StrongBox-backed (dedicated security chip)
        if (!tryGenerateKey(strongBox = true)) {
            // Attempt 2: TEE-backed (software-emulated Trusted Execution Environment)
            Log.w(TAG, "StrongBox unavailable — falling back to TEE-backed key generation.")
            tryGenerateKey(strongBox = false)
        }
    }

    private fun tryGenerateKey(strongBox: Boolean): Boolean {
        return try {
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setIsStrongBoxBacked(strongBox)
                .build()

            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .also { it.init(spec) }
                .generateKey()

            Log.i(TAG, "Master key generated. StrongBox=$strongBox")
            true
        } catch (e: StrongBoxUnavailableException) {
            Log.w(TAG, "StrongBox not available on this hardware.", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Key generation failed (StrongBox=$strongBox)", e)
            false
        }
    }
}

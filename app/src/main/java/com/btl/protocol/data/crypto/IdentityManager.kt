package com.btl.protocol.data.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.X25519
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityManager @Inject constructor(
    private val keyStoreWrapper: AndroidKeyStoreWrapper,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "SawaIdentityV3"
        private const val PREF_X25519_PRIVATE_WRAPPED = "x25519_private_wrapped"
        private const val PREF_X25519_PUBLIC = "x25519_public"
        private const val PREF_ED25519_PRIVATE_WRAPPED = "ed25519_private_wrapped"
        private const val PREF_ED25519_PUBLIC = "ed25519_public"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var x25519PublicKey: ByteArray = ByteArray(0)
        private set
    var ed25519PublicKey: ByteArray = ByteArray(0)
        private set

    init {
        initializeKeys()
    }

    private fun initializeKeys() {
        val xPubB64 = prefs.getString(PREF_X25519_PUBLIC, null)
        val edPubB64 = prefs.getString(PREF_ED25519_PUBLIC, null)

        if (xPubB64 == null || edPubB64 == null) {
            generateAndStoreKeys()
        } else {
            x25519PublicKey = Base64.decode(xPubB64, Base64.NO_WRAP)
            ed25519PublicKey = Base64.decode(edPubB64, Base64.NO_WRAP)
        }
    }

    private fun generateAndStoreKeys() {
        // X25519
        val x25519Private = X25519.generatePrivateKey()
        x25519PublicKey = X25519.publicFromPrivate(x25519Private)
        val wrappedX25519 = keyStoreWrapper.wrapKey(x25519Private)

        // Ed25519
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        val ed25519PrivateKey = keyPair.privateKey
        ed25519PublicKey = keyPair.publicKey
        val wrappedEd25519 = keyStoreWrapper.wrapKey(ed25519PrivateKey)

        prefs.edit().apply {
            putString(PREF_X25519_PRIVATE_WRAPPED, Base64.encodeToString(wrappedX25519, Base64.NO_WRAP))
            putString(PREF_X25519_PUBLIC, Base64.encodeToString(x25519PublicKey, Base64.NO_WRAP))
            putString(PREF_ED25519_PRIVATE_WRAPPED, Base64.encodeToString(wrappedEd25519, Base64.NO_WRAP))
            putString(PREF_ED25519_PUBLIC, Base64.encodeToString(ed25519PublicKey, Base64.NO_WRAP))
            apply()
        }
    }

    fun getX25519PrivateKey(): ByteArray {
        val b64 = prefs.getString(PREF_X25519_PRIVATE_WRAPPED, null) ?: throw IllegalStateException("Key not generated")
        return keyStoreWrapper.unwrapKey(Base64.decode(b64, Base64.NO_WRAP))
    }

    fun getEd25519PrivateKey(): ByteArray {
        val b64 = prefs.getString(PREF_ED25519_PRIVATE_WRAPPED, null) ?: throw IllegalStateException("Key not generated")
        return keyStoreWrapper.unwrapKey(Base64.decode(b64, Base64.NO_WRAP))
    }

    fun getPublicFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(x25519PublicKey)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

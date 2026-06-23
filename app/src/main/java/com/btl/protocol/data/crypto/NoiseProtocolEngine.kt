package com.btl.protocol.data.crypto

import com.google.crypto.tink.subtle.ChaCha20Poly1305
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoiseProtocolEngine @Inject constructor(
    private val identityManager: IdentityManager
) {

    /**
     * Noise XX Pattern: ECDH key exchange between local X25519 and remote X25519 public key.
     * Returns a symmetric shared secret.
     */
    fun performEcdh(remoteX25519PublicKey: ByteArray): ByteArray {
        val localPrivateKey = identityManager.getX25519PrivateKey()
        return X25519.computeSharedSecret(localPrivateKey, remoteX25519PublicKey)
    }

    /**
     * Encrypts a payload using ChaCha20-Poly1305.
     * Note: Tink's ChaCha20Poly1305 implementation handles nonce generation and prepends it.
     */
    fun encryptChaCha20Poly1305(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray {
        val aead = ChaCha20Poly1305(sharedSecret)
        return aead.encrypt(plaintext, ByteArray(0))
    }

    /**
     * Decrypts a payload using ChaCha20-Poly1305.
     */
    fun decryptChaCha20Poly1305(sharedSecret: ByteArray, ciphertext: ByteArray): ByteArray {
        val aead = ChaCha20Poly1305(sharedSecret)
        return aead.decrypt(ciphertext, ByteArray(0))
    }

    /**
     * Signs a payload using the local Ed25519 private key.
     */
    fun signPayload(payload: ByteArray): ByteArray {
        val privateKey = identityManager.getEd25519PrivateKey()
        val signer = Ed25519Sign(privateKey)
        return signer.sign(payload)
    }

    /**
     * Verifies an Ed25519 signature.
     */
    fun verifySignature(publicKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Verify(publicKey)
            verifier.verify(signature, payload)
            true
        } catch (e: Exception) {
            false
        }
    }
}

#include <jni.h>
#include <string.h>
#include <sodium.h>
#include <vector>

// Strict memory management: Purge memory for sensitive arrays
void purge_memory(uint8_t* ptr, size_t size) {
    if (ptr != nullptr && size > 0) {
        sodium_memzero(ptr, size);
    }
}

// Double Ratchet State Machine Struct
struct RatchetState {
    uint8_t root_key[32];
    uint8_t send_chain_key[32];
    uint8_t recv_chain_key[32];
    uint8_t DHs[crypto_kx_SECRETKEYBYTES]; // Self Secret
    uint8_t DHr[crypto_kx_PUBLICKEYBYTES]; // Remote Public
    uint32_t send_message_num = 0;
    uint32_t recv_message_num = 0;
    
    ~RatchetState() {
        purge_memory(root_key, sizeof(root_key));
        purge_memory(send_chain_key, sizeof(send_chain_key));
        purge_memory(recv_chain_key, sizeof(recv_chain_key));
        purge_memory(DHs, sizeof(DHs));
    }
};

// KDF Chains for Double Ratchet
void kdf_rk(const uint8_t* rk, const uint8_t* dh_out, uint8_t* next_rk, uint8_t* next_ck) {
    uint8_t temp[64];
    crypto_auth_hmacsha512_state state;
    crypto_auth_hmacsha512_init(&state, rk, 32);
    crypto_auth_hmacsha512_update(&state, dh_out, 32);
    crypto_auth_hmacsha512_final(&state, temp);
    memcpy(next_rk, temp, 32);
    memcpy(next_ck, temp + 32, 32);
    purge_memory(temp, 64);
}

void kdf_ck(const uint8_t* ck, uint8_t* next_ck, uint8_t* msg_key) {
    uint8_t input_ck[1] = {0x01};
    uint8_t input_mk[1] = {0x02};
    crypto_auth_hmacsha256(next_ck, input_ck, 1, ck);
    crypto_auth_hmacsha256(msg_key, input_mk, 1, ck);
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (sodium_init() == -1) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

// Module 1: Complete cryptographic key-pair generation functions (Ed25519)
JNIEXPORT jobjectArray JNICALL
Java_com_btl_protocol_data_crypto_NativeCrypto_generateEd25519KeyPair(JNIEnv *env, jobject thiz) {
    uint8_t pk[crypto_sign_PUBLICKEYBYTES];
    uint8_t sk[crypto_sign_SECRETKEYBYTES];
    
    crypto_sign_keypair(pk, sk);
    
    jbyteArray publicKey = env->NewByteArray(crypto_sign_PUBLICKEYBYTES);
    env->SetByteArrayRegion(publicKey, 0, crypto_sign_PUBLICKEYBYTES, reinterpret_cast<jbyte*>(pk));
    
    jbyteArray secretKey = env->NewByteArray(crypto_sign_SECRETKEYBYTES);
    env->SetByteArrayRegion(secretKey, 0, crypto_sign_SECRETKEYBYTES, reinterpret_cast<jbyte*>(sk));
    
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray result = env->NewObjectArray(2, byteArrayClass, nullptr);
    env->SetObjectArrayElement(result, 0, publicKey);
    env->SetObjectArrayElement(result, 1, secretKey);
    
    purge_memory(sk, crypto_sign_SECRETKEYBYTES);
    return result;
}

// Module 1: X25519 Key Exchange
JNIEXPORT jobjectArray JNICALL
Java_com_btl_protocol_data_crypto_NativeCrypto_generateX25519KeyPair(JNIEnv *env, jobject thiz) {
    uint8_t pk[crypto_kx_PUBLICKEYBYTES];
    uint8_t sk[crypto_kx_SECRETKEYBYTES];
    
    crypto_kx_keypair(pk, sk);
    
    jbyteArray publicKey = env->NewByteArray(crypto_kx_PUBLICKEYBYTES);
    env->SetByteArrayRegion(publicKey, 0, crypto_kx_PUBLICKEYBYTES, reinterpret_cast<jbyte*>(pk));
    
    jbyteArray secretKey = env->NewByteArray(crypto_kx_SECRETKEYBYTES);
    env->SetByteArrayRegion(secretKey, 0, crypto_kx_SECRETKEYBYTES, reinterpret_cast<jbyte*>(sk));
    
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray result = env->NewObjectArray(2, byteArrayClass, nullptr);
    env->SetObjectArrayElement(result, 0, publicKey);
    env->SetObjectArrayElement(result, 1, secretKey);
    
    purge_memory(sk, crypto_kx_SECRETKEYBYTES);
    return result;
}

// Module 1: Complete AES-256-GCM authenticated encryption routines
JNIEXPORT jbyteArray JNICALL
Java_com_btl_protocol_data_crypto_NativeCrypto_aesGcmEncrypt(JNIEnv *env, jobject thiz, 
                                                             jbyteArray plaintext, jbyteArray key, jbyteArray iv) {
    jsize pt_len = env->GetArrayLength(plaintext);
    
    std::vector<uint8_t> pt_buf(pt_len);
    env->GetByteArrayRegion(plaintext, 0, pt_len, reinterpret_cast<jbyte*>(pt_buf.data()));
    
    uint8_t key_buf[crypto_aead_aes256gcm_KEYBYTES];
    env->GetByteArrayRegion(key, 0, crypto_aead_aes256gcm_KEYBYTES, reinterpret_cast<jbyte*>(key_buf));
    
    uint8_t iv_buf[crypto_aead_aes256gcm_NPUBBYTES];
    env->GetByteArrayRegion(iv, 0, crypto_aead_aes256gcm_NPUBBYTES, reinterpret_cast<jbyte*>(iv_buf));
    
    unsigned long long ct_len;
    std::vector<uint8_t> ct_buf(pt_len + crypto_aead_aes256gcm_ABYTES);
    
    crypto_aead_aes256gcm_encrypt(ct_buf.data(), &ct_len, pt_buf.data(), pt_len,
                                  nullptr, 0, nullptr, iv_buf, key_buf);
                                  
    jbyteArray ciphertext = env->NewByteArray(ct_len);
    env->SetByteArrayRegion(ciphertext, 0, ct_len, reinterpret_cast<jbyte*>(ct_buf.data()));
    
    // Explicitly zero-out sensitive buffers immediately
    purge_memory(key_buf, crypto_aead_aes256gcm_KEYBYTES);
    purge_memory(pt_buf.data(), pt_len);
    
    return ciphertext;
}

}

package com.btl.protocol.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class BtlPacket(
    val version: Byte = 1,
    val packetType: Byte,
    val sequenceNumber: Int,
    val senderHash: ByteArray, // 32 bytes
    val receiverHash: ByteArray, // 32 bytes
    var ttl: Byte,
    val encryptedPayload: ByteArray,
    var hmacSha256: ByteArray? = null // 32 bytes
) {
    companion object {
        const val MAGIC_BYTES: Short = 0x4254 // "BT"
        const val SENDER_HASH_LEN = 32
        const val RECEIVER_HASH_LEN = 32
        const val HMAC_LEN = 32
        const val HEADER_MIN_SIZE = 2 + 1 + 1 + 4 + SENDER_HASH_LEN + RECEIVER_HASH_LEN + 1 + 2 + HMAC_LEN

        fun deserialize(bytes: ByteArray): BtlPacket {
            require(bytes.size >= HEADER_MIN_SIZE) { "Packet too small" }
            
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.short
            require(magic == MAGIC_BYTES) { "Invalid Magic Bytes" }
            
            val version = buffer.get()
            val type = buffer.get()
            val seqNum = buffer.int
            
            val sHash = ByteArray(SENDER_HASH_LEN)
            buffer.get(sHash)
            
            val rHash = ByteArray(RECEIVER_HASH_LEN)
            buffer.get(rHash)
            
            val ttl = buffer.get()
            val payloadLen = buffer.short.toInt() and 0xFFFF
            
            require(buffer.remaining() >= payloadLen + HMAC_LEN) { "Invalid payload length" }
            
            val payload = ByteArray(payloadLen)
            buffer.get(payload)
            
            val hmac = ByteArray(HMAC_LEN)
            buffer.get(hmac)
            
            return BtlPacket(version, type, seqNum, sHash, rHash, ttl, payload, hmac)
        }
    }

    fun serialize(hmacKey: ByteArray): ByteArray {
        val payloadLen = encryptedPayload.size
        val totalSize = HEADER_MIN_SIZE - HMAC_LEN + payloadLen + HMAC_LEN
        
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(MAGIC_BYTES)
        buffer.put(version)
        buffer.put(packetType)
        buffer.putInt(sequenceNumber)
        
        require(senderHash.size == SENDER_HASH_LEN) { "Invalid Sender Hash length" }
        buffer.put(senderHash)
        
        require(receiverHash.size == RECEIVER_HASH_LEN) { "Invalid Receiver Hash length" }
        buffer.put(receiverHash)
        
        buffer.put(ttl)
        buffer.putShort(payloadLen.toShort())
        buffer.put(encryptedPayload)
        
        // Calculate HMAC over everything up to this point
        val dataToMac = buffer.array().copyOfRange(0, buffer.position())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        val calculatedHmac = mac.doFinal(dataToMac)
        
        this.hmacSha256 = calculatedHmac
        buffer.put(calculatedHmac)
        
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BtlPacket

        if (sequenceNumber != other.sequenceNumber) return false
        if (!senderHash.contentEquals(other.senderHash)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + senderHash.contentHashCode()
        return result
    }
}

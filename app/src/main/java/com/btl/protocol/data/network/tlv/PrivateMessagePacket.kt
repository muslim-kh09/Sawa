package com.btl.protocol.data.network.tlv

import java.nio.ByteBuffer

data class PrivateMessagePacket(
    val senderFingerprint: String,
    val receiverFingerprint: String,
    val messageId: String,
    val encryptedPayload: ByteArray
) : BtlPacket() {
    override val packetType = TlvSerializer.TYPE_PRIVATE_MESSAGE

    override fun toByteArray(): ByteArray {
        val senderBytes = senderFingerprint.toByteArray(Charsets.UTF_8)
        val receiverBytes = receiverFingerprint.toByteArray(Charsets.UTF_8)
        val msgIdBytes = messageId.toByteArray(Charsets.UTF_8)
        
        val payload = ByteBuffer.allocate(2 + senderBytes.size + 2 + receiverBytes.size + 2 + msgIdBytes.size + encryptedPayload.size)
            .putShort(senderBytes.size.toShort()).put(senderBytes)
            .putShort(receiverBytes.size.toShort()).put(receiverBytes)
            .putShort(msgIdBytes.size.toShort()).put(msgIdBytes)
            .put(encryptedPayload)
            .array()
            
        return TlvSerializer.encode(packetType, payload)
    }

    companion object {
        fun fromByteArray(data: ByteArray): PrivateMessagePacket? {
            val fields = TlvSerializer.decode(data)
            val field = fields.firstOrNull { it.type == TlvSerializer.TYPE_PRIVATE_MESSAGE } ?: return null
            val value = field.value
            
            val buffer = ByteBuffer.wrap(value)
            try {
                val senderLen = buffer.short.toInt()
                val senderBytes = ByteArray(senderLen)
                buffer.get(senderBytes)
                
                val receiverLen = buffer.short.toInt()
                val receiverBytes = ByteArray(receiverLen)
                buffer.get(receiverBytes)
                
                val msgIdLen = buffer.short.toInt()
                val msgIdBytes = ByteArray(msgIdLen)
                buffer.get(msgIdBytes)
                
                val encryptedPayload = ByteArray(buffer.remaining())
                buffer.get(encryptedPayload)
                
                return PrivateMessagePacket(
                    String(senderBytes, Charsets.UTF_8),
                    String(receiverBytes, Charsets.UTF_8),
                    String(msgIdBytes, Charsets.UTF_8),
                    encryptedPayload
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

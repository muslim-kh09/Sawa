package com.btl.protocol.data.network.tlv

import java.nio.ByteBuffer

data class PublicMessagePacket(
    val senderFingerprint: String,
    val messageId: String,
    val text: String,
    val signature: ByteArray
) : BtlPacket() {
    override val packetType = TlvSerializer.TYPE_PUBLIC_MESSAGE

    override fun toByteArray(): ByteArray {
        val senderBytes = senderFingerprint.toByteArray(Charsets.UTF_8)
        val msgIdBytes = messageId.toByteArray(Charsets.UTF_8)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        
        val payload = ByteBuffer.allocate(2 + senderBytes.size + 2 + msgIdBytes.size + 2 + textBytes.size + 64)
            .putShort(senderBytes.size.toShort()).put(senderBytes)
            .putShort(msgIdBytes.size.toShort()).put(msgIdBytes)
            .putShort(textBytes.size.toShort()).put(textBytes)
            .put(signature)
            .array()
            
        return TlvSerializer.encode(packetType, payload)
    }

    companion object {
        fun fromByteArray(data: ByteArray): PublicMessagePacket? {
            val fields = TlvSerializer.decode(data)
            val field = fields.firstOrNull { it.type == TlvSerializer.TYPE_PUBLIC_MESSAGE } ?: return null
            val value = field.value
            
            val buffer = ByteBuffer.wrap(value)
            try {
                val senderLen = buffer.short.toInt()
                val senderBytes = ByteArray(senderLen)
                buffer.get(senderBytes)
                
                val msgIdLen = buffer.short.toInt()
                val msgIdBytes = ByteArray(msgIdLen)
                buffer.get(msgIdBytes)
                
                val textLen = buffer.short.toInt()
                val textBytes = ByteArray(textLen)
                buffer.get(textBytes)
                
                val signature = ByteArray(64)
                buffer.get(signature)
                
                return PublicMessagePacket(
                    String(senderBytes, Charsets.UTF_8),
                    String(msgIdBytes, Charsets.UTF_8),
                    String(textBytes, Charsets.UTF_8),
                    signature
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}

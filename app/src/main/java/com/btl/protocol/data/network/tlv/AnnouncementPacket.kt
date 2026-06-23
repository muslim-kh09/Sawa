package com.btl.protocol.data.network.tlv

import java.nio.ByteBuffer

data class AnnouncementPacket(
    val ed25519PublicKey: ByteArray,
    val x25519PublicKey: ByteArray,
    val displayName: String
) : BtlPacket() {
    override val packetType = TlvSerializer.TYPE_ANNOUNCEMENT

    override fun toByteArray(): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(32 + 32 + nameBytes.size)
            .put(ed25519PublicKey)
            .put(x25519PublicKey)
            .put(nameBytes)
            .array()
        return TlvSerializer.encode(packetType, payload)
    }

    companion object {
        fun fromByteArray(data: ByteArray): AnnouncementPacket? {
            val fields = TlvSerializer.decode(data)
            val field = fields.firstOrNull { it.type == TlvSerializer.TYPE_ANNOUNCEMENT } ?: return null
            val value = field.value
            if (value.size < 64) return null
            
            val ed25519PublicKey = value.copyOfRange(0, 32)
            val x25519PublicKey = value.copyOfRange(32, 64)
            val displayName = String(value, 64, value.size - 64, Charsets.UTF_8)
            
            return AnnouncementPacket(ed25519PublicKey, x25519PublicKey, displayName)
        }
    }
}

package com.btl.protocol.data.network.tlv

abstract class BtlPacket {
    abstract val packetType: Byte
    abstract fun toByteArray(): ByteArray

    companion object {
        fun fromByteArray(data: ByteArray): BtlPacket? {
            if (data.isEmpty()) return null
            return when (data[0]) {
                TlvSerializer.TYPE_ANNOUNCEMENT -> AnnouncementPacket.fromByteArray(data)
                TlvSerializer.TYPE_PUBLIC_MESSAGE -> PublicMessagePacket.fromByteArray(data)
                TlvSerializer.TYPE_PRIVATE_MESSAGE -> PrivateMessagePacket.fromByteArray(data)
                else -> null
            }
        }
    }
}

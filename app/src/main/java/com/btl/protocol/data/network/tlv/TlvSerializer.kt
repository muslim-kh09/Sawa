package com.btl.protocol.data.network.tlv

import java.nio.ByteBuffer

object TlvSerializer {
    const val TYPE_ANNOUNCEMENT: Byte = 0x01
    const val TYPE_PUBLIC_MESSAGE: Byte = 0x02
    const val TYPE_PRIVATE_MESSAGE: Byte = 0x03

    fun encode(type: Byte, value: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(1 + 2 + value.size)
        buffer.put(type)
        buffer.putShort(value.size.toShort())
        buffer.put(value)
        return buffer.array()
    }

    fun decode(data: ByteArray): List<TlvField> {
        val fields = mutableListOf<TlvField>()
        val buffer = ByteBuffer.wrap(data)
        while (buffer.remaining() >= 3) {
            val type = buffer.get()
            val length = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < length) break
            val value = ByteArray(length)
            buffer.get(value)
            fields.add(TlvField(type, value))
        }
        return fields
    }
}

data class TlvField(val type: Byte, val value: ByteArray)

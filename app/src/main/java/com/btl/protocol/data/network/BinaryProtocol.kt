package com.btl.protocol.data.network

import java.nio.ByteBuffer
import java.util.zip.Deflater
import java.util.zip.Inflater

object BinaryProtocol {
    const val VERSION: Byte = 0x0B
    const val HEADER_SIZE = 14
    const val SENDER_ID_SIZE = 8
    
    object Flags {
        const val IS_COMPRESSED: Byte = 0x04
        const val IS_FRAGMENT_START: Byte = 0x10
        const val IS_FRAGMENT_CONTINUE: Byte = 0x20
        const val IS_FRAGMENT_END: Byte = 0x40
    }

    data class Packet(
        val type: Byte,
        val ttl: Byte,
        val timestamp: Long,
        val flags: Byte,
        val senderId: ByteArray,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Packet
            if (type != other.type) return false
            if (ttl != other.ttl) return false
            if (timestamp != other.timestamp) return false
            if (flags != other.flags) return false
            if (!senderId.contentEquals(other.senderId)) return false
            if (!payload.contentEquals(other.payload)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = type.toInt()
            result = 31 * result + ttl
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + flags
            result = 31 * result + senderId.contentHashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun encode(packet: Packet): ByteArray {
        var payload = packet.payload
        var flags = packet.flags
        var originalSize = 0
        
        if (payload.size > 256) {
            val compressed = compress(payload)
            if (compressed.size < payload.size) {
                originalSize = payload.size
                payload = compressed
                flags = (flags.toInt() or Flags.IS_COMPRESSED.toInt()).toByte()
            }
        }
        
        val isCompressed = (flags.toInt() and Flags.IS_COMPRESSED.toInt()) != 0
        val payloadLen = payload.size + if (isCompressed) 2 else 0
        
        val buffer = ByteBuffer.allocate(HEADER_SIZE + SENDER_ID_SIZE + payload.size + (if (isCompressed) 2 else 0))
        buffer.put(VERSION)
        buffer.put(packet.type)
        buffer.put(packet.ttl)
        buffer.putLong(packet.timestamp)
        buffer.put(flags)
        buffer.putShort(payloadLen.toShort())
        
        val senderPadded = ByteArray(SENDER_ID_SIZE)
        System.arraycopy(packet.senderId, 0, senderPadded, 0, minOf(packet.senderId.size, SENDER_ID_SIZE))
        buffer.put(senderPadded)
        
        if (isCompressed) {
            buffer.putShort(originalSize.toShort())
        }
        buffer.put(payload)
        
        return buffer.array()
    }
    
    fun decode(data: ByteArray): Packet? {
        try {
            val buffer = ByteBuffer.wrap(data)
            if (buffer.remaining() < HEADER_SIZE + SENDER_ID_SIZE) return null
            
            val version = buffer.get()
            if (version != VERSION) return null
            val type = buffer.get()
            val ttl = buffer.get()
            val timestamp = buffer.getLong()
            val flags = buffer.get()
            val payloadLen = buffer.short.toInt() and 0xFFFF
            
            val senderId = ByteArray(SENDER_ID_SIZE)
            buffer.get(senderId)
            
            val isCompressed = (flags.toInt() and Flags.IS_COMPRESSED.toInt()) != 0
            
            val payload = if (isCompressed) {
                if (buffer.remaining() < 2) return null
                val originalSize = buffer.short.toInt() and 0xFFFF
                val compressedPayload = ByteArray(payloadLen - 2)
                if (buffer.remaining() < compressedPayload.size) return null
                buffer.get(compressedPayload)
                decompress(compressedPayload, originalSize)
            } else {
                val rawPayload = ByteArray(payloadLen)
                if (buffer.remaining() < rawPayload.size) return null
                buffer.get(rawPayload)
                rawPayload
            }
            
            return Packet(type, ttl, timestamp, flags, senderId, payload)
        } catch (e: Exception) {
            return null
        }
    }
    
    private fun compress(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val buffer = ByteArray(data.size)
        val size = deflater.deflate(buffer)
        deflater.end()
        return buffer.copyOf(size)
    }

    private fun decompress(data: ByteArray, originalSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = ByteArray(originalSize)
        try {
            inflater.inflate(buffer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        inflater.end()
        return buffer
    }
}

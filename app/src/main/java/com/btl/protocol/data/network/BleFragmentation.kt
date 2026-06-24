package com.btl.protocol.data.network

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object BleFragmentation {
    const val MAX_MTU_PAYLOAD = 400

    data class Chunk(
        val messageId: String,
        val totalSize: Int,
        val offset: Int,
        val data: ByteArray
    ) {
        fun toByteArray(): ByteArray {
            val idBytes = messageId.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(4 + 4 + 4 + idBytes.size + data.size)
            buffer.putInt(idBytes.size)
            buffer.put(idBytes)
            buffer.putInt(totalSize)
            buffer.putInt(offset)
            buffer.put(data)
            return buffer.array()
        }

        companion object {
            fun fromByteArray(bytes: ByteArray): Chunk? {
                try {
                    val buffer = ByteBuffer.wrap(bytes)
                    val idLen = buffer.int
                    if (idLen <= 0 || idLen > 100) return null
                    val idBytes = ByteArray(idLen)
                    buffer.get(idBytes)
                    val messageId = String(idBytes, Charsets.UTF_8)
                    val totalSize = buffer.int
                    val offset = buffer.int
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    return Chunk(messageId, totalSize, offset, data)
                } catch (e: Exception) {
                    return null
                }
            }
        }
    }

    fun planOutbound(messageId: String, payload: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + MAX_MTU_PAYLOAD, payload.size)
            val chunkData = payload.copyOfRange(offset, end)
            chunks.add(Chunk(messageId, payload.size, offset, chunkData))
            offset = end
        }
        return chunks
    }
}

class InboundWriteBuffer {
    private val buffers = ConcurrentHashMap<String, ByteArray>()
    private val receivedBytes = ConcurrentHashMap<String, AtomicInteger>()

    fun appendChunk(chunk: BleFragmentation.Chunk): ByteArray? {
        buffers.putIfAbsent(chunk.messageId, ByteArray(chunk.totalSize))
        receivedBytes.putIfAbsent(chunk.messageId, AtomicInteger(0))

        val buffer = buffers[chunk.messageId] ?: return null
        val received = receivedBytes[chunk.messageId] ?: return null

        if (chunk.offset + chunk.data.size <= buffer.size) {
            System.arraycopy(chunk.data, 0, buffer, chunk.offset, chunk.data.size)
            val currentReceived = received.addAndGet(chunk.data.size)
            
            if (currentReceived >= chunk.totalSize) {
                buffers.remove(chunk.messageId)
                receivedBytes.remove(chunk.messageId)
                return buffer
            }
        }
        return null
    }
}

package com.btl.protocol.data.network

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles fragmentation and reassembly of BLE payloads that exceed the negotiated MTU.
 *
 * ## Wire Format — 9-byte fragment header:
 * ┌─────────┬──────────────┬───────────────┬───────────────┬────────────┐
 * │  Magic  │    msgId     │   fragIndex   │   fragTotal   │   data...  │
 * │ 1 byte  │   4 bytes    │    2 bytes    │    2 bytes    │  variable  │
 * │  F6/F7  │  (Big-Endian)│  (0-indexed)  │ (max 65535)   │            │
 * └─────────┴──────────────┴───────────────┴───────────────┴────────────┘
 */
object PacketFragmenter {

    const val FRAG_CONTINUE: Byte = 0xF6.toByte()
    const val FRAG_END: Byte = 0xF7.toByte()

    const val HEADER_SIZE = 9

    private val msgIdGen = AtomicInteger(0)

    private class BufferState(val slots: Array<ByteArray?>) {
        var lastUpdated: Long = System.currentTimeMillis()
    }

    private val assemblyBuffer = ConcurrentHashMap<String, BufferState>()
    private const val BUFFER_TIMEOUT_MS = 30_000L

    private fun pruneStaleBuffers() {
        val now = System.currentTimeMillis()
        val staleKeys = assemblyBuffer.filter { now - it.value.lastUpdated > BUFFER_TIMEOUT_MS }.keys
        staleKeys.forEach { assemblyBuffer.remove(it) }
    }

    fun fragment(payload: ByteArray, maxBytesPerFragment: Int): List<ByteArray> {
        val chunkSize = maxBytesPerFragment - HEADER_SIZE
        require(chunkSize > 0) { "maxBytesPerFragment ($maxBytesPerFragment) is too small; must be > $HEADER_SIZE" }

        val msgId = msgIdGen.incrementAndGet()

        val chunks: List<ByteArray> = if (payload.size <= chunkSize) {
            listOf(payload)
        } else {
            payload.toList().chunked(chunkSize).map { it.toByteArray() }
        }

        val total = chunks.size.coerceAtMost(65535).toShort()

        return chunks.mapIndexed { index, chunk ->
            val magic = if (index == total.toInt() - 1) FRAG_END else FRAG_CONTINUE
            ByteBuffer.allocate(HEADER_SIZE + chunk.size).apply {
                put(magic)
                putInt(msgId)
                putShort(index.toShort())
                putShort(total)
                put(chunk)
            }.array()
        }
    }

    fun reassemble(senderAddress: String, data: ByteArray): ByteArray? {
        pruneStaleBuffers()

        if (data.isEmpty()) return null

        val magic = data[0]
        if (magic != FRAG_CONTINUE && magic != FRAG_END) return data

        if (data.size < HEADER_SIZE) return null

        val buf = ByteBuffer.wrap(data)
        buf.get() // consume magic byte
        val msgId = buf.int
        val fragIndex = buf.short.toInt() and 0xFFFF
        val fragTotal = buf.short.toInt() and 0xFFFF
        val chunk = ByteArray(buf.remaining()).also { buf.get(it) }

        val key = "$senderAddress-$msgId"
        val state = assemblyBuffer.getOrPut(key) { BufferState(arrayOfNulls(fragTotal)) }
        state.lastUpdated = System.currentTimeMillis()

        if (fragIndex < state.slots.size) {
            state.slots[fragIndex] = chunk
        }

        return if (state.slots.none { it == null }) {
            assemblyBuffer.remove(key)
            state.slots.filterNotNull().fold(ByteArray(0)) { acc, b -> acc + b }
        } else {
            null
        }
    }

    fun clearAddress(senderAddress: String) {
        assemblyBuffer.keys
            .filter { it.startsWith("$senderAddress-") }
            .forEach { assemblyBuffer.remove(it) }
    }
}

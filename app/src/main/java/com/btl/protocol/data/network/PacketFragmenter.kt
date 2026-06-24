package com.btl.protocol.data.network

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles fragmentation and reassembly of BLE payloads that exceed the negotiated MTU.
 *
 * ## Wire Format — 9-byte fragment header:
 * ```
 * ┌─────────┬──────────────┬───────────────┬───────────────┬────────────┐
 * │  Magic  │    msgId     │   fragIndex   │   fragTotal   │   data...  │
 * │ 1 byte  │   4 bytes    │    2 bytes    │    2 bytes    │  variable  │
 * │  0xF5   │  (Big-Endian)│  (0-indexed)  │ (max 65535)   │            │
 * └─────────┴──────────────┴───────────────┴───────────────┴────────────┘
 * ```
 */
object PacketFragmenter {

    /** Magic byte that identifies a fragmented packet. */
    private const val FRAG_MAGIC: Byte = 0xF5.toByte()

    /** Total bytes consumed by the fragment header. */
    const val HEADER_SIZE = 9  // 1 magic + 4 msgId + 2 fragIndex + 2 fragTotal

    /** Monotonically increasing message ID generator — wraps on Int overflow (that's fine). */
    private val msgIdGen = AtomicInteger(0)

    /**
     * Key = "$senderAddress-$msgId"
     * Value = nullable array of chunks; null slot = not yet received.
     */
    private val assemblyBuffer = ConcurrentHashMap<String, Array<ByteArray?>>()

    // ──────────────────────────────────────────────────────────────────────────
    // Fragmentation
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Splits [payload] into a list of framed byte arrays, each ≤ [maxBytesPerFragment].
     *
     * @param payload             The raw bytes to send.
     * @param maxBytesPerFragment The maximum bytes per fragment including header.
     *                            Must be > [HEADER_SIZE].
     */
    fun fragment(payload: ByteArray, maxBytesPerFragment: Int): List<ByteArray> {
        val chunkSize = maxBytesPerFragment - HEADER_SIZE
        require(chunkSize > 0) {
            "maxBytesPerFragment ($maxBytesPerFragment) is too small; must be > $HEADER_SIZE"
        }

        val msgId = msgIdGen.incrementAndGet()

        // Split payload into raw chunks
        val chunks: List<ByteArray> = if (payload.size <= chunkSize) {
            listOf(payload)
        } else {
            payload.toList().chunked(chunkSize).map { it.toByteArray() }
        }

        val total = chunks.size.coerceAtMost(65535).toShort()

        return chunks.mapIndexed { index, chunk ->
            ByteBuffer.allocate(HEADER_SIZE + chunk.size).apply {
                put(FRAG_MAGIC)
                putInt(msgId)
                putShort(index.toShort())
                putShort(total)
                put(chunk)
            }.array()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Reassembly
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Feeds a raw incoming byte array for a given [senderAddress].
     *
     * - If [data] has no magic byte, it is returned immediately (legacy / non-fragmented).
     * - Otherwise, the fragment is buffered. When all fragments of a message arrive,
     *   the fully assembled payload is returned.
     * - Returns `null` while still waiting for more fragments.
     */
    fun reassemble(senderAddress: String, data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null

        // Not a fragmented packet — return as-is
        if (data[0] != FRAG_MAGIC) return data

        if (data.size < HEADER_SIZE) {
            return null  // Malformed header — discard
        }

        val buf = ByteBuffer.wrap(data)
        buf.get()                                        // consume magic byte
        val msgId = buf.int
        val fragIndex = buf.short.toInt() and 0xFFFF
        val fragTotal = buf.short.toInt() and 0xFFFF
        val chunk = ByteArray(buf.remaining()).also { buf.get(it) }

        val key = "$senderAddress-$msgId"
        val slots = assemblyBuffer.getOrPut(key) { arrayOfNulls(fragTotal) }

        if (fragIndex < slots.size) {
            slots[fragIndex] = chunk
        }

        // Check if all fragments have arrived
        return if (slots.none { it == null }) {
            assemblyBuffer.remove(key)
            slots.filterNotNull().fold(ByteArray(0)) { acc, b -> acc + b }
        } else {
            null  // Still waiting for more fragments
        }
    }

    /**
     * Clears all pending reassembly state for a given [senderAddress].
     * Call when a peer disconnects to free memory.
     */
    fun clearAddress(senderAddress: String) {
        assemblyBuffer.keys
            .filter { it.startsWith("$senderAddress-") }
            .forEach { assemblyBuffer.remove(it) }
    }
}

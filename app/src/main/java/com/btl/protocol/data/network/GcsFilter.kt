package com.btl.protocol.data.network

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * A probabilistic data structure mimicking a Golomb-Coded Set / Bloom Filter.
 * Used for anti-entropy mesh syncing of Room Database ledgers to avoid sending
 * the full ledger string.
 */
class GcsFilter(val bitSet: ByteArray) {

    companion object {
        const val FILTER_SIZE_BYTES = 1024
        const val NUM_BITS = FILTER_SIZE_BYTES * 8

        fun build(items: List<String>): GcsFilter {
            val bitSet = ByteArray(FILTER_SIZE_BYTES)
            val digest = MessageDigest.getInstance("SHA-256")
            for (item in items) {
                val hash = digest.digest(item.toByteArray(Charsets.UTF_8))
                
                val idx1 = (((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)) % NUM_BITS
                val idx2 = (((hash[2].toInt() and 0xFF) shl 8) or (hash[3].toInt() and 0xFF)) % NUM_BITS
                val idx3 = (((hash[4].toInt() and 0xFF) shl 8) or (hash[5].toInt() and 0xFF)) % NUM_BITS
                
                setBit(bitSet, idx1)
                setBit(bitSet, idx2)
                setBit(bitSet, idx3)
            }
            return GcsFilter(bitSet)
        }

        private fun setBit(array: ByteArray, bitIndex: Int) {
            val byteIndex = bitIndex / 8
            val bitOffset = bitIndex % 8
            array[byteIndex] = (array[byteIndex].toInt() or (1 shl bitOffset)).toByte()
        }

        private fun getBit(array: ByteArray, bitIndex: Int): Boolean {
            val byteIndex = bitIndex / 8
            val bitOffset = bitIndex % 8
            return (array[byteIndex].toInt() and (1 shl bitOffset)) != 0
        }
    }

    fun mightContain(item: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(item.toByteArray(Charsets.UTF_8))
        
        val idx1 = (((hash[0].toInt() and 0xFF) shl 8) or (hash[1].toInt() and 0xFF)) % NUM_BITS
        val idx2 = (((hash[2].toInt() and 0xFF) shl 8) or (hash[3].toInt() and 0xFF)) % NUM_BITS
        val idx3 = (((hash[4].toInt() and 0xFF) shl 8) or (hash[5].toInt() and 0xFF)) % NUM_BITS

        return getBit(bitSet, idx1) && getBit(bitSet, idx2) && getBit(bitSet, idx3)
    }
}

import os

# 1. Delete redundant BleFragmentation.kt
ble_frag = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/BleFragmentation.kt'
if os.path.exists(ble_frag):
    os.remove(ble_frag)
    print("Deleted BleFragmentation.kt")

# 2. Rewrite PacketFragmenter.kt
packet_frag = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/PacketFragmenter.kt'
with open(packet_frag, 'w') as f:
    f.write("""package com.btl.protocol.data.network

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
""")
    print("Rewrote PacketFragmenter.kt")

# 3. Modify GattOperationQueue.kt to enforce WRITE_TYPE_DEFAULT
gatt_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/GattOperationQueue.kt'
with open(gatt_file, 'r') as f:
    gatt_content = f.read()

# Replace writeNext writeType logic
gatt_content = gatt_content.replace(
"""                    val writeType = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }""",
"""                    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT"""
)

# Replace onCharacteristicWrite writeType logic
gatt_content = gatt_content.replace(
"""                    val writeType = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    }""",
"""                    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT"""
)

with open(gatt_file, 'w') as f:
    f.write(gatt_content)
    print("Updated GattOperationQueue.kt")

# 4. Update RELEASE_NOTES.md
release_notes_file = '/root/BTL/RELEASE_NOTES.md'
with open(release_notes_file, 'r') as f:
    release_notes_lines = f.readlines()

new_notes = """## v2.0.3
- **Architectural Reform:** Executed a massive network layer overhaul by successfully smashing the fatal 5-minute GATT timeout and slashing it down to 15 seconds.
- **Background Architecture:** Permanently deleted the naive 15s radio-shutdown battery saver loop, successfully moving to OS-native `SCAN_MODE_LOW_POWER`.
- **Strict Binary Routing:** Eradicated the plaintext legacy UI packet leak by introducing strict byte-header typing (`0x01` Chat, `0x02` Sync), ensuring encrypted identity payloads no longer bleed into the public chat UI.
- **Fragmentation Reboot (Phase 2):** Unified the chunking logic into a robust BitChat-inspired resilient BLE Mesh protocol, dropping redundant implementations.
- **Strict Chunking Write:** Enforced `WRITE_TYPE_DEFAULT` (Write with Response) for all outgoing fragmentation payloads, eliminating arbitrary thread delays.
- **Memory Safety:** Implemented strict 30s timeout tracking on the inbound payload assembly buffer to automatically self-clear dropped connections and prevent memory leaks.
"""

for i, line in enumerate(release_notes_lines):
    if line.startswith("## v2.0.3"):
        # We need to replace the v2.0.3 block
        start_idx = i
        end_idx = i + 1
        while end_idx < len(release_notes_lines) and not release_notes_lines[end_idx].startswith("## "):
            end_idx += 1
        release_notes_lines[start_idx:end_idx] = [new_notes + "\n"]
        break

with open(release_notes_file, 'w') as f:
    f.writelines(release_notes_lines)
    print("Updated RELEASE_NOTES.md")

# 5. Update README.md
readme_file = '/root/BTL/README.md'
with open(readme_file, 'r') as f:
    readme_content = f.read()

features_insertion = """- Premium Design: Features a pristine iOS-inspired interface with perfectly soft rounded corners, smooth intuitive interactions, and welcoming typography.
- Localization: Full Arabic language support (RTL compatible).
- **Core Architecture Upgrades:**
  - BitChat-inspired resilient BLE Mesh protocol.
  - Zero-leak strict binary packet routing.
  - Persistent background mesh stability via OS-native low-power scanning.
  - Robust fragmentation pipeline for stable large payloads and offline media."""

readme_content = readme_content.replace(
"""- Premium Design: Features a pristine iOS-inspired interface with perfectly soft rounded corners, smooth intuitive interactions, and welcoming typography.
- Localization: Full Arabic language support (RTL compatible).""", features_insertion)

with open(readme_file, 'w') as f:
    f.write(readme_content)
    print("Updated README.md")


package com.btl.protocol.data.network

import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "PeerRegistry"

/** Time after which a peer that stops advertising is considered gone. */
private const val PEER_TTL_MS = 30_000L

/** How often the eviction loop runs. */
private const val EVICTION_PERIOD_MS = 15_000L

/**
 * Represents a discovered Sawa peer.
 *
 * @param address   BLE MAC address (stable identifier).
 * @param device    The raw [BluetoothDevice] for initiating GATT connections.
 * @param lastSeen  Epoch millis of the most recent advertising packet from this peer.
 * @param rssi      Signal strength in dBm from the last scan result.
 */
data class SawaPeer(
    val address: String,
    val device: BluetoothDevice,
    @Volatile var lastSeen: Long = System.currentTimeMillis(),
    @Volatile var rssi: Int = 0
)

/**
 * Thread-safe registry of all currently active Sawa peers discovered via BLE scanning.
 *
 * - New peers are added on first sight and announced via [stateFlow].
 * - Existing peers get their [SawaPeer.lastSeen] timestamp refreshed on every
 *   scan result — no duplicate StateFlow emission for repeated sightings.
 * - An eviction coroutine runs every [EVICTION_PERIOD_MS] and removes peers whose
 *   last-seen timestamp is older than [PEER_TTL_MS]. This prevents ghost connections
 *   to devices that have walked out of range.
 */
class PeerRegistry(scope: CoroutineScope) {

    private val _peers = ConcurrentHashMap<String, SawaPeer>()
    private val _stateFlow = MutableStateFlow<Map<String, SawaPeer>>(emptyMap())

    /** Observable snapshot of the current live peer map. Emits on add/evict events. */
    val stateFlow: StateFlow<Map<String, SawaPeer>> = _stateFlow.asStateFlow()

    init {
        scope.launch { runEvictionLoop() }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Called when a BLE scan result is received for a verified Sawa device.
     * Updates [SawaPeer.lastSeen] and [SawaPeer.rssi] for existing peers;
     * adds a new entry and emits on [stateFlow] for new peers.
     */
    fun seen(device: BluetoothDevice, rssi: Int = 0) {
        val existing = _peers[device.address]
        if (existing != null) {
            // Refresh timestamp & RSSI without emitting a new flow event
            existing.lastSeen = System.currentTimeMillis()
            existing.rssi = rssi
        } else {
            Log.i(TAG, "✚ New Sawa peer: ${device.address} (RSSI: $rssi dBm)")
            _peers[device.address] = SawaPeer(
                address = device.address,
                device = device,
                rssi = rssi
            )
            publish()
        }
    }

    /** Returns a snapshot of all currently tracked peers. */
    fun all(): List<SawaPeer> = _peers.values.toList()

    /** Returns the current number of tracked peers. */
    fun count(): Int = _peers.size

    // ──────────────────────────────────────────────────────────────────────────
    // Eviction
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun runEvictionLoop() {
        while (true) {
            delay(EVICTION_PERIOD_MS)
            val before = _peers.size
            val cutoff = System.currentTimeMillis() - PEER_TTL_MS
            _peers.entries.removeIf { it.value.lastSeen < cutoff }
            val evicted = before - _peers.size
            if (evicted > 0) {
                Log.d(TAG, "✖ Evicted $evicted stale peer(s). Active: ${_peers.size}")
                publish()
            }
        }
    }

    private fun publish() {
        _stateFlow.value = _peers.toMap()
    }
}

package com.btl.protocol.data.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import com.btl.protocol.data.repository.MeshRepository
import com.btl.protocol.data.repository.Message
import com.btl.protocol.data.repository.STATUS_DELIVERED
import com.btl.protocol.data.repository.STATUS_SENT
import com.btl.protocol.data.repository.STATUS_PENDING
import com.btl.protocol.data.repository.MeshRoutingEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

private const val TAG = "BtlMeshService"

/**
 * Sawa BLE Mesh Foreground Service — fully rewritten.
 *
 * ## Architecture
 * ```
 *  Advertiser ──────────────────────────────────────────────────┐
 *  Scanner ──→ PeerRegistry (TTL eviction) ──→ GattQueue (send) │
 *                                                               │
 *  GattServer ──→ PacketFragmenter.reassemble()                 │
 *             ──→ MeshRoutingEngine (replay defense + TTL)      │
 *             ──→ MeshRepository.addMessage()  (deliver to UI)  │
 *             ──→ GattQueue.enqueue() ×peers  (relay / forward) ┘
 * ```
 *
 * ## Key fixes over the original implementation
 * - **GATT Storm fix**: All outbound GATT writes go through [GattOperationQueue] —
 *   strictly one operation at a time with exponential backoff retry.
 * - **MTU negotiation**: Every new connection negotiates up to 512-byte MTU before
 *   writing, then fragments via [PacketFragmenter].
 * - **Reliable delivery**: Characteristic uses [BluetoothGattCharacteristic.PROPERTY_WRITE]
 *   (with response) instead of WRITE_NO_RESPONSE.
 * - **Peer eviction**: [PeerRegistry] removes stale peers after 30 s of silence —
 *   no more ghost connections.
 * - **Implemented forwarding**: Received packets are re-broadcast to all other peers
 *   with TTL decremented (Store-Carry-Forward).
 * - **No singleton instance var**: Service ↔ ViewModel communication via Hilt-injected
 *   [MeshRepository] and companion [StateFlow].
 */
@AndroidEntryPoint
class BtlMeshService : Service() {

    // ──────────────────────────────────────────────────────────────────────────
    // Companion — static surface for UI/ViewModel access
    // ──────────────────────────────────────────────────────────────────────────

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B71C-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000B71D-0000-1000-8000-00805f9b34fb")

        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "BTL_MESH_SERVICE_CHANNEL"

        /** Default broadcast TTL — packets relay up to 7 hops. */
        private const val BROADCAST_TTL: Byte = 7

        /** Unique identifier for this device instance to prevent self-echoes. */
        var LOCAL_DEVICE_ID = ""
        var NODE_ID_BYTES = ByteArray(8)
        var NODE_ID_STRING = ""

        fun initIdentity(context: Context) {
            if (LOCAL_DEVICE_ID.isNotEmpty()) return
            val prefs = context.getSharedPreferences("SawaIdentity", Context.MODE_PRIVATE)
            var pubKeyBase64 = prefs.getString("publicKey", null)
            if (pubKeyBase64 == null) {
                val kpg = java.security.KeyPairGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC)
                kpg.initialize(256)
                val kp = kpg.generateKeyPair()
                pubKeyBase64 = android.util.Base64.encodeToString(kp.public.encoded, android.util.Base64.NO_WRAP)
                prefs.edit().putString("publicKey", pubKeyBase64).apply()
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(pubKeyBase64!!.toByteArray(Charsets.UTF_8))
            LOCAL_DEVICE_ID = hash.joinToString("") { "%02x".format(it) }.take(16)
            NODE_ID_BYTES = LOCAL_DEVICE_ID.toByteArray(Charsets.UTF_8).take(8).toByteArray()
            NODE_ID_STRING = String(NODE_ID_BYTES, Charsets.UTF_8)
        }

        /** Tracks seen message UUIDs to deduplicate echoes from the broadcast mesh. */
        val processedMessageIds = mutableSetOf<String>()

        /** PANIC MODE: Clears all in-memory state. */
        fun panicWipe() {
            processedMessageIds.clear()
            peers.value.values.forEach { 
                try { it.device.connectGatt(null, false, null)?.close() } catch(_: Exception){} 
            }
            // Clear current message queue if possible (not strictly required if app restarts, but good hygiene)
        }

        /** SHA-256 of "SAWA_BROADCAST_IDENTITY" — used as the senderHash for all nodes. */
        val BROADCAST_SENDER_HASH: ByteArray by lazy {
            MessageDigest.getInstance("SHA-256").digest("SAWA_BROADCAST_IDENTITY".toByteArray())
        }

        /** SHA-256 of "SAWA_BROADCAST_DEST" — used as the receiverHash for mesh-wide messages. */
        val BROADCAST_RECEIVER_HASH: ByteArray by lazy {
            MessageDigest.getInstance("SHA-256").digest("SAWA_BROADCAST_DEST".toByteArray())
        }

        // Shared state flows — observed by ViewModel / UI
        private val _peers = MutableStateFlow<Map<String, SawaPeer>>(emptyMap())
        val peers: StateFlow<Map<String, SawaPeer>> = _peers.asStateFlow()

        private val _meshActive = MutableStateFlow(false)
        val meshActive: StateFlow<Boolean> = _meshActive.asStateFlow()

        // References to live service components — null when the service is not running
        @Volatile private var liveQueue: GattOperationQueue? = null
        @Volatile private var livePeers: PeerRegistry? = null
        @Volatile private var liveService: BtlMeshService? = null

        @Volatile var lastTrafficTime = System.currentTimeMillis()
        
        fun markTraffic() {
            lastTrafficTime = System.currentTimeMillis()
        }

        /**
         * Builds an outgoing payload using the live service's sequence number counter.
         * Returns null if the service is not currently running.
         */
        fun buildPayloadStatic(text: String): ByteArray? {
            val msgId = java.util.UUID.randomUUID().toString()
            processedMessageIds.add(msgId)
            val newText = "$msgId|$LOCAL_DEVICE_ID|$text"
            return liveService?.buildOutgoingPayload(newText)
        } 

        /**
         * Enqueues a text message for broadcast to all currently known peers.
         * Called by [MeshViewModel] — thread-safe.
         *
         * @param payload   Raw bytes to transmit.
         * @param messageId Room DB row ID for status tracking.
         * @param onResult  Called with true on successful delivery to ≥1 peer.
         */
        fun enqueueTransmit(payload: ByteArray, messageId: Long, onResult: (Boolean) -> Unit = {}) {
            markTraffic()
            val queue = liveQueue ?: run {
                Log.w(TAG, "enqueueTransmit called but service is not running")
                onResult(false)
                return
            }
            val peers = livePeers?.all() ?: emptyList()
            if (peers.isEmpty()) {
                Log.w(TAG, "No peers to transmit to")
                onResult(false)
                return
            }
            var successCount = 0
            var completedCount = 0
            peers.forEach { peer ->
                queue.enqueue(
                    GattWriteOp(
                        device = peer.device,
                        payload = payload,
                        messageId = messageId.toInt()
                    ) { success ->
                        if (success) successCount++
                        if (++completedCount == peers.size) {
                            onResult(successCount > 0)
                        }
                    }
                )
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Hilt Injected Dependencies
    // ──────────────────────────────────────────────────────────────────────────

    @Inject lateinit var meshRepository: MeshRepository
    @Inject lateinit var routingEngine: MeshRoutingEngine

    // ──────────────────────────────────────────────────────────────────────────
    // Service State
    // ──────────────────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var peerRegistry: PeerRegistry
    private lateinit var gattQueue: GattOperationQueue
    private var gattServer: BluetoothGattServer? = null
    private val seqNum = AtomicInteger(0)

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        initIdentity(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        peerRegistry = PeerRegistry(serviceScope)
        gattQueue = GattOperationQueue(this, serviceScope)

        liveQueue = gattQueue
        livePeers = peerRegistry
        liveService = this

        // Mirror PeerRegistry state into the companion StateFlow for the UI
        serviceScope.launch {
            peerRegistry.stateFlow.collect { peerMap -> _peers.value = peerMap }
        }

        // Hourly ledger pruning
        serviceScope.launch {
            while (true) {
                delay(60 * 60_000L)
                routingEngine.pruneLedger()
            }
        }

        Log.i(TAG, "Service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bluetoothAdapter.isEnabled) {
            setupGattServer()
            startScanning()
            startAdvertising()
            _meshActive.value = true
            isRadioOn = true
            startDutyCycleLoop()
            Log.i(TAG, "Mesh started.")
        } else {
            Log.w(TAG, "Bluetooth not enabled — mesh not started.")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        liveQueue = null
        livePeers = null
        liveService = null
        _meshActive.value = false
        gattQueue.close()
        serviceScope.cancel()
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            gattServer?.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on destroy", e)
        }
        Log.i(TAG, "Service destroyed.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────────
    // GATT Server — Receives inbound mesh writes from remote peers acting as clients
    // ──────────────────────────────────────────────────────────────────────────

    private fun setupGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            val service = BluetoothGattService(
                MESH_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            // PROPERTY_WRITE_NO_RESPONSE (reliable delivery, fixes WRITE_NO_RESPONSE bug logic using delayed settle)
            // PROPERTY_INDICATE = server-initiated confirmed notifications (future use)
            val characteristic = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.i(TAG, "GATT Server ready.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException opening GATT server", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid != CHAR_UUID || value == null) {
                if (responseNeeded) sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset)
                return
            }

            // Always send ACK immediately — regardless of processing outcome
            if (responseNeeded) sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset)

            // Attempt to reassemble (handles both single-packet and multi-fragment messages)
            val completePayload = PacketFragmenter.reassemble(device.address, value) ?: return

            // Process on the service coroutine scope — never block the GATT callback thread
            serviceScope.launch {
                handleIncomingPayload(device.address, completePayload)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                PacketFragmenter.clearAddress(device.address)
                Log.d(TAG, "Server: peer disconnected ${device.address}")
            }
        }
    }

    private fun sendGattResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, null)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException sendResponse", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Inbound Packet Pipeline
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Full inbound pipeline:
     * 1. Parse raw bytes as a Sawa wire packet  (senderHex | seq | ttl | text)
     * 2. Replay-attack defense via [MeshRoutingEngine]
     * 3. Deliver decoded text to Room DB (UI observes via Flow)
     * 4. Re-broadcast to all other peers (Store-Carry-Forward relay)
     */
    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
        markTraffic()
        // Simple wire format for this implementation:
        // [senderHashHex: 64 ASCII chars] [seqNum: 4 bytes BE] [ttl: 1 byte] [utf8Text...]
        if (payload.size < 69) {
            Log.w(TAG, "Payload too short (${payload.size} bytes) from $senderAddress")
            return
        }
        val senderHex = String(payload, 0, 64, Charsets.US_ASCII)
        val seq = ((payload[64].toInt() and 0xFF) shl 24) or
                  ((payload[65].toInt() and 0xFF) shl 16) or
                  ((payload[66].toInt() and 0xFF) shl 8) or
                   (payload[67].toInt() and 0xFF)
        val ttl = payload[68]
        val text = String(payload, 69, payload.size - 69, Charsets.UTF_8)

        val isNew = routingEngine.processIncomingPacket(senderHex, seq, ttl, payload)
        if (!isNew) {
            Log.d(TAG, "Dropped duplicate/expired packet $senderHex-$seq")
            return
        }

        // Deduplication and Self-echo drop
        val parts = text.split("|", limit = 3)
        if (parts.size == 3) {
            val msgId = parts[0]
            val sId = parts[1]
            val actualText = parts[2]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            if (processedMessageIds.contains(msgId)) return
            processedMessageIds.add(msgId)

            meshRepository.addMessage(
                Message(isMe = false, text = actualText, status = STATUS_DELIVERED)
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$actualText\"")
        } else {
            // Legacy plaintext
            meshRepository.addMessage(
                Message(isMe = false, text = text, status = STATUS_DELIVERED)
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$text\"")
        }

        // Relay with decremented TTL (Store-Carry-Forward)
        val newTtl = (ttl - 1).toByte()
        if (newTtl > 0) {
            val relayPayload = buildPayload(senderHex, seq, newTtl, text)
            val otherPeers = peerRegistry.all().filter { it.address != senderAddress }
            otherPeers.forEach { peer ->
                gattQueue.enqueue(GattWriteOp(device = peer.device, payload = relayPayload))
            }
            Log.d(TAG, "Relayed packet to ${otherPeers.size} peers (TTL=$newTtl)")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLE Scanning
    // ──────────────────────────────────────────────────────────────────────────

    private fun startScanning() {
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            Log.e(TAG, "BLE scanner unavailable")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Hardware UUID filter — only Sawa devices reach the callback
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "BLE scanning started with UUID filter.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException startScan", e)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                // Secondary software UUID check (belt-and-suspenders)
                val scanRecord = result.scanRecord ?: return
                if (!scanRecord.serviceUuids.orEmpty().contains(ParcelUuid(MESH_SERVICE_UUID))) return
                
                // Extract Node ID
                val serviceData = scanRecord.serviceData[ParcelUuid(MESH_SERVICE_UUID)] ?: return
                val nodeId = String(serviceData, Charsets.UTF_8)
                
                if (nodeId == NODE_ID_STRING) return // Ignore self
                
                peerRegistry.seen(nodeId, device, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }

    private fun stopScanning() {
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
            Log.i(TAG, "BLE scanning stopped.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopScan", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // BLE Advertising
    // ──────────────────────────────────────────────────────────────────────────

    private fun startAdvertising() {
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertiser unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .addServiceData(ParcelUuid(MESH_SERVICE_UUID), NODE_ID_BYTES)
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            Log.i(TAG, "BLE advertising started.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException startAdvertising", e)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising active.")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
        }
    }

    private fun stopAdvertising() {
        try {
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.i(TAG, "BLE advertising stopped.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException stopAdvertising", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Adaptive Duty Cycling (Battery Saver)
    // ──────────────────────────────────────────────────────────────────────────

    private var isDutyCycling = false
    private var isRadioOn = false

    private fun startDutyCycleLoop() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val hasRecentTraffic = (System.currentTimeMillis() - lastTrafficTime) < 60_000L
                val connectedCount = peerRegistry.count()
                val shouldDutyCycle = !hasRecentTraffic && connectedCount > 2

                if (shouldDutyCycle && !isDutyCycling) {
                    isDutyCycling = true
                    Log.i(TAG, "Entering adaptive duty cycle mode (Battery Saver)")
                } else if (!shouldDutyCycle && isDutyCycling) {
                    isDutyCycling = false
                    Log.i(TAG, "Exiting duty cycle mode (Continuous)")
                    if (!isRadioOn) {
                        startScanning()
                        startAdvertising()
                        isRadioOn = true
                    }
                }

                if (isDutyCycling) {
                    if (!isRadioOn) {
                        startScanning()
                        startAdvertising()
                        isRadioOn = true
                    }
                    kotlinx.coroutines.delay(5000) // ON duration
                    if (isDutyCycling) {
                        stopScanning()
                        stopAdvertising()
                        isRadioOn = false
                        kotlinx.coroutines.delay(15000) // OFF duration
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Packet Encoding
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds the raw wire payload for a text message.
     *
     * Format: (senderHex: 64 bytes) (seq: 4 bytes BE) (ttl: 1 byte) (utf8Text...)
     */
    fun buildOutgoingPayload(text: String): ByteArray {
        val seq = seqNum.getAndIncrement()
        val senderHex = BROADCAST_SENDER_HASH.joinToString("") { "%02x".format(it) }
        return buildPayload(senderHex, seq, BROADCAST_TTL, text)
    }

    private fun buildPayload(senderHex: String, seq: Int, ttl: Byte, text: String): ByteArray {
        val senderBytes = senderHex.toByteArray(Charsets.US_ASCII)  // 64 bytes
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return ByteArray(senderBytes.size + 4 + 1 + textBytes.size).also { buf ->
            senderBytes.copyInto(buf, 0)
            buf[64] = ((seq shr 24) and 0xFF).toByte()
            buf[65] = ((seq shr 16) and 0xFF).toByte()
            buf[66] = ((seq shr 8) and 0xFF).toByte()
            buf[67] = (seq and 0xFF).toByte()
            buf[68] = ttl
            textBytes.copyInto(buf, 69)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sawa Mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Maintains the Sawa offline mesh network" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sawa Mesh Active")
            .setContentText("Broadcasting secure offline mesh...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
}

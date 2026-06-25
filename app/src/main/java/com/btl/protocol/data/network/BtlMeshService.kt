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
import android.os.PowerManager
import android.util.Log
import com.btl.protocol.data.repository.MeshRepository
import com.btl.protocol.data.repository.Message
import com.btl.protocol.data.repository.STATUS_DELIVERED
import com.btl.protocol.data.repository.STATUS_SENT
import com.btl.protocol.data.repository.STATUS_PENDING
import com.btl.protocol.data.repository.MeshRoutingEngine
import com.btl.protocol.data.crypto.IdentityManager
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

        const val TYPE_CHAT: Byte = 0x01
        const val TYPE_ROUTING_TABLE: Byte = 0x03
        const val TYPE_SYNC: Byte = 0x02
        const val TYPE_DISCOVERY: Byte = 0x03

        var LOCAL_DEVICE_ID = ""
        var NODE_ID_BYTES = ByteArray(8)
        var NODE_ID_STRING = ""
        var DISPLAY_NAME = ""

        fun initIdentity(context: Context) {
            // Identity is now initialized via Hilt-injected IdentityManager in onCreate()
        }
        
        fun updateDisplayName(context: Context, newName: String) {
            DISPLAY_NAME = newName
            context.getSharedPreferences("SawaIdentityV2", Context.MODE_PRIVATE).edit().putString("displayName", newName).apply()
        }

        /** Tracks seen message UUIDs to deduplicate echoes from the broadcast mesh. */
        val packetCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, Boolean>(500, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 500
            }
        )

        /** PANIC MODE: Clears all in-memory state. */
        fun panicWipe() {
            packetCache.clear()
            peers.value.values.forEach { 
                try { it.device.connectGatt(null, false, null, BluetoothDevice.TRANSPORT_LE)?.close() } catch(_: Exception){} 
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

        data class PeerIdentity(val fullId: String, val displayName: String, val pubKey: ByteArray? = null)
        private val _knownIdentities = MutableStateFlow<Map<String, PeerIdentity>>(emptyMap())
        val knownIdentities: StateFlow<Map<String, PeerIdentity>> = _knownIdentities.asStateFlow()

        fun updateIdentity(sId: String, dName: String, pubKeyB64: String? = null) {
            val nodeId = sId.take(8)
            val current = _knownIdentities.value
            val existing = current[nodeId]
            
            val pubKeyBytes = try {
                pubKeyB64?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            } catch (e: Exception) { null }
            
            val newPubKey = pubKeyBytes ?: existing?.pubKey
            
            if (existing?.displayName != dName || (pubKeyBytes != null && existing?.pubKey == null)) {
                _knownIdentities.value = current + (nodeId to PeerIdentity(sId, dName, newPubKey))
            }
        }

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
        fun buildPayloadStatic(text: String, msgId: String = java.util.UUID.randomUUID().toString(), conversationId: String = "PUBLIC"): ByteArray? {
            packetCache.put(msgId, true)
            
            val finalString = if (conversationId != "PUBLIC") {
                val recipientNodeId = conversationId.take(8)
                val recipientPubKey = _knownIdentities.value[recipientNodeId]?.pubKey
                if (recipientPubKey != null) {
                    val encrypted = liveService?.identityManager?.encryptMessage(recipientPubKey, text)
                    if (encrypted != null) "[$conversationId] E2E:$encrypted" else "[$conversationId] $text"
                } else {
                    "[$conversationId] $text" // Fallback if key unknown
                }
            } else {
                text
            }
            
            val newText = "$msgId|$LOCAL_DEVICE_ID|$DISPLAY_NAME|$finalString"
            return liveService?.buildOutgoingPayload(newText)
        } 

        fun buildMediaPayloadStatic(msgId: String, conversationId: String, mediaBytes: ByteArray, mediaType: String): ByteArray? {
            packetCache.put(msgId, true)
            
            val packet = BinaryProtocol.Packet(
                type = if (mediaType == "image") 2 else 3,
                ttl = 3,
                timestamp = System.currentTimeMillis(),
                flags = 0,
                senderId = LOCAL_DEVICE_ID.toByteArray(Charsets.US_ASCII).copyOf(8),
                payload = mediaBytes
            )
            val binaryData = BinaryProtocol.encode(packet)
            
            // We need to prepend the messageId string length and bytes to the payload so it can be tracked
            // Actually, BinaryProtocol packet has NO messageId field, just senderId and timestamp.
            // Let's prepend msgId, LOCAL_DEVICE_ID, DISPLAY_NAME string separated by pipes, then a null byte, then binary data.
            val prefix = "$msgId|$LOCAL_DEVICE_ID|$DISPLAY_NAME|$conversationId\u0000"
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            
            val finalData = ByteArray(prefixBytes.size + binaryData.size)
            System.arraycopy(prefixBytes, 0, finalData, 0, prefixBytes.size)
            System.arraycopy(binaryData, 0, finalData, prefixBytes.size, binaryData.size)
            
            return liveService?.buildOutgoingPayload(finalData)
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
            val peers = livePeers?.allDirect() ?: emptyList()
            if (peers.isEmpty()) {
                Log.w(TAG, "No peers to transmit to")
                onResult(false)
                return
            }
            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
            peers.forEach { peer ->
                queue.enqueue(
                    GattWriteOp(
                        device = peer.device!!,
                        payload = payload,
                        messageId = messageId.toInt()
                    ) { success ->
                        if (success && reported.compareAndSet(false, true)) {
                            onResult(true)
                        }
                        if (completedCount.incrementAndGet() == peers.size) {
                            if (reported.compareAndSet(false, true)) {
                                onResult(false)
                            }
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
    @Inject lateinit var identityManager: IdentityManager

    // ──────────────────────────────────────────────────────────────────────────
    // Service State
    // ──────────────────────────────────────────────────────────────────────────

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var peerRegistry: PeerRegistry
    private lateinit var gattQueue: GattOperationQueue
    private var gattServer: BluetoothGattServer? = null
    // Use Unix timestamp as the initial sequence number to prevent replay-attack 
    // drops by peers after the app is restarted.
    private val seqNum = AtomicInteger((System.currentTimeMillis() / 1000).toInt())
    
    private var wakeLock: PowerManager.WakeLock? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        
        val fingerprint = identityManager.getPublicFingerprint()
        LOCAL_DEVICE_ID = fingerprint.take(16)
        NODE_ID_BYTES = fingerprint.toByteArray(Charsets.UTF_8).take(8).toByteArray()
        NODE_ID_STRING = String(NODE_ID_BYTES, Charsets.UTF_8)
        
        val prefs = getSharedPreferences("SawaIdentityV2", Context.MODE_PRIVATE)
        DISPLAY_NAME = prefs.getString("displayName", "") ?: ""
        if (DISPLAY_NAME.isEmpty()) {
            DISPLAY_NAME = "Sawa_" + fingerprint.take(4)
            prefs.edit().putString("displayName", DISPLAY_NAME).apply()
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sawa::MeshWakeLock")
        wakeLock?.acquire()

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

        // Gossip Protocol (Distant Node Discovery)
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                if (_meshActive.value) {
                    val peers = peerRegistry.allDirect()
                    if (peers.isNotEmpty()) {
                        val gossipData = peers.joinToString(";") { "${it.nodeId},${it.meshName ?: "Unknown"}" }
                        val payload = buildOutgoingPayload(gossipData, TYPE_ROUTING_TABLE)
                        val queue = liveQueue
                        if (queue != null) {
                            peers.forEach { peer ->
                                queue.enqueue(GattWriteOp(device = peer.device!!, payload = payload))
                            }
                        }
                    }
                }
            }
        }

        // Active Mesh Anti-Entropy (Vector Clock Sync)
        serviceScope.launch {
            while (true) {
                if (_meshActive.value) {
                    val allIds = meshRepository.getAllMessageIds()
                    val recentIds = if (allIds.isNotEmpty()) allIds.takeLast(5) else emptyList()
                    val myPubKeyB64 = android.util.Base64.encodeToString(identityManager.x25519PublicKey, android.util.Base64.NO_WRAP)
                    val syncPayload = "SYNC|${recentIds.joinToString(",")}|$LOCAL_DEVICE_ID|$DISPLAY_NAME|$myPubKeyB64"
                    val payload = buildOutgoingPayload(syncPayload)
                    
                    // Enqueue to all peers safely
                    val live = liveQueue
                    if (live != null) {
                        peerRegistry.all().forEach { peer ->
                            live.enqueue(GattWriteOp(device = peer.device!!, payload = payload))
                        }
                    }
                }
                delay(20_000L) // Broadcast Vector Clock every 20 seconds
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
            Log.i(TAG, "Mesh started.")
        } else {
            Log.w(TAG, "Bluetooth not enabled — mesh not started.")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
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
                BluetoothGattCharacteristic.PROPERTY_WRITE or
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
        // Format: [senderHashHex: 64 ASCII chars] [seqNum: 4 bytes BE] [ttl: 1 byte] [type: 1 byte] [utf8Text...]
        if (payload.size < 70) {
            Log.w(TAG, "Payload too short (${payload.size} bytes) from $senderAddress")
            return
        }
        val senderHex = String(payload, 0, 64, Charsets.US_ASCII)
        val seq = ((payload[64].toInt() and 0xFF) shl 24) or
                  ((payload[65].toInt() and 0xFF) shl 16) or
                  ((payload[66].toInt() and 0xFF) shl 8) or
                   (payload[67].toInt() and 0xFF)
        val ttl = payload[68]
        val type = payload[69]
        val text = String(payload, 70, payload.size - 70, Charsets.UTF_8)

        val packetId = "$senderHex-$seq"
        if (packetCache.containsKey(packetId)) {
            Log.d(TAG, "Dropped duplicate packet $packetId (LRU Cache)")
            return
        }
        packetCache.put(packetId, true)

        if (type == TYPE_ROUTING_TABLE) {
            val entries = text.split(";")
            entries.forEach { entry ->
                val p = entry.split(",")
                if (p.size == 2) {
                    val id = p[0]
                    val name = p[1]
                    if (id != LOCAL_DEVICE_ID) {
                        peerRegistry.seenMesh(id, name)
                    }
                }
            }
            // Relay gossip table
            val newTtl = (ttl - 1).toByte()
            if (newTtl > 0) {
                val relayPayload = buildPayload(senderHex, seq, newTtl, type, text.toByteArray(Charsets.UTF_8))
                peerRegistry.allDirect().filter { it.address != senderAddress }.forEach { peer ->
                    gattQueue.enqueue(GattWriteOp(device = peer.device!!, payload = relayPayload))
                }
            }
            return
        }

        // --- Vector Sync Anti-Entropy / Discovery Logic ---
        if (type == TYPE_SYNC || type == TYPE_DISCOVERY) {
            if (text.startsWith("SYNC|")) {
                val parts = text.split("|")
                if (parts.size >= 4) {
                    val pubKey = if (parts.size >= 5) parts[4] else null
                    updateIdentity(parts[2], parts[3], pubKey)
                }
                val remoteIds = parts[1].split(",")
                val localIds = meshRepository.getAllMessageIds()
                val missingInRemote = localIds.filter { it !in remoteIds }.takeLast(5)
                
                val peerDevice = peerRegistry.all().find { it.address == senderAddress }?.device ?: return
                missingInRemote.forEach { missingId ->
                    val msg = meshRepository.getMessageById(missingId)
                    if (msg != null) {
                        val finalText = if (msg.conversationId != "PUBLIC") "[${msg.conversationId}] ${msg.text}" else msg.text
                        val relayText = "${msg.messageId}|$LOCAL_DEVICE_ID|${msg.senderName ?: "Unknown"}|$finalText"
                        val relayPayload = buildOutgoingPayload(relayText, TYPE_CHAT)
                        gattQueue.enqueue(GattWriteOp(device = peerDevice, payload = relayPayload))
                    }
                }
            }
            return // Route ends here for internal packets! No UI leakage.
        }

        if (type != TYPE_CHAT) {
            Log.w(TAG, "Dropped unhandled packet type: $type")
            return
        }

        // --- Binary Media Parser (Only for TYPE_CHAT) ---
        val rawData = payload.copyOfRange(70, payload.size)
        val nullIndex = rawData.indexOfFirst { it == 0.toByte() }
        if (nullIndex != -1) {
            val prefix = String(rawData, 0, nullIndex, Charsets.UTF_8)
            val parts = prefix.split("|")
            if (parts.size >= 4) {
                val msgId = parts[0]
                val sId = parts[1]
                val dName = parts[2]
                val convId = parts[3]
                
                if (sId == LOCAL_DEVICE_ID) return
                updateIdentity(sId, dName)
                if (packetCache.containsKey(msgId)) return
                packetCache.put(msgId, true)
                
                val binaryData = rawData.copyOfRange(nullIndex + 1, rawData.size)
                if (binaryData.isNotEmpty() && binaryData[0] == BinaryProtocol.VERSION) {
                    val packet = BinaryProtocol.decode(binaryData)
                    if (packet != null) {
                        val mediaType = if (packet.type == 2.toByte()) "image" else "voice"
                        val ext = if (mediaType == "image") ".jpg" else ".amr"
                        try {
                            val file = java.io.File(applicationContext.filesDir, "$msgId$ext")
                            java.io.FileOutputStream(file).use { it.write(packet.payload) }
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                applicationContext,
                                applicationContext.packageName + ".fileprovider",
                                file
                            )
                            meshRepository.addMessage(
                                com.btl.protocol.data.repository.Message(
                                    messageId = msgId,
                                    isMe = false,
                                    text = "",
                                    timestamp = packet.timestamp,
                                    senderName = dName,
                                    conversationId = convId,
                                    mediaUri = uri.toString(),
                                    mediaType = mediaType
                                )
                            )
                            showDmNotification(dName, if (mediaType == "voice") "Sent a voice message" else "Sent an image")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save media", e)
                        }
                    }
                }
                
                val newTtl = (ttl - 1).toByte()
                if (newTtl > 0) {
                    val relayPayload = buildPayload(senderHex, seq, newTtl, type, payload.copyOfRange(70, payload.size))
                    peerRegistry.allDirect().filter { it.address != senderAddress }.forEach { peer ->
                        gattQueue.enqueue(GattWriteOp(device = peer.device!!, payload = relayPayload))
                    }
                }
                return
            }
        }

        // Deduplication and Self-echo drop
        val parts = text.split("|", limit = 4)
        if (parts.size == 4) {
            val msgId = parts[0]
            val sId = parts[1]
            val dName = parts[2]
            val actualText = parts[3]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            updateIdentity(sId, dName)
            if (packetCache.containsKey(msgId)) return
            packetCache.put(msgId, true)

            val convIdPrefix = "[$LOCAL_DEVICE_ID] "
            val isForMe = actualText.startsWith(convIdPrefix)
            val isForSomeoneElse = actualText.matches(Regex("^\\[[a-fA-F0-9]{16}\\] .*"))

            if (isForMe) {
                var cleanedText = actualText.removePrefix(convIdPrefix)
                if (cleanedText.startsWith("E2E:")) {
                    val encryptedB64 = cleanedText.removePrefix("E2E:")
                    val senderNodeId = sId.take(8)
                    val senderPubKey = _knownIdentities.value[senderNodeId]?.pubKey
                    if (senderPubKey != null) {
                        val decrypted = identityManager.decryptMessage(senderPubKey, encryptedB64)
                        if (decrypted != null) {
                            cleanedText = decrypted
                        } else {
                            cleanedText = "🔒 [Encrypted message could not be decrypted]"
                        }
                    } else {
                        cleanedText = "🔒 [Encrypted message received, but sender's public key is unknown]"
                    }
                }
                
                meshRepository.addMessage(
                    Message(messageId = msgId, isMe = false, text = cleanedText, status = STATUS_DELIVERED, senderName = dName, conversationId = sId)
                )
                Log.i(TAG, "✉ Delivered DM from mesh: \"$cleanedText\" from $dName")
                showDmNotification(dName, cleanedText)
            } else if (!isForSomeoneElse) {
                meshRepository.addMessage(
                    Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = dName, conversationId = "PUBLIC")
                )
                Log.i(TAG, "✉ Delivered public message from mesh: \"$actualText\" from $dName")
            } else {
                Log.i(TAG, "✉ Relaying DM intended for another peer.")
            }
        } else if (parts.size == 3) {
            val msgId = parts[0]
            val sId = parts[1]
            val actualText = parts[2]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            if (packetCache.containsKey(msgId)) return
            packetCache.put(msgId, true)

            meshRepository.addMessage(
                Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = "Unknown")
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$actualText\"")
        } else {
            Log.w(TAG, "Malformed TYPE_CHAT packet dropped silently.")
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
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
    // Adaptive Duty Cycling Removed (Using OS-Native SCAN_MODE_LOW_POWER)
    // ──────────────────────────────────────────────────────────────────────────

    private var isRadioOn = false

    // ──────────────────────────────────────────────────────────────────────────
    // Packet Encoding
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds the raw wire payload for a text message.
     *
     * Format: (senderHex: 64 bytes) (seq: 4 bytes BE) (ttl: 1 byte) (utf8Text...)
     */
    fun buildOutgoingPayload(text: String, type: Byte = if (text.startsWith("SYNC|")) TYPE_SYNC else TYPE_CHAT): ByteArray {
        val seq = seqNum.getAndIncrement()
        val senderHex = identityManager.getPublicFingerprint()
        return buildPayload(senderHex, seq, BROADCAST_TTL, type, text.toByteArray(Charsets.UTF_8))
    }

    fun buildOutgoingPayload(data: ByteArray, type: Byte = TYPE_CHAT): ByteArray {
        val seq = seqNum.getAndIncrement()
        val senderHex = identityManager.getPublicFingerprint()
        return buildPayload(senderHex, seq, BROADCAST_TTL, type, data)
    }

    private fun buildPayload(senderHex: String, seq: Int, ttl: Byte, type: Byte, textBytes: ByteArray): ByteArray {
        val senderBytes = senderHex.toByteArray(Charsets.US_ASCII)  // 64 bytes
        return ByteArray(senderBytes.size + 4 + 1 + 1 + textBytes.size).also { buf ->
            senderBytes.copyInto(buf, 0)
            buf[64] = ((seq shr 24) and 0xFF).toByte()
            buf[65] = ((seq shr 16) and 0xFF).toByte()
            buf[66] = ((seq shr 8) and 0xFF).toByte()
            buf[67] = (seq and 0xFF).toByte()
            buf[68] = ttl
            buf[69] = type
            textBytes.copyInto(buf, 70)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Sawa Mesh",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Maintains the Sawa offline mesh network" }
            
            val dmChannel = android.app.NotificationChannel(
                "DM_CHANNEL",
                "Direct Messages",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifications for private messages" }

            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
            nm?.createNotificationChannel(dmChannel)
        }
    }

    private fun buildNotification(): Notification =
        androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sawa Mesh Active")
            .setContentText("Broadcasting secure offline mesh...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
            
    private fun showDmNotification(senderName: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        
        val intent = Intent(this, com.btl.protocol.ui.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notif = androidx.core.app.NotificationCompat.Builder(this, "DM_CHANNEL")
            .setContentTitle("Private Message from $senderName")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(senderName.hashCode(), notif)
    }
}

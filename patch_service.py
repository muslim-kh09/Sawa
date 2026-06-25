import sys

with open('/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt', 'r') as f:
    content = f.read()

# 1. TYPE_CHAT constants
target_ttl = "private const val BROADCAST_TTL: Byte = 7"
replacement_ttl = """private const val BROADCAST_TTL: Byte = 7

        const val TYPE_CHAT: Byte = 0x01
        const val TYPE_SYNC: Byte = 0x02
        const val TYPE_DISCOVERY: Byte = 0x03"""
content = content.replace(target_ttl, replacement_ttl)

# 2. enqueueTransmit
target_enqueue = """        fun enqueueTransmit(payload: ByteArray, messageId: Long, onResult: (Boolean) -> Unit = {}) {
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
        }"""
replacement_enqueue = """        fun enqueueTransmit(payload: ByteArray, messageId: Long, onResult: (Boolean) -> Unit = {}) {
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
            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            var completedCount = 0
            peers.forEach { peer ->
                queue.enqueue(
                    GattWriteOp(
                        device = peer.device,
                        payload = payload,
                        messageId = messageId.toInt()
                    ) { success ->
                        if (success && reported.compareAndSet(false, true)) {
                            onResult(true)
                        }
                        if (++completedCount == peers.size) {
                            if (reported.compareAndSet(false, true)) {
                                onResult(false)
                            }
                        }
                    }
                )
            }
        }"""
content = content.replace(target_enqueue, replacement_enqueue)

# 3. Remove startDutyCycleLoop from onStartCommand
target_onstart = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bluetoothAdapter.isEnabled) {
            setupGattServer()
            startScanning()
            startAdvertising()
            _meshActive.value = true
            isRadioOn = true
            startDutyCycleLoop()
            Log.i(TAG, "Mesh started.")"""
replacement_onstart = """    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bluetoothAdapter.isEnabled) {
            setupGattServer()
            startScanning()
            startAdvertising()
            _meshActive.value = true
            isRadioOn = true
            Log.i(TAG, "Mesh started.")"""
content = content.replace(target_onstart, replacement_onstart)

# 4. Remove startDutyCycleLoop definition
target_duty = """    // ──────────────────────────────────────────────────────────────────────────
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
    }"""
replacement_duty = """    // ──────────────────────────────────────────────────────────────────────────
    // Adaptive Duty Cycling Removed (Using OS-Native SCAN_MODE_LOW_POWER)
    // ──────────────────────────────────────────────────────────────────────────

    private var isRadioOn = false"""
content = content.replace(target_duty, replacement_duty)

# 5. SCAN_MODE_LOW_POWER
target_scan = """        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()"""
replacement_scan = """        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()"""
content = content.replace(target_scan, replacement_scan)

# 6. handleIncomingPayload
target_handle = """    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
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

        // --- Binary Media Parser ---
        val rawData = payload.copyOfRange(69, payload.size)
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
                if (processedMessageIds.contains(msgId)) return
                processedMessageIds.add(msgId)
                
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
                return
            }
        }

        // --- Vector Sync Anti-Entropy Logic ---
        if (text.startsWith("SYNC|")) {
            val parts = text.split("|")
            if (parts.size >= 4) {
                val pubKey = if (parts.size >= 5) parts[4] else null
                updateIdentity(parts[2], parts[3], pubKey)
            }
            val remoteIds = parts[1].split(",")
            val localIds = meshRepository.getAllMessageIds()
            // Sync up to 5 missing messages to avoid flooding
            val missingInRemote = localIds.filter { it !in remoteIds }.takeLast(5)
            
            val peerDevice = peerRegistry.all().find { it.address == senderAddress }?.device ?: return
            missingInRemote.forEach { missingId ->
                val msg = meshRepository.getMessageById(missingId)
                if (msg != null) {
                    val finalText = if (msg.conversationId != "PUBLIC") "[${msg.conversationId}] ${msg.text}" else msg.text
                    val relayText = "${msg.messageId}|$LOCAL_DEVICE_ID|${msg.senderName ?: "Unknown"}|$finalText"
                    val relayPayload = buildOutgoingPayload(relayText)
                    gattQueue.enqueue(GattWriteOp(device = peerDevice, payload = relayPayload))
                }
            }
            return
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
            if (processedMessageIds.contains(msgId)) return
            processedMessageIds.add(msgId)

            // Extract conversationId from text if it's a DM
            val convIdPrefix = "[$LOCAL_DEVICE_ID] "
            val isForMe = actualText.startsWith(convIdPrefix)
            val isForSomeoneElse = actualText.matches(Regex("^\\[[a-fA-F0-9]{16}\\] .*"))

            if (isForMe) {
                // It's a DM directed to me
                var cleanedText = actualText.removePrefix(convIdPrefix)
                
                // Attempt E2EE Decryption
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
                // It's a PUBLIC message
                meshRepository.addMessage(
                    Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = dName, conversationId = "PUBLIC")
                )
                Log.i(TAG, "✉ Delivered public message from mesh: \"$actualText\" from $dName")
            } else {
                // It's a DM for someone else. Store it for relaying, but don't show it in our UI.
                // Or we can just drop UI delivery, but keep it in processedMessageIds so we relay it via SCF.
                Log.i(TAG, "✉ Relaying DM intended for another peer.")
            }
        } else if (parts.size == 3) {
            val msgId = parts[0]
            val sId = parts[1]
            val actualText = parts[2]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            if (processedMessageIds.contains(msgId)) return
            processedMessageIds.add(msgId)

            meshRepository.addMessage(
                Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = "Unknown")
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$actualText\"")
        } else {
            // Legacy plaintext
            meshRepository.addMessage(
                Message(messageId = java.util.UUID.randomUUID().toString(), isMe = false, text = text, status = STATUS_DELIVERED)
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$text\"")
        }

        // Relay with decremented TTL (Store-Carry-Forward)
        val newTtl = (ttl - 1).toByte()
        if (newTtl > 0) {
            val relayPayload = buildPayload(senderHex, seq, newTtl, text.toByteArray(Charsets.UTF_8))
            val otherPeers = peerRegistry.all().filter { it.address != senderAddress }
            otherPeers.forEach { peer ->
                gattQueue.enqueue(GattWriteOp(device = peer.device, payload = relayPayload))
            }
            Log.d(TAG, "Relayed packet to ${otherPeers.size} peers (TTL=$newTtl)")
        }
    }"""
replacement_handle = """    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
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

        val isNew = routingEngine.processIncomingPacket(senderHex, seq, ttl, payload)
        if (!isNew) {
            Log.d(TAG, "Dropped duplicate/expired packet $senderHex-$seq")
            return
        }

        // Relay with decremented TTL (Store-Carry-Forward)
        val newTtl = (ttl - 1).toByte()
        if (newTtl > 0) {
            val relayPayload = buildPayload(senderHex, seq, newTtl, type, text.toByteArray(Charsets.UTF_8))
            val otherPeers = peerRegistry.all().filter { it.address != senderAddress }
            otherPeers.forEach { peer ->
                gattQueue.enqueue(GattWriteOp(device = peer.device, payload = relayPayload))
            }
            Log.d(TAG, "Relayed packet to ${otherPeers.size} peers (TTL=$newTtl)")
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
                if (processedMessageIds.contains(msgId)) return
                processedMessageIds.add(msgId)
                
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
            if (processedMessageIds.contains(msgId)) return
            processedMessageIds.add(msgId)

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
            if (processedMessageIds.contains(msgId)) return
            processedMessageIds.add(msgId)

            meshRepository.addMessage(
                Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = "Unknown")
            )
            Log.i(TAG, "✉ Delivered message from mesh: \"$actualText\"")
        } else {
            Log.w(TAG, "Malformed TYPE_CHAT packet dropped silently.")
        }
    }"""
content = content.replace(target_handle, replacement_handle)

# 7. buildPayload methods
target_build = """    fun buildOutgoingPayload(text: String): ByteArray {
        val seq = seqNum.getAndIncrement()
        val senderHex = identityManager.getPublicFingerprint()
        return buildPayload(senderHex, seq, BROADCAST_TTL, text.toByteArray(Charsets.UTF_8))
    }

    fun buildOutgoingPayload(data: ByteArray): ByteArray {
        val seq = seqNum.getAndIncrement()
        val senderHex = identityManager.getPublicFingerprint()
        return buildPayload(senderHex, seq, BROADCAST_TTL, data)
    }

    private fun buildPayload(senderHex: String, seq: Int, ttl: Byte, textBytes: ByteArray): ByteArray {
        val senderBytes = senderHex.toByteArray(Charsets.US_ASCII)  // 64 bytes
        return ByteArray(senderBytes.size + 4 + 1 + textBytes.size).also { buf ->
            senderBytes.copyInto(buf, 0)
            buf[64] = ((seq shr 24) and 0xFF).toByte()
            buf[65] = ((seq shr 16) and 0xFF).toByte()
            buf[66] = ((seq shr 8) and 0xFF).toByte()
            buf[67] = (seq and 0xFF).toByte()
            buf[68] = ttl
            textBytes.copyInto(buf, 69)
        }
    }"""
replacement_build = """    fun buildOutgoingPayload(text: String, type: Byte = if (text.startsWith("SYNC|")) TYPE_SYNC else TYPE_CHAT): ByteArray {
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
    }"""
content = content.replace(target_build, replacement_build)

with open('/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt', 'w') as f:
    f.write(content)

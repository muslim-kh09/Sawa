import sys

with open('/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt', 'r') as f:
    lines = f.readlines()

new_handle = """    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
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
    }
"""

start_index = -1
end_index = -1

for i, line in enumerate(lines):
    if line.startswith("    private suspend fun handleIncomingPayload"):
        start_index = i
    if start_index != -1 and i > start_index and line == "    }\n":
        end_index = i
        break

if start_index != -1 and end_index != -1:
    lines[start_index:end_index+1] = [new_handle]
    with open('/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt', 'w') as f:
        f.writelines(lines)
    print("Successfully replaced handleIncomingPayload")
else:
    print("Could not find start or end index.")

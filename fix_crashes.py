import re

## 1. BtlMeshService.kt
btl_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt'
with open(btl_file, 'r') as f:
    content = f.read()

# Fix packetCache
old_cache = """        val packetCache = java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, Boolean>(500, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) = size > 500
            }
        )"""
new_cache = """        val packetCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()"""
content = content.replace(old_cache, new_cache)

# Fix packetCache.put in buildPayloadStatic
old_put_static = """            packetCache.put(msgId, true)"""
new_put_static = """            if (packetCache.size > 1000) packetCache.clear()
            packetCache.put(msgId, true)"""
content = content.replace(old_put_static, new_put_static)

# Fix packetCache.put in handleIncomingPayload (top)
old_put_handle = """        if (packetCache.containsKey(packetId)) {
            Log.d(TAG, "Dropped duplicate packet $packetId (LRU Cache)")
            return
        }
        packetCache.put(packetId, true)"""
new_put_handle = """        if (packetCache.containsKey(packetId)) {
            Log.d(TAG, "Dropped duplicate packet $packetId (LRU Cache)")
            return
        }
        if (packetCache.size > 1000) packetCache.clear()
        packetCache.put(packetId, true)"""
content = content.replace(old_put_handle, new_put_handle)

# Fix packetCache.put in handleIncomingPayload (bottom 1)
old_put_bottom1 = """                if (packetCache.containsKey(msgId)) return
                packetCache.put(msgId, true)"""
new_put_bottom1 = """                if (packetCache.containsKey(msgId)) return
                if (packetCache.size > 1000) packetCache.clear()
                packetCache.put(msgId, true)"""
content = content.replace(old_put_bottom1, new_put_bottom1)

# Fix packetCache.put in handleIncomingPayload (bottom 2)
old_put_bottom2 = """            if (packetCache.containsKey(msgId)) return
            packetCache.put(msgId, true)"""
new_put_bottom2 = """            if (packetCache.containsKey(msgId)) return
            if (packetCache.size > 1000) packetCache.clear()
            packetCache.put(msgId, true)"""
content = content.replace(old_put_bottom2, new_put_bottom2)

# Fix recipientNodeId in buildPayloadStatic
old_recipient = """                val recipientNodeId = conversationId.take(8)
                val recipientPubKey = _knownIdentities.value[recipientNodeId]?.pubKey"""
new_recipient = """                val recipientNodeId = conversationId
                val recipientPubKey = _knownIdentities.value[recipientNodeId]?.pubKey"""
content = content.replace(old_recipient, new_recipient)

# Fix senderNodeId in handleIncomingPayload
old_sender = """                    val encryptedB64 = cleanedText.removePrefix("E2E:")
                    val senderNodeId = sId.take(8)
                    val senderPubKey = _knownIdentities.value[senderNodeId]?.pubKey"""
new_sender = """                    val encryptedB64 = cleanedText.removePrefix("E2E:")
                    val senderNodeId = sId
                    val senderPubKey = _knownIdentities.value[senderNodeId]?.pubKey"""
content = content.replace(old_sender, new_sender)

# Fix buildPayload
old_build = """    private fun buildPayload(senderHex: String, seq: Int, ttl: Byte, type: Byte, textBytes: ByteArray): ByteArray {
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
new_build = """    private fun buildPayload(senderHex: String, seq: Int, ttl: Byte, type: Byte, textBytes: ByteArray): ByteArray {
        val senderBytes = senderHex.toByteArray(Charsets.US_ASCII)
        return ByteArray(64 + 4 + 1 + 1 + textBytes.size).also { buf ->
            senderBytes.copyInto(buf, 0, 0, minOf(64, senderBytes.size))
            buf[64] = ((seq shr 24) and 0xFF).toByte()
            buf[65] = ((seq shr 16) and 0xFF).toByte()
            buf[66] = ((seq shr 8) and 0xFF).toByte()
            buf[67] = (seq and 0xFF).toByte()
            buf[68] = ttl
            buf[69] = type
            textBytes.copyInto(buf, 70)
        }
    }"""
content = content.replace(old_build, new_build)

# Fix Gossip loop
old_gossip = """                        val gossipData = peers.joinToString(";") { "${it.nodeId},${it.meshName ?: "Unknown"}" }"""
new_gossip = """                        val gossipData = peers.joinToString(";") { "${it.nodeId},${_knownIdentities.value[it.nodeId]?.displayName ?: "Unknown"}" }"""
content = content.replace(old_gossip, new_gossip)

# Wrap handleIncomingPayload in try-catch
old_handle = """    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
        markTraffic()"""
new_handle = """    private suspend fun handleIncomingPayload(senderAddress: String, payload: ByteArray) {
        markTraffic()
        try {"""
old_end = """        } else if (parts.size == 3) {
            val msgId = parts[0]
            val sId = parts[1]
            val actualText = parts[2]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            if (packetCache.containsKey(msgId)) return
            if (packetCache.size > 1000) packetCache.clear()
            packetCache.put(msgId, true)

            meshRepository.addMessage(
                Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = "Unknown")
            )
        }
    }"""
new_end = """        } else if (parts.size == 3) {
            val msgId = parts[0]
            val sId = parts[1]
            val actualText = parts[2]
            
            if (sId == LOCAL_DEVICE_ID) return // drop self-echo
            if (packetCache.containsKey(msgId)) return
            if (packetCache.size > 1000) packetCache.clear()
            packetCache.put(msgId, true)

            meshRepository.addMessage(
                Message(messageId = msgId, isMe = false, text = actualText, status = STATUS_DELIVERED, senderName = "Unknown")
            )
        }
        } catch (e: Exception) {
            Log.e(TAG, "Fatal crash inside handleIncomingPayload!", e)
        }
    }"""
content = content.replace(old_end, new_end)
content = content.replace(old_handle, new_handle)

with open(btl_file, 'w') as f:
    f.write(content)

print("Applied crash fixes")

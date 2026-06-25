import re

## 1. PacketFragmenter.kt
frag_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/PacketFragmenter.kt'
with open(frag_file, 'r') as f:
    content = f.read()

old_frag = """        val chunks: List<ByteArray> = if (payload.size <= chunkSize) {
            listOf(payload)
        } else {
            payload.toList().chunked(chunkSize).map { it.toByteArray() }
        }"""
new_frag = """        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }"""
content = content.replace(old_frag, new_frag)
with open(frag_file, 'w') as f:
    f.write(content)

## 2. ChatScreen.kt
chat_file = '/root/BTL/app/src/main/java/com/btl/protocol/ui/screens/ChatScreen.kt'
with open(chat_file, 'r') as f:
    content = f.read()

old_chat = """                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 150.dp),
                    placeholder = {
                        Text(stringResource(R.string.type_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },"""
new_chat = """                TextField(
                    value = text,
                    onValueChange = { if (it.length <= 1500) onTextChange(it) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 150.dp),
                    placeholder = {
                        Text(stringResource(R.string.type_message) + " (Max 1500 chars)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },"""
content = content.replace(old_chat, new_chat)
with open(chat_file, 'w') as f:
    f.write(content)

## 3. MeshViewModel.kt
vm_file = '/root/BTL/app/src/main/java/com/btl/protocol/ui/MeshViewModel.kt'
with open(vm_file, 'r') as f:
    content = f.read()

old_vm = """    fun sendMessage(text: String, conversationId: String = "PUBLIC") {
        if (text.isBlank()) return
        viewModelScope.launch {"""
new_vm = """    fun sendMessage(text: String, conversationId: String = "PUBLIC") {
        if (text.isBlank()) return
        val limitedText = text.take(1500)
        viewModelScope.launch {
            val msgId = java.util.UUID.randomUUID().toString()
            val rowId = repository.addMessage(
                Message(messageId = msgId, isMe = true, text = limitedText, status = STATUS_PENDING, conversationId = conversationId)
            )
            if (rowId == -1L) return@launch
            Log.d(TAG, "Message inserted, rowId=$rowId, enqueuing transmission…")

            val payload = BtlMeshService.buildPayloadStatic(limitedText, msgId, conversationId)"""
# We need to replace the body correctly. I'll use regex for MeshViewModel.
content = re.sub(
    r'fun sendMessage\(text: String, conversationId: String = "PUBLIC"\) \{\s*if \(text\.isBlank\(\)\) return\s*viewModelScope\.launch \{\s*val msgId = java\.util\.UUID\.randomUUID\(\)\.toString\(\)\s*val rowId = repository\.addMessage\(\s*Message\(messageId = msgId, isMe = true, text = text, status = STATUS_PENDING, conversationId = conversationId\)\s*\)\s*if \(rowId == -1L\) return@launch\s*Log\.d\(TAG, "Message inserted, rowId=\$rowId, enqueuing transmission…"\)\s*val payload = BtlMeshService\.buildPayloadStatic\(text, msgId, conversationId\)',
    new_vm.strip(),
    content
)
with open(vm_file, 'w') as f:
    f.write(content)

print("Applied OOM and length limit fixes")

import re

## 1. Fix GattOperationQueue.kt (MTU tracking and dynamic timeouts)
gatt_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/GattOperationQueue.kt'
with open(gatt_file, 'r') as f:
    content = f.read()

# Add deviceMtus map
content = content.replace(
    "private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()",
    "private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()\n    private val deviceMtus = ConcurrentHashMap<String, Int>()"
)

# Fix timeout logic in processWithRetry
content = re.sub(
    r"val success = withTimeoutOrNull\(GATT_TIMEOUT_MS\) \{ executeOnce\(op\) \} \?: false",
    "val dynamicTimeout = GATT_TIMEOUT_MS + (op.payload.size * 10L)\n            val success = withTimeoutOrNull(dynamicTimeout) { executeOnce(op) } ?: false",
    content
)

# Fix hardcoded 500 MTU
content = content.replace(
    "fragments = PacketFragmenter.fragment(op.payload, 500) // Fallback safe MTU for pooled",
    "val mtu = deviceMtus[mac] ?: 23\n                    fragments = PacketFragmenter.fragment(op.payload, mtu - 3)"
)

# Track MTU in onMtuChanged
mtu_changed_old = """                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    val negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    val maxPayload = negotiatedMtu - 3
                    fragments = PacketFragmenter.fragment(op.payload, maxPayload)"""

mtu_changed_new = """                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    val negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    deviceMtus[gatt.device.address] = negotiatedMtu
                    val maxPayload = negotiatedMtu - 3
                    fragments = PacketFragmenter.fragment(op.payload, maxPayload)"""

content = content.replace(mtu_changed_old, mtu_changed_new)

with open(gatt_file, 'w') as f:
    f.write(content)


## 2. Fix BtlMeshService.kt (completedCount thread safety)
btl_service_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt'
with open(btl_service_file, 'r') as f:
    btl_content = f.read()

btl_old = """            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
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
            }"""

btl_new = """            val reported = java.util.concurrent.atomic.AtomicBoolean(false)
            val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
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
                        if (completedCount.incrementAndGet() == peers.size) {
                            if (reported.compareAndSet(false, true)) {
                                onResult(false)
                            }
                        }
                    }
                )
            }"""

btl_content = btl_content.replace(btl_old, btl_new)

with open(btl_service_file, 'w') as f:
    f.write(btl_content)


## 3. Fix MeshViewModel.kt (STATUS_DELIVERED to STATUS_SENT)
mesh_vm_file = '/root/BTL/app/src/main/java/com/btl/protocol/ui/MeshViewModel.kt'
with open(mesh_vm_file, 'r') as f:
    mesh_vm_content = f.read()

mesh_vm_old = """                    if (success) {
                        repository.updateStatus(rowId, STATUS_DELIVERED)
                        Log.d(TAG, "Message $rowId delivered ✓")
                    } else {"""

mesh_vm_new = """                    if (success) {
                        repository.updateStatus(rowId, STATUS_SENT)
                        Log.d(TAG, "Message $rowId sent to mesh ✓")
                    } else {"""

mesh_vm_content = mesh_vm_content.replace(mesh_vm_old, mesh_vm_new)

with open(mesh_vm_file, 'w') as f:
    f.write(mesh_vm_content)

print("Applied fixes")

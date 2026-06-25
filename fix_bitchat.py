import re

## 1. Update GattOperationQueue.kt
gatt_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/GattOperationQueue.kt'
with open(gatt_file, 'r') as f:
    gatt_content = f.read()

# Add AtomicInteger and callback
gatt_content = gatt_content.replace(
"""class GattOperationQueue(
    private val context: Context,
    private val scope: CoroutineScope
) {""",
"""class GattOperationQueue(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var onQueueActiveChanged: (Boolean) -> Unit = {}
    private val activeOpCount = java.util.concurrent.atomic.AtomicInteger(0)"""
)

# Add try-finally to processWithRetry
old_process_retry = """    private suspend fun processWithRetry(op: GattWriteOp) {
        repeat(MAX_RETRIES) { attempt ->
            val dynamicTimeout = GATT_TIMEOUT_MS + (op.payload.size * 10L)
            val success = withTimeoutOrNull(dynamicTimeout) { executeOnce(op) } ?: false
            if (success) {
                op.onComplete(true)
                return
            }
            val backoff = RETRY_BASE_DELAY_MS * (attempt + 1)
            Log.w(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES failed for ${op.device.address}. Retry in ${backoff}ms")
            
            // On failure, tear down connection to reset state
            tearDown(op.device.address)
            
            if (attempt < MAX_RETRIES - 1) delay(backoff)
        }
        Log.e(TAG, "All retries exhausted for ${op.device.address}")
        op.onComplete(false)
        tearDown(op.device.address)
    }"""

new_process_retry = """    private suspend fun processWithRetry(op: GattWriteOp) {
        if (activeOpCount.getAndIncrement() == 0) {
            onQueueActiveChanged(true)
        }
        try {
            repeat(MAX_RETRIES) { attempt ->
                val dynamicTimeout = GATT_TIMEOUT_MS + (op.payload.size * 10L)
                val success = withTimeoutOrNull(dynamicTimeout) { executeOnce(op) } ?: false
                if (success) {
                    op.onComplete(true)
                    return
                }
                val backoff = RETRY_BASE_DELAY_MS * (attempt + 1)
                Log.w(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES failed for ${op.device.address}. Retry in ${backoff}ms")
                
                tearDown(op.device.address)
                if (attempt < MAX_RETRIES - 1) delay(backoff)
            }
            Log.e(TAG, "All retries exhausted for ${op.device.address}")
            op.onComplete(false)
            tearDown(op.device.address)
        } finally {
            if (activeOpCount.decrementAndGet() == 0) {
                onQueueActiveChanged(false)
            }
        }
    }"""

gatt_content = gatt_content.replace(old_process_retry, new_process_retry)

# Add pacing delay to WRITE_TYPE_DEFAULT
old_write_next = """                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                        writeNext(gatt, characteristic)
                    } else {"""
                    
new_write_next = """                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                        scope.launch {
                            delay(5) // Lightweight pacing to reduce floods and allow BLE buffers to drain
                            writeNext(gatt, characteristic)
                        }
                    } else {"""
                    
gatt_content = gatt_content.replace(old_write_next, new_write_next)

with open(gatt_file, 'w') as f:
    f.write(gatt_content)


## 2. Update BtlMeshService.kt
btl_file = '/root/BTL/app/src/main/java/com/btl/protocol/data/network/BtlMeshService.kt'
with open(btl_file, 'r') as f:
    btl_content = f.read()

btl_old = """        peerRegistry = PeerRegistry(serviceScope)
        gattQueue = GattOperationQueue(this, serviceScope)

        liveQueue = gattQueue"""

btl_new = """        peerRegistry = PeerRegistry(serviceScope)
        gattQueue = GattOperationQueue(this, serviceScope).apply {
            onQueueActiveChanged = { isActive ->
                if (isActive) {
                    stopScanning()
                } else {
                    if (_meshActive.value) startScanning()
                }
            }
        }

        liveQueue = gattQueue"""

btl_content = btl_content.replace(btl_old, btl_new)

with open(btl_file, 'w') as f:
    f.write(btl_content)

print("Applied BitChat pacing fixes")

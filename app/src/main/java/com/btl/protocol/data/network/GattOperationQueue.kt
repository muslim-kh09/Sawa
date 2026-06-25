package com.btl.protocol.data.network

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "GattOpQueue"
private const val TARGET_MTU = 512
private const val GATT_TIMEOUT_MS = 15_000L
private const val MAX_RETRIES = 1
private const val RETRY_BASE_DELAY_MS = 500L
private const val INTER_OP_COOLDOWN_MS = 20L

data class GattWriteOp(
    val device: BluetoothDevice,
    val payload: ByteArray,
    val messageId: Int = -1,
    val onComplete: (success: Boolean) -> Unit = {}
)

enum class ConnectionState {
    DISCONNECTED, CONNECTING, READY
}

class GattOperationQueue(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var onQueueActiveChanged: (Boolean) -> Unit = {}
    private val activeOpCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val deviceChannels = ConcurrentHashMap<String, Channel<GattWriteOp>>()
    private val deviceStates = ConcurrentHashMap<String, ConnectionState>()
    private val activeGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val deviceMtus = ConcurrentHashMap<String, Int>()

    fun enqueue(op: GattWriteOp) {
        val mac = op.device.address
        val channel = deviceChannels.getOrPut(mac) {
            val newChannel = Channel<GattWriteOp>(Channel.UNLIMITED)
            scope.launch(Dispatchers.IO) {
                for (queuedOp in newChannel) {
                    processWithRetry(queuedOp)
                    delay(INTER_OP_COOLDOWN_MS)
                }
            }
            newChannel
        }
        channel.trySend(op)
    }

    fun close() {
        deviceChannels.values.forEach { it.close() }
        deviceChannels.clear()
        activeGatts.values.forEach { 
            try { it.disconnect(); it.close() } catch (_: SecurityException) {} 
        }
        activeGatts.clear()
        deviceStates.clear()
    }

    private suspend fun processWithRetry(op: GattWriteOp) {
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
    }

    private fun tearDown(mac: String) {
        deviceStates[mac] = ConnectionState.DISCONNECTED
        activeGatts.remove(mac)?.let {
            try { it.disconnect(); it.close() } catch (_: SecurityException) {}
        }
    }

    private suspend fun executeOnce(op: GattWriteOp): Boolean =
        suspendCancellableCoroutine { cont ->
            val mac = op.device.address
            var fragments: List<ByteArray> = emptyList()
            var fragIndex = 0
            val settled = AtomicBoolean(false)

            fun settle(success: Boolean) {
                if (settled.compareAndSet(false, true)) {
                    if (cont.isActive) cont.resume(success)
                }
            }

            fun writeNext(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
                if (fragIndex >= fragments.size) {
                    settle(true)
                    return
                }
                val data = fragments[fragIndex]
                try {
                    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                    val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, data, writeType) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = data
                        @Suppress("DEPRECATION")
                        char.writeType = writeType
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                    if (!initiated) {
                        Log.e(TAG, "writeCharacteristic rejected synchronously")
                        settle(false)
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException during writeCharacteristic", e)
                    settle(false)
                }
            }

            // We reuse the existing GATT connection if READY.
            // If CONNECTING, wait (for simplicity here, we assume one op at a time per device, so CONNECTING means we're in it).
            val existingState = deviceStates[mac] ?: ConnectionState.DISCONNECTED
            val existingGatt = activeGatts[mac]

            if (existingState == ConnectionState.READY && existingGatt != null) {
                // Already connected and ready, just find characteristic and write
                val char = existingGatt.getService(BtlMeshService.MESH_SERVICE_UUID)
                    ?.getCharacteristic(BtlMeshService.CHAR_UUID)
                if (char != null) {
                    // Start writing directly
                    val mtu = deviceMtus[mac] ?: 23
                    fragments = PacketFragmenter.fragment(op.payload, mtu - 3)
                    fragIndex = 0
                    writeNext(existingGatt, char)
                } else {
                    settle(false)
                }
                return@suspendCancellableCoroutine
            }

            // Otherwise, we need to connect
            deviceStates[mac] = ConnectionState.CONNECTING

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "[$mac] Connected — requesting MTU $TARGET_MTU")
                            scope.launch {
                                delay(100)
                                try {
                                    if (!gatt.requestMtu(TARGET_MTU)) {
                                        Log.w(TAG, "requestMtu failed, falling back to MTU 23")
                                        fragments = PacketFragmenter.fragment(op.payload, 20)
                                        fragIndex = 0
                                        delay(50)
                                        if (!gatt.discoverServices()) settle(false)
                                    }
                                } catch (e: SecurityException) {
                                    settle(false)
                                }
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            deviceStates[mac] = ConnectionState.DISCONNECTED
                            activeGatts.remove(mac)
                            if (!settled.get()) {
                                Log.w(TAG, "[$mac] Disconnected unexpectedly, status=$status")
                                settle(false)
                            }
                            try { gatt.close() } catch (_: SecurityException) {}
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    val negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    deviceMtus[gatt.device.address] = negotiatedMtu
                    val maxPayload = negotiatedMtu - 3
                    fragments = PacketFragmenter.fragment(op.payload, maxPayload)
                    fragIndex = 0
                    scope.launch {
                        delay(50)
                        try {
                            if (!gatt.discoverServices()) settle(false)
                        } catch (e: SecurityException) {
                            settle(false)
                        }
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        settle(false)
                        return
                    }
                    val char = gatt.getService(BtlMeshService.MESH_SERVICE_UUID)
                        ?.getCharacteristic(BtlMeshService.CHAR_UUID)
                    if (char == null) {
                        settle(false)
                        return
                    }
                    deviceStates[mac] = ConnectionState.READY
                    activeGatts[mac] = gatt
                    writeNext(gatt, char)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        settle(false)
                        return
                    }
                    val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    fragIndex++
                    
                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                        scope.launch {
                            delay(5) // Lightweight pacing to reduce floods and allow BLE buffers to drain
                            writeNext(gatt, characteristic)
                        }
                    } else {
                        scope.launch {
                            delay(15)
                            writeNext(gatt, characteristic)
                        }
                    }
                }
            }

            try {
                val newGatt = op.device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
                if (newGatt == null) {
                    deviceStates[mac] = ConnectionState.DISCONNECTED
                    if (cont.isActive) cont.resume(false)
                }
            } catch (e: SecurityException) {
                deviceStates[mac] = ConnectionState.DISCONNECTED
                if (cont.isActive) cont.resume(false)
            }

            cont.invokeOnCancellation {
                if (!settled.get()) settle(false)
            }
        }
}

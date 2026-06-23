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

private const val TAG = "GattOpQueue"
private const val TARGET_MTU = 512
private const val GATT_TIMEOUT_MS = 12_000L
private const val MAX_RETRIES = 3
private const val RETRY_BASE_DELAY_MS = 500L
private const val INTER_OP_COOLDOWN_MS = 20L

/**
 * Represents a single write request to a remote BLE peer.
 *
 * @param device       The target BluetoothDevice.
 * @param payload      The raw byte payload to deliver (will be fragmented if needed).
 * @param messageId    The Room DB message ID for status tracking (-1 if not applicable).
 * @param onComplete   Called when all fragments are delivered (true) or all retries exhausted (false).
 */
data class GattWriteOp(
    val device: BluetoothDevice,
    val payload: ByteArray,
    val messageId: Int = -1,
    val onComplete: (success: Boolean) -> Unit = {}
)

/**
 * Serialized GATT operation queue that processes one [GattWriteOp] at a time.
 *
 * For each operation:
 * 1. Connects to the remote device via LE transport
 * 2. Negotiates MTU (target: 512 bytes)
 * 3. Discovers services to locate the Sawa characteristic
 * 4. Sends all payload fragments sequentially, waiting for acknowledgment per fragment
 * 5. Disconnects cleanly on completion or failure
 *
 * On failure, retries up to [MAX_RETRIES] times with exponential backoff.
 */
class GattOperationQueue(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val channel = Channel<GattWriteOp>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (op in channel) {
                processWithRetry(op)
                delay(INTER_OP_COOLDOWN_MS)
            }
        }
    }

    /** Enqueues a write operation. Thread-safe. */
    fun enqueue(op: GattWriteOp) {
        channel.trySend(op)
    }

    /** Shuts down the queue — no more operations will be processed. */
    fun close() = channel.close()

    // ──────────────────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun processWithRetry(op: GattWriteOp) {
        repeat(MAX_RETRIES) { attempt ->
            val success = withTimeoutOrNull(GATT_TIMEOUT_MS) { executeOnce(op) } ?: false
            if (success) {
                op.onComplete(true)
                return
            }
            val backoff = RETRY_BASE_DELAY_MS * (attempt + 1)
            Log.w(TAG, "Attempt ${attempt + 1}/$MAX_RETRIES failed for ${op.device.address}. Retry in ${backoff}ms")
            if (attempt < MAX_RETRIES - 1) delay(backoff)
        }
        Log.e(TAG, "All retries exhausted for ${op.device.address}")
        op.onComplete(false)
    }

    private suspend fun executeOnce(op: GattWriteOp): Boolean =
        suspendCancellableCoroutine { cont ->
            var gattRef: BluetoothGatt? = null
            var negotiatedMtu = 23
            var fragments: List<ByteArray> = emptyList()
            var fragIndex = 0
            val settled = AtomicBoolean(false)

            fun settle(success: Boolean) {
                if (settled.compareAndSet(false, true)) {
                    try { 
                        gattRef?.disconnect()
                        gattRef?.close() // INSTANT CLOSURE to prevent GATT leaks
                    } catch (_: SecurityException) {}
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
                    val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(
                            char,
                            data,
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = data
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

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "[${op.device.address}] Connected — requesting MTU $TARGET_MTU")
                            try {
                                if (!gatt.requestMtu(TARGET_MTU)) {
                                    Log.w(TAG, "requestMtu failed, falling back to MTU 23")
                                    negotiatedMtu = 23
                                    fragments = PacketFragmenter.fragment(op.payload, 20)
                                    fragIndex = 0
                                    if (!gatt.discoverServices()) settle(false)
                                }
                            } catch (e: SecurityException) {
                                Log.e(TAG, "SecurityException requestMtu", e)
                                settle(false)
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            if (!settled.get()) {
                                Log.w(TAG, "[${op.device.address}] Disconnected unexpectedly, status=$status")
                                settle(false)
                            }
                            try { gatt.close() } catch (_: SecurityException) {}
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                    Log.d(TAG, "[${op.device.address}] MTU = $negotiatedMtu")
                    // Fragment the payload now that we know the MTU
                    val maxPayload = negotiatedMtu - 3  // subtract ATT header overhead
                    fragments = PacketFragmenter.fragment(op.payload, maxPayload)
                    fragIndex = 0
                    try {
                        if (!gatt.discoverServices()) {
                            Log.e(TAG, "discoverServices rejected synchronously")
                            settle(false)
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException discoverServices", e)
                        settle(false)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "[${op.device.address}] Service discovery failed: $status")
                        settle(false)
                        return
                    }
                    val char = gatt
                        .getService(BtlMeshService.MESH_SERVICE_UUID)
                        ?.getCharacteristic(BtlMeshService.CHAR_UUID)

                    if (char == null) {
                        Log.e(TAG, "[${op.device.address}] Sawa characteristic not found — not a Sawa node")
                        settle(false)
                        return
                    }
                    writeNext(gatt, char)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "[${op.device.address}] Fragment $fragIndex write failed: $status")
                        settle(false)
                        return
                    }
                    fragIndex++
                    writeNext(gatt, characteristic)
                }
            }

            try {
                gattRef = op.device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
                if (gattRef == null) {
                    Log.e(TAG, "[${op.device.address}] connectGatt returned null")
                    if (cont.isActive) cont.resume(false)
                    return@suspendCancellableCoroutine
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "[${op.device.address}] SecurityException on connectGatt", e)
                if (cont.isActive) cont.resume(false)
                return@suspendCancellableCoroutine
            }

            cont.invokeOnCancellation {
                try { gattRef?.disconnect(); gattRef?.close() } catch (_: SecurityException) {}
            }
        }
}

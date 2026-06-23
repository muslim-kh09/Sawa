package com.btl.protocol.data.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BtlMeshService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "BTL_MESH_SERVICE_CHANNEL"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B71C-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000B71D-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        
        private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
        val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()
        
        var instance: BtlMeshService? = null

        fun transmitGatt(text: String) {
            Log.d("BtlMeshService", "Transmitting encrypted byte array via GATT to nodes: $text")
            instance?.transmit(text.toByteArray(Charsets.UTF_8))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    private var gattServer: BluetoothGattServer? = null

    // Track connected devices
    private val connectedClientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val connectedServerClients = ConcurrentHashMap<String, BluetoothDevice>()
    private val deviceMtuMap = ConcurrentHashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBleMesh()
        startWifiDirectDiscovery()
        return START_STICKY
    }

    private fun updateConnectedPeers() {
        val peers = mutableSetOf<String>()
        peers.addAll(connectedClientGatts.keys)
        peers.addAll(connectedServerClients.keys)
        _connectedPeers.value = peers
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BTL Mesh Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains offline mesh network connections"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sawa Mesh is active")
            .setContentText("Actively routing offline packets...")
            .setOngoing(true)
            .build()
    }

    private fun startBleMesh() {
        if (!bluetoothAdapter.isEnabled) return

        setupGattServer()

        val scanner = bluetoothAdapter.bluetoothLeScanner
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (scanner == null || advertiser == null) {
            Log.e("BtlMeshService", "BLE Scanner or Advertiser is null.")
            return
        }

        try {
            scanner.stopScan(bleScanCallback)
            advertiser.stopAdvertising(advCallback)
        } catch (e: Exception) {
            // Ignore
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
            Log.d("BtlMeshService", "BLE Scanning started.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException scanning", e)
        }

        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(advSettings, advData, advCallback)
            Log.d("BtlMeshService", "BLE Advertising requested.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException advertising", e)
        }
    }

    private fun setupGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            
            val characteristic = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or 
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or 
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or 
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )

            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            characteristic.addDescriptor(cccd)
            
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.d("BtlMeshService", "GATT Server started.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException openGattServer", e)
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                if (!connectedClientGatts.containsKey(device.address)) {
                    try {
                        Log.d("BtlMeshService", "Found device, connecting GATT: ${device.address}")
                        val gatt = device.connectGatt(this@BtlMeshService, false, gattCallback)
                        if (gatt != null) {
                            connectedClientGatts[device.address] = gatt
                            updateConnectedPeers()
                        }
                    } catch (e: SecurityException) {
                        Log.e("BtlMeshService", "SecurityException connectGatt", e)
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BtlMeshService", "GATT Client connected to: $address")
                connectedClientGatts[address] = gatt
                updateConnectedPeers()
                try {
                    // MTU Negotiation
                    val requested = gatt.requestMtu(512)
                    Log.d("BtlMeshService", "Requested MTU 512: $requested")
                } catch (e: SecurityException) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BtlMeshService", "GATT Client disconnected from: $address")
                connectedClientGatts.remove(address)
                deviceMtuMap.remove(address)
                updateConnectedPeers()
                try {
                    gatt.close()
                } catch (e: SecurityException) {}
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BtlMeshService", "MTU changed to $mtu for ${gatt.device.address}")
                deviceMtuMap[gatt.device.address] = mtu
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {}
            } else {
                Log.e("BtlMeshService", "MTU request failed, falling back to 20")
                deviceMtuMap[gatt.device.address] = 23 // Default MTU
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {}
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(MESH_SERVICE_UUID)
                val char = service?.getCharacteristic(CHAR_UUID)
                if (char != null) {
                    try {
                        gatt.setCharacteristicNotification(char, true)
                        // CCCD descriptor (Critical)
                        val descriptor = char.getDescriptor(CCCD_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            Log.d("BtlMeshService", "Wrote CCCD to enable notifications")
                        }
                    } catch (e: SecurityException) {
                        Log.e("BtlMeshService", "Failed to write CCCD", e)
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BtlMeshService", "Message chunk written successfully to GATT")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                Log.d("BtlMeshService", "Received data via Client notification: ${data.size} bytes")
                // In a real app we'd pass this up to the app logic
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BtlMeshService", "GATT Server client connected: $address")
                connectedServerClients[address] = device
                updateConnectedPeers()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BtlMeshService", "GATT Server client disconnected: $address")
                connectedServerClients.remove(address)
                deviceMtuMap.remove(address)
                updateConnectedPeers()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Log.d("BtlMeshService", "Server MTU changed to $mtu for ${device.address}")
            deviceMtuMap[device.address] = mtu
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == CHAR_UUID) {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                    if (value != null) {
                        Log.d("BtlMeshService", "Received data via Server write: ${value.size} bytes")
                        // In a real app we'd pass this up to the app logic
                    }
                } catch (e: SecurityException) {
                    Log.e("BtlMeshService", "Failed to send write response", e)
                }
            } else {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                } catch (e: SecurityException) {}
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val isNotifying = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
                Log.d("BtlMeshService", "CCCD write request for ${device.address}, enabling notifications: $isNotifying")
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                } catch (e: SecurityException) {}
            } else {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                } catch (e: SecurityException) {}
            }
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d("BtlMeshService", "BLE Advertising started successfully.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BtlMeshService", "BLE Advertising failed: $errorCode")
        }
    }

    private fun transmit(payload: ByteArray) {
        // Payload Chunking (Fallback)
        // If MTU fails, fallback to 20 bytes payload (MTU 23 - 3 bytes overhead)
        
        // Send via Server to connected Clients (Notify)
        for ((address, device) in connectedServerClients) {
            val mtu = deviceMtuMap[address] ?: 23
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            val chunks = payload.toList().chunked(chunkSize)
            
            val char = gattServer?.getService(MESH_SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char != null) {
                for (chunk in chunks) {
                    char.value = chunk.toByteArray()
                    try {
                        gattServer?.notifyCharacteristicChanged(device, char, false)
                        Log.d("BtlMeshService", "Sent chunk of ${chunk.size} via Server Notify to $address")
                    } catch (e: SecurityException) {
                        Log.e("BtlMeshService", "Failed to notify characteristic", e)
                    }
                }
            }
        }

        // Send via Client to connected Servers (Write)
        for ((address, gatt) in connectedClientGatts) {
            val mtu = deviceMtuMap[address] ?: 23
            val chunkSize = (mtu - 3).coerceAtLeast(20)
            val chunks = payload.toList().chunked(chunkSize)
            
            val char = gatt.getService(MESH_SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
            if (char != null) {
                for (chunk in chunks) {
                    char.value = chunk.toByteArray()
                    try {
                        gatt.writeCharacteristic(char)
                        Log.d("BtlMeshService", "Sent chunk of ${chunk.size} via Client Write to $address")
                    } catch (e: SecurityException) {
                        Log.e("BtlMeshService", "Failed to write characteristic", e)
                    }
                }
            }
        }
    }

    private fun startWifiDirectDiscovery() {
        try {
            wifiP2pManager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d("BtlMeshService", "Wi-Fi Direct discovery started.")
                }
                override fun onFailure(reasonCode: Int) {
                    Log.e("BtlMeshService", "Wi-Fi Direct discovery failed: $reasonCode")
                }
            })
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "Missing permissions for Wi-Fi Direct", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback)
            
            for (gatt in connectedClientGatts.values) {
                gatt.close()
            }
            connectedClientGatts.clear()
            
            gattServer?.close()
            gattServer = null
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException stopping BLE", e)
        }
    }
}

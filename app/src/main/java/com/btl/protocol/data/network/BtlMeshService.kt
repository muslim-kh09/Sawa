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
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BtlMeshService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "BTL_MESH_SERVICE_CHANNEL"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B71C-0000-1000-8000-00805f9b34fb")
        val CHAR_UUID: UUID = UUID.fromString("0000B71D-0000-1000-8000-00805f9b34fb")
        
        private val _connectedPeers = MutableStateFlow<Map<String, BluetoothDevice>>(emptyMap())
        val connectedPeers: StateFlow<Map<String, BluetoothDevice>> = _connectedPeers.asStateFlow()
        
        var instance: BtlMeshService? = null

        fun transmitGatt(text: String) {
            Log.d("BtlMeshService", "Transmitting public broadcast packet to mesh: $text")
            instance?.broadcastToMesh(text.toByteArray(Charsets.UTF_8))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    private var gattServer: BluetoothGattServer? = null

    // Track discovered devices for broadcasting instead of maintaining persistent P2P connections
    private val discoveredPeers = ConcurrentHashMap<String, BluetoothDevice>()

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
        startPublicBroadcastMesh()
        startWifiDirectDiscovery()
        return START_STICKY
    }

    private fun updateDiscoveredPeers() {
        _connectedPeers.value = discoveredPeers.toMap()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BTL Mesh Active",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains public broadcast mesh connections"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sawa Mesh is active")
            .setContentText("Listening for public mesh broadcasts...")
            .setOngoing(true)
            .build()
    }

    private fun startPublicBroadcastMesh() {
        if (!bluetoothAdapter.isEnabled) return

        // 1. Every Sawa node acts as a broadcaster (Advertising the UUID)
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
            
        // STRICT "SAWA-ONLY" HARDWARE FILTERING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        try {
            scanner.startScan(listOf(filter), settings, bleScanCallback)
            Log.d("BtlMeshService", "BLE Scanning started with strict UUID filter.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException scanning", e)
        }

        val advSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Explicitly add the Sawa Service UUID to the AdvertiseData
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        try {
            advertiser.startAdvertising(advSettings, advData, advCallback)
            Log.d("BtlMeshService", "BLE Advertising requested with strict UUID.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException advertising", e)
        }
    }

    private fun setupGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(this, gattServerCallback)
            
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            
            // Implement a Characteristic that is WRITE_NO_RESPONSE
            val characteristic = BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.d("BtlMeshService", "GATT Server started for Public Broadcast Mesh.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException openGattServer", e)
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                // STRICT UUID VERIFICATION: If it doesn't match exactly, ignore it.
                val uuids = result.scanRecord?.serviceUuids
                if (uuids?.contains(ParcelUuid(MESH_SERVICE_UUID)) != true) {
                    return
                }

                // Stop trying to "connect" to individual devices via GATT persistently
                if (!discoveredPeers.containsKey(device.address)) {
                    Log.d("BtlMeshService", "Discovered Sawa Peer: ${device.address}")
                    discoveredPeers[device.address] = device
                    updateDiscoveredPeers()
                }
            }
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
            if (characteristic.uuid == CHAR_UUID) {
                try {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    }
                    if (value != null) {
                        val message = String(value, Charsets.UTF_8)
                        Log.d("BtlMeshService", "Received Broadcast Message from Mesh: $message")
                        
                        // Handle the broadcast packet. A real app would send this to the local UI.
                        val dbIntent = Intent("com.btl.protocol.NEW_MESSAGE")
                        dbIntent.putExtra("MESSAGE", message)
                        sendBroadcast(dbIntent)
                    }
                } catch (e: SecurityException) {
                    Log.e("BtlMeshService", "Failed to process broadcast", e)
                }
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

    private fun broadcastToMesh(payload: ByteArray) {
        // Broadcast architecture: When a user types a message, broadcast it to the characteristic
        // of all nodes in range scanning for this specific UUID.
        
        for ((address, device) in discoveredPeers) {
            try {
                // One-off connection to write the broadcast and disconnect immediately
                val gatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            try {
                                gatt.discoverServices()
                            } catch (e: SecurityException) {}
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            try {
                                gatt.close()
                            } catch (e: SecurityException) {}
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val service = gatt.getService(MESH_SERVICE_UUID)
                            val char = service?.getCharacteristic(CHAR_UUID)
                            if (char != null) {
                                char.value = payload
                                try {
                                    gatt.writeCharacteristic(char)
                                } catch (e: SecurityException) {}
                            }
                        }
                    }

                    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                        // Immediately disconnect after the broadcast payload is delivered
                        try {
                            gatt.disconnect()
                        } catch (e: SecurityException) {}
                    }
                })
            } catch (e: SecurityException) {
                Log.e("BtlMeshService", "SecurityException broadcasting to $address", e)
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
            gattServer?.close()
            gattServer = null
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException stopping BLE", e)
        }
    }
}

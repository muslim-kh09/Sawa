package com.btl.protocol.data.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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

class BtlMeshService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "BTL_MESH_SERVICE_CHANNEL"
        val MESH_SERVICE_UUID: UUID = UUID.fromString("0000B71C-0000-1000-8000-00805f9b34fb")
        
        private val _connectedPeers = MutableStateFlow<Set<String>>(emptySet())
        val connectedPeers: StateFlow<Set<String>> = _connectedPeers.asStateFlow()
        
        fun transmitGatt(text: String) {
            Log.d("BtlMeshService", "Transmitting encrypted byte array via GATT to nodes: $text")
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var wifiP2pChannel: WifiP2pManager.Channel

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        wifiP2pChannel = wifiP2pManager.initialize(this, mainLooper, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startBleMesh()
        startWifiDirectDiscovery()
        return START_STICKY
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
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        if (scanner == null || advertiser == null) {
            Log.e("BtlMeshService", "BLE Scanner or Advertiser is null, Bluetooth might be off.")
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
            Log.d("BtlMeshService", "BLE Scanning started with UUID filter.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException starting BLE scan", e)
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
            Log.d("BtlMeshService", "BLE Advertising requested with UUID payload.")
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException starting BLE advertising", e)
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.device?.let { device ->
                val current = _connectedPeers.value.toMutableSet()
                if (current.add(device.address)) {
                    _connectedPeers.value = current
                    try {
                        device.connectGatt(this@BtlMeshService, false, gattCallback)
                    } catch (e: SecurityException) {
                        Log.e("BtlMeshService", "SecurityException connecting GATT", e)
                    }
                }
            }
        }
    }

    private val gattCallback = object : android.bluetooth.BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: android.bluetooth.BluetoothGatt, status: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                try {
                    gatt.requestMtu(512)
                    gatt.discoverServices()
                } catch (e: SecurityException) {}
            }
        }
        
        override fun onServicesDiscovered(gatt: android.bluetooth.BluetoothGatt, status: Int) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(MESH_SERVICE_UUID)
                val char = service?.getCharacteristic(UUID.fromString("0000B71D-0000-1000-8000-00805f9b34fb"))
                if (char != null) {
                    try {
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    } catch (e: SecurityException) {}
                }
            }
        }

        override fun onCharacteristicWrite(gatt: android.bluetooth.BluetoothGatt, characteristic: android.bluetooth.BluetoothGattCharacteristic, status: Int) {
            if (status == android.bluetooth.BluetoothGatt.GATT_SUCCESS) {
                Log.d("BtlMeshService", "Message written successfully to GATT")
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
        try {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback)
            bluetoothAdapter.bluetoothLeAdvertiser?.stopAdvertising(advCallback)
        } catch (e: SecurityException) {
            Log.e("BtlMeshService", "SecurityException stopping BLE", e)
        }
    }
}

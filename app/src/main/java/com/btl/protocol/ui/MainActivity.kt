package com.btl.protocol.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.btl.protocol.data.network.BtlMeshService
import dagger.hilt.android.AndroidEntryPoint
import com.btl.protocol.ui.screens.EmergencyDashboardScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private var allPermissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            checkBluetoothAndStart()
        } else {
            allPermissionsGranted = false
        }
    }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            allPermissionsGranted = true
            startMeshService()
        } else {
            allPermissionsGranted = false
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        
        requestPermissions()

        setContent {
            if (allPermissionsGranted) {
                EmergencyDashboardScreen()
            } else {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Permissions are required for the Mesh Network")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { requestPermissions() }) {
                            Text("Grant Permissions")
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }

    private fun checkBluetoothAndStart() {
        val bm = getSystemService(android.bluetooth.BluetoothManager::class.java)
        val btAdapter = bm.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothLauncher.launch(enableBtIntent)
        } else {
            allPermissionsGranted = true
            startMeshService()
        }
    }

    private fun startMeshService() {
        val intent = Intent(this, BtlMeshService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.room.Room
import com.btl.protocol.data.network.BtlMeshService
import com.btl.protocol.data.repository.MeshDatabase
import dagger.hilt.android.AndroidEntryPoint
import com.btl.protocol.ui.screens.EmergencyDashboardScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    private var allPermissionsGranted by mutableStateOf(false)
    private lateinit var db: MeshDatabase

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
        
        db = Room.databaseBuilder(applicationContext, MeshDatabase::class.java, "btl_mesh_ledger.db")
            .fallbackToDestructiveMigration()
            .build()
        
        requestPermissions()

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!allPermissionsGranted) {
                    requestPermissions()
                }
            }
        })

        setContent {
            if (allPermissionsGranted) {
                EmergencyDashboardScreen(db.messageDao())
            } else {
                OnboardingScreen(onRetry = { requestPermissions() })
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
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

@Composable
fun OnboardingScreen(onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Welcome to Sawa",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "To build a secure, zero-latency offline mesh network, Sawa needs access to your Location and Bluetooth.\n\nPlease grant the required permissions when prompted so devices can discover each other.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onRetry) {
                Text("Grant Permissions & Continue")
            }
        }
    }
}

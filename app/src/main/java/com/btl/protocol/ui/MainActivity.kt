package com.btl.protocol.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.btl.protocol.data.network.BtlMeshService
import com.btl.protocol.ui.screens.ChatScreen
import com.btl.protocol.ui.screens.OnboardingScreen
import com.btl.protocol.ui.theme.SawaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point for the Sawa application.
 *
 * ## Responsibilities
 * - Manages the runtime permission request lifecycle via [SmartPermissionHandler].
 * - Prompts the user to enable Bluetooth if needed.
 * - Starts [BtlMeshService] as a foreground service once all preconditions are met.
 * - Routes to [OnboardingScreen] or [ChatScreen] based on current state.
 *
 * ## What changed from the original
 * - Removed inline Room database instantiation (was causing double-instantiation bug).
 * - Permission logic extracted to [SmartPermissionHandler] — no more duplicated arrays.
 * - Navigation is reactive: State objects drive which screen is shown.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)

    // ──────────────────────────────────────────────────────────────────────────
    // Permission + BT launchers
    // ──────────────────────────────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (permissionsGranted) checkBluetoothAndStart()
    }

    private val bluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        bluetoothEnabled = (result.resultCode == RESULT_OK)
        if (bluetoothEnabled) startMeshService()
    }

    // Listens for Bluetooth state changes while the app is open
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                bluetoothEnabled = (state == BluetoothAdapter.STATE_ON)
                if (bluetoothEnabled && permissionsGranted) startMeshService()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Re-evaluate state each time the app comes to the foreground
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshState()
        })

        refreshState()

        setContent {
            SawaTheme {
                val pGranted = permissionsGranted
                val btOn = bluetoothEnabled

                if (pGranted && btOn) {
                    ChatScreen()
                } else {
                    OnboardingScreen(
                        permissionsGranted = pGranted,
                        bluetoothEnabled = btOn,
                        onRequestPermissions = { requestAllPermissions() },
                        onEnableBluetooth = {
                            bluetoothLauncher.launch(
                                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(btStateReceiver)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun refreshState() {
        permissionsGranted = SmartPermissionHandler.allGranted(this)
        bluetoothEnabled = getSystemService(android.bluetooth.BluetoothManager::class.java)
            ?.adapter?.isEnabled == true
        if (permissionsGranted && bluetoothEnabled) startMeshService()
    }

    private fun requestAllPermissions() {
        permissionLauncher.launch(SmartPermissionHandler.required().toTypedArray())
    }

    private fun checkBluetoothAndStart() {
        val btAdapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            bluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            bluetoothEnabled = true
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

// ══════════════════════════════════════════════════════════════════════════════
// Smart Permission Handler
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Centralised, API-level-aware permission manager.
 *
 * Eliminates the duplicated permission arrays that existed in the original
 * [MainActivity] (the same list was copy-pasted in three separate functions).
 */
object SmartPermissionHandler {

    /** Returns the complete list of permissions required on this API level. */
    fun required(): List<String> = buildList {
        // Location — required for BLE scanning on all API levels
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)

        // Legacy Bluetooth permissions (API < 31)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Modern Bluetooth permissions (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Notification permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Returns true if every required permission is currently granted. */
    fun allGranted(context: Context): Boolean =
        required().all { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
}

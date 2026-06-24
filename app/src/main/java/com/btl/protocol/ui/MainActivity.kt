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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.btl.protocol.data.network.BtlMeshService
import com.btl.protocol.ui.screens.ChatScreen
import com.btl.protocol.ui.screens.OnboardingScreen
import com.btl.protocol.ui.screens.SettingsScreen
import com.btl.protocol.ui.theme.SawaTheme
import com.btl.protocol.data.ota.OtaUpdateManager
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.btl.protocol.ui.utils.parseMarkdown
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
class MainActivity : FragmentActivity() {

    private var appUnlocked by mutableStateOf(false)
    private var isAppLockEnabled by mutableStateOf(false)
    private var permissionsGranted by mutableStateOf(false)
    private var bluetoothEnabled by mutableStateOf(false)
    private var availableUpdate by mutableStateOf<OtaUpdateManager.UpdateInfo?>(null)

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BtlMeshService.initIdentity(this)

        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Re-evaluate state each time the app comes to the foreground
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshState()
        })

        val prefs = getSharedPreferences("SawaSettings", Context.MODE_PRIVATE)
        isAppLockEnabled = prefs.getBoolean("appLockEnabled", false)
        if (!isAppLockEnabled) appUnlocked = true

        refreshState()

        // Check for OTA updates in the background
        lifecycleScope.launch {
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (e: Exception) {
                "1.0.0"
            }
            val update = OtaUpdateManager.checkForUpdates(versionName ?: "1.0.0")
            if (update != null) {
                availableUpdate = update
            }
        }

        setContent {
            val prefs = getSharedPreferences("SawaSettings", Context.MODE_PRIVATE)
            var themePref by remember { mutableIntStateOf(prefs.getInt("themePref", 0)) }
            var currentRoute by remember { mutableStateOf("CHAT") }

            SawaTheme(themePreference = themePref) {
                val pGranted = permissionsGranted
                val btOn = bluetoothEnabled

                if (!appUnlocked) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Sawa is Locked", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.height(32.dp))
                            Button(onClick = { authenticateUser() }) {
                                Text("Unlock", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                } else if (pGranted && btOn) {
                    if (currentRoute == "SETTINGS") {
                        androidx.activity.compose.BackHandler { currentRoute = "CHAT" }
                        SettingsScreen(
                            onNavigateBack = { currentRoute = "CHAT" },
                            themePreference = themePref,
                            onThemeChange = { 
                                themePref = it
                                prefs.edit().putInt("themePref", it).apply()
                            },
                            isAppLockEnabled = isAppLockEnabled,
                            onToggleAppLock = { enabled ->
                                isAppLockEnabled = enabled
                                prefs.edit().putBoolean("appLockEnabled", enabled).apply()
                            }
                        )
                    } else {
                        var currentChat by remember { mutableStateOf("PUBLIC") }
                        androidx.activity.compose.BackHandler(enabled = currentChat != "PUBLIC") {
                            currentChat = "PUBLIC"
                        }
                        ChatScreen(
                            conversationId = currentChat,
                            onNavigateToDm = { currentChat = it },
                            onSettingsClick = { currentRoute = "SETTINGS" }
                        )
                    }
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

                // Show OTA Update Dialog if available
                availableUpdate?.let { update ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { availableUpdate = null },
                        title = { androidx.compose.material3.Text("Update Available: v${update.versionName}") },
                        text = { androidx.compose.material3.Text(update.releaseNotes.parseMarkdown()) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                availableUpdate = null
                                OtaUpdateManager.downloadAndInstall(this@MainActivity, update.downloadUrl)
                            }) {
                                androidx.compose.material3.Text("Download & Install")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { availableUpdate = null }) {
                                androidx.compose.material3.Text("Later")
                            }
                        }
                    )
                }
            }
        }
        
        // Launch Biometric Prompt on startup if enabled
        if (isAppLockEnabled) {
            authenticateUser()
        }
    }

    private fun authenticateUser() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometric/pin hardware or not set up. Just let them in.
            appUnlocked = true
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    appUnlocked = true
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Sawa")
            .setSubtitle("Enter your phone's lock screen password, PIN, or scan your fingerprint to continue.")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
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

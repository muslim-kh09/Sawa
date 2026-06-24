package com.btl.protocol.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btl.protocol.data.network.BtlMeshService
import com.btl.protocol.data.network.SawaPeer
import com.btl.protocol.data.repository.MeshRepository
import com.btl.protocol.data.repository.Message
import com.btl.protocol.data.repository.STATUS_PENDING
import com.btl.protocol.data.repository.STATUS_SENT
import com.btl.protocol.data.repository.STATUS_DELIVERED
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MeshViewModel"

/**
 * ViewModel that bridges the [BtlMeshService] with the Compose UI layer.
 *
 * ## Responsibilities
 * - Exposes [messages] (Room Flow → StateFlow) for the chat UI to observe.
 * - Exposes [peers] (Service companion StateFlow) for the peer indicator.
 * - Exposes [meshActive] to show/hide the mesh status banner.
 * - [sendMessage]: inserts a PENDING message in Room, then enqueues the GATT
 *   transmission. On completion, updates the message status to SENT or FAILED.
 *
 * The ViewModel does NOT hold a direct reference to the Service. Communication
 * goes through [MeshRepository] (for persistence) and [BtlMeshService.enqueueTransmit]
 * (for transmission via the static companion surface). This avoids lifecycle coupling.
 */
@HiltViewModel
class MeshViewModel @Inject constructor(
    private val repository: MeshRepository
) : ViewModel() {

    /** Live stream of all messages, ascending by timestamp. */
    val messages: StateFlow<List<Message>> = repository.observeMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Live map of currently active mesh peers (address → SawaPeer). */
    val peers: StateFlow<Map<String, SawaPeer>> = BtlMeshService.peers

    /** True when the mesh service is running and BLE is active. */
    val meshActive: StateFlow<Boolean> = BtlMeshService.meshActive

    /** Map of node IDs to PeerIdentity (Full Fingerprint + Display Name) */
    val knownIdentities: StateFlow<Map<String, BtlMeshService.Companion.PeerIdentity>> = BtlMeshService.knownIdentities

    // ──────────────────────────────────────────────────────────────────────────
    // Public actions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends a text message over the mesh.
     *
     * 1. Inserts a [STATUS_PENDING] message to Room immediately (optimistic UI).
     * 2. Builds the wire payload via [BtlMeshService.buildOutgoingPayload].
     * 3. Enqueues transmission via [BtlMeshService.enqueueTransmit].
     * 4. On completion, updates status to [STATUS_SENT] (≥1 peer ACKed) or leaves
     *    as PENDING if no peers were reachable.
     */
    fun sendMessage(text: String, conversationId: String = "PUBLIC") {
        if (text.isBlank()) return
        viewModelScope.launch {
            val msgId = java.util.UUID.randomUUID().toString()
            val rowId = repository.addMessage(
                Message(messageId = msgId, isMe = true, text = text, status = STATUS_PENDING, conversationId = conversationId)
            )
            if (rowId == -1L) return@launch
            Log.d(TAG, "Message inserted, rowId=$rowId, enqueuing transmission…")

            val payload = BtlMeshService.buildPayloadStatic(text, msgId, conversationId)
            if (payload == null) {
                Log.w(TAG, "Service not running — cannot build payload for message $rowId")
                return@launch
            }

            BtlMeshService.enqueueTransmit(payload, rowId) { success ->
                viewModelScope.launch {
                    if (success) {
                        repository.updateStatus(rowId, STATUS_DELIVERED)
                        Log.d(TAG, "Message $rowId delivered ✓")
                    } else {
                        Log.w(TAG, "Message $rowId — no peers acknowledged delivery")
                    }
                }
            }
        }
    }

    fun sendImageMessage(context: Context, uri: android.net.Uri, conversationId: String = "PUBLIC") {
        viewModelScope.launch {
            val msgId = java.util.UUID.randomUUID().toString()
            val rowId = repository.addMessage(
                Message(
                    messageId = msgId,
                    isMe = true,
                    text = "📷 Image",
                    status = STATUS_PENDING,
                    conversationId = conversationId,
                    mediaUri = uri.toString(),
                    mediaType = "image"
                )
            )
            if (rowId == -1L) return@launch

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val compressedBytes = com.btl.protocol.data.network.ImageUtils.processImage(context, uri)
                if (compressedBytes != null) {
                    val payload = BtlMeshService.buildMediaPayloadStatic(msgId, conversationId, compressedBytes, "image")
                    if (payload != null) {
                        BtlMeshService.enqueueTransmit(payload, rowId) { success ->
                            viewModelScope.launch {
                                if (success) {
                                    repository.updateStatus(rowId, STATUS_DELIVERED)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Sends an SOS broadcast with maximum priority and appends location.
     */
    @SuppressLint("MissingPermission")
    fun sendSos(context: Context) {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (!hasFine && !hasCoarse) {
            sendMessage("[🆘 SOS] I need immediate help! Location unknown. Please relay.")
            return
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)

        if (!isGpsEnabled && !isNetworkEnabled) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, "Please enable system GPS/Location services!", android.widget.Toast.LENGTH_LONG).show()
            }
            sendMessage("[🆘 SOS] I need immediate help! Location unknown. Please relay.")
            return
        }

        fun fallbackToNativeLocation() {
            try {
                val provider = if (isGpsEnabled && hasFine) android.location.LocationManager.GPS_PROVIDER else android.location.LocationManager.NETWORK_PROVIDER
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(
                        provider,
                        null,
                        androidx.core.content.ContextCompat.getMainExecutor(context)
                    ) { location ->
                        if (location != null) {
                            val lat = String.format(java.util.Locale.US, "%.4f", location.latitude)
                            val lon = String.format(java.util.Locale.US, "%.4f", location.longitude)
                            sendMessage("[🆘 SOS] I need immediate help! Location: $lat, $lon. Please relay.")
                        } else {
                            sendMessage("[🆘 SOS] I need immediate help! Location unknown. Please relay.")
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        provider,
                        object : android.location.LocationListener {
                            override fun onLocationChanged(location: android.location.Location) {
                                val lat = String.format(java.util.Locale.US, "%.4f", location.latitude)
                                val lon = String.format(java.util.Locale.US, "%.4f", location.longitude)
                                sendMessage("[🆘 SOS] I need immediate help! Location: $lat, $lon. Please relay.")
                            }
                            override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                            override fun onProviderEnabled(p: String) {}
                            override fun onProviderDisabled(p: String) {}
                        },
                        android.os.Looper.getMainLooper()
                    )
                }
            } catch (e: Exception) {
                sendMessage("[🆘 SOS] I need immediate help! Location unknown. Please relay.")
            }
        }

        try {
            // Fast check for Play Services
            val apiAvailability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            if (apiAvailability.isGooglePlayServicesAvailable(context) != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                fallbackToNativeLocation()
                return
            }

            val client = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
            val priority = if (hasFine) {
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            } else {
                com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()

            client.getCurrentLocation(priority, cancellationTokenSource.token).addOnSuccessListener { location ->
                if (location != null) {
                    val lat = String.format(java.util.Locale.US, "%.4f", location.latitude)
                    val lon = String.format(java.util.Locale.US, "%.4f", location.longitude)
                    sendMessage("[🆘 SOS] I need immediate help! Location: $lat, $lon. Please relay.")
                } else {
                    fallbackToNativeLocation()
                }
            }.addOnFailureListener {
                fallbackToNativeLocation()
            }
        } catch (t: Throwable) {
            fallbackToNativeLocation()
        }
    }

    /**
     * PANIC MODE (وضع الذعر)
     * Clears local database and in-memory cryptographic states.
     */
    fun panicWipe() {
        viewModelScope.launch {
            repository.purgeDatabase()
            BtlMeshService.panicWipe()
            Log.e(TAG, "🚨 PANIC MODE ACTIVATED. All data wiped.")
        }
    }

    fun updateDisplayName(context: Context, newName: String) {
        if (newName.isNotBlank()) {
            BtlMeshService.updateDisplayName(context, newName.trim())
        }
    }
}

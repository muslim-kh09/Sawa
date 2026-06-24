package com.btl.protocol.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

// ──────────────────────────────────────────────────────────────────────────────
// Onboarding screen
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Premium onboarding flow shown before permissions are granted or Bluetooth enabled.
 *
 * Shows a two-step card:
 * 1. Grant required permissions
 * 2. Enable Bluetooth
 *
 * Each step animates in/out with a subtle slide + fade transition.
 */
@Composable
fun OnboardingScreen(
    permissionsGranted: Boolean,
    bluetoothEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // ── Logo / Hero
            HeroSection()

            // ── Step cards
            StepCard(
                stepNumber = 1,
                title = "Grant Permissions",
                description = "Sawa needs Bluetooth and Location access to discover nearby devices and form an offline mesh network.",
                icon = Icons.Rounded.Security,
                isDone = permissionsGranted,
                isActive = !permissionsGranted,
                actionLabel = "Grant Permissions",
                onAction = onRequestPermissions
            )

            StepCard(
                stepNumber = 2,
                title = "Enable Bluetooth",
                description = "Turn on Bluetooth so Sawa can discover peers, advertise its presence, and relay messages through the mesh.",
                icon = Icons.Rounded.Bluetooth,
                isDone = bluetoothEnabled,
                isActive = permissionsGranted && !bluetoothEnabled,
                actionLabel = "Enable Bluetooth",
                onAction = onEnableBluetooth
            )

            // ── Security note
            SecurityNote()

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Hero section
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeroSection() {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "rotate"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(120.dp)
        ) {
            // Rotating outer ring
            Canvas(modifier = Modifier.fillMaxSize().rotate(rotation)) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), Color.Transparent, MaterialTheme.colorScheme.primary)
                    ),
                    style = Stroke(width = 2f)
                )
            }
            // Pulsing inner circle
            Box(
                modifier = Modifier
                    .size(88.dp * pulse)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Sawa",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Decentralized · Offline · Encrypted",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "A peer-to-peer BLE mesh communication network that works without internet, cell towers, or servers.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Step card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun StepCard(
    stepNumber: Int,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isDone: Boolean,
    isActive: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val successColor = Color(0xFF22C55E)
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    val borderColor = when {
        isDone   -> successColor
        isActive -> primary
        else     -> mutedColor.copy(alpha = 0.2f)
    }
    val bgColor = when {
        isDone   -> successColor.copy(alpha = 0.05f)
        isActive -> primary.copy(alpha = 0.05f)
        else     -> MaterialTheme.colorScheme.surface
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Step badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDone) successColor.copy(alpha = 0.2f)
                            else if (isActive) primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = successColor,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Text(
                            "$stepNumber",
                            color = if (isActive) primary else mutedColor,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = if (isDone) successColor else if (isActive) MaterialTheme.colorScheme.onBackground else mutedColor,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDone) successColor else if (isActive) primary else mutedColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                description,
                color = mutedColor,
                style = MaterialTheme.typography.bodyLarge
            )

            AnimatedVisibility(
                visible = isActive,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            actionLabel,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (isDone) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "✓ Complete",
                    color = successColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Security note
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun SecurityNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Sawa never connects to the internet. All messages are relayed exclusively over Bluetooth Low Energy between Sawa devices in physical proximity. No servers, no accounts, no tracking.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

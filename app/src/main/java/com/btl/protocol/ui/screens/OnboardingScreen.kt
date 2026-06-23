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
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

// ──────────────────────────────────────────────────────────────────────────────
// Onboarding colour tokens — matching chat screen for visual continuity
// ──────────────────────────────────────────────────────────────────────────────

private val ObBackground   = Color(0xFF0D1117)
private val ObSurface      = Color(0xFF161B22)
private val ObTeal         = Color(0xFF00A884)
private val ObTealDark     = Color(0xFF075E54)
private val ObTextPrimary  = Color(0xFFE6EDF3)
private val ObTextMuted    = Color(0xFF8B949E)
private val ObSuccess      = Color(0xFF25D366)
private val ObWarning      = Color(0xFFFF9800)

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
            .background(ObBackground),
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
                        listOf(ObTeal, ObTealDark, Color.Transparent, ObTeal)
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
                                ObTealDark.copy(alpha = 0.8f),
                                Color(0xFF001F1A)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Hub,
                    contentDescription = null,
                    tint = ObTeal,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Sawa",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = ObTextPrimary,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Decentralized · Offline · Encrypted",
            fontSize = 13.sp,
            color = ObTeal,
            letterSpacing = 0.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A peer-to-peer BLE mesh communication network that works without internet, cell towers, or servers.",
            fontSize = 13.sp,
            color = ObTextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
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
    val borderColor = when {
        isDone   -> ObSuccess
        isActive -> ObTeal
        else     -> ObTextMuted.copy(alpha = 0.2f)
    }
    val bgColor = when {
        isDone   -> ObSuccess.copy(alpha = 0.05f)
        isActive -> ObTeal.copy(alpha = 0.05f)
        else     -> ObSurface
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Step badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isDone) ObSuccess.copy(alpha = 0.2f)
                            else if (isActive) ObTeal.copy(alpha = 0.15f)
                            else ObSurface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = ObSuccess,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            "$stepNumber",
                            color = if (isActive) ObTeal else ObTextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = if (isDone) ObSuccess else if (isActive) ObTextPrimary else ObTextMuted,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDone) ObSuccess else if (isActive) ObTeal else ObTextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                description,
                color = ObTextMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            AnimatedVisibility(
                visible = isActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ObTeal,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            actionLabel,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            if (isDone) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "✓ Complete",
                    color = ObSuccess,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
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
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161B22))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = ObTeal,
            modifier = Modifier.size(16.dp).padding(top = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Sawa never connects to the internet. All messages are relayed exclusively over Bluetooth Low Energy between Sawa devices in physical proximity. No servers, no accounts, no tracking.",
            color = ObTextMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

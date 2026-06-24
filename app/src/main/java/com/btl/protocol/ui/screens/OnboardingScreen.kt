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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.btl.protocol.R

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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            HeroSection()

            StepCard(
                stepNumber = 1,
                title = stringResource(R.string.step_1_title),
                description = stringResource(R.string.step_1_desc),
                icon = Icons.Rounded.Security,
                isDone = permissionsGranted,
                isActive = !permissionsGranted,
                actionLabel = stringResource(R.string.step_1_action),
                onAction = onRequestPermissions
            )

            StepCard(
                stepNumber = 2,
                title = stringResource(R.string.step_2_title),
                description = stringResource(R.string.step_2_desc),
                icon = Icons.Rounded.Bluetooth,
                isDone = bluetoothEnabled,
                isActive = permissionsGranted && !bluetoothEnabled,
                actionLabel = stringResource(R.string.step_2_action),
                onAction = onEnableBluetooth
            )

            SecurityNote()

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HeroSection() {
    val infiniteTransition = rememberInfiniteTransition(label = "hero")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.primary).padding(24.dp)
    ) {
        Text(
            text = "< " + stringResource(R.string.init_sequence) + " >",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.init_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = pulse))
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.init_full_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

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
    val successColor = MaterialTheme.colorScheme.secondary
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant

    val borderColor = when {
        isDone   -> successColor
        isActive -> primary
        else     -> mutedColor.copy(alpha = 0.5f)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(2.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "0$stepNumber // ",
                    color = if (isDone) successColor else if (isActive) primary else mutedColor,
                    style = MaterialTheme.typography.titleLarge
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = if (isDone) successColor else if (isActive) MaterialTheme.colorScheme.onBackground else mutedColor,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                description,
                color = mutedColor,
                style = MaterialTheme.typography.bodyLarge
            )

            AnimatedVisibility(
                visible = isActive,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth().border(2.dp, primary),
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primary.copy(alpha = 0.1f),
                            contentColor = primary
                        ),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Text(
                            "> " + actionLabel + " _",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            if (isDone) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.status_complete),
                    color = successColor,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun SecurityNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            stringResource(R.string.security_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

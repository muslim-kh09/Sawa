package com.btl.protocol.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.btl.protocol.ui.MeshViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    themePreference: Int,
    onThemeChange: (Int) -> Unit,
    glassmorphismEnabled: Boolean,
    onToggleGlassmorphism: (Boolean) -> Unit,
    isAppLockEnabled: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    viewModel: MeshViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf(com.btl.protocol.data.network.BtlMeshService.DISPLAY_NAME) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Theme Section
            SettingsCard(title = "Appearance", icon = Icons.Default.Palette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeOption(label = "System", selected = themePreference == 0) { onThemeChange(0) }
                    ThemeOption(label = "Light", selected = themePreference == 1) { onThemeChange(1) }
                    ThemeOption(label = "Dark", selected = themePreference == 2) { onThemeChange(2) }
                    ThemeOption(label = "AMOLED", selected = themePreference == 3) { onThemeChange(3) }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Frosted Glass UI", color = MaterialTheme.colorScheme.onBackground)
                    Switch(
                        checked = glassmorphismEnabled,
                        onCheckedChange = onToggleGlassmorphism
                    )
                }
            }

            // Security Section
            SettingsCard(title = "Security", icon = Icons.Default.Lock) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("App Lock (Biometrics/PIN)", color = MaterialTheme.colorScheme.onBackground)
                    Switch(
                        checked = isAppLockEnabled,
                        onCheckedChange = onToggleAppLock
                    )
                }
            }

            // Identity Section
            SettingsCard(title = "Identity", icon = Icons.Default.Person) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.updateDisplayName(context, displayName) },
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Save Name")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Cryptographic Fingerprint", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(com.btl.protocol.data.network.BtlMeshService.LOCAL_DEVICE_ID, fontSize = 10.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
            }
            content()
        }
    }
}

@Composable
private fun ThemeOption(modifier: Modifier = Modifier, label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        label = { 
            Text(
                text = label,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible
            ) 
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            labelColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

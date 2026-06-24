package com.btl.protocol.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.btl.protocol.R
import com.btl.protocol.ui.MeshViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    themePreference: Int,
    onThemeChange: (Int) -> Unit,
    isAppLockEnabled: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    viewModel: MeshViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var displayName by remember { mutableStateOf(com.btl.protocol.data.network.BtlMeshService.DISPLAY_NAME) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            
            // Appearance Section
            SettingsGroup(title = stringResource(R.string.appearance).uppercase()) {
                SettingsRow(
                    icon = Icons.Default.Palette,
                    iconBgColor = Color(0xFF007AFF),
                    title = "Theme",
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeOption(label = "System", selected = themePreference == 0) { onThemeChange(0) }
                            ThemeOption(label = "Light", selected = themePreference == 1) { onThemeChange(1) }
                            ThemeOption(label = "Dark", selected = themePreference == 2) { onThemeChange(2) }
                        }
                    }
                )
                
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 56.dp))
                
                val prefs = context.getSharedPreferences("SawaSettings", Context.MODE_PRIVATE)
                val currentLang = prefs.getString("app_language", "ar") ?: "ar"
                val activity = context as? android.app.Activity
                
                SettingsRow(
                    icon = androidx.compose.material.icons.filled.Language,
                    iconBgColor = Color(0xFFFF9500),
                    title = stringResource(R.string.language),
                    content = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeOption(label = "عربي", selected = currentLang == "ar") { 
                                prefs.edit().putString("app_language", "ar").apply()
                                activity?.recreate()
                            }
                            ThemeOption(label = "English", selected = currentLang == "en") { 
                                prefs.edit().putString("app_language", "en").apply()
                                activity?.recreate()
                            }
                        }
                    }
                )
            }

            // Security Section
            SettingsGroup(title = stringResource(R.string.security).uppercase()) {
                SettingsRow(
                    icon = Icons.Default.Lock,
                    iconBgColor = Color(0xFF34C759),
                    title = stringResource(R.string.app_lock),
                    content = {
                        Switch(
                            checked = isAppLockEnabled,
                            onCheckedChange = onToggleAppLock,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                )
            }

            // Identity Section
            SettingsGroup(title = stringResource(R.string.identity).uppercase()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.display_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.updateDisplayName(context, displayName) },
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.save_name))
                    }
                }
                
                Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), modifier = Modifier.padding(start = 56.dp))
                
                SettingsRow(
                    icon = Icons.Default.Fingerprint,
                    iconBgColor = Color(0xFF8E8E93),
                    title = stringResource(R.string.crypto_fingerprint),
                    subtitle = com.btl.protocol.data.network.BtlMeshService.LOCAL_DEVICE_ID
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBgColor: Color,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBgColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (content != null) {
            content()
        } else if (onClick != null) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

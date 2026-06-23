package com.btl.protocol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.btl.protocol.ui.MeshViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onNavigateToChat: (String) -> Unit,
    viewModel: MeshViewModel = hiltViewModel()
) {
    val peers by viewModel.peers.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Mesh Chats", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text("Global Mesh Broadcast", color = Color.White) },
                    supportingContent = { Text("Public Channel", color = Color.Gray) },
                    leadingContent = { Icon(Icons.Filled.Public, contentDescription = null, tint = Color.White) },
                    modifier = Modifier.clickable { onNavigateToChat("PUBLIC") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = Color.DarkGray)
            }
            items(peers.values.toList()) { peer ->
                ListItem(
                    headlineContent = { Text("Peer: ${peer.address.take(8)}...", color = Color.White) },
                    supportingContent = { Text("End-to-End Encrypted DM", color = Color(0xFF00A884)) },
                    leadingContent = { Icon(Icons.Filled.Lock, contentDescription = null, tint = Color(0xFF00A884)) },
                    modifier = Modifier.clickable { onNavigateToChat(peer.address) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = Color.DarkGray)
            }
        }
    }
}

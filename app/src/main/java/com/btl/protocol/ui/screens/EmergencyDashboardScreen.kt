package com.btl.protocol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btl.protocol.data.network.BtlMeshService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyDashboardScreen() {
    val connectedPeers by BtlMeshService.connectedPeers.collectAsState()
    var messageText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf(
        Pair("system", "Channel encrypted (X25519) & verified (Ed25519)"),
    ) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sawa", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54)),
                actions = {
                    IconButton(onClick = {
                        messages.add(Pair("me", "[SOS] I need help!"))
                    }) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = "SOS", tint = Color.Red)
                    }
                }
            )
        },
        containerColor = Color(0xFFECE5DD)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (connectedPeers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF128C7E)).padding(4.dp), contentAlignment = Alignment.Center) {
                    Text("Scanning for peers...", color = Color.White, fontSize = 12.sp)
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF25D366)).padding(4.dp), contentAlignment = Alignment.Center) {
                    Text("${connectedPeers.size} active mesh peers connected", color = Color.White, fontSize = 12.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages.size) { index ->
                    val msg = messages[index]
                    val isMe = msg.first == "me"
                    val isSystem = msg.first == "system"

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isSystem) Arrangement.Center else if (isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSystem) Color(0xFFFFF59D) else if (isMe) Color(0xFFDCF8C6) else Color.White)
                                .padding(12.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Text(text = msg.second, color = Color.Black, fontSize = 14.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)),
                    placeholder = { Text("Message") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FloatingActionButton(
                    onClick = {
                        if (messageText.isNotBlank()) {
                            messages.add(Pair("me", messageText))
                            messageText = ""
                        }
                    },
                    containerColor = Color(0xFF128C7E),
                    shape = CircleShape
                ) {
                    Icon(imageVector = Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                }
            }
        }
    }
}

package com.btl.protocol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.btl.protocol.data.repository.MessageDao
import com.btl.protocol.data.repository.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyDashboardScreen(messageDao: MessageDao) {
    val connectedPeersSet by BtlMeshService.connectedPeers.collectAsState()
    val connectedPeers = connectedPeersSet.toList()
    var messageText by remember { mutableStateOf("") }
    val messages by messageDao.getMessages().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sawa", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54)),
                actions = {
                    IconButton(onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val sosMsg = "[SOS] I need help!"
                            messageDao.insertMessage(Message(isMe = true, text = sosMsg))
                            BtlMeshService.transmitGatt(sosMsg)
                        }
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
                    Text("Scanning for Sawa peers...", color = Color.White, fontSize = 12.sp)
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
                items(messages) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isMe) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (msg.isMe) Color(0xFFDCF8C6) else Color.White)
                                .padding(12.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(text = msg.text, color = Color.Black, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
                                if (msg.isMe) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val checkmark = when (msg.status) {
                                        2 -> "✓✓"
                                        1 -> "✓"
                                        else -> "🕐"
                                    }
                                    val checkColor = if (msg.status == 2) Color(0xFF34B7F1) else Color.Gray
                                    Text(text = checkmark, color = checkColor, fontSize = 10.sp)
                                }
                            }
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
                            val textToSend = messageText
                            messageText = ""
                            coroutineScope.launch(Dispatchers.IO) {
                                messageDao.insertMessage(Message(isMe = true, text = textToSend))
                                BtlMeshService.transmitGatt(textToSend)
                            }
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

package com.btl.protocol.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.btl.protocol.data.repository.Message
import com.btl.protocol.data.repository.STATUS_PENDING
import com.btl.protocol.data.repository.STATUS_SENT
import com.btl.protocol.data.repository.STATUS_DELIVERED
import com.btl.protocol.ui.MeshViewModel
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────────────────────────────────────────
// Colour tokens
// ──────────────────────────────────────────────────────────────────────────────

private val ColorHeader        = Color(0xFF075E54)
private val ColorHeaderLight   = Color(0xFF128C7E)
private val ColorBackground    = Color(0xFF0D1117)   // Dark Navy/Black background
private val ColorBubbleMe      = Color(0xFF005C4B)   // WhatsApp Green — sent
private val ColorBubblePeer    = Color(0xFF202C33)   // Dark Gray — received
private val ColorInputBg       = Color(0xFF1F2C34)
private val ColorSendBtn       = Color(0xFF00A884)
private val ColorPeerOnline    = Color(0xFF25D366)
private val ColorTimestamp     = Color(0xFF8696A0)
private val ColorTextPrimary   = Color(0xFFE9EDEF)
private val ColorTextSecondary = Color(0xFF8696A0)

// ──────────────────────────────────────────────────────────────────────────────
// Main chat screen
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MeshViewModel = hiltViewModel()) {
    val messages   by viewModel.messages.collectAsState()
    val peers      by viewModel.peers.collectAsState()
    val meshActive by viewModel.meshActive.collectAsState()
    val peerCount = peers.size

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // Auto-scroll to the latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().systemBarsPadding(),
        containerColor = ColorBackground,
        topBar = { 
            ChatTopBar(
                peerCount = peerCount, 
                meshActive = meshActive, 
                onSosClick = { viewModel.sendSos(context) },
                onPanicClick = { viewModel.panicWipe() }
            ) 
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ColorBackground)
        ) {
            // ── Network status banner
            NetworkBanner(peerCount = peerCount, meshActive = meshActive)

            // ── Message list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Date header
                item {
                    DateHeader(timestamp = System.currentTimeMillis())
                }

                if (messages.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(messages, key = { it.id }) { message ->
                        AnimatedVisibility(
                            visible = true,
                            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn()
                        ) {
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            // ── Input bar
            MessageInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    val toSend = inputText.trim()
                    if (toSend.isNotEmpty()) {
                        inputText = ""
                        viewModel.sendMessage(toSend)
                    }
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Top bar
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(peerCount: Int, meshActive: Boolean, onSosClick: () -> Unit, onPanicClick: () -> Unit) {
    var tapTimes by remember { mutableStateOf(listOf<Long>()) }

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ColorHeader),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar (Panic Mode trigger)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(ColorHeaderLight, ColorHeader)
                            )
                        )
                        .clickable {
                            val now = System.currentTimeMillis()
                            val recentTaps = tapTimes.filter { now - it < 1000 } + now
                            tapTimes = recentTaps
                            if (recentTaps.size >= 3) {
                                onPanicClick()
                                tapTimes = emptyList()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Sawa Mesh",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (meshActive && peerCount > 0)
                            "$peerCount peer${if (peerCount > 1) "s" else ""} online"
                        else if (meshActive) "scanning…"
                        else "mesh inactive",
                        color = ColorTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        },
        actions = {
            // SOS button
            IconButton(
                onClick = onSosClick,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFB00020).copy(alpha = 0.15f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "SOS broadcast",
                    tint = Color(0xFFFF4444)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Network status banner
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun NetworkBanner(peerCount: Int, meshActive: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    AnimatedContent(
        targetState = peerCount > 0,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "bannerState"
    ) { hasPeers ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hasPeers) Color(0xFF01382F) else Color(0xFF1A1A2E))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pulse dot
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(
                        color = if (hasPeers) ColorPeerOnline else Color(0xFFFF9800),
                        alpha = if (!hasPeers) alpha else 1f
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (hasPeers)
                        "$peerCount active peer${if (peerCount > 1) "s" else ""} in mesh"
                    else if (meshActive)
                        "Scanning for Sawa peers…"
                    else
                        "Mesh offline",
                    color = if (hasPeers) ColorPeerOnline else Color(0xFFFF9800),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Message bubble
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message) {
    val isMe = message.isMe
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isMe) 18.dp else 0.dp,
                        bottomEnd = if (isMe) 0.dp else 18.dp
                    )
                )
                .background(if (isMe) ColorBubbleMe else ColorBubblePeer)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                // Message text
                Text(
                    text = message.text,
                    color = ColorTextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                // Timestamp + status row
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = ColorTimestamp,
                        fontSize = 10.sp
                    )
                    if (isMe) {
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: Int) {
    when (status) {
        STATUS_PENDING -> Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = "Pending",
            tint = ColorTimestamp,
            modifier = Modifier.size(13.dp)
        )
        STATUS_SENT -> Row {
            Icon(Icons.Filled.Check, contentDescription = "Sent", tint = ColorTimestamp, modifier = Modifier.size(13.dp))
        }
        STATUS_DELIVERED -> Row {
            Icon(Icons.Filled.DoneAll, contentDescription = "Delivered", tint = Color(0xFF53BDEB), modifier = Modifier.size(13.dp))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Message input bar
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val canSend = text.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorBackground)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .imePadding(),
        verticalAlignment = Alignment.Bottom
    ) {
        // Text field
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .heightIn(min = 48.dp, max = 120.dp),
            placeholder = {
                Text("Message", color = ColorTextSecondary, fontSize = 15.sp)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ColorInputBg,
                unfocusedContainerColor = ColorInputBg,
                focusedTextColor = ColorTextPrimary,
                unfocusedTextColor = ColorTextPrimary,
                cursorColor = ColorSendBtn,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            maxLines = 5,
            singleLine = false,
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
        )
        Spacer(Modifier.width(8.dp))
        // Send FAB
        FloatingActionButton(
            onClick = { if (canSend) onSend() },
            containerColor = if (canSend) ColorSendBtn else ColorInputBg,
            shape = CircleShape,
            modifier = Modifier.size(48.dp),
            elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Send,
                contentDescription = "Send message",
                tint = if (canSend) Color.White else ColorTextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun DateHeader(timestamp: Long) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F2C34))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = formatDate(timestamp),
                color = ColorTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Wifi,
            contentDescription = null,
            tint = ColorTextSecondary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No messages yet",
            color = ColorTextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Once Sawa peers are in range, messages\nwill appear here automatically.",
            color = ColorTextSecondary.copy(alpha = 0.6f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatDate(ts: Long): String {
    val today = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Today"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

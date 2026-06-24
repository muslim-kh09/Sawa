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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import com.btl.protocol.ui.utils.parseMarkdown
import java.text.SimpleDateFormat
import java.util.*

// ──────────────────────────────────────────────────────────────────────────────
// Main chat screen
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String = "PUBLIC", 
    onNavigateToDm: (String) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    viewModel: MeshViewModel = hiltViewModel()
) {
    val allMessages by viewModel.messages.collectAsState()
    val messages = remember(allMessages, conversationId) {
        allMessages.filter { it.conversationId == conversationId }
    }
    val peers      by viewModel.peers.collectAsState()
    val knownIdentities by viewModel.knownIdentities.collectAsState()
    val meshActive by viewModel.meshActive.collectAsState()
    val peerCount = peers.size

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showPeersDialog by remember { mutableStateOf(false) }

    // Auto-scroll to the latest message
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(messages.size, isImeVisible) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { 
            ChatTopBar(
                peerCount = peerCount, 
                meshActive = meshActive, 
                conversationId = conversationId,
                knownIdentities = knownIdentities,
                onSettingsClick = onSettingsClick,
                onSosClick = { viewModel.sendSos(context) },
                onPanicClick = { viewModel.panicWipe() },
                onTitleClick = { if (peerCount > 0) showPeersDialog = true }
            ) 
        },
        bottomBar = {
            MessageInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    val toSend = inputText.trim()
                    if (toSend.isNotEmpty()) {
                        inputText = ""
                        viewModel.sendMessage(toSend, conversationId)
                    }
                },
                onSendVoice = { bytes, uri ->
                    viewModel.sendVoiceMessage(context, bytes, uri, conversationId)
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 16.dp,
                start = 12.dp,
                end = 12.dp
            ),
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
                    androidx.compose.animation.AnimatedVisibility(
                        visible = true,
                        enter = androidx.compose.animation.scaleIn(
                            initialScale = 0.8f,
                            transformOrigin = TransformOrigin(if (message.isMe) 1f else 0f, 1f),
                            animationSpec = androidx.compose.animation.core.tween(
                                durationMillis = 700,
                                easing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
                            )
                        ) + androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                        )
                    ) {
                        MessageBubble(message = message)
                    }
                }
            }
        }
    }

            // Removed EditNameDialog, now handled in Settings Screen

    if (showPeersDialog) {
        val primaryColor = MaterialTheme.colorScheme.primary
        AlertDialog(
            onDismissRequest = { showPeersDialog = false },
            title = { Text("Active Mesh Peers", color = MaterialTheme.colorScheme.onBackground) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(peers.values.toList(), key = { it.nodeId }) { peer ->
                        val identity = knownIdentities[peer.nodeId]
                        val displayName = identity?.displayName ?: "Unknown Peer (${peer.nodeId})"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showPeersDialog = false
                                    if (identity != null) {
                                        onNavigateToDm(identity.fullId)
                                    } else {
                                        // Fallback if we don't have full ID yet
                                        android.widget.Toast.makeText(context, "Waiting for peer identity sync...", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Canvas(modifier = Modifier.size(10.dp)) {
                                drawCircle(color = primaryColor)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(displayName, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                                Text("RSSI: ${peer.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            confirmButton = {
                TextButton(onClick = { showPeersDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Top bar
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    peerCount: Int, 
    meshActive: Boolean, 
    conversationId: String,
    knownIdentities: Map<String, com.btl.protocol.data.network.BtlMeshService.Companion.PeerIdentity>,
    onSettingsClick: () -> Unit,
    onSosClick: () -> Unit, 
    onPanicClick: () -> Unit,
    onTitleClick: () -> Unit
) {
    var tapTimes by remember { mutableStateOf(listOf<Long>()) }

    val isDm = conversationId != "PUBLIC"
    val titleText = if (isDm) {
        knownIdentities.values.find { it.fullId == conversationId }?.displayName ?: "Unknown Peer"
    } else {
        "Global Mesh"
    }
    
    val subtitleText = if (isDm) {
        "Direct connection"
    } else {
        if (meshActive && peerCount > 0)
            "$peerCount peer${if (peerCount > 1) "s" else ""} online"
        else if (meshActive) "scanning…"
        else "mesh inactive"
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
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

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar (Panic Mode trigger)
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
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
                        imageVector = if (isDm) Icons.Rounded.Person else Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.clickable { onTitleClick() }.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            titleText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.width(6.dp))
                        Canvas(modifier = Modifier.size(6.dp)) {
                            drawCircle(
                                color = if (peerCount > 0 || isDm) primaryColor else errorColor,
                                alpha = if (peerCount == 0 && !isDm) alpha else 1f
                            )
                        }
                    }
                    Text(
                        subtitleText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface)
            }
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

// (NetworkBanner removed as requested)

// ──────────────────────────────────────────────────────────────────────────────
// Message bubble
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val isMe = message.isMe
    val isDark = isSystemInDarkTheme()
    
    // Outer Shell Colors
    val outerBg = if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.03f)
    val outerBorder = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f)
    
    // Inner Core Colors
    val innerBg = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.8f)
    val innerHighlight = if (isDark) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.5f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .animateContentSize(animationSpec = tween(600, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        // OUTER SHELL (Double-Bezel)
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 320.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(outerBg)
                .border(1.dp, outerBorder, RoundedCornerShape(32.dp))
                .padding(6.dp) // Outer gap
        ) {
            // INNER CORE
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = if(isDark) 0.2f else 0.1f) else innerBg)
                    .border(1.dp, if (isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else innerHighlight, RoundedCornerShape(26.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column {
                    if (!isMe && message.senderName != null) {
                        // Eyebrow Tag for Name
                        Text(
                            text = message.senderName.uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.1.em,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    
                    // Optional Voice display
                    if (message.mediaUri != null && message.mediaType == "voice") {
                        VoiceMessagePlayer(uriString = message.mediaUri, isMe = isMe)
                    }

                    if (message.text.isNotEmpty()) {
                        // Message text - Grotesk/Light feel
                        SelectionContainer {
                            Text(
                                text = message.text.parseMarkdown(),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.W300,
                                lineHeight = 22.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Timestamp + status row
                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formatTime(message.timestamp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (isMe) {
                            MessageStatusIcon(status = message.status)
                        }
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        STATUS_SENT -> Row {
            Icon(Icons.Filled.Check, contentDescription = "Sent", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        STATUS_DELIVERED -> Row {
            Icon(Icons.Filled.DoneAll, contentDescription = "Delivered", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
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
    onSend: () -> Unit,
    onSendVoice: (ByteArray, Uri) -> Unit
) {
    val canSend = text.isNotBlank()
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var voiceRecorder by remember { mutableStateOf<com.btl.protocol.data.media.VoiceRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<java.io.File?>(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(context, "Microphone permission required for voice messages", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val isDark = isSystemInDarkTheme()
    val outerBg = if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.03f)
    val outerBorder = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.05f)

    // Fluid Island Outer Shell
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(outerBg)
            .border(1.dp, outerBorder, RoundedCornerShape(40.dp))
            .padding(8.dp) // Outer Bezel Padding
    ) {
        // Inner Core Input Box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 150.dp),
                placeholder = {
                    Text("Type a message...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 16.sp, fontWeight = FontWeight.W300)
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 6,
                singleLine = false,
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontWeight = FontWeight.W300, lineHeight = 24.sp)
            )
            Spacer(Modifier.width(8.dp))
            
            // Nested CTA & "Island" Button Architecture
            if (canSend) {
                // Send Text (Button-in-Button style)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = "Send message",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp).offset(x = 2.dp)
                        )
                    }
                }
            } else {
                // Voice record FAB
                val recordColor by animateColorAsState(
                    targetValue = if (isRecording) Color(0xFFFF4444) else MaterialTheme.colorScheme.surfaceVariant,
                    animationSpec = tween(400, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                )
                val recordScale by animateFloatAsState(
                    targetValue = if (isRecording) 0.95f else 1f, // Active down-scale physical pressing
                    animationSpec = tween(400, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                )
                val iconScale by animateFloatAsState(
                    targetValue = if (isRecording) 1.15f else 1f, // Inner icon scales up to create tension
                    animationSpec = tween(400, easing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f))
                )
                
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .scale(recordScale)
                        .clip(CircleShape)
                        .background(recordColor)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        return@detectTapGestures
                                    }
                                    isRecording = true
                                    voiceRecorder = com.btl.protocol.data.media.VoiceRecorder(context)
                                    voiceFile = voiceRecorder?.startRecording()
                                    try { awaitRelease() } finally {
                                        isRecording = false
                                        val bytes = voiceRecorder?.stopRecording()
                                        if (bytes != null && voiceFile != null && bytes.size > 500) {
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                context.packageName + ".fileprovider",
                                                voiceFile!!
                                            )
                                            onSendVoice(bytes, uri)
                                        } else {
                                            android.widget.Toast.makeText(context, "Hold the microphone button to record", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Mic,
                        contentDescription = "Hold to record",
                        tint = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp).scale(iconScale)
                    )
                }
            }
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
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = formatDate(timestamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No messages yet",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Once Sawa peers are in range, messages\nwill appear here automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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

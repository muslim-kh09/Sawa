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

    val context = LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.sendImageMessage(context, it, conversationId)
        }
    }

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
                onAttachClick = {
                    imagePickerLauncher.launch("image/*")
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
                        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it / 2 }) + androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically()
                    ) {
                        MessageBubble(message = message, modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
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
    val bgColor = if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)
    val strokeColor = Color.White.copy(alpha = 0.15f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .animateContentSize(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 60.dp, max = 290.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(bgColor)
                .border(BorderStroke(1.dp, strokeColor), RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                if (!isMe && message.senderName != null) {
                    Text(
                        text = message.senderName,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                
                // Optional Media display
                if (message.mediaUri != null) {
                    val context = LocalContext.current
                    val bitmap = remember(message.mediaUri) {
                        try {
                            val stream = context.contentResolver.openInputStream(Uri.parse(message.mediaUri))
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        } catch (e: Exception) { null }
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it,
                            contentDescription = "Shared image",
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                        )
                    }
                }

                // Message text
                SelectionContainer {
                    Text(
                        text = message.text.parseMarkdown(),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                // Timestamp + status row
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onAttachClick: () -> Unit
) {
    val canSend = text.isNotBlank()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp, start = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = onAttachClick) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Attach",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    Text("Message", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
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
                maxLines = 5,
                singleLine = false,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
            )
            Spacer(Modifier.width(4.dp))
            // Send FAB
            FloatingActionButton(
                onClick = { if (canSend) onSend() },
                containerColor = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "Send message",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
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

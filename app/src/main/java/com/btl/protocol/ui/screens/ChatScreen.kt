package com.btl.protocol.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.hilt.navigation.compose.hiltViewModel
import com.btl.protocol.R
import com.btl.protocol.data.repository.Message
import com.btl.protocol.data.repository.STATUS_PENDING
import com.btl.protocol.data.repository.STATUS_SENT
import com.btl.protocol.data.repository.STATUS_DELIVERED
import com.btl.protocol.ui.MeshViewModel
import com.btl.protocol.ui.utils.parseMarkdown
import java.text.SimpleDateFormat
import java.util.*

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
    var showPeersDialog by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DateHeader(timestamp = System.currentTimeMillis())
            }

            if (messages.isEmpty()) {
                item { EmptyState() }
            } else {
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }

    if (showPeersDialog) {
        AlertDialog(
            onDismissRequest = { showPeersDialog = false },
            title = { Text(stringResource(R.string.active_peers), style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(peers.values.toList(), key = { it.nodeId }) { peer ->
                        val identity = knownIdentities[peer.nodeId]
                        val displayName = identity?.displayName ?: "Unknown Peer (${peer.nodeId})"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    showPeersDialog = false
                                    if (identity != null) {
                                        onNavigateToDm(identity.fullId)
                                    } else {
                                        android.widget.Toast.makeText(context, "Waiting for peer identity sync...", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(displayName, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)
                                Text("RSSI: ${peer.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large,
            confirmButton = {
                TextButton(onClick = { showPeersDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
        )
    }
}

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
        knownIdentities.values.find { it.fullId == conversationId }?.displayName ?: stringResource(R.string.unknown_peer)
    } else {
        stringResource(R.string.global_mesh)
    }
    
    val subtitleText = if (isDm) {
        stringResource(R.string.direct_connection)
    } else {
        if (meshActive && peerCount > 0)
            stringResource(R.string.peers_online, peerCount)
        else if (meshActive) stringResource(R.string.scanning)
        else stringResource(R.string.mesh_inactive)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
            titleContentColor = MaterialTheme.colorScheme.onBackground
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.clickable { onTitleClick() }) {
                    Text(
                        titleText,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        subtitleText,
                        color = if (peerCount > 0 || isDm) MaterialTheme.colorScheme.onSurfaceVariant else errorColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSosClick) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "SOS broadcast",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@Composable
private fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val isMe = message.isMe
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 80.dp, max = 280.dp)
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (isMe) 18.dp else 4.dp,
                        bottomEnd = if (isMe) 4.dp else 18.dp
                    )
                )
                .background(if (isMe) primaryColor else surfaceColor)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column {
                if (!isMe && message.senderName != null) {
                    Text(
                        text = message.senderName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (message.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = message.text.parseMarkdown().toString(),
                            color = if (isMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = if (isMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (isMe) {
                        Spacer(Modifier.width(4.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: Int) {
    val iconColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
    when (status) {
        STATUS_PENDING -> Icon(Icons.Rounded.Schedule, contentDescription = "Pending", tint = iconColor, modifier = Modifier.size(12.dp))
        STATUS_SENT -> Icon(Icons.Rounded.Check, contentDescription = "Sent", tint = iconColor, modifier = Modifier.size(14.dp))
        STATUS_DELIVERED -> Icon(Icons.Rounded.DoneAll, contentDescription = "Delivered", tint = iconColor, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun MessageInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val canSend = text.isNotBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 150.dp),
                    placeholder = {
                        Text(stringResource(R.string.type_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    textStyle = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.width(12.dp))
            
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowUpward,
                    contentDescription = "Send",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun DateHeader(timestamp: Long) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text = formatDate(timestamp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_messages),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_messages_desc),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

private fun formatDate(ts: Long): String {
    val today = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "Today"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

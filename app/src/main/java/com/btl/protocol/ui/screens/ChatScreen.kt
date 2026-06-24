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
        val primaryColor = MaterialTheme.colorScheme.primary
        AlertDialog(
            onDismissRequest = { showPeersDialog = false },
            title = { Text("[ " + stringResource(R.string.active_peers) + " ]", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(peers.values.toList(), key = { it.nodeId }) { peer ->
                        val identity = knownIdentities[peer.nodeId]
                        val displayName = identity?.displayName ?: "Unknown Peer (${peer.nodeId})"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                                .clickable {
                                    showPeersDialog = false
                                    if (identity != null) {
                                        onNavigateToDm(identity.fullId)
                                    } else {
                                        android.widget.Toast.makeText(context, "Waiting for peer identity sync...", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(">", color = primaryColor, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(displayName, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium)
                                Text("RSSI: ${peer.rssi} dBm", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.medium,
            confirmButton = {
                TextButton(onClick = { showPeersDialog = false }) {
                    Text("[ CLOSE ]", color = MaterialTheme.colorScheme.primary)
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
            "$peerCount " + stringResource(R.string.peers_online)
        else if (meshActive) stringResource(R.string.scanning)
        else stringResource(R.string.mesh_inactive)
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.border(bottom = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant)),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary)
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
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.clickable { onTitleClick() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            titleText.uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Text(
                        subtitleText.uppercase(),
                        color = if (peerCount > 0 || isDm) MaterialTheme.colorScheme.primary else errorColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(
                onClick = onSosClick,
                modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = "SOS broadcast",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.width(8.dp))
        }
    )
}

@Composable
private fun MessageBubble(message: Message, modifier: Modifier = Modifier) {
    val isMe = message.isMe
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 120.dp, max = 340.dp)
                .background(if (isMe) primaryColor.copy(alpha = 0.1f) else surfaceColor)
                .border(2.dp, if (isMe) primaryColor else MaterialTheme.colorScheme.onSurfaceVariant)
                .padding(16.dp)
        ) {
            Column {
                if (!isMe && message.senderName != null) {
                    Text(
                        text = "ID: " + message.senderName.uppercase(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                if (message.text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = message.text.parseMarkdown().toString(),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (isMe) {
                        Spacer(Modifier.width(8.dp))
                        MessageStatusIcon(status = message.status)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStatusIcon(status: Int) {
    val iconColor = MaterialTheme.colorScheme.primary
    when (status) {
        STATUS_PENDING -> Text("[ WAIT ]", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        STATUS_SENT -> Text("[ SENT ]", color = iconColor, style = MaterialTheme.typography.labelSmall)
        STATUS_DELIVERED -> Text("[ RCVD ]", color = iconColor, style = MaterialTheme.typography.labelSmall)
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
            .background(MaterialTheme.colorScheme.background)
            .border(top = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(2.dp, if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 150.dp),
                    placeholder = {
                        Text("> " + stringResource(R.string.type_message) + "_", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Spacer(Modifier.width(16.dp))
            
            Button(
                onClick = onSend,
                enabled = canSend,
                shape = MaterialTheme.shapes.small, // Brutalist 0dp shape in theme
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(56.dp).border(2.dp, if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun DateHeader(timestamp: Long) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = "--- " + formatDate(timestamp).uppercase() + " ---",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "[ " + stringResource(R.string.no_messages) + " ]",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge
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
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatDate(ts: Long): String {
    val today = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) -> "TODAY"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }
}

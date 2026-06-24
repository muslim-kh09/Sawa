package com.btl.protocol.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VoiceMessagePlayer(uriString: String, isMe: Boolean) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var duration by remember { mutableStateOf(0) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val coroutineScope = rememberCoroutineScope()

    val primaryColor = MaterialTheme.colorScheme.primary
    val onBgColor = MaterialTheme.colorScheme.onBackground
    val trackColor = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.1f)

    DisposableEffect(uriString) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .width(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isMe) primaryColor.copy(alpha = 0.1f) else trackColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Play/Pause Button
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(primaryColor)
                .clickable {
                    if (isPlaying) {
                        mediaPlayer?.pause()
                        isPlaying = false
                    } else {
                        if (mediaPlayer == null) {
                            try {
                                mediaPlayer = MediaPlayer.create(context, Uri.parse(uriString))
                                duration = mediaPlayer?.duration ?: 0
                                mediaPlayer?.setOnCompletionListener {
                                    isPlaying = false
                                    progress = 0f
                                    mediaPlayer?.seekTo(0)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        mediaPlayer?.start()
                        isPlaying = true
                        coroutineScope.launch {
                            while (isPlaying && mediaPlayer != null) {
                                val currentPos = mediaPlayer?.currentPosition ?: 0
                                val dur = mediaPlayer?.duration ?: 1
                                progress = currentPos.toFloat() / dur.toFloat()
                                delay(50)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        // Progress Bar (Waveform shape substitute)
        Canvas(modifier = Modifier.weight(1f).height(24.dp)) {
            val width = size.width
            val height = size.height
            val centerY = height / 2

            // Background track
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Progress track
            drawLine(
                color = primaryColor,
                start = Offset(0f, centerY),
                end = Offset(width * progress, centerY),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )

            // Thumb
            drawCircle(
                color = primaryColor,
                radius = 6.dp.toPx(),
                center = Offset(width * progress, centerY)
            )
        }
    }
}

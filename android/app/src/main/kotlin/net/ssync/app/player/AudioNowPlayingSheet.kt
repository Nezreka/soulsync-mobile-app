package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable

private enum class NowPlayingPanel { ARTWORK, LYRICS, QUEUE }

@Composable
fun AudioNowPlayingSheet(
    coordinator: PlaybackCoordinator,
    onDismiss: () -> Unit,
) {
    val item = coordinator.activeItem ?: return
    var panel by remember { mutableStateOf(NowPlayingPanel.ARTWORK) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoulSyncColors.BgPrimary),
    ) {
        // Atmospheric full-bleed blurred-style artwork backdrop
        SoulSyncArtwork(
            url = item.artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            SoulSyncColors.BgPrimary.copy(alpha = 0.78f),
                            SoulSyncColors.BgPrimary.copy(alpha = 0.94f),
                            SoulSyncColors.BgPrimary,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = SoulSyncColors.TextPrimary)
            }
            Text(
                text = when (item.itemType) {
                    "podcast" -> "Now Playing • Podcast"
                    "audiobook" -> "Now Playing • Audiobook"
                    else -> "Now Playing • Direct"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { coordinator.cycleSpeed() }, modifier = Modifier.dpadFocusable()) {
                Text("${coordinator.playbackSpeed}x", color = SoulSyncColors.AccentLight)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (panel) {
            NowPlayingPanel.ARTWORK -> {
                SoulSyncArtwork(
                    url = item.artworkUrl,
                    contentDescription = item.title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
            NowPlayingPanel.LYRICS -> {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    ) {
                        Text(
                            text = coordinator.lyricsText ?: "No lyrics available for this track.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SoulSyncColors.TextPrimary,
                            lineHeight = 26.sp,
                        )
                    }
                }
            }
            NowPlayingPanel.QUEUE -> {
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                        itemsIndexed(coordinator.queue) { idx, q ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${idx + 1}.",
                                    color = if (q.itemId == item.itemId) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = q.title,
                                        color = if (q.itemId == item.itemId) SoulSyncColors.AccentLight else SoulSyncColors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = q.subtitle,
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (panel == NowPlayingPanel.ARTWORK) {
            Spacer(modifier = Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        val maxDur = coordinator.durationMs.coerceAtLeast(1L).toFloat()
        val curPos = coordinator.positionMs.coerceIn(0L, maxDur.toLong()).toFloat()
        Slider(
            value = curPos,
            onValueChange = { coordinator.seekTo(it.toLong()) },
            valueRange = 0f..maxDur,
            colors = SliderDefaults.colors(
                thumbColor = SoulSyncColors.AccentLight,
                activeTrackColor = SoulSyncColors.Accent,
                inactiveTrackColor = SoulSyncColors.BgRaised2,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(coordinator.positionMs), fontSize = 11.sp, color = SoulSyncColors.TextSecondary)
            Text(formatTime(coordinator.durationMs), fontSize = 11.sp, color = SoulSyncColors.TextSecondary)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    panel = if (panel == NowPlayingPanel.LYRICS) NowPlayingPanel.ARTWORK else NowPlayingPanel.LYRICS
                },
                modifier = Modifier.dpadFocusable(),
            ) {
                Icon(
                    Icons.Default.Lyrics,
                    contentDescription = "Lyrics",
                    tint = if (panel == NowPlayingPanel.LYRICS) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
                )
            }
            IconButton(onClick = { coordinator.skipPrevious() }, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = SoulSyncColors.TextPrimary, modifier = Modifier.size(32.dp))
            }
            FilledIconButton(
                onClick = { coordinator.togglePlayPause() },
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = SoulSyncColors.Accent,
                    contentColor = SoulSyncColors.OnAccent,
                ),
                modifier = Modifier.size(64.dp).dpadFocusable(CircleShape),
            ) {
                Icon(
                    imageVector = if (coordinator.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (coordinator.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { coordinator.skipNext() }, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = SoulSyncColors.TextPrimary, modifier = Modifier.size(32.dp))
            }
            IconButton(
                onClick = {
                    panel = if (panel == NowPlayingPanel.QUEUE) NowPlayingPanel.ARTWORK else NowPlayingPanel.QUEUE
                },
                modifier = Modifier.dpadFocusable(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    tint = if (panel == NowPlayingPanel.QUEUE) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
                )
            }
        }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60
    val secs = totalSec % 60
    return "%d:%02d".format(mins, secs)
}

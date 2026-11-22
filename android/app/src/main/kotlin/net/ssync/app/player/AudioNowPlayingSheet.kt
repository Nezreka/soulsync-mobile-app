package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable

private enum class NowPlayingPanel(val label: String) {
    ARTWORK("Now Playing"),
    LYRICS("Lyrics"),
    QUEUE("Up Next"),
}

private val SLEEP_PRESETS_MIN = listOf(0, 15, 30, 45, 60)

@Composable
fun AudioNowPlayingSheet(
    coordinator: PlaybackCoordinator,
    onDismiss: () -> Unit,
) {
    val item = coordinator.activeItem ?: return
    var panel by remember { mutableStateOf(NowPlayingPanel.ARTWORK) }
    var sleepSecondsLeft by remember { mutableIntStateOf(0) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatOn by remember { mutableStateOf(false) }

    LaunchedEffect(sleepSecondsLeft) {
        if (sleepSecondsLeft > 0) {
            delay(1_000L)
            val next = sleepSecondsLeft - 1
            sleepSecondsLeft = next
            if (next == 0 && coordinator.isPlaying) {
                coordinator.togglePlayPause()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoulSyncColors.BgPrimary),
    ) {
        // Atmospheric full-bleed artwork backdrop
        SoulSyncArtwork(
            url = item.artworkUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to SoulSyncColors.BgPrimary.copy(alpha = 0.72f),
                        0.55f to SoulSyncColors.BgPrimary.copy(alpha = 0.92f),
                        1.0f to SoulSyncColors.BgPrimary,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: Minimize + Segmented Panel Switcher + Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .dpadFocusable(CircleShape),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White)
                }

                // Segmented panel pill
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    NowPlayingPanel.entries.forEach { p ->
                        val on = panel == p
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable { panel = p }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = p.label,
                                color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Speed pill
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                        .clickable { coordinator.cycleSpeed() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${coordinator.playbackSpeed}x",
                        color = SoulSyncColors.AccentLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Main center stage
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (panel) {
                    NowPlayingPanel.ARTWORK -> {
                        SoulSyncArtwork(
                            url = item.artworkUrl,
                            contentDescription = item.title,
                            fallbackIcon = Icons.Default.MusicNote,
                            modifier = Modifier
                                .widthIn(max = 330.dp)
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp)),
                        )
                    }
                    NowPlayingPanel.LYRICS -> {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(20.dp),
                            ) {
                                val text = coordinator.lyricsText
                                if (text.isNullOrBlank()) {
                                    Text(
                                        text = "No lyrics available for this track.",
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 15.sp,
                                    )
                                } else {
                                    Text(
                                        text = text,
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        lineHeight = 28.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                    NowPlayingPanel.QUEUE -> {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                        ) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    Text(
                                        text = "Up Next (${coordinator.queue.size})",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                                itemsIndexed(coordinator.queue, key = { idx, q -> "$idx-${q.itemId}" }) { idx, q ->
                                    SimpleTrackRow(
                                        title = q.title,
                                        subtitle = q.subtitle,
                                        artworkUrl = q.artworkUrl,
                                        onClick = {
                                            coordinator.playAudio(q, coordinator.queue.drop(idx + 1))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Track Title & Artist
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.subtitle.ifBlank { "SoulSync Direct" },
                    color = SoulSyncColors.TextSoft,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Scrubber + Live Timestamps
            val dur = coordinator.durationMs.coerceAtLeast(1L)
            val pos = coordinator.positionMs.coerceIn(0L, dur)
            Slider(
                value = pos.toFloat() / dur.toFloat(),
                onValueChange = { fraction -> coordinator.seekTo((fraction * dur).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = SoulSyncColors.Accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatAudioClock(pos), color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("-${formatAudioClock((dur - pos).coerceAtLeast(0L))}", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Transport Row: Shuffle · Prev · Play/Pause · Next · Repeat
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { shuffleOn = !shuffleOn },
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (shuffleOn) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    )
                }

                IconButton(
                    onClick = { coordinator.skipPrevious() },
                    modifier = Modifier.size(48.dp).dpadFocusable(),
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(34.dp))
                }

                FilledIconButton(
                    onClick = { coordinator.togglePlayPause() },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    modifier = Modifier.size(68.dp).dpadFocusable(CircleShape),
                ) {
                    Icon(
                        imageVector = if (coordinator.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (coordinator.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = { coordinator.skipNext() },
                    modifier = Modifier.size(48.dp).dpadFocusable(),
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(34.dp))
                }

                IconButton(
                    onClick = { repeatOn = !repeatOn },
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Icon(
                        Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatOn) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Plexamp-style Bottom Utility Strip: Sleep Timer · Lyrics · Queue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (sleepSecondsLeft > 0) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                        .clickable {
                            val curMin = (sleepSecondsLeft + 59) / 60
                            val nextMin = SLEEP_PRESETS_MIN.firstOrNull { it > curMin } ?: 0
                            sleepSecondsLeft = nextMin * 60
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Default.Bedtime,
                        contentDescription = "Sleep timer",
                        tint = if (sleepSecondsLeft > 0) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (sleepSecondsLeft > 0) formatAudioClock(sleepSecondsLeft * 1000L) else "Sleep",
                        color = if (sleepSecondsLeft > 0) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (panel == NowPlayingPanel.LYRICS) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                        .clickable { panel = if (panel == NowPlayingPanel.LYRICS) NowPlayingPanel.ARTWORK else NowPlayingPanel.LYRICS }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Default.Lyrics, contentDescription = "Lyrics", tint = SoulSyncColors.TextSoft, modifier = Modifier.size(16.dp))
                    Text("Lyrics", color = SoulSyncColors.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (panel == NowPlayingPanel.QUEUE) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                        .clickable { panel = if (panel == NowPlayingPanel.QUEUE) NowPlayingPanel.ARTWORK else NowPlayingPanel.QUEUE }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue", tint = SoulSyncColors.TextSoft, modifier = Modifier.size(16.dp))
                    Text("Queue (${coordinator.queue.size})", color = SoulSyncColors.TextSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun formatAudioClock(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}

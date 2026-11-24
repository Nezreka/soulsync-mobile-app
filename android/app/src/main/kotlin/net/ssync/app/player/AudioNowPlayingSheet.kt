package net.ssync.app.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.PlayerApiClient
import net.ssync.player.SongRefPayload

private enum class NowPlayingPanel {
    ARTWORK,
    LYRICS,
    QUEUE,
}

private val SLEEP_PRESETS_MIN = listOf(0, 15, 30, 45, 60)

@Composable
fun AudioNowPlayingSheet(
    coordinator: PlaybackCoordinator,
    api: PlayerApiClient,
    castConnected: Boolean = false,
    onOpenCast: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val item = coordinator.activeItem ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { PlayerDevicePrefs.init(context) }
    val prefs by PlayerDevicePrefs.state.collectAsState()

    val likedState = remember(api) { LikedTracksState(api) }
    LaunchedEffect(likedState) { likedState.loadInitial() }

    var panel by remember { mutableStateOf(NowPlayingPanel.ARTWORK) }
    var sleepSecondsLeft by remember { mutableIntStateOf(0) }
    var shuffleOn by remember { mutableStateOf(false) }
    var repeatOn by remember { mutableStateOf(false) }
    var addPlaylistTarget by remember { mutableStateOf<SongRefPayload?>(null) }

    val isTrack = item.itemType == "track"
    val songRef = remember(item) {
        SongRefPayload(
            lib2TrackId = item.itemId.toIntOrNull(),
            title = item.title,
            artist = item.subtitle.substringBefore(" · ").ifBlank { "Unknown Artist" },
            durationMs = item.durationMs.takeIf { it > 0L },
        )
    }
    val liked = isTrack && likedState.isLiked(songRef.lib2TrackId, songRef.artist, songRef.title)
    val isOfflineLocal = item.streamUrl.startsWith("file://")

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
            .background(Color(0xFF0A0B12)),
    ) {
        // Soft blurred full-bleed artwork atmosphere (Plexamp signature backdrop)
        SoulSyncArtwork(
            url = item.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(48.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xAA0C0D16),
                        0.45f to Color(0xCC0B0C14),
                        1.0f to Color(0xFA090A10),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Ultra-Clean Plexamp Top Bar: Minimize | Context Eyebrow | Cast + More Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .dpadFocusable(CircleShape),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(26.dp))
                }

                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when (panel) {
                            NowPlayingPanel.ARTWORK -> if (isOfflineLocal) "PLAYING OFFLINE" else "NOW PLAYING"
                            NowPlayingPanel.LYRICS -> "LYRICS"
                            NowPlayingPanel.QUEUE -> "UP NEXT · ${coordinator.queue.size}"
                        },
                        color = SoulSyncColors.AccentLight,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                var showReportIssue by remember { mutableStateOf(false) }
                IconButton(
                    onClick = { showReportIssue = true },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Flag,
                        contentDescription = "Report issue",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (showReportIssue) {
                    ReportIssueDialog(
                        api = api,
                        itemType = item.itemType,
                        itemId = item.itemId,
                        title = item.title,
                        subtitle = item.subtitle,
                        positionSec = (coordinator.positionMs / 1000L).toInt(),
                        onDismiss = { showReportIssue = false },
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                if (onOpenCast != null) {
                    IconButton(
                        onClick = onOpenCast,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                    ) {
                        Icon(
                            imageVector = if (castConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = "Cast",
                            tint = if (castConnected) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(38.dp))
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 2. Center Stage (Swipeable Hero Artwork | Lyrics | Up Next Queue)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(targetState = panel, label = "np_panel") { activePanel ->
                    when (activePanel) {
                        NowPlayingPanel.ARTWORK -> {
                            var dragAccum by remember { mutableFloatStateOf(0f) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.92f)
                                    .aspectRatio(1f)
                                    .shadow(28.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = SoulSyncColors.Accent.copy(alpha = 0.35f))
                                    .clip(RoundedCornerShape(22.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                                    .pointerInput(item.itemId) {
                                        detectHorizontalDragGestures(
                                            onDragEnd = {
                                                if (dragAccum < -90f) coordinator.skipNext()
                                                else if (dragAccum > 90f) coordinator.skipPrevious()
                                                dragAccum = 0f
                                            },
                                            onDragCancel = { dragAccum = 0f },
                                            onHorizontalDrag = { _, delta -> dragAccum += delta },
                                        )
                                    }
                                    .clickable { panel = NowPlayingPanel.LYRICS },
                            ) {
                                SoulSyncArtwork(
                                    url = item.artworkUrl,
                                    contentDescription = item.title,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        NowPlayingPanel.LYRICS -> {
                            val lyrics = coordinator.lyricsText
                            if (lyrics.isNullOrBlank()) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Default.Lyrics, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(38.dp))
                                    Text("No lyrics available for this track", color = SoulSyncColors.TextSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    lyrics.lines().forEach { line ->
                                        val clean = line.replace(Regex("^\\[\\d+:\\d+(?:\\.\\d+)?\\]\\s*"), "").trim()
                                        if (clean.isNotEmpty()) {
                                            Text(
                                                text = clean,
                                                color = Color.White.copy(alpha = 0.92f),
                                                fontSize = 19.sp,
                                                lineHeight = 28.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                textAlign = TextAlign.Start,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        NowPlayingPanel.QUEUE -> {
                            val queue = coordinator.queue
                            if (queue.isEmpty()) {
                                Text("Queue is empty", color = SoulSyncColors.TextSecondary, fontSize = 14.sp)
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    itemsIndexed(queue, key = { idx, q -> "${idx}_${q.itemType}_${q.itemId}" }) { idx, qItem ->
                                        val isCurrent = qItem.itemId == item.itemId && qItem.itemType == item.itemType
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                                                .border(
                                                    1.dp,
                                                    if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.55f) else Color.Transparent,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .clickable { coordinator.playAudio(qItem, queue.drop(idx + 1)) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Box(modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))) {
                                                SoulSyncArtwork(url = qItem.artworkUrl, contentDescription = qItem.title, modifier = Modifier.fillMaxSize())
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = qItem.title,
                                                    color = if (isCurrent) SoulSyncColors.AccentLight else Color.White,
                                                    fontSize = 14.sp,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (qItem.subtitle.isNotBlank()) {
                                                    Text(
                                                        text = qItem.subtitle,
                                                        color = SoulSyncColors.TextSecondary,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                            if (isCurrent) {
                                                Text(
                                                    text = "PLAYING",
                                                    color = SoulSyncColors.AccentLight,
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Track Title + Instant Action Icons (Heart, Add to Playlist, Offline Download)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            color = SoulSyncColors.TextSoft,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isTrack) {
                    IconButton(
                        onClick = { scope.launch { likedState.toggle(songRef) } },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(
                        onClick = { addPlaylistTarget = songRef },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = "Add to playlist",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                OfflineDownloadButton(
                    api = api,
                    itemType = item.itemType,
                    itemId = item.itemId,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUrl = item.artworkUrl,
                    streamUrl = if (isOfflineLocal) api.streamUrl(item.itemType, item.itemId) else item.streamUrl,
                    isVideo = false,
                    parentId = item.parentId,
                    compact = true,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Precision Scrubber + Timestamps + Technical Quality/Crossfade Pill
            val dur = coordinator.durationMs.coerceAtLeast(1L)
            val pos = coordinator.positionMs.coerceIn(0L, dur)
            var scrubbing by remember { mutableStateOf(false) }
            var scrubFrac by remember { mutableFloatStateOf(0f) }
            val shownFrac = if (scrubbing) scrubFrac else (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)

            Slider(
                value = shownFrac,
                onValueChange = {
                    scrubbing = true
                    scrubFrac = it
                },
                onValueChangeFinished = {
                    coordinator.seekTo((scrubFrac * dur).toLong())
                    scrubbing = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shownPos = if (scrubbing) (scrubFrac * dur).toLong() else pos
                Text(formatTimeMs(shownPos), color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                // Signature Plexamp technical pill
                val pillText = buildString {
                    if (isOfflineLocal) append("OFFLINE · ")
                    if (prefs.crossfadeSeconds > 0 && isTrack) append("CROSSFADE ${prefs.crossfadeSeconds}s")
                    else append("GAPLESS")
                }
                Text(
                    text = pillText,
                    color = SoulSyncColors.TextSoft,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
                Text("-${formatTimeMs((dur - shownPos).coerceAtLeast(0L))}", color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 5. Balanced 7-Control Transport Row:
            //    Left: [Prev] [Stop] [Next]  |  Center: [Play/Pause]  |  Right: [Shuffle] [Loop] [Speed]
            val speed = coordinator.playbackSpeed
            val speedLabel = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
            val speedActive = speed != 1.0f
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left side: Prev (farthest left), Stop, Next
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { coordinator.skipPrevious() }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            coordinator.stopPlayback()
                            onDismiss()
                        },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = Color(0xFFFDA4AF),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    IconButton(onClick = { coordinator.skipNext() }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Center: Play / Pause
                IconButton(
                    onClick = { coordinator.togglePlayPause() },
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(16.dp, CircleShape, spotColor = Color.White.copy(alpha = 0.4f))
                        .clip(CircleShape)
                        .background(Color.White)
                        .dpadFocusable(CircleShape),
                ) {
                    Icon(
                        imageVector = if (coordinator.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (coordinator.isPlaying) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(34.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Right side: Shuffle, Loop, Speed
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { shuffleOn = !shuffleOn }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffleOn) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = { repeatOn = !repeatOn }, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Default.Repeat,
                            contentDescription = "Loop",
                            tint = if (repeatOn) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (speedActive) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.08f))
                            .border(
                                1.dp,
                                if (speedActive) SoulSyncColors.Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.14f),
                                RoundedCornerShape(999.dp),
                            )
                            .clickable { coordinator.cycleSpeed() }
                            .padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = speedLabel,
                            color = if (speedActive) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.82f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Bottom Interactive Dock: Lyrics | Up Next | Sleep Timer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTab(
                    icon = Icons.Default.Lyrics,
                    label = "Lyrics",
                    active = panel == NowPlayingPanel.LYRICS,
                    onClick = { panel = if (panel == NowPlayingPanel.LYRICS) NowPlayingPanel.ARTWORK else NowPlayingPanel.LYRICS },
                )
                DockTab(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Up Next",
                    active = panel == NowPlayingPanel.QUEUE,
                    onClick = { panel = if (panel == NowPlayingPanel.QUEUE) NowPlayingPanel.ARTWORK else NowPlayingPanel.QUEUE },
                )
                DockTab(
                    icon = Icons.Default.Bedtime,
                    label = if (sleepSecondsLeft > 0) "${(sleepSecondsLeft + 59) / 60}m" else "Sleep",
                    active = sleepSecondsLeft > 0,
                    onClick = {
                        val curMin = if (sleepSecondsLeft > 0) (sleepSecondsLeft + 59) / 60 else 0
                        val idx = SLEEP_PRESETS_MIN.indexOfFirst { it > curMin }
                        val nextMin = if (idx >= 0) SLEEP_PRESETS_MIN[idx] else 0
                        sleepSecondsLeft = nextMin * 60
                    },
                )
            }
        }
    }

    if (addPlaylistTarget != null) {
        AddToPlaylistSheet(
            song = addPlaylistTarget!!,
            api = api,
            onDismiss = { addPlaylistTarget = null },
        )
    }
}

@Composable
private fun DockTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (active) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, if (active) SoulSyncColors.Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = if (active) Color.White else Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "%d:%02d".format(m, s)
}

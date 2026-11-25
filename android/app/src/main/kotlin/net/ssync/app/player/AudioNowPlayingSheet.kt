package net.ssync.app.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.PlayerApiClient
import net.ssync.player.SongRefPayload
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class NowPlayingPanel {
    ARTWORK,
    LYRICS,
    QUEUE,
}

private data class SyncedLyricLine(
    val timeMs: Long?,
    val text: String,
)

private val LRC_TIME_RE = Regex("^\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?]\\s*(.*)$")
private val SLEEP_PRESETS_MIN = listOf(0, 15, 30, 45, 60)

private fun parseSyncedLyrics(raw: String?): List<SyncedLyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val match = LRC_TIME_RE.matchEntire(trimmed)
        if (match != null) {
            val mins = match.groupValues[1].toLongOrNull() ?: 0L
            val secs = match.groupValues[2].toLongOrNull() ?: 0L
            val fracRaw = match.groupValues[3]
            val millis = when (fracRaw.length) {
                1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                3 -> fracRaw.toLongOrNull() ?: 0L
                else -> 0L
            }
            val text = match.groupValues[4].trim()
            if (text.isEmpty()) null else SyncedLyricLine(mins * 60_000L + secs * 1000L + millis, text)
        } else {
            val cleaned = trimmed.replace(Regex("^\\[[^]]*]\\s*"), "").trim()
            if (cleaned.isEmpty()) null else SyncedLyricLine(null, cleaned)
        }
    }
}

@Composable
fun AudioNowPlayingSheet(
    coordinator: PlaybackCoordinator,
    api: PlayerApiClient,
    castConnected: Boolean = false,
    onOpenCast: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val item = coordinator.activeItem ?: return
    val isTv = LocalIsTv.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { PlayerDevicePrefs.init(context) }
    val prefs by PlayerDevicePrefs.state.collectAsState()

    val likedState = remember(api) { LikedTracksState(api) }
    LaunchedEffect(likedState) { likedState.loadInitial() }

    // On TV default right pane to Live Lyrics; on mobile default center pager to Cover Art / Visualizer
    val parsedLyrics = remember(coordinator.lyricsText) { parseSyncedLyrics(coordinator.lyricsText) }
    var panel by remember(isTv) { mutableStateOf(if (isTv) NowPlayingPanel.LYRICS else NowPlayingPanel.ARTWORK) }
    var showVisualizer by remember { mutableStateOf(false) }
    var visualizerTheme by remember { mutableStateOf(DesktopVisualizerTheme.BARSCOPE) }
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    var sleepSecondsLeft by remember { mutableIntStateOf(0) }
    val shuffleOn = coordinator.shuffleEnabled
    val repeatMode = coordinator.repeatMode
    val repeatOn = repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF
    var addPlaylistTarget by remember { mutableStateOf<SongRefPayload?>(null) }
    var showReportIssue by remember { mutableStateOf(false) }

    val playPauseFocus = remember { FocusRequester() }
    LaunchedEffect(isTv) {
        if (isTv) {
            delay(80L)
            runCatching { playPauseFocus.requestFocus() }
        }
    }

    val isTrack = item.itemType == "track"
    val songRef = remember(item) {
        SongRefPayload(
            lib2TrackId = item.itemId.toIntOrNull(),
            title = item.title,
            artist = item.subtitle.substringBefore(" Â· ").ifBlank { "Unknown Artist" },
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

    val dur = coordinator.durationMs.coerceAtLeast(1L)
    val pos = coordinator.positionMs.coerceIn(0L, dur)
    val progressFrac = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
    val artScale by animateFloatAsState(
        targetValue = if (coordinator.isPlaying) 1.0f else 0.93f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "plexampArtBreath",
    )

    val pillText = buildString {
        if (isOfflineLocal) append("OFFLINE Â· ")
        if (prefs.crossfadeSeconds > 0 && isTrack) append("CROSSFADE ${prefs.crossfadeSeconds}s")
        else append("GAPLESS Â· HI-FI")
    }
    val speed = coordinator.playbackSpeed
    val speedLabel = if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
    val speedActive = speed != 1.0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08090F))
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        coordinator.togglePlayPause()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        coordinator.skipNext()
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        coordinator.skipPrevious()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Signature Plexamp atmospheric full-bleed blurred artwork backdrop (180px decode = 16x lighter blur!)
        SoulSyncArtwork(
            url = item.artworkUrl,
            contentDescription = null,
            maxPx = 180,
            modifier = Modifier
                .fillMaxSize()
                .blur(64.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color(0xA80A0C16),
                        0.45f to Color(0xCC090B14),
                        1.0f to Color(0xFA07080E),
                    ),
                ),
        )

        if (isTv) {
            // =========================================================================
            // 10-FOOT WIDESCREEN TV PLEXAMP-KILLER SPLIT-STAGE LAYOUT
            // Left: Breathing Hero Album Art + Track Identity + Quick Actions
            // Right: Live Synced Karaoke Lyrics / Up Next Queue + Sonic Waveform + Transport
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 44.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // LEFT STAGE: Album Art + Track Metadata
                Column(
                    modifier = Modifier
                        .weight(0.43f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Top-left Back + Context badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .dpadFocusable(CircleShape),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = pillText,
                            color = SoulSyncColors.AccentLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.1.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(SoulSyncColors.Accent.copy(alpha = 0.18f))
                                .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }

                    // Breathing Hero Album Cover <-> 60fps Live Visualizer (press OK / tap to toggle)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.92f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = artScale
                                    scaleY = artScale
                                }
                                .shadow(36.dp, RoundedCornerShape(24.dp), spotColor = SoulSyncColors.Accent.copy(alpha = 0.45f))
                                .clip(RoundedCornerShape(24.dp))
                                .border(1.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
                                .dpadFocusable(RoundedCornerShape(24.dp), scaleOnFocus = true)
                                .clickable { showVisualizer = !showVisualizer },
                        ) {
                            AnimatedContent(
                                targetState = showVisualizer,
                                transitionSpec = { fadeIn(tween(240)).togetherWith(fadeOut(tween(180))) },
                                label = "tvHeroVisualizerToggle",
                            ) { vizActive ->
                                if (vizActive) {
                                    DesktopSoulSyncVisualizer(
                                        artworkUrl = item.artworkUrl,
                                        theme = visualizerTheme,
                                        isPlaying = coordinator.isPlaying,
                                        onCycleTheme = {
                                            val themes = DesktopVisualizerTheme.entries
                                            visualizerTheme = themes[(themes.indexOf(visualizerTheme) + 1) % themes.size]
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        SoulSyncArtwork(
                                            url = item.artworkUrl,
                                            contentDescription = item.title,
                                            maxPx = 1080,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(12.dp)
                                                .clip(CircleShape)
                                                .background(Color.Black.copy(alpha = 0.56f))
                                                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.GraphicEq,
                                                contentDescription = null,
                                                tint = SoulSyncColors.AccentLight,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = "Visualizer",
                                                color = Color.White.copy(alpha = 0.9f),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Track Title + Artist + Quick Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.4).sp,
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
                                modifier = Modifier.size(40.dp).clip(CircleShape).dpadFocusable(CircleShape),
                            ) {
                                Icon(
                                    imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (liked) "Unlike" else "Like",
                                    tint = if (liked) Color(0xFFF43F5E) else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            IconButton(
                                onClick = { addPlaylistTarget = songRef },
                                modifier = Modifier.size(40.dp).clip(CircleShape).dpadFocusable(CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = "Add to playlist",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { showReportIssue = true },
                            modifier = Modifier.size(40.dp).clip(CircleShape).dpadFocusable(CircleShape),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = "Report issue",
                                tint = Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                // RIGHT STAGE: Live Synced Lyrics / Up Next + Sonic Waveform + Transport
                Column(
                    modifier = Modifier
                        .weight(0.57f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Segmented Tab Switcher (Lyrics | Up Next | Visualizer | Sleep)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockTab(
                            icon = Icons.Default.Lyrics,
                            label = "Live Lyrics",
                            active = panel != NowPlayingPanel.QUEUE,
                            onClick = { panel = NowPlayingPanel.LYRICS },
                        )
                        DockTab(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            label = "Up Next (${coordinator.queue.size})",
                            active = panel == NowPlayingPanel.QUEUE,
                            onClick = { panel = NowPlayingPanel.QUEUE },
                        )
                        DockTab(
                            icon = Icons.Default.GraphicEq,
                            label = if (showVisualizer) visualizerTheme.label else "Visuals",
                            active = showVisualizer,
                            onClick = {
                                if (!showVisualizer) {
                                    showVisualizer = true
                                } else {
                                    val themes = DesktopVisualizerTheme.entries
                                    visualizerTheme = themes[(themes.indexOf(visualizerTheme) + 1) % themes.size]
                                }
                            },
                        )
                        Spacer(modifier = Modifier.weight(1f))
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

                    // Center Live Lyrics or Up Next Queue
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(vertical = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        AnimatedContent(
                            targetState = panel == NowPlayingPanel.QUEUE,
                            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(140))) },
                            label = "tvAudioRightPanel",
                        ) { showQueue ->
                            if (showQueue) {
                                UpNextQueueList(
                                    queue = coordinator.queue,
                                    currentItem = item,
                                    onSelect = { idx, qItem -> coordinator.playAudio(qItem, coordinator.queue.drop(idx + 1)) },
                                )
                            } else {
                                SyncedLyricsView(
                                    lines = parsedLyrics,
                                    positionMs = pos,
                                    isTv = true,
                                    onSeek = { coordinator.seekTo(it) },
                                )
                            }
                        }
                    }

                    // Signature Plexamp Sonic Waveform Scrubber + Timestamps
                    Column(modifier = Modifier.fillMaxWidth()) {
                        SonicWaveformScrubber(
                            seedKey = item.itemId,
                            progress = progressFrac,
                            isPlaying = coordinator.isPlaying,
                            isTv = true,
                            onSeekFraction = { frac -> coordinator.seekTo((frac * dur).toLong()) },
                            onSeekDeltaMs = { delta -> coordinator.seekTo((pos + delta).coerceIn(0L, dur)) },
                            onTogglePlayPause = { coordinator.togglePlayPause() },
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatTimeMs(pos), color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("-${formatTimeMs((dur - pos).coerceAtLeast(0L))}", color = SoulSyncColors.TextSecondary, fontSize = 12.5.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Clean TV Transport Deck
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            IconButton(
                                onClick = { coordinator.skipPrevious() },
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).dpadFocusable(CircleShape),
                            ) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            IconButton(
                                onClick = { coordinator.togglePlayPause() },
                                modifier = Modifier
                                    .size(58.dp)
                                    .shadow(18.dp, CircleShape, spotColor = Color.White.copy(alpha = 0.35f))
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .focusRequester(playPauseFocus)
                                    .dpadFocusable(CircleShape, scaleOnFocus = true, onLightSurface = true),
                            ) {
                                Icon(
                                    imageVector = if (coordinator.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (coordinator.isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                            IconButton(
                                onClick = { coordinator.skipNext() },
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f)).dpadFocusable(CircleShape),
                            ) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(26.dp))
                            }
                            IconButton(
                                onClick = {
                                    coordinator.stopPlayback()
                                    onDismiss()
                                },
                                modifier = Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).dpadFocusable(CircleShape),
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFFDA4AF), modifier = Modifier.size(22.dp))
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            IconButton(
                                onClick = { coordinator.toggleShuffle() },
                                modifier = Modifier.size(40.dp).clip(CircleShape).dpadFocusable(CircleShape),
                            ) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (shuffleOn) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            IconButton(
                                onClick = { coordinator.cycleRepeatMode() },
                                modifier = Modifier.size(40.dp).clip(CircleShape).dpadFocusable(CircleShape),
                            ) {
                                Icon(
                                    imageVector = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
                                        Icons.Default.RepeatOne
                                    } else {
                                        Icons.Default.Repeat
                                    },
                                    contentDescription = "Loop",
                                    tint = if (repeatOn) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.65f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (speedActive) SoulSyncColors.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.10f))
                                    .border(
                                        1.dp,
                                        if (speedActive) SoulSyncColors.Accent.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.16f),
                                        RoundedCornerShape(999.dp),
                                    )
                                    .dpadFocusable(RoundedCornerShape(999.dp))
                                    .clickable { coordinator.cycleSpeed() }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = speedLabel,
                                    color = if (speedActive) SoulSyncColors.AccentLight else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // =========================================================================
            // MOBILE PORTRAIT PLEXAMP-KILLER LAYOUT
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
                            text = when (pagerState.currentPage) {
                                0 -> "LIVE LYRICS"
                                2 -> "UP NEXT Â· ${coordinator.queue.size}"
                                else -> if (showVisualizer) "VISUALIZER Â· ${visualizerTheme.label.uppercase()}" else if (isOfflineLocal) "PLAYING OFFLINE" else "NOW PLAYING"
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

                    IconButton(
                        onClick = { showReportIssue = true },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = "Report issue",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (onOpenCast != null) {
                        IconButton(
                            onClick = onOpenCast,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f)).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 3-page swipeable Plexamp center stage:
                    // Slide Right -> Lyrics (0) | Center -> Cover Art <-> Visualizer on tap (1) | Slide Left -> Queue (2)
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (page) {
                                0 -> {
                                    SyncedLyricsView(
                                        lines = parsedLyrics,
                                        positionMs = pos,
                                        isTv = false,
                                        onSeek = { coordinator.seekTo(it) },
                                    )
                                }

                                1 -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(0.92f)
                                            .aspectRatio(1f)
                                            .graphicsLayer {
                                                scaleX = artScale
                                                scaleY = artScale
                                            }
                                            .shadow(
                                                28.dp,
                                                RoundedCornerShape(22.dp),
                                                ambientColor = Color.Black,
                                                spotColor = SoulSyncColors.Accent.copy(alpha = 0.35f),
                                            )
                                            .clip(RoundedCornerShape(22.dp))
                                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
                                            .clickable { showVisualizer = !showVisualizer },
                                    ) {
                                        AnimatedContent(
                                            targetState = showVisualizer,
                                            transitionSpec = { fadeIn(tween(240)).togetherWith(fadeOut(tween(180))) },
                                            label = "mobileCoverVisualizerToggle",
                                        ) { vizActive ->
                                            if (vizActive) {
                                                DesktopSoulSyncVisualizer(
                                                    artworkUrl = item.artworkUrl,
                                                    theme = visualizerTheme,
                                                    isPlaying = coordinator.isPlaying,
                                                    onCycleTheme = {
                                                        val themes = DesktopVisualizerTheme.entries
                                                        visualizerTheme = themes[(themes.indexOf(visualizerTheme) + 1) % themes.size]
                                                    },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            } else {
                                                SoulSyncArtwork(
                                                    url = item.artworkUrl,
                                                    contentDescription = item.title,
                                                    maxPx = 1080,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        }
                                    }
                                }

                                else -> {
                                    UpNextQueueList(
                                        queue = coordinator.queue,
                                        currentItem = item,
                                        onSelect = { idx, qItem -> coordinator.playAudio(qItem, coordinator.queue.drop(idx + 1)) },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Subtle 3-dot swipe indicator (Lyrics â€¢ Cover/Visualizer â€¢ Queue)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeat(3) { idx ->
                            val active = pagerState.currentPage == idx
                            Box(
                                modifier = Modifier
                                    .width(if (active) 18.dp else 6.dp)
                                    .height(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (active) SoulSyncColors.AccentLight
                                        else Color.White.copy(alpha = 0.28f),
                                    ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                            modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
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
                            modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
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

                Spacer(modifier = Modifier.height(12.dp))

                // Signature Plexamp Sonic Waveform Scrubber
                SonicWaveformScrubber(
                    seedKey = item.itemId,
                    progress = progressFrac,
                    isPlaying = coordinator.isPlaying,
                    isTv = false,
                    onSeekFraction = { frac -> coordinator.seekTo((frac * dur).toLong()) },
                    onSeekDeltaMs = { delta -> coordinator.seekTo((pos + delta).coerceIn(0L, dur)) },
                    onTogglePlayPause = { coordinator.togglePlayPause() },
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatTimeMs(pos), color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
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
                    Text("-${formatTimeMs((dur - pos).coerceAtLeast(0L))}", color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { coordinator.skipPrevious() }, modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        IconButton(
                            onClick = {
                                coordinator.stopPlayback()
                                onDismiss()
                            },
                            modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFFDA4AF), modifier = Modifier.size(26.dp))
                        }
                        IconButton(onClick = { coordinator.skipNext() }, modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = { coordinator.togglePlayPause() },
                        modifier = Modifier
                            .size(64.dp)
                            .shadow(16.dp, CircleShape, spotColor = Color.White.copy(alpha = 0.4f))
                            .clip(CircleShape)
                            .background(Color.White)
                            .dpadFocusable(CircleShape, onLightSurface = true),
                    ) {
                        Icon(
                            imageVector = if (coordinator.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (coordinator.isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(34.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { coordinator.toggleShuffle() }, modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffleOn) SoulSyncColors.AccentLight else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        IconButton(onClick = { coordinator.cycleRepeatMode() }, modifier = Modifier.size(38.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(
                                imageVector = if (repeatMode == androidx.media3.common.Player.REPEAT_MODE_ONE) {
                                    Icons.Default.RepeatOne
                                } else {
                                    Icons.Default.Repeat
                                },
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockTab(
                        icon = Icons.Default.Lyrics,
                        label = "Lyrics",
                        active = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(if (pagerState.currentPage == 0) 1 else 0)
                            }
                        },
                    )
                    DockTab(
                        icon = Icons.Default.GraphicEq,
                        label = "Visuals",
                        active = pagerState.currentPage == 1 && showVisualizer,
                        onClick = {
                            scope.launch {
                                if (pagerState.currentPage != 1) {
                                    showVisualizer = true
                                    pagerState.animateScrollToPage(1)
                                } else {
                                    showVisualizer = !showVisualizer
                                }
                            }
                        },
                    )
                    DockTab(
                        icon = Icons.AutoMirrored.Filled.QueueMusic,
                        label = "Queue",
                        active = pagerState.currentPage == 2,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(if (pagerState.currentPage == 2) 1 else 2)
                            }
                        },
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

    if (addPlaylistTarget != null) {
        AddToPlaylistSheet(
            song = addPlaylistTarget!!,
            api = api,
            onDismiss = { addPlaylistTarget = null },
        )
    }
}

/**
 * Plexamp-signature interactive 56-bar Sonic Waveform Scrubber:
 * - Touch tap/drag scrubbing on mobile
 * - D-pad Left/Right (Â±10s, hold Â±30s) & Center Play/Pause on Android TV
 * - Played bars glow in SoulSync Accent -> White; unplayed bars sit in frosted glass
 */
@Composable
private fun SonicWaveformScrubber(
    seedKey: String,
    progress: Float,
    isPlaying: Boolean,
    isTv: Boolean,
    onSeekFraction: (Float) -> Unit,
    onSeekDeltaMs: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    val barCount = 56
    val bars = remember(seedKey) {
        val h = seedKey.hashCode().toLong()
        List(barCount) { idx ->
            val phase = (idx.toFloat() / barCount) * PI.toFloat() * 4.5f
            val env = sin((idx.toFloat() / (barCount - 1)) * PI.toFloat()).coerceIn(0.25f, 1f)
            val pseudo = abs(sin((h * 0.0013f + idx * 1.73f).toDouble())).toFloat()
            val wave = abs(sin(phase + (h % 7) * 0.4f))
            (0.22f + env * (0.48f * pseudo + 0.30f * wave)).coerceIn(0.18f, 1.0f)
        }
    }
    var isFocused by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val activeFrac = (dragFrac ?: progress).coerceIn(0f, 1f)
    val accentColor = SoulSyncColors.AccentLight

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isFocused) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(2.5.dp, Color.White, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .then(if (isTv) Modifier.focusable() else Modifier)
            .onPreviewKeyEvent { ev ->
                if (!isTv || ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = if (ev.nativeKeyEvent.repeatCount > 3) 30_000L else 10_000L
                when (ev.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeekDeltaMs(-step)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeekDeltaMs(step)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> {
                        onTogglePlayPause()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onSeekFraction(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragFrac?.let(onSeekFraction)
                        dragFrac = null
                    },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFrac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                )
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val totalW = size.width
            val totalH = size.height
            val slotW = totalW / barCount
            val barW = (slotW * 0.58f).coerceAtLeast(2.dp.toPx())
            val radius = CornerRadius(barW / 2f, barW / 2f)
            val activeIndex = (activeFrac * barCount).toInt()

            bars.forEachIndexed { idx, amp ->
                val barH = (amp * totalH).coerceAtLeast(4.dp.toPx())
                val x = idx * slotW + (slotW - barW) / 2f
                val y = (totalH - barH) / 2f
                val played = idx <= activeIndex
                val isHead = idx == activeIndex
                val color = when {
                    isHead -> Color.White
                    played -> if (isPlaying) accentColor else Color.White.copy(alpha = 0.82f)
                    else -> Color.White.copy(alpha = 0.22f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barW, barH),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * Time-synced Apple Music / Plexamp-beating live lyrics view:
 * Auto-scrolls the active line smoothly into focus and lets users click/tap any synced line to seek.
 */
@Composable
private fun SyncedLyricsView(
    lines: List<SyncedLyricLine>,
    positionMs: Long,
    isTv: Boolean,
    onSeek: (Long) -> Unit,
) {
    if (lines.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Lyrics, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(40.dp))
                Text(
                    text = "No lyrics available for this track",
                    color = SoulSyncColors.TextSoft,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        return
    }

    val hasTimestamps = remember(lines) { lines.any { it.timeMs != null } }
    val activeIdx = remember(lines, positionMs, hasTimestamps) {
        if (!hasTimestamps) -1
        else lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs + 250L }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(activeIdx) {
        if (activeIdx >= 0) {
            val target = (activeIdx - 2).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 12.dp),
    ) {
        itemsIndexed(lines) { idx, line ->
            val isActive = hasTimestamps && idx == activeIdx
            val alpha = when {
                !hasTimestamps -> 0.90f
                isActive -> 1.0f
                idx < activeIdx -> 0.42f
                else -> 0.52f
            }
            Text(
                text = line.text,
                color = if (isActive) Color.White else Color.White.copy(alpha = alpha),
                fontSize = when {
                    isActive && isTv -> 24.sp
                    isActive -> 22.sp
                    isTv -> 19.sp
                    else -> 18.sp
                },
                lineHeight = if (isActive) 32.sp else 26.sp,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (line.timeMs != null) {
                            Modifier
                                .dpadFocusable(RoundedCornerShape(10.dp))
                                .clickable { onSeek(line.timeMs) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun UpNextQueueList(
    queue: List<ActivePlayable>,
    currentItem: ActivePlayable,
    onSelect: (Int, ActivePlayable) -> Unit,
) {
    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Queue is empty", color = SoulSyncColors.TextSecondary, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(queue, key = { idx, q -> "${idx}_${q.itemType}_${q.itemId}" }) { idx, qItem ->
            val isCurrent = qItem.itemId == currentItem.itemId && qItem.itemType == currentItem.itemType
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
                    .border(
                        1.dp,
                        if (isCurrent) SoulSyncColors.Accent.copy(alpha = 0.55f) else Color.Transparent,
                        RoundedCornerShape(12.dp),
                    )
                    .dpadFocusable(RoundedCornerShape(12.dp))
                    .clickable { onSelect(idx, qItem) }
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
        modifier = Modifier.dpadFocusable(CircleShape).clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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

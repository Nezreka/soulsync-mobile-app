package net.ssync.app.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.SubtitleCandidate
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

private val VIDEO_SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val SUB_LANGS = listOf("en", "es", "fr", "de", "it", "pt", "ja", "ko", "zh", "nl", "pl", "ru")

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerOverlay(
    item: ActivePlayable,
    api: PlayerApiClient,
    pinnedHttpClient: OkHttpClient,
    onClose: (positionMs: Long, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) { PlayerDevicePrefs.init(context) }
    val displayPrefs by PlayerDevicePrefs.state.collectAsState()
    val resizeMode = when (displayPrefs.pictureFit) {
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    val dimAlpha = (1f - displayPrefs.brightness).coerceIn(0f, 0.85f)

    val exoPlayer = remember(item.streamUrl) {
        val dataSourceFactory = OkHttpDataSource.Factory(pinnedHttpClient)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(item.streamUrl))
                if (item.resumePositionMs > 0L) {
                    seekTo(item.resumePositionMs)
                }
                prepare()
                playWhenReady = true
            }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(item.resumePositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var showHud by remember { mutableStateOf(true) }
    var hudPoke by remember { mutableStateOf(0) }
    var speedIndex by remember { mutableStateOf(1) }
    var showTracksSheet by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (exoPlayer.duration > 0L) durationMs = exoPlayer.duration
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            exoPlayer.release()
            onClose(pos, dur)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                if (exoPlayer.duration > 0L) durationMs = exoPlayer.duration
            }
            delay(400L)
        }
    }

    // Auto-hide cinema HUD after 3.5s while playing
    LaunchedEffect(showHud, isPlaying, hudPoke, showTracksSheet) {
        if (showHud && isPlaying && !showTracksSheet) {
            delay(3500L)
            showHud = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                showHud = !showHud
                hudPoke++
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (dimAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dimAlpha)),
            )
        }

        if (isBuffering) {
            CircularProgressIndicator(
                color = SoulSyncColors.AccentLight,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Custom Best-in-Class Cinema HUD
        AnimatedVisibility(
            visible = showHud,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f)),
            ) {
                // Top Bar: Close + Title + Quick Cinema Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                            val dur = exoPlayer.duration.coerceAtLeast(0L)
                            onClose(pos, dur)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close video", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                color = SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Picture Fit cycle pill
                    CinemaPill(
                        icon = Icons.Default.AspectRatio,
                        label = displayPrefs.pictureFit.replaceFirstChar { it.uppercase() },
                        onClick = {
                            val order = listOf("fit", "fill", "stretch")
                            val next = order[(order.indexOf(displayPrefs.pictureFit) + 1) % order.size]
                            PlayerDevicePrefs.update { it.copy(pictureFit = next) }
                            hudPoke++
                        },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Brightness quick-dim pill
                    CinemaPill(
                        icon = Icons.Default.Brightness6,
                        label = "${(displayPrefs.brightness * 100).roundToInt()}%",
                        onClick = {
                            val steps = listOf(1.0f, 0.80f, 0.60f, 0.45f, 0.30f)
                            val curIdx = steps.indexOfFirst { kotlin.math.abs(it - displayPrefs.brightness) < 0.08f }
                            val next = steps[(curIdx + 1) % steps.size]
                            PlayerDevicePrefs.update { it.copy(brightness = next) }
                            hudPoke++
                        },
                    )
                }

                // Center Cinema Transport: -10s / Play-Pause / +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                            hudPoke++
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10s", tint = Color.White, modifier = Modifier.size(30.dp))
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            hudPoke++
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            val target = exoPlayer.currentPosition + 10_000L
                            exoPlayer.seekTo(if (durationMs > 0L) target.coerceAtMost(durationMs) else target)
                            hudPoke++
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }

                // Bottom Bar: Seek Scrubber + Timestamps + Speed + Subtitles/Audio Tracks
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                            ),
                        )
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    val fraction = if (scrubbing) scrubValue
                    else if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    else 0f

                    Slider(
                        value = fraction,
                        onValueChange = { v ->
                            scrubbing = true
                            scrubValue = v
                            hudPoke++
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0L) {
                                exoPlayer.seekTo((scrubValue * durationMs).toLong())
                            }
                            scrubbing = false
                            hudPoke++
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = SoulSyncColors.AccentLight,
                            activeTrackColor = SoulSyncColors.Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val shownPos = if (scrubbing && durationMs > 0L) (scrubValue * durationMs).toLong() else positionMs
                        val remaining = (durationMs - shownPos).coerceAtLeast(0L)
                        Text(
                            text = "${formatVideoTime(shownPos)}  /  -${formatVideoTime(remaining)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CinemaPill(
                                icon = Icons.Default.Speed,
                                label = "${VIDEO_SPEEDS[speedIndex]}x",
                                onClick = {
                                    speedIndex = (speedIndex + 1) % VIDEO_SPEEDS.size
                                    exoPlayer.playbackParameters = PlaybackParameters(VIDEO_SPEEDS[speedIndex])
                                    hudPoke++
                                },
                            )
                            CinemaPill(
                                icon = Icons.Default.Subtitles,
                                label = "Audio & Subs",
                                onClick = {
                                    showTracksSheet = true
                                    hudPoke++
                                },
                            )
                        }
                    }
                }
            }
        }

        if (showTracksSheet) {
            ModalBottomSheet(
                onDismissRequest = { showTracksSheet = false },
                containerColor = SoulSyncColors.Surface,
                contentColor = Color.White,
            ) {
                val tracks = exoPlayer.currentTracks
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                val canSearchSubs = (item.itemType == "movie" || item.itemType == "episode") && item.itemId.toIntOrNull() != null
                var sheetTab by remember { mutableStateOf("tracks") } // "tracks" | "search"
                var subLang by remember { mutableStateOf("en") }
                var subCandidates by remember { mutableStateOf<List<SubtitleCandidate>>(emptyList()) }
                var searchingSubs by remember { mutableStateOf(false) }
                var subStatus by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(sheetTab, subLang) {
                    if (sheetTab == "search" && canSearchSubs) {
                        searchingSubs = true
                        subStatus = null
                        val idInt = item.itemId.toInt()
                        when (val res = api.searchSubtitles(item.itemType, idInt, subLang)) {
                            is MusicApiResult.Success -> subCandidates = res.data
                            is MusicApiResult.Error -> subStatus = res.message
                            MusicApiResult.DeviceRevoked -> Unit
                        }
                        searchingSubs = false
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Audio & Subtitles", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        if (canSearchSubs) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.4f))
                                    .border(1.dp, SoulSyncColors.Line, CircleShape)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("tracks" to "Tracks", "search" to "Find Subtitles").forEach { (key, label) ->
                                    val on = sheetTab == key
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.25f) else Color.Transparent)
                                            .clickable { sheetTab = key }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (on) Color.White else SoulSyncColors.TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (sheetTab == "tracks") {
                        Text("AUDIO TRACKS", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (audioGroups.isEmpty()) {
                            Text("Default audio stream", color = SoulSyncColors.TextSoft, fontSize = 13.sp)
                        } else {
                            audioGroups.forEachIndexed { gIdx, group ->
                                val fmt = group.getTrackFormat(0)
                                val label = listOfNotNull(fmt.label, fmt.language?.uppercase()).joinToString(" · ")
                                    .ifBlank { "Audio Track ${gIdx + 1}" }
                                TrackOptionRow(label = label, selected = group.isSelected) {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                        .build()
                                    showTracksSheet = false
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("SUBTITLES", color = SoulSyncColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        TrackOptionRow(
                            label = "Off",
                            selected = textGroups.none { it.isSelected },
                        ) {
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            showTracksSheet = false
                        }
                        textGroups.forEachIndexed { gIdx, group ->
                            val fmt = group.getTrackFormat(0)
                            val label = listOfNotNull(fmt.label, fmt.language?.uppercase()).joinToString(" · ")
                                .ifBlank { "Subtitle ${gIdx + 1}" }
                            TrackOptionRow(label = label, selected = group.isSelected) {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                    .build()
                                showTracksSheet = false
                            }
                        }
                    } else {
                        // Find Subtitles via SoulSync's subtitle providers
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(SUB_LANGS, key = { it }) { code ->
                                val on = subLang == code
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
                                        .border(1.dp, if (on) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line, CircleShape)
                                        .clickable { subLang = code }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Text(code.uppercase(), color = if (on) Color.White else SoulSyncColors.TextSoft, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (subStatus != null) {
                            Text(subStatus!!, color = SoulSyncColors.AccentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (searchingSubs) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = SoulSyncColors.AccentLight, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Searching subtitle providers…", color = SoulSyncColors.TextSoft, fontSize = 13.sp)
                            }
                        } else if (subCandidates.isEmpty()) {
                            Text("No subtitle matches found for ${subLang.uppercase()}.", color = SoulSyncColors.TextSecondary, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(subCandidates, key = { it.candidateId }) { cand ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White.copy(alpha = 0.04f))
                                            .border(
                                                1.dp,
                                                if (cand.hashMatch) SoulSyncColors.Accent.copy(alpha = 0.5f) else SoulSyncColors.Line,
                                                RoundedCornerShape(10.dp),
                                            )
                                            .clickable {
                                                val idInt = item.itemId.toIntOrNull() ?: return@clickable
                                                subStatus = "Downloading subtitle…"
                                                scope.launch {
                                                    when (val r = api.downloadSubtitle(item.itemType, idInt, subLang, cand.candidateId)) {
                                                        is MusicApiResult.Success -> {
                                                            subStatus = "Subtitle saved! Reloading stream…"
                                                            val resumeAt = exoPlayer.currentPosition.coerceAtLeast(0L)
                                                            exoPlayer.setMediaItem(MediaItem.fromUri(item.streamUrl))
                                                            exoPlayer.seekTo(resumeAt)
                                                            exoPlayer.prepare()
                                                            exoPlayer.playWhenReady = true
                                                            showTracksSheet = false
                                                        }
                                                        is MusicApiResult.Error -> subStatus = r.message
                                                        MusicApiResult.DeviceRevoked -> Unit
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = cand.title.ifBlank { "${cand.provider} (${cand.language})" },
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = buildString {
                                                    append(cand.provider)
                                                    if (cand.hashMatch) append(" · HASH MATCH")
                                                    if (cand.hi) append(" · HI")
                                                    if (cand.forced) append(" · Forced")
                                                    if (cand.score > 0) append(" · ${cand.score.roundToInt()}%")
                                                },
                                                color = if (cand.hashMatch) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                                                fontSize = 11.sp,
                                            )
                                        }
                                        Icon(Icons.Default.Download, contentDescription = "Download subtitle", tint = SoulSyncColors.AccentLight, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .dpadFocusable(CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(15.dp))
        Text(text = label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TrackOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) SoulSyncColors.Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (selected) SoulSyncColors.Accent.copy(alpha = 0.55f) else SoulSyncColors.Line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else SoulSyncColors.TextSoft,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun formatVideoTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs) else "%d:%02d".format(mins, secs)
}

package net.ssync.app.player

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
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
import androidx.media3.common.PlaybackException
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
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
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
    castConnected: Boolean = false,
    onOpenCast: (() -> Unit)? = null,
    onOpenChannel: ((String) -> Unit)? = null,
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

    val isRemoteStream = remember(item.streamUrl) {
        item.streamUrl.startsWith("http://", ignoreCase = true) ||
            item.streamUrl.startsWith("https://", ignoreCase = true)
    }

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
                // Only direct-start immediately for offline local files; for remote streams we
                // consult /api/player/playable first (like desktop) so AC-3/E-AC-3/HEVC/AV1/DTS
                // files start directly on the transcoded H.264+AAC stream instead of rendering a
                // black screen with silent audio while the clock ticks!
                if (!isRemoteStream) {
                    setMediaItem(MediaItem.fromUri(item.streamUrl))
                    if (item.resumePositionMs > 0L) {
                        seekTo(item.resumePositionMs)
                    }
                    prepare()
                    playWhenReady = true
                }
            }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(item.resumePositionMs) }
    var durationMs by remember(item.streamUrl) { mutableLongStateOf(item.durationMs.coerceAtLeast(0L)) }
    var usingTranscode by remember(item.streamUrl) { mutableStateOf(false) }
    var transcodeOffsetMs by remember(item.streamUrl) { mutableLongStateOf(0L) }
    val transcodeScreenId = remember(item.streamUrl) {
        "and-" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    var showHud by remember { mutableStateOf(true) }
    var hudPoke by remember { mutableStateOf(0) }
    var speedIndex by remember { mutableStateOf(1) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    val canTranscode = item.itemType == "movie" || item.itemType == "episode" || item.itemType == "youtube"

    fun effectivePositionMs(): Long {
        val raw = exoPlayer.currentPosition.coerceAtLeast(0L)
        return if (usingTranscode) transcodeOffsetMs + raw else raw
    }

    fun effectiveDurationMs(): Long {
        val exoDur = exoPlayer.duration
        return when {
            !usingTranscode && exoDur > 0L -> exoDur
            durationMs > 0L -> durationMs
            exoDur > 0L -> exoDur
            else -> 0L
        }
    }

    fun startTranscodeAt(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L).let {
            if (durationMs > 0L) it.coerceAtMost((durationMs - 1000L).coerceAtLeast(0L)) else it
        }
        usingTranscode = true
        transcodeOffsetMs = clamped
        positionMs = clamped
        playbackError = null
        isBuffering = true
        val url = api.transcodeUrl(
            itemType = item.itemType,
            itemId = item.itemId,
            startSec = clamped / 1000.0,
            screenId = transcodeScreenId,
        )
        exoPlayer.stop()
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(item.streamUrl, item.itemType, item.itemId) {
        if (!isRemoteStream) return@LaunchedEffect
        if (canTranscode) {
            val res = api.fetchPlayable(item.itemType, item.itemId)
            if (res is MusicApiResult.Success) {
                val decision = res.data
                if (decision.durationMs > 0L && durationMs <= 0L) {
                    durationMs = decision.durationMs
                }
                if (decision.needsTranscode) {
                    startTranscodeAt(item.resumePositionMs)
                    return@LaunchedEffect
                }
            }
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(item.streamUrl))
        if (item.resumePositionMs > 0L) {
            exoPlayer.seekTo(item.resumePositionMs)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun seekOrTranscode(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L).let {
            if (durationMs > 0L) it.coerceAtMost(durationMs) else it
        }
        if (usingTranscode) {
            startTranscodeAt(clamped)
        } else {
            exoPlayer.seekTo(clamped)
        }
    }

    var hasRenderedFirstFrame by remember(item.streamUrl) { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) playbackError = null
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // If direct play reached READY and selected no supported video or audio track
                // (e.g. Dolby E-AC-3/DTS/TrueHD or 10-bit HEVC/AV1 where ExoPlayer silently drops
                // the unsupported track without throwing PlaybackException, resulting in a black
                // screen or silent playback while the timeline clock ticks), switch immediately
                // to the server's H.264 + AAC transcode stream!
                if (!usingTranscode && canTranscode && tracks.groups.isNotEmpty()) {
                    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    val videoUnsupported = videoGroups.isNotEmpty() && videoGroups.none { it.isSupported }
                    val audioUnsupported = audioGroups.isNotEmpty() && audioGroups.none { it.isSupported }
                    if (videoUnsupported || audioUnsupported) {
                        val resumeAt = effectivePositionMs().takeIf { it > 0L } ?: item.resumePositionMs
                        startTranscodeAt(resumeAt)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (!usingTranscode && exoPlayer.duration > 0L) {
                    durationMs = exoPlayer.duration
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Auto-fallback to server transcode when direct play hits a codec,
                // audio renderer (e.g. Dolby E-AC-3 / Atmos 5.1, TrueHD, DTS), or
                // container/parser error on this device.
                val msg = error.localizedMessage.orEmpty()
                val isCodecOrRendererError =
                    error.errorCode in 3000..5999 ||
                        msg.contains("MediaCodec", ignoreCase = true) ||
                        msg.contains("AudioRenderer", ignoreCase = true) ||
                        msg.contains("VideoRenderer", ignoreCase = true) ||
                        msg.contains("audio/eac3", ignoreCase = true) ||
                        msg.contains("audio/ac3", ignoreCase = true) ||
                        msg.contains("Decoder", ignoreCase = true)
                if (!usingTranscode && canTranscode && isCodecOrRendererError) {
                    val resumeAt = effectivePositionMs().takeIf { it > 0L } ?: item.resumePositionMs
                    startTranscodeAt(resumeAt)
                    return
                }
                isBuffering = false
                isPlaying = false
                showHud = true
                playbackError = error.localizedMessage
                    ?: "Couldn't play this video (${error.errorCodeName}). The file may be corrupt or unreachable."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            val pos = effectivePositionMs()
            val dur = effectiveDurationMs()
            exoPlayer.release()
            onClose(pos, dur)
        }
    }

    var watchedMarked by remember(item.itemType, item.itemId) { mutableStateOf(false) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing) {
                positionMs = effectivePositionMs()
                if (!usingTranscode && exoPlayer.duration > 0L) {
                    durationMs = exoPlayer.duration
                }
                if (
                    !watchedMarked &&
                    durationMs > 0L &&
                    positionMs >= (durationMs * 0.92).toLong() &&
                    (item.itemType == "movie" || (!item.isLiveGuide && item.itemType == "episode"))
                ) {
                    watchedMarked = true
                    scope.launch {
                        api.markWatched(itemType = item.itemType, itemId = item.itemId)
                    }
                }
            }
            delay(400L)
        }
    }

    // True immersive fullscreen while video player is open (auto-restores system bars on close)
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
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

        // Live full-screen Watch Party floating emoji reactions (visible to all guests)
        if (!item.partyId.isNullOrBlank()) {
            WatchPartyFullscreenReactions(
                partyId = item.partyId,
                api = api,
                hudVisible = showHud,
                onInteract = { hudPoke++ },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (isBuffering && playbackError == null) {
            CircularProgressIndicator(
                color = SoulSyncColors.AccentLight,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (playbackError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.86f))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Playback error",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = playbackError.orEmpty(),
                    color = SoulSyncColors.TextSoft,
                    fontSize = 13.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CinemaPill(
                        icon = Icons.Default.PlayArrow,
                        label = "Retry",
                        onClick = {
                            if (usingTranscode) {
                                startTranscodeAt(effectivePositionMs().takeIf { it > 0L } ?: item.resumePositionMs)
                            } else {
                                playbackError = null
                                isBuffering = true
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        },
                    )
                    if (!usingTranscode && canTranscode) {
                        CinemaPill(
                            icon = Icons.Default.Speed,
                            label = "Transcode",
                            onClick = {
                                startTranscodeAt(effectivePositionMs().takeIf { it > 0L } ?: item.resumePositionMs)
                            },
                        )
                    }
                    CinemaPill(
                        icon = Icons.Default.Close,
                        label = "Close",
                        onClick = { onClose(effectivePositionMs(), effectiveDurationMs()) },
                    )
                }
            }
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
                // Top Bar: Close + Title (+ clickable Channel/Show subtitle) + Cast icon
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            onClose(effectivePositionMs(), effectiveDurationMs())
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
                    val canJumpToChannel = item.itemType == "youtube" && !item.parentId.isNullOrBlank() && onOpenChannel != null
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (usingTranscode) {
                                Text(
                                    text = "TRANSCODED",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = if (canJumpToChannel) "${item.subtitle} ›" else item.subtitle,
                                color = if (canJumpToChannel) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
                                fontSize = 12.sp,
                                fontWeight = if (canJumpToChannel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (canJumpToChannel) {
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { onOpenChannel?.invoke(item.parentId!!) }
                                        .padding(vertical = 2.dp)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (onOpenCast != null) {
                                onOpenCast()
                            } else {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                                }
                            }
                            hudPoke++
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (castConnected) SoulSyncColors.Accent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f))
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = if (castConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = "Cast",
                            tint = if (castConnected) SoulSyncColors.AccentLight else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Center Cinema Transport: -10s / Play-Pause / +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            seekOrTranscode(effectivePositionMs() - 10_000L)
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
                            seekOrTranscode(effectivePositionMs() + 10_000L)
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
                                seekOrTranscode((scrubValue * durationMs).toLong())
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

                    val shownPos = if (scrubbing && durationMs > 0L) (scrubValue * durationMs).toLong() else positionMs
                    val remaining = (durationMs - shownPos).coerceAtLeast(0L)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatVideoTime(shownPos),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "-${formatVideoTime(remaining)}",
                            color = SoulSyncColors.TextSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Clean scrollable cinema action bar (never overflows portrait or landscape)
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            CinemaPill(
                                icon = Icons.Default.Speed,
                                label = "${VIDEO_SPEEDS[speedIndex]}x",
                                onClick = {
                                    speedIndex = (speedIndex + 1) % VIDEO_SPEEDS.size
                                    exoPlayer.playbackParameters = PlaybackParameters(VIDEO_SPEEDS[speedIndex])
                                    hudPoke++
                                },
                            )
                        }
                        item {
                            CinemaPill(
                                icon = Icons.Default.Subtitles,
                                label = "Audio & Subs",
                                onClick = {
                                    showTracksSheet = true
                                    hudPoke++
                                },
                            )
                        }
                        item {
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
                        }
                        item {
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
                        if (item.itemType == "youtube" && !item.parentId.isNullOrBlank() && onOpenChannel != null) {
                            item {
                                CinemaPill(
                                    icon = Icons.Default.PlayArrow,
                                    label = "Channel",
                                    onClick = { onOpenChannel(item.parentId) },
                                )
                            }
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
                                                            val resumeAt = effectivePositionMs()
                                                            if (usingTranscode) {
                                                                startTranscodeAt(resumeAt)
                                                            } else {
                                                                exoPlayer.setMediaItem(MediaItem.fromUri(item.streamUrl))
                                                                exoPlayer.seekTo(resumeAt)
                                                                exoPlayer.prepare()
                                                                exoPlayer.playWhenReady = true
                                                            }
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

private val PARTY_REACTION_EMOJIS = listOf("🍿", "🔥", "😂", "😮", "👏", "❤️")

private data class FloatingPartyEmoji(
    val uid: String,
    val emoji: String,
    val sender: String,
    val rightOffsetDp: Float,
    val swayDp: Float,
)

@Composable
private fun WatchPartyFullscreenReactions(
    partyId: String,
    api: PlayerApiClient,
    hudVisible: Boolean,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var bubbles by remember(partyId) { mutableStateOf<List<FloatingPartyEmoji>>(emptyList()) }
    var muted by remember(partyId) { mutableStateOf(false) }
    val seenIds = remember(partyId) { HashSet<Int>() }
    var initialized by remember(partyId) { mutableStateOf(false) }

    fun spawn(uid: String, emoji: String, sender: String, seed: Int) {
        val rightDp = 18f + ((kotlin.math.abs(seed) * 23) % 64)
        val sway = (if (seed % 2 == 0) 1f else -1f) * (10f + (kotlin.math.abs(seed) % 14))
        val bubble = FloatingPartyEmoji(uid, emoji, sender, rightDp, sway)
        bubbles = (bubbles.takeLast(14)) + bubble
        scope.launch {
            delay(3300L)
            bubbles = bubbles.filterNot { it.uid == uid }
        }
    }

    LaunchedEffect(partyId) {
        while (true) {
            val res = api.fetchWatchParty(partyId)
            if (res is MusicApiResult.Success) {
                val msgs = res.data.messages
                if (!initialized) {
                    initialized = true
                    msgs.forEach { seenIds += it.id }
                } else {
                    for (m in msgs) {
                        if (seenIds.add(m.id)) {
                            val body = m.body.trim()
                            val isReaction = m.kind == "reaction" || body in PARTY_REACTION_EMOJIS
                            if (isReaction && !muted) {
                                spawn("srv-${m.id}", body, m.profile?.name.orEmpty(), m.id)
                            }
                        }
                    }
                }
            }
            delay(2000L)
        }
    }

    Box(modifier = modifier) {
        if (!muted) {
            bubbles.forEach { b ->
                key(b.uid) {
                    FloatingReactionBubble(
                        bubble = b,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = b.rightOffsetDp.dp, bottom = 104.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 108.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xBB0C0E16))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                PARTY_REACTION_EMOJIS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .clickable {
                                onInteract()
                                spawn("local-${System.nanoTime()}", emoji, "You", System.currentTimeMillis().toInt())
                                scope.launch {
                                    api.postWatchPartyChat(partyId, "reaction", emoji)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = emoji, fontSize = 18.sp)
                    }
                }
                Text(
                    text = if (muted) "Off" else "Live",
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .clip(CircleShape)
                        .background(if (muted) Color(0x44F43F5E) else Color.White.copy(alpha = 0.12f))
                        .clickable {
                            onInteract()
                            muted = !muted
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FloatingReactionBubble(
    bubble: FloatingPartyEmoji,
    modifier: Modifier = Modifier,
) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(bubble.uid) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 3200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        )
    }
    val p = progress.value
    val yOffset = (-300f * p).dp
    val xOffset = (kotlin.math.sin(p * Math.PI * 2.0).toFloat() * bubble.swayDp).dp
    val alpha = when {
        p < 0.12f -> p / 0.12f
        p > 0.72f -> ((1f - p) / 0.28f).coerceIn(0f, 1f)
        else -> 1f
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                translationX = xOffset.toPx()
                translationY = yOffset.toPx()
            },
    ) {
        Text(text = bubble.emoji, fontSize = 28.sp)
        if (bubble.sender.isNotBlank()) {
            Text(
                text = bubble.sender,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xAA0A0C14))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

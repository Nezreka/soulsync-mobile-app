package net.ssync.app.player

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
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
    minimized: Boolean = false,
    castConnected: Boolean = false,
    onOpenCast: (() -> Unit)? = null,
    onOpenChannel: ((String) -> Unit)? = null,
    onMinimize: ((positionMs: Long, durationMs: Long) -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
    onLeaveParty: (() -> Unit)? = null,
    nextVideoTitle: String? = null,
    onVideoEnded: (() -> Unit)? = null,
    onSurfChannel: ((delta: Int) -> Unit)? = null,
    onClose: (positionMs: Long, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
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

    // Single reused ExoPlayer instance with decoder fallback enabled:
    // - Prevents Tegra/Shield TV hardware MediaCodec deadlocks & crashes when transitioning
    //   between Guide commercials and shows (never creates a 2nd decoder before releasing the 1st).
    // - Enables software decoder fallback for audio/mpeg (MP2/MP3) Guide tracks when hardware
    //   audio decoders reject mid-stream frames.
    val exoPlayer = remember {
        val dataSourceFactory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(pinnedHttpClient))
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    val latestItem by androidx.compose.runtime.rememberUpdatedState(item)
    val latestOnVideoEnded by androidx.compose.runtime.rememberUpdatedState(onVideoEnded)
    val latestOnClose by androidx.compose.runtime.rememberUpdatedState(onClose)

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
    var hasRenderedFirstFrame by remember(item.streamUrl) { mutableStateOf(false) }

    val canTranscode = item.itemType == "movie" || item.itemType == "episode" || item.itemType == "youtube"

    fun effectivePositionMs(): Long {
        val raw = exoPlayer.currentPosition.coerceAtLeast(0L)
        return when {
            usingTranscode -> transcodeOffsetMs + raw
            raw == 0L && item.resumePositionMs > 0L && !hasRenderedFirstFrame -> item.resumePositionMs
            else -> raw
        }
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
        playbackError = null
        isBuffering = true
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!isRemoteStream) {
            exoPlayer.setMediaItem(MediaItem.fromUri(item.streamUrl))
            if (item.resumePositionMs > 0L) {
                exoPlayer.seekTo(item.resumePositionMs)
            }
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }
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

    /**
     * ten seconds on or back from the remote: on the item's own clock (a
     * transcoded stream's player clock starts where the stream did), and
     * never to 0 when the length isn't known yet (fast forward restarted it)
     */
    fun skipBy(deltaMs: Long) {
        val total = effectiveDurationMs()
        val target = (effectivePositionMs() + deltaMs).coerceAtLeast(0L)
        seekOrTranscode(if (total > 0L) target.coerceAtMost(total) else target)
    }

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
                if (playbackState == Player.STATE_ENDED && latestItem.isLiveGuide) {
                    latestOnVideoEnded?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = latestItem
                val curCanTranscode = cur.itemType == "movie" || cur.itemType == "episode" || cur.itemType == "youtube"
                // If a Live Guide commercial clip fails to decode (e.g. broken audio/mpeg frame),
                // skip seamlessly into the next scheduled item instead of interrupting playback.
                if (cur.isLiveGuide && cur.itemType == "commercial") {
                    latestOnVideoEnded?.invoke()
                    return
                }
                // Auto-fallback to server transcode when direct play hits a codec,
                // audio renderer (e.g. audio/mpeg, Dolby E-AC-3 / Atmos 5.1, TrueHD, DTS), or
                // container/parser error on this device.
                val msg = error.localizedMessage.orEmpty()
                val isCodecOrRendererError =
                    error.errorCode in 3000..5999 ||
                        msg.contains("MediaCodec", ignoreCase = true) ||
                        msg.contains("AudioRenderer", ignoreCase = true) ||
                        msg.contains("VideoRenderer", ignoreCase = true) ||
                        msg.contains("audio/mpeg", ignoreCase = true) ||
                        msg.contains("audio/eac3", ignoreCase = true) ||
                        msg.contains("audio/ac3", ignoreCase = true) ||
                        msg.contains("Decoder", ignoreCase = true)
                if (!usingTranscode && curCanTranscode && isCodecOrRendererError) {
                    val resumeAt = effectivePositionMs().takeIf { it > 0L } ?: cur.resumePositionMs
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
            runCatching { exoPlayer.stop() }
            runCatching { exoPlayer.release() }
            latestOnClose(pos, dur)
        }
    }

    var watchedMarked by remember(item.itemType, item.itemId) { mutableStateOf(false) }
    var lastPresenceBeatMs by remember(item.itemType, item.itemId) { mutableLongStateOf(0L) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!scrubbing) {
                positionMs = effectivePositionMs()
                if (!usingTranscode && exoPlayer.duration > 0L) {
                    durationMs = exoPlayer.duration
                }
                val now = System.currentTimeMillis()
                if (now - lastPresenceBeatMs >= 8_000L) {
                    lastPresenceBeatMs = now
                    val channelRef = item.channelId?.takeIf { it.isNotBlank() }?.let { cid ->
                        net.ssync.player.PresenceChannelRef(
                            id = cid,
                            name = item.channelName.orEmpty(),
                            number = item.channelNumber,
                        )
                    }
                    scope.launch {
                        api.sendPresence(
                            itemType = item.itemType,
                            itemId = item.itemId,
                            title = item.title,
                            subtitle = item.subtitle.ifBlank { null },
                            artwork = item.artworkUrl,
                            positionSeconds = positionMs / 1000.0,
                            durationSeconds = durationMs.takeIf { it > 0L }?.let { it / 1000.0 },
                            paused = !isPlaying,
                            live = item.isLiveGuide,
                            channel = channelRef,
                        )
                    }
                }
                if (
                    !watchedMarked &&
                    durationMs > 0L &&
                    positionMs >= (durationMs * 0.92).toLong() &&
                    (item.itemType == "movie" || (!item.isLiveGuide && item.itemType == "episode"))
                ) {
                    watchedMarked = true
                    val atS = positionMs / 1000.0
                    val lenS = durationMs / 1000.0
                    scope.launch {
                        val r = api.markWatched(itemType = item.itemType, itemId = item.itemId)
                        // a download remembers it; offline, the mark waits for the connection
                        OfflineSync.noteLocal(item.itemType, item.itemId, atS, lenS, watched = true)
                        if (r is MusicApiResult.Error && item.streamUrl.startsWith("file:")) {
                            OfflineSync.queueEvent("watched", item.itemType, item.itemId, atS, lenS)
                        }
                    }
                }
            }
            delay(400L)
        }
    }

    // True immersive fullscreen while video player is open in fullscreen (restores system bars when minimized or closed)
    DisposableEffect(context, minimized) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!minimized) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Auto-hide cinema HUD after 5.0s on TV (3.5s on touch) while playing in fullscreen
    LaunchedEffect(showHud, isPlaying, hudPoke, showTracksSheet, minimized, isTv) {
        if (!minimized && showHud && isPlaying && !showTracksSheet) {
            delay(if (isTv) 5000L else 3500L)
            showHud = false
        }
    }

    if (minimized) {
        val frac = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF20D111C))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .clickable { onExpand?.invoke() },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Live 16:9 Mini Video Frame
                    Box(
                        modifier = Modifier
                            .width(110.dp)
                            .height(62.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                            .clickable { onExpand?.invoke() },
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                    this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                }
                            },
                            update = { view ->
                                if (view.player !== exoPlayer) {
                                    view.player = exoPlayer
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onExpand?.invoke() },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!item.partyId.isNullOrBlank()) {
                                Text(
                                    text = "🍿 PARTY",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                text = item.title,
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = item.subtitle.ifBlank { "${formatVideoTime(positionMs)} / ${formatVideoTime(durationMs)}" },
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = {
                            val pid = item.partyId
                            if (!pid.isNullOrBlank()) {
                                val wasPlaying = exoPlayer.isPlaying
                                val posSec = effectivePositionMs() / 1000.0
                                if (wasPlaying) {
                                    exoPlayer.pause()
                                    scope.launch {
                                        api.updateWatchPartyState(pid, action = "pause", position = posSec)
                                    }
                                } else {
                                    scope.launch {
                                        api.updateWatchPartyState(pid, action = "resume", position = posSec)
                                    }
                                }
                            } else {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        },
                        modifier = Modifier.size(36.dp).clip(CircleShape).dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                        )
                    }

                    IconButton(
                        onClick = { onExpand?.invoke() },
                        modifier = Modifier.size(34.dp).clip(CircleShape).dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Expand video",
                            tint = Color.White,
                        )
                    }

                    IconButton(
                        onClick = { onClose(effectivePositionMs(), effectiveDurationMs()) },
                        modifier = Modifier.size(34.dp).clip(CircleShape).dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close video",
                            tint = SoulSyncColors.TextSecondary,
                        )
                    }
                }
                if (durationMs > 0L) {
                    LinearProgressIndicator(
                        progress = { frac },
                        color = SoulSyncColors.AccentLight,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                    )
                }
            }
        }
        return
    }

    // Standard Android TV Back behavior:
    // 1) If Audio/Subtitles sheet is open -> close it
    // 2) If HUD is visible -> hide HUD so user keeps watching uninterrupted
    // 3) If HUD is hidden -> close video cleanly & return to previous screen (never trap TV user in a minimized bar)
    BackHandler(enabled = isTv && !minimized) {
        when {
            showTracksSheet -> showTracksSheet = false
            showHud -> showHud = false
            else -> onClose(effectivePositionMs(), effectiveDurationMs())
        }
    }

    // TV focus management: when HUD opens, focus lands directly on Play/Pause; when HUD hides,
    // focus returns to rootFocus so remote keys (Play/Pause, Seek, Channel, Center) always work.
    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    var skipFeedbackText by remember { mutableStateOf<String?>(null) }
    var skipFeedbackNonce by remember { mutableStateOf(0) }

    LaunchedEffect(skipFeedbackNonce) {
        if (skipFeedbackText != null) {
            delay(900L)
            skipFeedbackText = null
        }
    }

    LaunchedEffect(showHud, isTv, showTracksSheet) {
        if (isTv && !showTracksSheet) {
            if (showHud) {
                delay(60L)
                runCatching { playPauseFocus.requestFocus() }
            } else {
                runCatching { rootFocus.requestFocus() }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!isTv) runCatching { rootFocus.requestFocus() }
    }

    fun togglePlayPauseAction() {
        val pid = item.partyId
        if (!pid.isNullOrBlank()) {
            val wasPlaying = exoPlayer.isPlaying
            val posSec = effectivePositionMs() / 1000.0
            if (wasPlaying) {
                exoPlayer.pause()
                scope.launch {
                    api.updateWatchPartyState(pid, action = "pause", position = posSec)
                }
            } else {
                scope.launch {
                    api.updateWatchPartyState(pid, action = "resume", position = posSec)
                }
            }
        } else {
            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        }
        hudPoke++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                // Keep HUD alive while user is actively navigating with D-pad
                if (showHud && code in setOf(
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                    )
                ) {
                    hudPoke++
                }
                when (code) {
                    android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                    android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                        if (item.isLiveGuide && onSurfChannel != null) {
                            onSurfChannel(1)
                            showHud = true
                            hudPoke++
                            true
                        } else {
                            false
                        }
                    }
                    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                    android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                        if (item.isLiveGuide && onSurfChannel != null) {
                            onSurfChannel(-1)
                            showHud = true
                            hudPoke++
                            true
                        } else {
                            false
                        }
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        togglePlayPauseAction()
                        showHud = true
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        exoPlayer.play()
                        showHud = true
                        hudPoke++
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        exoPlayer.pause()
                        showHud = true
                        hudPoke++
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        skipBy(10_000L)
                        skipFeedbackText = "+10s »"
                        skipFeedbackNonce++
                        showHud = true
                        hudPoke++
                        true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        skipBy(-10_000L)
                        skipFeedbackText = "« -10s"
                        skipFeedbackNonce++
                        showHud = true
                        hudPoke++
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_DPAD_UP,
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (!showHud) {
                            showHud = true
                            hudPoke++
                            true
                        } else {
                            false
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!showHud) {
                            skipBy(10_000L)
                            skipFeedbackText = "+10s »"
                            skipFeedbackNonce++
                            showHud = true
                            hudPoke++
                            true
                        } else {
                            false
                        }
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!showHud) {
                            skipBy(-10_000L)
                            skipFeedbackText = "« -10s"
                            skipFeedbackNonce++
                            showHud = true
                            hudPoke++
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .then(
                if (!isTv) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        showHud = !showHud
                        hudPoke++
                    }
                } else {
                    Modifier
                },
            ),
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

        if (isBuffering && playbackError == null) {
            var bufferSeconds by remember(item.streamUrl, usingTranscode) { mutableStateOf(0) }
            LaunchedEffect(isBuffering, item.streamUrl, usingTranscode) {
                bufferSeconds = 0
                while (isBuffering) {
                    delay(1_000L)
                    bufferSeconds += 1
                }
            }
            val statusHeadline = when {
                bufferSeconds >= 18 -> "Still waking storage drive…"
                bufferSeconds >= 8 -> "Waking storage drive…"
                bufferSeconds >= 3 -> if (usingTranscode) "Preparing transcoded stream…" else "Locating media file on server…"
                else -> if (usingTranscode) "Starting transcode…" else "Loading stream…"
            }
            val statusSubtext = when {
                bufferSeconds >= 8 -> "Sleeping drives can take up to 30 seconds to spin up (${bufferSeconds}s)"
                bufferSeconds >= 3 -> "Hang tight (${bufferSeconds}s)"
                else -> null
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    color = SoulSyncColors.AccentLight,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    text = statusHeadline,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (statusSubtext != null) {
                    Text(
                        text = statusSubtext,
                        color = SoulSyncColors.TextSoft,
                        fontSize = 12.sp,
                    )
                }
            }
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

        var showTvReportDialog by remember(item.itemId) { mutableStateOf(false) }
        if (showTvReportDialog) {
            ReportIssueDialog(
                api = api,
                itemType = item.itemType,
                itemId = item.itemId,
                title = item.title,
                subtitle = item.subtitle,
                positionSec = (effectivePositionMs() / 1000L).toInt(),
                onDismiss = { showTvReportDialog = false },
            )
        }

        // Custom Best-in-Class Cinema HUD (Dedicated TV Bottom Deck vs Mobile Touch HUD)
        AnimatedVisibility(
            visible = showHud,
            enter = if (isTv) fadeIn(tween(200)) + slideInVertically(tween(240)) { it / 7 } else fadeIn(tween(180)),
            exit = if (isTv) fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 7 } else fadeOut(tween(160)),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (isTv) {
                // Flagship 10-Foot Apple TV / Netflix / Plex Bottom-Docked Cinema Deck
                // Leaves the top 65% of the video completely unobstructed; 2-row D-pad flow (Timeline <-> Action Deck)
                Box(modifier = Modifier.fillMaxSize()) {
                    // Subtle top vignette for contrast + deep bottom cinema scrim
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.28f to Color.Black.copy(alpha = 0.72f),
                                    0.65f to Color.Black.copy(alpha = 0.90f),
                                    1.0f to Color.Black.copy(alpha = 0.96f),
                                ),
                            )
                            .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 30.dp),
                    ) {
                        // Row 1: Cinema Title, Live/Transcode Badges, Up Next & Ends-At Clock
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    if (item.isLiveGuide) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFEF4444).copy(alpha = 0.24f))
                                                .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFEF4444)),
                                            )
                                            Text(
                                                text = item.channelNumber?.let { "LIVE · CH $it" } ?: "LIVE",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.6.sp,
                                            )
                                        }
                                    }
                                    if (usingTranscode) {
                                        Text(
                                            text = "TRANSCODED",
                                            color = SoulSyncColors.AccentLight,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SoulSyncColors.Accent.copy(alpha = 0.22f))
                                                .padding(horizontal = 7.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = item.title,
                                    color = Color.White,
                                    fontSize = 26.sp,
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

                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(start = 24.dp),
                            ) {
                                if (!nextVideoTitle.isNullOrBlank()) {
                                    Text(
                                        text = "Up Next · $nextVideoTitle",
                                        color = SoulSyncColors.TextSoft,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(Color.White.copy(alpha = 0.10f))
                                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 12.dp, vertical = 5.dp),
                                    )
                                }
                                val remMs = (durationMs - positionMs).coerceAtLeast(0L)
                                if (durationMs > 0L && remMs > 0L) {
                                    val endsAt = remember(remMs / 60_000L) {
                                        val fmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                        fmt.format(java.util.Date(System.currentTimeMillis() + remMs))
                                    }
                                    Text(
                                        text = "Ends at $endsAt",
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Row 2: D-pad Scrubbable Cinema Timeline
                        val tvFraction = if (scrubbing) scrubValue
                        else if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        else 0f
                        var tvScrubberFocused by remember { mutableStateOf(false) }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (tvScrubberFocused) {
                                        Modifier
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .border(2.5.dp, Color.White, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .focusRequester(scrubberFocus)
                                .onFocusChanged { tvScrubberFocused = it.isFocused || it.hasFocus }
                                .focusable()
                                .onPreviewKeyEvent { ev ->
                                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    val step = if (ev.nativeKeyEvent.repeatCount > 3) 30_000L else 10_000L
                                    when (ev.nativeKeyEvent.keyCode) {
                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            skipBy(-step)
                                            skipFeedbackText = "« -${step / 1000L}s"
                                            skipFeedbackNonce++
                                            hudPoke++
                                            true
                                        }
                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            skipBy(step)
                                            skipFeedbackText = "+${step / 1000L}s »"
                                            skipFeedbackNonce++
                                            hudPoke++
                                            true
                                        }
                                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                        android.view.KeyEvent.KEYCODE_ENTER -> {
                                            togglePlayPauseAction()
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Slider(
                                value = tvFraction,
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
                                    thumbColor = Color.White,
                                    activeTrackColor = if (tvScrubberFocused) Color.White else SoulSyncColors.AccentLight,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        val tvShownPos = if (scrubbing && durationMs > 0L) (scrubValue * durationMs).toLong() else positionMs
                        val tvRemaining = (durationMs - tvShownPos).coerceAtLeast(0L)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatVideoTime(tvShownPos),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (tvScrubberFocused) {
                                Text(
                                    text = "◀ ▶ Seek ±10s  ·  Hold for ±30s",
                                    color = SoulSyncColors.TextSoft,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Text(
                                text = "-${formatVideoTime(tvRemaining)}",
                                color = SoulSyncColors.TextSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Row 3: Unified Horizontal TV Transport & Cinema Action Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            // Left: Transport controls
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                IconButton(
                                    onClick = { togglePlayPauseAction() },
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .focusRequester(playPauseFocus)
                                        .dpadFocusable(CircleShape, scaleOnFocus = true, onLightSurface = true),
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = Color.Black,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        skipBy(-10_000L)
                                        skipFeedbackText = "« -10s"
                                        skipFeedbackNonce++
                                        hudPoke++
                                    },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .dpadFocusable(CircleShape, scaleOnFocus = true),
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Back 10s", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                IconButton(
                                    onClick = {
                                        skipBy(10_000L)
                                        skipFeedbackText = "+10s »"
                                        skipFeedbackNonce++
                                        hudPoke++
                                    },
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.14f))
                                        .dpadFocusable(CircleShape, scaleOnFocus = true),
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                                if (item.isLiveGuide && onSurfChannel != null) {
                                    CinemaPill(
                                        icon = Icons.Default.KeyboardArrowDown,
                                        label = "CH −",
                                        onClick = {
                                            onSurfChannel(-1)
                                            hudPoke++
                                        },
                                    )
                                    CinemaPill(
                                        icon = Icons.Default.KeyboardArrowUp,
                                        label = "CH +",
                                        onClick = {
                                            onSurfChannel(1)
                                            hudPoke++
                                        },
                                    )
                                }
                            }

                            // Right: Audio/Subs, Speed, Fit, Report, Exit
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CinemaPill(
                                    icon = Icons.Default.Subtitles,
                                    label = "Audio & Subs",
                                    onClick = {
                                        showTracksSheet = true
                                        hudPoke++
                                    },
                                )
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
                                    icon = Icons.Default.AspectRatio,
                                    label = displayPrefs.pictureFit.replaceFirstChar { it.uppercase() },
                                    onClick = {
                                        val order = listOf("fit", "fill", "stretch")
                                        val next = order[(order.indexOf(displayPrefs.pictureFit) + 1) % order.size]
                                        PlayerDevicePrefs.update { it.copy(pictureFit = next) }
                                        hudPoke++
                                    },
                                )
                                CinemaPill(
                                    icon = Icons.Default.Flag,
                                    label = "Report",
                                    onClick = {
                                        showTvReportDialog = true
                                        hudPoke++
                                    },
                                )
                                CinemaPill(
                                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                                    label = "Exit",
                                    onClick = { onClose(effectivePositionMs(), effectiveDurationMs()) },
                                )
                            }
                        }
                    }
                }
            } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.36f)),
            ) {
                // Top Bar: Minimize + Title (+ clickable Channel/Show subtitle) + Cast + Close
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
                            if (!isTv && onMinimize != null) {
                                onMinimize(effectivePositionMs(), effectiveDurationMs())
                            } else {
                                onClose(effectivePositionMs(), effectiveDurationMs())
                            }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = when {
                                isTv -> Icons.AutoMirrored.Filled.ArrowBack
                                onMinimize != null -> Icons.Default.KeyboardArrowDown
                                else -> Icons.Default.Close
                            },
                            contentDescription = if (isTv) "Back" else "Minimize video",
                            tint = Color.White,
                        )
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
                    if (item.isLiveGuide && onSurfChannel != null) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "CH ▼",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        onSurfChannel(-1)
                                        hudPoke++
                                    }
                                    .dpadFocusable(RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Text(
                                text = "CH ▲",
                                color = SoulSyncColors.AccentLight,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .clickable {
                                        onSurfChannel(1)
                                        hudPoke++
                                    }
                                    .dpadFocusable(RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    var showReportDialog by remember(item.itemId) { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            showReportDialog = true
                            hudPoke++
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .dpadFocusable(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Flag,
                            contentDescription = "Report issue",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    if (showReportDialog) {
                        ReportIssueDialog(
                            api = api,
                            itemType = item.itemType,
                            itemId = item.itemId,
                            title = item.title,
                            subtitle = item.subtitle,
                            positionSec = (effectivePositionMs() / 1000L).toInt(),
                            onDismiss = { showReportDialog = false },
                        )
                    }
                    if (!isTv) {
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
                    Spacer(modifier = Modifier.width(8.dp))
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
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Stop and close video",
                            tint = Color.White,
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
                        onClick = { togglePlayPauseAction() },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .focusRequester(playPauseFocus)
                            .dpadFocusable(CircleShape, scaleOnFocus = true),
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

                    var scrubberFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (scrubberFocused) {
                                    Modifier
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .border(2.dp, SoulSyncColors.AccentLight, RoundedCornerShape(10.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .focusRequester(scrubberFocus)
                            .onFocusChanged { scrubberFocused = it.isFocused || it.hasFocus }
                            .then(if (isTv) Modifier.focusable() else Modifier)
                            .onPreviewKeyEvent { ev ->
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val step = if (ev.nativeKeyEvent.repeatCount > 3) 30_000L else 10_000L
                                when (ev.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        skipBy(-step)
                                        skipFeedbackText = "« -${step / 1000L}s"
                                        skipFeedbackNonce++
                                        hudPoke++
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        skipBy(step)
                                        skipFeedbackText = "+${step / 1000L}s »"
                                        skipFeedbackNonce++
                                        hudPoke++
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                    android.view.KeyEvent.KEYCODE_ENTER -> {
                                        togglePlayPauseAction()
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .padding(horizontal = 4.dp),
                    ) {
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
                                thumbColor = if (scrubberFocused) Color.White else SoulSyncColors.AccentLight,
                                activeTrackColor = if (scrubberFocused) SoulSyncColors.AccentLight else SoulSyncColors.Accent,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

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
        }

        // Crisp skip feedback pill ("« -10s" / "+10s »") when seeking with TV remote
        AnimatedVisibility(
            visible = skipFeedbackText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .border(1.5.dp, SoulSyncColors.AccentLight, RoundedCornerShape(999.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(
                    text = skipFeedbackText.orEmpty(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Fullscreen Watch Party Curtain Unfurl, Live Sync, Pee Break Alert, 5s Resume Countdown & Dual-Side Emoji Reactions
        if (!item.partyId.isNullOrBlank()) {
            WatchPartyFullscreenReactions(
                partyId = item.partyId,
                movieTitle = item.title,
                playingItemId = item.itemId,
                api = api,
                exoPlayer = exoPlayer,
                currentPositionMs = { effectivePositionMs() },
                onSeekMs = { seekOrTranscode(it) },
                hudVisible = showHud,
                onInteract = { hudPoke++ },
                onLeaveParty = {
                    val pid = item.partyId
                    scope.launch {
                        api.leaveWatchParty(pid)
                    }
                    onLeaveParty?.invoke()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showTracksSheet) {
            AdaptiveTvSheet(
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
            .dpadFocusable(RoundedCornerShape(10.dp))
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
    val isLeft: Boolean,
    val edgeOffsetDp: Float,
    val swayDp: Float,
)

@Composable
private fun WatchPartyFullscreenReactions(
    partyId: String,
    movieTitle: String,
    /** what's playing here: the room's clock only applies to the room's title */
    playingItemId: String,
    api: PlayerApiClient,
    exoPlayer: ExoPlayer,
    currentPositionMs: () -> Long,
    onSeekMs: (Long) -> Unit,
    hudVisible: Boolean,
    onInteract: () -> Unit,
    onLeaveParty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var party by remember(partyId) { mutableStateOf<net.ssync.player.WatchParty?>(null) }
    var bubbles by remember(partyId) { mutableStateOf<List<FloatingPartyEmoji>>(emptyList()) }
    var muted by remember(partyId) { mutableStateOf(false) }
    var nowMs by remember(partyId) { mutableLongStateOf(System.currentTimeMillis()) }
    var serverOffsetSec by remember(partyId) { mutableStateOf(0.0) }
    var lastLocalActionMs by remember(partyId) { mutableLongStateOf(0L) }
    var rateLimitedUntilMs by remember(partyId) { mutableLongStateOf(0L) }
    var toastMsg by remember(partyId) { mutableStateOf<String?>(null) }
    var chatToasts by remember(partyId) { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    var chatOpen by remember(partyId) { mutableStateOf(false) }
    var chatInput by remember(partyId) { mutableStateOf("") }
    var unreadChat by remember(partyId) { mutableStateOf(0) }
    var videoEnded by remember(partyId) { mutableStateOf(false) }

    // Full-viewport velvet theater curtain unfurl directly over the video player
    var curtainMounted by remember(partyId) { mutableStateOf(true) }
    var curtainOpen by remember(partyId) { mutableStateOf(false) }

    val seenIds = remember(partyId) { HashSet<Int>() }
    var initialized by remember(partyId) { mutableStateOf(false) }
    val reactionTimestamps = remember(partyId) { mutableListOf<Long>() }

    fun spawnChatToast(uid: String, sender: String, body: String) {
        chatToasts = (chatToasts.takeLast(2)) + Triple(uid, sender, body)
        scope.launch {
            delay(5500L)
            chatToasts = chatToasts.filterNot { it.first == uid }
        }
    }

    fun showToast(msg: String) {
        toastMsg = msg
        scope.launch {
            delay(3000L)
            if (toastMsg == msg) toastMsg = null
        }
    }

    LaunchedEffect(partyId) {
        curtainMounted = true
        curtainOpen = false
        delay(420L)
        curtainOpen = true
        delay(2200L)
        curtainMounted = false
    }

    LaunchedEffect(partyId) {
        while (true) {
            delay(200L)
            nowMs = System.currentTimeMillis()
            videoEnded = exoPlayer.playbackState == Player.STATE_ENDED
        }
    }

    fun spawn(uid: String, emoji: String, sender: String, seed: Int) {
        val positiveSeed = seed and 0x7FFFFFFF
        val isLeft = (positiveSeed % 2 == 0)
        val edgeDp = (18f + (((positiveSeed % 62) * 23) % 62)).coerceIn(12f, 84f)
        val sway = (if (positiveSeed % 3 == 0) 1f else -1f) * (11f + (positiveSeed % 15))
        val bubble = FloatingPartyEmoji(uid, emoji, sender, isLeft, edgeDp, sway)
        bubbles = (bubbles.takeLast(18)) + bubble
        scope.launch {
            delay(3300L)
            bubbles = bubbles.filterNot { it.uid == uid }
        }
    }

    var lastSyncSeekMs by remember { mutableLongStateOf(0L) }

    fun applyPartySync(p: net.ssync.player.WatchParty) {
        party = p
        if (p.serverNow > 0.0) {
            serverOffsetSec = p.serverNow - (System.currentTimeMillis() / 1000.0)
        }
        // another title is playing (the next episode): the room's clock isn't its
        if (p.itemId.isNotBlank() && p.itemId != playingItemId) return
        val estServerNow = (System.currentTimeMillis() / 1000.0) + serverOffsetSec
        // a poll cycle plus the request, as the web player: a poll that left
        // before this viewer's change landed mustn't undo it
        val recentlyActed = (System.currentTimeMillis() - lastLocalActionMs) < 3000L
        // a seek this sync made is still landing (a transcode restarting, or
        // buffering at the new spot): seeking again restarted it every poll
        val settling = exoPlayer.playbackState == Player.STATE_BUFFERING ||
            System.currentTimeMillis() - lastSyncSeekMs < 6000L
        fun syncSeek(ms: Long) {
            if (settling) return
            lastSyncSeekMs = System.currentTimeMillis()
            onSeekMs(ms)
        }
        val inResumeCountdown = (p.resumeAt ?: 0.0) > estServerNow
        val localPosSec = currentPositionMs() / 1000.0

        if (p.status == "paused" || inResumeCountdown) {
            if (exoPlayer.isPlaying && !recentlyActed) {
                exoPlayer.pause()
            }
            val targetSec = if (p.position > 0.0) p.position else p.livePosition
            if (kotlin.math.abs(localPosSec - targetSec) > 2.5 && !recentlyActed) {
                syncSeek((targetSec * 1000.0).toLong())
            }
        } else if (p.status == "playing" && !inResumeCountdown) {
            if (!exoPlayer.isPlaying && !recentlyActed) {
                if (kotlin.math.abs(localPosSec - p.livePosition) > 2.5) {
                    syncSeek((p.livePosition * 1000.0).toLong())
                }
                exoPlayer.play()
            } else if (exoPlayer.isPlaying && kotlin.math.abs(localPosSec - p.livePosition) > 4.5 && !recentlyActed) {
                syncSeek((p.livePosition * 1000.0).toLong())
            }
        }
    }

    LaunchedEffect(partyId) {
        while (true) {
            val res = api.fetchWatchParty(partyId)
            if (res is MusicApiResult.Success) {
                val fetched = res.data
                applyPartySync(fetched)
                val msgs = fetched.messages
                if (!initialized) {
                    initialized = true
                    msgs.forEach { seenIds += it.id }
                } else {
                    for (m in msgs) {
                        if (seenIds.add(m.id)) {
                            val body = m.body.trim()
                            val isReaction = m.kind == "reaction" || body in PARTY_REACTION_EMOJIS || body.startsWith("🚽")
                            if (isReaction && !muted) {
                                spawn("srv-${m.id}", body, m.profile?.name.orEmpty(), m.id)
                            } else if (m.kind == "chat" && body.isNotEmpty()) {
                                if (!muted && !chatOpen) {
                                    spawnChatToast("chat-${m.id}", m.profile?.name ?: "Someone", body)
                                }
                                if (!chatOpen) unreadChat += 1
                            }
                        }
                    }
                }
            }
            delay(1800L)
        }
    }

    val estServerNow = (nowMs / 1000.0) + serverOffsetSec
    val resumeRemainingSec = party?.resumeAt?.let { ra ->
        if (ra > estServerNow) kotlin.math.ceil(ra - estServerNow).toInt().coerceAtLeast(1) else null
    }

    // Automatically start playback the exact moment the 5-second countdown hits 0
    var prevResumeRemaining by remember(partyId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(resumeRemainingSec, party?.status) {
        if (prevResumeRemaining != null && resumeRemainingSec == null && party?.status == "playing") {
            if (!exoPlayer.isPlaying) {
                exoPlayer.play()
            }
        }
        prevResumeRemaining = resumeRemainingSec
    }

    val me = party?.members?.firstOrNull { it.you }
    val peeBreak = party?.peeBreak
    val peeEndsRemainingSec = peeBreak?.let { pb ->
        if (pb.endsAt > estServerNow) kotlin.math.ceil(pb.endsAt - estServerNow).toInt().coerceAtLeast(0) else 0
    } ?: 0
    val isMyPeeBreak = peeBreak != null && me?.profileId == peeBreak.profileId
    val canResumePeeBreak = isMyPeeBreak || (party?.isHost == true)
    val peeCooldownSec = party?.peeCooldownRemaining ?: 0
    val isRateLimited = nowMs < rateLimitedUntilMs

    fun triggerTogglePauseResume() {
        val p = party ?: return
        lastLocalActionMs = System.currentTimeMillis()
        val posSec = currentPositionMs() / 1000.0
        scope.launch {
            if (p.status == "playing" && resumeRemainingSec == null) {
                exoPlayer.pause()
                val r = api.updateWatchPartyState(p.id, action = "pause", position = posSec)
                if (r is MusicApiResult.Success) applyPartySync(r.data)
            } else {
                val r = api.updateWatchPartyState(p.id, action = "resume", position = posSec)
                if (r is MusicApiResult.Success) applyPartySync(r.data)
            }
        }
    }

    fun triggerPeeBreak() {
        val p = party ?: return
        lastLocalActionMs = System.currentTimeMillis()
        val posSec = currentPositionMs() / 1000.0
        exoPlayer.pause()
        scope.launch {
            when (val r = api.updateWatchPartyState(p.id, action = "pee_break", position = posSec)) {
                is MusicApiResult.Success -> {
                    applyPartySync(r.data)
                    spawn("pee-L-${System.nanoTime()}", "🚽💨", "You", 2)
                    spawn("pee-R-${System.nanoTime()}", "🚽💨", "You", 3)
                    api.postWatchPartyChat(p.id, "reaction", "🚽💨")
                }
                is MusicApiResult.Error -> showToast(r.message)
                MusicApiResult.DeviceRevoked -> Unit
            }
        }
    }

    fun sendEmojiReaction(emoji: String) {
        val now = System.currentTimeMillis()
        reactionTimestamps.removeAll { now - it >= 4_000L }
        if (reactionTimestamps.size >= 5) {
            rateLimitedUntilMs = now + 2_800L
            showToast("Whoa! Emoji cooldown (3s)…")
            return
        }
        reactionTimestamps += now
        if (reactionTimestamps.size >= 5) {
            rateLimitedUntilMs = now + 2_500L
        }
        val seed = (now.toInt() + reactionTimestamps.size * 19)
        spawn("local-$now-${reactionTimestamps.size}", emoji, "You", seed)
        scope.launch {
            val r = api.postWatchPartyChat(partyId, "reaction", emoji)
            if (r is MusicApiResult.Success) seenIds += r.data.id
        }
    }

    Box(modifier = modifier) {
        // 1. Dual-Side (Left + Right Edge) Floating Emoji Reaction Lanes
        if (!muted) {
            bubbles.forEach { b ->
                key(b.uid) {
                    val safeEdge = b.edgeOffsetDp.coerceAtLeast(0f)
                    FloatingReactionBubble(
                        bubble = b,
                        modifier = Modifier
                            .align(if (b.isLeft) Alignment.BottomStart else Alignment.BottomEnd)
                            .padding(
                                start = if (b.isLeft) safeEdge.dp else 0.dp,
                                end = if (!b.isLeft) safeEdge.dp else 0.dp,
                                bottom = 104.dp,
                            ),
                    )
                }
            }
        }

        // 2. 🚽 5-Minute Pee Break Intermission Banner at Top-Center
        if (peeBreak != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE1F1408))
                    .border(1.5.dp, Color(0xFFFBBF24).copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text = "🚽", fontSize = 26.sp)
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = "${peeBreak.profile?.name ?: "Someone"} needs a 5-min pee break!",
                        color = Color(0xFFFEF3C7),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = if (peeEndsRemainingSec > 0) {
                            "Stream paused · ${formatVideoTime(peeEndsRemainingSec * 1000L)} remaining"
                        } else {
                            "5 minutes up — ready to resume!"
                        },
                        color = Color(0xFFFCD34D),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (canResumePeeBreak) {
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                            .clickable {
                                onInteract()
                                triggerTogglePauseResume()
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = if (isMyPeeBreak) "✓ I'm Back (5s)" else "Resume (5s)",
                            color = Color(0xFF041B11),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }

        // 3. Synchronized 5-Second Resume Countdown Overlay in Center
        if (resumeRemainingSec != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x77060910)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xEB0B0F19))
                        .border(1.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 32.dp, vertical = 22.dp),
                ) {
                    Text(
                        text = "EVERYONE READY · SYNCHRONIZED RESUME",
                        color = Color(0xFFA5B4FC),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.4.sp,
                    )
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(CircleShape)
                            .background(Color(0x384F46E5))
                            .border(3.dp, Color(0xFF818CF8), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$resumeRemainingSec",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Text(
                        text = "Resuming together…",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Toast alert for cooldowns
        if (toastMsg != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 18.dp, bottom = 156.dp)
                    .clip(CircleShape)
                    .background(Color(0xEA0F121C))
                    .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = toastMsg.orEmpty(),
                    color = Color(0xFFFDE68A),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 4. Sleek Frosted Watch Party Social & Control Dock inside the Cinema HUD
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 14.dp, bottom = 104.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xD80A0D16))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                // Party Pause / 5s Countdown Resume button
                val isPaused = party?.status == "paused"
                val pauseDisabled = peeBreak != null && !canResumePeeBreak
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (isPaused) Color(0x3834D399) else Color.White.copy(alpha = 0.10f),
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                        .clickable(enabled = !pauseDisabled) {
                            onInteract()
                            triggerTogglePauseResume()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (isPaused) "▶ Resume (5s)" else "⏸ Pause",
                        color = if (isPaused) Color(0xFF6EE7B7) else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                // 🚽 5-Minute Pee Break Button (10m per-user cooldown, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),)
                val cooldownMin = ((peeCooldownSec + 59) / 60).coerceAtLeast(1)
                val peeDisabled = peeCooldownSec > 0 || peeBreak != null
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            if (peeCooldownSec > 0) Color.White.copy(alpha = 0.05f) else Color(0x33F59E0B),
                        )
                        .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.38f), CircleShape)
                        .clickable(enabled = !peeDisabled) {
                            onInteract()
                            triggerPeeBreak()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = if (peeCooldownSec > 0) "🚽 ${cooldownMin}m" else "🚽 Pee",
                        color = if (peeCooldownSec > 0) Color.White.copy(alpha = 0.45f) else Color(0xFFFDE68A),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }

                PARTY_REACTION_EMOJIS.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !isRateLimited) {
                                onInteract()
                                sendEmojiReaction(emoji)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 17.sp,
                            color = Color.White.copy(alpha = if (isRateLimited) 0.35f else 1f),
                        )
                    }
                }
                Text(
                    text = if (unreadChat > 0 && !chatOpen) "💬 $unreadChat" else "💬",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .background(if (chatOpen) Color(0x666366F1) else Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color(0x55A5B4FC), CircleShape)
                        .clickable {
                            onInteract()
                            chatOpen = !chatOpen
                            if (chatOpen) unreadChat = 0
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text = if (muted) "Off" else "Live",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .background(if (muted) Color(0x44F43F5E) else Color.White.copy(alpha = 0.12f))
                        .clickable {
                            onInteract()
                            muted = !muted
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text = "Leave",
                    color = Color(0xFFFDA4AF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .clip(CircleShape)
                        .background(Color(0x38F43F5E))
                        .border(1.dp, Color(0x77F43F5E), CircleShape)
                        .clickable {
                            onInteract()
                            onLeaveParty()
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }

        // 4b. Top-Right "Who's Here" Presence Strip (when HUD is visible)
        val liveParty = party
        if (hudVisible && liveParty != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 56.dp, end = 16.dp)
                    .clip(CircleShape)
                    .background(Color(0xC80A0D16))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (liveParty.status == "playing") Color(0xFF22C55E) else Color(0xFFF59E0B)),
                )
                Text(
                    text = "${liveParty.memberCount} watching",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                liveParty.members.take(4).forEach { m ->
                    val onBreak = peeBreak?.profileId == m.profileId
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (onBreak) Color(0xFFF59E0B) else Color(0xCC6366F1))
                            .border(1.dp, Color(0xFF0B0F19), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (onBreak) "🚽" else (m.profile?.name ?: "?").take(1).uppercase(),
                            color = if (onBreak) Color.Black else Color.White,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                }
            }
        }

        // 4c. Auto-Fading Live Chat Toasts (bottom-left, non-intrusive)
        if (!muted && !chatOpen && chatToasts.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = if (hudVisible) 150.dp else 28.dp, end = 80.dp),
            ) {
                chatToasts.forEach { (_, sender, body) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xC80A0D16))
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(sender, color = Color(0xFFA5B4FC), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                        Text(body, color = Color.White, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // 4d. Slide-Over In-Player Chat Panel
        if (chatOpen && liveParty != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp, top = 64.dp, bottom = 148.dp)
                    .width(290.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xE80A0D16))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "PARTY CHAT · ${liveParty.memberCount} LIVE",
                        color = Color(0xFFC7D2FE),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        text = "✕",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { chatOpen = false }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                val chatMsgs = liveParty.messages.filter { it.kind != "reaction" }.takeLast(30)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(chatMsgs, key = { "inplayer-msg-${it.id}" }) { m ->
                        if (m.kind == "system") {
                            Text(
                                text = "${m.profile?.name ?: "Someone"} ${m.body.lowercase()}",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 11.sp,
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = m.profile?.name ?: "Someone",
                                    color = Color(0xFFA5B4FC),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = m.body,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        placeholder = { Text("Message…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                        modifier = Modifier.weight(1f).height(46.dp),
                    )
                    Text(
                        text = "Send",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF6366F1))
                            .clickable {
                                val txt = chatInput.trim()
                                if (txt.isNotEmpty()) {
                                    chatInput = ""
                                    scope.launch {
                                        val r = api.postWatchPartyChat(liveParty.id, "chat", txt)
                                        if (r is MusicApiResult.Success) seenIds += r.data.id
                                        (api.fetchWatchParty(liveParty.id) as? MusicApiResult.Success)?.let { party = it.data }
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        // 4e. Post-Credits Lounge Overlay when Movie Ends
        if (videoEnded && liveParty != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB8060910)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .padding(24.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xF00D111D))
                        .border(1.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 28.dp, vertical = 22.dp),
                ) {
                    Text(
                        text = "★ CREDITS ROLLED ★",
                        color = Color(0xFFA5B4FC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = liveParty.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = "Hope everyone enjoyed the show!",
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 13.sp,
                    )
                    Text(
                        text = "Leave Party",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onLeaveParty() }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
            }
        }

        // 5. Full-Viewport Velvet Theater Curtain Unfurl directly over the Video Player
        if (curtainMounted) {
            val openFrac by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (curtainOpen) 1f else 0f,
                animationSpec = androidx.compose.animation.core.tween(
                    durationMillis = 1900,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
                label = "fullscreen_velvet_curtains",
            )
            val velvetPleats = remember {
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFF6B0714),
                        Color(0xFFA81226),
                        Color(0xFF4A030D),
                        Color(0xFF960E20),
                        Color(0xFF590511),
                        Color(0xFFA81226),
                        Color(0xFF6B0714),
                    ),
                )
            }
            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
            ) {
                val halfW = maxWidth * 0.53f
                val slideOffset = halfW * 0.96f * openFrac

                // Left Pleated Velvet Curtain
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(halfW)
                        .fillMaxHeight()
                        .offset(x = -slideOffset)
                        .background(velvetPleats)
                        .border(BorderStroke(2.dp, Color(0xAAF6C978))),
                )

                // Right Pleated Velvet Curtain
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(halfW)
                        .fillMaxHeight()
                        .offset(x = slideOffset)
                        .background(velvetPleats)
                        .border(BorderStroke(2.dp, Color(0xAAF6C978))),
                )

                // Top Crimson & Gold Valance
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(28.dp)
                        .offset(y = (-32f * openFrac).dp)
                        .background(velvetPleats)
                        .border(BorderStroke(2.dp, Color(0xCCF6C978))),
                )

                // Feature Presentation Center Plaque (fades out as curtains sweep open)
                if (openFrac < 0.75f) {
                    val plaqueAlpha = (1f - (openFrac / 0.65f)).coerceIn(0f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .graphicsLayer {
                                alpha = plaqueAlpha
                                scaleX = 1f + openFrac * 0.08f
                                scaleY = 1f + openFrac * 0.08f
                            }
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xD80A060C))
                            .border(1.5.dp, Color(0x99F6C978), RoundedCornerShape(18.dp))
                            .padding(horizontal = 28.dp, vertical = 18.dp),
                    ) {
                        Text(
                            text = "★ FEATURE PRESENTATION ★",
                            color = Color(0xFFF6C978),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                        )
                        Text(
                            text = party?.title ?: movieTitle,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                }
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

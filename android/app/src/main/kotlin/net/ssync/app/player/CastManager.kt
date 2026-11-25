package net.ssync.app.player

import net.ssync.pairing.ui.dpadFocusable

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import coil.compose.AsyncImage
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import kotlinx.coroutines.delay
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

const val SOULSYNC_CAST_APP_ID = "4567F316"

/**
 * Configures Google Cast with SoulSync's branded Styled Media Receiver (`4567F316`),
 * which displays the SoulSync logo + emerald progress bar and natively trusts
 * SoulSync Connect's Let's Encrypt wildcard TLS certificate.
 */
class SoulSyncCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(SOULSYNC_CAST_APP_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastRouteItem(
    val id: String,
    val name: String,
    val description: String?,
    val isSelected: Boolean,
)

@Stable
class SoulSyncCastController(private val appContext: Context) {
    var availableRoutes by mutableStateOf<List<CastRouteItem>>(emptyList())
        private set
    var connectedDeviceName by mutableStateOf<String?>(null)
        private set
    var isCastingMedia by mutableStateOf(false)
        private set
    var remoteIsPlaying by mutableStateOf(false)
        private set
    var castingTitle by mutableStateOf<String?>(null)
        private set
    var castingSubtitle by mutableStateOf<String?>(null)
        private set
    var castingArtworkUrl by mutableStateOf<String?>(null)
        private set
    var remotePositionMs by mutableLongStateOf(0L)
        private set
    var remoteDurationMs by mutableLongStateOf(0L)
        private set
    var remoteVolume by mutableFloatStateOf(0.8f)
        private set
    var remoteMuted by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set

    val isConnected: Boolean
        get() = connectedDeviceName != null

    /** Invoked when Chromecast finishes playing the current media/queue (IDLE_REASON_FINISHED). */
    var onMediaFinished: (() -> Unit)? = null

    /** Invoked when Chromecast natively advances to the next queued item on the TV. */
    var onQueueItemChanged: ((title: String?, key: String?) -> Unit)? = null

    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var activeSession: CastSession? = null
    private var attachedRemote: RemoteMediaClient? = null
    private var isTranscodedCast: Boolean = false
    private var castStartOffsetMs: Long = 0L
    private var lastCastBaseStreamUrl: String? = null
    private var lastCastContentType: String = "video/mp4"
    private var lastCastMetadata: MediaMetadata? = null
    private var lastQueueItemId: Int = MediaQueueItem.INVALID_ITEM_ID
    private var sawActivePlaybackForItem: Boolean = false
    private var finishedFiredForSession: Boolean = false
    private var lastVolumeWriteMs: Long = 0L
    private var lastVolumeRpcMs: Long = 0L

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            handleRemoteStatusUpdate()
        }
    }

    private fun attachRemoteCallback(session: CastSession?) {
        val remote = session?.remoteMediaClient
        if (remote === attachedRemote) return
        runCatching { attachedRemote?.unregisterCallback(remoteCallback) }
        attachedRemote = remote
        runCatching { remote?.registerCallback(remoteCallback) }
    }

    private fun detachRemoteCallback() {
        runCatching { attachedRemote?.unregisterCallback(remoteCallback) }
        attachedRemote = null
    }

    private fun handleRemoteStatusUpdate() {
        val remote = activeSession?.remoteMediaClient ?: return
        val status = remote.mediaStatus ?: return

        // Detect Chromecast native queue advancing to the next program on the TV
        val curItemId = status.currentItemId
        if (curItemId != MediaQueueItem.INVALID_ITEM_ID && curItemId != lastQueueItemId) {
            val wasValid = lastQueueItemId != MediaQueueItem.INVALID_ITEM_ID
            lastQueueItemId = curItemId
            if (wasValid) {
                // Native queue advanced to the next item (which always starts at 0ms)
                castStartOffsetMs = 0L
                finishedFiredForSession = false
                val meta = status.mediaInfo?.metadata
                val nextTitle = meta?.getString(MediaMetadata.KEY_TITLE)
                val nextSub = meta?.getString(MediaMetadata.KEY_SUBTITLE)
                if (!nextTitle.isNullOrBlank()) {
                    castingTitle = nextTitle
                    castingSubtitle = nextSub
                    statusMessage = "Casting \"$nextTitle\" to ${connectedDeviceName ?: "TV"}"
                }
                val nextDur = status.mediaInfo?.streamDuration ?: 0L
                if (nextDur > 0L) remoteDurationMs = nextDur
                val nextKey = runCatching { status.mediaInfo?.customData?.optString("key") }.getOrNull()
                onQueueItemChanged?.invoke(nextTitle, nextKey?.takeIf { it.isNotBlank() })
            }
        }

        // Track that playback actually started for the current item before accepting IDLE_REASON_FINISHED
        if (
            status.playerState == MediaStatus.PLAYER_STATE_PLAYING ||
            status.playerState == MediaStatus.PLAYER_STATE_BUFFERING
        ) {
            sawActivePlaybackForItem = true
            finishedFiredForSession = false
        } else if (
            sawActivePlaybackForItem &&
            status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
            status.idleReason == MediaStatus.IDLE_REASON_FINISHED &&
            !finishedFiredForSession
        ) {
            finishedFiredForSession = true
            sawActivePlaybackForItem = false
            remoteIsPlaying = false
            onMediaFinished?.invoke()
        }
    }

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            statusMessage = "Connecting to Chromecast…"
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            activeSession = session
            attachRemoteCallback(session)
            connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
            statusMessage = "Connected to ${connectedDeviceName}"
            refreshRoutes()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            detachRemoteCallback()
            activeSession = null
            connectedDeviceName = null
            isCastingMedia = false
            statusMessage = "Couldn't connect to Cast device (code $error)"
            refreshRoutes()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachRemoteCallback()
            activeSession = null
            connectedDeviceName = null
            isCastingMedia = false
            remoteIsPlaying = false
            statusMessage = null
            refreshRoutes()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {}

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            activeSession = session
            attachRemoteCallback(session)
            connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
            refreshRoutes()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            activeSession = null
            connectedDeviceName = null
            refreshRoutes()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun startDiscovery() {
        runCatching {
            val ctx = CastContext.getSharedInstance(appContext)
            castContext = ctx
            activeSession = ctx.sessionManager.currentCastSession
            connectedDeviceName = activeSession?.castDevice?.friendlyName
            ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)

            val router = MediaRouter.getInstance(appContext)
            val sel = MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(SOULSYNC_CAST_APP_ID))
                .build()
            mediaRouter = router
            selector = sel
            router.addCallback(sel, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refreshRoutes()
        }
    }

    fun stopDiscovery() {
        runCatching {
            mediaRouter?.removeCallback(routeCallback)
            castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    private fun refreshRoutes() {
        val router = mediaRouter ?: return
        val sel = selector ?: return
        val selectedId = router.selectedRoute.id
        availableRoutes = router.routes
            .filter { !it.isDefaultOrBluetooth && it.matchesSelector(sel) && it.isEnabled }
            .map { route ->
                CastRouteItem(
                    id = route.id,
                    name = route.name,
                    description = route.description,
                    isSelected = route.id == selectedId,
                )
            }
    }

    fun selectRoute(routeId: String) {
        val router = mediaRouter ?: return
        val route = router.routes.firstOrNull { it.id == routeId } ?: return
        statusMessage = "Connecting to ${route.name}…"
        router.selectRoute(route)
    }

    /** the queue appender of the last cast, if it's still adding */
    private var appendJob: kotlinx.coroutines.Job? = null

    fun disconnect() {
        appendJob?.cancel()
        runCatching {
            castContext?.sessionManager?.endCurrentSession(true)
            mediaRouter?.unselect(MediaRouter.UNSELECT_REASON_DISCONNECTED)
        }
        activeSession = null
        connectedDeviceName = null
        isCastingMedia = false
        remoteIsPlaying = false
        statusMessage = null
        refreshRoutes()
    }

    private data class PreparedCastMedia(
        val mediaInfo: MediaInfo,
        val metadata: MediaMetadata,
        val baseStreamUrl: String,
        val contentType: String,
        val transcoded: Boolean,
        val hls: Boolean,
        val durationMs: Long,
        val startOffsetMs: Long,
    )

    private suspend fun prepareCastMedia(
        api: PlayerApiClient,
        baseUrl: String,
        item: ActivePlayable,
        positionMs: Long,
    ): PreparedCastMedia? {
        val tokenRes = api.createCastToken(item.itemType, item.itemId)
        val castInfo = (tokenRes as? MusicApiResult.Success)?.data ?: return null
        val trimmedBase = baseUrl.trimEnd('/')
        val isRawLocalIp = Regex("^https?://(192\\.168\\.|10\\.|172\\.(1[6-9]|2\\d|3[01])\\.|localhost|127\\.0\\.0\\.1)")
            .containsMatchIn(trimmedBase)
        val fullUrl = if (isRawLocalIp && !castInfo.streamUrl.isNullOrBlank()) {
            castInfo.streamUrl!!
        } else {
            trimmedBase + castInfo.streamPath
        }
        val artBase = if (isRawLocalIp) castInfo.artworkBaseUrl?.trimEnd('/') else null
        val resolvedArt = item.artworkUrl?.let { raw ->
            if (artBase != null && raw.startsWith(trimmedBase)) {
                artBase + raw.removePrefix(trimmedBase)
            } else {
                raw
            }
        }
        val mediaType = if (item.isVideo) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        val meta = MediaMetadata(mediaType).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            if (item.subtitle.isNotBlank()) {
                putString(MediaMetadata.KEY_SUBTITLE, item.subtitle)
            }
            resolvedArt?.takeIf { it.startsWith("http") }?.let { art ->
                addImage(WebImage(Uri.parse(art)))
            }
        }
        val initialStartMs = positionMs.coerceAtLeast(0L)
        // VOD HLS and direct-play MP4 both support native Cast seeking & exact duration;
        // only piped fMP4 fallback (transcoded && !hls) needs ?t= server-side offset.
        val needsServerSeek = castInfo.transcoded && !castInfo.hls
        val loadUrl = if (needsServerSeek && initialStartMs > 0L) {
            val sep = if (fullUrl.contains('?')) '&' else '?'
            "$fullUrl${sep}t=${initialStartMs / 1000L}"
        } else {
            fullUrl
        }
        val mediaInfoBuilder = MediaInfo.Builder(loadUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castInfo.contentType)
            .setMetadata(meta)
            // which item this is, so a queue advance on the tv maps back to it
            // exactly (titles repeat: commercials, same-named episodes)
            .setCustomData(org.json.JSONObject().put("key", "${item.itemType}:${item.itemId}"))
        if (castInfo.hls) {
            mediaInfoBuilder.setHlsSegmentFormat(HlsSegmentFormat.TS)
            mediaInfoBuilder.setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
        }
        if (castInfo.durationMs > 0L) {
            mediaInfoBuilder.setStreamDuration(castInfo.durationMs)
        }
        return PreparedCastMedia(
            mediaInfo = mediaInfoBuilder.build(),
            metadata = meta,
            baseStreamUrl = fullUrl,
            contentType = castInfo.contentType,
            transcoded = castInfo.transcoded,
            hls = castInfo.hls,
            durationMs = castInfo.durationMs,
            startOffsetMs = if (needsServerSeek) initialStartMs else 0L,
        )
    }

    suspend fun castItem(
        api: PlayerApiClient,
        baseUrl: String,
        item: ActivePlayable,
        positionMs: Long = 0L,
        upcoming: List<ActivePlayable> = emptyList(),
    ): Boolean {
        appendJob?.cancel()
        val session = activeSession ?: castContext?.sessionManager?.currentCastSession
        val remote = session?.remoteMediaClient
        if (remote == null) {
            statusMessage = "Select a Chromecast first."
            return false
        }
        activeSession = session
        attachRemoteCallback(session)
        statusMessage = "Preparing stream for ${connectedDeviceName ?: "TV"}…"
        val primary = prepareCastMedia(api, baseUrl, item, positionMs)
        if (primary == null) {
            statusMessage = "Couldn't authorize stream for Cast."
            return false
        }

        val initialStartMs = positionMs.coerceAtLeast(0L)
        val needsServerSeek = primary.transcoded && !primary.hls
        isTranscodedCast = needsServerSeek
        castStartOffsetMs = primary.startOffsetMs
        lastCastBaseStreamUrl = primary.baseStreamUrl
        lastCastContentType = primary.contentType
        lastCastMetadata = primary.metadata
        lastQueueItemId = MediaQueueItem.INVALID_ITEM_ID
        sawActivePlaybackForItem = false
        finishedFiredForSession = false

        // When using Direct Play OR VOD HLS (exact duration known by Chromecast), load primary +
        // 1 immediate next item right away (fast start!) and append the remaining 6 items in the
        // background so TV shows "Up Next in 10s" ONLY in the final 10s and plays with phone OFF!
        if (upcoming.isNotEmpty() && !needsServerSeek) {
            val queueItems = mutableListOf<MediaQueueItem>()
            queueItems += MediaQueueItem.Builder(primary.mediaInfo)
                .setAutoplay(true)
                .setPreloadTime(10.0)
                .build()
            val firstNext = prepareCastMedia(api, baseUrl, upcoming.first(), 0L)
            if (firstNext != null) {
                queueItems += MediaQueueItem.Builder(firstNext.mediaInfo)
                    .setAutoplay(true)
                    .setPreloadTime(10.0)
                    .build()
            }
            if (queueItems.size > 1) {
                remote.queueLoad(
                    queueItems.toTypedArray(),
                    0,
                    MediaStatus.REPEAT_MODE_REPEAT_OFF,
                    initialStartMs,
                    null,
                )
            } else {
                val loadReq = MediaLoadRequestData.Builder()
                    .setMediaInfo(primary.mediaInfo)
                    .setAutoplay(true)
                    .setCurrentTime(initialStartMs)
                    .build()
                remote.load(loadReq)
            }
            // Append remaining upcoming shows asynchronously after TV playback has already started
            val rest = upcoming.drop(1).take(6)
            if (rest.isNotEmpty()) {
                // one appender at a time: casting something else (or disconnecting)
                // stops it, or the old show's episodes landed in the new queue
                appendJob?.cancel()
                appendJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    for (next in rest) {
                        val prep = runCatching { prepareCastMedia(api, baseUrl, next, 0L) }.getOrNull() ?: continue
                        val qItem = MediaQueueItem.Builder(prep.mediaInfo)
                            .setAutoplay(true)
                            .setPreloadTime(10.0)
                            .build()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            runCatching { remote.queueAppendItem(qItem, null) }
                        }
                    }
                }
            }
        } else {
            val loadReq = MediaLoadRequestData.Builder()
                .setMediaInfo(primary.mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(if (needsServerSeek) 0L else initialStartMs)
                .build()
            remote.load(loadReq)
        }

        isCastingMedia = true
        remoteIsPlaying = true
        castingTitle = item.title
        castingSubtitle = item.subtitle.takeIf { it.isNotBlank() }
        castingArtworkUrl = item.artworkUrl
        remotePositionMs = initialStartMs
        remoteDurationMs = primary.durationMs
        statusMessage = "Casting \"${item.title}\" to ${connectedDeviceName ?: "TV"}"
        return true
    }

    fun refreshRemoteState() {
        val session = activeSession ?: castContext?.sessionManager?.currentCastSession ?: return
        activeSession = session
        attachRemoteCallback(session)
        val remote = session.remoteMediaClient
        // Avoid snapping the slider back while the user is actively adjusting volume
        if (System.currentTimeMillis() - lastVolumeWriteMs > 1_500L) {
            runCatching {
                val streamVol = remote?.mediaStatus?.streamVolume?.toFloat()
                val devVol = session.volume.toFloat()
                // Prefer stream volume when active media is loaded (works even on fixed-volume HDMI/CEC TVs)
                val effective = if (streamVol != null && streamVol in 0f..1f) streamVol else devVol
                remoteVolume = effective.coerceIn(0f, 1f)
                remoteMuted = (remote?.mediaStatus?.isMute == true) || session.isMute
            }
        }
        if (remote == null) return
        handleRemoteStatusUpdate()
        val hasMedia = remote.hasMediaSession()
        if (hasMedia) {
            isCastingMedia = true
            remoteIsPlaying = remote.isPlaying
            val streamPos = remote.approximateStreamPosition.coerceAtLeast(0L)
            val combined = castStartOffsetMs + streamPos
            remotePositionMs = if (remoteDurationMs > 0L) combined.coerceAtMost(remoteDurationMs) else combined
            // For transcoded fragmented MP4, Chromecast only reports its rolling buffer length
            // (~3s ahead of current pos). Keep the real ffprobe duration from cast-token!
            if (!isTranscodedCast || remoteDurationMs <= 0L) {
                val dur = remote.streamDuration
                if (dur > 0L) remoteDurationMs = dur
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val remote = activeSession?.remoteMediaClient ?: return
        val clamped = positionMs.coerceAtLeast(0L).let {
            if (remoteDurationMs > 0L) it.coerceAtMost(remoteDurationMs) else it
        }
        if (isTranscodedCast) {
            val base = lastCastBaseStreamUrl ?: return
            val sep = if (base.contains('?')) '&' else '?'
            val seekUrl = "$base${sep}t=${clamped / 1000L}"
            val builder = MediaInfo.Builder(seekUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(lastCastContentType)
            lastCastMetadata?.let { builder.setMetadata(it) }
            if (remoteDurationMs > 0L) builder.setStreamDuration(remoteDurationMs)
            castStartOffsetMs = clamped
            remotePositionMs = clamped
            remote.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(builder.build())
                    .setAutoplay(true)
                    .setCurrentTime(0L)
                    .build(),
            )
        } else {
            remote.seek(MediaSeekOptions.Builder().setPosition(clamped).build())
            remotePositionMs = clamped
        }
    }

    fun skipBy(deltaMs: Long) {
        val target = (remotePositionMs + deltaMs).coerceAtLeast(0L)
        val clamped = if (remoteDurationMs > 0L) target.coerceAtMost(remoteDurationMs) else target
        seekTo(clamped)
    }

    fun setVolume(volume: Float, forceImmediate: Boolean = false) {
        val session = activeSession ?: castContext?.sessionManager?.currentCastSession ?: return
        activeSession = session
        val v = volume.coerceIn(0f, 1f)
        val now = System.currentTimeMillis()
        remoteVolume = v
        lastVolumeWriteMs = now
        if (!forceImmediate && now - lastVolumeRpcMs < 110L) return
        lastVolumeRpcMs = now

        val remote = session.remoteMediaClient
        // Set BOTH receiver stream volume (always works on HDMI-CEC / Google TV / Shield with fixed
        // device volume) AND Cast device volume (for Chromecast speakers/dongles with variable volume).
        runCatching { remote?.setStreamVolume(v.toDouble()) }
        runCatching { session.setVolume(v.toDouble()) }
        if (v > 0f && remoteMuted) {
            runCatching { remote?.setStreamMute(false) }
            runCatching { session.setMute(false) }
            remoteMuted = false
        }
    }

    fun toggleMute() {
        val session = activeSession ?: castContext?.sessionManager?.currentCastSession ?: return
        activeSession = session
        val remote = session.remoteMediaClient
        val next = !remoteMuted
        lastVolumeWriteMs = System.currentTimeMillis()
        remoteMuted = next
        runCatching { remote?.setStreamMute(next) }
        runCatching { session.setMute(next) }
    }

    fun toggleRemotePlayPause() {
        val remote = activeSession?.remoteMediaClient ?: return
        if (remote.isPlaying) {
            remote.pause()
            remoteIsPlaying = false
        } else {
            remote.play()
            remoteIsPlaying = true
        }
    }

    fun stopRemote() {
        activeSession?.remoteMediaClient?.stop()
        isCastingMedia = false
        remoteIsPlaying = false
        castingTitle = null
        castingSubtitle = null
        castingArtworkUrl = null
        remotePositionMs = 0L
        remoteDurationMs = 0L
        statusMessage = "Connected to ${connectedDeviceName ?: "TV"}"
    }
}

@Composable
fun rememberCastController(): SoulSyncCastController {
    val context = LocalContext.current
    val controller = remember(context) { SoulSyncCastController(context.applicationContext) }
    DisposableEffect(controller) {
        controller.startDiscovery()
        onDispose { controller.stopDiscovery() }
    }
    LaunchedEffect(controller.isConnected) {
        while (controller.isConnected) {
            controller.refreshRemoteState()
            delay(500L)
        }
    }
    return controller
}

private fun formatCastTime(ms: Long): String {
    val sec = (ms / 1000L).coerceAtLeast(0L)
    val h = sec / 3600L
    val m = (sec % 3600L) / 60L
    val s = sec % 60L
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

@Composable
fun CastMiniBar(
    castController: SoulSyncCastController,
    onExpand: () -> Unit,
) {
    val frac = if (castController.remoteDurationMs > 0L) {
        (castController.remotePositionMs.toFloat() / castController.remoteDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        color = SoulSyncColors.CardRaised,
        border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
    ) {
        Column {
            if (frac > 0f) {
                LinearProgressIndicator(
                    progress = { frac },
                    color = SoulSyncColors.AccentLight,
                    trackColor = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().height(2.5.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SoulSyncColors.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!castController.castingArtworkUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = castController.castingArtworkUrl,
                            imageLoader = LocalSoulSyncImageLoader.current,
                            contentDescription = castController.castingTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Default.CastConnected, contentDescription = null, tint = SoulSyncColors.AccentLight)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CASTING TO ${(castController.connectedDeviceName ?: "TV").uppercase()}",
                        color = SoulSyncColors.AccentLight,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = castController.castingTitle ?: "Playing on TV",
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { castController.toggleRemotePlayPause() }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                    Icon(
                        imageVector = if (castController.remoteIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (castController.remoteIsPlaying) "Pause TV" else "Play TV",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { castController.stopRemote() }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.CircleShape),) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop casting",
                        tint = SoulSyncColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    castController: SoulSyncCastController,
    api: PlayerApiClient,
    baseUrl: String,
    activeItem: ActivePlayable?,
    currentPositionMs: Long,
    upcoming: List<ActivePlayable> = emptyList(),
    onPlayNext: (() -> Unit)? = null,
    onCastStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SoulSyncColors.Surface,
        contentColor = SoulSyncColors.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = if (castController.isConnected) Icons.Default.CastConnected else Icons.Default.Cast,
                    contentDescription = null,
                    tint = if (castController.isConnected) SoulSyncColors.AccentLight else Color.White,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (castController.isConnected) {
                            "Connected to ${castController.connectedDeviceName}"
                        } else {
                            "Cast to a Device"
                        },
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    castController.statusMessage?.let { msg ->
                        Text(msg, color = SoulSyncColors.AccentLight, fontSize = 12.sp)
                    }
                }
            }

            if (castController.isConnected) {
                HorizontalDivider(color = SoulSyncColors.Line)
                if (activeItem != null) {
                    Button(
                        onClick = {
                            if (sending) return@Button
                            sending = true
                            scope.launch {
                                val ok = castController.castItem(api, baseUrl, activeItem, currentPositionMs, upcoming)
                                sending = false
                                if (ok) onCastStarted()
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoulSyncColors.Accent,
                            contentColor = SoulSyncColors.OnAccent,
                        ),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SoulSyncColors.OnAccent)
                        } else {
                            Icon(Icons.Default.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (sending) "Starting Cast…" else "Cast \"${activeItem.title}\"",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                if (castController.isCastingMedia) {
                    // ── Best-in-Class On-Screen Cast Remote ──
                    var scrubbing by remember { mutableStateOf(false) }
                    var scrubPosMs by remember { mutableLongStateOf(0L) }
                    val durMs = castController.remoteDurationMs.coerceAtLeast(1L)
                    val shownPosMs = if (scrubbing) scrubPosMs else castController.remotePositionMs

                    // Hero artwork + title
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SoulSyncColors.CardRaised)
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                    ) {
                        if (!castController.castingArtworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = castController.castingArtworkUrl,
                                imageLoader = LocalSoulSyncImageLoader.current,
                                contentDescription = castController.castingTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0x44070A10), Color(0x99070A10), Color(0xEE070A10)),
                                    ),
                                ),
                        )
                        Surface(
                            shape = CircleShape,
                            color = Color(0xBA070A10),
                            border = BorderStroke(0.75.dp, SoulSyncColors.Accent.copy(alpha = 0.5f)),
                            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                        ) {
                            Text(
                                text = "● ON ${(castController.connectedDeviceName ?: "TV").uppercase()}",
                                color = SoulSyncColors.AccentLight,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                        ) {
                            Text(
                                text = castController.castingTitle ?: "Playing on TV",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            castController.castingSubtitle?.let { sub ->
                                Text(
                                    text = sub,
                                    color = SoulSyncColors.TextSoft,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Live Timeline Scrubber
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = (shownPosMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { frac ->
                                scrubbing = true
                                scrubPosMs = (frac * durMs).toLong()
                            },
                            onValueChangeFinished = {
                                castController.seekTo(scrubPosMs)
                                scrubbing = false
                            },
                            enabled = castController.remoteDurationMs > 0L,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = SoulSyncColors.AccentLight,
                                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(formatCastTime(shownPosMs), color = SoulSyncColors.TextSecondary, fontSize = 11.5.sp)
                            Text(
                                text = if (castController.remoteDurationMs > 0L) formatCastTime(castController.remoteDurationMs) else "Live",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                            )
                        }
                    }

                    // Transport Controls: -10s · Play/Pause · +30s
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { castController.skipBy(-10_000L) },
                            modifier = Modifier.size(48.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }

                        FilledIconButton(
                            onClick = { castController.toggleRemotePlayPause() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = SoulSyncColors.Accent,
                                contentColor = SoulSyncColors.OnAccent,
                            ),
                            modifier = Modifier.size(62.dp),
                        ) {
                            Icon(
                                imageVector = if (castController.remoteIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (castController.remoteIsPlaying) "Pause TV" else "Play TV",
                                modifier = Modifier.size(34.dp),
                            )
                        }

                        IconButton(
                            onClick = { castController.skipBy(30_000L) },
                            modifier = Modifier.size(48.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
                        ) {
                            Icon(Icons.Default.Forward30, contentDescription = "Forward 30s", tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                    }

                    // Hardware TV Volume Slider + Mute
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = { castController.toggleMute() }, modifier = Modifier.size(36.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape)) {
                            Icon(
                                imageVector = if (castController.remoteMuted || castController.remoteVolume <= 0.01f) {
                                    Icons.Default.VolumeOff
                                } else {
                                    Icons.Default.VolumeUp
                                },
                                contentDescription = "Mute TV",
                                tint = if (castController.remoteMuted) SoulSyncColors.TextSecondary else Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Slider(
                            value = if (castController.remoteMuted) 0f else castController.remoteVolume,
                            onValueChange = { castController.setVolume(it) },
                            onValueChangeFinished = { castController.setVolume(castController.remoteVolume, forceImmediate = true) },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${(if (castController.remoteMuted) 0 else (castController.remoteVolume * 100).toInt())}%",
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(34.dp),
                        )
                    }

                    // Truthful "Up Next" card with live countdown in the final 20 seconds (zero receiver loop bug!)
                    val nextUp = upcoming.firstOrNull()
                    if (nextUp != null) {
                        val remSec = if (castController.remoteDurationMs > 0L) {
                            ((castController.remoteDurationMs - castController.remotePositionMs) / 1000L).coerceAtLeast(0L)
                        } else -1L
                        val inFinalCountdown = remSec in 1L..20L
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (inFinalCountdown) SoulSyncColors.Accent.copy(alpha = 0.16f)
                                    else Color.White.copy(alpha = 0.05f),
                                )
                                .border(
                                    1.dp,
                                    if (inFinalCountdown) SoulSyncColors.Accent else SoulSyncColors.Line,
                                    RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (inFinalCountdown) "UP NEXT IN ${remSec}s" else "UP NEXT",
                                    color = if (inFinalCountdown) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.7.sp,
                                )
                                Text(
                                    text = nextUp.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (onPlayNext != null) {
                                Button(
                                    onClick = onPlayNext,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (inFinalCountdown) SoulSyncColors.Accent else Color.White.copy(alpha = 0.14f),
                                        contentColor = if (inFinalCountdown) SoulSyncColors.OnAccent else Color.White,
                                    ),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                                ) {
                                    Text(
                                        text = if (inFinalCountdown) "Play Now" else "Skip",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    var showCastReport by remember { mutableStateOf(false) }
                    if (activeItem != null) {
                        OutlinedButton(
                            onClick = { showCastReport = true },
                            modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                            border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                        ) {
                            Icon(Icons.Default.Flag, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Report Issue with Current Media", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        if (showCastReport) {
                            ReportIssueDialog(
                                api = api,
                                itemType = activeItem.itemType,
                                itemId = activeItem.itemId,
                                title = activeItem.title,
                                subtitle = activeItem.subtitle,
                                positionSec = (currentPositionMs / 1000L).toInt(),
                                onDismiss = { showCastReport = false },
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = { castController.stopRemote() },
                        modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop Casting Media", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                OutlinedButton(
                    onClick = { castController.disconnect() },
                    modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                    border = BorderStroke(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.5f)),
                ) {
                    Text("Disconnect", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
                }
            } else {
                HorizontalDivider(color = SoulSyncColors.Line)
                val routes = castController.availableRoutes
                if (routes.isEmpty()) {
                    Text(
                        text = "Searching for Chromecast & Google TV devices on your Wi-Fi…",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 13.sp,
                    )
                } else {
                    routes.forEach { route ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
                                .clickable { castController.selectRoute(route.id) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(SoulSyncColors.Accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Tv, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(route.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                route.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
                },
                modifier = Modifier.fillMaxWidth().dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Text("System Cast / Screen Mirroring", color = SoulSyncColors.TextSoft, fontSize = 13.sp)
            }
        }
    }
}

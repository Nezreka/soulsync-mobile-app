package net.ssync.app.player

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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

/**
 * Configures Google Cast with the built-in Default Media Receiver (`CC1AD845`),
 * which natively trusts SoulSync Connect's Let's Encrypt wildcard TLS certificate.
 */
class SoulSyncCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
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
    var statusMessage by mutableStateOf<String?>(null)
        private set

    val isConnected: Boolean
        get() = connectedDeviceName != null

    private var castContext: CastContext? = null
    private var mediaRouter: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var activeSession: CastSession? = null

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
            connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
            statusMessage = "Connected to ${connectedDeviceName}"
            refreshRoutes()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            activeSession = null
            connectedDeviceName = null
            isCastingMedia = false
            statusMessage = "Couldn't connect to Cast device (code $error)"
            refreshRoutes()
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
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
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                    ),
                )
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

    fun disconnect() {
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

    suspend fun castItem(
        api: PlayerApiClient,
        baseUrl: String,
        item: ActivePlayable,
        positionMs: Long = 0L,
    ): Boolean {
        val session = activeSession ?: castContext?.sessionManager?.currentCastSession
        val remote = session?.remoteMediaClient
        if (remote == null) {
            statusMessage = "Select a Chromecast first."
            return false
        }
        statusMessage = "Preparing stream for ${connectedDeviceName ?: "TV"}…"
        val tokenRes = api.createCastToken(item.itemType, item.itemId)
        val castInfo = when (tokenRes) {
            is MusicApiResult.Success -> tokenRes.data
            else -> {
                statusMessage = "Couldn't authorize stream for Cast."
                return false
            }
        }
        val fullUrl = baseUrl.trimEnd('/') + castInfo.streamPath
        val mediaType = if (item.isVideo) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_MUSIC_TRACK
        val meta = MediaMetadata(mediaType).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            if (item.subtitle.isNotBlank()) {
                putString(MediaMetadata.KEY_SUBTITLE, item.subtitle)
            }
            item.artworkUrl?.takeIf { it.startsWith("http") }?.let { art ->
                addImage(WebImage(Uri.parse(art)))
            }
        }
        val mediaInfo = MediaInfo.Builder(fullUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castInfo.contentType)
            .setMetadata(meta)
            .build()
        val loadReq = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .setCurrentTime(positionMs.coerceAtLeast(0L))
            .build()
        remote.load(loadReq)
        isCastingMedia = true
        remoteIsPlaying = true
        statusMessage = "Casting \"${item.title}\" to ${connectedDeviceName ?: "TV"}"
        return true
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
    return controller
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastDeviceSheet(
    castController: SoulSyncCastController,
    api: PlayerApiClient,
    baseUrl: String,
    activeItem: ActivePlayable?,
    currentPositionMs: Long,
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
                                val ok = castController.castItem(api, baseUrl, activeItem, currentPositionMs)
                                sending = false
                                if (ok) onCastStarted()
                            }
                        },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { castController.toggleRemotePlayPause() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                        ) {
                            Icon(
                                imageVector = if (castController.remoteIsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (castController.remoteIsPlaying) "Pause TV" else "Resume TV", color = Color.White)
                        }
                        OutlinedButton(
                            onClick = { castController.stopRemote() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stop", color = Color.White)
                        }
                    }
                }

                OutlinedButton(
                    onClick = { castController.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Text("System Cast / Screen Mirroring", color = SoulSyncColors.TextSoft, fontSize = 13.sp)
            }
        }
    }
}

package net.ssync.app.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.DownloadTask
import net.ssync.player.MusicApiResult
import net.ssync.player.OFFLINE_VIDEO_KINDS
import net.ssync.player.OfflineApiClient
import net.ssync.player.OfflineDevice
import net.ssync.player.OfflineFormat
import net.ssync.player.OfflineMigration
import net.ssync.player.OfflinePendingOp
import net.ssync.player.OfflinePlan
import net.ssync.player.OfflineRule
import net.ssync.player.OfflineStorage
import net.ssync.player.PlayerApiClient
import net.ssync.player.TaskState
import java.io.File

// ── colours shared by the downloads screens (the approved mockup's palette) ──

internal object DlColors {
    val Raise = Color(0xFF14141C)
    val Raise2 = Color(0xFF1C1C27)
    val Line = Color(0x12FFFFFF)
    val Line2 = Color(0x24FFFFFF)
    val Muted = Color(0xFF8F92A3)
    val Warn = Color(0xFFFFB547)
    val Smart = Color(0xFF9DB3FF)
    val SmartBg = Color(0x297C9CFF)
    val Bad = Color(0xFFFDA4AF)
    val Done = Color(0xFF6EE7B7)
}

/** what a download sheet is about: a group the plan route knows, or one item */
data class DownloadTarget(
    /** movie, episode, season, show, youtube, track, album, artist, playlist, audiobook, podcast, podcast_episode */
    val type: String,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artwork: String? = null,
    /** where its items sit on the Downloads screen: a show for episodes, an album for tracks */
    val groupType: String = "",
    val groupId: String = "",
    val groupTitle: String = "",
    val parentId: String? = null,
    /** an episode's season * 1000 + number, so a show's downloads list in order */
    val sortIndex: Int = 0,
)

internal val GROUP_TYPES = setOf("season", "show", "album", "artist", "playlist", "podcast")

private fun needsNotificationAsk(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** asks once for the progress notification, the first time something downloads */
@Composable
internal fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context) {
        {
            val sp = context.getSharedPreferences("soulsync_offline_prefs", Context.MODE_PRIVATE)
            if (needsNotificationAsk(context) && !sp.getBoolean("asked_notifications", false)) {
                sp.edit().putBoolean("asked_notifications", true).apply()
                runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
    }
}

/** stored artwork paths are the server's, never a full url with today's address */
internal fun relativeArt(url: String?): String? = OfflineMigration.relativeArtwork(url)

/** the picture for a download: the saved copy first, so it shows offline */
internal fun artModel(api: PlayerApiClient, t: DownloadTask): String? =
    t.localArtwork.takeIf { it.isNotBlank() && File(it).exists() }?.let { android.net.Uri.fromFile(File(it)).toString() }
        ?: api.artwork.resolve(t.groupArtwork ?: t.artwork)

/**
 * the download button for one movie, episode, track, video, audiobook or
 * podcast episode. it shows where the download is (download, waiting,
 * preparing, 60%, downloaded, retry). on a tv it sends the item to a phone
 * instead: a tv keeps no downloads.
 */
@Composable
fun OfflineDownloadButton(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    title: String,
    subtitle: String = "",
    artworkUrl: String? = null,
    @Suppress("UNUSED_PARAMETER") streamUrl: String = "",
    isVideo: Boolean = false,
    parentId: String? = null,
    compact: Boolean = false,
    groupType: String = "",
    groupId: String = "",
    groupTitle: String = "",
    sortIndex: Int = 0,
) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    LaunchedEffect(context) { OfflineDownloads.init(context) }
    val tasks by OfflineDownloads.tasks.collectAsState()
    val live by OfflineDownloads.live.collectAsState()
    val settings by OfflineDownloads.settings.collectAsState()
    val key = net.ssync.player.offlineKey(itemType, itemId)
    val task = tasks.firstOrNull { it.key == key }
    var sheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val askNotify = rememberNotificationAsk()
    val target = DownloadTarget(
        type = itemType, id = itemId, title = title, subtitle = subtitle, artwork = relativeArt(artworkUrl),
        groupType = groupType.ifBlank {
            when {
                itemType == "episode" && parentId != null -> "show"
                itemType == "track" && parentId != null -> "album"
                else -> ""
            }
        },
        groupId = groupId.ifBlank { if (itemType == "episode" || itemType == "track") parentId.orEmpty() else "" },
        groupTitle = groupTitle,
        parentId = parentId,
        sortIndex = sortIndex,
    )

    val onTap: () -> Unit = {
        when {
            isTv -> sheet = true
            task != null -> menu = true
            // a song goes straight in at the remembered quality; video asks (sizes matter)
            !isVideo && itemType !in OFFLINE_VIDEO_KINDS && settings.audioQuality.isNotBlank() -> {
                askNotify()
                OfflineDownloads.enqueue(
                    context,
                    listOf(
                        OfflineRequest(
                            itemType = itemType, itemId = itemId, title = title, subtitle = subtitle,
                            artwork = target.artwork, isVideo = false, quality = settings.audioQuality,
                            parentId = parentId, groupType = target.groupType, groupId = target.groupId, groupTitle = groupTitle,
                            sortIndex = sortIndex,
                        ),
                    ),
                )
            }
            else -> sheet = true
        }
    }

    val progress = task?.let { t -> live[t.key]?.let { l -> if (l.totalBytes > 0) l.bytesDone.toDouble() / l.totalBytes else t.progress } ?: t.progress }
    val look = buttonLook(task, progress, isTv)

    if (compact) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .dpadFocusable(CircleShape)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            if (task != null && task.state == TaskState.DOWNLOADING) {
                CircularProgressIndicator(
                    progress = { (progress ?: 0.0).toFloat() },
                    color = SoulSyncColors.AccentLight,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
                Icon(Icons.Default.Pause, contentDescription = look.label, tint = Color.White, modifier = Modifier.size(11.dp))
            } else if (task != null && (task.state == TaskState.QUEUED || task.state == TaskState.PREPARING)) {
                CircularProgressIndicator(
                    color = SoulSyncColors.AccentLight.copy(alpha = 0.6f),
                    trackColor = Color.White.copy(alpha = 0.08f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(look.icon, contentDescription = look.label, tint = look.tint, modifier = Modifier.size(18.dp))
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = look.bg,
            border = BorderStroke(1.dp, look.border),
            modifier = Modifier.height(34.dp).dpadFocusable(CircleShape).clip(CircleShape).clickable(onClick = onTap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (task?.state == TaskState.DOWNLOADING) {
                    CircularProgressIndicator(
                        progress = { (progress ?: 0.0).toFloat() },
                        color = SoulSyncColors.AccentLight,
                        trackColor = Color.White.copy(alpha = 0.14f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(16.dp))
                }
                Text(look.label, color = look.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }

    if (sheet) DownloadSheet(api = api, target = target, sendMode = isTv, onDismiss = { sheet = false })
    if (menu && task != null) DownloadItemMenu(api = api, task = task, onDismiss = { menu = false })
}

private data class ButtonLook(
    val icon: ImageVector,
    val label: String,
    val tint: Color,
    val text: Color = Color.White,
    val bg: Color = Color.White.copy(alpha = 0.08f),
    val border: Color = SoulSyncColors.Line,
)

private fun buttonLook(task: DownloadTask?, progress: Double?, isTv: Boolean): ButtonLook {
    if (isTv) return ButtonLook(Icons.Default.SendToMobile, "Send to a phone", Color.White)
    return when (task?.state) {
        null -> ButtonLook(Icons.Default.Download, "Download", Color.White)
        TaskState.DOWNLOADED -> ButtonLook(
            Icons.Default.DownloadDone, "Downloaded", DlColors.Done, DlColors.Done,
            Color(0xFF10B981).copy(alpha = 0.16f), Color(0xFF10B981).copy(alpha = 0.45f),
        )
        TaskState.DOWNLOADING -> ButtonLook(Icons.Default.Download, "${((progress ?: 0.0) * 100).toInt()}%", SoulSyncColors.AccentLight)
        TaskState.PREPARING -> ButtonLook(Icons.Default.Schedule, if (task.convertProgress > 0) "Preparing ${(task.convertProgress * 100).toInt()}%" else "Preparing", SoulSyncColors.AccentLight)
        TaskState.QUEUED -> ButtonLook(Icons.Default.Schedule, "Waiting", DlColors.Muted, DlColors.Muted)
        TaskState.PAUSED -> ButtonLook(Icons.Default.Pause, "Paused", DlColors.Muted, DlColors.Muted)
        TaskState.FAILED -> ButtonLook(Icons.Default.Refresh, "Retry", DlColors.Bad, DlColors.Bad)
    }
}

/**
 * the download button for a whole season, show, album, artist, playlist,
 * audiobook or podcast. its label says how much of it is on the phone.
 */
@Composable
fun GroupDownloadButton(
    api: PlayerApiClient,
    target: DownloadTarget,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isTv = LocalIsTv.current
    LaunchedEffect(context) { OfflineDownloads.init(context) }
    val tasks by OfflineDownloads.tasks.collectAsState()
    val mine = remember(tasks, target) {
        tasks.filter { if (target.type in GROUP_TYPES) inGroup(it, target.type, target.id) else it.key == net.ssync.player.offlineKey(target.type, target.id) }
    }
    var sheet by remember { mutableStateOf(false) }
    val done = mine.count { it.state == TaskState.DOWNLOADED }
    val active = mine.filter { it.state.isActive }
    val noun = when (target.type) {
        "season" -> "season"
        "show" -> "show"
        "album" -> "album"
        "artist" -> "albums"
        "playlist" -> "playlist"
        "podcast" -> "episodes"
        "audiobook" -> "book"
        else -> ""
    }
    val label = when {
        isTv -> "Send to a phone"
        active.isNotEmpty() -> {
            val bytes = active.sumOf { maxOf(it.totalBytes, it.expectedBytes) }
            val doneBytes = active.sumOf { it.bytesDone }
            val pct = if (bytes > 0) (doneBytes * 100 / bytes).toInt() else 0
            "Downloading ${active.size} · $pct%"
        }
        done > 0 && target.type == "audiobook" -> "Downloaded"
        done > 0 -> "$done on this phone"
        else -> if (noun.isBlank()) "Download" else "Download $noun"
    }
    val onPhone = !isTv && done > 0 && active.isEmpty()
    Surface(
        shape = CircleShape,
        color = if (onPhone) Color(0xFF10B981).copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, if (onPhone) Color(0xFF10B981).copy(alpha = 0.45f) else SoulSyncColors.Line),
        modifier = modifier.height(36.dp).dpadFocusable(CircleShape).clip(CircleShape).clickable { sheet = true },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Icon(
                imageVector = when {
                    isTv -> Icons.Default.SendToMobile
                    onPhone -> Icons.Default.DownloadDone
                    else -> Icons.Default.Download
                },
                contentDescription = null,
                tint = if (onPhone) DlColors.Done else Color.White,
                modifier = Modifier.size(16.dp),
            )
            Text(label, color = if (onPhone) DlColors.Done else Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (sheet) DownloadSheet(api = api, target = target, sendMode = isTv, onDismiss = { sheet = false })
}

/** what can be done with one download: play, pause, resume, retry, move up, remove */
@Composable
fun DownloadItemMenu(
    api: PlayerApiClient,
    task: DownloadTask,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val tasks by OfflineDownloads.tasks.collectAsState()
    val t = tasks.firstOrNull { it.key == task.key } ?: task
    AdaptiveTvSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF131520)) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoulSyncArtwork(
                    url = artModel(api, t),
                    contentDescription = t.title,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(t, null), color = DlColors.Muted, fontSize = 12.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (t.state == TaskState.DOWNLOADED && onPlay != null) {
                MenuRow(Icons.Default.PlayArrow, "Play", SoulSyncColors.AccentLight) { onDismiss(); onPlay() }
            }
            if (t.state.isActive) {
                MenuRow(Icons.Default.Pause, "Pause") { OfflineDownloads.pause(t.key); onDismiss() }
                MenuRow(Icons.Default.VerticalAlignTop, "Download this next") { OfflineDownloads.moveToTop(t.key); onDismiss() }
            }
            if (t.state.isPending) {
                MenuRow(Icons.Default.KeyboardArrowUp, "Move up") { OfflineDownloads.moveUp(t.key) }
                MenuRow(Icons.Default.KeyboardArrowDown, "Move down") { OfflineDownloads.moveDown(t.key) }
            }
            if (t.state == TaskState.PAUSED) {
                MenuRow(Icons.Default.PlayArrow, "Resume", SoulSyncColors.AccentLight) { OfflineDownloads.resume(context, t.key); onDismiss() }
            }
            if (t.state == TaskState.FAILED) {
                MenuRow(Icons.Default.Refresh, "Try again", SoulSyncColors.AccentLight) { OfflineDownloads.resume(context, t.key); onDismiss() }
            }
            MenuRow(
                if (t.state == TaskState.DOWNLOADED) Icons.Default.DeleteOutline else Icons.Default.Close,
                if (t.state == TaskState.DOWNLOADED) "Remove from this phone" else "Cancel download",
                DlColors.Bad,
            ) {
                OfflineDownloads.remove(listOf(t.key))
                onDismiss()
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color = Color.White.copy(alpha = 0.9f), onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
    }
}

private fun sheetHeading(type: String, send: Boolean): String {
    val what = when (type) {
        "season" -> "a season"
        "show" -> "a show"
        "album" -> "an album"
        "artist" -> "an artist"
        "playlist" -> "a playlist"
        "podcast" -> "a podcast"
        "audiobook" -> "an audiobook"
        "movie" -> "a movie"
        "episode" -> "an episode"
        "track" -> "a song"
        else -> "this"
    }
    return if (send) "Send $what to a phone" else "Download $what"
}

/**
 * the quality sheet: real sizes from /plan, whether it fits, whole groups in
 * one go and smart downloads. on a tv it asks which phone to send it to.
 */
@Composable
fun DownloadSheet(
    api: PlayerApiClient,
    target: DownloadTarget,
    sendMode: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offline = remember(api) { OfflineApiClient(api.baseUrl, api.pinnedAuthenticatedClient) }
    val myId = remember(context) { OfflineDownloads.deviceId(context) }
    val tasks by OfflineDownloads.tasks.collectAsState()
    val settings by OfflineDownloads.settings.collectAsState()
    val rules by OfflineDownloads.rules.collectAsState()
    val askNotify = rememberNotificationAsk()

    var devices by remember { mutableStateOf<List<OfflineDevice>>(emptyList()) }
    var deviceId by remember { mutableStateOf<String?>(if (sendMode) null else myId) }
    var plan by remember { mutableStateOf<OfflinePlan?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var quality by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var doneMessage by remember { mutableStateOf<String?>(null) }

    val ruleType = when (target.type) {
        "season", "show" -> "show"
        "playlist" -> "playlist"
        "podcast" -> "podcast"
        else -> null
    }
    val ruleId = if (target.type == "season") target.id.substringBefore(':') else target.id
    val existingRule = remember(rules, devices, deviceId, plan) {
        // the plan says the device's rule when the server knows it; else what this phone last heard
        plan?.rule?.takeIf { it.type == ruleType }
            ?: (if (sendMode) devices.firstOrNull { it.deviceId == deviceId }?.rules.orEmpty() else rules)
                .firstOrNull { it.type == ruleType && it.id == ruleId }
    }
    var keepOn by remember(existingRule) { mutableStateOf(existingRule?.keepNext != null && existingRule.keepNext!! > 0) }
    var keepNext by remember(existingRule) { mutableStateOf(existingRule?.keepNext?.takeIf { it > 0 } ?: if (ruleType == "podcast") 5 else 3) }
    var deleteWatched by remember(existingRule) { mutableStateOf(existingRule?.deleteWatched ?: false) }
    var follow by remember(existingRule) { mutableStateOf(existingRule?.follow ?: false) }

    // the phones of this profile, for the tv's "send to"
    LaunchedEffect(sendMode) {
        if (!sendMode) return@LaunchedEffect
        when (val r = offline.devices()) {
            is MusicApiResult.Success -> {
                devices = r.data.filter { it.deviceId != myId && it.platform != "web" }
                if (deviceId == null) deviceId = devices.firstOrNull()?.deviceId
                if (devices.isEmpty()) loadError = "No phones yet. Open SoulSync on a phone or tablet signed in to this profile, and it shows up here."
            }
            is MusicApiResult.Error -> loadError = r.message
            MusicApiResult.DeviceRevoked -> loadError = "This device was signed out."
        }
    }
    LaunchedEffect(target, deviceId) {
        val dev = deviceId ?: return@LaunchedEffect
        plan = null
        when (val r = offline.plan(target.type, target.id, dev)) {
            is MusicApiResult.Success -> {
                plan = r.data
                loadError = null
                val remembered = if (r.data.isVideo) settings.videoQuality else settings.audioQuality
                val ids = r.data.qualities.map { it.id }
                quality = when {
                    existingRule != null && existingRule.quality in ids -> existingRule.quality
                    remembered in ids -> remembered
                    r.data.isVideo && "phone" in ids -> "phone"
                    else -> ids.firstOrNull().orEmpty()
                }
            }
            is MusicApiResult.Error -> loadError = r.message
            MusicApiResult.DeviceRevoked -> loadError = "This device was signed out."
        }
    }

    val p = plan
    val localKeys = remember(tasks) { tasks.map { it.key }.toSet() }
    val missing = p?.items.orEmpty().filter { !it.onDevice && (sendMode || it.key !in localKeys) }
    val q = p?.qualities?.firstOrNull { it.id == quality }
    fun bytesFor(qid: String): Long {
        val qq = p?.qualities?.firstOrNull { it.id == qid } ?: return 0L
        if (missing.isEmpty()) return 0L
        if (qq.perItemBytes.isEmpty()) return if (missing.size == p.items.size) qq.totalBytes else 0L
        return missing.sumOf { qq.perItemBytes[it.key] ?: if (qid == "original") it.sizeBytes else 0L }
    }
    val need = if (q != null) bytesFor(q.id) else 0L
    val dev = devices.firstOrNull { it.deviceId == deviceId }
    val used = if (sendMode) dev?.usedBytes ?: 0L else tasks.sumOf { it.diskBytes } + tasks.sumOf { it.remainingBytes }
    val limit = if (sendMode) (p?.limitBytes?.takeIf { it > 0 } ?: dev?.limitBytes ?: 0L) else settings.limitBytes
    val free = if (sendMode) (p?.freeBytes?.takeIf { it > 0 } ?: dev?.freeBytes ?: 0L) else OfflineDownloads.freeBytes()
    val fit = OfflineStorage.check(need, used, limit, free)
    val video = p?.isVideo ?: (target.type in OFFLINE_VIDEO_KINDS || target.type in setOf("season", "show"))

    AdaptiveTvSheet(onDismissRequest = onDismiss, containerColor = DlColors.Raise, panelWidth = 480.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoulSyncArtwork(
                    url = api.artwork.resolve(p?.artwork ?: target.artwork),
                    contentDescription = target.title,
                    modifier = Modifier.size(if (video) 46.dp else 56.dp, 56.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(sheetHeading(target.type, sendMode), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(p?.title?.ifBlank { null } ?: target.title, (p?.subtitle ?: target.subtitle).ifBlank { null }).joinToString(" · "),
                        color = DlColors.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (p != null && p.items.size > 1) {
                        val n = p.items.size
                        Text(
                            "$n ${groupNoun(target.type, n, video)}" + if (missing.size in 1 until n) " · ${missing.size} not downloaded" else if (missing.isEmpty()) " · all on ${if (sendMode) "the phone" else "this phone"}" else "",
                            color = DlColors.Muted, fontSize = 12.sp,
                        )
                    }
                }
            }

            if (doneMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SoulSyncColors.Accent.copy(alpha = 0.12f))
                        .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(20.dp))
                    Text(doneMessage!!, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
                SheetButton("Done", primary = true) { onDismiss() }
                return@Column
            }

            // which phone (tv only)
            if (sendMode && devices.isNotEmpty()) {
                Text("SEND TO", color = DlColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                devices.forEach { d ->
                    val on = d.deviceId == deviceId
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) SoulSyncColors.Accent else DlColors.Line, RoundedCornerShape(12.dp))
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.07f) else Color.Transparent)
                            .dpadFocusable(RoundedCornerShape(12.dp))
                            .clickable { deviceId = d.deviceId }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = if (on) SoulSyncColors.AccentLight else DlColors.Muted)
                        Column(Modifier.weight(1f)) {
                            Text(d.deviceName.ifBlank { "Phone" }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    if (d.limitBytes > 0) "${OfflineFormat.bytes(d.usedBytes)} of ${OfflineFormat.bytes(d.limitBytes)}" else "${OfflineFormat.bytes(d.usedBytes)} used",
                                    d.lastSeen?.let { "seen ${seenAgo(it)}" },
                                ).joinToString(" · "),
                                color = DlColors.Muted, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            if (loadError != null && p == null) {
                Text(loadError!!, color = DlColors.Bad, fontSize = 13.sp)
            } else if (p == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SoulSyncColors.AccentLight, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                }
            } else if (p.items.isEmpty()) {
                Text("Nothing here can be downloaded yet: the server has no file for it.", color = DlColors.Muted, fontSize = 13.sp)
            } else {
                // qualities with real sizes
                p.qualities.forEach { qq ->
                    val on = qq.id == quality
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) SoulSyncColors.Accent else DlColors.Line, RoundedCornerShape(12.dp))
                            .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.07f) else Color.Transparent)
                            .dpadFocusable(RoundedCornerShape(12.dp))
                            .clickable { quality = qq.id }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            Modifier.size(18.dp).clip(CircleShape)
                                .border(if (on) 5.dp else 2.dp, if (on) SoulSyncColors.Accent else DlColors.Line2, CircleShape),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(qq.label.ifBlank { OfflineFormat.qualityTag(qq.id) }, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            if (qq.sub.isNotBlank()) Text(qq.sub, color = DlColors.Muted, fontSize = 12.sp)
                        }
                        val b = bytesFor(qq.id)
                        Text(
                            if (missing.isEmpty()) "" else if (b > 0) OfflineFormat.bytes(b) else "",
                            color = DlColors.Muted, fontSize = 12.5.sp,
                        )
                    }
                }

                if (p.conversionOff) {
                    Text("Only the original is offered: smaller copies are turned off for this profile.", color = DlColors.Muted, fontSize = 12.sp)
                }

                // smart downloads
                if (ruleType == "show" || ruleType == "podcast") {
                    SheetToggle(
                        if (ruleType == "podcast") "Keep the newest $keepNext episodes ready" else "Keep the next $keepNext unwatched ready",
                        keepOn,
                    ) { keepOn = it }
                    if (keepOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 2.dp)) {
                            listOf(1, 3, 5).forEach { n ->
                                val on = n == keepNext
                                Box(
                                    Modifier.clip(CircleShape)
                                        .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.15f) else DlColors.Raise2)
                                        .dpadFocusable(CircleShape)
                                        .clickable { keepNext = n }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text("$n", color = if (on) SoulSyncColors.AccentLight else DlColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    SheetToggle(
                        if (ruleType == "podcast") "Delete an episode once it's played" else "Delete an episode once it's watched",
                        deleteWatched,
                    ) { deleteWatched = it }
                }
                if (ruleType == "playlist") {
                    SheetToggle("Keep in step as the playlist changes", follow) { follow = it }
                }
                if (video) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Subtitles, contentDescription = null, tint = DlColors.Muted, modifier = Modifier.size(16.dp))
                        Text("Subtitles and chapter marks come along", color = DlColors.Muted, fontSize = 12.5.sp)
                    }
                }

                val ruleWanted = (ruleType == "show" || ruleType == "podcast") && (keepOn || deleteWatched) || (ruleType == "playlist" && follow)
                val ruleChanged = ruleType != null && (
                    (existingRule == null && ruleWanted) || (existingRule != null && !ruleWanted) ||
                        (existingRule != null && (
                            existingRule.quality != quality ||
                                (existingRule.keepNext ?: 0) != (if (keepOn) keepNext else 0) ||
                                existingRule.deleteWatched != deleteWatched || existingRule.follow != follow
                            ))
                    )

                suspend fun saveRule(dev: String): String? {
                    if (!ruleChanged || ruleType == null) return null
                    if (!ruleWanted) {
                        val id = existingRule?.ruleId?.takeIf { it.isNotBlank() } ?: return null
                        val r = offline.deleteRule(id)
                        if (r !is MusicApiResult.Success) OfflineDownloads.addOp(OfflinePendingOp(op = "rule_delete", id = id))
                        return "Smart downloads off"
                    }
                    val rule = OfflineRule(
                        deviceId = dev, type = ruleType, id = ruleId, quality = quality,
                        keepNext = if ((ruleType == "show" || ruleType == "podcast") && keepOn) keepNext else null,
                        deleteWatched = deleteWatched, follow = follow,
                    )
                    return when (val r = offline.saveRule(rule)) {
                        is MusicApiResult.Success -> {
                            if (!sendMode) OfflineDownloads.setRules(rules.filterNot { it.type == ruleType && it.id == ruleId } + (r.data ?: rule))
                            "Smart downloads on"
                        }
                        is MusicApiResult.Error -> "Smart downloads didn't save: ${r.message}"
                        MusicApiResult.DeviceRevoked -> null
                    }
                }

                fun rememberQuality(qid: String) {
                    OfflineDownloads.updateSettings(context) { s -> if (video) s.copy(videoQuality = qid) else s.copy(audioQuality = qid) }
                }

                // the fits line
                if (missing.isNotEmpty() && q != null) {
                    val room = fit.roomBytes
                    val line = when {
                        fit.fits && limit > 0 -> "Fits: ${OfflineFormat.bytes(room)} free under ${if (sendMode) "its" else "your"} ${OfflineFormat.bytes(limit)} limit"
                        fit.fits -> "Fits: ${OfflineFormat.bytes(room)} free on ${if (sendMode) "the phone" else "this phone"}"
                        fit.verdict == net.ssync.player.FitVerdict.OVER_LIMIT -> "Needs ${OfflineFormat.bytes(need)}, only ${OfflineFormat.bytes(room)} left under the ${OfflineFormat.bytes(limit)} limit. Pick a smaller quality, free up space or raise the limit."
                        else -> "Needs ${OfflineFormat.bytes(need)}, only ${OfflineFormat.bytes(room)} free on ${if (sendMode) "the phone" else "this phone"}."
                    }
                    Text(line, color = if (fit.fits) DlColors.Muted else DlColors.Warn, fontSize = 12.5.sp)
                    if (!sendMode && settings.wifiOnly && !onUnmetered(context)) {
                        Text("It waits for Wi-Fi. Turn off Wi-Fi only in Downloads settings to use mobile data.", color = DlColors.Muted, fontSize = 12.sp)
                    }
                }

                val count = missing.size
                val noun = groupNoun(target.type, count, video)
                val primary = when {
                    count == 0 && ruleChanged -> if (ruleWanted) "Save smart downloads" else "Turn off smart downloads"
                    count == 0 -> if (sendMode) "Already on the phone" else "Already on this phone"
                    sendMode -> "Send ${if (count > 1) "$count $noun" else "it"}${if (need > 0) " · ${OfflineFormat.bytes(need)}" else ""}"
                    count == 1 && p.items.size == 1 -> "Download${if (need > 0) " · ${OfflineFormat.bytes(need)}" else ""}"
                    else -> "Download $count $noun${if (need > 0) " · ${OfflineFormat.bytes(need)}" else ""}"
                }
                SheetButton(primary, primary = true, enabled = !busy && (count > 0 || ruleChanged) && (sendMode || count == 0 || fit.fits)) {
                    busy = true
                    scope.launch {
                        val dev = deviceId ?: myId
                        val ruleNote = saveRule(dev)
                        if (sendMode) {
                            val r = if (count > 0) offline.wish(dev, "add", target.type, target.id, quality) else MusicApiResult.Success(Unit)
                            doneMessage = when (r) {
                                is MusicApiResult.Success -> {
                                    val name = devices.firstOrNull { it.deviceId == dev }?.deviceName?.ifBlank { null } ?: "the phone"
                                    listOfNotNull(
                                        if (count > 0) "Sent to $name. It downloads the next time it's online." else null,
                                        ruleNote,
                                    ).joinToString(" ")
                                }
                                is MusicApiResult.Error -> "Didn't send: ${r.message}"
                                MusicApiResult.DeviceRevoked -> "This device was signed out."
                            }
                        } else {
                            rememberQuality(quality)
                            if (count > 0) askNotify()
                            val seasonBase = if (target.type == "season") (target.id.substringAfter(':').toIntOrNull() ?: 0) * 1000 else 0
                            val groupType = when (target.type) {
                                "season" -> "show"
                                in GROUP_TYPES, "audiobook" -> target.type
                                else -> target.groupType
                            }
                            val groupId = when (target.type) {
                                "season" -> target.id.substringBefore(':')
                                in GROUP_TYPES, "audiobook" -> target.id
                                else -> target.groupId
                            }
                            val groupTitle = when (target.type) {
                                "season" -> target.groupTitle.ifBlank { p.title }
                                in GROUP_TYPES, "audiobook" -> p.title.ifBlank { target.title }
                                else -> target.groupTitle
                            }
                            val added = OfflineDownloads.enqueue(
                                context,
                                missing.map { item ->
                                    OfflineRequest(
                                        itemType = item.itemType,
                                        itemId = item.itemId,
                                        title = item.title.ifBlank { target.title },
                                        subtitle = item.subtitle.orEmpty(),
                                        artwork = relativeArt(item.artwork ?: p.artwork ?: target.artwork),
                                        isVideo = item.itemType in OFFLINE_VIDEO_KINDS,
                                        quality = quality,
                                        parentId = target.parentId ?: groupId.ifBlank { null },
                                        groupType = groupType,
                                        groupId = groupId,
                                        groupTitle = groupTitle,
                                        groupArtwork = if (groupType.isNotBlank()) relativeArt(p.artwork ?: target.artwork) else null,
                                        sortIndex = sortIndexFor(item.title, item.subtitle, target, seasonBase, p.items.indexOf(item), p.items.size),
                                        expectedBytes = q?.perItemBytes?.get(item.key) ?: if (quality == "original") item.sizeBytes else 0L,
                                        watched = item.watched,
                                        positionS = item.positionS,
                                        durationS = item.durationS,
                                    )
                                },
                            )
                            if (ruleNote != null) OfflineSync.requestReport()
                            if (ruleNote != null && ruleWanted) {
                                // smart downloads fill in from the server's wanted list right away
                                scope.launch { OfflineSync.reconcile(context) }
                            }
                            doneMessage = listOfNotNull(
                                if (added > 0) "$added ${groupNoun(target.type, added, video)} added to your downloads." else null,
                                ruleNote,
                            ).joinToString(" ").ifBlank { "Saved." }
                            if (added > 0 && ruleNote == null) onDismiss()
                        }
                        busy = false
                    }
                }

                // already here: offer to take it off
                if (!sendMode) {
                    val here = p.items.filter { it.key in localKeys }
                    if (here.isNotEmpty()) {
                        SheetButton(
                            if (here.size == 1) "Remove from this phone" else "Remove ${here.size} from this phone",
                            primary = false,
                            danger = true,
                        ) {
                            OfflineDownloads.remove(here.map { it.key })
                            onDismiss()
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

private fun onUnmetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

@Composable
internal fun SheetToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).dpadFocusable(RoundedCornerShape(10.dp))
            .clickable { onChange(!checked) }.padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = SoulSyncColors.Accent,
                checkedThumbColor = SoulSyncColors.OnAccent,
                uncheckedTrackColor = DlColors.Raise2,
                uncheckedThumbColor = DlColors.Muted,
                uncheckedBorderColor = DlColors.Line2,
            ),
        )
    }
}

@Composable
internal fun SheetButton(label: String, primary: Boolean, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        !enabled -> DlColors.Raise2
        primary -> SoulSyncColors.Accent
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> DlColors.Muted
        primary -> SoulSyncColors.OnAccent
        danger -> DlColors.Bad
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(if (!primary) Modifier.border(1.dp, DlColors.Line2, RoundedCornerShape(14.dp)) else Modifier)
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** a small tag under a card title: smart, quality, watched */
@Composable
internal fun DlTag(text: String, fg: Color = DlColors.Muted, bg: Color = DlColors.Raise2, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = fg, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

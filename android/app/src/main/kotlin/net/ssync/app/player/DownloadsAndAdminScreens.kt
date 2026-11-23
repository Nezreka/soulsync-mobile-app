package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.AdminUsersOverview
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import java.io.File

private fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var n = bytes.toDouble()
    var i = 0
    while (n >= 1024.0 && i < units.lastIndex) {
        n /= 1024.0
        i++
    }
    return if (n >= 100.0 || i == 0) "${n.toInt()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", n, units[i])
}

/**
 * Reusable Download button for Movies, Episodes, YouTube, Podcasts, Audiobooks, and Music tracks.
 * Shows live progress while downloading, a green check when stored offline, or triggers download.
 */
@Composable
fun OfflineDownloadButton(
    api: PlayerApiClient,
    itemType: String,
    itemId: String,
    title: String,
    subtitle: String = "",
    artworkUrl: String? = null,
    streamUrl: String,
    isVideo: Boolean = false,
    parentId: String? = null,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    LaunchedEffect(context) { OfflineDownloadManager.init(context) }
    val items by OfflineDownloadManager.items.collectAsState()
    val activeMap by OfflineDownloadManager.active.collectAsState()
    val key = OfflineDownloadManager.keyOf(itemType, itemId)
    val saved = items.firstOrNull { it.key == key }
    val active = activeMap[key]

    if (compact) {
        IconButton(
            onClick = {
                if (saved != null) {
                    OfflineDownloadManager.deleteDownload(api, saved)
                } else if (active == null || active.error != null) {
                    OfflineDownloadManager.enqueueDownload(
                        api = api,
                        itemType = itemType,
                        itemId = itemId,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        streamUrl = streamUrl,
                        isVideo = isVideo,
                        parentId = parentId,
                    )
                }
            },
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = if (saved != null) Icons.Default.DownloadDone else Icons.Default.Download,
                contentDescription = if (saved != null) "Downloaded" else "Download",
                tint = when {
                    saved != null -> Color(0xFF6EE7B7)
                    active?.error != null -> Color(0xFFFDA4AF)
                    active != null -> SoulSyncColors.AccentLight
                    else -> SoulSyncColors.TextSoft
                },
                modifier = Modifier.size(18.dp),
            )
        }
    } else {
        Surface(
            shape = CircleShape,
            color = if (saved != null) Color(0xFF10B981).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
            border = BorderStroke(
                1.dp,
                if (saved != null) Color(0xFF10B981).copy(alpha = 0.5f) else SoulSyncColors.Line,
            ),
            modifier = Modifier.clickable {
                if (saved != null) {
                    OfflineDownloadManager.deleteDownload(api, saved)
                } else if (active == null || active.error != null) {
                    OfflineDownloadManager.enqueueDownload(
                        api = api,
                        itemType = itemType,
                        itemId = itemId,
                        title = title,
                        subtitle = subtitle,
                        artworkUrl = artworkUrl,
                        streamUrl = streamUrl,
                        isVideo = isVideo,
                        parentId = parentId,
                    )
                }
            },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Icon(
                    imageVector = if (saved != null) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = null,
                    tint = if (saved != null) Color(0xFF6EE7B7) else Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = when {
                        saved != null -> "Downloaded"
                        active?.error != null -> active.error
                        active != null -> if (active.progress >= 0f) "${(active.progress * 100).toInt()}%" else "Saving…"
                        else -> "Download"
                    },
                    color = if (saved != null) Color(0xFF6EE7B7) else Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
fun DownloadsScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
) {
    val context = LocalContext.current
    LaunchedEffect(context, api) {
        OfflineDownloadManager.init(context)
        OfflineDownloadManager.syncPendingRemovals(api)
    }
    val items by OfflineDownloadManager.items.collectAsState()
    val activeMap by OfflineDownloadManager.active.collectAsState()
    var filter by remember { mutableStateOf("all") }

    val filters = listOf(
        "all" to "All",
        "movie" to "Movies",
        "episode" to "TV",
        "track" to "Music",
        "youtube" to "YouTube",
        "podcast" to "Podcasts",
        "audiobook" to "Audiobooks",
    )
    val shown = remember(items, filter) {
        if (filter == "all") items else items.filter { it.itemType == filter }
    }
    val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Downloads", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    text = "${items.size} saved offline · ${formatDownloadBytes(totalBytes)} on this device",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { (k, lbl) ->
                        val on = filter == k
                        Text(
                            text = lbl,
                            color = if (on) Color.White else SoulSyncColors.TextSoft,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (on) SoulSyncColors.Accent else SoulSyncColors.Line, CircleShape)
                                .clickable { filter = k }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // Active downloads in progress
        if (activeMap.isNotEmpty()) {
            items(activeMap.values.toList(), key = { "active-${it.key}" }) { task ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SoulSyncColors.Accent.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = task.title,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (task.error != null) {
                                IconButton(
                                    onClick = { OfflineDownloadManager.dismissError(task.key) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = Color(0xFFFDA4AF))
                                }
                            } else {
                                Text(
                                    text = if (task.progress >= 0f) "${(task.progress * 100).toInt()}%" else "Downloading…",
                                    color = SoulSyncColors.AccentLight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        if (task.error != null) {
                            Text(task.error, color = Color(0xFFFDA4AF), fontSize = 12.sp)
                        } else if (task.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { task.progress },
                                color = SoulSyncColors.AccentLight,
                                trackColor = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            )
                        } else {
                            LinearProgressIndicator(
                                color = SoulSyncColors.AccentLight,
                                trackColor = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            )
                        }
                    }
                }
            }
        }

        if (shown.isEmpty() && activeMap.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(36.dp))
                    Text("No offline downloads yet", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Tap Download on any movie, episode, track, podcast, audiobook, or YouTube video to play it offline.",
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            items(shown, key = { it.key }) { item ->
                val fileUri = android.net.Uri.fromFile(File(item.localPath)).toString()
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.045f),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable {
                            val playable = ActivePlayable(
                                itemType = item.itemType,
                                itemId = item.itemId,
                                title = item.title,
                                subtitle = item.subtitle.ifBlank { "Offline" },
                                artworkUrl = item.artworkUrl,
                                streamUrl = fileUri,
                                isVideo = item.isVideo,
                                parentId = item.parentId,
                            )
                            if (item.isVideo) coordinator.playVideo(playable) else coordinator.playAudio(playable)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!item.artworkUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = item.artworkUrl,
                                    imageLoader = LocalSoulSyncImageLoader.current,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = listOfNotNull(
                                    item.itemType.uppercase(),
                                    item.subtitle.takeIf { it.isNotBlank() },
                                    formatDownloadBytes(item.sizeBytes),
                                ).joinToString(" · "),
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { OfflineDownloadManager.deleteDownload(api, item) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove download", tint = Color(0xFFFDA4AF))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminUsersScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var activeTab by remember { mutableStateOf("admin") } // "admin" | "mine"
    val scope = rememberCoroutineScope()
    var overview by remember { mutableStateOf<AdminUsersOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var selectedUserId by remember { mutableStateOf<Int?>(null) }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(api, selectedUserId, statusFilter, refreshTick) {
        loading = true
        when (val res = api.fetchAdminUsers(profileId = selectedUserId, status = statusFilter)) {
            is MusicApiResult.Success -> {
                overview = res.data
                errorMsg = null
            }
            is MusicApiResult.Error -> errorMsg = res.message
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Unified Admin Hub Segmented Switcher: Users & Devices | My Downloads
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(CircleShape)
                .background(Color(0xFF141620))
                .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                .padding(3.dp),
        ) {
            listOf("admin" to "Users & Devices", "mine" to "My Downloads").forEach { (id, label) ->
                val on = activeTab == id
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.28f) else Color.Transparent)
                        .clickable { activeTab = id }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (on) Color.White else SoulSyncColors.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        if (activeTab == "mine") {
            DownloadsScreen(api = api, coordinator = coordinator)
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = SoulSyncColors.AccentLight)
                    Text("Users & Downloads", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
                Text(
                    "Admin control center — manage download permissions and track device downloads & removals.",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }

        if (loading && overview == null) {
            item { LoadingBox() }
        } else if (errorMsg != null) {
            item {
                Text(
                    text = errorMsg.orEmpty(),
                    color = Color(0xFFFDA4AF),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            val data = overview!!
            // Summary Row
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val cards = listOf(
                        "Users" to "${data.users.size}",
                        "On devices" to "${data.totals.activeDownloads}",
                        "Storage" to formatDownloadBytes(data.totals.activeBytes),
                        "Removed" to "${data.totals.removedDownloads}",
                    )
                    items(cards) { (lbl, value) ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = 0.045f),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                            modifier = Modifier.width(126.dp),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(lbl, color = SoulSyncColors.TextSecondary, fontSize = 11.sp)
                                Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }

            // Profiles Section
            item {
                Text(
                    text = "Profiles",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            items(data.users, key = { "u-${it.id}" }) { u ->
                val selected = selectedUserId == u.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) SoulSyncColors.Accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, if (selected) SoulSyncColors.Accent else SoulSyncColors.Line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { selectedUserId = if (selected) null else u.id },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(SoulSyncColors.Accent.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = u.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 15.sp,
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(u.name, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                                if (u.isAdmin) {
                                    Text(
                                        text = "ADMIN",
                                        color = SoulSyncColors.AccentLight,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(SoulSyncColors.Accent.copy(alpha = 0.24f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = "${u.activeDownloads} on device · ${formatDownloadBytes(u.activeBytes)} · ${u.removedDownloads} removed",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                            )
                        }
                        if (!u.isAdmin) {
                            androidx.compose.material3.Switch(
                                checked = u.allowDownloads,
                                onCheckedChange = { allow ->
                                    scope.launch {
                                        api.setAdminUserDownloadPerm(u.id, allow)
                                        refreshTick++
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Activity Filter Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Download Activity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "All", "downloaded" to "On device", "removed" to "Removed").forEach { (st, lbl) ->
                            val on = statusFilter == st
                            Text(
                                text = lbl,
                                color = if (on) Color.White else SoulSyncColors.TextSoft,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f))
                                    .clickable { statusFilter = st }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            items(data.downloads, key = { "dl-${it.id}" }) { dl ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.035f),
                    border = BorderStroke(1.dp, SoulSyncColors.Line),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${dl.title} (${dl.itemType.uppercase()})",
                                color = Color.White,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${dl.profileName ?: "User ${dl.profileId}"} · ${dl.deviceName} · ${formatDownloadBytes(dl.sizeBytes)}",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val active = dl.status == "downloaded"
                        Text(
                            text = if (active) "On device" else "Removed",
                            color = if (active) Color(0xFF6EE7B7) else Color(0xFFFDA4AF),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (active) Color(0xFF10B981).copy(alpha = 0.18f) else Color(0xFFF43F5E).copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        }
    }
}

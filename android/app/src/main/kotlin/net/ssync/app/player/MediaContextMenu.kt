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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SendToMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.SongRefPayload

data class MediaContextItem(
    val itemType: String, // "movie", "show", "season", "episode", "album", "track", "audiobook", "podcast", "youtube"
    val itemId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val streamUrl: String? = null,
    val isVideo: Boolean = false,
    val parentId: String? = null,
    val onOpenDetails: (() -> Unit)? = null,
    val onPlayOverride: (() -> Unit)? = null,
    val songRef: SongRefPayload? = null,
    val owned: Boolean = true,
    val onRequest: (() -> Unit)? = null,
)

val LocalMediaContextMenu = staticCompositionLocalOf<(MediaContextItem) -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaContextMenuSheet(
    item: MediaContextItem,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val downloads by OfflineDownloads.tasks.collectAsState()
    val dlKey = net.ssync.player.offlineKey(item.itemType, item.itemId)
    val savedDownload = downloads.firstOrNull { it.key == dlKey }
    val isTv = net.ssync.pairing.ui.LocalIsTv.current
    var dlSheet by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var showPlaylistSheet by remember { mutableStateOf(false) }

    val kindLabel = when (item.itemType) {
        "movie" -> "Movie"
        "show" -> "TV Series"
        "season" -> "Season"
        "episode" -> "Episode"
        "album" -> "Album"
        "track" -> "Track"
        "audiobook" -> "Audiobook"
        "podcast" -> "Podcast"
        "youtube" -> "YouTube"
        "home" -> "Home video"
        else -> "Media"
    }

    // single items and whole groups; a tv sends them to a phone instead
    val canDownload = item.owned &&
        item.itemType in setOf("movie", "episode", "track", "youtube", "home", "audiobook", "podcast", "podcast_episode", "show", "season", "album", "artist", "playlist")

    if (!dlSheet) AdaptiveTvSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF131520),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header with Artwork + Title + Type
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1D2030))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                ) {
                    SoulSyncArtwork(
                        url = item.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(kindLabel, item.subtitle.takeIf { it.isNotBlank() }).joinToString(" · "),
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(modifier = Modifier.height(4.dp))

            // 0. Request (for missing / unowned items only — never Play or Download!)
            if (!item.owned && item.onRequest != null) {
                ContextMenuRow(
                    icon = Icons.Default.Add,
                    label = "Request ${kindLabel.lowercase()}",
                    tint = SoulSyncColors.AccentLight,
                    onClick = {
                        onDismiss()
                        item.onRequest.invoke()
                    },
                )
            }

            // 1. Play Now (owned items only)
            if (item.owned && (item.onPlayOverride != null || !item.streamUrl.isNullOrBlank() || item.itemType == "album")) {
                ContextMenuRow(
                    icon = Icons.Default.PlayArrow,
                    label = "Play now",
                    tint = SoulSyncColors.AccentLight,
                    onClick = {
                        when {
                            item.onPlayOverride != null -> {
                                onDismiss()
                                item.onPlayOverride.invoke()
                            }
                            item.itemType == "album" -> {
                                val albumId = item.itemId.toIntOrNull()
                                if (albumId != null) {
                                    scope.launch {
                                        val res = api.fetchAlbumDetail(albumId)
                                        val al = (res as? MusicApiResult.Success)?.data
                                        val playable = al?.tracks?.filter { it.isOwned }?.map { t ->
                                            ActivePlayable(
                                                itemType = "track",
                                                itemId = t.id.toString(),
                                                title = t.title,
                                                subtitle = al.primaryArtist?.name ?: al.title,
                                                artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                                                streamUrl = api.streamUrl("track", t.id.toString()),
                                                parentId = al.id.toString(),
                                            )
                                        }.orEmpty()
                                        if (playable.isNotEmpty()) {
                                            coordinator.playAudio(playable.first(), playable.drop(1))
                                        }
                                        onDismiss()
                                    }
                                }
                            }
                            !item.streamUrl.isNullOrBlank() -> {
                                val p = ActivePlayable(
                                    itemType = item.itemType,
                                    itemId = item.itemId,
                                    title = item.title,
                                    subtitle = item.subtitle,
                                    artworkUrl = item.artworkUrl,
                                    streamUrl = item.streamUrl,
                                    isVideo = item.isVideo,
                                    parentId = item.parentId,
                                )
                                if (item.isVideo) coordinator.playVideo(p) else coordinator.playAudio(p)
                                onDismiss()
                            }
                        }
                    },
                )
            }

            // 2. Shuffle Album (owned only)
            if (item.owned && item.itemType == "album") {
                ContextMenuRow(
                    icon = Icons.Default.Shuffle,
                    label = "Shuffle album",
                    onClick = {
                        val albumId = item.itemId.toIntOrNull()
                        if (albumId != null) {
                            scope.launch {
                                val res = api.fetchAlbumDetail(albumId)
                                val al = (res as? MusicApiResult.Success)?.data
                                val playable = al?.tracks?.filter { it.isOwned }?.map { t ->
                                    ActivePlayable(
                                        itemType = "track",
                                        itemId = t.id.toString(),
                                        title = t.title,
                                        subtitle = al.primaryArtist?.name ?: al.title,
                                        artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                                        streamUrl = api.streamUrl("track", t.id.toString()),
                                        parentId = al.id.toString(),
                                    )
                                }?.shuffled().orEmpty()
                                if (playable.isNotEmpty()) {
                                    coordinator.playAudio(playable.first(), playable.drop(1))
                                }
                                onDismiss()
                            }
                        }
                    },
                )
            }

            // 3. Add to Queue (owned audio items only)
            if (item.owned && !item.isVideo && !item.streamUrl.isNullOrBlank()) {
                ContextMenuRow(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Add to queue",
                    onClick = {
                        coordinator.enqueueAudio(
                            ActivePlayable(
                                itemType = item.itemType,
                                itemId = item.itemId,
                                title = item.title,
                                subtitle = item.subtitle,
                                artworkUrl = item.artworkUrl,
                                streamUrl = item.streamUrl,
                                isVideo = false,
                                parentId = item.parentId,
                            ),
                        )
                        onDismiss()
                    },
                )
            }

            // 4. Add to Playlist (owned tracks only)
            if (item.owned && item.songRef != null) {
                ContextMenuRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Add to playlist",
                    onClick = { showPlaylistSheet = true },
                )
            }

            // 5. Watchlist (Movies & Shows)
            if (item.owned && (item.itemType == "movie" || item.itemType == "show")) {
                ContextMenuRow(
                    icon = Icons.Default.BookmarkAdd,
                    label = "Add to Watchlist",
                    onClick = {
                        scope.launch {
                            api.addToWatchlist(item.itemType, item.itemId)
                            statusBanner = "Saved to Watchlist"
                        }
                    },
                )
                ContextMenuRow(
                    icon = Icons.Default.BookmarkRemove,
                    label = "Remove from Watchlist",
                    onClick = {
                        scope.launch {
                            api.removeFromWatchlist(item.itemType, item.itemId)
                            statusBanner = "Removed from Watchlist"
                        }
                    },
                )
            }

            // 6. Download (sheet with sizes) / remove, or send to a phone on a tv
            if (canDownload) {
                if (!isTv && savedDownload != null && savedDownload.state == net.ssync.player.TaskState.DOWNLOADED) {
                    ContextMenuRow(
                        icon = Icons.Default.DeleteOutline,
                        label = "Remove from this phone",
                        tint = Color(0xFFFDA4AF),
                        onClick = {
                            OfflineDownloads.remove(listOf(savedDownload.key))
                            onDismiss()
                        },
                    )
                } else if (!isTv && savedDownload != null) {
                    ContextMenuRow(
                        icon = Icons.Default.DeleteOutline,
                        label = "Cancel download",
                        tint = Color(0xFFFDA4AF),
                        onClick = {
                            OfflineDownloads.remove(listOf(savedDownload.key))
                            onDismiss()
                        },
                    )
                } else {
                    ContextMenuRow(
                        icon = if (isTv) Icons.Default.SendToMobile else Icons.Default.Download,
                        label = if (isTv) "Send to a phone" else "Download",
                        onClick = { dlSheet = true },
                    )
                }
            }

            // 7. Open Details
            if (item.onOpenDetails != null) {
                ContextMenuRow(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    label = "Open details",
                    onClick = {
                        onDismiss()
                        item.onOpenDetails.invoke()
                    },
                )
            }

            if (statusBanner != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SoulSyncColors.Accent.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = statusBanner!!,
                        color = SoulSyncColors.AccentLight,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (dlSheet) {
        DownloadSheet(
            api = api,
            target = DownloadTarget(
                type = item.itemType,
                id = item.itemId,
                title = item.title,
                subtitle = item.subtitle,
                artwork = relativeArt(item.artworkUrl),
                groupType = when (item.itemType) {
                    "episode" -> if (item.parentId != null) "show" else ""
                    "track" -> if (item.parentId != null) "album" else ""
                    else -> ""
                },
                groupId = if (item.itemType == "episode" || item.itemType == "track") item.parentId.orEmpty() else "",
                parentId = item.parentId,
            ),
            sendMode = isTv,
            onDismiss = {
                dlSheet = false
                onDismiss()
            },
        )
    }

    if (showPlaylistSheet && item.songRef != null) {
        AddToPlaylistSheet(
            song = item.songRef,
            api = api,
            onDismiss = {
                showPlaylistSheet = false
                onDismiss()
            },
        )
    }
}

@Composable
private fun ContextMenuRow(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.9f),
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            color = tint,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

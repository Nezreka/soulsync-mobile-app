package net.ssync.app.player

import net.ssync.pairing.ui.dpadFocusable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicPlaylist
import net.ssync.player.PlayerApiClient
import net.ssync.player.SongRefPayload

internal class LikedTracksState(
    internal val api: PlayerApiClient,
) {
    val likedIds = mutableStateMapOf<Int, Boolean>()
    val likedKeys = mutableStateMapOf<String, Boolean>()

    fun keyFor(artist: String?, title: String): String =
        "${artist.orEmpty().trim().lowercase()}|${title.trim().lowercase()}"

    fun isLiked(trackId: Int?, artist: String?, title: String): Boolean {
        if (trackId != null && likedIds[trackId] != null) return likedIds[trackId] == true
        return likedKeys[keyFor(artist, title)] == true
    }

    suspend fun loadInitial() {
        val pls = (api.fetchPlaylists() as? MusicApiResult.Success)?.data.orEmpty()
        val likedPl = pls.firstOrNull { it.name.equals("Liked Songs", ignoreCase = true) }
        val pid = likedPl?.idString?.toIntOrNull() ?: return
        val detail = (api.fetchPlaylistDetail(pid) as? MusicApiResult.Success)?.data ?: return
        detail.tracks.forEach { t ->
            t.trackId?.let { likedIds[it] = true }
            likedKeys[keyFor(t.artist, t.title)] = true
        }
    }

    suspend fun toggle(song: SongRefPayload) {
        val currentlyLiked = isLiked(song.lib2TrackId, song.artist, song.title)
        val next = !currentlyLiked
        song.lib2TrackId?.let { likedIds[it] = next }
        likedKeys[keyFor(song.artist, song.title)] = next
        val res = if (next) api.likeTrack(song) else api.unlikeTrack(song)
        if (res is MusicApiResult.Error) {
            // Roll back on failure
            song.lib2TrackId?.let { likedIds[it] = currentlyLiked }
            likedKeys[keyFor(song.artist, song.title)] = currentlyLiked
        }
    }
}

@Composable
internal fun rememberLikedTracksState(api: PlayerApiClient): LikedTracksState {
    val state = remember(api) { LikedTracksState(api) }
    LaunchedEffect(api) {
        state.loadInitial()
    }
    return state
}

@Composable
internal fun TrackRowActionIcons(
    song: SongRefPayload,
    playable: ActivePlayable?,
    likedState: LikedTracksState,
    coordinator: PlaybackCoordinator,
    onAddToPlaylist: (SongRefPayload) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val liked = likedState.isLiked(song.lib2TrackId, song.artist, song.title)
    var justQueued by remember(song.lib2TrackId, song.title) { mutableStateOf(false) }
    val showQueue = playable != null && coordinator.activeItem != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(
            onClick = { scope.launch { likedState.toggle(song) } },
            modifier = Modifier.size(34.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(
                imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (liked) "Unlike" else "Like",
                tint = if (liked) Color(0xFFF43F5E) else SoulSyncColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showQueue && playable != null) {
            IconButton(
                onClick = {
                    coordinator.enqueueAudio(playable)
                    justQueued = true
                    scope.launch {
                        delay(1500)
                        justQueued = false
                    }
                },
                modifier = Modifier.size(34.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(
                    imageVector = if (justQueued) Icons.Default.Check else Icons.Default.QueueMusic,
                    contentDescription = "Add to queue",
                    tint = if (justQueued) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(
            onClick = { onAddToPlaylist(song) },
            modifier = Modifier.size(34.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = "Add to playlist",
                tint = SoulSyncColors.TextSecondary,
                modifier = Modifier.size(19.dp),
            )
        }
        if (playable != null) {
            var showReport by remember(playable.itemId) { mutableStateOf(false) }
            OfflineDownloadButton(
                api = likedState.api,
                itemType = playable.itemType,
                itemId = playable.itemId,
                title = playable.title,
                subtitle = playable.subtitle,
                artworkUrl = playable.artworkUrl,
                streamUrl = playable.streamUrl,
                isVideo = false,
                parentId = playable.parentId,
                compact = true,
            )
            IconButton(
                onClick = { showReport = true },
                modifier = Modifier.size(32.dp).dpadFocusable(androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = "Report issue",
                    tint = SoulSyncColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (showReport) {
                ReportIssueDialog(
                    api = likedState.api,
                    itemType = playable.itemType,
                    itemId = playable.itemId,
                    title = playable.title,
                    subtitle = playable.subtitle,
                    onDismiss = { showReport = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
    song: SongRefPayload,
    api: PlayerApiClient,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var addedIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        loading = true
        (api.fetchPlaylists() as? MusicApiResult.Success)?.let { r ->
            playlists = r.data.filter { it.kind == "yours" }
        }
        loading = false
    }

    AdaptiveTvSheet(
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Add to playlist",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${song.title} · ${song.artist}",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
                    Text("Loading playlists…", color = SoulSyncColors.TextSecondary, fontSize = 13.sp)
                }
            } else if (playlists.isEmpty()) {
                Text(
                    text = "No personal playlists yet.",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 13.5.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(playlists, key = { it.idString }) { pl ->
                        val pid = pl.idString.toIntOrNull()
                        val isAdded = pid != null && pid in addedIds
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = pid != null && !isAdded) {
                                    if (pid != null) {
                                        addedIds = addedIds + pid
                                        scope.launch {
                                            api.addTrackToPlaylist(pid, song)
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAdded) SoulSyncColors.Accent.copy(alpha = 0.16f) else SoulSyncColors.Card,
                            border = BorderStroke(
                                1.dp,
                                if (isAdded) SoulSyncColors.Accent else SoulSyncColors.Line,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = pl.name,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${pl.trackCount} tracks",
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (isAdded) SoulSyncColors.Accent else Color.White.copy(alpha = 0.08f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = if (isAdded) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = if (isAdded) "Added" else "Add",
                                        tint = if (isAdded) Color.Black else Color.White,
                                        modifier = Modifier.size(16.dp),
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

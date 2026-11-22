package net.ssync.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.launch
import net.ssync.network.CertPinningOkHttp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.MusicApiClient
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicPlaylist
import net.ssync.player.MusicTrack
import net.ssync.storage.PairedServerRecord

private enum class MusicTab(val label: String) {
    RECENT("Recent"),
    LIBRARY("Library"),
    PLAYLISTS("Playlists"),
}

@Composable
fun MusicHomeScreen(
    record: PairedServerRecord,
    mediaController: MediaController?,
    onDeviceRevoked: () -> Unit,
    onUnpair: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val apiClient = remember(record) {
        val pinnedClient = CertPinningOkHttp.createPinnedClient(
            expectedCertFingerprintSha256 = record.certFingerprintSha256,
            deviceTokenProvider = { record.deviceToken },
            onUnauthorized = onDeviceRevoked,
        )
        MusicApiClient(
            baseUrl = record.lastWorkingBaseUrl,
            pinnedAuthenticatedClient = pinnedClient,
        )
    }

    var activeTab by remember { mutableStateOf(MusicTab.RECENT) }
    var tracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(mediaController) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                nowPlayingTitle = metadata.title?.toString()
                nowPlayingArtist = metadata.artist?.toString()
            }
        }
        mediaController?.addListener(listener)
        nowPlayingTitle = mediaController?.mediaMetadata?.title?.toString()
        nowPlayingArtist = mediaController?.mediaMetadata?.artist?.toString()
        isPlaying = mediaController?.isPlaying == true
        onDispose { mediaController?.removeListener(listener) }
    }

    fun loadTab(tab: MusicTab) {
        scope.launch {
            isLoading = true
            errorMessage = null
            when (tab) {
                MusicTab.RECENT -> when (val res = apiClient.fetchRecentTracks()) {
                    is MusicApiResult.Success -> tracks = res.data
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> errorMessage = res.message
                }
                MusicTab.LIBRARY -> when (val res = apiClient.fetchLibraryTracks()) {
                    is MusicApiResult.Success -> tracks = res.data
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> errorMessage = res.message
                }
                MusicTab.PLAYLISTS -> when (val res = apiClient.fetchPlaylists()) {
                    is MusicApiResult.Success -> playlists = res.data
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> errorMessage = res.message
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(activeTab, record) {
        loadTab(activeTab)
    }

    fun playTrack(track: MusicTrack) {
        val controller = mediaController ?: return
        val item = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(apiClient.trackStreamUrl(track.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .build(),
            )
            .build()
        controller.setMediaItem(item)
        controller.prepare()
        controller.play()
        nowPlayingTitle = track.title
        nowPlayingArtist = track.artist
    }

    Scaffold(
        containerColor = SoulSyncColors.BgPrimary,
        topBar = {
            Surface(
                color = SoulSyncColors.Panel,
                border = BorderStroke(1.dp, SoulSyncColors.Line),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.soulsync_logo),
                        contentDescription = "SoulSync",
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.serverName ?: "SoulSync",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${record.profileName} • Direct",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(
                        onClick = { loadTab(activeTab) },
                        modifier = Modifier.dpadFocusable(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = SoulSyncColors.TextSoft,
                        )
                    }
                    IconButton(
                        onClick = onUnpair,
                        modifier = Modifier.dpadFocusable(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = "Unpair",
                            tint = SoulSyncColors.TextSoft,
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (nowPlayingTitle != null) {
                NowPlayingBar(
                    title = nowPlayingTitle.orEmpty(),
                    artist = nowPlayingArtist.orEmpty(),
                    isPlaying = isPlaying,
                    onPrevious = { mediaController?.seekToPreviousMediaItem() },
                    onTogglePlayPause = {
                        mediaController?.let { if (it.isPlaying) it.pause() else it.play() }
                    },
                    onNext = { mediaController?.seekToNextMediaItem() },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MusicTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { activeTab = tab },
                        label = { Text(tab.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SoulSyncColors.Accent,
                            selectedLabelColor = SoulSyncColors.OnAccent,
                            containerColor = SoulSyncColors.Card,
                            labelColor = SoulSyncColors.TextSoft,
                        ),
                        modifier = Modifier.dpadFocusable(RoundedCornerShape(10.dp)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SoulSyncColors.Accent)
                }
                errorMessage != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = errorMessage.orEmpty(), color = SoulSyncColors.Danger)
                }
                activeTab == MusicTab.PLAYLISTS -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(playlists, key = { it.id }) { pl ->
                        PlaylistRow(pl)
                    }
                }
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(tracks, key = { it.id }) { track ->
                        TrackRow(track = track, onClick = { playTrack(track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: MusicTrack, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.Line),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SoulSyncColors.CardRaised, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = SoulSyncColors.Accent,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(track.artist, track.album.takeIf { it.isNotBlank() })
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            track.format?.takeIf { it.isNotBlank() }?.let { fmt ->
                Text(
                    text = fmt.uppercase(),
                    color = if (fmt.equals("FLAC", ignoreCase = true)) SoulSyncColors.HiRes else SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: MusicPlaylist) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.Line),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.QueueMusic,
                contentDescription = null,
                tint = SoulSyncColors.Accent,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = playlist.name, style = MaterialTheme.typography.titleMedium)
                Text(text = "${playlist.trackCount} tracks", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun NowPlayingBar(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        color = SoulSyncColors.CardRaised,
        border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = SoulSyncColors.TextPrimary)
            }
            IconButton(onClick = onTogglePlayPause, modifier = Modifier.dpadFocusable()) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = SoulSyncColors.AccentLight,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.dpadFocusable()) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = SoulSyncColors.TextPrimary)
            }
        }
    }
}

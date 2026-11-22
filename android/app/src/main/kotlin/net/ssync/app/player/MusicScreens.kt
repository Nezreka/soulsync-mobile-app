package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.LibAlbum
import net.ssync.player.LibAlbumDetail
import net.ssync.player.LibArtist
import net.ssync.player.LibArtistDetail
import net.ssync.player.LibSong
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicPlaylist
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlaylistDetail

private enum class MusicSubTab(val label: String) {
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
    PLAYLISTS("Playlists"),
}

@Composable
fun MusicScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var tab by remember { mutableStateOf(MusicSubTab.ALBUMS) }
    var isLoading by remember { mutableStateOf(true) }
    var albums by remember { mutableStateOf<List<LibAlbum>>(emptyList()) }
    var artists by remember { mutableStateOf<List<LibArtist>>(emptyList()) }
    var songs by remember { mutableStateOf<List<LibSong>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<MusicPlaylist>>(emptyList()) }

    LaunchedEffect(tab, api) {
        isLoading = true
        when (tab) {
            MusicSubTab.ALBUMS -> when (val r = api.fetchAlbums()) {
                is MusicApiResult.Success -> albums = r.data
                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                is MusicApiResult.Error -> Unit
            }
            MusicSubTab.ARTISTS -> when (val r = api.fetchArtists()) {
                is MusicApiResult.Success -> artists = r.data
                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                is MusicApiResult.Error -> Unit
            }
            MusicSubTab.SONGS -> when (val r = api.fetchSongs()) {
                is MusicApiResult.Success -> songs = r.data
                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                is MusicApiResult.Error -> Unit
            }
            MusicSubTab.PLAYLISTS -> when (val r = api.fetchPlaylists()) {
                is MusicApiResult.Success -> playlists = r.data
                MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                is MusicApiResult.Error -> Unit
            }
        }
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicSubTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = { Text(t.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                        containerColor = SoulSyncColors.Card,
                        labelColor = SoulSyncColors.TextSoft,
                    ),
                    modifier = Modifier.dpadFocusable(),
                )
            }
        }

        if (isLoading) {
            LoadingBox()
            return
        }

        when (tab) {
            MusicSubTab.ALBUMS -> {
                if (albums.isEmpty()) {
                    EmptyBox("No albums found.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            SquareMediaCard(
                                title = album.title,
                                subtitle = album.artistName,
                                artworkUrl = api.artwork.resolve(album.imageUrl) ?: api.artwork.albumArtwork(album.id),
                                onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                            )
                        }
                    }
                }
            }
            MusicSubTab.ARTISTS -> {
                if (artists.isEmpty()) {
                    EmptyBox("No artists found.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            SquareMediaCard(
                                title = artist.name,
                                subtitle = "${artist.albumCount} albums",
                                artworkUrl = api.artwork.resolve(artist.imageUrl ?: artist.remoteImageUrl),
                                fallbackIcon = Icons.Default.Person,
                                onClick = { onNavigate(PlayerDestination.ArtistDetail(artist.id)) },
                            )
                        }
                    }
                }
            }
            MusicSubTab.SONGS -> {
                if (songs.isEmpty()) {
                    EmptyBox("No songs found.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                            val playables = songs.drop(idx).map { s ->
                                ActivePlayable(
                                    itemType = "track",
                                    itemId = s.id.toString(),
                                    title = s.title,
                                    subtitle = s.artistName ?: "Unknown Artist",
                                    artworkUrl = api.artwork.albumArtwork(s.albumId),
                                    streamUrl = api.streamUrl("track", s.id.toString()),
                                )
                            }
                            SimpleTrackRow(
                                title = song.title,
                                subtitle = listOfNotNull(song.artistName, song.albumTitle).joinToString(" • "),
                                artworkUrl = api.artwork.albumArtwork(song.albumId),
                                onClick = {
                                    coordinator.playAudio(playables.first(), playables.drop(1))
                                },
                            )
                        }
                    }
                }
            }
            MusicSubTab.PLAYLISTS -> {
                if (playlists.isEmpty()) {
                    EmptyBox("No playlists yet.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(playlists, key = { it.idString }) { pl ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dpadFocusable(RoundedCornerShape(12.dp))
                                    .clickable {
                                        pl.idString.toIntOrNull()?.let {
                                            onNavigate(PlayerDestination.PlaylistDetail(it))
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
                                border = BorderStroke(1.dp, SoulSyncColors.Line),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    SoulSyncArtwork(
                                        url = api.artwork.resolve(pl.imageUrl),
                                        contentDescription = pl.name,
                                        fallbackIcon = Icons.AutoMirrored.Filled.QueueMusic,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pl.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${pl.trackCount} tracks", style = MaterialTheme.typography.bodyMedium)
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
fun ArtistDetailScreen(
    artistId: Int,
    api: PlayerApiClient,
    onAlbumClick: (Int) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var artist by remember { mutableStateOf<LibArtistDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(artistId, api) {
        isLoading = true
        when (val r = api.fetchArtistDetail(artistId)) {
            is MusicApiResult.Success -> artist = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    val a = artist
    if (isLoading) {
        LoadingBox()
        return
    }
    if (a == null) {
        EmptyBox("Artist not found.")
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoulSyncArtwork(
                    url = api.artwork.resolve(a.imageUrl ?: a.remoteImageUrl),
                    contentDescription = a.name,
                    fallbackIcon = Icons.Default.Person,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(a.name, style = MaterialTheme.typography.headlineMedium)
                    if (a.genres.isNotEmpty()) {
                        Text(a.genres.take(3).joinToString(" • "), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        val bio = a.about?.bio ?: a.summary
        if (!bio.isNullOrBlank()) {
            item { Text(bio, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
        val releases = a.albums + a.eps + a.singles
        if (releases.isNotEmpty()) {
            item { SectionHeader("Releases") }
            items(releases, key = { it.id }) { album ->
                SimpleTrackRow(
                    title = album.title,
                    subtitle = listOfNotNull(album.year?.toString(), album.albumType).joinToString(" • "),
                    artworkUrl = api.artwork.resolve(album.imageUrl) ?: api.artwork.albumArtwork(album.id),
                    onClick = { onAlbumClick(album.id) },
                )
            }
        }
    }
}

@Composable
fun AlbumDetailScreen(
    albumId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var album by remember { mutableStateOf<LibAlbumDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(albumId, api) {
        isLoading = true
        when (val r = api.fetchAlbumDetail(albumId)) {
            is MusicApiResult.Success -> album = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    val al = album
    if (isLoading) {
        LoadingBox()
        return
    }
    if (al == null) {
        EmptyBox("Album not found.")
        return
    }

    val coverUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id)
    val playableTracks = al.tracks.filter { it.id != null && !it.isMissing }.map { t ->
        ActivePlayable(
            itemType = "track",
            itemId = t.id.toString(),
            title = t.title,
            subtitle = al.primaryArtist?.name ?: al.title,
            artworkUrl = coverUrl,
            streamUrl = api.streamUrl("track", t.id.toString()),
        )
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoulSyncArtwork(
                    url = coverUrl,
                    contentDescription = al.title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.size(100.dp).clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(al.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = listOfNotNull(al.primaryArtist?.name, al.year?.toString()).joinToString(" • "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (playableTracks.isNotEmpty()) {
                                coordinator.playAudio(playableTracks.first(), playableTracks.drop(1))
                            }
                        },
                        enabled = playableTracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoulSyncColors.Accent,
                            contentColor = SoulSyncColors.OnAccent,
                        ),
                        modifier = Modifier.dpadFocusable(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play Album")
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        itemsIndexed(al.tracks) { idx, t ->
            val fmt = t.file?.format?.uppercase()
            SimpleTrackRow(
                title = "${t.trackNumber ?: (idx + 1)}. ${t.title}",
                subtitle = if (t.isMissing) "Not in library" else (fmt ?: ""),
                artworkUrl = null,
                trailingBadge = fmt,
                onClick = {
                    val pIdx = playableTracks.indexOfFirst { it.itemId == t.id?.toString() }
                    if (pIdx >= 0) {
                        coordinator.playAudio(playableTracks[pIdx], playableTracks.drop(pIdx + 1))
                    }
                },
            )
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var playlist by remember { mutableStateOf<PlaylistDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(playlistId, api) {
        isLoading = true
        when (val r = api.fetchPlaylistDetail(playlistId)) {
            is MusicApiResult.Success -> playlist = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    val pl = playlist
    if (isLoading) {
        LoadingBox()
        return
    }
    if (pl == null) {
        EmptyBox("Playlist not found.")
        return
    }

    val playable = pl.tracks.filter { it.trackId != null }.map { item ->
        ActivePlayable(
            itemType = "track",
            itemId = item.trackId.toString(),
            title = item.title,
            subtitle = item.artist,
            artworkUrl = api.artwork.albumArtwork(item.albumId),
            streamUrl = api.streamUrl("track", item.trackId.toString()),
        )
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(pl.name, style = MaterialTheme.typography.headlineMedium)
            if (!pl.description.isNullOrBlank()) {
                Text(pl.description.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    if (playable.isNotEmpty()) coordinator.playAudio(playable.first(), playable.drop(1))
                },
                enabled = playable.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoulSyncColors.Accent,
                    contentColor = SoulSyncColors.OnAccent,
                ),
                modifier = Modifier.dpadFocusable(),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play Playlist")
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        items(pl.tracks) { item ->
            SimpleTrackRow(
                title = item.title,
                subtitle = listOfNotNull(item.artist, item.album).joinToString(" • "),
                artworkUrl = api.artwork.albumArtwork(item.albumId),
                onClick = {
                    val idx = playable.indexOfFirst { it.itemId == item.trackId?.toString() }
                    if (idx >= 0) coordinator.playAudio(playable[idx], playable.drop(idx + 1))
                },
            )
        }
    }
}

@Composable
fun SimpleTrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    trailingBadge: String? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Card),
        border = BorderStroke(1.dp, SoulSyncColors.Line),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artworkUrl != null) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!trailingBadge.isNullOrBlank()) {
                Text(
                    text = trailingBadge,
                    color = if (trailingBadge == "FLAC") SoulSyncColors.HiRes else SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

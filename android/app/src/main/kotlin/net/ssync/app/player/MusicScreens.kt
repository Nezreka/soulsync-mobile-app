package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import net.ssync.pairing.ui.LocalIsTv
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.DiscoverPlaylistDetail
import net.ssync.player.DiscoverPlaylistSummary
import net.ssync.player.LibAlbum
import net.ssync.player.LibAlbumDetail
import net.ssync.player.LibArtist
import net.ssync.player.LibArtistDetail
import net.ssync.player.LibSong
import net.ssync.player.LibTrack
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicChannel
import net.ssync.player.MusicPlaylist
import net.ssync.player.MusicTrackRequestResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlaylistDetail
import net.ssync.player.SongRefPayload

private enum class MusicSubTab(val label: String) {
    FOR_YOU("For you"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    SONGS("Tracks"),
    PLAYLISTS("Playlists"),
}

private fun formatTrackDuration(raw: Long?): String? {
    if (raw == null || raw <= 0L) return null
    val totalSec = if (raw > 10_000L) raw / 1000L else raw
    val m = totalSec / 60L
    val s = totalSec % 60L
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun formatTotalAlbumDuration(tracks: List<LibTrack>): String? {
    val totalSec = tracks.sumOf { t ->
        val raw = t.duration ?: 0L
        if (raw > 10_000L) raw / 1000L else raw
    }
    if (totalSec <= 0L) return null
    val hrs = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    return if (hrs > 0) "${hrs}h ${mins}m" else "${mins} min"
}

@Composable
fun MusicScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
    startOnPlaylists: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val pageRows = rememberPageRows(api, "music")
    var tab by remember(startOnPlaylists) {
        mutableStateOf(if (startOnPlaylists) MusicSubTab.PLAYLISTS else MusicSubTab.FOR_YOU)
    }
    val warmHit = HomeWarmCache.baseUrl == api.baseUrl &&
        (HomeWarmCache.albums.isNotEmpty() || HomeWarmCache.musicArtists.isNotEmpty() || HomeWarmCache.discoverPlaylists.isNotEmpty())
    var isLoading by remember(api.baseUrl) { mutableStateOf(!warmHit) }
    var loadingMore by remember { mutableStateOf(false) }
    var albumsPage by remember { mutableStateOf(1) }
    var artistsPage by remember { mutableStateOf(1) }
    var songsPage by remember { mutableStateOf(1) }
    var hasMoreAlbums by remember { mutableStateOf(true) }
    var hasMoreArtists by remember { mutableStateOf(true) }
    var hasMoreSongs by remember { mutableStateOf(true) }
    var gridView by remember { mutableStateOf(true) }
    var albums by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.albums else emptyList()) }
    var artists by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicArtists else emptyList()) }
    var songs by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicSongs else emptyList()) }
    var recentMusic by remember(api.baseUrl) {
        mutableStateOf(if (warmHit) HomeWarmCache.continueItems.filter { it.itemType == "track" } else emptyList())
    }
    var playlists by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicPlaylists else emptyList()) }
    var discoverPlaylists by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.discoverPlaylists else emptyList()) }
    var musicStations by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicStations else emptyList()) }
    var stats30d by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicStats30d else net.ssync.player.ListeningStatsSnapshot()) }
    var statsAll by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicStatsAll else net.ssync.player.ListeningStatsSnapshot()) }
    var recentStatsTracks by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicRecentTracks else emptyList()) }
    var moreByArtistRails by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicMoreByArtistRails else emptyList()) }
    var similarAlbumsRail by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicSimilarAlbums else null) }
    var genreRails by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.musicGenreRails else emptyList()) }
    val likedState = rememberLikedTracksState(api)

    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId

    LaunchedEffect(tab, api) {
        when (tab) {
            MusicSubTab.FOR_YOU -> {
                if (albums.isEmpty() && stats30d.topAlbums.isEmpty() && discoverPlaylists.isEmpty()) isLoading = true
                // Stream every music shelf independently in parallel so UI paints immediately!
                coroutineScope {
                    launch {
                        when (val r = api.fetchAlbums(limit = 80, page = 1)) {
                            is MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                            is MusicApiResult.Success -> {
                                albums = r.data
                                HomeWarmCache.albums = r.data
                                hasMoreAlbums = r.data.size >= 80
                                isLoading = false
                            }
                            is MusicApiResult.Error -> Unit
                        }
                    }
                    launch {
                        // Real 30-day listening stats (Recent, Most Played Last Month, Top Artists, Top Genres)
                        val r30 = api.fetchListeningStats("30d")
                        if (r30 is MusicApiResult.Success) {
                            stats30d = r30.data
                            HomeWarmCache.musicStats30d = r30.data
                            if (r30.data.recent.isNotEmpty()) {
                                recentStatsTracks = r30.data.recent
                                HomeWarmCache.musicRecentTracks = r30.data.recent
                            }
                            isLoading = false
                            // Stream dynamic "More by X", "Albums Like X", and "More in [Genre]" rails!
                            launch {
                                val topArtistsForMore = r30.data.topArtists.filter { it.id != null }.take(4)
                                val builtMore = mutableListOf<Pair<String, List<LibAlbum>>>()
                                var firstDetail: net.ssync.player.LibArtistDetail? = null
                                for (ta in topArtistsForMore) {
                                    val aid = ta.id ?: continue
                                    val det = (api.fetchArtistDetail(aid) as? MusicApiResult.Success)?.data ?: continue
                                    if (firstDetail == null) firstDetail = det
                                    val owned = det.albums.filter { it.tracksPresent > 0 || it.trackCount > 0 }.take(16)
                                    if (owned.size >= 2 && builtMore.size < 2) {
                                        builtMore += det.name to owned
                                    }
                                    if (builtMore.size >= 2 && firstDetail != null) break
                                }
                                if (builtMore.isNotEmpty()) {
                                    moreByArtistRails = builtMore
                                    HomeWarmCache.musicMoreByArtistRails = builtMore
                                }
                                // "Albums Like [Top Artist]" — same genre(s) as your top artist, excluding their own albums
                                val seed = firstDetail
                                if (seed != null) {
                                    val seedGenre = (seed.genres + seed.about?.tags.orEmpty())
                                        .map { it.trim() }
                                        .firstOrNull { it.isNotBlank() }
                                    if (seedGenre != null) {
                                        val simAlbums = (api.fetchAlbums(limit = 28, page = 1, genre = seedGenre) as? MusicApiResult.Success)
                                            ?.data.orEmpty()
                                            .filter { it.artistId != seed.id && !it.artistName.equals(seed.name, ignoreCase = true) }
                                            .distinctBy { it.id }
                                            .take(18)
                                        if (simAlbums.size >= 3) {
                                            val pair = seed.name to simAlbums
                                            similarAlbumsRail = pair
                                            HomeWarmCache.musicSimilarAlbums = pair
                                        }
                                    }
                                }
                            }
                            launch {
                                val topGenres = r30.data.genres.map { it.genre.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(2)
                                val builtGenres = mutableListOf<Pair<String, List<LibAlbum>>>()
                                for (g in topGenres) {
                                    val gAlbums = (api.fetchAlbums(limit = 20, page = 1, genre = g) as? MusicApiResult.Success)?.data.orEmpty()
                                    if (gAlbums.size >= 3) {
                                        builtGenres += g to gAlbums
                                    }
                                }
                                if (builtGenres.isNotEmpty()) {
                                    genreRails = builtGenres
                                    HomeWarmCache.musicGenreRails = builtGenres
                                }
                            }
                        }
                    }
                    launch {
                        val rAll = api.fetchListeningStats("all")
                        if (rAll is MusicApiResult.Success) {
                            statsAll = rAll.data
                            HomeWarmCache.musicStatsAll = rAll.data
                            if (recentStatsTracks.isEmpty() && rAll.data.recent.isNotEmpty()) {
                                recentStatsTracks = rAll.data.recent
                                HomeWarmCache.musicRecentTracks = rAll.data.recent
                            }
                            // Fallback More-by & Albums-Like from all-time top artists if 30d had none
                            if (moreByArtistRails.isEmpty() && rAll.data.topArtists.isNotEmpty()) {
                                val topArtistsForMore = rAll.data.topArtists.filter { it.id != null }.take(4)
                                val builtMore = mutableListOf<Pair<String, List<LibAlbum>>>()
                                var firstDetail: net.ssync.player.LibArtistDetail? = null
                                for (ta in topArtistsForMore) {
                                    val aid = ta.id ?: continue
                                    val det = (api.fetchArtistDetail(aid) as? MusicApiResult.Success)?.data ?: continue
                                    if (firstDetail == null) firstDetail = det
                                    val owned = det.albums.filter { it.tracksPresent > 0 || it.trackCount > 0 }.take(16)
                                    if (owned.size >= 2 && builtMore.size < 2) builtMore += det.name to owned
                                    if (builtMore.size >= 2 && firstDetail != null) break
                                }
                                if (builtMore.isNotEmpty()) {
                                    moreByArtistRails = builtMore
                                    HomeWarmCache.musicMoreByArtistRails = builtMore
                                }
                                if (similarAlbumsRail == null && firstDetail != null) {
                                    val seed = firstDetail!!
                                    val seedGenre = (seed.genres + seed.about?.tags.orEmpty())
                                        .map { it.trim() }
                                        .firstOrNull { it.isNotBlank() }
                                    if (seedGenre != null) {
                                        val simAlbums = (api.fetchAlbums(limit = 28, page = 1, genre = seedGenre) as? MusicApiResult.Success)
                                            ?.data.orEmpty()
                                            .filter { it.artistId != seed.id && !it.artistName.equals(seed.name, ignoreCase = true) }
                                            .distinctBy { it.id }
                                            .take(18)
                                        if (simAlbums.size >= 3) {
                                            val pair = seed.name to simAlbums
                                            similarAlbumsRail = pair
                                            HomeWarmCache.musicSimilarAlbums = pair
                                        }
                                    }
                                }
                            }
                            // Fallback genre rails from all-time listening if 30d genres were empty
                            if (genreRails.isEmpty() && rAll.data.genres.isNotEmpty()) {
                                val topGenres = rAll.data.genres.map { it.genre.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(2)
                                val builtGenres = mutableListOf<Pair<String, List<LibAlbum>>>()
                                for (g in topGenres) {
                                    val gAlbums = (api.fetchAlbums(limit = 20, page = 1, genre = g) as? MusicApiResult.Success)?.data.orEmpty()
                                    if (gAlbums.size >= 3) builtGenres += g to gAlbums
                                }
                                if (builtGenres.isNotEmpty()) {
                                    genreRails = builtGenres
                                    HomeWarmCache.musicGenreRails = builtGenres
                                }
                            }
                        }
                    }
                    launch {
                        (api.fetchRecentStatsTracks(limit = 24) as? MusicApiResult.Success)?.let {
                            if (it.data.isNotEmpty()) {
                                recentStatsTracks = it.data
                                HomeWarmCache.musicRecentTracks = it.data
                            }
                        }
                    }
                    launch {
                        (api.fetchPlaylists() as? MusicApiResult.Success)?.let {
                            playlists = it.data
                            HomeWarmCache.musicPlaylists = it.data
                        }
                    }
                    launch {
                        (api.fetchDiscoverPlaylists() as? MusicApiResult.Success)?.let {
                            discoverPlaylists = it.data
                            HomeWarmCache.discoverPlaylists = it.data
                            isLoading = false
                        }
                    }
                    launch {
                        (api.fetchSongs(limit = 100, page = 1) as? MusicApiResult.Success)?.let {
                            songs = it.data
                            HomeWarmCache.musicSongs = it.data
                            hasMoreSongs = it.data.size >= 100
                        }
                    }
                    launch {
                        (api.fetchContinue() as? MusicApiResult.Success)?.let { list ->
                            recentMusic = list.data.filter { it.itemType == "track" }
                        }
                    }
                    launch {
                        (api.fetchMusicChannels() as? MusicApiResult.Success)?.let {
                            musicStations = it.data
                            HomeWarmCache.musicStations = it.data
                        }
                    }
                }
                isLoading = false
                return@LaunchedEffect
            }
            MusicSubTab.ALBUMS -> if (albums.isEmpty()) {
                when (val r = api.fetchAlbums(limit = 80, page = 1)) {
                    is MusicApiResult.Success -> {
                        albums = r.data
                        albumsPage = 1
                        hasMoreAlbums = r.data.size >= 80
                    }
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> Unit
                }
            }
            MusicSubTab.ARTISTS -> if (artists.isEmpty()) {
                when (val r = api.fetchArtists(limit = 80, page = 1)) {
                    is MusicApiResult.Success -> {
                        artists = r.data
                        artistsPage = 1
                        hasMoreArtists = r.data.size >= 80
                    }
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> Unit
                }
            }
            MusicSubTab.SONGS -> if (songs.isEmpty()) {
                when (val r = api.fetchSongs(limit = 100, page = 1)) {
                    is MusicApiResult.Success -> {
                        songs = r.data
                        songsPage = 1
                        hasMoreSongs = r.data.size >= 100
                    }
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> Unit
                }
            }
            MusicSubTab.PLAYLISTS -> {
                coroutineScope {
                    val plDef = async { api.fetchPlaylists() }
                    val dDef = if (discoverPlaylists.isEmpty()) async { api.fetchDiscoverPlaylists() } else null
                    when (val r = plDef.await()) {
                        is MusicApiResult.Success -> playlists = r.data
                        MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                        is MusicApiResult.Error -> Unit
                    }
                    dDef?.await()?.let { r ->
                        (r as? MusicApiResult.Success)?.let { discoverPlaylists = it.data }
                    }
                }
            }
        }
        isLoading = false
    }

    val isTv = LocalIsTv.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isTv) Modifier.background(
                    Brush.radialGradient(listOf(SoulSyncColors.Accent.copy(alpha = 0.14f), Color(0xFF0A0C16))),
                ) else Modifier,
            ),
    ) {
        BrowseHeaderAndTabs(
            title = if (tab == MusicSubTab.PLAYLISTS && startOnPlaylists) "Playlists" else "Music",
            tabs = MusicSubTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { tab = it },
        )

        if (isLoading) {
            LoadingBox()
            return
        }

        Column(modifier = Modifier.fillMaxSize().padding(start = if (isTv) 62.dp else 0.dp)) {

        // Compact utility bar (count + Grid/List toggle or Play/Shuffle pills)
        val countLabel = when (tab) {
            MusicSubTab.FOR_YOU -> null
            MusicSubTab.ALBUMS -> if (albums.isEmpty()) null else "${albums.size}${if (hasMoreAlbums) "+" else ""} albums"
            MusicSubTab.ARTISTS -> if (artists.isEmpty()) null else "${artists.size}${if (hasMoreArtists) "+" else ""} artists"
            MusicSubTab.SONGS -> if (songs.isEmpty()) null else "${songs.size}${if (hasMoreSongs) "+" else ""} tracks"
            MusicSubTab.PLAYLISTS -> if (playlists.isEmpty()) null else "${playlists.size} playlists"
        }

        if (countLabel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = countLabel.uppercase(),
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                )

                when (tab) {
                    MusicSubTab.ALBUMS, MusicSubTab.ARTISTS -> {
                        ViewModeTogglePill(
                            isGrid = gridView,
                            onChange = { gridView = it },
                        )
                    }
                    MusicSubTab.SONGS -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniActionPill(
                                icon = Icons.Default.PlayArrow,
                                label = "Play",
                                primary = true,
                                onClick = {
                                    val playables = songs.toPlayables(api)
                                    if (playables.isNotEmpty()) {
                                        coordinator.playAudio(playables.first(), playables.drop(1))
                                    }
                                },
                            )
                            MiniActionPill(
                                icon = Icons.Default.Shuffle,
                                label = "Shuffle",
                                primary = false,
                                onClick = {
                                    val playables = songs.toPlayables(api).shuffled()
                                    if (playables.isNotEmpty()) {
                                        coordinator.playAudio(playables.first(), playables.drop(1))
                                    }
                                },
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        when (tab) {
            MusicSubTab.ALBUMS -> {
                if (albums.isEmpty()) {
                    EmptyBox("No albums found.")
                } else if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (net.ssync.pairing.ui.LocalIsTv.current) 176.dp else 152.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(albums, key = { it.id }) { album ->
                            AlbumGridTile(
                                title = album.title,
                                subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
                                artworkUrl = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                    ?: api.artwork.albumArtwork(album.id),
                                tracksPresent = album.tracksPresent,
                                trackCount = album.trackCount,
                                onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                            )
                        }
                        if (hasMoreAlbums) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadMorePill(
                                    label = if (loadingMore) "Loading…" else "Load more albums",
                                    enabled = !loadingMore,
                                    onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            val next = albumsPage + 1
                                            (api.fetchAlbums(limit = 80, page = next) as? MusicApiResult.Success)?.let { r ->
                                                albums = (albums + r.data).distinctBy { it.id }
                                                albumsPage = next
                                                hasMoreAlbums = r.data.size >= 80
                                            }
                                            loadingMore = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                    ) {
                        itemsIndexed(albums, key = { _, a -> a.id }) { idx, album ->
                            AlbumListRow(
                                album = album,
                                artworkUrl = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                    ?: api.artwork.albumArtwork(album.id),
                                showDivider = idx < albums.lastIndex,
                                onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                            )
                        }
                        if (hasMoreAlbums) {
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    LoadMorePill(
                                        label = if (loadingMore) "Loading…" else "Load more albums",
                                        enabled = !loadingMore,
                                        onClick = {
                                            loadingMore = true
                                            scope.launch {
                                                val next = albumsPage + 1
                                                (api.fetchAlbums(limit = 80, page = next) as? MusicApiResult.Success)?.let { r ->
                                                    albums = (albums + r.data).distinctBy { it.id }
                                                    albumsPage = next
                                                    hasMoreAlbums = r.data.size >= 80
                                                }
                                                loadingMore = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MusicSubTab.ARTISTS -> {
                if (artists.isEmpty()) {
                    EmptyBox("No artists found.")
                } else if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (net.ssync.pairing.ui.LocalIsTv.current) 144.dp else 108.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(artists, key = { it.id }) { artist ->
                            ArtistCircleTile(
                                name = artist.name,
                                subtitle = formatArtistMeta(artist.albumCount, artist.trackCount),
                                artworkUrl = api.artwork.resolve(artist.imageUrl ?: artist.remoteImageUrl),
                                onClick = { onNavigate(PlayerDestination.ArtistDetail(artist.id)) },
                            )
                        }
                        if (hasMoreArtists) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                LoadMorePill(
                                    label = if (loadingMore) "Loading…" else "Load more artists",
                                    enabled = !loadingMore,
                                    onClick = {
                                        loadingMore = true
                                        scope.launch {
                                            val next = artistsPage + 1
                                            (api.fetchArtists(limit = 80, page = next) as? MusicApiResult.Success)?.let { r ->
                                                artists = (artists + r.data).distinctBy { it.id }
                                                artistsPage = next
                                                hasMoreArtists = r.data.size >= 80
                                            }
                                            loadingMore = false
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp),
                    ) {
                        itemsIndexed(artists, key = { _, a -> a.id }) { idx, artist ->
                            ArtistListRow(
                                artist = artist,
                                artworkUrl = api.artwork.resolve(artist.imageUrl ?: artist.remoteImageUrl),
                                showDivider = idx < artists.lastIndex,
                                onClick = { onNavigate(PlayerDestination.ArtistDetail(artist.id)) },
                            )
                        }
                        if (hasMoreArtists) {
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    LoadMorePill(
                                        label = if (loadingMore) "Loading…" else "Load more artists",
                                        enabled = !loadingMore,
                                        onClick = {
                                            loadingMore = true
                                            scope.launch {
                                                val next = artistsPage + 1
                                                (api.fetchArtists(limit = 80, page = next) as? MusicApiResult.Success)?.let { r ->
                                                    artists = (artists + r.data).distinctBy { it.id }
                                                    artistsPage = next
                                                    hasMoreArtists = r.data.size >= 80
                                                }
                                                loadingMore = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MusicSubTab.SONGS -> {
                if (songs.isEmpty()) {
                    EmptyBox("No tracks found.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
                    ) {
                        itemsIndexed(songs, key = { _, s -> s.id }) { idx, song ->
                            val isPlayingThis = activeTrackId == song.id.toString()
                            val songRef = SongRefPayload(
                                title = song.title,
                                artist = song.artistName ?: "Unknown Artist",
                                album = song.albumTitle,
                                durationMs = song.duration,
                                lib2TrackId = song.id,
                            )
                            val playable = ActivePlayable(
                                itemType = "track",
                                itemId = song.id.toString(),
                                title = song.title,
                                subtitle = song.artistName ?: "Unknown Artist",
                                artworkUrl = api.artwork.albumArtwork(song.albumId),
                                streamUrl = api.streamUrl("track", song.id.toString()),
                            )
                            MusicTrackRow(
                                title = song.title,
                                subtitle = listOfNotNull(song.artistName, song.albumTitle).joinToString(" · "),
                                artworkUrl = api.artwork.albumArtwork(song.albumId),
                                duration = formatTrackDuration(song.duration),
                                isActive = isPlayingThis,
                                showDivider = idx < songs.lastIndex,
                                contextItem = playable.toContextItem(songRef),
                                trailingActions = {
                                    TrackRowActionIcons(
                                        song = songRef,
                                        playable = playable,
                                        likedState = likedState,
                                    )
                                },
                                onClick = {
                                    val playables = songs.drop(idx).toPlayables(api)
                                    if (playables.isNotEmpty()) {
                                        coordinator.playAudio(playables.first(), playables.drop(1))
                                    }
                                },
                            )
                        }
                        if (hasMoreSongs) {
                            item {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    LoadMorePill(
                                        label = if (loadingMore) "Loading…" else "Load more tracks",
                                        enabled = !loadingMore,
                                        onClick = {
                                            loadingMore = true
                                            scope.launch {
                                                val next = songsPage + 1
                                                (api.fetchSongs(limit = 100, page = next) as? MusicApiResult.Success)?.let { r ->
                                                    songs = (songs + r.data).distinctBy { it.id }
                                                    songsPage = next
                                                    hasMoreSongs = r.data.size >= 100
                                                }
                                                loadingMore = false
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MusicSubTab.PLAYLISTS -> {
                if (playlists.isEmpty() && discoverPlaylists.isEmpty()) {
                    EmptyBox("No playlists yet.")
                } else {
                    val groupedDiscover = remember(discoverPlaylists) {
                        discoverPlaylists.groupBy { it.sectionLabel.ifBlank { "SoulSync Discovery" } }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        groupedDiscover.forEach { (secLabel, itemsInSec) ->
                            item(key = "disc-sec-$secLabel") {
                                MediaRail(
                                    title = "$secLabel (${itemsInSec.size})",
                                ) {
                                    items(itemsInSec, key = { "pl-disc-${it.key}" }) { dp ->
                                        val tiles = dp.coverTiles.mapNotNull { api.artwork.resolve(it) }
                                        AlbumGridTile(
                                            title = dp.title,
                                            subtitle = dp.subtitle ?: "${dp.trackCount} tracks",
                                            artworkUrl = api.artwork.resolve(dp.coverUrl),
                                            mosaicUrls = tiles,
                                            tracksPresent = 0,
                                            trackCount = 0,
                                            modifier = Modifier.width(142.dp),
                                            // its own page: play, shuffle or download it
                                            onClick = { onNavigate(PlayerDestination.DiscoverPlaylistDetail(dp.key, dp.title)) },
                                        )
                                    }
                                }
                            }
                        }
                        if (playlists.isNotEmpty()) {
                            item(key = "your-playlists-head") {
                                Text(
                                    text = "Your Playlists & Mirrored (${playlists.size})",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            itemsIndexed(playlists, key = { _, p -> p.idString }) { idx, pl ->
                                PlaylistListRow(
                                    playlist = pl,
                                    artworkUrl = api.artwork.resolve(pl.imageUrl),
                                    mosaicUrls = pl.coverTiles.mapNotNull { api.artwork.resolve(it) },
                                    showDivider = idx < playlists.lastIndex,
                                    onClick = {
                                        pl.idString.toIntOrNull()?.let {
                                            onNavigate(PlayerDestination.PlaylistDetail(it))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            MusicSubTab.FOR_YOU -> {
                // Compute era rail off the loaded albums (e.g. Top Albums of the 2000s / 2010s / 90s)
                val eraRail = remember(albums) {
                    val byDecade = albums
                        .filter { (it.year ?: 0) in 1960..2029 }
                        .groupBy { ((it.year ?: 2000) / 10) * 10 }
                        .filterValues { it.size >= 4 }
                    val best = byDecade.entries.maxByOrNull { it.value.size }
                    if (best != null) {
                        val label = if (best.key >= 2000) "Top Albums of the ${best.key}s" else "Top Albums of the ${best.key % 100}s"
                        label to best.value.take(18)
                    } else null
                }
                val dedupedRecent = remember(recentStatsTracks) {
                    recentStatsTracks
                        .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                        .take(20)
                }
                val topAlbumsMonth = remember(stats30d) { stats30d.topAlbums.take(18) }
                val topTracksMonth = remember(stats30d) { stats30d.topTracks.take(20) }
                val topAlbumsAllTime = remember(statsAll, topAlbumsMonth) {
                    val monthIds = topAlbumsMonth.mapNotNull { it.id }.toSet()
                    statsAll.topAlbums.filter { it.id == null || it.id !in monthIds }.ifEmpty { statsAll.topAlbums }.take(18)
                }

                // each row of the page as its own piece, so the page's layout (the same one
                // the web player's Music page draws from) decides which show and in what order
                val recentRows: LazyListScope.() -> Unit = {
                    // 1. Recently Played (REAL listening history from /api/stats/recent + in-app resume!)
                    if (dedupedRecent.isNotEmpty() || recentMusic.isNotEmpty()) {
                        item(key = "music-recently-played") {
                            MediaRail(title = "Recently Played") {
                                if (dedupedRecent.isNotEmpty()) {
                                    itemsIndexed(dedupedRecent, key = { idx, t -> "srp-$idx-${t.title}-${t.artist}" }) { _, t ->
                                        val art = api.artwork.resolve(t.imageUrl)
                                        SquareMediaCard(
                                            title = t.title,
                                            subtitle = listOfNotNull(t.artist.takeIf { it.isNotBlank() }, t.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            artworkUrl = art,
                                            fallbackIcon = Icons.Default.MusicNote,
                                            width = 148.dp,
                                            onClick = {
                                                scope.launch {
                                                    val resolved = (api.resolveStatsTrack(t.title, t.artist) as? MusicApiResult.Success)?.data
                                                    if (resolved != null) {
                                                        coordinator.playAudio(
                                                            ActivePlayable(
                                                                itemType = "track",
                                                                itemId = resolved.id.toString(),
                                                                title = resolved.title,
                                                                subtitle = resolved.artistName ?: t.artist,
                                                                artworkUrl = api.artwork.resolve(resolved.imageUrl) ?: api.artwork.albumArtwork(resolved.albumId) ?: art,
                                                                streamUrl = api.streamUrl("track", resolved.id.toString()),
                                                            ),
                                                        )
                                                    } else if (t.artistDbId != null) {
                                                        onNavigate(PlayerDestination.ArtistDetail(t.artistDbId!!))
                                                    }
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    items(recentMusic.take(18), key = { "rp-${it.itemId}" }) { row ->
                                        val art = api.artwork.resolve(row.meta?.artwork ?: row.meta?.backdrop)
                                        SquareMediaCard(
                                            title = row.meta?.title ?: "Track #${row.itemId}",
                                            subtitle = row.meta?.subtitle,
                                            artworkUrl = art,
                                            fallbackIcon = Icons.Default.MusicNote,
                                            width = 148.dp,
                                            onClick = {
                                                coordinator.playAudio(
                                                    ActivePlayable(
                                                        itemType = "track",
                                                        itemId = row.itemId,
                                                        title = row.meta?.title ?: "Track",
                                                        subtitle = row.meta?.subtitle.orEmpty(),
                                                        artworkUrl = art,
                                                        streamUrl = api.streamUrl("track", row.itemId),
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val topAlbumsRows: LazyListScope.() -> Unit = {
                    // 2. Most Played Last Month · Albums (REAL 30-day listening stats!)
                    if (topAlbumsMonth.isNotEmpty()) {
                        item(key = "music-top-albums-month") {
                            MediaRail(title = "Most Played Last Month") {
                                itemsIndexed(topAlbumsMonth, key = { idx, al -> "tam-$idx-${al.id ?: al.name}" }) { _, al ->
                                    val art = api.artwork.resolve(al.imageUrl) ?: al.id?.let { api.artwork.albumArtwork(it) }
                                    val sub = buildString {
                                        if (al.artist.isNotBlank()) append(al.artist)
                                        if (al.playCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${al.playCount} plays")
                                        }
                                    }
                                    AlbumGridTile(
                                        title = al.name,
                                        subtitle = sub,
                                        artworkUrl = art,
                                        tracksPresent = 0,
                                        trackCount = 0,
                                        modifier = Modifier.width(148.dp),
                                        onClick = {
                                            when {
                                                al.id != null -> onNavigate(PlayerDestination.AlbumDetail(al.id!!))
                                                al.artistId != null -> onNavigate(PlayerDestination.ArtistDetail(al.artistId!!))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val topTracksRows: LazyListScope.() -> Unit = {
                    // 3. Heavy Rotation · Most Played Tracks (REAL 30-day top tracks with play counts!)
                    if (topTracksMonth.isNotEmpty()) {
                        item(key = "music-heavy-rotation-tracks") {
                            MediaRail(title = "Heavy Rotation · Last 30 Days") {
                                itemsIndexed(topTracksMonth, key = { idx, tr -> "hrt-$idx-${tr.id ?: tr.name}" }) { idx, tr ->
                                    val art = api.artwork.resolve(tr.imageUrl)
                                    val sub = buildString {
                                        if (tr.artist.isNotBlank()) append(tr.artist)
                                        if (tr.playCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${tr.playCount} plays")
                                        }
                                    }
                                    SquareMediaCard(
                                        title = tr.name,
                                        subtitle = sub,
                                        artworkUrl = art,
                                        fallbackIcon = Icons.Default.MusicNote,
                                        width = 148.dp,
                                        onClick = {
                                            scope.launch {
                                                val directId = tr.id
                                                if (directId != null) {
                                                    val playable = ActivePlayable(
                                                        itemType = "track",
                                                        itemId = directId.toString(),
                                                        title = tr.name,
                                                        subtitle = tr.artist,
                                                        artworkUrl = art,
                                                        streamUrl = api.streamUrl("track", directId.toString()),
                                                    )
                                                    val rest = topTracksMonth.drop(idx + 1).mapNotNull { nxt ->
                                                        val nid = nxt.id ?: return@mapNotNull null
                                                        ActivePlayable(
                                                            itemType = "track",
                                                            itemId = nid.toString(),
                                                            title = nxt.name,
                                                            subtitle = nxt.artist,
                                                            artworkUrl = api.artwork.resolve(nxt.imageUrl),
                                                            streamUrl = api.streamUrl("track", nid.toString()),
                                                        )
                                                    }
                                                    coordinator.playAudio(playable, rest)
                                                } else {
                                                    val resolved = (api.resolveStatsTrack(tr.name, tr.artist) as? MusicApiResult.Success)?.data
                                                    if (resolved != null) {
                                                        coordinator.playAudio(
                                                            ActivePlayable(
                                                                itemType = "track",
                                                                itemId = resolved.id.toString(),
                                                                title = resolved.title,
                                                                subtitle = resolved.artistName ?: tr.artist,
                                                                artworkUrl = api.artwork.resolve(resolved.imageUrl) ?: api.artwork.albumArtwork(resolved.albumId) ?: art,
                                                                streamUrl = api.streamUrl("track", resolved.id.toString()),
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val recentAlbumsRows: LazyListScope.() -> Unit = {
                    // 4. Recently Added Albums
                    if (albums.isNotEmpty()) {
                        item(key = "music-recently-added") {
                            MediaRail(
                                title = "Recently Added Albums",
                                actionLabel = "All albums",
                                onAction = { tab = MusicSubTab.ALBUMS },
                            ) {
                                items(albums.take(18), key = { "fy-al-top-${it.id}" }) { album ->
                                    val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(album.id)
                                    AlbumGridTile(
                                        title = album.title,
                                        subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
                                        artworkUrl = alArt,
                                        tracksPresent = album.tracksPresent,
                                        trackCount = album.trackCount,
                                        modifier = Modifier.width(148.dp),
                                        contextItem = MediaContextItem(
                                            itemType = "album",
                                            itemId = album.id.toString(),
                                            title = album.title,
                                            subtitle = album.artistName.orEmpty(),
                                            artworkUrl = alArt,
                                            isVideo = false,
                                            onOpenDetails = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                        ),
                                        onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val moreByRows: LazyListScope.() -> Unit = {
                    // 5. More by [Top Listened Artists] (owned albums from artists you actually play!)
                    moreByArtistRails.forEach { (artistName, artistAlbums) ->
                        item(key = "music-more-by-$artistName") {
                            MediaRail(title = "More by $artistName") {
                                items(artistAlbums, key = { "mba-$artistName-${it.id}" }) { album ->
                                    val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(album.id)
                                    AlbumGridTile(
                                        title = album.title,
                                        subtitle = listOfNotNull(album.year?.toString(), album.albumType?.replaceFirstChar { it.uppercase() }).joinToString(" · "),
                                        artworkUrl = alArt,
                                        tracksPresent = album.tracksPresent,
                                        trackCount = album.trackCount,
                                        modifier = Modifier.width(148.dp),
                                        onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val albumsLikeRows: LazyListScope.() -> Unit = {
                    // 6. Albums Like [Top Artist] (from similar artists owned in your library!)
                    similarAlbumsRail?.let { (seedArtist, simAlbums) ->
                        item(key = "music-albums-like-$seedArtist") {
                            MediaRail(title = "Albums Like $seedArtist") {
                                items(simAlbums, key = { "sim-${it.id}" }) { album ->
                                    val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(album.id)
                                    AlbumGridTile(
                                        title = album.title,
                                        subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
                                        artworkUrl = alArt,
                                        tracksPresent = album.tracksPresent,
                                        trackCount = album.trackCount,
                                        modifier = Modifier.width(148.dp),
                                        onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val genresRows: LazyListScope.() -> Unit = {
                    // 7. More in [Genres You've Been Listening To]
                    genreRails.forEach { (genreName, gAlbums) ->
                        item(key = "music-genre-$genreName") {
                            MediaRail(title = "More in $genreName") {
                                items(gAlbums, key = { "mg-$genreName-${it.id}" }) { album ->
                                    val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(album.id)
                                    AlbumGridTile(
                                        title = album.title,
                                        subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
                                        artworkUrl = alArt,
                                        tracksPresent = album.tracksPresent,
                                        trackCount = album.trackCount,
                                        modifier = Modifier.width(148.dp),
                                        onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val eraRows: LazyListScope.() -> Unit = {
                    // 8. Top Albums of the [Decade]s
                    eraRail?.let { (eraTitle, eraAlbums) ->
                        item(key = "music-era-$eraTitle") {
                            MediaRail(title = eraTitle) {
                                items(eraAlbums, key = { "era-${it.id}" }) { album ->
                                    val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                                        ?: api.artwork.albumArtwork(album.id)
                                    AlbumGridTile(
                                        title = album.title,
                                        subtitle = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · "),
                                        artworkUrl = alArt,
                                        tracksPresent = album.tracksPresent,
                                        trackCount = album.trackCount,
                                        modifier = Modifier.width(148.dp),
                                        onClick = { onNavigate(PlayerDestination.AlbumDetail(album.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val allTimeRows: LazyListScope.() -> Unit = {
                    // 9. Most Played Albums · All Time
                    if (topAlbumsAllTime.isNotEmpty()) {
                        item(key = "music-most-played-alltime") {
                            MediaRail(title = "Most Played · All Time") {
                                itemsIndexed(topAlbumsAllTime, key = { idx, al -> "taa-$idx-${al.id ?: al.name}" }) { _, al ->
                                    val art = api.artwork.resolve(al.imageUrl) ?: al.id?.let { api.artwork.albumArtwork(it) }
                                    val sub = buildString {
                                        if (al.artist.isNotBlank()) append(al.artist)
                                        if (al.playCount > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append("${al.playCount} plays")
                                        }
                                    }
                                    AlbumGridTile(
                                        title = al.name,
                                        subtitle = sub,
                                        artworkUrl = art,
                                        tracksPresent = 0,
                                        trackCount = 0,
                                        modifier = Modifier.width(148.dp),
                                        onClick = {
                                            when {
                                                al.id != null -> onNavigate(PlayerDestination.AlbumDetail(al.id!!))
                                                al.artistId != null -> onNavigate(PlayerDestination.ArtistDetail(al.artistId!!))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val stationsRows: LazyListScope.() -> Unit = {
                    // 10. Stations & Artist Mixes (Redesigned Cinema wide radio cards!)
                    item(key = "music-stations-mixes") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Stations & Artist Mixes",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item(key = "station-shuffle") {
                                    val shuffleTiles = albums.take(4)
                                        .mapNotNull { api.artwork.resolve(it.imageUrl ?: it.remoteImageUrl) ?: api.artwork.albumArtwork(it.id) }
                                    ArtistMixCard(
                                        badge = "LIBRARY RADIO",
                                        title = "Library Shuffle",
                                        subtitle = "Endless mix from your collection",
                                        tiles = shuffleTiles,
                                        accentTint = true,
                                        vibeTint = false,
                                        onClick = {
                                            val shuffled = songs.toPlayables(api).shuffled()
                                            if (shuffled.isNotEmpty()) {
                                                coordinator.playAudio(shuffled.first(), shuffled.drop(1))
                                            }
                                        },
                                    )
                                }
                                items(musicStations.take(12), key = { it.id }) { st ->
                                    val isVibe = st.kind.equals("vibe", ignoreCase = true)
                                    val distinctArtists = st.trackItems
                                        .map { it.artist.trim() }
                                        .filter { it.isNotBlank() }
                                        .distinctBy { it.lowercase() }
                                        .take(3)
                                    val mosaicUrls = st.trackItems
                                        .mapNotNull { it.albumId }
                                        .distinct()
                                        .take(4)
                                        .mapNotNull { api.artwork.albumArtwork(it) }
                                    val headline = if (distinctArtists.size >= 2) {
                                        "${distinctArtists.take(2).joinToString(", ")} & More"
                                    } else {
                                        st.name
                                    }
                                    ArtistMixCard(
                                        badge = if (isVibe) "${st.name.uppercase()} VIBE" else "${st.name.uppercase()} RADIO",
                                        title = headline,
                                        subtitle = "${st.name} · ${st.count} songs",
                                        tiles = mosaicUrls,
                                        accentTint = false,
                                        vibeTint = isVibe,
                                        onClick = {
                                            val queue = st.trackItems.shuffled().take(100).map { t ->
                                                ActivePlayable(
                                                    itemType = "track",
                                                    itemId = t.id.toString(),
                                                    title = t.title,
                                                    subtitle = t.artist,
                                                    artworkUrl = api.artwork.albumArtwork(t.albumId),
                                                    streamUrl = api.streamUrl("track", t.id.toString()),
                                                )
                                            }
                                            if (queue.isNotEmpty()) coordinator.playAudio(queue.first(), queue.drop(1))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val discoveryRows: LazyListScope.() -> Unit = {
                    // 11. Made for you (Discover mixes)
                    if (discoverPlaylists.isNotEmpty()) {
                        item(key = "music-discover-playlists") {
                            MediaRail(
                                title = "Made for You · SoulSync Discovery",
                                actionLabel = "All ${discoverPlaylists.size}",
                                onAction = { tab = MusicSubTab.PLAYLISTS },
                            ) {
                                items(discoverPlaylists.take(24), key = { it.key }) { dp ->
                                    val tiles = dp.coverTiles.mapNotNull { api.artwork.resolve(it) }
                                    AlbumGridTile(
                                        title = dp.title,
                                        subtitle = dp.subtitle ?: "${dp.trackCount} tracks",
                                        artworkUrl = api.artwork.resolve(dp.coverUrl),
                                        mosaicUrls = tiles,
                                        tracksPresent = 0,
                                        trackCount = 0,
                                        modifier = Modifier.width(148.dp),
                                        // its own page: play, shuffle or download it
                                        onClick = { onNavigate(PlayerDestination.DiscoverPlaylistDetail(dp.key, dp.title)) },
                                    )
                                }
                            }
                        }
                    }
                }
                val playlistsRows: LazyListScope.() -> Unit = {
                    // 12. Your playlists
                    if (playlists.isNotEmpty()) {
                        item(key = "music-your-playlists") {
                            MediaRail(title = "Your Playlists") {
                                items(playlists.take(14), key = { "fy-pl-${it.idString}" }) { pl ->
                                    val tiles = pl.coverTiles.mapNotNull { api.artwork.resolve(it) }
                                    AlbumGridTile(
                                        title = pl.name,
                                        subtitle = "${pl.trackCount} tracks",
                                        artworkUrl = api.artwork.resolve(pl.imageUrl),
                                        mosaicUrls = tiles,
                                        tracksPresent = 0,
                                        trackCount = 0,
                                        modifier = Modifier.width(148.dp),
                                        onClick = {
                                            pl.idString.toIntOrNull()?.let { pid ->
                                                onNavigate(PlayerDestination.PlaylistDetail(pid))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                val sections: Map<String, List<LazyListScope.() -> Unit>> = mapOf(
                    "recently-played" to listOf(recentRows),
                    "stations" to listOf(stationsRows),
                    "discovery" to listOf(discoveryRows),
                    "heavy-rotation" to listOf(topTracksRows),
                    "recent-albums" to listOf(recentAlbumsRows),
                    "artists" to listOf(moreByRows, albumsLikeRows),
                    "playlists" to listOf(playlistsRows),
                    "top-albums" to listOf(topAlbumsRows),
                    "top-tracks" to listOf(topTracksRows),
                    "all-time-artists" to listOf(allTimeRows),
                )
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // a piece two rows share (heavy rotation and most played tracks) draws once
                    val drawn = mutableSetOf<LazyListScope.() -> Unit>()
                    pageRows.rows.forEach { row ->
                        sections[row.id]?.forEach { piece -> if (drawn.add(piece)) piece() }
                    }
                    // from your library, after the rows you arranged
                    genresRows()
                    eraRows()
                    item(key = "customize") { CustomizePageButton { pageRows.editing = true } }
                }
                PageRowsEditor(pageRows, "Music")
            }
        }
        }
    }
}

private fun List<LibSong>.toPlayables(api: PlayerApiClient): List<ActivePlayable> = map { s ->
    ActivePlayable(
        itemType = "track",
        itemId = s.id.toString(),
        title = s.title,
        subtitle = s.artistName ?: "Unknown Artist",
        artworkUrl = api.artwork.albumArtwork(s.albumId),
        streamUrl = api.streamUrl("track", s.id.toString()),
    )
}

private fun formatArtistMeta(albumCount: Int, trackCount: Int): String = when {
    albumCount > 0 && trackCount > 0 -> "$albumCount ${if (albumCount == 1) "album" else "albums"} · $trackCount tracks"
    albumCount > 0 -> "$albumCount ${if (albumCount == 1) "album" else "albums"}"
    trackCount > 0 -> "$trackCount tracks"
    else -> "Artist"
}

// ── Music Hub Reusable Components (Plexamp / Apple Music Grade) ──────────────

@Composable
private fun ViewModeTogglePill(
    isGrid: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = SoulSyncColors.Card,
        border = BorderStroke(1.dp, SoulSyncColors.Line),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isGrid) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onChange(true) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.GridView,
                    contentDescription = "Grid view",
                    tint = if (isGrid) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (!isGrid) SoulSyncColors.Accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onChange(false) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = "List view",
                    tint = if (!isGrid) SoulSyncColors.AccentLight else SoulSyncColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniActionPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (primary) SoulSyncColors.Accent else SoulSyncColors.Card,
        border = if (primary) null else BorderStroke(1.dp, SoulSyncColors.Line),
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (primary) SoulSyncColors.OnAccent else SoulSyncColors.TextPrimary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                color = if (primary) SoulSyncColors.OnAccent else SoulSyncColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadMorePill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, SoulSyncColors.Line),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
    ) {
        Text(label, color = SoulSyncColors.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MosaicArtwork(
    primaryUrl: String?,
    mosaicUrls: List<String>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    if (!primaryUrl.isNullOrBlank()) {
        SoulSyncArtwork(
            url = primaryUrl,
            contentDescription = contentDescription,
            fallbackIcon = Icons.Default.MusicNote,
            modifier = modifier,
        )
    } else if (mosaicUrls.size >= 4) {
        Column(modifier = modifier) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SoulSyncArtwork(url = mosaicUrls[0], contentDescription = null, fallbackIcon = Icons.Default.MusicNote, modifier = Modifier.weight(1f).fillMaxSize())
                SoulSyncArtwork(url = mosaicUrls[1], contentDescription = null, fallbackIcon = Icons.Default.MusicNote, modifier = Modifier.weight(1f).fillMaxSize())
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SoulSyncArtwork(url = mosaicUrls[2], contentDescription = null, fallbackIcon = Icons.Default.MusicNote, modifier = Modifier.weight(1f).fillMaxSize())
                SoulSyncArtwork(url = mosaicUrls[3], contentDescription = null, fallbackIcon = Icons.Default.MusicNote, modifier = Modifier.weight(1f).fillMaxSize())
            }
        }
    } else {
        SoulSyncArtwork(
            url = mosaicUrls.firstOrNull(),
            contentDescription = contentDescription,
            fallbackIcon = Icons.Default.MusicNote,
            modifier = modifier,
        )
    }
}

@Composable
private fun ArtistMixCard(
    badge: String,
    title: String,
    subtitle: String,
    tiles: List<String>,
    accentTint: Boolean,
    vibeTint: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        accentTint -> SoulSyncColors.Accent
        vibeTint -> Color(0xFFA855F7)
        else -> Color(0xFF38BDF8)
    }
    val badgeBg = accent.copy(alpha = 0.24f)
    val badgeText = when {
        vibeTint -> Color(0xFFE9D5FF)
        accentTint -> SoulSyncColors.AccentLight
        else -> Color(0xFFBAE6FD)
    }
    Surface(
        modifier = Modifier
            .width(268.dp)
            .height(116.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .dpadFocusable(RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF131726),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.34f)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle ambient artwork backdrop glow
            if (tiles.isNotEmpty()) {
                SoulSyncArtwork(
                    url = tiles.first(),
                    contentDescription = null,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xEB0E111D),
                                Color(0xDC131728),
                                accent.copy(alpha = 0.22f),
                            ),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SoulSyncColors.CardRaised)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                ) {
                    MosaicArtwork(
                        primaryUrl = null,
                        mosaicUrls = tiles,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.78f))
                            .border(1.dp, Color.White.copy(alpha = 0.30f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play station",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(badgeBg)
                            .border(0.75.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = badge,
                            color = badgeText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        lineHeight = 17.sp,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumGridTile(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    tracksPresent: Int,
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    mosaicUrls: List<String> = emptyList(),
    contextItem: MediaContextItem? = null,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
    val isIncomplete = trackCount > 0 && tracksPresent in 1 until trackCount
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(SoulSyncColors.CardRaised)
                .border(0.75.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(12.dp)),
        ) {
            MosaicArtwork(
                primaryUrl = artworkUrl,
                mosaicUrls = mosaicUrls,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
            )
            if (isIncomplete) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    border = BorderStroke(0.5.dp, SoulSyncColors.HiRes.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp),
                ) {
                    Text(
                        text = "$tracksPresent/$trackCount",
                        color = SoulSyncColors.HiRes,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            color = SoulSyncColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = SoulSyncColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AlbumListRow(
    album: LibAlbum,
    artworkUrl: String?,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val isIncomplete = album.trackCount > 0 && album.tracksPresent in 1 until album.trackCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SoulSyncColors.CardRaised)
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            ) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = album.title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    color = SoulSyncColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val meta = listOfNotNull(album.artistName, album.year?.toString()).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isIncomplete) {
                Text(
                    text = "${album.tracksPresent}/${album.trackCount}",
                    color = SoulSyncColors.HiRes,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 6.dp),
                )
            } else if (album.trackCount > 0) {
                Text(
                    text = "${album.trackCount} tracks",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SoulSyncColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 86.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = SoulSyncColors.Line.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ArtistCircleTile(
    name: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(SoulSyncColors.CardRaised)
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
        ) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = name,
                fallbackIcon = Icons.Default.Person,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = name,
            color = SoulSyncColors.TextPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            color = SoulSyncColors.TextSecondary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistListRow(
    artist: LibArtist,
    artworkUrl: String?,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(SoulSyncColors.CardRaised)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            ) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = artist.name,
                    fallbackIcon = Icons.Default.Person,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    color = SoulSyncColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val sub = buildList {
                    add(formatArtistMeta(artist.albumCount, artist.trackCount))
                    artist.genres.firstOrNull()?.let { add(it) }
                }.joinToString(" · ")
                Text(
                    text = sub,
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SoulSyncColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = SoulSyncColors.Line.copy(alpha = 0.55f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MusicTrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    duration: String?,
    isActive: Boolean,
    showDivider: Boolean,
    playable: Boolean = true,
    contextItem: MediaContextItem? = null,
    trailingActions: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) SoulSyncColors.Accent.copy(alpha = 0.09f) else Color.Transparent)
            .combinedClickable(
                enabled = playable,
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .alpha(if (playable) 1f else 0.45f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SoulSyncColors.CardRaised)
                    .border(
                        width = if (isActive) 1.5.dp else 0.5.dp,
                        color = if (isActive) SoulSyncColors.Accent else Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Playing",
                            tint = SoulSyncColors.AccentLight,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (playable) 1f else 0.48f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        color = when {
                            isActive -> SoulSyncColors.AccentLight
                            playable -> SoulSyncColors.TextPrimary
                            else -> SoulSyncColors.TextSecondary
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!playable) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                        ) {
                            Text(
                                text = "MISSING",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!duration.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = duration,
                    color = SoulSyncColors.TextSecondary.copy(alpha = if (playable) 1f else 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (playable && trailingActions != null) {
                Spacer(modifier = Modifier.width(4.dp))
                trailingActions()
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = SoulSyncColors.Line.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun PlaylistListRow(
    playlist: MusicPlaylist,
    artworkUrl: String?,
    showDivider: Boolean,
    onClick: () -> Unit,
    mosaicUrls: List<String> = emptyList(),
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SoulSyncColors.CardRaised)
                    .border(0.75.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
            ) {
                MosaicArtwork(
                    primaryUrl = artworkUrl,
                    mosaicUrls = mosaicUrls,
                    contentDescription = playlist.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    color = SoulSyncColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${playlist.trackCount} tracks",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.5.sp,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SoulSyncColors.TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 84.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = SoulSyncColors.Line.copy(alpha = 0.5f),
            )
        }
    }
}

// ── Artist Detail Screen (Editorial Cinema Hero + Organized Discography) ─────

@Composable
fun ArtistDetailScreen(
    artistId: Int,
    api: PlayerApiClient,
    onAlbumClick: (Int) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var artist by remember { mutableStateOf<LibArtistDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var bioExpanded by remember { mutableStateOf(false) }

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

    val heroUrl = api.artwork.resolve(a.imageUrl ?: a.remoteImageUrl)
    val bio = a.about?.bio ?: a.summary
    val totalReleases = a.albums.size + a.eps.size + a.singles.size

    LazyColumn(
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Full-bleed atmospheric artist hero
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(276.dp),
            ) {
                if (heroUrl != null) {
                    AsyncImage(
                        model = heroUrl,
                        imageLoader = LocalSoulSyncImageLoader.current,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.32f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.45f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.72f),
                                    SoulSyncColors.BgPrimary,
                                ),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .clip(CircleShape)
                            .background(SoulSyncColors.CardRaised)
                            .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape),
                    ) {
                        SoulSyncArtwork(
                            url = heroUrl,
                            contentDescription = a.name,
                            fallbackIcon = Icons.Default.Person,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = a.name,
                        color = SoulSyncColors.TextPrimary,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val metaLine = buildList {
                        if (totalReleases > 0) add("$totalReleases ${if (totalReleases == 1) "release" else "releases"}")
                        addAll(a.genres.take(3))
                    }.joinToString(" · ")
                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (!bio.isNullOrBlank()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clickable { bioExpanded = !bioExpanded },
                ) {
                    Text(
                        text = bio,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 20.sp,
                        maxLines = if (bioExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bio.length > 140) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (bioExpanded) "Show less" else "Read more",
                            color = SoulSyncColors.AccentLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        if (a.albums.isNotEmpty()) {
            item {
                DiscographyRailSection(
                    title = "Albums",
                    releases = a.albums,
                    api = api,
                    onAlbumClick = onAlbumClick,
                )
            }
        }

        if (a.eps.isNotEmpty()) {
            item {
                DiscographyRailSection(
                    title = "EPs",
                    releases = a.eps,
                    api = api,
                    onAlbumClick = onAlbumClick,
                )
            }
        }

        if (a.singles.isNotEmpty()) {
            item {
                DiscographyRailSection(
                    title = "Singles",
                    releases = a.singles,
                    api = api,
                    onAlbumClick = onAlbumClick,
                )
            }
        }
    }
}

@Composable
private fun DiscographyRailSection(
    title: String,
    releases: List<LibAlbum>,
    api: PlayerApiClient,
    onAlbumClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                color = SoulSyncColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${releases.size}",
                color = SoulSyncColors.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(releases, key = { it.id }) { album ->
                val alArt = api.artwork.resolve(album.imageUrl ?: album.remoteImageUrl)
                    ?: api.artwork.albumArtwork(album.id)
                AlbumGridTile(
                    title = album.title,
                    subtitle = listOfNotNull(
                        album.year?.toString(),
                        if (album.trackCount > 0) "${album.trackCount} tracks" else null,
                    ).joinToString(" · "),
                    artworkUrl = alArt,
                    tracksPresent = album.tracksPresent,
                    trackCount = album.trackCount,
                    contextItem = MediaContextItem(
                        itemType = "album",
                        itemId = album.id.toString(),
                        title = album.title,
                        subtitle = album.artistName.orEmpty(),
                        artworkUrl = alArt,
                        isVideo = false,
                        onOpenDetails = { onAlbumClick(album.id) },
                    ),
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.width(148.dp),
                )
            }
        }
    }
}

// ── Album Detail Screen (Apple Music / Plexamp Editorial Hero + Tracklist) ───

@Composable
fun AlbumDetailScreen(
    albumId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
    onArtistClick: (Int) -> Unit = {},
    highlightTrackId: Int? = null,
) {
    var album by remember { mutableStateOf<LibAlbumDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableStateOf(0) }
    val likedState = rememberLikedTracksState(api)

    LaunchedEffect(albumId, api, reloadNonce) {
        isLoading = true
        loadError = null
        when (val r = api.fetchAlbumDetail(albumId, resolve = true)) {
            is MusicApiResult.Success -> album = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> loadError = r.message
        }
        isLoading = false
        // the server is fetching the release's real tracklist: refresh until missing songs have names
        if (album?.isTracklistPending == true) {
            api.pollAlbumTracklist(albumId, onDeviceRevoked = onDeviceRevoked) { album = it }
        }
    }

    val al = album
    if (isLoading) {
        LoadingBox()
        return
    }
    if (al == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyBox(loadError ?: "Album not found.")
            if (loadError != null) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { reloadNonce += 1 },
                    shape = RoundedCornerShape(999.dp),
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                ) {
                    Text("Retry", color = SoulSyncColors.TextPrimary)
                }
            }
        }
        return
    }

    val scope = rememberCoroutineScope()
    val reqState = remember(albumId) { mutableStateMapOf<String, String>() }
    var requestingAll by remember { mutableStateOf(false) }

    // the album's own page draws its cover large: the full picture, not the row thumbnail
    val coverUrl = net.ssync.player.PlayerArtwork.full(api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id))
    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId

    val playableTracks = al.tracks.filter { it.isOwned }.map { t ->
        ActivePlayable(
            itemType = "track",
            itemId = t.id.toString(),
            title = t.title,
            subtitle = al.primaryArtist?.name ?: al.title,
            artworkUrl = coverUrl,
            streamUrl = api.streamUrl("track", t.id.toString()),
            parentId = al.id.toString(),
        )
    }
    val missingEntries = al.tracks.mapIndexedNotNull { idx, t ->
        if (t.isRequestable) Triple(idx, t, "${t.id ?: "m-$idx"}") else null
    }
    val unrequestedMissing = missingEntries.filter { (_, _, key) ->
        val st = reqState[key]
        st == null || st == "error"
    }
    val distinctDiscs = al.tracks.mapNotNull { it.discNumber }.distinct()
    val showDiscHeaders = distinctDiscs.size > 1
    val totalDuration = formatTotalAlbumDuration(al.tracks)

    suspend fun requestTrack(idx: Int, t: LibTrack, key: String) {
        // an untitled track would send an empty title: wait for the real tracklist
        if (!t.isRequestable) return
        val cur = reqState[key]
        if (cur == "sending" || cur == "wishlist" || cur == "requested" || cur == "owned") return
        reqState[key] = "sending"
        when (val r = api.requestMusicTrack(
            title = t.title,
            artist = al.primaryArtist?.name ?: "Unknown Artist",
            album = al.title,
            albumId = al.id,
            artistId = al.primaryArtist?.id,
            trackId = t.id,
            trackNumber = t.trackNumber ?: (idx + 1),
            discNumber = t.discNumber ?: 1,
            durationMs = t.duration,
            year = al.year,
            imageUrl = al.imageUrl,
        )) {
            is MusicApiResult.Success -> reqState[key] = when (r.data) {
                MusicTrackRequestResult.Wishlist -> "wishlist"
                MusicTrackRequestResult.Requested -> "requested"
                MusicTrackRequestResult.Owned -> "owned"
                is MusicTrackRequestResult.Error -> "error"
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> reqState[key] = "error"
        }
    }

    val albumListState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(al.tracks, highlightTrackId) {
        if (highlightTrackId != null) {
            val idx = al.tracks.indexOfFirst { it.id == highlightTrackId }
            if (idx >= 0) {
                albumListState.animateScrollToItem(idx + 1)
            }
        }
    }

    LazyColumn(
        state = albumListState,
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        // Centered Apple Music / Plexamp album hero
        item {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (coverUrl != null) {
                    AsyncImage(
                        model = coverUrl,
                        imageLoader = LocalSoulSyncImageLoader.current,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .alpha(0.22f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.35f),
                                    SoulSyncColors.BgPrimary.copy(alpha = 0.80f),
                                    SoulSyncColors.BgPrimary,
                                ),
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 84.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(204.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SoulSyncColors.CardRaised)
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                    ) {
                        SoulSyncArtwork(
                            url = coverUrl,
                            contentDescription = al.title,
                            fallbackIcon = Icons.Default.MusicNote,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = al.title,
                        color = SoulSyncColors.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val artistName = al.primaryArtist?.name
                    val artistRefId = al.primaryArtist?.id
                    if (!artistName.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = artistName,
                            color = SoulSyncColors.AccentLight,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = artistRefId != null) {
                                    artistRefId?.let(onArtistClick)
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val metaParts = buildList {
                        al.year?.let { add(it.toString()) }
                        if (playableTracks.size == al.tracks.size) {
                            add("${al.tracks.size} tracks")
                        } else {
                            add("${playableTracks.size} of ${al.tracks.size} tracks")
                        }
                        totalDuration?.let { add(it) }
                    }
                    Text(
                        text = metaParts.joinToString(" · "),
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Play + Shuffle side-by-side pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                if (playableTracks.isNotEmpty()) {
                                    coordinator.playAudio(playableTracks.first(), playableTracks.drop(1))
                                }
                            },
                            enabled = playableTracks.isNotEmpty(),
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SoulSyncColors.Accent,
                                contentColor = SoulSyncColors.OnAccent,
                            ),
                            modifier = Modifier.weight(1f).height(46.dp).dpadFocusable(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = {
                                val shuffled = playableTracks.shuffled()
                                if (shuffled.isNotEmpty()) {
                                    coordinator.playAudio(shuffled.first(), shuffled.drop(1))
                                }
                            },
                            enabled = playableTracks.isNotEmpty(),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, SoulSyncColors.Line),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = SoulSyncColors.Card,
                                contentColor = SoulSyncColors.TextPrimary,
                            ),
                            modifier = Modifier.weight(1f).height(46.dp).dpadFocusable(),
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (unrequestedMissing.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                if (!requestingAll) {
                                    requestingAll = true
                                    scope.launch {
                                        for ((i, tr, k) in unrequestedMissing) {
                                            requestTrack(i, tr, k)
                                        }
                                        requestingAll = false
                                    }
                                }
                            },
                            enabled = !requestingAll,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.65f)),
                            modifier = Modifier.fillMaxWidth().height(42.dp).dpadFocusable(),
                        ) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = SoulSyncColors.AccentLight,
                                modifier = Modifier.size(17.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (requestingAll) "Requesting missing tracks…" else "Request ${unrequestedMissing.size} missing tracks",
                                color = SoulSyncColors.AccentLight,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }

        // Frameless tracklist
        itemsIndexed(al.tracks) { idx, t ->
            val prevDisc = if (idx > 0) al.tracks[idx - 1].discNumber else null
            if (showDiscHeaders && t.discNumber != null && t.discNumber != prevDisc) {
                Text(
                    text = "DISC ${t.discNumber}",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
                )
            }

            val playable = t.isOwned
            val displayTitle = t.displayTitle(idx + 1)
            val rowKey = "${t.id ?: "m-$idx"}"
            val st = reqState[rowKey]
            val isActive = playable && activeTrackId == t.id?.toString()

            AlbumTrackRow(
                trackNumber = t.trackNumber ?: (idx + 1),
                title = displayTitle,
                explicit = t.explicit,
                format = if (playable) t.file?.format?.uppercase() else null,
                duration = formatTrackDuration(t.duration),
                playable = playable,
                isActive = isActive,
                requestStatus = if (playable) null else st,
                showDivider = idx < al.tracks.lastIndex,
                requestable = t.isRequestable,
                contextItem = MediaContextItem(
                    itemType = "track",
                    itemId = t.id?.toString() ?: "m-$idx",
                    title = displayTitle,
                    subtitle = al.primaryArtist?.name ?: al.title,
                    artworkUrl = coverUrl,
                    streamUrl = if (playable && t.id != null) api.streamUrl("track", t.id.toString()) else null,
                    isVideo = false,
                    parentId = al.id.toString(),
                    owned = playable,
                    onRequest = if (t.isRequestable) ({ scope.launch { requestTrack(idx, t, rowKey) } }) else null,
                    songRef = if (playable) {
                        SongRefPayload(
                            title = t.title,
                            artist = al.primaryArtist?.name ?: "Unknown Artist",
                            album = al.title,
                            durationMs = t.duration,
                            lib2TrackId = t.id,
                        )
                    } else null,
                ),
                trailingActions = if (playable) {
                    {
                        val songRef = SongRefPayload(
                            title = t.title,
                            artist = al.primaryArtist?.name ?: "Unknown Artist",
                            album = al.title,
                            durationMs = t.duration,
                            lib2TrackId = t.id,
                        )
                        val pItem = ActivePlayable(
                            itemType = "track",
                            itemId = t.id.toString(),
                            title = t.title,
                            subtitle = al.primaryArtist?.name ?: al.title,
                            artworkUrl = api.artwork.resolve(al.imageUrl),
                            streamUrl = api.streamUrl("track", t.id.toString()),
                            parentId = al.id.toString(),
                        )
                        TrackRowActionIcons(
                            song = songRef,
                            playable = pItem,
                            likedState = likedState,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    if (playable) {
                        val pIdx = playableTracks.indexOfFirst { it.itemId == t.id?.toString() }
                        if (pIdx >= 0) {
                            coordinator.playAudio(playableTracks[pIdx], playableTracks.drop(pIdx + 1))
                        }
                    } else {
                        scope.launch { requestTrack(idx, t, rowKey) }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    trackNumber: Int,
    title: String,
    explicit: Boolean,
    format: String?,
    duration: String?,
    playable: Boolean,
    isActive: Boolean,
    requestStatus: String?,
    showDivider: Boolean,
    contextItem: MediaContextItem? = null,
    // false for a missing track whose name isn't known yet: no request until it has one
    requestable: Boolean = true,
    trailingActions: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) SoulSyncColors.Accent.copy(alpha = 0.09f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        openContext(ci)
                    }
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (isActive) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Playing",
                        tint = SoulSyncColors.AccentLight,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = trackNumber.toString(),
                        color = if (playable) SoulSyncColors.TextSecondary else SoulSyncColors.TextSecondary.copy(alpha = 0.45f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .alpha(if (playable) 1f else 0.48f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        color = when {
                            isActive -> SoulSyncColors.AccentLight
                            playable -> SoulSyncColors.TextPrimary
                            else -> SoulSyncColors.TextSecondary
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!playable) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color.White.copy(alpha = 0.08f),
                            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.16f)),
                        ) {
                            Text(
                                text = "MISSING",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (explicit) {
                        Surface(
                            shape = RoundedCornerShape(3.dp),
                            color = SoulSyncColors.TextSecondary.copy(alpha = 0.22f),
                        ) {
                            Text(
                                text = "E",
                                color = SoulSyncColors.TextSecondary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                if (!playable) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (requestStatus) {
                            "sending" -> "Requesting…"
                            "wishlist" -> "Added to wishlist"
                            "requested" -> "Request submitted"
                            "owned" -> "In library"
                            "error" -> "Could not request · Tap to retry"
                            else -> if (requestable) "Not in library · Tap to request" else "Not in library"
                        },
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 11.5.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (playable) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!format.isNullOrBlank()) {
                        val isLossless = format == "FLAC" || format == "ALAC" || format == "WAV"
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLossless) SoulSyncColors.HiRes.copy(alpha = 0.14f) else SoulSyncColors.CardRaised,
                            border = if (isLossless) BorderStroke(0.5.dp, SoulSyncColors.HiRes.copy(alpha = 0.45f)) else null,
                        ) {
                            Text(
                                text = format,
                                color = if (isLossless) SoulSyncColors.HiRes else SoulSyncColors.TextSecondary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (!duration.isNullOrBlank()) {
                        Text(
                            text = duration,
                            color = SoulSyncColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    trailingActions?.invoke()
                }
            } else if (requestable || requestStatus != null) {
                val pillText = when (requestStatus) {
                    "sending" -> "…"
                    "wishlist" -> "Wishlist"
                    "requested" -> "Requested"
                    "owned" -> "Owned"
                    "error" -> "Retry"
                    else -> "Request"
                }
                val isDone = requestStatus == "wishlist" || requestStatus == "requested" || requestStatus == "owned"
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (isDone) SoulSyncColors.Success.copy(alpha = 0.14f) else SoulSyncColors.Accent.copy(alpha = 0.14f),
                    border = BorderStroke(
                        0.75.dp,
                        if (isDone) SoulSyncColors.Success.copy(alpha = 0.45f) else SoulSyncColors.Accent.copy(alpha = 0.45f),
                    ),
                ) {
                    Text(
                        text = pillText,
                        color = if (isDone) SoulSyncColors.Success else SoulSyncColors.AccentLight,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 48.dp, end = 18.dp),
                thickness = 0.5.dp,
                color = SoulSyncColors.Line.copy(alpha = 0.48f),
            )
        }
    }
}

// ── Playlist Detail Screen ───────────────────────────────────────────────────

@Composable
fun PlaylistDetailScreen(
    playlistId: Int,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var playlist by remember { mutableStateOf<PlaylistDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val likedState = rememberLikedTracksState(api)

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

    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId
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

    LazyColumn(contentPadding = PaddingValues(top = 78.dp, bottom = 32.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(
                    text = pl.name,
                    color = SoulSyncColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (!pl.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = pl.description.orEmpty(),
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 13.5.sp,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${pl.tracks.size} tracks",
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (playable.isNotEmpty()) coordinator.playAudio(playable.first(), playable.drop(1))
                        },
                        enabled = playable.isNotEmpty(),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoulSyncColors.Accent,
                            contentColor = SoulSyncColors.OnAccent,
                        ),
                        modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            val shuffled = playable.shuffled()
                            if (shuffled.isNotEmpty()) coordinator.playAudio(shuffled.first(), shuffled.drop(1))
                        },
                        enabled = playable.isNotEmpty(),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SoulSyncColors.Card,
                            contentColor = SoulSyncColors.TextPrimary,
                        ),
                        modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(),
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Shuffle", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (playable.isEmpty() && pl.tracks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    NothingToDownload("None of these songs are in your library yet, so there is nothing to download.")
                }
                if (playable.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    // the playlist on the phone, kept in step if wanted (send to a phone on a tv)
                    GroupDownloadButton(
                        api = api,
                        target = DownloadTarget(
                            type = "playlist",
                            id = (if (pl.kind == "yours") "user:" else "mirror:") + pl.id,
                            title = pl.name,
                            subtitle = "${playable.size} songs",
                            artwork = relativeArt(pl.imageUrl),
                            groupTitle = pl.name,
                        ),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        itemsIndexed(pl.tracks) { idx, item ->
            val isPlayable = item.trackId != null
            val songRef = SongRefPayload(
                title = item.title,
                artist = item.artist,
                album = item.album,
                durationMs = item.durationMs,
                lib2TrackId = item.trackId,
            )
            val pItem = ActivePlayable(
                itemType = "track",
                itemId = item.trackId.toString(),
                title = item.title,
                subtitle = item.artist,
                artworkUrl = api.artwork.albumArtwork(item.albumId),
                streamUrl = api.streamUrl("track", item.trackId.toString()),
            )
            MusicTrackRow(
                title = item.title,
                subtitle = listOfNotNull(item.artist, item.album).joinToString(" · "),
                artworkUrl = api.artwork.albumArtwork(item.albumId),
                duration = formatTrackDuration(item.durationMs),
                isActive = isPlayable && activeTrackId == item.trackId?.toString(),
                showDivider = idx < pl.tracks.lastIndex,
                playable = isPlayable,
                contextItem = if (isPlayable) pItem.toContextItem(songRef) else null,
                trailingActions = if (isPlayable) {
                    {
                        TrackRowActionIcons(
                            song = songRef,
                            playable = pItem,
                            likedState = likedState,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    val pIdx = playable.indexOfFirst { it.itemId == item.trackId?.toString() }
                    if (pIdx >= 0) coordinator.playAudio(playable[pIdx], playable.drop(pIdx + 1))
                },
            )
        }
    }
}

/**
 * one of soulsync's own playlists (a daily mix, a mood, fresh tape, flow,
 * listenbrainz): play it, shuffle it, or download it (send to a phone on a
 * tv). the songs not in the library stay listed, dimmed.
 */
@Composable
fun DiscoverPlaylistDetailScreen(
    key: String,
    title: String,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onDeviceRevoked: () -> Unit,
) {
    var playlist by remember { mutableStateOf<DiscoverPlaylistDetail?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val likedState = rememberLikedTracksState(api)

    LaunchedEffect(key, api) {
        isLoading = true
        when (val r = api.fetchDiscoverPlaylistDetail(key)) {
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
    val name = pl.name.ifBlank { title }
    val activeTrackId = coordinator.activeItem?.takeIf { it.itemType == "track" }?.itemId
    val playable = pl.songs.filter { it.trackId != null }.map { item ->
        ActivePlayable(
            itemType = "track",
            itemId = item.trackId.toString(),
            title = item.title,
            subtitle = item.artist,
            artworkUrl = api.artwork.albumArtwork(item.albumId),
            streamUrl = api.streamUrl("track", item.trackId.toString()),
        )
    }

    LazyColumn(contentPadding = PaddingValues(top = 78.dp, bottom = 32.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(text = name, color = SoulSyncColors.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (!pl.blurb.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = pl.blurb.orEmpty(), color = SoulSyncColors.TextSecondary, fontSize = 13.5.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        "${pl.songs.size} songs",
                        (pl.songs.size - playable.size).takeIf { it > 0 }?.let { "$it not in your library" },
                    ).joinToString(" · "),
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { if (playable.isNotEmpty()) coordinator.playAudio(playable.first(), playable.drop(1)) },
                        enabled = playable.isNotEmpty(),
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoulSyncColors.Accent,
                            contentColor = SoulSyncColors.OnAccent,
                        ),
                        modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(
                        onClick = {
                            val shuffled = playable.shuffled()
                            if (shuffled.isNotEmpty()) coordinator.playAudio(shuffled.first(), shuffled.drop(1))
                        },
                        enabled = playable.isNotEmpty(),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, SoulSyncColors.Line),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = SoulSyncColors.Card,
                            contentColor = SoulSyncColors.TextPrimary,
                        ),
                        modifier = Modifier.weight(1f).height(44.dp).dpadFocusable(),
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Shuffle", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                if (playable.isNotEmpty()) {
                    // the same key the web player sends: discover:<key>
                    GroupDownloadButton(
                        api = api,
                        target = DownloadTarget(
                            type = "playlist",
                            id = "discover:$key",
                            title = name,
                            subtitle = "${playable.size} songs",
                            groupTitle = name,
                        ),
                    )
                } else if (pl.songs.isNotEmpty()) {
                    NothingToDownload("None of these are in your library yet, so there is nothing to download.")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        itemsIndexed(pl.songs) { idx, item ->
            val isPlayable = item.trackId != null
            val songRef = SongRefPayload(
                title = item.title,
                artist = item.artist,
                album = item.album,
                durationMs = item.durationMs,
                lib2TrackId = item.trackId,
            )
            val pItem = ActivePlayable(
                itemType = "track",
                itemId = item.trackId.toString(),
                title = item.title,
                subtitle = item.artist,
                artworkUrl = api.artwork.albumArtwork(item.albumId),
                streamUrl = api.streamUrl("track", item.trackId.toString()),
            )
            MusicTrackRow(
                title = item.title,
                subtitle = listOfNotNull(item.artist, item.album).joinToString(" · "),
                artworkUrl = api.artwork.albumArtwork(item.albumId),
                duration = formatTrackDuration(item.durationMs),
                isActive = isPlayable && activeTrackId == item.trackId?.toString(),
                showDivider = idx < pl.songs.lastIndex,
                playable = isPlayable,
                contextItem = if (isPlayable) pItem.toContextItem(songRef) else null,
                trailingActions = if (isPlayable) {
                    {
                        TrackRowActionIcons(
                            song = songRef,
                            playable = pItem,
                            likedState = likedState,
                        )
                    }
                } else {
                    null
                },
                onClick = {
                    val pIdx = playable.indexOfFirst { it.itemId == item.trackId?.toString() }
                    if (pIdx >= 0) coordinator.playAudio(playable[pIdx], playable.drop(pIdx + 1))
                },
            )
        }
    }
}

/** a download button that can't do anything yet, saying why */
@Composable
private fun NothingToDownload(why: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Download, contentDescription = null, tint = SoulSyncColors.TextSecondary, modifier = Modifier.size(16.dp))
        Text(why, color = SoulSyncColors.TextSecondary, fontSize = 12.5.sp)
    }
}

// ── Shared Clean Row (kept for Queue, Watchlist, Podcasts, Audiobooks) ───────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SimpleTrackRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    trailingBadge: String? = null,
    // a long press (holding select on a tv) opens its menu
    contextItem: MediaContextItem? = null,
    onClick: () -> Unit,
) {
    val openContext = LocalMediaContextMenu.current
    // Sleek borderless Plexamp/Spotify row (no boxy white border around track rows)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = contextItem?.let { ci -> { openContext(ci) } },
            ),
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.035f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artworkUrl != null) {
                SoulSyncArtwork(
                    url = artworkUrl,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.MusicNote,
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!trailingBadge.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(10.dp))
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

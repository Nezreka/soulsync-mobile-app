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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.GuideChannel
import net.ssync.player.MusicApiResult
import net.ssync.player.MusicChannel
import net.ssync.player.PlayerApiClient
import net.ssync.player.PlayerSettings
import net.ssync.player.TogetherTitle
import net.ssync.player.VideoCatalog
import net.ssync.player.WatchingSession
import net.ssync.player.WatchlistItem
import net.ssync.storage.PairedServerRecord

@Composable
fun WatchlistScreen(
    api: PlayerApiClient,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WatchlistItem>>(emptyList()) }
    var catalog by remember { mutableStateOf(net.ssync.player.VideoCatalog()) }
    var isLoading by remember { mutableStateOf(true) }
    var kindFilter by remember { mutableStateOf("all") }
    var sortByTitle by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val r = api.fetchWatchlist()) {
            is MusicApiResult.Success -> items = r.data
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchVideoCatalog() as? MusicApiResult.Success)?.let { catalog = it.data }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (items.isEmpty()) {
        EmptyBox("Your Watchlist is empty. Save movies or shows to watch later.")
        return
    }

    val moviesById = remember(catalog.movies) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog.shows) { catalog.shows.associateBy { it.id } }
    val movieCount = items.count { it.itemType == "movie" }
    val showCount = items.count { it.itemType == "show" }
    val ytCount = items.count { it.itemType == "youtube" }

    val tabs = buildList {
        add("all" to "All ${items.size}")
        if (movieCount > 0) add("movie" to "Movies $movieCount")
        if (showCount > 0) add("show" to "TV $showCount")
        if (ytCount > 0) add("youtube" to "YouTube $ytCount")
    }

    fun titleOf(w: WatchlistItem): String {
        val idInt = w.itemId.toIntOrNull()
        return (if (w.itemType == "movie" && idInt != null) moviesById[idInt]?.title else null)
            ?: (if (w.itemType == "show" && idInt != null) showsById[idInt]?.title else null)
            ?: w.meta?.title
            ?: w.itemId
    }

    fun artOf(w: WatchlistItem): String? =
        api.artwork.resolve(w.meta?.artwork) ?: api.artwork.forItem(w.itemType, w.itemId, preferBackdrop = false)

    val filtered = remember(items, kindFilter, sortByTitle, catalog) {
        val base = items.filter { kindFilter == "all" || it.itemType == kindFilter }
        if (sortByTitle) base.sortedBy { titleOf(it).lowercase().removePrefix("the ") } else base
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeaderAndTabs(
            title = "Watchlist",
            tabs = tabs,
            selected = kindFilter,
            onSelect = { kindFilter = it },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !sortByTitle,
                    onClick = { sortByTitle = false },
                    label = { Text("Recently added") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                    ),
                )
                FilterChip(
                    selected = sortByTitle,
                    onClick = { sortByTitle = true },
                    label = { Text("A to Z") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                    ),
                )
            }
            TextButton(onClick = { gridView = !gridView }) {
                Text(if (gridView) "List" else "Grid", color = SoulSyncColors.AccentLight)
            }
        }

        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 116.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                gridItems(filtered, key = { "${it.itemType}:${it.itemId}" }) { w ->
                    PosterCard(
                        title = titleOf(w),
                        subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                        artworkUrl = artOf(w),
                        onClick = {
                            val idInt = w.itemId.toIntOrNull()
                            if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                            if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                        },
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { "${it.itemType}:${it.itemId}" }) { w ->
                    CompactMediaListRow(
                        title = titleOf(w),
                        subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                        artworkUrl = artOf(w),
                        onClick = {
                            val idInt = w.itemId.toIntOrNull()
                            if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                            if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                        },
                        trailing = {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        api.removeFromWatchlist(w.itemType, w.itemId)
                                        items = items.filterNot { it.itemType == w.itemType && it.itemId == w.itemId }
                                    }
                                },
                                modifier = Modifier.dpadFocusable(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = SoulSyncColors.TextSecondary)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var podcasts by remember { mutableStateOf<List<net.ssync.player.PodcastSub>>(emptyList()) }
    var books by remember { mutableStateOf<List<net.ssync.player.OwnedBook>>(emptyList()) }
    var artists by remember { mutableStateOf<List<net.ssync.player.LibArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<net.ssync.player.LibAlbum>>(emptyList()) }
    var songs by remember { mutableStateOf<List<net.ssync.player.LibSong>>(emptyList()) }
    var searchingMusic by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        coroutineScope {
            val vDef = async { api.fetchVideoCatalog() }
            val pDef = async { api.fetchPodcastSubs() }
            val bDef = async { api.fetchAudiobooks() }
            val v = vDef.await()
            if (v is MusicApiResult.DeviceRevoked) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (v as? MusicApiResult.Success)?.let { catalog = it.data }
            (pDef.await() as? MusicApiResult.Success)?.let { podcasts = it.data }
            (bDef.await() as? MusicApiResult.Success)?.let { books = it.data }
        }
    }

    // Live debounced music library search (Artists, Albums, Songs)
    LaunchedEffect(query, api) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            artists = emptyList()
            albums = emptyList()
            songs = emptyList()
            searchingMusic = false
            return@LaunchedEffect
        }
        searchingMusic = true
        kotlinx.coroutines.delay(220L)
        coroutineScope {
            val arDef = async { api.fetchArtists(search = trimmed, limit = 12) }
            val alDef = async { api.fetchAlbums(search = trimmed, limit = 12) }
            val soDef = async { api.fetchSongs(search = trimmed, limit = 20) }
            (arDef.await() as? MusicApiResult.Success)?.let { artists = it.data }
            (alDef.await() as? MusicApiResult.Success)?.let { albums = it.data }
            (soDef.await() as? MusicApiResult.Success)?.let { songs = it.data }
        }
        searchingMusic = false
    }

    val q = query.trim().lowercase()
    val movieHits = if (q.isEmpty()) emptyList() else catalog.movies.filter {
        it.title.lowercase().contains(q) || it.genres.any { g -> g.lowercase().contains(q) } || (it.studio?.lowercase()?.contains(q) == true)
    }.take(24)
    val showHits = if (q.isEmpty()) emptyList() else catalog.shows.filter {
        it.title.lowercase().contains(q) || it.genres.any { g -> g.lowercase().contains(q) } || (it.network?.lowercase()?.contains(q) == true)
    }.take(24)
    val ytHits = if (q.isEmpty()) emptyList() else catalog.youtube.filter {
        it.title.lowercase().contains(q) || (it.channelTitle?.lowercase()?.contains(q) == true)
    }.take(20)
    val podcastHits = if (q.isEmpty()) emptyList() else podcasts.filter {
        it.title.lowercase().contains(q) || (it.author?.lowercase()?.contains(q) == true)
    }.take(12)
    val bookHits = if (q.isEmpty()) emptyList() else books.filter {
        it.title.lowercase().contains(q) || (it.author?.lowercase()?.contains(q) == true)
    }.take(12)

    val musicCount = artists.size + albums.size + songs.size
    val totalCount = movieHits.size + showHits.size + musicCount + ytHits.size + podcastHits.size + bookHits.size

    val tabs = buildList {
        add("all" to "All $totalCount")
        if (movieHits.isNotEmpty()) add("movies" to "Movies ${movieHits.size}")
        if (showHits.isNotEmpty()) add("tv" to "TV ${showHits.size}")
        if (musicCount > 0) add("music" to "Music $musicCount")
        if (ytHits.isNotEmpty()) add("youtube" to "YouTube ${ytHits.size}")
        if (podcastHits.isNotEmpty()) add("podcasts" to "Podcasts ${podcastHits.size}")
        if (bookHits.isNotEmpty()) add("audiobooks" to "Audiobooks ${bookHits.size}")
    }

    val topResult = remember(q, movieHits, showHits, artists, albums, songs, ytHits, podcastHits, bookHits) {
        if (q.isEmpty()) return@remember null
        // Pick the single strongest match across all categories (exact or prefix match first)
        data class TopCandidate(
            val kindLabel: String,
            val title: String,
            val subtitle: String,
            val artworkUrl: String?,
            val isPoster: Boolean,
            val score: Int,
            val onActivate: () -> Unit,
        )
        fun scoreOf(t: String): Int {
            val low = t.lowercase()
            return when {
                low == q -> 100
                low.startsWith(q) -> 85
                low.split(" ").any { it.startsWith(q) } -> 70
                low.contains(q) -> 50
                else -> 0
            }
        }
        val candidates = mutableListOf<TopCandidate>()
        movieHits.firstOrNull()?.let { m ->
            candidates += TopCandidate(
                kindLabel = "MOVIE",
                title = m.title,
                subtitle = listOfNotNull(m.year?.toString(), m.contentRating, m.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                artworkUrl = api.artwork.moviePoster(m.id),
                isPoster = true,
                score = scoreOf(m.title),
                onActivate = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
            )
        }
        showHits.firstOrNull()?.let { s ->
            candidates += TopCandidate(
                kindLabel = "TV SHOW",
                title = s.title,
                subtitle = listOfNotNull(s.year?.toString(), "${s.episodeCount} episodes", s.network).joinToString(" · "),
                artworkUrl = api.artwork.showPoster(s.id),
                isPoster = true,
                score = scoreOf(s.title),
                onActivate = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
            )
        }
        artists.firstOrNull()?.let { ar ->
            candidates += TopCandidate(
                kindLabel = "ARTIST",
                title = ar.name,
                subtitle = "${ar.albumCount} albums",
                artworkUrl = api.artwork.resolve(ar.imageUrl ?: ar.remoteImageUrl),
                isPoster = false,
                score = scoreOf(ar.name),
                onActivate = { onNavigate(PlayerDestination.ArtistDetail(ar.id)) },
            )
        }
        albums.firstOrNull()?.let { al ->
            candidates += TopCandidate(
                kindLabel = "ALBUM",
                title = al.title,
                subtitle = listOfNotNull(al.artistName, al.year?.toString()).joinToString(" · "),
                artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                isPoster = false,
                score = scoreOf(al.title),
                onActivate = { onNavigate(PlayerDestination.AlbumDetail(al.id)) },
            )
        }
        songs.firstOrNull()?.let { song ->
            candidates += TopCandidate(
                kindLabel = "SONG",
                title = song.title,
                subtitle = listOfNotNull(song.artistName, song.albumTitle).joinToString(" · "),
                artworkUrl = api.artwork.albumArtwork(song.albumId),
                isPoster = false,
                score = scoreOf(song.title),
                onActivate = {
                    coordinator.playAudio(
                        ActivePlayable(
                            itemType = "track",
                            itemId = song.id.toString(),
                            title = song.title,
                            subtitle = song.artistName ?: "Unknown Artist",
                            artworkUrl = api.artwork.albumArtwork(song.albumId),
                            streamUrl = api.streamUrl("track", song.id.toString()),
                        ),
                    )
                },
            )
        }
        candidates.maxByOrNull { it.score }?.takeIf { it.score >= 50 }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                filter = "all"
            },
            placeholder = { Text("Search movies, shows, artists, albums, songs, podcasts…") },
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
            modifier = Modifier.fillMaxWidth().dpadFocusable(),
        )

        if (q.isNotEmpty() && tabs.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tabs, key = { it.first }) { (key, label) ->
                    val on = filter == key
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (on) Color.White else SoulSyncColors.Line, CircleShape)
                            .clickable { filter = key }
                            .padding(horizontal = 13.dp, vertical = 6.dp),
                    ) {
                        Text(
                            label,
                            color = if (on) Color.Black else SoulSyncColors.TextSoft,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (q.isEmpty()) {
            EmptyBox("Search across your entire SoulSync library — movies, TV shows, music, YouTube, podcasts, and audiobooks.")
        } else if (totalCount == 0 && !searchingMusic) {
            EmptyBox("No matches for \"$query\".")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (filter == "all" && topResult != null) {
                    item(key = "top-result") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SectionHeader("Top Result")
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .dpadFocusable(RoundedCornerShape(16.dp))
                                    .clickable { topResult.onActivate() },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = SoulSyncColors.Accent.copy(alpha = 0.14f)),
                                border = BorderStroke(1.dp, SoulSyncColors.Accent.copy(alpha = 0.45f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = topResult.artworkUrl,
                                        contentDescription = topResult.title,
                                        modifier = Modifier
                                            .width(if (topResult.isPoster) 58.dp else 64.dp)
                                            .height(if (topResult.isPoster) 86.dp else 64.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(SoulSyncColors.Accent.copy(alpha = 0.25f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = topResult.kindLabel,
                                                color = SoulSyncColors.AccentLight,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                            )
                                        }
                                        Text(
                                            text = topResult.title,
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (topResult.subtitle.isNotBlank()) {
                                            Text(
                                                text = topResult.subtitle,
                                                color = SoulSyncColors.TextSoft,
                                                fontSize = 12.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if ((filter == "all" || filter == "movies") && movieHits.isNotEmpty()) {
                    item { SectionHeader("Movies") }
                    items(movieHits, key = { "m-${it.id}" }) { m ->
                        CompactMediaListRow(
                            title = m.title,
                            subtitle = listOfNotNull("Movie", m.year?.toString(), m.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
                            artworkUrl = api.artwork.moviePoster(m.id),
                            onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "tv") && showHits.isNotEmpty()) {
                    item { SectionHeader("TV Shows") }
                    items(showHits, key = { "s-${it.id}" }) { s ->
                        CompactMediaListRow(
                            title = s.title,
                            subtitle = listOfNotNull("TV Show", "${s.episodeCount} episodes", s.network).joinToString(" · "),
                            artworkUrl = api.artwork.showPoster(s.id),
                            onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(artists, key = { "ar-${it.id}" }) { ar ->
                        SimpleTrackRow(
                            title = ar.name,
                            subtitle = "Artist · ${ar.albumCount} albums",
                            artworkUrl = api.artwork.resolve(ar.imageUrl ?: ar.remoteImageUrl),
                            onClick = { onNavigate(PlayerDestination.ArtistDetail(ar.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(albums, key = { "al-${it.id}" }) { al ->
                        SimpleTrackRow(
                            title = al.title,
                            subtitle = listOfNotNull("Album", al.artistName, al.year?.toString()).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                            onClick = { onNavigate(PlayerDestination.AlbumDetail(al.id)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "music") && songs.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    items(songs, key = { "so-${it.id}" }) { song ->
                        SimpleTrackRow(
                            title = song.title,
                            subtitle = listOfNotNull("Song", song.artistName, song.albumTitle).joinToString(" · "),
                            artworkUrl = api.artwork.albumArtwork(song.albumId),
                            onClick = {
                                coordinator.playAudio(
                                    ActivePlayable(
                                        itemType = "track",
                                        itemId = song.id.toString(),
                                        title = song.title,
                                        subtitle = song.artistName ?: "Unknown Artist",
                                        artworkUrl = api.artwork.albumArtwork(song.albumId),
                                        streamUrl = api.streamUrl("track", song.id.toString()),
                                    ),
                                )
                            },
                        )
                    }
                }
                if ((filter == "all" || filter == "youtube") && ytHits.isNotEmpty()) {
                    item { SectionHeader("YouTube") }
                    items(ytHits, key = { "y-${it.id}" }) { y ->
                        SimpleTrackRow(
                            title = y.title,
                            subtitle = "YouTube · ${y.channelTitle ?: ""}".trimEnd(' ', '·'),
                            artworkUrl = api.artwork.resolve(y.thumbnailUrl),
                            onClick = {
                                coordinator.playVideo(
                                    ActivePlayable(
                                        itemType = "youtube",
                                        itemId = y.id,
                                        title = y.title,
                                        subtitle = y.channelTitle.orEmpty(),
                                        artworkUrl = api.artwork.resolve(y.thumbnailUrl),
                                        streamUrl = api.streamUrl("youtube", y.id),
                                        isVideo = true,
                                    ),
                                )
                            },
                        )
                    }
                }
                if ((filter == "all" || filter == "podcasts") && podcastHits.isNotEmpty()) {
                    item { SectionHeader("Podcasts") }
                    items(podcastHits, key = { "p-${it.id}" }) { p ->
                        SimpleTrackRow(
                            title = p.title,
                            subtitle = listOfNotNull("Podcast", p.author).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(p.artworkUrl),
                            onClick = { onNavigate(PlayerDestination.PodcastDetail(p)) },
                        )
                    }
                }
                if ((filter == "all" || filter == "audiobooks") && bookHits.isNotEmpty()) {
                    item { SectionHeader("Audiobooks") }
                    items(bookHits, key = { "b-${it.asin}" }) { b ->
                        SimpleTrackRow(
                            title = b.title,
                            subtitle = listOfNotNull("Audiobook", b.author).joinToString(" · "),
                            artworkUrl = api.artwork.resolve(b.coverUrl) ?: api.artwork.audiobookCover(b.asin),
                            onClick = { onNavigate(PlayerDestination.AudiobookDetail(b)) },
                        )
                    }
                }
            }
        }
    }
}


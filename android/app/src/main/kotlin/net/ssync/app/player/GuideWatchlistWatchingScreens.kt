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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.LocalIsTv
import net.ssync.pairing.ui.dpadCardFocusable
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
    val isTv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val warmHit = HomeWarmCache.baseUrl == api.baseUrl && HomeWarmCache.watchlist.isNotEmpty()
    var items by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.watchlist else emptyList()) }
    var catalog by remember(api.baseUrl) { mutableStateOf(if (HomeWarmCache.baseUrl == api.baseUrl) HomeWarmCache.catalog else net.ssync.player.VideoCatalog()) }
    var isLoading by remember(api.baseUrl) { mutableStateOf(!warmHit) }
    var kindFilter by remember { mutableStateOf("all") }
    var sortByTitle by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        if (!warmHit) isLoading = true
        coroutineScope {
            launch {
                when (val r = api.fetchWatchlist()) {
                    is MusicApiResult.Success -> {
                        items = r.data
                        HomeWarmCache.watchlist = r.data
                    }
                    MusicApiResult.DeviceRevoked -> onDeviceRevoked()
                    is MusicApiResult.Error -> Unit
                }
                isLoading = false
            }
            launch {
                if (catalog.movies.isEmpty() && catalog.shows.isEmpty()) {
                    (api.fetchVideoCatalog() as? MusicApiResult.Success)?.let { catalog = it.data }
                }
            }
        }
        isLoading = false
    }

    if (isLoading && items.isEmpty()) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isTv) Modifier.background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(SoulSyncColors.Accent.copy(alpha = 0.12f), Color(0xFF0A0C16)),
                    ),
                ) else Modifier,
            ),
    ) {
        BrowseHeaderAndTabs(
            title = "Watchlist",
            tabs = tabs,
            selected = kindFilter,
            onSelect = { kindFilter = it },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isTv) 78.dp else 16.dp, end = if (isTv) 32.dp else 16.dp, top = 4.dp, bottom = 4.dp),
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
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                )
                FilterChip(
                    selected = sortByTitle,
                    onClick = { sortByTitle = true },
                    label = { Text("A to Z") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SoulSyncColors.Accent,
                        selectedLabelColor = SoulSyncColors.OnAccent,
                    ),
    modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                )
            }
            TextButton(onClick = { gridView = !gridView }, modifier = Modifier.dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),) {
                Text(if (gridView) "List" else "Grid", color = SoulSyncColors.AccentLight)
            }
        }

        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (isTv) 148.dp else 116.dp),
                contentPadding = PaddingValues(start = if (isTv) 78.dp else 16.dp, end = if (isTv) 32.dp else 16.dp, top = 12.dp, bottom = 36.dp),
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
                contentPadding = PaddingValues(start = if (isTv) 78.dp else 16.dp, end = if (isTv) 32.dp else 16.dp, top = 10.dp, bottom = 36.dp),
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
    initialQuery: String = "",
) {
    val warmHit = HomeWarmCache.baseUrl == api.baseUrl
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var filter by remember { mutableStateOf("all") }
    var catalog by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.catalog else VideoCatalog()) }
    var podcasts by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.podcasts else emptyList()) }
    var books by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.books else emptyList()) }
    var artists by remember { mutableStateOf<List<net.ssync.player.LibArtist>>(emptyList()) }
    var albums by remember { mutableStateOf<List<net.ssync.player.LibAlbum>>(emptyList()) }
    var songs by remember { mutableStateOf<List<net.ssync.player.LibSong>>(emptyList()) }
    var searchingMusic by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var recent by remember { mutableStateOf(SearchHistory.read(context)) }
    // a search that got somewhere is kept for next time, like the web player's
    val latestQuery = androidx.compose.runtime.rememberUpdatedState(query)
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { SearchHistory.remember(context, latestQuery.value) }
    }

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
    val qTokens = remember(q) { q.split(Regex("\\s+")).filter { it.isNotBlank() } }
    fun matchesTokens(vararg fields: String?): Boolean {
        if (qTokens.isEmpty()) return false
        val combined = fields.filterNotNull().joinToString(" ").lowercase()
        return qTokens.all { tok -> combined.contains(tok) }
    }
    val movieHits = if (q.isEmpty()) emptyList() else catalog.movies.filter {
        matchesTokens(it.title, it.studio, it.year?.toString(), it.genres.joinToString(" "))
    }.take(24)
    val showHits = if (q.isEmpty()) emptyList() else catalog.shows.filter {
        matchesTokens(it.title, it.network, it.year?.toString(), it.genres.joinToString(" "))
    }.take(24)
    val ytHits = if (q.isEmpty()) emptyList() else catalog.youtube.filter {
        matchesTokens(it.title, it.channelTitle)
    }.take(20)
    val podcastHits = if (q.isEmpty()) emptyList() else podcasts.filter {
        matchesTokens(it.title, it.author)
    }.take(12)
    val bookHits = if (q.isEmpty()) emptyList() else books.filter {
        matchesTokens(it.title, it.author)
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

    val isTv = LocalIsTv.current
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = 10.dp)) {
        SearchField(
            value = query,
            onValueChange = {
                query = it
                filter = "all"
            },
            onSubmit = { recent = SearchHistory.remember(context, query) },
            autoFocus = !isTv,
            modifier = Modifier.fillMaxWidth(),
        )

        if (q.isNotEmpty() && tabs.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it.first }) { (key, label) ->
                    val on = filter == key
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (on) Color.White else Color.White.copy(alpha = 0.07f))
                            .border(1.dp, if (on) Color.White else Color.White.copy(alpha = 0.12f), CircleShape)
                            .dpadFocusable(CircleShape, onLightSurface = on)
                            .clickable { filter = key }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            label,
                            color = if (on) Color.Black else SoulSyncColors.TextSoft,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (q.isEmpty()) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                SearchStart(
                    recent = recent,
                    onPickRecent = { query = it },
                    onClearRecent = {
                        SearchHistory.clear(context)
                        recent = emptyList()
                    },
                    browse = browseTargets(
                        hasVideo = catalog.movies.isNotEmpty() || catalog.shows.isNotEmpty() || catalog.youtube.isNotEmpty(),
                        hasMusic = true,
                        hasPodcasts = podcasts.isNotEmpty(),
                        hasBooks = books.isNotEmpty(),
                    ),
                    onBrowse = onNavigate,
                )
            }
        } else if (totalCount == 0 && !searchingMusic) {
            EmptyBox("No matches for \"$query\".")
        } else {
            val posterWidth = if (isTv) 136.dp else 116.dp
            val squareWidth = if (isTv) 140.dp else 122.dp
            val wideWidth = if (isTv) 224.dp else 204.dp

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 1. Elevated Spotlight Top Result (Apple TV / Spotify hero card with blurred ambient glow)
                if (filter == "all" && topResult != null) {
                    item(key = "top-result") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionHeader("Top Result")
                            val isArtistTop = topResult.kindLabel == "ARTIST"
                            val isSongTop = topResult.kindLabel == "SONG"
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFF141624))
                                    .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
                                    .dpadFocusable(RoundedCornerShape(18.dp))
                                    .clickable { topResult.onActivate() },
                            ) {
                                // Subtle blurred artwork aura inside the Top Result card (no fallback icon on missing art)
                                if (!topResult.artworkUrl.isNullOrBlank()) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(context)
                                            .data(topResult.artworkUrl)
                                            .size(220)
                                            .precision(coil.size.Precision.INEXACT)
                                            .build(),
                                        imageLoader = LocalSoulSyncImageLoader.current,
                                        contentDescription = null,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .matchParentSize()
                                            .blur(36.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                0.0f to Color(0xD610121E),
                                                0.55f to Color(0xC2131626),
                                                1.0f to Color(0xE80C0E18),
                                            ),
                                        ),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = topResult.artworkUrl,
                                        contentDescription = topResult.title,
                                        fallbackIcon = if (isArtistTop) Icons.Default.Person else Icons.Default.MusicNote,
                                        modifier = Modifier
                                            .width(if (topResult.isPoster) 62.dp else 74.dp)
                                            .height(if (topResult.isPoster) 92.dp else 74.dp)
                                            .clip(if (isArtistTop) CircleShape else RoundedCornerShape(12.dp)),
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(SoulSyncColors.Accent.copy(alpha = 0.28f))
                                                .padding(horizontal = 9.dp, vertical = 2.5.dp),
                                        ) {
                                            Text(
                                                text = topResult.kindLabel,
                                                color = SoulSyncColors.AccentLight,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = 0.8.sp,
                                            )
                                        }
                                        Text(
                                            text = topResult.title,
                                            color = Color.White,
                                            fontSize = if (isTv) 21.sp else 18.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (topResult.subtitle.isNotBlank()) {
                                            Text(
                                                text = topResult.subtitle,
                                                color = SoulSyncColors.TextSoft,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color.White)
                                            .padding(horizontal = 14.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (isSongTop) Icons.Default.PlayArrow else Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = if (isSongTop) "Play" else "Open",
                                            color = Color.Black,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Movies — Horizontal Poster Shelf (Netflix / Apple TV style)
                if ((filter == "all" || filter == "movies") && movieHits.isNotEmpty()) {
                    item(key = "search-movies") {
                        MediaRail(
                            title = "Movies",
                            actionLabel = if (filter == "all" && movieHits.size > 6) "${movieHits.size} found" else null,
                            onAction = if (filter == "all" && movieHits.size > 6) ({ filter = "movies" }) else null,
                        ) {
                            items(movieHits, key = { "m-${it.id}" }) { m ->
                                PosterCard(
                                    title = m.title,
                                    subtitle = m.year?.toString(),
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                    badge = m.rating?.let { "★ %.1f".format(it) },
                                    width = posterWidth,
                                    onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                )
                            }
                        }
                    }
                }

                // 3. TV Shows — Horizontal Poster Shelf
                if ((filter == "all" || filter == "tv") && showHits.isNotEmpty()) {
                    item(key = "search-shows") {
                        MediaRail(
                            title = "TV Shows",
                            actionLabel = if (filter == "all" && showHits.size > 6) "${showHits.size} found" else null,
                            onAction = if (filter == "all" && showHits.size > 6) ({ filter = "tv" }) else null,
                        ) {
                            items(showHits, key = { "s-${it.id}" }) { s ->
                                PosterCard(
                                    title = s.title,
                                    subtitle = s.network ?: s.year?.toString(),
                                    artworkUrl = api.artwork.showPoster(s.id),
                                    badge = if (s.episodeCount > 0) "${s.episodeCount} eps" else null,
                                    width = posterWidth,
                                    onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                                )
                            }
                        }
                    }
                }

                // 4. Artists — Circular Plexamp / Spotify Artist Medallions
                if ((filter == "all" || filter == "music") && artists.isNotEmpty()) {
                    item(key = "search-artists") {
                        MediaRail(title = "Artists") {
                            items(artists, key = { "ar-${it.id}" }) { ar ->
                                val circleSize = if (isTv) 108.dp else 94.dp
                                Column(
                                    modifier = Modifier
                                        .width(circleSize + 10.dp)
                                        .dpadCardFocusable(RoundedCornerShape(14.dp))
                                        .clickable { onNavigate(PlayerDestination.ArtistDetail(ar.id)) }
                                        .padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    SoulSyncArtwork(
                                        url = api.artwork.resolve(ar.imageUrl ?: ar.remoteImageUrl),
                                        contentDescription = ar.name,
                                        fallbackIcon = Icons.Default.Person,
                                        modifier = Modifier
                                            .size(circleSize)
                                            .clip(CircleShape)
                                            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                                    )
                                    Text(
                                        text = ar.name,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${ar.albumCount} ${if (ar.albumCount == 1) "album" else "albums"}",
                                        color = SoulSyncColors.TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Albums — Horizontal Square Album Cover Shelf
                if ((filter == "all" || filter == "music") && albums.isNotEmpty()) {
                    item(key = "search-albums") {
                        MediaRail(title = "Albums") {
                            items(albums, key = { "al-${it.id}" }) { al ->
                                SquareMediaCard(
                                    title = al.title,
                                    subtitle = listOfNotNull(al.artistName, al.year?.toString()).joinToString(" · "),
                                    artworkUrl = api.artwork.resolve(al.imageUrl) ?: api.artwork.albumArtwork(al.id),
                                    fallbackIcon = Icons.Default.MusicNote,
                                    width = squareWidth,
                                    onClick = { onNavigate(PlayerDestination.AlbumDetail(al.id)) },
                                )
                            }
                        }
                    }
                }

                // 6. Songs — Clean Borderless Spotify / Plexamp Track List (zero white borders!)
                if ((filter == "all" || filter == "music") && songs.isNotEmpty()) {
                    item(key = "search-songs-hdr") {
                        SectionHeader("Songs")
                    }
                    itemsIndexed(songs, key = { _, song -> "so-${song.id}" }) { idx, song ->
                        SearchSongRow(
                            song = song,
                            artworkUrl = api.artwork.albumArtwork(song.albumId),
                            showDivider = idx < songs.lastIndex,
                            onClick = {
                                val playables = songs.map { s ->
                                    ActivePlayable(
                                        itemType = "track",
                                        itemId = s.id.toString(),
                                        title = s.title,
                                        subtitle = s.artistName ?: "Unknown Artist",
                                        artworkUrl = api.artwork.albumArtwork(s.albumId),
                                        streamUrl = api.streamUrl("track", s.id.toString()),
                                        durationMs = (s.duration ?: 0L).let { d -> if (d in 1L..9_999L) d * 1000L else d },
                                    )
                                }
                                coordinator.playAudio(playables[idx], playables.drop(idx + 1))
                            },
                        )
                    }
                }

                // 7. YouTube — Horizontal 16:9 Widescreen Video Shelf
                if ((filter == "all" || filter == "youtube") && ytHits.isNotEmpty()) {
                    item(key = "search-youtube") {
                        MediaRail(title = "YouTube") {
                            items(ytHits, key = { "y-${it.id}" }) { y ->
                                WideMediaCard(
                                    title = y.title,
                                    subtitle = y.channelTitle.orEmpty(),
                                    artworkUrl = api.artwork.resolve(y.thumbnailUrl),
                                    width = wideWidth,
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
                    }
                }

                // 8. Podcasts — Horizontal Square Cover Shelf
                if ((filter == "all" || filter == "podcasts") && podcastHits.isNotEmpty()) {
                    item(key = "search-podcasts") {
                        MediaRail(title = "Podcasts") {
                            items(podcastHits, key = { "p-${it.id}" }) { p ->
                                SquareMediaCard(
                                    title = p.title,
                                    subtitle = p.author.orEmpty(),
                                    artworkUrl = api.artwork.resolve(p.artworkUrl),
                                    fallbackIcon = Icons.Default.Podcasts,
                                    width = squareWidth,
                                    onClick = { onNavigate(PlayerDestination.PodcastDetail(p)) },
                                )
                            }
                        }
                    }
                }

                // 9. Audiobooks — Horizontal Square Cover Shelf
                if ((filter == "all" || filter == "audiobooks") && bookHits.isNotEmpty()) {
                    item(key = "search-audiobooks") {
                        MediaRail(title = "Audiobooks") {
                            items(bookHits, key = { "b-${it.asin}" }) { b ->
                                SquareMediaCard(
                                    title = b.title,
                                    subtitle = b.author.orEmpty(),
                                    artworkUrl = api.artwork.resolve(b.coverUrl) ?: api.artwork.audiobookCover(b.asin),
                                    fallbackIcon = Icons.Default.Headphones,
                                    width = squareWidth,
                                    onClick = { onNavigate(PlayerDestination.AudiobookDetail(b)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSongRow(
    song: net.ssync.player.LibSong,
    artworkUrl: String?,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val rawDur = song.duration ?: 0L
    val durSec = (if (rawDur > 10_000L) rawDur / 1000L else rawDur).coerceAtLeast(0L)
    val durText = if (durSec > 0L) "%d:%02d".format(durSec / 60L, durSec % 60L) else null
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .dpadFocusable(RoundedCornerShape(10.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SoulSyncArtwork(
                url = artworkUrl,
                contentDescription = song.title,
                fallbackIcon = Icons.Default.MusicNote,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = song.title,
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = listOfNotNull(song.artistName, song.albumTitle).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        color = SoulSyncColors.TextSecondary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!durText.isNullOrBlank()) {
                Text(
                    text = durText,
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play ${song.title}",
                tint = SoulSyncColors.TextSoft,
                modifier = Modifier.size(20.dp),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 68.dp, end = 8.dp)
                    .height(0.5.dp)
                    .background(Color.White.copy(alpha = 0.06f)),
            )
        }
    }
}


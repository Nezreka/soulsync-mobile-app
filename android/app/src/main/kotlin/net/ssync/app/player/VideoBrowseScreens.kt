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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.CatalogMovie
import net.ssync.player.CatalogRecentEpisode
import net.ssync.player.CatalogShow
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient

private enum class VideoBrowseTab(val label: String) {
    FOR_YOU("For you"),
    LIBRARY("Library"),
    COLLECTIONS("Collections"),
    CATEGORIES("Categories"),
}

private enum class VideoSort(val label: String) {
    ADDED("Recently added"),
    TITLE("A to Z"),
    YEAR("Newest"),
    RATING("Highest rated"),
}

private fun decadeOf(year: Int?): String? = year?.let { "${(it / 10) * 10}s" }

@Composable
fun MoviesScreen(
    api: PlayerApiClient,
    onMovieClick: (Int) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var movies by remember { mutableStateOf<List<CatalogMovie>>(emptyList()) }
    var studioCollections by remember { mutableStateOf<List<net.ssync.player.StudioCollection>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(VideoBrowseTab.FOR_YOU) }
    var sort by remember { mutableStateOf(VideoSort.ADDED) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var selectedDecade by remember { mutableStateOf<String?>(null) }
    var selectedCollection by remember { mutableStateOf<String?>(null) }
    var selectedStudioIds by remember { mutableStateOf<Set<Int>?>(null) }
    var gridView by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val res = api.fetchVideoCatalog()) {
            is MusicApiResult.Success -> movies = res.data.movies
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        (api.fetchCollections() as? MusicApiResult.Success)?.let {
            studioCollections = it.data.filter { c -> c.mediaType == "movie" }
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (movies.isEmpty()) {
        EmptyBox("No movies in your SoulSync library yet.")
        return
    }

    val genres = remember(movies) {
        movies.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    val decades = remember(movies) {
        movies.mapNotNull { decadeOf(it.year) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.key }.map { it.key to it.value }
    }
    val collections = remember(movies) {
        val byCol = movies.filter { !it.collection.isNullOrBlank() }
            .groupBy { it.collection!! }
            .filter { it.value.size >= 2 }
        if (byCol.isNotEmpty()) {
            byCol.entries.sortedByDescending { it.value.size }
        } else {
            movies.filter { !it.studio.isNullOrBlank() }
                .groupBy { it.studio!! }
                .filter { it.value.size >= 2 }
                .entries.sortedByDescending { it.value.size }
        }
    }

    val filtered = remember(movies, selectedGenre, selectedDecade, selectedCollection, selectedStudioIds, sort) {
        val base = movies.filter { m ->
            (selectedGenre == null || m.genres.any { it.equals(selectedGenre, ignoreCase = true) }) &&
                (selectedDecade == null || decadeOf(m.year) == selectedDecade) &&
                (selectedStudioIds == null || selectedStudioIds!!.contains(m.id)) &&
                (selectedStudioIds != null || selectedCollection == null || m.collection == selectedCollection || m.studio == selectedCollection)
        }
        when (sort) {
            VideoSort.ADDED -> base.sortedByDescending { it.addedAt.orEmpty() }
            VideoSort.TITLE -> base.sortedBy { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
            VideoSort.YEAR -> base.sortedByDescending { it.year ?: 0 }
            VideoSort.RATING -> base.sortedByDescending { it.rating ?: 0.0 }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeaderAndTabs(
            title = "Movies",
            tabs = VideoBrowseTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { next ->
                tab = next
                if (next != VideoBrowseTab.LIBRARY) {
                    selectedCollection = null
                }
            },
        )

        when (tab) {
            VideoBrowseTab.FOR_YOU -> {
                val recent = remember(movies) { movies.sortedByDescending { it.addedAt.orEmpty() }.take(20) }
                val topRated = remember(movies) { movies.sortedByDescending { it.rating ?: 0.0 }.take(10) }
                val topGenres = remember(genres) { genres.take(4) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    item {
                        MediaRail("Recently Added", actionLabel = "Library", onAction = { tab = VideoBrowseTab.LIBRARY }) {
                            items(recent, key = { it.id }) { m ->
                                PosterCard(
                                    title = m.title,
                                    subtitle = m.year?.toString(),
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                    badge = m.rating?.let { "%.1f".format(it) },
                                    onClick = { onMovieClick(m.id) },
                                )
                            }
                        }
                    }
                    item {
                        MediaRail("Top 10 Highest Rated") {
                            items(topRated.withIndex().toList(), key = { "top-${it.value.id}" }) { (idx, m) ->
                                RankedPosterCard(
                                    rank = idx + 1,
                                    title = m.title,
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                    onClick = { onMovieClick(m.id) },
                                )
                            }
                        }
                    }
                    topGenres.forEach { g ->
                        val itemsForGenre = movies.filter { it.genres.contains(g) }.take(18)
                        if (itemsForGenre.size >= 3) {
                            item(key = "g-$g") {
                                MediaRail(
                                    title = g,
                                    actionLabel = "All",
                                    onAction = {
                                        selectedGenre = g
                                        selectedDecade = null
                                        selectedCollection = null
                                        tab = VideoBrowseTab.LIBRARY
                                    },
                                ) {
                                    items(itemsForGenre, key = { it.id }) { m ->
                                        PosterCard(
                                            title = m.title,
                                            subtitle = m.year?.toString(),
                                            artworkUrl = api.artwork.moviePoster(m.id),
                                            onClick = { onMovieClick(m.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VideoBrowseTab.LIBRARY -> {
                LibraryFilterBar(
                    countLabel = "${filtered.size} ${if (filtered.size == 1) "film" else "films"}",
                    sort = sort,
                    onSortChange = { sort = it },
                    genres = genres,
                    selectedGenre = selectedGenre,
                    onGenreChange = { selectedGenre = it },
                    activeChip = selectedCollection ?: selectedDecade,
                    onClearActiveChip = {
                        selectedCollection = null
                        selectedStudioIds = null
                        selectedDecade = null
                    },
                    gridView = gridView,
                    onToggleView = { gridView = !gridView },
                )
                if (filtered.isEmpty()) {
                    EmptyBox("No movies match your filters.")
                } else if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 116.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { m ->
                            PosterCard(
                                title = m.title,
                                subtitle = m.year?.toString(),
                                artworkUrl = api.artwork.moviePoster(m.id),
                                badge = m.rating?.let { "%.1f".format(it) },
                                onClick = { onMovieClick(m.id) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { m ->
                            CompactMediaListRow(
                                title = m.title,
                                subtitle = listOfNotNull(
                                    m.year?.toString(),
                                    m.contentRating,
                                    m.rating?.let { "★ %.1f".format(it) },
                                    m.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                artworkUrl = api.artwork.moviePoster(m.id),
                                onClick = { onMovieClick(m.id) },
                            )
                        }
                    }
                }
            }

            VideoBrowseTab.COLLECTIONS -> {
                val movieIds = remember(movies) { movies.map { it.id }.toSet() }
                val visibleStudio = remember(studioCollections, movieIds) {
                    studioCollections.mapNotNull { c ->
                        val mem = c.members.filter { movieIds.contains(it) }
                        if (mem.isEmpty()) null else c to mem
                    }
                }
                if (visibleStudio.isEmpty() && collections.isEmpty()) {
                    EmptyBox("No collections found yet.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (visibleStudio.isNotEmpty()) {
                            items(visibleStudio, key = { (c, _) -> "studio-col-${c.id}" }) { (c, mem) ->
                                val posters = buildList {
                                    api.artwork.resolve(c.poster)?.let { add(it) }
                                    mem.take(4).forEach { id ->
                                        val u = api.artwork.moviePoster(id)
                                        if (u !in this) add(u)
                                    }
                                }.take(4)
                                CollectionRowCard(
                                    name = if (c.pinned) "★ ${c.name}" else c.name,
                                    countLabel = "${mem.size} films · SoulSync Collection",
                                    posterUrls = posters,
                                    onClick = {
                                        selectedCollection = c.name
                                        selectedStudioIds = mem.toSet()
                                        selectedGenre = null
                                        selectedDecade = null
                                        tab = VideoBrowseTab.LIBRARY
                                    },
                                )
                            }
                        }
                        items(collections, key = { "tmdb-col-${it.key}" }) { (name, films) ->
                            CollectionRowCard(
                                name = name,
                                countLabel = "${films.size} films",
                                posterUrls = films.take(4).map { api.artwork.moviePoster(it.id) },
                                onClick = {
                                    selectedCollection = name
                                    selectedStudioIds = null
                                    selectedGenre = null
                                    selectedDecade = null
                                    tab = VideoBrowseTab.LIBRARY
                                },
                            )
                        }
                    }
                }
            }

            VideoBrowseTab.CATEGORIES -> {
                CategoriesTabView(
                    genres = genres.map { g -> g to movies.count { it.genres.contains(g) } },
                    decades = decades,
                    onPickGenre = { g ->
                        selectedGenre = g
                        selectedDecade = null
                        selectedCollection = null
                        tab = VideoBrowseTab.LIBRARY
                    },
                    onPickDecade = { d ->
                        selectedDecade = d
                        selectedGenre = null
                        selectedCollection = null
                        tab = VideoBrowseTab.LIBRARY
                    },
                )
            }
        }
    }
}

@Composable
fun TvScreen(
    api: PlayerApiClient,
    onShowClick: (Int) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    var shows by remember { mutableStateOf<List<CatalogShow>>(emptyList()) }
    var recentEpisodes by remember { mutableStateOf<List<CatalogRecentEpisode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(VideoBrowseTab.FOR_YOU) }
    var sort by remember { mutableStateOf(VideoSort.ADDED) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var selectedDecade by remember { mutableStateOf<String?>(null) }
    var selectedNetwork by remember { mutableStateOf<String?>(null) }
    var gridView by remember { mutableStateOf(true) }

    LaunchedEffect(api) {
        isLoading = true
        when (val res = api.fetchVideoCatalog()) {
            is MusicApiResult.Success -> {
                shows = res.data.shows
                recentEpisodes = res.data.recentEpisodes
            }
            MusicApiResult.DeviceRevoked -> onDeviceRevoked()
            is MusicApiResult.Error -> Unit
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }
    if (shows.isEmpty()) {
        EmptyBox("No TV shows in your SoulSync library yet.")
        return
    }

    val genres = remember(shows) {
        shows.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
    }
    val decades = remember(shows) {
        shows.mapNotNull { decadeOf(it.year) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.key }.map { it.key to it.value }
    }
    val networks = remember(shows) {
        shows.filter { !it.network.isNullOrBlank() }
            .groupBy { it.network!! }
            .entries.sortedByDescending { it.value.size }
    }

    val filtered = remember(shows, selectedGenre, selectedDecade, selectedNetwork, sort) {
        val base = shows.filter { s ->
            (selectedGenre == null || s.genres.any { it.equals(selectedGenre, ignoreCase = true) }) &&
                (selectedDecade == null || decadeOf(s.year) == selectedDecade) &&
                (selectedNetwork == null || s.network == selectedNetwork)
        }
        when (sort) {
            VideoSort.ADDED -> base.sortedByDescending { it.addedAt.orEmpty() }
            VideoSort.TITLE -> base.sortedBy { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
            VideoSort.YEAR -> base.sortedByDescending { it.year ?: 0 }
            VideoSort.RATING -> base.sortedByDescending { it.rating ?: 0.0 }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeaderAndTabs(
            title = "TV Shows",
            tabs = VideoBrowseTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { next ->
                tab = next
                if (next != VideoBrowseTab.LIBRARY) {
                    selectedNetwork = null
                }
            },
        )

        when (tab) {
            VideoBrowseTab.FOR_YOU -> {
                val recent = remember(shows) { shows.sortedByDescending { it.addedAt.orEmpty() }.take(20) }
                val topRated = remember(shows) { shows.sortedByDescending { it.rating ?: 0.0 }.take(10) }
                val topGenres = remember(genres) { genres.take(4) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    if (recentEpisodes.isNotEmpty()) {
                        item {
                            MediaRail("New Episodes") {
                                items(recentEpisodes.take(18), key = { it.id }) { ep ->
                                    val art = if (ep.hasStill) api.artwork.episodeStill(ep.id)
                                    else api.artwork.showBackdrop(ep.showId)
                                    WideMediaCard(
                                        title = ep.showTitle,
                                        subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                            (ep.title?.let { " · $it" } ?: ""),
                                        artworkUrl = art,
                                        onClick = { onShowClick(ep.showId) },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        MediaRail("Recently Added Shows", actionLabel = "Library", onAction = { tab = VideoBrowseTab.LIBRARY }) {
                            items(recent, key = { it.id }) { s ->
                                PosterCard(
                                    title = s.title,
                                    subtitle = "${s.episodeCount} episodes",
                                    artworkUrl = api.artwork.showPoster(s.id),
                                    badge = s.rating?.let { "%.1f".format(it) },
                                    onClick = { onShowClick(s.id) },
                                )
                            }
                        }
                    }
                    item {
                        MediaRail("Top 10 Highest Rated") {
                            items(topRated.withIndex().toList(), key = { "top-${it.value.id}" }) { (idx, s) ->
                                RankedPosterCard(
                                    rank = idx + 1,
                                    title = s.title,
                                    artworkUrl = api.artwork.showPoster(s.id),
                                    onClick = { onShowClick(s.id) },
                                )
                            }
                        }
                    }
                    topGenres.forEach { g ->
                        val itemsForGenre = shows.filter { it.genres.contains(g) }.take(18)
                        if (itemsForGenre.size >= 2) {
                            item(key = "g-$g") {
                                MediaRail(
                                    title = g,
                                    actionLabel = "All",
                                    onAction = {
                                        selectedGenre = g
                                        selectedDecade = null
                                        selectedNetwork = null
                                        tab = VideoBrowseTab.LIBRARY
                                    },
                                ) {
                                    items(itemsForGenre, key = { it.id }) { s ->
                                        PosterCard(
                                            title = s.title,
                                            subtitle = "${s.episodeCount} episodes",
                                            artworkUrl = api.artwork.showPoster(s.id),
                                            onClick = { onShowClick(s.id) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VideoBrowseTab.LIBRARY -> {
                LibraryFilterBar(
                    countLabel = "${filtered.size} ${if (filtered.size == 1) "show" else "shows"}",
                    sort = sort,
                    onSortChange = { sort = it },
                    genres = genres,
                    selectedGenre = selectedGenre,
                    onGenreChange = { selectedGenre = it },
                    activeChip = selectedNetwork ?: selectedDecade,
                    onClearActiveChip = {
                        selectedNetwork = null
                        selectedDecade = null
                    },
                    gridView = gridView,
                    onToggleView = { gridView = !gridView },
                )
                if (filtered.isEmpty()) {
                    EmptyBox("No TV shows match your filters.")
                } else if (gridView) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 116.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { s ->
                            PosterCard(
                                title = s.title,
                                subtitle = "${s.episodeCount} episodes",
                                artworkUrl = api.artwork.showPoster(s.id),
                                badge = s.rating?.let { "%.1f".format(it) },
                                onClick = { onShowClick(s.id) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { s ->
                            CompactMediaListRow(
                                title = s.title,
                                subtitle = listOfNotNull(
                                    s.year?.toString(),
                                    s.network,
                                    "${s.episodeCount} eps",
                                    s.rating?.let { "★ %.1f".format(it) },
                                ).joinToString(" · "),
                                artworkUrl = api.artwork.showPoster(s.id),
                                onClick = { onShowClick(s.id) },
                            )
                        }
                    }
                }
            }

            VideoBrowseTab.COLLECTIONS -> {
                if (networks.isEmpty()) {
                    EmptyBox("No network collections available yet.")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(networks, key = { it.key }) { (net, list) ->
                            CollectionRowCard(
                                name = net,
                                countLabel = "${list.size} ${if (list.size == 1) "show" else "shows"}",
                                posterUrls = list.take(4).map { api.artwork.showPoster(it.id) },
                                onClick = {
                                    selectedNetwork = net
                                    selectedGenre = null
                                    selectedDecade = null
                                    tab = VideoBrowseTab.LIBRARY
                                },
                            )
                        }
                    }
                }
            }

            VideoBrowseTab.CATEGORIES -> {
                CategoriesTabView(
                    genres = genres.map { g -> g to shows.count { it.genres.contains(g) } },
                    decades = decades,
                    onPickGenre = { g ->
                        selectedGenre = g
                        selectedDecade = null
                        selectedNetwork = null
                        tab = VideoBrowseTab.LIBRARY
                    },
                    onPickDecade = { d ->
                        selectedDecade = d
                        selectedGenre = null
                        selectedNetwork = null
                        tab = VideoBrowseTab.LIBRARY
                    },
                )
            }
        }
    }
}

@Composable
internal fun <T> BrowseHeaderAndTabs(
    title: String,
    tabs: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs, key = { it.second }) { (value, label) ->
                val on = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                        .border(
                            1.dp,
                            if (on) SoulSyncColors.Accent.copy(alpha = 0.6f) else SoulSyncColors.Line,
                            RoundedCornerShape(999.dp),
                        )
                        .dpadFocusable(RoundedCornerShape(999.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        color = if (on) Color.White else SoulSyncColors.TextSoft,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryFilterBar(
    countLabel: String,
    sort: VideoSort,
    onSortChange: (VideoSort) -> Unit,
    genres: List<String>,
    selectedGenre: String?,
    onGenreChange: (String?) -> Unit,
    activeChip: String?,
    onClearActiveChip: () -> Unit,
    gridView: Boolean,
    onToggleView: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = countLabel, color = SoulSyncColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (activeChip != null) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(SoulSyncColors.Accent.copy(alpha = 0.18f))
                            .border(1.dp, SoulSyncColors.Accent.copy(alpha = 0.5f), CircleShape)
                            .clickable(onClick = onClearActiveChip)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = activeChip, color = SoulSyncColors.AccentLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = SoulSyncColors.AccentLight, modifier = Modifier.size(13.dp))
                    }
                }
            }
            IconButton(
                onClick = onToggleView,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .dpadFocusable(CircleShape),
            ) {
                Icon(
                    imageVector = if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                    contentDescription = "Toggle grid/list",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(VideoSort.entries, key = { it.name }) { s ->
                val on = s == sort
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
                        .border(1.dp, if (on) Color.White.copy(alpha = 0.35f) else SoulSyncColors.Line, RoundedCornerShape(8.dp))
                        .clickable { onSortChange(s) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = s.label,
                        color = if (on) Color.White else SoulSyncColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        if (genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "all-genres") {
                    GenrePill(label = "All genres", selected = selectedGenre == null) { onGenreChange(null) }
                }
                items(genres, key = { it }) { g ->
                    GenrePill(label = g, selected = selectedGenre == g) {
                        onGenreChange(if (selectedGenre == g) null else g)
                    }
                }
            }
        }
    }
}

@Composable
private fun GenrePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) SoulSyncColors.Accent.copy(alpha = 0.2f) else Color.Transparent)
            .border(1.dp, if (selected) SoulSyncColors.Accent.copy(alpha = 0.55f) else SoulSyncColors.Line, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (selected) SoulSyncColors.AccentLight else SoulSyncColors.TextSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun CompactMediaListRow(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            SoulSyncArtwork(url = artworkUrl, contentDescription = title, modifier = Modifier.fillMaxSize())
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = SoulSyncColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun CollectionRowCard(
    name: String,
    countLabel: String,
    posterUrls: List<String>,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.055f), Color.White.copy(alpha = 0.02f)),
                ),
            )
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(14.dp))
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(text = countLabel, color = SoulSyncColors.AccentLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            posterUrls.forEach { url ->
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(94.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    SoulSyncArtwork(url = url, contentDescription = name, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CategoriesTabView(
    genres: List<Pair<String, Int>>,
    decades: List<Pair<String, Int>>,
    onPickGenre: (String) -> Unit,
    onPickDecade: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (genres.isNotEmpty()) {
            item {
                Text("Genres", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(genres.chunked(2)) { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (g, count) ->
                        CategoryTile(
                            title = g,
                            subtitle = "$count titles",
                            modifier = Modifier.weight(1f),
                            onClick = { onPickGenre(g) },
                        )
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        if (decades.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Decades", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(decades.chunked(2)) { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (d, count) ->
                        CategoryTile(
                            title = d,
                            subtitle = "$count titles",
                            modifier = Modifier.weight(1f),
                            onClick = { onPickDecade(d) },
                        )
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(SoulSyncColors.Accent.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f)),
                ),
            )
            .border(1.dp, SoulSyncColors.Line, RoundedCornerShape(12.dp))
            .dpadFocusable(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(text = title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = subtitle, color = SoulSyncColors.TextSecondary, fontSize = 12.sp)
    }
}

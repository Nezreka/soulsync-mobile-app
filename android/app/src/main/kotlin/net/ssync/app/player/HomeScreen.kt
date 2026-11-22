package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.pairing.ui.dpadFocusable
import net.ssync.player.HomeLayout
import net.ssync.player.LibAlbum
import net.ssync.player.MusicApiResult
import net.ssync.player.OwnedBook
import net.ssync.player.PlayerApiClient
import net.ssync.player.PodcastSub
import net.ssync.player.ProgressRow
import net.ssync.player.VideoCatalog
import net.ssync.player.WatchlistItem

@Composable
fun HomeScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var continueItems by remember { mutableStateOf<List<ProgressRow>>(emptyList()) }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var watchlist by remember { mutableStateOf<List<WatchlistItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<LibAlbum>>(emptyList()) }
    var podcasts by remember { mutableStateOf<List<PodcastSub>>(emptyList()) }
    var books by remember { mutableStateOf<List<OwnedBook>>(emptyList()) }
    var homeLayout by remember { mutableStateOf(HomeLayout()) }
    var layoutShared by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(false) }

    // Auto-rotating Netflix-style Billboard slide index
    var billboardIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(api) {
        isLoading = true
        coroutineScope {
            val cDef = async { api.fetchContinue() }
            val vDef = async { api.fetchVideoCatalog() }
            val wDef = async { api.fetchWatchlist() }
            val aDef = async { api.fetchAlbums(limit = 20) }
            val pDef = async { api.fetchPodcastSubs() }
            val bDef = async { api.fetchAudiobooks() }
            val lDef = async { api.fetchHomeLayout(device = "phone") }

            val results = listOf(cDef.await(), vDef.await(), wDef.await(), aDef.await(), pDef.await(), bDef.await())
            if (results.any { it is MusicApiResult.DeviceRevoked }) {
                onDeviceRevoked()
                return@coroutineScope
            }
            (results[0] as? MusicApiResult.Success<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                continueItems = it.data as List<ProgressRow>
            }
            (results[1] as? MusicApiResult.Success<*>)?.let { catalog = it.data as VideoCatalog }
            (results[2] as? MusicApiResult.Success<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                watchlist = it.data as List<WatchlistItem>
            }
            (results[3] as? MusicApiResult.Success<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                albums = it.data as List<LibAlbum>
            }
            (results[4] as? MusicApiResult.Success<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                podcasts = it.data as List<PodcastSub>
            }
            (results[5] as? MusicApiResult.Success<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                books = it.data as List<OwnedBook>
            }
            (lDef.await() as? MusicApiResult.Success)?.let { resp ->
                if (resp.data.layout.rows.isNotEmpty()) {
                    homeLayout = resp.data.layout
                    layoutShared = resp.data.shared
                }
            }
        }
        isLoading = false
    }

    val billboardMovies = catalog.movies.take(5)
    LaunchedEffect(billboardMovies.size) {
        if (billboardMovies.size > 1) {
            while (true) {
                delay(7_000L)
                billboardIndex = (billboardIndex + 1) % billboardMovies.size
            }
        }
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        // Netflix-style auto-rotating Hero Billboard (toggleable via Home Layout)
        if (homeLayout.billboard && billboardMovies.isNotEmpty()) {
            item(key = "billboard") {
                val hero = billboardMovies[billboardIndex % billboardMovies.size]
                val saved = watchlist.any { it.itemType == "movie" && it.itemId == hero.id.toString() }
                Box {
                    HeroSpotlight(
                        title = hero.title,
                        subtitle = listOfNotNull(
                            hero.year?.toString(),
                            hero.contentRating,
                            hero.rating?.let { "★ %.1f".format(it) },
                            hero.genres.firstOrNull(),
                        ).joinToString(" • "),
                        backdropUrl = api.artwork.movieBackdrop(hero.id),
                        inWatchlist = saved,
                        onPlay = {
                            coordinator.playVideo(
                                ActivePlayable(
                                    itemType = "movie",
                                    itemId = hero.id.toString(),
                                    title = hero.title,
                                    subtitle = hero.year?.toString().orEmpty(),
                                    artworkUrl = api.artwork.moviePoster(hero.id),
                                    streamUrl = api.streamUrl("movie", hero.id.toString()),
                                    isVideo = true,
                                ),
                            )
                        },
                        onToggleWatchlist = {
                            scope.launch {
                                if (saved) api.removeFromWatchlist("movie", hero.id.toString())
                                else api.addToWatchlist("movie", hero.id.toString())
                                (api.fetchWatchlist() as? MusicApiResult.Success)?.let { watchlist = it.data }
                            }
                        },
                    )
                    if (billboardMovies.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 18.dp, bottom = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            billboardMovies.indices.forEach { idx ->
                                Box(
                                    modifier = Modifier
                                        .size(if (idx == billboardIndex) 8.dp else 6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (idx == billboardIndex) SoulSyncColors.AccentLight
                                            else SoulSyncColors.TextSecondary.copy(alpha = 0.5f),
                                        )
                                        .clickable { billboardIndex = idx },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Dynamic customizable rows driven by HomeLayout (order, visibility, style, genre rows)
        homeLayout.rows.filter { it.on }.forEach { row ->
            val cardWidth = if (row.style == "large") 150.dp else 122.dp
            when {
                row.id == "continue" && continueItems.isNotEmpty() -> {
                    item(key = "row-continue") {
                        MediaRail(title = "Continue") {
                            items(continueItems, key = { "${it.itemType}:${it.itemId}" }) { item ->
                                WideMediaCard(
                                    title = item.meta?.title ?: item.itemId,
                                    subtitle = item.meta?.subtitle ?: item.itemType.replaceFirstChar { it.uppercase() },
                                    artworkUrl = api.artwork.resolve(item.meta?.backdrop ?: item.meta?.artwork),
                                    progress = item.progressFraction,
                                    onClick = {
                                        val isVideo = item.itemType in setOf("movie", "episode", "youtube")
                                        val playable = ActivePlayable(
                                            itemType = item.itemType,
                                            itemId = item.itemId,
                                            title = item.meta?.title ?: item.itemId,
                                            subtitle = item.meta?.subtitle.orEmpty(),
                                            artworkUrl = api.artwork.resolve(item.meta?.artwork),
                                            streamUrl = api.streamUrl(item.itemType, item.itemId),
                                            isVideo = isVideo,
                                            resumePositionMs = (item.positionSeconds * 1000.0).toLong(),
                                        )
                                        if (isVideo) coordinator.playVideo(playable) else coordinator.playAudio(playable)
                                    },
                                )
                            }
                        }
                    }
                }
                row.id == "watchlist" && watchlist.isNotEmpty() -> {
                    item(key = "row-watchlist") {
                        MediaRail("Watchlist", actionLabel = "See all", onAction = { onNavigate(PlayerDestination.Watchlist) }) {
                            items(watchlist, key = { "${it.itemType}:${it.itemId}" }) { w ->
                                PosterCard(
                                    title = w.meta?.title ?: w.itemId,
                                    subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                                    artworkUrl = api.artwork.resolve(w.meta?.artwork),
                                    width = cardWidth,
                                    onClick = {
                                        val idInt = w.itemId.toIntOrNull()
                                        if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                                        if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                                    },
                                )
                            }
                        }
                    }
                }
                row.id == "top-rated" && catalog.movies.isNotEmpty() -> {
                    item(key = "row-top-rated") {
                        val top10 = catalog.movies.sortedByDescending { it.rating ?: 0.0 }.take(10)
                        MediaRail("Top 10 in Your Library") {
                            itemsIndexed(top10, key = { _, m -> "top-${m.id}" }) { idx, m ->
                                if (row.style == "ranked") {
                                    RankedPosterCard(
                                        rank = idx + 1,
                                        title = m.title,
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                } else {
                                    PosterCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        badge = m.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                row.id == "recent-movies" && catalog.movies.isNotEmpty() -> {
                    item(key = "row-movies") {
                        MediaRail("Movies", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Movies) }) {
                            items(catalog.movies.take(20), key = { it.id }) { m ->
                                if (row.style == "wide") {
                                    WideMediaCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.movieBackdrop(m.id),
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                } else {
                                    PosterCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        badge = m.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                row.id == "recent-shows" && catalog.shows.isNotEmpty() -> {
                    item(key = "row-shows") {
                        MediaRail("TV Shows", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Tv) }) {
                            items(catalog.shows.take(20), key = { it.id }) { show ->
                                PosterCard(
                                    title = show.title,
                                    subtitle = "${show.episodeCount} episodes",
                                    artworkUrl = api.artwork.showPoster(show.id),
                                    badge = show.rating?.let { "%.1f".format(it) },
                                    width = cardWidth,
                                    onClick = { onNavigate(PlayerDestination.ShowDetail(show.id)) },
                                )
                            }
                        }
                    }
                }
                row.id == "recent-music" && albums.isNotEmpty() -> {
                    item(key = "row-music") {
                        MediaRail("Music", actionLabel = "Library", onAction = { onNavigate(PlayerDestination.Music) }) {
                            items(albums.take(20), key = { it.id }) { album ->
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
                row.id == "youtube" && catalog.youtube.isNotEmpty() -> {
                    item(key = "row-yt") {
                        MediaRail("YouTube", actionLabel = "All", onAction = { onNavigate(PlayerDestination.YouTube) }) {
                            items(catalog.youtube.take(15), key = { it.id }) { yt ->
                                WideMediaCard(
                                    title = yt.title,
                                    subtitle = yt.channelTitle,
                                    artworkUrl = api.artwork.resolve(yt.thumbnailUrl),
                                    onClick = {
                                        coordinator.playVideo(
                                            ActivePlayable(
                                                itemType = "youtube",
                                                itemId = yt.id,
                                                title = yt.title,
                                                subtitle = yt.channelTitle.orEmpty(),
                                                artworkUrl = api.artwork.resolve(yt.thumbnailUrl),
                                                streamUrl = api.streamUrl("youtube", yt.id),
                                                isVideo = true,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                row.id == "podcasts" && podcasts.isNotEmpty() -> {
                    item(key = "row-podcasts") {
                        MediaRail("Podcasts", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Podcasts) }) {
                            items(podcasts.take(15), key = { it.id }) { sub ->
                                SquareMediaCard(
                                    title = sub.title,
                                    subtitle = sub.author,
                                    artworkUrl = api.artwork.resolve(sub.artworkUrl),
                                    fallbackIcon = Icons.Default.Podcasts,
                                    onClick = { onNavigate(PlayerDestination.PodcastDetail(sub)) },
                                )
                            }
                        }
                    }
                }
                row.id == "audiobooks" && books.isNotEmpty() -> {
                    item(key = "row-books") {
                        MediaRail("Audiobooks", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Audiobooks) }) {
                            items(books.take(15), key = { it.asin }) { book ->
                                SquareMediaCard(
                                    title = book.title,
                                    subtitle = book.author,
                                    artworkUrl = api.artwork.resolve(book.coverUrl),
                                    fallbackIcon = Icons.Default.Headphones,
                                    onClick = { onNavigate(PlayerDestination.AudiobookDetail(book)) },
                                )
                            }
                        }
                    }
                }
                row.id.startsWith("genre:movie:") -> {
                    val genre = row.id.removePrefix("genre:movie:")
                    val matches = catalog.movies.filter { it.genres.any { g -> g.equals(genre, ignoreCase = true) } }
                    if (matches.isNotEmpty()) {
                        item(key = "row-${row.id}") {
                            MediaRail("$genre Movies") {
                                items(matches, key = { it.id }) { m ->
                                    PosterCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Customize Home Layout Footer CTA
        item(key = "customize-home") {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(
                    onClick = { showEditor = true },
                    border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                    modifier = Modifier.dpadFocusable(),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = SoulSyncColors.AccentLight, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Customize Home Layout", color = SoulSyncColors.TextPrimary)
                }
            }
        }
    }

    if (showEditor) {
        val allGenres = catalog.movies.flatMap { it.genres }.distinct().sorted()
        HomeEditorSheet(
            initialLayout = homeLayout,
            initialShared = layoutShared,
            availableMovieGenres = allGenres,
            onSave = { updated, isShared ->
                homeLayout = updated
                layoutShared = isShared
                showEditor = false
                scope.launch { api.saveHomeLayout(updated, device = "phone", shared = isShared) }
            },
            onReset = {
                homeLayout = HomeLayout()
                showEditor = false
                scope.launch { api.resetHomeLayout(device = "phone") }
            },
            onDismiss = { showEditor = false },
        )
    }
}

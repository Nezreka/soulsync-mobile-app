package net.ssync.app.player

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    LaunchedEffect(api) {
        isLoading = true
        coroutineScope {
            val cDef = async { api.fetchContinue() }
            val vDef = async { api.fetchVideoCatalog() }
            val wDef = async { api.fetchWatchlist() }
            val aDef = async { api.fetchAlbums(limit = 20) }
            val pDef = async { api.fetchPodcastSubs() }
            val bDef = async { api.fetchAudiobooks() }

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
        }
        isLoading = false
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    val heroMovie = catalog.movies.firstOrNull()
    val heroShow = catalog.shows.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        // Hero Spotlight
        if (heroMovie != null) {
            item(key = "hero-movie") {
                val saved = watchlist.any { it.itemType == "movie" && it.itemId == heroMovie.id.toString() }
                HeroSpotlight(
                    title = heroMovie.title,
                    subtitle = listOfNotNull(
                        heroMovie.year?.toString(),
                        heroMovie.contentRating,
                        heroMovie.genres.take(2).joinToString(" • ").takeIf { it.isNotBlank() },
                    ).joinToString("  •  "),
                    backdropUrl = api.artwork.movieBackdrop(heroMovie.id),
                    inWatchlist = saved,
                    onPlay = {
                        coordinator.playVideo(
                            ActivePlayable(
                                itemType = "movie",
                                itemId = heroMovie.id.toString(),
                                title = heroMovie.title,
                                subtitle = heroMovie.year?.toString().orEmpty(),
                                artworkUrl = api.artwork.moviePoster(heroMovie.id),
                                streamUrl = api.streamUrl("movie", heroMovie.id.toString()),
                                isVideo = true,
                            ),
                        )
                    },
                    onToggleWatchlist = {
                        scope.launch {
                            if (saved) {
                                api.removeFromWatchlist("movie", heroMovie.id.toString())
                            } else {
                                api.addToWatchlist("movie", heroMovie.id.toString())
                            }
                            (api.fetchWatchlist() as? MusicApiResult.Success)?.let { watchlist = it.data }
                        }
                    },
                )
            }
        } else if (heroShow != null) {
            item(key = "hero-show") {
                HeroSpotlight(
                    title = heroShow.title,
                    subtitle = listOfNotNull(heroShow.year?.toString(), heroShow.network).joinToString(" • "),
                    backdropUrl = api.artwork.showBackdrop(heroShow.id),
                    onPlay = { onNavigate(PlayerDestination.ShowDetail(heroShow.id)) },
                )
            }
        }

        // Continue Watching / Listening
        if (continueItems.isNotEmpty()) {
            item(key = "rail-continue") {
                MediaRail(title = "Continue") {
                    items(continueItems, key = { "${it.itemType}:${it.itemId}" }) { row ->
                        WideMediaCard(
                            title = row.meta?.title ?: row.itemId,
                            subtitle = row.meta?.subtitle ?: row.itemType.replaceFirstChar { it.uppercase() },
                            artworkUrl = api.artwork.resolve(row.meta?.backdrop ?: row.meta?.artwork),
                            progress = row.progressFraction,
                            onClick = {
                                val isVideo = row.itemType in setOf("movie", "episode", "youtube")
                                val playable = ActivePlayable(
                                    itemType = row.itemType,
                                    itemId = row.itemId,
                                    title = row.meta?.title ?: row.itemId,
                                    subtitle = row.meta?.subtitle.orEmpty(),
                                    artworkUrl = api.artwork.resolve(row.meta?.artwork),
                                    streamUrl = api.streamUrl(row.itemType, row.itemId),
                                    isVideo = isVideo,
                                    resumePositionMs = (row.positionSeconds * 1000.0).toLong(),
                                )
                                if (isVideo) coordinator.playVideo(playable) else coordinator.playAudio(playable)
                            },
                        )
                    }
                }
            }
        }

        // Watchlist
        if (watchlist.isNotEmpty()) {
            item(key = "rail-watchlist") {
                MediaRail(
                    title = "Watchlist",
                    actionLabel = "See all",
                    onAction = { onNavigate(PlayerDestination.Watchlist) },
                ) {
                    items(watchlist, key = { "${it.itemType}:${it.itemId}" }) { w ->
                        PosterCard(
                            title = w.meta?.title ?: w.itemId,
                            subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                            artworkUrl = api.artwork.resolve(w.meta?.artwork),
                            onClick = {
                                val idInt = w.itemId.toIntOrNull()
                                when {
                                    w.itemType == "movie" && idInt != null -> onNavigate(PlayerDestination.MovieDetail(idInt))
                                    w.itemType == "show" && idInt != null -> onNavigate(PlayerDestination.ShowDetail(idInt))
                                }
                            },
                        )
                    }
                }
            }
        }

        // Movies
        if (catalog.movies.isNotEmpty()) {
            item(key = "rail-movies") {
                MediaRail(
                    title = "Movies",
                    actionLabel = "All",
                    onAction = { onNavigate(PlayerDestination.Movies) },
                ) {
                    items(catalog.movies.take(20), key = { it.id }) { m ->
                        PosterCard(
                            title = m.title,
                            subtitle = m.year?.toString(),
                            artworkUrl = api.artwork.moviePoster(m.id),
                            badge = m.rating?.let { "%.1f".format(it) },
                            onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                        )
                    }
                }
            }
        }

        // TV Shows
        if (catalog.shows.isNotEmpty()) {
            item(key = "rail-tv") {
                MediaRail(
                    title = "TV Shows",
                    actionLabel = "All",
                    onAction = { onNavigate(PlayerDestination.Tv) },
                ) {
                    items(catalog.shows.take(20), key = { it.id }) { show ->
                        PosterCard(
                            title = show.title,
                            subtitle = "${show.episodeCount} episodes",
                            artworkUrl = api.artwork.showPoster(show.id),
                            badge = show.rating?.let { "%.1f".format(it) },
                            onClick = { onNavigate(PlayerDestination.ShowDetail(show.id)) },
                        )
                    }
                }
            }
        }

        // Music Albums
        if (albums.isNotEmpty()) {
            item(key = "rail-music") {
                MediaRail(
                    title = "Music",
                    actionLabel = "Library",
                    onAction = { onNavigate(PlayerDestination.Music) },
                ) {
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

        // YouTube
        if (catalog.youtube.isNotEmpty()) {
            item(key = "rail-youtube") {
                MediaRail(
                    title = "YouTube",
                    actionLabel = "All",
                    onAction = { onNavigate(PlayerDestination.YouTube) },
                ) {
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

        // Podcasts
        if (podcasts.isNotEmpty()) {
            item(key = "rail-podcasts") {
                MediaRail(
                    title = "Podcasts",
                    actionLabel = "All",
                    onAction = { onNavigate(PlayerDestination.Podcasts) },
                ) {
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

        // Audiobooks
        if (books.isNotEmpty()) {
            item(key = "rail-audiobooks") {
                MediaRail(
                    title = "Audiobooks",
                    actionLabel = "All",
                    onAction = { onNavigate(PlayerDestination.Audiobooks) },
                ) {
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

        if (heroMovie == null && heroShow == null && albums.isEmpty() && podcasts.isEmpty() && books.isEmpty()) {
            item(key = "empty-home") {
                Spacer(modifier = Modifier.height(64.dp))
                EmptyBox("Connected to SoulSync! Your movies, TV, music, podcasts, and audiobooks will appear here.")
            }
        }
    }
}

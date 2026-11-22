package net.ssync.app.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private data class BillboardSlide(
    val kind: String, // "movie" | "show"
    val id: Int,
    val tmdbId: Int?,
    val title: String,
    val eyebrow: String,
    val subtitle: String,
    val backdropUrl: String,
    val posterUrl: String,
    val addedAt: String,
)

@Composable
fun HomeScreen(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
    onScrolledChanged: (Boolean) -> Unit = {},
    onHasHeroChanged: (Boolean) -> Unit = {},
    openEditorInitially: Boolean = false,
    onEditorConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var continueItems by remember { mutableStateOf<List<ProgressRow>>(emptyList()) }
    var catalog by remember { mutableStateOf(VideoCatalog()) }
    var watchlist by remember { mutableStateOf<List<WatchlistItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<LibAlbum>>(emptyList()) }
    var podcasts by remember { mutableStateOf<List<PodcastSub>>(emptyList()) }
    var books by remember { mutableStateOf<List<OwnedBook>>(emptyList()) }
    var logos by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var homeLayout by remember { mutableStateOf(HomeLayout()) }
    var layoutShared by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(openEditorInitially) }

    LaunchedEffect(openEditorInitially) {
        if (openEditorInitially) {
            showEditor = true
            onEditorConsumed()
        }
    }

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

    // Build New Releases Billboard slides from newest Movies + TV Shows (1:1 with desktop /player)
    val billboardSlides = remember(catalog) {
        val movieSlides = catalog.movies
            .sortedByDescending { it.addedAt.orEmpty() }
            .take(4)
            .map { m ->
                BillboardSlide(
                    kind = "movie",
                    id = m.id,
                    tmdbId = m.tmdbId,
                    title = m.title,
                    eyebrow = "New Release",
                    subtitle = listOfNotNull(
                        m.year?.toString(),
                        m.contentRating,
                        m.rating?.let { "★ %.1f".format(it) },
                        m.genres.firstOrNull(),
                    ).joinToString(" • "),
                    backdropUrl = api.artwork.movieBackdrop(m.id),
                    posterUrl = api.artwork.moviePoster(m.id),
                    addedAt = m.addedAt.orEmpty(),
                )
            }
        val showSlides = catalog.shows
            .sortedByDescending { it.addedAt.orEmpty() }
            .take(3)
            .map { s ->
                BillboardSlide(
                    kind = "show",
                    id = s.id,
                    tmdbId = s.tmdbId,
                    title = s.title,
                    eyebrow = "New Series",
                    subtitle = listOfNotNull(
                        s.year?.toString(),
                        s.contentRating,
                        s.rating?.let { "★ %.1f".format(it) },
                        s.genres.firstOrNull(),
                    ).joinToString(" • "),
                    backdropUrl = api.artwork.showBackdrop(s.id),
                    posterUrl = api.artwork.showPoster(s.id),
                    addedAt = s.addedAt.orEmpty(),
                )
            }
        (movieSlides + showSlides)
            .sortedByDescending { it.addedAt }
            .take(6)
    }

    // Fetch official TMDB wordmark logos for the billboard slides
    LaunchedEffect(billboardSlides) {
        val keys = billboardSlides.mapNotNull { slide ->
            slide.tmdbId?.let { "${slide.kind}:$it" }
        }
        if (keys.isNotEmpty()) {
            (api.fetchLogos(keys) as? MusicApiResult.Success)?.let { logos = it.data }
        }
    }

    val showBillboard = homeLayout.billboard && billboardSlides.isNotEmpty()
    LaunchedEffect(showBillboard) {
        onHasHeroChanged(showBillboard)
    }

    LaunchedEffect(billboardSlides.size) {
        if (billboardSlides.size > 1) {
            while (true) {
                delay(8_000L)
                billboardIndex = (billboardIndex + 1) % billboardSlides.size
            }
        }
    }

    if (isLoading) {
        LoadingBox()
        return
    }

    val listState = rememberLazyListState()
    val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24
    LaunchedEffect(scrolled) {
        onScrolledChanged(scrolled)
    }

    val moviesById = remember(catalog.movies) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog.shows) { catalog.shows.associateBy { it.id } }
    val sortedMovies = remember(catalog.movies) { catalog.movies.sortedByDescending { it.addedAt.orEmpty() } }
    val sortedShows = remember(catalog.shows) { catalog.shows.sortedByDescending { it.addedAt.orEmpty() } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        if (showBillboard) {
            item(key = "billboard") {
                val hero = billboardSlides[billboardIndex % billboardSlides.size]
                val saved = watchlist.any { it.itemType == hero.kind && it.itemId == hero.id.toString() }
                val logoKey = hero.tmdbId?.let { "${hero.kind}:$it" }
                Box {
                    HeroSpotlight(
                        title = hero.title,
                        subtitle = hero.subtitle,
                        backdropUrl = hero.backdropUrl,
                        eyebrow = hero.eyebrow,
                        logoUrl = logoKey?.let { logos[it] }?.let { api.artwork.resolve(it) },
                        inWatchlist = saved,
                        onPlay = {
                            if (hero.kind == "movie") {
                                coordinator.playVideo(
                                    ActivePlayable(
                                        itemType = "movie",
                                        itemId = hero.id.toString(),
                                        title = hero.title,
                                        subtitle = hero.subtitle,
                                        artworkUrl = hero.posterUrl,
                                        streamUrl = api.streamUrl("movie", hero.id.toString()),
                                        isVideo = true,
                                    ),
                                )
                            } else {
                                onNavigate(PlayerDestination.ShowDetail(hero.id))
                            }
                        },
                        onDetails = {
                            if (hero.kind == "movie") onNavigate(PlayerDestination.MovieDetail(hero.id))
                            else onNavigate(PlayerDestination.ShowDetail(hero.id))
                        },
                        onToggleWatchlist = {
                            scope.launch {
                                if (saved) api.removeFromWatchlist(hero.kind, hero.id.toString())
                                else api.addToWatchlist(hero.kind, hero.id.toString())
                                (api.fetchWatchlist() as? MusicApiResult.Success)?.let { watchlist = it.data }
                            }
                        },
                    )
                    if (billboardSlides.size > 1) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 18.dp, bottom = 26.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            billboardSlides.indices.forEach { idx ->
                                val active = idx == billboardIndex % billboardSlides.size
                                Box(
                                    modifier = Modifier
                                        .width(if (active) 20.dp else 6.dp)
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (active) SoulSyncColors.AccentLight
                                            else Color.White.copy(alpha = 0.35f),
                                        )
                                        .clickable { billboardIndex = idx },
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Guaranteed top safe-zone when no hero billboard is shown so header NEVER overlaps row 1
            item(key = "top-header-spacer") {
                Spacer(modifier = Modifier.statusBarsPadding().height(68.dp))
            }
        }

        // Sleek Plex/Apple-TV-style Quick-Jump Category Pill Strip (no dated bottom nav needed)
        item(key = "quick-pills") {
            QuickJumpStrip(onNavigate = onNavigate)
        }

        // Dynamic customizable rows driven by HomeLayout (order, visibility, style, genre rows)
        homeLayout.rows.filter { it.on }.forEach { row ->
            val cardWidth = if (row.style == "large") 150.dp else 124.dp
            when {
                row.id == "continue" && continueItems.isNotEmpty() -> {
                    item(key = "row-continue") {
                        MediaRail(title = "Continue Watching") {
                            items(continueItems, key = { "${it.itemType}:${it.itemId}" }) { item ->
                                val idInt = item.itemId.toIntOrNull()
                                val parentInt = item.meta?.parentId?.toIntOrNull()
                                val catalogTitle = when (item.itemType) {
                                    "movie" -> idInt?.let { moviesById[it]?.title }
                                    "show" -> idInt?.let { showsById[it]?.title }
                                    "episode" -> parentInt?.let { showsById[it]?.title }
                                    "youtube" -> catalog.youtube.firstOrNull { it.id == item.itemId || it.youtubeId == item.itemId }?.title
                                    "audiobook" -> books.firstOrNull { it.asin == item.itemId }?.title
                                    else -> null
                                }
                                val displayTitle = item.meta?.title
                                    ?.takeIf { it.isNotBlank() && !it.equals("Untitled", ignoreCase = true) }
                                    ?: catalogTitle
                                    ?: item.itemId
                                val resolvedArt = api.artwork.resolve(item.meta?.backdrop ?: item.meta?.artwork)
                                    ?: api.artwork.forItem(
                                        itemType = item.itemType,
                                        itemId = item.itemId,
                                        preferBackdrop = true,
                                        parentId = item.meta?.parentId,
                                    )
                                WideMediaCard(
                                    title = displayTitle,
                                    subtitle = item.meta?.subtitle ?: item.itemType.replaceFirstChar { it.uppercase() },
                                    artworkUrl = resolvedArt,
                                    progress = item.progressFraction,
                                    onClick = {
                                        when (item.itemType) {
                                            "movie" -> if (idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                                            "show" -> if (idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                                            "episode" -> if (parentInt != null) {
                                                onNavigate(PlayerDestination.ShowDetail(parentInt))
                                            } else {
                                                coordinator.playVideo(
                                                    ActivePlayable(
                                                        itemType = item.itemType,
                                                        itemId = item.itemId,
                                                        title = displayTitle,
                                                        subtitle = item.meta?.subtitle.orEmpty(),
                                                        artworkUrl = resolvedArt,
                                                        streamUrl = api.streamUrl(item.itemType, item.itemId),
                                                        isVideo = true,
                                                        resumePositionMs = (item.positionSeconds * 1000.0).toLong(),
                                                    ),
                                                )
                                            }
                                            "track" -> if (parentInt != null) {
                                                onNavigate(PlayerDestination.AlbumDetail(parentInt))
                                            } else {
                                                onNavigate(PlayerDestination.Music)
                                            }
                                            "audiobook" -> {
                                                val book = books.firstOrNull { it.asin == item.itemId }
                                                if (book != null) onNavigate(PlayerDestination.AudiobookDetail(book))
                                                else onNavigate(PlayerDestination.Audiobooks)
                                            }
                                            "podcast" -> onNavigate(PlayerDestination.Podcasts)
                                            "youtube" -> onNavigate(PlayerDestination.YouTube)
                                            else -> coordinator.playVideo(
                                                ActivePlayable(
                                                    itemType = item.itemType,
                                                    itemId = item.itemId,
                                                    title = displayTitle,
                                                    subtitle = item.meta?.subtitle.orEmpty(),
                                                    artworkUrl = resolvedArt,
                                                    streamUrl = api.streamUrl(item.itemType, item.itemId),
                                                    isVideo = true,
                                                    resumePositionMs = (item.positionSeconds * 1000.0).toLong(),
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                row.id == "watchlist" && watchlist.isNotEmpty() -> {
                    item(key = "row-watchlist") {
                        MediaRail("Your Watchlist", actionLabel = "See all", onAction = { onNavigate(PlayerDestination.Watchlist) }) {
                            items(watchlist, key = { "${it.itemType}:${it.itemId}" }) { w ->
                                val idInt = w.itemId.toIntOrNull()
                                val movie = if (w.itemType == "movie" && idInt != null) moviesById[idInt] else null
                                val show = if (w.itemType == "show" && idInt != null) showsById[idInt] else null
                                val title = movie?.title ?: show?.title ?: w.meta?.title ?: w.itemId
                                val art = api.artwork.resolve(w.meta?.artwork)
                                    ?: api.artwork.forItem(w.itemType, w.itemId, preferBackdrop = false)
                                PosterCard(
                                    title = title,
                                    subtitle = movie?.year?.toString()
                                        ?: show?.year?.toString()
                                        ?: w.itemType.replaceFirstChar { it.uppercase() },
                                    artworkUrl = art,
                                    width = cardWidth,
                                    onClick = {
                                        if (w.itemType == "movie" && idInt != null) onNavigate(PlayerDestination.MovieDetail(idInt))
                                        if (w.itemType == "show" && idInt != null) onNavigate(PlayerDestination.ShowDetail(idInt))
                                    },
                                )
                            }
                        }
                    }
                }
                row.id == "new-episodes" && catalog.recentEpisodes.isNotEmpty() -> {
                    item(key = "row-new-episodes") {
                        MediaRail("New Episodes", actionLabel = "TV", onAction = { onNavigate(PlayerDestination.Tv) }) {
                            items(catalog.recentEpisodes.take(18), key = { it.id }) { ep ->
                                val art = if (ep.hasStill) api.artwork.episodeStill(ep.id)
                                else api.artwork.showBackdrop(ep.showId)
                                WideMediaCard(
                                    title = ep.showTitle,
                                    subtitle = "S${ep.seasonNumber} E${ep.episodeNumber}" +
                                        (ep.title?.let { " · $it" } ?: ""),
                                    artworkUrl = art,
                                    onClick = { onNavigate(PlayerDestination.ShowDetail(ep.showId)) },
                                )
                            }
                        }
                    }
                }
                (row.id == "top-rated" || row.id == "top-movies") && catalog.movies.isNotEmpty() -> {
                    item(key = "row-${row.id}") {
                        val top10 = catalog.movies.sortedByDescending { it.rating ?: 0.0 }.take(10)
                        MediaRail("Top 10 in Your Library") {
                            itemsIndexed(top10, key = { _, m -> "${row.id}-${m.id}" }) { idx, m ->
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
                row.id == "top-shows" && catalog.shows.isNotEmpty() -> {
                    item(key = "row-top-shows") {
                        val top10 = catalog.shows.sortedByDescending { it.rating ?: 0.0 }.take(10)
                        MediaRail("Top 10 TV Shows") {
                            itemsIndexed(top10, key = { _, s -> "top-show-${s.id}" }) { idx, s ->
                                RankedPosterCard(
                                    rank = idx + 1,
                                    title = s.title,
                                    artworkUrl = api.artwork.showPoster(s.id),
                                    onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                                )
                            }
                        }
                    }
                }
                row.id == "recent-movies" && sortedMovies.isNotEmpty() -> {
                    item(key = "row-movies") {
                        MediaRail("Recently Added Movies", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Movies) }) {
                            items(sortedMovies.take(20), key = { it.id }) { m ->
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
                row.id == "recent-shows" && sortedShows.isNotEmpty() -> {
                    item(key = "row-shows") {
                        MediaRail("Recently Added Shows", actionLabel = "All", onAction = { onNavigate(PlayerDestination.Tv) }) {
                            items(sortedShows.take(20), key = { it.id }) { show ->
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
                        MediaRail("Recently Added Music", actionLabel = "Library", onAction = { onNavigate(PlayerDestination.Music) }) {
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
                                                artworkUrl = api.artwork.resolve(yt.thumbnailUrl)
                                                    ?: api.artwork.forItem("youtube", yt.id),
                                                streamUrl = api.streamUrl("youtube", yt.id),
                                                isVideo = true,
                                                parentId = yt.channelId,
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
                                    artworkUrl = api.artwork.resolve(book.coverUrl) ?: api.artwork.audiobookCover(book.asin),
                                    fallbackIcon = Icons.Default.Headphones,
                                    onClick = { onNavigate(PlayerDestination.AudiobookDetail(book)) },
                                )
                            }
                        }
                    }
                }
                row.id.startsWith("genre:movie:") -> {
                    val genre = row.id.removePrefix("genre:movie:")
                    val matches = catalog.movies.filter { m -> m.genres.any { it.equals(genre, ignoreCase = true) } }
                    if (matches.isNotEmpty()) {
                        item(key = "row-${row.id}") {
                            MediaRail("$genre Movies") {
                                items(matches.take(20), key = { it.id }) { m ->
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
                row.id.startsWith("genre:show:") -> {
                    val genre = row.id.removePrefix("genre:show:")
                    val matches = catalog.shows.filter { s -> s.genres.any { it.equals(genre, ignoreCase = true) } }
                    if (matches.isNotEmpty()) {
                        item(key = "row-${row.id}") {
                            MediaRail("$genre Shows") {
                                items(matches.take(20), key = { it.id }) { s ->
                                    PosterCard(
                                        title = s.title,
                                        subtitle = "${s.episodeCount} episodes",
                                        artworkUrl = api.artwork.showPoster(s.id),
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Customize Home footer action
        item(key = "customize-home") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(
                    onClick = { showEditor = true },
                    border = BorderStroke(1.dp, SoulSyncColors.LineStrong),
                    shape = CircleShape,
                    modifier = Modifier.dpadFocusable(CircleShape),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp), tint = SoulSyncColors.TextSoft)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Customize Home", color = SoulSyncColors.TextSoft)
                }
            }
        }
    }

    if (showEditor) {
        HomeEditorSheet(
            initialLayout = homeLayout,
            initialShared = layoutShared,
            availableMovieGenres = catalog.movies.flatMap { it.genres }.distinct().sorted(),
            onSave = { updated, shared ->
                homeLayout = updated
                layoutShared = shared
                showEditor = false
                scope.launch {
                    api.saveHomeLayout(layout = updated, device = if (shared) "*" else "phone")
                }
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

private data class QuickChip(val label: String, val icon: ImageVector, val dest: PlayerDestination)

@Composable
private fun QuickJumpStrip(onNavigate: (PlayerDestination) -> Unit) {
    val chips = remember {
        listOf(
            QuickChip("Movies", Icons.Default.Movie, PlayerDestination.Movies),
            QuickChip("TV Shows", Icons.Default.Tv, PlayerDestination.Tv),
            QuickChip("Music", Icons.Default.MusicNote, PlayerDestination.Music),
            QuickChip("Live Guide", Icons.Default.LiveTv, PlayerDestination.Guide),
            QuickChip("Watchlist", Icons.Default.Bookmark, PlayerDestination.Watchlist),
            QuickChip("Watching Now", Icons.Default.Group, PlayerDestination.WatchingNow),
            QuickChip("YouTube", Icons.Default.SmartDisplay, PlayerDestination.YouTube),
            QuickChip("Podcasts", Icons.Default.Podcasts, PlayerDestination.Podcasts),
            QuickChip("Audiobooks", Icons.Default.Headphones, PlayerDestination.Audiobooks),
        )
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chips, key = { it.label }) { chip ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .dpadFocusable(RoundedCornerShape(999.dp))
                    .clickable { onNavigate(chip.dest) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = chip.icon,
                    contentDescription = null,
                    tint = SoulSyncColors.TextSoft,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = chip.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

package net.ssync.app.player

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    val previewStreamUrl: String? = null,
)

internal object HomeWarmCache {
    var baseUrl: String? = null
    var continueItems: List<ProgressRow> = emptyList()
    var catalog: VideoCatalog = VideoCatalog()
    var watchlist: List<WatchlistItem> = emptyList()
    var studioCollections: List<net.ssync.player.StudioCollection> = emptyList()
    var discoverPlaylists: List<net.ssync.player.DiscoverPlaylistSummary> = emptyList()
    var albums: List<LibAlbum> = emptyList()
    var podcasts: List<PodcastSub> = emptyList()
    var books: List<OwnedBook> = emptyList()
    var homeLayout: HomeLayout = HomeLayout()
    var layoutShared: Boolean = true
    var liveGuideHighlights: List<HomeLiveHighlight> = emptyList()
    var hasLoadedOnce: Boolean = false
}

@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: PlayerApiClient,
    pinnedHttpClient: OkHttpClient,
    coordinator: PlaybackCoordinator,
    onNavigate: (PlayerDestination) -> Unit,
    onDeviceRevoked: () -> Unit,
    onScrolledChanged: (Boolean) -> Unit = {},
    onHasHeroChanged: (Boolean) -> Unit = {},
    openEditorInitially: Boolean = false,
    onEditorConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val appCacheDir = LocalContext.current.applicationContext.cacheDir
    val warmHit = HomeWarmCache.hasLoadedOnce && HomeWarmCache.baseUrl == api.baseUrl
    var isLoading by remember(api.baseUrl) { mutableStateOf(!warmHit) }
    var isRefreshing by remember { mutableStateOf(false) }
    var continueItems by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.continueItems else emptyList()) }
    var catalog by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.catalog else VideoCatalog()) }
    var watchlist by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.watchlist else emptyList()) }
    var studioCollections by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.studioCollections else emptyList()) }
    var discoverPlaylists by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.discoverPlaylists else emptyList()) }
    var albums by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.albums else emptyList()) }
    var podcasts by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.podcasts else emptyList()) }
    var books by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.books else emptyList()) }
    var logos by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var homeLayout by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.homeLayout else HomeLayout()) }
    var layoutShared by remember(api.baseUrl) { mutableStateOf(if (warmHit) HomeWarmCache.layoutShared else true) }
    var showEditor by remember { mutableStateOf(openEditorInitially) }

    LaunchedEffect(openEditorInitially) {
        if (openEditorInitially) {
            showEditor = true
            onEditorConsumed()
        }
    }

    var billboardIndex by remember { mutableIntStateOf(0) }

    suspend fun loadHomeData(forceRefresh: Boolean) {
        HomeWarmCache.baseUrl = api.baseUrl
        coroutineScope {
            // 1. Prioritize above-the-fold primary endpoints so Billboard + Continue Watching paint ASAP
            //    without contending with 6 below-the-fold secondary requests on cold start.
            val cDef = async { api.fetchContinue() }
            val vDef = async { api.fetchVideoCatalog(forceRefresh = forceRefresh) }
            val lDef = async { api.fetchHomeLayout(device = "android-phone") }

            when (val v = vDef.await()) {
                is MusicApiResult.Success -> {
                    catalog = v.data
                    HomeWarmCache.catalog = v.data
                }
                MusicApiResult.DeviceRevoked -> {
                    onDeviceRevoked()
                    return@coroutineScope
                }
                is MusicApiResult.Error -> Unit
            }
            (cDef.await() as? MusicApiResult.Success)?.let {
                continueItems = it.data
                HomeWarmCache.continueItems = it.data
            }
            (lDef.await() as? MusicApiResult.Success)?.let { resp ->
                if (resp.data.layout.rows.isNotEmpty()) {
                    homeLayout = resp.data.layout
                    layoutShared = resp.data.shared
                    HomeWarmCache.homeLayout = resp.data.layout
                    HomeWarmCache.layoutShared = resp.data.shared
                }
            }
            HomeWarmCache.hasLoadedOnce = true
            isLoading = false

            // Persist lightweight cold-start snapshot to disk so next app launch renders instantaneously
            launch(Dispatchers.IO) {
                net.ssync.player.HomeDiskSnapshot.write(
                    appCacheDir,
                    net.ssync.player.HomeDiskSnapshot(
                        baseUrl = api.baseUrl,
                        continueItems = HomeWarmCache.continueItems,
                        catalog = HomeWarmCache.catalog,
                        studioCollections = HomeWarmCache.studioCollections,
                        homeLayout = HomeWarmCache.homeLayout,
                        layoutShared = HomeWarmCache.layoutShared,
                    ),
                )
            }

            // 2. Hydrate secondary below-the-fold sections in parallel after primary has painted
            launch {
                (api.fetchWatchlist() as? MusicApiResult.Success)?.let {
                    watchlist = it.data
                    HomeWarmCache.watchlist = it.data
                }
            }
            launch {
                (api.fetchAlbums(limit = 20) as? MusicApiResult.Success)?.let {
                    albums = it.data
                    HomeWarmCache.albums = it.data
                }
            }
            launch {
                (api.fetchCollections() as? MusicApiResult.Success)?.let {
                    studioCollections = it.data
                    HomeWarmCache.studioCollections = it.data
                }
            }
            launch {
                (api.fetchDiscoverPlaylists() as? MusicApiResult.Success)?.let {
                    discoverPlaylists = it.data
                    HomeWarmCache.discoverPlaylists = it.data
                }
            }
            launch {
                (api.fetchPodcastSubs() as? MusicApiResult.Success)?.let {
                    podcasts = it.data
                    HomeWarmCache.podcasts = it.data
                }
            }
            launch {
                (api.fetchAudiobooks() as? MusicApiResult.Success)?.let {
                    books = it.data
                    HomeWarmCache.books = it.data
                }
            }
        }
    }

    LaunchedEffect(api) {
        if (!warmHit) {
            // Instant cold-start paint from persistent disk snapshot (<20ms) while live refresh runs
            val snap = withContext(Dispatchers.IO) {
                net.ssync.player.HomeDiskSnapshot.read(appCacheDir, api.baseUrl)
            }
            if (snap != null) {
                catalog = snap.catalog
                continueItems = snap.continueItems
                studioCollections = snap.studioCollections
                if (snap.homeLayout.rows.isNotEmpty()) {
                    homeLayout = snap.homeLayout
                    layoutShared = snap.layoutShared
                }
                HomeWarmCache.baseUrl = api.baseUrl
                HomeWarmCache.catalog = snap.catalog
                HomeWarmCache.continueItems = snap.continueItems
                HomeWarmCache.studioCollections = snap.studioCollections
                HomeWarmCache.homeLayout = homeLayout
                HomeWarmCache.layoutShared = layoutShared
                HomeWarmCache.hasLoadedOnce = true
                isLoading = false
            } else {
                isLoading = true
            }
        }
        loadHomeData(forceRefresh = false)
        isLoading = false
        // Auto-refresh silently every 60s so newly added episodes & movies land ASAP
        while (true) {
            delay(60_000L)
            loadHomeData(forceRefresh = true)
        }
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
                    previewStreamUrl = api.streamUrl("movie", m.id.toString()),
                )
            }
        val showSlides = catalog.shows
            .sortedByDescending { it.addedAt.orEmpty() }
            .take(3)
            .map { s ->
                val firstEpId = s.episodes.firstOrNull()?.firstOrNull()?.toString()?.trim('"')
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
                    previewStreamUrl = firstEpId?.let { api.streamUrl("episode", it) },
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

    var heroMuted by remember { mutableStateOf(true) }
    var heroVideoReady by remember { mutableStateOf(false) }

    LaunchedEffect(billboardSlides.size, heroVideoReady) {
        if (billboardSlides.size > 1) {
            while (true) {
                delay(if (heroVideoReady) 18_000L else 8_000L)
                billboardIndex = (billboardIndex + 1) % billboardSlides.size
            }
        }
    }

    if (isLoading) {
        // Sleek cinema skeleton placeholder on cold start (0ms warm cache on subsequent visits!)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SoulSyncColors.BgPrimary)
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SoulSyncColors.Card),
            )
            repeat(2) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(SoulSyncColors.Card),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .width(116.dp)
                                .height(172.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(SoulSyncColors.Card),
                        )
                    }
                }
            }
        }
        return
    }

    val listState = rememberLazyListState()
    val scrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 24
        }
    }
    LaunchedEffect(scrolled) {
        onScrolledChanged(scrolled)
    }

    val moviesById = remember(catalog.movies) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog.shows) { catalog.shows.associateBy { it.id } }
    val sortedMovies = remember(catalog.movies) { catalog.movies.sortedByDescending { it.addedAt.orEmpty() } }
    val sortedShows = remember(catalog.shows) { catalog.shows.sortedByDescending { it.addedAt.orEmpty() } }

    // 1:1 Desktop Parity: On Live TV Right Now, Seasonal Spotlight, Today's Picks & Rediscover
    // Computed off the main thread on Dispatchers.Default so initial Home composition never drops frames!
    var liveGuideHighlights by remember(api.baseUrl) {
        mutableStateOf(if (warmHit) HomeWarmCache.liveGuideHighlights else emptyList())
    }
    LaunchedEffect(catalog, studioCollections, api) {
        if (catalog.movies.isNotEmpty() || catalog.shows.isNotEmpty()) {
            val built = withContext(Dispatchers.Default) {
                buildHomeLiveGuideHighlights(
                    catalog = catalog,
                    studioCollections = studioCollections,
                    api = api,
                )
            }
            liveGuideHighlights = built
            HomeWarmCache.liveGuideHighlights = built
        }
    }
    val seasonalBundle = remember(catalog.movies, catalog.shows) {
        val cal = java.util.Calendar.getInstance()
        val month = cal.get(java.util.Calendar.MONTH) // 0..11
        val daySeed = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        val (label, genres) = when (month) {
            0 -> "Winter escape" to setOf("adventure", "sci-fi", "science fiction", "fantasy")
            1 -> "Date night & romance" to setOf("romance", "drama", "comedy")
            2 -> "Spring awakening" to setOf("comedy", "adventure", "family")
            3 -> "Mind-bending thrillers" to setOf("thriller", "mystery", "sci-fi", "science fiction")
            4 -> "Action blockbuster season" to setOf("action", "adventure", "sci-fi", "science fiction")
            5 -> "Summer vibes" to setOf("comedy", "adventure", "music", "family")
            6 -> "Summer blockbusters" to setOf("action", "adventure", "fantasy")
            7 -> "Late summer mysteries" to setOf("mystery", "crime", "thriller")
            8 -> "Back-to-school & drama" to setOf("drama", "documentary", "biography", "history")
            9 -> "October chills & Halloween" to setOf("horror", "thriller", "mystery")
            10 -> "Cozy autumn nights" to setOf("drama", "family", "comedy", "fantasy")
            else -> "Holiday & winter cinema" to setOf("family", "fantasy", "comedy", "animation")
        }
        val movieMatches = catalog.movies.filter { m -> m.genres.any { it.lowercase() in genres } }
            .map { Triple("movie", it.id, it.title) }
        val showMatches = catalog.shows.filter { s -> s.genres.any { it.lowercase() in genres } }
            .map { Triple("show", it.id, it.title) }
        val combined = (movieMatches + showMatches)
            .sortedBy { (k, id, _) -> ((id * 2654435761L) xor daySeed.toLong() xor k.hashCode().toLong()) }
            .take(18)
        label to combined
    }
    val dailyGenrePick = remember(catalog.movies) {
        val counts = mutableMapOf<String, Int>()
        catalog.movies.forEach { m -> m.genres.forEach { g -> counts[g] = (counts[g] ?: 0) + 1 } }
        val eligible = counts.entries.filter { it.value >= 4 }.map { it.key }.sorted()
        if (eligible.isEmpty()) null else {
            val daySeed = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
            val chosen = eligible[daySeed % eligible.size]
            chosen to catalog.movies.filter { m -> m.genres.any { it.equals(chosen, ignoreCase = true) } }
                .sortedByDescending { it.rating ?: 0.0 }
                .take(18)
        }
    }
    val rediscoverMovies = remember(catalog.movies) {
        val daySeed = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR).toLong()
        catalog.movies
            .sortedBy { it.addedAt.orEmpty() }
            .take(40)
            .sortedBy { (it.id.toLong() * 1103515245L) xor daySeed }
            .take(18)
    }

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            if (!isRefreshing) {
                scope.launch {
                    isRefreshing = true
                    loadHomeData(forceRefresh = true)
                    isRefreshing = false
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
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
                // Use hardware-cached backdrop artwork on mobile Home instead of spinning up an
                // ExoPlayer TextureView decoding full-bitrate movie files inside the LazyColumn.
                val allowHeroVideo = false
                Box {
                    HeroSpotlight(
                        title = hero.title,
                        subtitle = hero.subtitle,
                        backdropUrl = hero.backdropUrl,
                        eyebrow = hero.eyebrow,
                        logoUrl = logoKey?.let { logos[it] }?.let { api.artwork.resolve(it) },
                        inWatchlist = saved,
                        backgroundVideo = if (allowHeroVideo) {
                            {
                                HeroVideoBackdrop(
                                    streamUrl = hero.previewStreamUrl!!,
                                    pinnedHttpClient = pinnedHttpClient,
                                    muted = heroMuted,
                                    onReadyChanged = { heroVideoReady = it },
                                )
                            }
                        } else {
                            null
                        },
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
                    if (allowHeroVideo && heroVideoReady) {
                        IconButton(
                            onClick = { heroMuted = !heroMuted },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 16.dp, bottom = 50.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.56f))
                                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                        ) {
                            Icon(
                                imageVector = if (heroMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = if (heroMuted) "Unmute preview" else "Mute preview",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
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
                                val resolvedArt = if (item.itemType == "youtube") {
                                    val ytCatalogThumb = catalog.youtube
                                        .firstOrNull { it.id == item.itemId || it.youtubeId == item.itemId }
                                        ?.thumbnailUrl
                                    val metaArt = (item.meta?.backdrop ?: item.meta?.artwork)
                                        ?.takeIf { it.isNotBlank() && !it.contains("/api/image-proxy") }
                                    api.artwork.resolve(metaArt ?: ytCatalogThumb)
                                        ?: api.artwork.forItem("youtube", item.itemId, preferBackdrop = true)
                                } else {
                                    api.artwork.resolve(item.meta?.backdrop ?: item.meta?.artwork)
                                        ?: api.artwork.forItem(
                                            itemType = item.itemType,
                                            itemId = item.itemId,
                                            preferBackdrop = true,
                                            parentId = item.meta?.parentId,
                                        )
                                }
                                WideMediaCard(
                                    title = displayTitle,
                                    subtitle = item.meta?.subtitle ?: item.itemType.replaceFirstChar { it.uppercase() },
                                    artworkUrl = resolvedArt,
                                    progress = item.progressFraction,
                                    contextItem = MediaContextItem(
                                        itemType = item.itemType,
                                        itemId = item.itemId,
                                        title = displayTitle,
                                        subtitle = item.meta?.subtitle.orEmpty(),
                                        artworkUrl = resolvedArt,
                                        streamUrl = api.streamUrl(item.itemType, item.itemId),
                                        isVideo = item.itemType in setOf("movie", "episode", "youtube"),
                                        parentId = item.meta?.parentId,
                                        onOpenDetails = {
                                            when (item.itemType) {
                                                "movie" -> idInt?.let { onNavigate(PlayerDestination.MovieDetail(it)) }
                                                "show" -> idInt?.let { onNavigate(PlayerDestination.ShowDetail(it)) }
                                                "episode" -> parentInt?.let { onNavigate(PlayerDestination.ShowDetail(it)) }
                                                "track" -> parentInt?.let { onNavigate(PlayerDestination.AlbumDetail(it)) }
                                            }
                                        },
                                    ),
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
                                                onNavigate(PlayerDestination.AlbumDetail(parentInt, highlightTrackId = idInt))
                                            } else {
                                                onNavigate(PlayerDestination.Music)
                                            }
                                            "audiobook" -> {
                                                val book = books.firstOrNull { it.asin == item.itemId }
                                                if (book != null) onNavigate(PlayerDestination.AudiobookDetail(book))
                                                else onNavigate(PlayerDestination.Audiobooks)
                                            }
                                            "podcast" -> onNavigate(PlayerDestination.Podcasts)
                                            "youtube" -> {
                                                val ytMatch = catalog.youtube.firstOrNull {
                                                    it.id == item.itemId || it.youtubeId == item.itemId
                                                }
                                                val chId = item.meta?.parentId ?: ytMatch?.channelId
                                                val chTitle = item.meta?.subtitle?.takeIf { it.isNotBlank() }
                                                    ?: ytMatch?.channelTitle.orEmpty()
                                                coordinator.playVideo(
                                                    ActivePlayable(
                                                        itemType = "youtube",
                                                        itemId = item.itemId,
                                                        title = displayTitle,
                                                        subtitle = chTitle,
                                                        artworkUrl = resolvedArt,
                                                        streamUrl = api.streamUrl("youtube", item.itemId),
                                                        isVideo = true,
                                                        resumePositionMs = (item.positionSeconds * 1000.0).toLong(),
                                                        parentId = chId,
                                                    ),
                                                )
                                            }
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
                row.id == "watchlist" -> Unit
                row.id == "live-guide" && liveGuideHighlights.isNotEmpty() -> {
                    item(key = "row-live-guide") {
                        MediaRail(
                            title = "On Live TV Right Now",
                            actionLabel = "Guide",
                            onAction = { onNavigate(PlayerDestination.Guide) },
                        ) {
                            items(liveGuideHighlights, key = { "live-${it.channelId}" }) { ch ->
                                WideMediaCard(
                                    title = ch.programTitle,
                                    subtitle = "LIVE · ${ch.programSubtitle}",
                                    artworkUrl = ch.artworkUrl,
                                    progress = ch.progressFraction,
                                    onClick = { ch.tune(coordinator) },
                                )
                            }
                        }
                    }
                }
                row.id == "seasonal" && seasonalBundle.second.isNotEmpty() -> {
                    item(key = "row-seasonal") {
                        MediaRail(
                            title = seasonalBundle.first,
                            actionLabel = "Movies",
                            onAction = { onNavigate(PlayerDestination.Movies) },
                        ) {
                            items(seasonalBundle.second, key = { (k, id, _) -> "season-$k-$id" }) { (kind, id, title) ->
                                if (kind == "movie") {
                                    val m = moviesById[id]
                                    PosterCard(
                                        title = title,
                                        subtitle = m?.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(id),
                                        badge = m?.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(id)) },
                                    )
                                } else {
                                    val s = showsById[id]
                                    PosterCard(
                                        title = title,
                                        subtitle = s?.episodeCount?.let { "$it episodes" },
                                        artworkUrl = api.artwork.showPoster(id),
                                        badge = s?.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        onClick = { onNavigate(PlayerDestination.ShowDetail(id)) },
                                    )
                                }
                            }
                        }
                    }
                }
                row.id == "genre-picks" && dailyGenrePick != null && dailyGenrePick.second.isNotEmpty() -> {
                    item(key = "row-genre-picks") {
                        MediaRail(
                            title = "Today's Picks: ${dailyGenrePick.first}",
                            actionLabel = "Movies",
                            onAction = { onNavigate(PlayerDestination.Movies) },
                        ) {
                            items(dailyGenrePick.second, key = { "gpick-${it.id}" }) { m ->
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
                row.id == "rediscover" && rediscoverMovies.isNotEmpty() -> {
                    item(key = "row-rediscover") {
                        MediaRail(
                            title = "Rediscover",
                            actionLabel = "All",
                            onAction = { onNavigate(PlayerDestination.Movies) },
                        ) {
                            items(rediscoverMovies, key = { "redisc-${it.id}" }) { m ->
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
                row.id == "collections" && studioCollections.isNotEmpty() -> {
                    val visibleCols = studioCollections.mapNotNull { c ->
                        val members = c.members.filter { id ->
                            if (c.mediaType == "movie") moviesById.containsKey(id) else showsById.containsKey(id)
                        }
                        if (members.isEmpty()) null else c to members
                    }
                    if (visibleCols.isNotEmpty()) {
                        item(key = "row-collections") {
                            MediaRail(
                                title = "Collections",
                                actionLabel = "See all",
                                onAction = { onNavigate(PlayerDestination.Movies) },
                            ) {
                                items(visibleCols, key = { (c, _) -> "col-${c.id}" }) { (c, members) ->
                                    val safePoster = c.poster?.takeIf { !it.startsWith("/api/video/collections/") }
                                    val art = api.artwork.resolve(safePoster ?: c.memberPosters.firstOrNull())
                                        ?: api.artwork.forItem(
                                            if (c.mediaType == "movie") "movie" else "show",
                                            members.first().toString(),
                                            preferBackdrop = false,
                                        )
                                    PosterCard(
                                        title = c.name,
                                        subtitle = "${members.size} ${if (members.size == 1) "title" else "titles"}",
                                        artworkUrl = art,
                                        badge = if (c.pinned) "Pinned" else null,
                                        inLibrary = true,
                                        width = cardWidth,
                                        onClick = {
                                            val firstId = members.first()
                                            if (c.mediaType == "movie") onNavigate(PlayerDestination.MovieDetail(firstId))
                                            else onNavigate(PlayerDestination.ShowDetail(firstId))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                row.id == "discovery-playlists" && discoverPlaylists.isNotEmpty() -> {
                    item(key = "row-discovery-playlists") {
                        MediaRail(
                            title = "SoulSync Discovery Playlists",
                            actionLabel = "All ${discoverPlaylists.size}",
                            onAction = { onNavigate(PlayerDestination.Music) },
                        ) {
                            items(discoverPlaylists.take(24), key = { "home-disc-${it.key}" }) { dp ->
                                val tiles = dp.coverTiles.mapNotNull { api.artwork.resolve(it) }
                                AlbumGridTile(
                                    title = dp.title,
                                    subtitle = dp.subtitle ?: "${dp.trackCount} tracks",
                                    artworkUrl = api.artwork.resolve(dp.coverUrl),
                                    mosaicUrls = tiles,
                                    tracksPresent = 0,
                                    trackCount = 0,
                                    modifier = Modifier.width(142.dp),
                                    onClick = {
                                        scope.launch {
                                            (api.fetchDiscoverPlaylistDetail(dp.key) as? MusicApiResult.Success)?.let { res ->
                                                val q = res.data.songs.filter { it.trackId != null }.map { t ->
                                                    ActivePlayable(
                                                        itemType = "track",
                                                        itemId = t.trackId.toString(),
                                                        title = t.title,
                                                        subtitle = t.artist,
                                                        artworkUrl = api.artwork.albumArtwork(t.albumId),
                                                        streamUrl = api.streamUrl("track", t.trackId.toString()),
                                                    )
                                                }
                                                if (q.isNotEmpty()) coordinator.playAudio(q.first(), q.drop(1))
                                            }
                                        }
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
                                    contextItem = MediaContextItem(
                                        itemType = "episode",
                                        itemId = ep.id.toString(),
                                        title = "${ep.showTitle} · S${ep.seasonNumber}E${ep.episodeNumber}",
                                        subtitle = ep.title.orEmpty(),
                                        artworkUrl = art,
                                        streamUrl = api.streamUrl("episode", ep.id.toString()),
                                        isVideo = true,
                                        parentId = ep.showId.toString(),
                                        onOpenDetails = { onNavigate(PlayerDestination.ShowDetail(ep.showId)) },
                                    ),
                                    onClick = {
                                        onNavigate(
                                            PlayerDestination.ShowDetail(
                                                showId = ep.showId,
                                                initialSeason = ep.seasonNumber,
                                                highlightEpisode = ep.episodeNumber,
                                            ),
                                        )
                                    },
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
                                val movieCtx = MediaContextItem(
                                    itemType = "movie",
                                    itemId = m.id.toString(),
                                    title = m.title,
                                    subtitle = m.year?.toString().orEmpty(),
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                    streamUrl = api.streamUrl("movie", m.id.toString()),
                                    isVideo = true,
                                    onOpenDetails = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                )
                                if (row.style == "ranked") {
                                    RankedPosterCard(
                                        rank = idx + 1,
                                        title = m.title,
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        contextItem = movieCtx,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                } else {
                                    PosterCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        badge = m.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        contextItem = movieCtx,
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
                                    contextItem = MediaContextItem(
                                        itemType = "show",
                                        itemId = s.id.toString(),
                                        title = s.title,
                                        subtitle = "${s.episodeCount} episodes",
                                        artworkUrl = api.artwork.showPoster(s.id),
                                        isVideo = true,
                                        onOpenDetails = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
                                    ),
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
                                val mCtx = MediaContextItem(
                                    itemType = "movie",
                                    itemId = m.id.toString(),
                                    title = m.title,
                                    subtitle = m.year?.toString().orEmpty(),
                                    artworkUrl = api.artwork.moviePoster(m.id),
                                    streamUrl = api.streamUrl("movie", m.id.toString()),
                                    isVideo = true,
                                    onOpenDetails = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                )
                                if (row.style == "wide") {
                                    WideMediaCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.movieBackdrop(m.id),
                                        contextItem = mCtx,
                                        onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                    )
                                } else {
                                    PosterCard(
                                        title = m.title,
                                        subtitle = m.year?.toString(),
                                        artworkUrl = api.artwork.moviePoster(m.id),
                                        badge = m.rating?.let { "%.1f".format(it) },
                                        width = cardWidth,
                                        contextItem = mCtx,
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
                                    contextItem = MediaContextItem(
                                        itemType = "show",
                                        itemId = show.id.toString(),
                                        title = show.title,
                                        subtitle = "${show.episodeCount} episodes",
                                        artworkUrl = api.artwork.showPoster(show.id),
                                        isVideo = true,
                                        onOpenDetails = { onNavigate(PlayerDestination.ShowDetail(show.id)) },
                                    ),
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
                                val alArt = api.artwork.resolve(album.imageUrl) ?: api.artwork.albumArtwork(album.id)
                                SquareMediaCard(
                                    title = album.title,
                                    subtitle = album.artistName,
                                    artworkUrl = alArt,
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
                row.id == "youtube" && catalog.youtube.isNotEmpty() -> {
                    item(key = "row-yt") {
                        MediaRail("YouTube", actionLabel = "All", onAction = { onNavigate(PlayerDestination.YouTube) }) {
                            items(catalog.youtube.take(15), key = { it.id }) { yt ->
                                val ytArt = api.artwork.resolve(yt.thumbnailUrl)
                                    ?: api.artwork.forItem("youtube", yt.id)
                                WideMediaCard(
                                    title = yt.title,
                                    subtitle = yt.channelTitle,
                                    artworkUrl = ytArt,
                                    contextItem = MediaContextItem(
                                        itemType = "youtube",
                                        itemId = yt.id,
                                        title = yt.title,
                                        subtitle = yt.channelTitle.orEmpty(),
                                        artworkUrl = ytArt,
                                        streamUrl = api.streamUrl("youtube", yt.id),
                                        isVideo = true,
                                        parentId = yt.channelId,
                                    ),
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
                                val podArt = api.artwork.resolve(sub.artworkUrl)
                                SquareMediaCard(
                                    title = sub.title,
                                    subtitle = sub.author,
                                    artworkUrl = podArt,
                                    fallbackIcon = Icons.Default.Podcasts,
                                    contextItem = MediaContextItem(
                                        itemType = "podcast",
                                        itemId = sub.id.toString(),
                                        title = sub.title,
                                        subtitle = sub.author.orEmpty(),
                                        artworkUrl = podArt,
                                        isVideo = false,
                                        onOpenDetails = { onNavigate(PlayerDestination.PodcastDetail(sub)) },
                                    ),
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
                                val bookArt = api.artwork.resolve(book.coverUrl) ?: api.artwork.audiobookCover(book.asin)
                                SquareMediaCard(
                                    title = book.title,
                                    subtitle = book.author,
                                    artworkUrl = bookArt,
                                    fallbackIcon = Icons.Default.Headphones,
                                    contextItem = MediaContextItem(
                                        itemType = "audiobook",
                                        itemId = book.asin,
                                        title = book.title,
                                        subtitle = book.author.orEmpty(),
                                        artworkUrl = bookArt,
                                        streamUrl = api.streamUrl("audiobook", book.asin),
                                        isVideo = false,
                                        onOpenDetails = { onNavigate(PlayerDestination.AudiobookDetail(book)) },
                                    ),
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
                                        contextItem = MediaContextItem(
                                            itemType = "movie",
                                            itemId = m.id.toString(),
                                            title = m.title,
                                            subtitle = m.year?.toString().orEmpty(),
                                            artworkUrl = api.artwork.moviePoster(m.id),
                                            streamUrl = api.streamUrl("movie", m.id.toString()),
                                            isVideo = true,
                                            onOpenDetails = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
                                        ),
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
                    api.saveHomeLayout(layout = updated, device = if (shared) "*" else "android-phone")
                }
            },
            onReset = {
                homeLayout = HomeLayout()
                showEditor = false
                scope.launch { api.resetHomeLayout(device = "android-phone") }
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

/**
 * Netflix / Apple TV style muted live video preview inside the Home Hero Billboard.
 * Uses TextureView (so it clips & fades smoothly inside LazyColumn) + ExoPlayer with our
 * authenticated OkHttpDataSource, seeks 10% into the title to skip black studio logos,
 * and silently falls back to the high-res backdrop image on any codec/network error.
 */
@OptIn(UnstableApi::class)
@Composable
private fun HeroVideoBackdrop(
    streamUrl: String,
    pinnedHttpClient: OkHttpClient,
    muted: Boolean,
    onReadyChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var startPreview by remember(streamUrl) { mutableStateOf(false) }
    var firstFrameRendered by remember(streamUrl) { mutableStateOf(false) }

    LaunchedEffect(streamUrl) {
        firstFrameRendered = false
        onReadyChanged(false)
        delay(1_400L)
        startPreview = true
    }

    if (!startPreview) return

    val exoPlayer = remember(streamUrl) {
        val dataSourceFactory = OkHttpDataSource.Factory(pinnedHttpClient)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }

    LaunchedEffect(exoPlayer, muted) {
        exoPlayer.volume = if (muted) 0f else 0.85f
    }

    var textureViewRef by remember(streamUrl) { mutableStateOf<TextureView?>(null) }

    DisposableEffect(exoPlayer) {
        var soughtPreview = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !soughtPreview) {
                    val dur = exoPlayer.duration
                    if (dur > 60_000L) {
                        soughtPreview = true
                        exoPlayer.seekTo((dur * 0.10).toLong().coerceAtMost(180_000L))
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                onReadyChanged(true)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val tv = textureViewRef ?: return
                if (videoSize.width > 0 && videoSize.height > 0 && tv.width > 0 && tv.height > 0) {
                    val viewW = tv.width.toFloat()
                    val viewH = tv.height.toFloat()
                    val videoRatio = videoSize.width.toFloat() / videoSize.height.toFloat()
                    val viewRatio = viewW / viewH
                    val scaleX: Float
                    val scaleY: Float
                    if (videoRatio > viewRatio) {
                        scaleX = videoRatio / viewRatio
                        scaleY = 1f
                    } else {
                        scaleX = 1f
                        scaleY = viewRatio / videoRatio
                    }
                    val matrix = android.graphics.Matrix()
                    matrix.setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
                    tv.setTransform(matrix)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                firstFrameRendered = false
                onReadyChanged(false)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoTextureView(textureViewRef)
            exoPlayer.release()
            onReadyChanged(false)
        }
    }

    val videoAlpha by animateFloatAsState(
        targetValue = if (firstFrameRendered) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "hero_video_fade",
    )

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                textureViewRef = tv
                exoPlayer.setVideoTextureView(tv)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .alpha(videoAlpha),
    )
}

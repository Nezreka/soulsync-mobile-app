package net.ssync.app.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.ssync.pairing.theme.SoulSyncColors
import net.ssync.player.CatalogMovie
import net.ssync.player.CatalogRecentEpisode
import net.ssync.player.CatalogShow
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.StudioCollection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class TvBrowseMode(val label: String) {
    DISCOVER("Discover"),
    LIBRARY("All Titles"),
    COLLECTIONS("Collections"),
    GENRES("Genres"),
}

private data class TvBrowseCard(
    val key: String,
    val title: String,
    val subtitle: String?,
    val metaChips: List<String>,
    val ratingText: String?,
    val contentRating: String?,
    val overview: String? = null,
    val posterUrl: String?,
    val backdropUrl: String?,
    val badge: String? = null,
    val rank: Int? = null,
    val detailKind: String? = null, // "movie" | "show"
    val detailId: Int? = null,
    val tmdbId: Int? = null,
    val isShow: Boolean = false,
    val onClick: () -> Unit,
)

private data class TvBrowseShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val items: List<TvBrowseCard>,
)

private data class TvBrowseEnrichment(
    val overview: String? = null,
    val castLine: String? = null,
    val logoUrl: String? = null,
)

private fun fmtRuntime(seconds: Int?): String? {
    if (seconds == null || seconds <= 60) return null
    val mins = seconds / 60
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvCinemaMoviesHub(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    movies: List<CatalogMovie>,
    studioCollections: List<StudioCollection>,
    onNavigate: (PlayerDestination) -> Unit,
) {
    val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val moviesById = remember(movies) { movies.associateBy { it.id } }

    fun toCard(prefix: String, m: CatalogMovie, rank: Int? = null, badge: String? = null): TvBrowseCard {
        val chips = buildList {
            m.year?.let { add(it.toString()) }
            fmtRuntime(m.runtimeSeconds)?.let { add(it) }
            if (m.genres.isNotEmpty()) add(m.genres.take(2).joinToString(", "))
        }
        return TvBrowseCard(
            key = "$prefix-${m.id}",
            title = m.title,
            subtitle = m.studio?.takeIf { it.isNotBlank() },
            metaChips = chips,
            ratingText = m.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
            contentRating = m.contentRating?.takeIf { it.isNotBlank() },
            posterUrl = api.artwork.moviePoster(m.id, width = 500),
            backdropUrl = if (m.hasBackdrop) api.artwork.movieBackdrop(m.id, width = 1280) else api.artwork.moviePoster(m.id, width = 780),
            badge = badge,
            rank = rank,
            detailKind = "movie",
            detailId = m.id,
            tmdbId = m.tmdbId,
            isShow = false,
            onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
        )
    }

    // Build the 8,930-movie curated shelves OFF the main thread (Dispatchers.Default)
    // and stream them in progressively: New Releases + Recently Added paint first in <15ms,
    // followed seamlessly by Top 10, Collections, Acclaimed, Quick Watches, Epics, Genres & Classics!
    var genres by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var discoverShelves by remember { mutableStateOf<List<TvBrowseShelf>>(emptyList()) }
    var tmdbCollections by remember { mutableStateOf<List<Map.Entry<String, List<CatalogMovie>>>>(emptyList()) }

    LaunchedEffect(movies, studioCollections, currentYear) {
        // Phase 1 (instant, <10ms on Default dispatcher): first 2 visible rows
        val phase1 = withContext(Dispatchers.Default) {
            val firstRows = mutableListOf<TvBrowseShelf>()
            val newReleases = movies
                .filter { (it.year ?: 0) >= currentYear - 1 }
                .sortedWith(compareByDescending<CatalogMovie> { it.year ?: 0 }.thenByDescending { it.addedAt.orEmpty() })
                .take(20)
            if (newReleases.size >= 3) {
                firstRows += TvBrowseShelf("new-releases", "New Releases", "${currentYear - 1}–$currentYear", newReleases.map { toCard("new", it) })
            }
            val recent = movies.sortedByDescending { it.addedAt.orEmpty() }.take(20)
            if (recent.isNotEmpty()) {
                firstRows += TvBrowseShelf("recent", "Recently Added", items = recent.map { toCard("rec", it) })
            }
            firstRows
        }
        discoverShelves = phase1

        // Phase 2 (background off main thread): all remaining curated rows, genres, & franchise groups
        val (computedGenres, fullShelves, computedCols) = withContext(Dispatchers.Default) {
            val gList = movies.flatMap { it.genres }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key to it.value }
            val out = phase1.toMutableList()

            val top10 = movies.filter { (it.rating ?: 0.0) > 0.0 }.sortedByDescending { it.rating ?: 0.0 }.take(10)
            if (top10.size >= 3) {
                out += TvBrowseShelf("top-10", "Top 10 Highest Rated", items = top10.mapIndexed { i, m -> toCard("top10", m, rank = i + 1) })
            }

            val colCards = studioCollections.mapNotNull { c ->
                val mem = c.members.filter { moviesById.containsKey(it) }
                if (mem.isEmpty()) return@mapNotNull null
                val safePoster = c.poster?.takeIf { !it.startsWith("/api/video/collections/") }
                val art = api.artwork.resolve(safePoster ?: c.memberPosters.firstOrNull())
                    ?: api.artwork.moviePoster(mem.first(), width = 500)
                val backdrop = api.artwork.movieBackdrop(mem.first(), width = 1280)
                TvBrowseCard(
                    key = "col-${c.id}",
                    title = c.name,
                    subtitle = "${mem.size} ${if (mem.size == 1) "film" else "films"} · SoulSync Collection",
                    metaChips = listOf("Collection", "${mem.size} films"),
                    ratingText = null,
                    contentRating = null,
                    overview = c.summary,
                    posterUrl = art,
                    backdropUrl = backdrop,
                    badge = if (c.pinned) "★" else null,
                    onClick = { onNavigate(PlayerDestination.CollectionDetail(c)) },
                )
            }
            if (colCards.isNotEmpty()) {
                out += TvBrowseShelf("collections", "Curated Collections", "${colCards.size} collections", colCards)
            }

            val acclaimed = movies.filter { (it.rating ?: 0.0) >= 7.6 }.sortedByDescending { it.rating ?: 0.0 }.drop(4).take(20)
            if (acclaimed.size >= 4) {
                out += TvBrowseShelf("acclaimed", "Critically Acclaimed", "★ 7.6+", acclaimed.map { toCard("acc", it) })
            }

            val quickWatches = movies.filter { (it.runtimeSeconds ?: 0) in 3600..6000 }.sortedByDescending { it.rating ?: 0.0 }.take(18)
            if (quickWatches.size >= 4) {
                out += TvBrowseShelf("quick", "Quick Watches", "Under 100 minutes", quickWatches.map { toCard("quick", it) })
            }

            val epics = movies.filter { (it.runtimeSeconds ?: 0) >= 8100 }.sortedByDescending { it.rating ?: 0.0 }.take(18)
            if (epics.size >= 4) {
                out += TvBrowseShelf("epics", "Epic Cinema", "2h 15m+", epics.map { toCard("epic", it) })
            }

            // Curated Editorial Rails (replacing raw genre spam — Genres have their own tab above!)
            val edgeOfSeat = movies.filter { m ->
                m.genres.any { it.equals("Thriller", true) || it.equals("Action", true) || it.equals("Sci-Fi", true) || it.equals("Science Fiction", true) } &&
                    (m.rating ?: 0.0) >= 6.8
            }.sortedByDescending { it.rating ?: 0.0 }.take(18)
            if (edgeOfSeat.size >= 4) {
                out += TvBrowseShelf("edge", "Edge of Your Seat", "Action, Thriller & Sci-Fi", edgeOfSeat.map { toCard("edge", it) })
            }

            val feelGood = movies.filter { m ->
                m.genres.any { it.equals("Comedy", true) || it.equals("Romance", true) || it.equals("Family", true) || it.equals("Animation", true) } &&
                    (m.rating ?: 0.0) >= 7.0
            }.sortedByDescending { it.rating ?: 0.0 }.take(18)
            if (feelGood.size >= 4) {
                out += TvBrowseShelf("feelgood", "Feel-Good & Date Night", "Comedy, Romance & Family", feelGood.map { toCard("fg", it) })
            }

            val hiddenGems = movies.filter { m ->
                (m.rating ?: 0.0) >= 7.5 && (m.year ?: 9999) in 2005..(currentYear - 3)
            }.sortedByDescending { it.rating ?: 0.0 }.drop(6).take(18)
            if (hiddenGems.size >= 4) {
                out += TvBrowseShelf("gems", "Hidden Gems", "Modern favorites ★ 7.5+", hiddenGems.map { toCard("gem", it) })
            }

            val classics = movies.filter { (it.year ?: 9999) in 1970..2004 }.sortedByDescending { it.rating ?: 0.0 }.take(18)
            if (classics.size >= 4) {
                out += TvBrowseShelf("classics", "Timeless Classics", "1970–2004", classics.map { toCard("cls", it) })
            }

            val cols = movies.filter { !it.collection.isNullOrBlank() }
                .groupBy { it.collection!! }
                .filter { it.value.size >= 2 }
                .entries
                .sortedByDescending { it.value.size }

            Triple(gList, out.toList(), cols)
        }
        genres = computedGenres
        discoverShelves = fullShelves
        tmdbCollections = computedCols
    }

    val allLibraryCards = remember(movies) {
        movies.map { m -> m to toCard("lib", m) }
    }

    TvCinemaBrowseScaffold(
        pageTitle = "Movies",
        totalCountLabel = "${movies.size} films",
        api = api,
        coordinator = coordinator,
        discoverShelves = discoverShelves,
        genres = genres,
        studioCollections = studioCollections.filter { c -> c.members.any { moviesById.containsKey(it) } },
        namedGroups = tmdbCollections.map { (name, list) ->
            Triple(name, "${list.size} films", list.map { toCard("grp-$name", it) })
        },
        allItems = allLibraryCards.map { (m, card) ->
            TvLibrarySortable(
                card = card,
                title = m.title,
                addedAt = m.addedAt.orEmpty(),
                year = m.year ?: 0,
                rating = m.rating ?: 0.0,
                genres = m.genres,
                groupKey = m.collection ?: m.studio,
            )
        },
        onOpenStudioCollection = { onNavigate(PlayerDestination.CollectionDetail(it)) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvCinemaTvHub(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    shows: List<CatalogShow>,
    recentEpisodes: List<CatalogRecentEpisode>,
    studioCollections: List<StudioCollection>,
    onNavigate: (PlayerDestination) -> Unit,
) {
    val showsById = remember(shows) { shows.associateBy { it.id } }

    fun toCard(prefix: String, s: CatalogShow, rank: Int? = null, badge: String? = null): TvBrowseCard {
        val chips = buildList {
            s.year?.let { add(it.toString()) }
            if (s.episodeCount > 0) add("${s.episodeCount} eps")
            if (s.genres.isNotEmpty()) add(s.genres.take(2).joinToString(", "))
        }
        return TvBrowseCard(
            key = "$prefix-${s.id}",
            title = s.title,
            subtitle = s.network?.takeIf { it.isNotBlank() },
            metaChips = chips,
            ratingText = s.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
            contentRating = s.contentRating?.takeIf { it.isNotBlank() },
            posterUrl = api.artwork.showPoster(s.id, width = 500),
            backdropUrl = if (s.hasBackdrop) api.artwork.showBackdrop(s.id, width = 1280) else api.artwork.showPoster(s.id, width = 780),
            badge = badge,
            rank = rank,
            detailKind = "show",
            detailId = s.id,
            tmdbId = s.tmdbId,
            isShow = true,
            onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
        )
    }

    val genres = remember(shows) {
        shows.flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    val networks = remember(shows) {
        shows.filter { !it.network.isNullOrBlank() }
            .groupBy { it.network!! }
            .filter { it.value.size >= 2 }
            .entries
            .sortedByDescending { it.value.size }
    }

    val discoverShelves = remember(shows, recentEpisodes, studioCollections, networks) {
        val out = mutableListOf<TvBrowseShelf>()

        // 1. New Episodes
        if (recentEpisodes.isNotEmpty()) {
            val epCards = recentEpisodes.take(20).map { ep ->
                val show = showsById[ep.showId]
                val tag = "S${ep.seasonNumber}·E${ep.episodeNumber}"
                val sub = listOfNotNull("S${ep.seasonNumber} • E${ep.episodeNumber}", ep.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
                TvBrowseCard(
                    key = "newep-${ep.id}",
                    title = ep.showTitle.ifBlank { show?.title ?: "Episode" },
                    subtitle = sub,
                    metaChips = listOfNotNull(fmtRuntime(ep.runtimeSeconds), show?.network),
                    ratingText = show?.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
                    contentRating = show?.contentRating,
                    posterUrl = api.artwork.showPoster(ep.showId, width = 500),
                    backdropUrl = if (ep.hasStill) api.artwork.episodeStill(ep.id, width = 1280) else api.artwork.showBackdrop(ep.showId, width = 1280),
                    badge = tag,
                    detailKind = if (show != null) "show" else null,
                    detailId = show?.id,
                    tmdbId = show?.tmdbId,
                    isShow = true,
                    onClick = { onNavigate(PlayerDestination.EpisodeDetail(ep.showId, ep.id)) },
                )
            }
            out += TvBrowseShelf("new-eps", "New Episodes", items = epCards)
        }

        // 2. Recently Added Shows
        val recent = shows.sortedByDescending { it.addedAt.orEmpty() }.take(20)
        if (recent.isNotEmpty()) {
            out += TvBrowseShelf("recent", "Recently Added Series", items = recent.map { toCard("rec", it) })
        }

        // 3. Top 10 Highest Rated Series
        val top10 = shows.filter { (it.rating ?: 0.0) > 0.0 }.sortedByDescending { it.rating ?: 0.0 }.take(10)
        if (top10.size >= 3) {
            out += TvBrowseShelf(
                id = "top-10",
                title = "Top 10 Highest Rated Series",
                items = top10.mapIndexed { i, s -> toCard("top10", s, rank = i + 1) },
            )
        }

        // 4. TV Studio Collections
        val colCards = studioCollections.mapNotNull { c ->
            val mem = c.members.filter { showsById.containsKey(it) }
            if (mem.isEmpty()) return@mapNotNull null
            val safePoster = c.poster?.takeIf { !it.startsWith("/api/video/collections/") }
            val art = api.artwork.resolve(safePoster ?: c.memberPosters.firstOrNull())
                ?: api.artwork.showPoster(mem.first(), width = 500)
            val backdrop = api.artwork.showBackdrop(mem.first(), width = 1280)
            TvBrowseCard(
                key = "tvcol-${c.id}",
                title = c.name,
                subtitle = "${mem.size} ${if (mem.size == 1) "show" else "shows"} · SoulSync Collection",
                metaChips = listOf("Collection", "${mem.size} shows"),
                ratingText = null,
                contentRating = null,
                overview = c.summary,
                posterUrl = art,
                backdropUrl = backdrop,
                badge = if (c.pinned) "★" else null,
                isShow = true,
                onClick = { onNavigate(PlayerDestination.CollectionDetail(c)) },
            )
        }
        if (colCards.isNotEmpty()) {
            out += TvBrowseShelf("collections", "Curated Collections", items = colCards)
        }

        // 5. Limited & Mini-Series (<= 12 episodes)
        val mini = shows
            .filter { it.episodeCount in 2..12 }
            .sortedByDescending { it.rating ?: 0.0 }
            .take(18)
        if (mini.size >= 4) {
            out += TvBrowseShelf("mini", "Weekend Binges · Limited Series", subtitle = "12 episodes or fewer", items = mini.map { toCard("mini", it) })
        }

        // 6. Long-Running Marathons (40+ episodes)
        val longRun = shows
            .filter { it.episodeCount >= 40 }
            .sortedByDescending { it.rating ?: 0.0 }
            .take(18)
        if (longRun.size >= 4) {
            out += TvBrowseShelf("marathon", "Deep Binges · 40+ Episodes", items = longRun.map { toCard("mar", it) })
        }

        // 7. Top Networks
        networks.take(4).forEach { (net, list) ->
            if (list.size >= 3) {
                out += TvBrowseShelf(
                    id = "net-$net",
                    title = "Best of $net",
                    subtitle = "${list.size} series",
                    items = list.sortedByDescending { it.rating ?: 0.0 }.take(18).map { toCard("net-$net", it) },
                )
            }
        }

        // 8. Curated Editorial TV Rails (replacing raw genre spam — Genres have their own tab above!)
        val acclaimedTv = shows.filter { (it.rating ?: 0.0) >= 8.0 }
            .sortedByDescending { it.rating ?: 0.0 }
            .drop(4)
            .take(18)
        if (acclaimedTv.size >= 4) {
            out += TvBrowseShelf("acclaimed-tv", "Critically Acclaimed Series", "★ 8.0+", acclaimedTv.map { toCard("acctv", it) })
        }

        val comfortComedy = shows.filter { s ->
            s.genres.any { it.equals("Comedy", true) || it.equals("Animation", true) } && (s.rating ?: 0.0) >= 7.2
        }.sortedByDescending { it.rating ?: 0.0 }.take(18)
        if (comfortComedy.size >= 4) {
            out += TvBrowseShelf("comfort-tv", "Comfort & Comedy Binges", "Easy-watching laughs", comfortComedy.map { toCard("comf", it) })
        }

        val mindBending = shows.filter { s ->
            s.genres.any { it.contains("Sci-Fi", true) || it.equals("Mystery", true) || it.equals("Crime", true) || it.equals("Drama", true) } &&
                (s.rating ?: 0.0) >= 7.6
        }.sortedByDescending { it.rating ?: 0.0 }.take(18)
        if (mindBending.size >= 4) {
            out += TvBrowseShelf("prestige-tv", "Prestige Drama & Sci-Fi", "Gripping storytelling", mindBending.map { toCard("prest", it) })
        }

        out
    }

    TvCinemaBrowseScaffold(
        pageTitle = "TV Shows",
        totalCountLabel = "${shows.size} series",
        api = api,
        coordinator = coordinator,
        discoverShelves = discoverShelves,
        genres = genres,
        studioCollections = studioCollections.filter { c -> c.members.any { showsById.containsKey(it) } },
        namedGroups = networks.map { (net, list) ->
            Triple(net, "${list.size} series", list.map { toCard("netgrp-$net", it) })
        },
        allItems = shows.map { s ->
            TvLibrarySortable(
                card = toCard("lib", s),
                title = s.title,
                addedAt = s.addedAt.orEmpty(),
                year = s.year ?: 0,
                rating = s.rating ?: 0.0,
                genres = s.genres,
                groupKey = s.network,
            )
        },
        onOpenStudioCollection = { onNavigate(PlayerDestination.CollectionDetail(it)) },
    )
}

private data class TvLibrarySortable(
    val card: TvBrowseCard,
    val title: String,
    val addedAt: String,
    val year: Int,
    val rating: Double,
    val genres: List<String>,
    val groupKey: String?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvCinemaBrowseScaffold(
    pageTitle: String,
    totalCountLabel: String,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    discoverShelves: List<TvBrowseShelf>,
    genres: List<Pair<String, Int>>,
    studioCollections: List<StudioCollection>,
    namedGroups: List<Triple<String, String, List<TvBrowseCard>>>,
    allItems: List<TvLibrarySortable>,
    onOpenStudioCollection: (StudioCollection) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    val density = LocalDensity.current

    var mode by remember { mutableStateOf(TvBrowseMode.DISCOVER) }
    var sortKey by remember { mutableStateOf("added") } // added | title | year | rating
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }

    val filteredLibrary = remember(allItems, selectedGenre, selectedGroup, sortKey) {
        val base = allItems.filter { item ->
            (selectedGenre == null || item.genres.any { it.equals(selectedGenre, ignoreCase = true) }) &&
                (selectedGroup == null || item.groupKey.equals(selectedGroup, ignoreCase = true))
        }
        when (sortKey) {
            "title" -> base.sortedBy { it.title.lowercase().removePrefix("the ").removePrefix("a ") }
            "year" -> base.sortedByDescending { it.year }
            "rating" -> base.sortedByDescending { it.rating }
            else -> base.sortedByDescending { it.addedAt }
        }
    }

    val defaultCard = discoverShelves.firstOrNull()?.items?.firstOrNull() ?: allItems.firstOrNull()?.card
    var focusedCard by remember { mutableStateOf<TvBrowseCard?>(null) }
    val activeCard = focusedCard ?: defaultCard

    val enrichmentCache = remember { mutableStateMapOf<String, TvBrowseEnrichment>() }
    val prefetchedLogos = remember { mutableStateMapOf<String, String>() }
    val colorCache = remember { mutableStateMapOf<String, Color>() }
    var targetAmbient by remember { mutableStateOf(Color(0xFF151326)) }

    // Batch-prefetch official TMDB wordmark logos for all visible Discover cards (shared app-wide via HomeWarmCache!)
    LaunchedEffect(discoverShelves, api) {
        val keys = discoverShelves
            .flatMap { it.items }
            .mapNotNull { c -> if (c.detailKind != null && c.tmdbId != null) "${c.detailKind}:${c.tmdbId}" else null }
            .distinct()
        keys.forEach { k ->
            HomeWarmCache.resolvedLogos[k]?.let { prefetchedLogos[k] = it }
        }
        val missing = keys.filter { !prefetchedLogos.containsKey(it) }
        missing.chunked(30).forEach { batch ->
            (api.fetchLogos(batch) as? MusicApiResult.Success)?.data?.forEach { (k, raw) ->
                api.artwork.resolve(raw)?.let { resolved ->
                    prefetchedLogos[k] = resolved
                    HomeWarmCache.resolvedLogos[k] = resolved
                }
            }
        }
    }

    // Dynamic ambient tint + debounced overview/cast enrichment
    LaunchedEffect(activeCard?.key) {
        val card = activeCard ?: return@LaunchedEffect
        val artUrl = card.backdropUrl ?: card.posterUrl
        if (!artUrl.isNullOrBlank()) {
            val cached = colorCache[artUrl]
            if (cached != null) {
                targetAmbient = cached
            } else {
                val extracted = withContext(Dispatchers.IO) {
                    runCatching {
                        val req = ImageRequest.Builder(context)
                            .data(artUrl)
                            .size(48)
                            .allowHardware(false)
                            .build()
                        val bmp = (imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                        bmp?.let { extractCinemaAmbientColor(it) }
                    }.getOrNull()
                }
                if (extracted != null) {
                    colorCache[artUrl] = extracted
                    targetAmbient = extracted
                }
            }
        }
        val kind = card.detailKind
        val id = card.detailId
        if (kind != null && id != null) {
            val cacheKey = "$kind:$id"
            if (!enrichmentCache.containsKey(cacheKey)) {
                delay(200L)
                var overview: String? = null
                var castLine: String? = null
                var logoUrl: String? = card.tmdbId?.let { prefetchedLogos["$kind:$it"] }
                if (kind == "movie") {
                    (api.fetchMovieDetail(id) as? MusicApiResult.Success)?.data?.let { d ->
                        overview = d.overview?.takeIf { it.isNotBlank() }
                        castLine = d.cast.take(4).mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.joinToString(", ").ifBlank { null }
                    }
                } else if (kind == "show") {
                    (api.fetchShowDetail(id) as? MusicApiResult.Success)?.data?.let { d ->
                        overview = d.overview?.takeIf { it.isNotBlank() }
                        castLine = d.cast.take(4).mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.joinToString(", ").ifBlank { null }
                    }
                }
                if (logoUrl == null && card.tmdbId != null) {
                    val lk = "$kind:${card.tmdbId}"
                    (api.fetchLogos(listOf(lk)) as? MusicApiResult.Success)?.data?.get(lk)?.let { raw ->
                        logoUrl = api.artwork.resolve(raw)
                    }
                }
                enrichmentCache[cacheKey] = TvBrowseEnrichment(overview, castLine, logoUrl)
            }
        }
    }

    val animatedAmbient by animateColorAsState(
        targetValue = targetAmbient,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "tvBrowseAmbient",
    )

    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            clockText = fmt.format(Date())
            delay(15_000L)
        }
    }

    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(mode) {
        if (mode == TvBrowseMode.DISCOVER && discoverShelves.isNotEmpty()) {
            delay(80L)
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    val shelfBringIntoView = remember(density) {
        val headerClearancePx = with(density) { 42.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                return if (kotlin.math.abs(offset - headerClearancePx) < 8f) 0f else offset - headerClearancePx
            }
        }
    }
    val rowBringIntoView = remember(density) {
        val pillClearancePx = with(density) { 78.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                val rightEdge = containerSize - with(density) { 32.dp.toPx() }
                return when {
                    offset < pillClearancePx -> offset - pillClearancePx
                    offset + size > rightEdge -> (offset + size) - rightEdge
                    else -> 0f
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedAmbient),
    ) {
        // 1. Feathered HD backdrop anchored top-right
        val activeBackdrop = activeCard?.backdropUrl ?: activeCard?.posterUrl
        AnimatedContent(
            targetState = activeBackdrop,
            transitionSpec = { fadeIn(tween(360, easing = FastOutSlowInEasing)).togetherWith(fadeOut(tween(240))) },
            label = "tvBrowseBackdrop",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.66f),
        ) { url ->
            if (!url.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .size(1920, 1080)
                        .precision(Precision.INEXACT)
                        .crossfade(false)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Seamless cinema feathering
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to animatedAmbient,
                        0.34f to animatedAmbient.copy(alpha = 0.96f),
                        0.56f to animatedAmbient.copy(alpha = 0.48f),
                        0.78f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.16f),
                        0.36f to Color.Transparent,
                        0.56f to animatedAmbient.copy(alpha = 0.86f),
                        0.70f to animatedAmbient,
                        1.0f to Color.Black.copy(alpha = 0.24f),
                    ),
                ),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Mode Strip: Page Title + Clean Pill Tabs (Discover / All Titles / Collections / Genres)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 78.dp, end = 160.dp, top = 16.dp, bottom = 4.dp),
            ) {
                Text(
                    text = pageTitle,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    text = totalCountLabel,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.width(10.dp))
                TvBrowseMode.entries.forEach { m ->
                    val lbl = if (m == TvBrowseMode.LIBRARY) "All $pageTitle" else m.label
                    TvCinemaPillTab(
                        label = lbl,
                        selected = mode == m,
                        onClick = {
                            mode = m
                            if (m != TvBrowseMode.LIBRARY) {
                                selectedGenre = null
                                selectedGroup = null
                            }
                        },
                    )
                }
            }

            if (mode == TvBrowseMode.DISCOVER) {
                // Pinned Editorial Spotlight Stage (38% height)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.39f)
                        .padding(start = 78.dp, end = 28.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    if (activeCard != null) {
                        val enrichKey = if (activeCard.detailKind != null && activeCard.detailId != null) {
                            "${activeCard.detailKind}:${activeCard.detailId}"
                        } else null
                        val enriched = enrichKey?.let { enrichmentCache[it] }
                        val preLogo = if (activeCard.detailKind != null && activeCard.tmdbId != null) {
                            prefetchedLogos["${activeCard.detailKind}:${activeCard.tmdbId}"]
                        } else null
                        val logoUrl = enriched?.logoUrl ?: preLogo
                        val overview = enriched?.overview ?: activeCard.overview
                        val castLine = enriched?.castLine

                        AnimatedContent(
                            targetState = activeCard.key,
                            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(130))) },
                            label = "tvBrowseMeta",
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(0.56f),
                        ) { _ ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (!logoUrl.isNullOrBlank()) {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(logoUrl)
                                            .size(900)
                                            .precision(Precision.INEXACT)
                                            .crossfade(true)
                                            .build(),
                                        imageLoader = imageLoader,
                                        contentDescription = activeCard.title,
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.BottomStart,
                                        modifier = Modifier
                                            .fillMaxWidth(0.84f)
                                            .height(60.dp),
                                        error = {
                                            Text(
                                                text = activeCard.title,
                                                color = Color.White,
                                                fontSize = 30.sp,
                                                lineHeight = 34.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                letterSpacing = (-0.5).sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                } else {
                                    Text(
                                        text = activeCard.title,
                                        color = Color.White,
                                        fontSize = 30.sp,
                                        lineHeight = 34.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.5).sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (!activeCard.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = activeCard.subtitle,
                                        color = Color.White.copy(alpha = 0.90f),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (activeCard.metaChips.isNotEmpty() || activeCard.ratingText != null || activeCard.contentRating != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (activeCard.metaChips.isNotEmpty()) {
                                            Text(
                                                text = activeCard.metaChips.joinToString("  ·  "),
                                                color = Color.White.copy(alpha = 0.78f),
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        }
                                        if (!activeCard.ratingText.isNullOrBlank()) {
                                            Text(
                                                text = activeCard.ratingText,
                                                color = Color(0xFFFBBF24),
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        if (!activeCard.contentRating.isNullOrBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .border(1.dp, Color.White.copy(alpha = 0.42f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            ) {
                                                Text(
                                                    text = activeCard.contentRating,
                                                    color = Color.White.copy(alpha = 0.90f),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }
                                }

                                if (!overview.isNullOrBlank()) {
                                    Text(
                                        text = overview,
                                        color = Color.White.copy(alpha = 0.84f),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                if (!castLine.isNullOrBlank()) {
                                    Text(
                                        text = castLine,
                                        color = Color.White.copy(alpha = 0.56f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom Curated Poster Shelves (61% height)
                val listState = rememberLazyListState()
                CompositionLocalProvider(LocalBringIntoViewSpec provides shelfBringIntoView) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.61f),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        itemsIndexed(discoverShelves, key = { _, s -> s.id }) { sIdx, shelf ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(start = 78.dp, end = 24.dp),
                                ) {
                                    Text(
                                        text = shelf.title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (!shelf.subtitle.isNullOrBlank()) {
                                        Text(
                                            text = "· ${shelf.subtitle}",
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
                                    LazyRow(
                                        contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        itemsIndexed(shelf.items, key = { _, c -> c.key }) { cIdx, card ->
                                            TvBrowsePosterTile(
                                                card = card,
                                                modifier = if (sIdx == 0 && cIdx == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                                                onFocused = { focusedCard = card },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (mode == TvBrowseMode.LIBRARY) {
                // Sort + Genre Filter Bar + Crisp Full Poster Grid
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 78.dp, end = 28.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    val activeFilter = selectedGenre ?: selectedGroup
                    if (activeFilter != null) {
                        TvCinemaPillTab(
                            label = "$activeFilter ✕",
                            selected = true,
                            onClick = {
                                selectedGenre = null
                                selectedGroup = null
                            },
                        )
                    }
                    listOf(
                        "added" to "Recently Added",
                        "rating" to "Highest Rated",
                        "year" to "Newest",
                        "title" to "A–Z",
                    ).forEach { (k, lbl) ->
                        TvCinemaPillTab(
                            label = lbl,
                            selected = sortKey == k,
                            onClick = { sortKey = k },
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${filteredLibrary.size} titles",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    contentPadding = PaddingValues(start = 78.dp, end = 28.dp, top = 6.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredLibrary, key = { it.card.key }) { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            TvBrowsePosterTile(
                                card = item.card,
                                onFocused = { focusedCard = item.card },
                            )
                            Text(
                                text = item.title,
                                color = Color.White.copy(alpha = 0.92f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else if (mode == TvBrowseMode.COLLECTIONS) {
                LazyColumn(
                    contentPadding = PaddingValues(start = 78.dp, end = 28.dp, top = 12.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (studioCollections.isNotEmpty()) {
                        items(studioCollections, key = { "sc-${it.id}" }) { c ->
                            val posters = buildList {
                                c.poster?.takeIf { !it.startsWith("/api/video/collections/") }?.let {
                                    api.artwork.resolve(it)?.let { u -> add(u) }
                                }
                                c.members.take(4).forEach { id ->
                                    val u = if (c.mediaType == "movie") api.artwork.moviePoster(id) else api.artwork.showPoster(id)
                                    if (u !in this) add(u)
                                }
                            }.take(4)
                            TvCinemaCollectionRow(
                                title = if (c.pinned) "★ ${c.name}" else c.name,
                                subtitle = "${c.members.size} titles · SoulSync Collection",
                                posters = posters,
                                onClick = { onOpenStudioCollection(c) },
                            )
                        }
                    }
                    items(namedGroups, key = { "ng-${it.first}" }) { (name, countLbl, list) ->
                        TvCinemaCollectionRow(
                            title = name,
                            subtitle = countLbl,
                            posters = list.take(4).mapNotNull { it.posterUrl },
                            onClick = {
                                selectedGroup = name
                                selectedGenre = null
                                mode = TvBrowseMode.LIBRARY
                            },
                        )
                    }
                }
            } else {
                // GENRES Grid
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    contentPadding = PaddingValues(start = 78.dp, end = 28.dp, top = 12.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(genres, key = { it.first }) { (g, count) ->
                        TvCinemaGenreTile(
                            title = g,
                            subtitle = "$count titles",
                            onClick = {
                                selectedGenre = g
                                selectedGroup = null
                                mode = TvBrowseMode.LIBRARY
                            },
                        )
                    }
                }
            }
        }

        // Top-Right Floating HUD: Mini Music Player + Live Clock
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 24.dp),
        ) {
            val activeAudio = coordinator.activeItem
            if (activeAudio != null && !activeAudio.isVideo) {
                TvTopRightMiniPlayer(coordinator = coordinator, item = activeAudio)
            }
            if (clockText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.34f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = clockText,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun TvBrowsePosterTile(
    card: TvBrowseCard,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "tvBrowseTileScale",
    )
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .width(132.dp)
            .height(196.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (focused) Modifier.shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                else Modifier,
            )
            .clip(shape)
            .background(Color(0xFF141622))
            .then(
                if (focused) Modifier.border(2.5.dp, Color.White, shape)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape),
            )
            .onFocusChanged { state ->
                val now = state.isFocused || state.hasFocus
                if (now != focused) {
                    focused = now
                    if (now) onFocused()
                }
            }
            .clickable(onClick = card.onClick),
    ) {
        SoulSyncArtwork(
            url = card.posterUrl,
            contentDescription = card.title,
            fallbackIcon = if (card.isShow) Icons.Default.Tv else Icons.Default.Movie,
            maxPx = 600,
            modifier = Modifier.fillMaxSize(),
        )

        if (card.rank != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xD90B0D14))
                    .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "#${card.rank}",
                    color = Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        } else if (!card.badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = card.badge,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun TvCinemaPillTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> SoulSyncColors.Accent.copy(alpha = 0.34f)
        else -> Color.Black.copy(alpha = 0.30f)
    }
    val fg = if (focused) Color(0xFF0B0D14) else Color.White
    val borderColor = when {
        focused -> Color.White
        selected -> SoulSyncColors.AccentLight.copy(alpha = 0.80f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, borderColor, CircleShape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TvCinemaCollectionRow(
    title: String,
    subtitle: String,
    posters: List<String>,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.36f))
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.12f), shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            posters.take(4).forEach { url ->
                SoulSyncArtwork(
                    url = url,
                    contentDescription = title,
                    fallbackIcon = Icons.Default.Movie,
                    maxPx = 220,
                    modifier = Modifier
                        .width(46.dp)
                        .height(68.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = if (focused) Color(0xFF0B0D14) else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = if (focused) Color(0xFF475569) else Color.White.copy(alpha = 0.68f),
                fontSize = 12.5.sp,
            )
        }
    }
}

@Composable
private fun TvCinemaGenreTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.38f))
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.14f), shape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = if (focused) Color(0xFF0B0D14) else Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = if (focused) Color(0xFF475569) else Color.White.copy(alpha = 0.68f),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

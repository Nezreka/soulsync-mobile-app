package net.ssync.app.player

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import net.ssync.player.CatalogShow
import net.ssync.player.DiscoverPlaylistSummary
import net.ssync.player.HomeLayout
import net.ssync.player.LibAlbum
import net.ssync.player.MusicApiResult
import net.ssync.player.OwnedBook
import net.ssync.player.PlayerApiClient
import net.ssync.player.PodcastSub
import net.ssync.player.ProgressRow
import net.ssync.player.StudioCollection
import net.ssync.player.VideoCatalog
import net.ssync.player.WatchlistItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class TvSpotlightCard(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val metaChips: List<String> = emptyList(),
    val ratingText: String? = null,
    val contentRating: String? = null,
    val overview: String? = null,
    val castLine: String? = null,
    val posterUrl: String?,
    val backdropUrl: String?,
    val fallbackIcon: ImageVector = Icons.Default.Movie,
    val progress: Float? = null,
    val badge: String? = null,
    val isLive: Boolean = false,
    val detailKind: String? = null, // "movie" | "show"
    val detailId: Int? = null,
    val tmdbId: Int? = null,
    val onClick: () -> Unit,
)

private data class TvSpotlightShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val cardStyle: TvCardStyle,
    val items: List<TvSpotlightCard>,
)

private enum class TvCardStyle {
    POSTER,
    WIDESCREEN,
    SQUARE,
}

private data class SpotlightEnrichment(
    val overview: String? = null,
    val castLine: String? = null,
    val logoUrl: String? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvSpotlightHome(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    continueItems: List<ProgressRow>,
    catalog: VideoCatalog,
    watchlist: List<WatchlistItem>,
    studioCollections: List<StudioCollection>,
    discoverPlaylists: List<DiscoverPlaylistSummary>,
    albums: List<LibAlbum>,
    podcasts: List<PodcastSub>,
    books: List<OwnedBook>,
    homeLayout: HomeLayout,
    liveGuideHighlights: List<HomeLiveHighlight>,
    logos: Map<String, String?>,
    onNavigate: (PlayerDestination) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    val moviesById = remember(catalog.movies) { catalog.movies.associateBy { it.id } }
    val showsById = remember(catalog.shows) { catalog.shows.associateBy { it.id } }

    fun formatRuntime(seconds: Int?): String? {
        if (seconds == null || seconds <= 60) return null
        val mins = seconds / 60
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun movieCard(keyPrefix: String, m: CatalogMovie, progress: Float? = null): TvSpotlightCard {
        val chips = buildList {
            m.year?.let { add(it.toString()) }
            formatRuntime(m.runtimeSeconds)?.let { add(it) }
            if (m.genres.isNotEmpty()) add(m.genres.take(2).joinToString(", "))
        }
        return TvSpotlightCard(
            key = "$keyPrefix-movie-${m.id}",
            title = m.title,
            subtitle = m.studio?.takeIf { it.isNotBlank() },
            metaChips = chips,
            ratingText = m.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
            contentRating = m.contentRating?.takeIf { it.isNotBlank() },
            posterUrl = api.artwork.moviePoster(m.id, width = 500),
            backdropUrl = if (m.hasBackdrop) api.artwork.movieBackdrop(m.id, width = 1280) else api.artwork.moviePoster(m.id, width = 780),
            fallbackIcon = Icons.Default.Movie,
            progress = progress,
            detailKind = "movie",
            detailId = m.id,
            tmdbId = m.tmdbId,
            onClick = { onNavigate(PlayerDestination.MovieDetail(m.id)) },
        )
    }

    fun showCard(keyPrefix: String, s: CatalogShow, subtitleOverride: String? = null, progress: Float? = null, badge: String? = null): TvSpotlightCard {
        val chips = buildList {
            s.year?.let { add(it.toString()) }
            if (s.episodeCount > 0) add("${s.episodeCount} eps")
            if (s.genres.isNotEmpty()) add(s.genres.take(2).joinToString(", "))
        }
        return TvSpotlightCard(
            key = "$keyPrefix-show-${s.id}",
            title = s.title,
            subtitle = subtitleOverride ?: s.network?.takeIf { it.isNotBlank() },
            metaChips = chips,
            ratingText = s.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
            contentRating = s.contentRating?.takeIf { it.isNotBlank() },
            posterUrl = api.artwork.showPoster(s.id, width = 500),
            backdropUrl = if (s.hasBackdrop) api.artwork.showBackdrop(s.id, width = 1280) else api.artwork.showPoster(s.id, width = 780),
            fallbackIcon = Icons.Default.Tv,
            progress = progress,
            badge = badge,
            detailKind = "show",
            detailId = s.id,
            tmdbId = s.tmdbId,
            onClick = { onNavigate(PlayerDestination.ShowDetail(s.id)) },
        )
    }

    val shelves = remember(
        continueItems, catalog, watchlist, studioCollections, discoverPlaylists,
        albums, podcasts, books, homeLayout, liveGuideHighlights,
    ) {
        val out = mutableListOf<TvSpotlightShelf>()
        val enabledRows = homeLayout.rows.filter { it.on }

        enabledRows.forEach { row ->
            when {
                row.id == "continue" && continueItems.isNotEmpty() -> {
                    val cards = continueItems.take(18).mapNotNull { item ->
                        val frac = item.progressFraction.takeIf { it > 0.01f }
                        when (item.itemType) {
                            "movie" -> {
                                val id = item.itemId.toIntOrNull()
                                val m = id?.let { moviesById[it] }
                                if (m != null) {
                                    movieCard("cw", m, progress = frac)
                                } else {
                                    TvSpotlightCard(
                                        key = "cw-m-${item.itemId}",
                                        title = item.meta?.title ?: "Movie",
                                        subtitle = item.meta?.subtitle,
                                        posterUrl = api.artwork.forItem("movie", item.itemId, preferBackdrop = false),
                                        backdropUrl = api.artwork.forItem("movie", item.itemId, preferBackdrop = true),
                                        progress = frac,
                                        onClick = { id?.let { onNavigate(PlayerDestination.MovieDetail(it)) } },
                                    )
                                }
                            }
                            "episode" -> {
                                val epId = item.itemId.toIntOrNull()
                                val showId = item.meta?.parentId?.toIntOrNull()
                                val show = showId?.let { showsById[it] }
                                val epLine = listOfNotNull(
                                    item.meta?.subtitle?.takeIf { it.isNotBlank() },
                                    item.meta?.title?.takeIf { it.isNotBlank() && it != show?.title },
                                ).joinToString(" · ")
                                if (show != null) {
                                    showCard(
                                        keyPrefix = "cw-ep-${item.itemId}",
                                        s = show,
                                        subtitleOverride = epLine.ifBlank { item.meta?.subtitle },
                                        progress = frac,
                                    ).copy(
                                        onClick = {
                                            if (showId != null && epId != null) {
                                                onNavigate(PlayerDestination.EpisodeDetail(showId, epId))
                                            } else {
                                                onNavigate(PlayerDestination.ShowDetail(show.id))
                                            }
                                        },
                                    )
                                } else {
                                    TvSpotlightCard(
                                        key = "cw-ep-${item.itemId}",
                                        title = item.meta?.parentTitle ?: item.meta?.title ?: "Episode",
                                        subtitle = epLine.ifBlank { item.meta?.subtitle },
                                        posterUrl = api.artwork.resolve(item.meta?.artwork) ?: api.artwork.forItem("episode", item.itemId, preferBackdrop = false),
                                        backdropUrl = api.artwork.resolve(item.meta?.backdrop) ?: api.artwork.forItem("episode", item.itemId, preferBackdrop = true),
                                        fallbackIcon = Icons.Default.Tv,
                                        progress = frac,
                                        onClick = {
                                            if (showId != null && epId != null) {
                                                onNavigate(PlayerDestination.EpisodeDetail(showId, epId))
                                            } else if (showId != null) {
                                                onNavigate(PlayerDestination.ShowDetail(showId))
                                            }
                                        },
                                    )
                                }
                            }
                            "youtube" -> {
                                TvSpotlightCard(
                                    key = "cw-yt-${item.itemId}",
                                    title = item.meta?.title ?: "YouTube Video",
                                    subtitle = item.meta?.subtitle ?: "YouTube",
                                    posterUrl = api.artwork.forItem("youtube", item.itemId, preferBackdrop = true),
                                    backdropUrl = api.artwork.forItem("youtube", item.itemId, preferBackdrop = true),
                                    fallbackIcon = Icons.Default.SmartDisplay,
                                    progress = frac,
                                    onClick = {
                                        coordinator.playVideo(
                                            ActivePlayable(
                                                itemType = "youtube",
                                                itemId = item.itemId,
                                                title = item.meta?.title ?: "YouTube",
                                                subtitle = item.meta?.subtitle.orEmpty(),
                                                artworkUrl = api.artwork.forItem("youtube", item.itemId, preferBackdrop = true),
                                                streamUrl = api.streamUrl("youtube", item.itemId),
                                                isVideo = true,
                                            ),
                                        )
                                    },
                                )
                            }
                            else -> null
                        }
                    }
                    if (cards.isNotEmpty()) {
                        out += TvSpotlightShelf("continue", "Continue Watching", cardStyle = TvCardStyle.POSTER, items = cards)
                    }
                }

                row.id == "movies-recent" && catalog.movies.isNotEmpty() -> {
                    val cards = catalog.movies
                        .sortedByDescending { it.addedAt.orEmpty() }
                        .take(20)
                        .map { movieCard("recent-m", it) }
                    out += TvSpotlightShelf("movies-recent", "Recently Added in Movies", cardStyle = TvCardStyle.POSTER, items = cards)
                }

                row.id == "shows-recent" && catalog.shows.isNotEmpty() -> {
                    val cards = catalog.shows
                        .sortedByDescending { it.addedAt.orEmpty() }
                        .take(20)
                        .map { showCard("recent-s", it) }
                    out += TvSpotlightShelf("shows-recent", "Recently Added in TV Shows", cardStyle = TvCardStyle.POSTER, items = cards)
                }

                row.id == "episodes-recent" && catalog.recentEpisodes.isNotEmpty() -> {
                    val cards = catalog.recentEpisodes.take(20).map { ep ->
                        val show = showsById[ep.showId]
                        val epTag = "S${ep.seasonNumber}·E${ep.episodeNumber}"
                        val sub = listOfNotNull("S${ep.seasonNumber} • E${ep.episodeNumber}", ep.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
                        TvSpotlightCard(
                            key = "ep-${ep.id}",
                            title = ep.showTitle.ifBlank { show?.title ?: "Episode" },
                            subtitle = sub,
                            metaChips = listOfNotNull(formatRuntime(ep.runtimeSeconds), show?.network),
                            ratingText = show?.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
                            contentRating = show?.contentRating,
                            posterUrl = api.artwork.showPoster(ep.showId),
                            backdropUrl = if (ep.hasStill) api.artwork.episodeStill(ep.id) else api.artwork.showBackdrop(ep.showId),
                            fallbackIcon = Icons.Default.Tv,
                            badge = epTag,
                            detailKind = if (show != null) "show" else null,
                            detailId = show?.id,
                            tmdbId = show?.tmdbId,
                            onClick = { onNavigate(PlayerDestination.EpisodeDetail(ep.showId, ep.id)) },
                        )
                    }
                    out += TvSpotlightShelf("episodes-recent", "New Episodes", cardStyle = TvCardStyle.POSTER, items = cards)
                }

                row.id == "live-guide" && liveGuideHighlights.isNotEmpty() -> {
                    val cards = liveGuideHighlights.take(16).map { ch ->
                        TvSpotlightCard(
                            key = "live-${ch.channelId}",
                            title = ch.programTitle,
                            subtitle = ch.programSubtitle,
                            metaChips = listOf("LIVE NOW"),
                            posterUrl = ch.artworkUrl,
                            backdropUrl = ch.artworkUrl,
                            fallbackIcon = Icons.Default.LiveTv,
                            progress = ch.progressFraction,
                            isLive = true,
                            onClick = { ch.tune(coordinator) },
                        )
                    }
                    out += TvSpotlightShelf("live-guide", "On Live TV Right Now", cardStyle = TvCardStyle.WIDESCREEN, items = cards)
                }

                row.id == "collections" && studioCollections.isNotEmpty() -> {
                    val cards = studioCollections.mapNotNull { c ->
                        val members = c.members.filter { id ->
                            if (c.mediaType == "movie") moviesById.containsKey(id) else showsById.containsKey(id)
                        }
                        if (members.isEmpty()) return@mapNotNull null
                        val safePoster = c.poster?.takeIf { !it.startsWith("/api/video/collections/") }
                        val art = api.artwork.resolve(safePoster ?: c.memberPosters.firstOrNull())
                            ?: api.artwork.forItem(
                                if (c.mediaType == "movie") "movie" else "show",
                                members.first().toString(),
                                preferBackdrop = false,
                            )
                        val backdrop = api.artwork.forItem(
                            if (c.mediaType == "movie") "movie" else "show",
                            members.first().toString(),
                            preferBackdrop = true,
                        ) ?: art
                        TvSpotlightCard(
                            key = "col-${c.id}",
                            title = c.name,
                            subtitle = "${members.size} ${if (members.size == 1) "title" else "titles"} · SoulSync Collection",
                            overview = c.summary,
                            posterUrl = art,
                            backdropUrl = backdrop,
                            fallbackIcon = Icons.Default.Movie,
                            badge = if (c.pinned) "★" else null,
                            onClick = { onNavigate(PlayerDestination.CollectionDetail(c)) },
                        )
                    }
                    if (cards.isNotEmpty()) {
                        out += TvSpotlightShelf("collections", "Collections", cardStyle = TvCardStyle.POSTER, items = cards)
                    }
                }

                row.id == "watchlist" && watchlist.isNotEmpty() -> {
                    val cards = watchlist.take(20).mapNotNull { w ->
                        val id = w.itemId.toIntOrNull()
                        if (w.itemType == "movie" && id != null && moviesById[id] != null) {
                            movieCard("wl", moviesById[id]!!)
                        } else if (w.itemType == "show" && id != null && showsById[id] != null) {
                            showCard("wl", showsById[id]!!)
                        } else {
                            TvSpotlightCard(
                                key = "wl-${w.itemType}-${w.itemId}",
                                title = w.meta?.title ?: "Saved Title",
                                subtitle = w.itemType.replaceFirstChar { it.uppercase() },
                                posterUrl = api.artwork.forItem(w.itemType, w.itemId, preferBackdrop = false),
                                backdropUrl = api.artwork.forItem(w.itemType, w.itemId, preferBackdrop = true),
                                onClick = {
                                    if (id != null) {
                                        if (w.itemType == "movie") onNavigate(PlayerDestination.MovieDetail(id))
                                        else onNavigate(PlayerDestination.ShowDetail(id))
                                    }
                                },
                            )
                        }
                    }
                    if (cards.isNotEmpty()) {
                        out += TvSpotlightShelf("watchlist", "Your Watchlist", cardStyle = TvCardStyle.POSTER, items = cards)
                    }
                }

                row.id == "youtube-recent" && catalog.youtube.isNotEmpty() -> {
                    val cards = catalog.youtube.take(18).map { yt ->
                        val thumb = api.artwork.resolve(yt.thumbnailUrl) ?: api.artwork.forItem("youtube", yt.id, preferBackdrop = true)
                        TvSpotlightCard(
                            key = "yt-${yt.id}",
                            title = yt.title,
                            subtitle = yt.channelTitle ?: "YouTube",
                            metaChips = listOfNotNull(formatRuntime(yt.durationSeconds), "YouTube"),
                            posterUrl = thumb,
                            backdropUrl = thumb,
                            fallbackIcon = Icons.Default.SmartDisplay,
                            onClick = {
                                coordinator.playVideo(
                                    ActivePlayable(
                                        itemType = "youtube",
                                        itemId = yt.id,
                                        title = yt.title,
                                        subtitle = yt.channelTitle.orEmpty(),
                                        artworkUrl = thumb,
                                        streamUrl = api.streamUrl("youtube", yt.id),
                                        isVideo = true,
                                    ),
                                )
                            },
                        )
                    }
                    out += TvSpotlightShelf("youtube-recent", "YouTube", cardStyle = TvCardStyle.WIDESCREEN, items = cards)
                }

                row.id == "music-recent" && albums.isNotEmpty() -> {
                    val cards = albums.take(18).map { alb ->
                        val art = api.artwork.resolve(alb.imageUrl) ?: api.artwork.albumArtwork(alb.id)
                        TvSpotlightCard(
                            key = "alb-${alb.id}",
                            title = alb.title,
                            subtitle = alb.artistName,
                            metaChips = listOfNotNull(alb.year?.toString(), "${alb.trackCount} tracks"),
                            posterUrl = art,
                            backdropUrl = art,
                            fallbackIcon = Icons.Default.MusicNote,
                            onClick = { onNavigate(PlayerDestination.AlbumDetail(alb.id)) },
                        )
                    }
                    out += TvSpotlightShelf("music-recent", "Recently Added Music", cardStyle = TvCardStyle.SQUARE, items = cards)
                }

                row.id == "discovery-playlists" && discoverPlaylists.isNotEmpty() -> {
                    val cards = discoverPlaylists.take(16).map { pl ->
                        val art = api.artwork.resolve(pl.coverUrl) ?: pl.coverTiles.firstOrNull()?.let { api.artwork.resolve(it) }
                        TvSpotlightCard(
                            key = "disc-${pl.key}",
                            title = pl.title,
                            subtitle = pl.subtitle ?: "${pl.trackCount} tracks · SoulSync Discovery",
                            metaChips = listOfNotNull(pl.sectionLabel.takeIf { it.isNotBlank() }, "${pl.trackCount} tracks"),
                            posterUrl = art,
                            backdropUrl = art,
                            fallbackIcon = Icons.Default.MusicNote,
                            onClick = { onNavigate(PlayerDestination.Music) },
                        )
                    }
                    out += TvSpotlightShelf("discovery-playlists", "SoulSync Discovery Playlists", cardStyle = TvCardStyle.SQUARE, items = cards)
                }

                row.id == "podcasts" && podcasts.isNotEmpty() -> {
                    val cards = podcasts.take(16).map { pod ->
                        val art = api.artwork.resolve(pod.artworkUrl)
                        TvSpotlightCard(
                            key = "pod-${pod.id}",
                            title = pod.title,
                            subtitle = pod.author,
                            overview = pod.description,
                            posterUrl = art,
                            backdropUrl = art,
                            fallbackIcon = Icons.Default.Podcasts,
                            onClick = { onNavigate(PlayerDestination.PodcastDetail(pod)) },
                        )
                    }
                    out += TvSpotlightShelf("podcasts", "Podcasts", cardStyle = TvCardStyle.SQUARE, items = cards)
                }

                row.id == "audiobooks" && books.isNotEmpty() -> {
                    val cards = books.take(16).map { book ->
                        val art = api.artwork.resolve(book.coverUrl) ?: api.artwork.audiobookCover(book.asin)
                        TvSpotlightCard(
                            key = "book-${book.asin}",
                            title = book.title,
                            subtitle = book.author,
                            metaChips = listOfNotNull(book.seriesTitle, book.runtimeMinutes?.let { "${it / 60}h ${it % 60}m" }),
                            posterUrl = art,
                            backdropUrl = art,
                            fallbackIcon = Icons.Default.Headphones,
                            onClick = { onNavigate(PlayerDestination.AudiobookDetail(book)) },
                        )
                    }
                    out += TvSpotlightShelf("audiobooks", "Audiobooks", cardStyle = TvCardStyle.SQUARE, items = cards)
                }

                // Raw genre spam rows omitted on Cinema Home — replaced below by personalized
                // "Because you watched…" library rows!
                row.id.startsWith("genre:") -> Unit
            }
        }

        // Personalized "Because you watched [Title]" library-only shelves (up to 2 seeds from Continue Watching)
        val becauseSeeds = mutableListOf<String>()
        continueItems.forEach { item ->
            if (becauseSeeds.size >= 2) return@forEach
            when (item.itemType) {
                "movie" -> {
                    val m = item.itemId.toIntOrNull()?.let { moviesById[it] } ?: return@forEach
                    if (m.genres.isEmpty() || "m-${m.id}" in becauseSeeds) return@forEach
                    val seedGenres = m.genres.map { it.lowercase() }.toSet()
                    val matches = catalog.movies
                        .filter { it.id != m.id && it.genres.any { g -> g.lowercase() in seedGenres } }
                        .sortedByDescending { cand ->
                            val overlap = cand.genres.count { it.lowercase() in seedGenres } * 2.0
                            val sameStudio = if (!m.studio.isNullOrBlank() && cand.studio == m.studio) 1.5 else 0.0
                            overlap + sameStudio + (cand.rating ?: 0.0) * 0.25
                        }
                        .take(18)
                    if (matches.size >= 4) {
                        becauseSeeds += "m-${m.id}"
                        out += TvSpotlightShelf(
                            id = "because-m-${m.id}",
                            title = "Because you watched ${m.title}",
                            cardStyle = TvCardStyle.POSTER,
                            items = matches.map { movieCard("byw-${m.id}", it) },
                        )
                    }
                }
                "episode" -> {
                    val sid = item.meta?.parentId?.toIntOrNull() ?: return@forEach
                    val s = showsById[sid] ?: return@forEach
                    if (s.genres.isEmpty() || "s-${s.id}" in becauseSeeds) return@forEach
                    val seedGenres = s.genres.map { it.lowercase() }.toSet()
                    val matches = catalog.shows
                        .filter { it.id != s.id && it.genres.any { g -> g.lowercase() in seedGenres } }
                        .sortedByDescending { cand ->
                            val overlap = cand.genres.count { it.lowercase() in seedGenres } * 2.0
                            val sameNet = if (!s.network.isNullOrBlank() && cand.network == s.network) 1.5 else 0.0
                            overlap + sameNet + (cand.rating ?: 0.0) * 0.25
                        }
                        .take(18)
                    if (matches.size >= 4) {
                        becauseSeeds += "s-${s.id}"
                        out += TvSpotlightShelf(
                            id = "because-s-${s.id}",
                            title = "Because you watched ${s.title}",
                            cardStyle = TvCardStyle.POSTER,
                            items = matches.map { showCard("byw-${s.id}", it) },
                        )
                    }
                }
            }
        }
        out
    }

    val defaultCard = shelves.firstOrNull()?.items?.firstOrNull()
    var focusedCard by remember { mutableStateOf<TvSpotlightCard?>(null) }
    val activeCard = focusedCard ?: defaultCard

    // Lazy cached overview + cast + logo enrichment + upfront batch TMDB logo prefetch
    val enrichmentCache = remember { mutableStateMapOf<String, SpotlightEnrichment>() }
    val prefetchedLogos = remember { mutableStateMapOf<String, String>() }
    val colorCache = remember { mutableStateMapOf<String, Color>() }
    var targetAmbient by remember { mutableStateOf(Color(0xFF161326)) }

    // Batch-prefetch official TMDB wordmark logos for all visible Movie & TV cards on Home
    // so logos appear instantly as you glide across posters (just like Plex TV!).
    LaunchedEffect(shelves, api) {
        val allKeys = shelves
            .flatMap { it.items }
            .mapNotNull { c ->
                val kind = c.detailKind
                val tid = c.tmdbId
                if (kind != null && tid != null) "$kind:$tid" else null
            }
            .distinct()
            .filter { !prefetchedLogos.containsKey(it) && logos[it].isNullOrBlank() }
        allKeys.chunked(30).forEach { batch ->
            (api.fetchLogos(batch) as? MusicApiResult.Success)?.data?.forEach { (k, raw) ->
                api.artwork.resolve(raw)?.let { resolved -> prefetchedLogos[k] = resolved }
            }
        }
    }

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
        // Debounced detail enrichment for overview + cast + TMDB logo
        val kind = card.detailKind
        val id = card.detailId
        if (kind != null && id != null) {
            val cacheKey = "$kind:$id"
            if (!enrichmentCache.containsKey(cacheKey)) {
                delay(220L)
                var overview: String? = null
                var castLine: String? = null
                var logoUrl: String? = card.tmdbId?.let { logos["$kind:$it"] }?.let { api.artwork.resolve(it) }
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
                    val logoKey = "$kind:${card.tmdbId}"
                    (api.fetchLogos(listOf(logoKey)) as? MusicApiResult.Success)?.data?.get(logoKey)?.let { raw ->
                        logoUrl = api.artwork.resolve(raw)
                    }
                }
                enrichmentCache[cacheKey] = SpotlightEnrichment(overview = overview, castLine = castLine, logoUrl = logoUrl)
            }
        }
    }

    val animatedAmbient by animateColorAsState(
        targetValue = targetAmbient,
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "tvSpotlightAmbient",
    )

    // Initial focus straight onto the very first poster card
    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(shelves.isNotEmpty()) {
        if (shelves.isNotEmpty()) {
            delay(90L)
            runCatching { firstCardFocus.requestFocus() }
        }
    }

    // Live clock text (updates every 15s)
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            clockText = fmt.format(Date())
            delay(15_000L)
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val shelfBringIntoView = remember(density) {
        val headerClearancePx = with(density) { 42.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
                if (containerSize <= 0f) return 0f
                return if (kotlin.math.abs(offset - headerClearancePx) < 8f) 0f else offset - headerClearancePx
            }
        }
    }
    val rowBringIntoView = remember(density) {
        val pillClearancePx = with(density) { 78.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float {
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
        // 1. Full-bleed feathered HD backdrop anchored top-right
        val activeBackdrop = activeCard?.backdropUrl ?: activeCard?.posterUrl
        AnimatedContent(
            targetState = activeBackdrop,
            transitionSpec = {
                fadeIn(tween(380, easing = FastOutSlowInEasing))
                    .togetherWith(fadeOut(tween(260)))
            },
            label = "tvSpotlightBackdrop",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.76f)
                .fillMaxHeight(0.68f),
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

        // Seamless cinema feathering from left & bottom into the living ambient canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to animatedAmbient,
                        0.32f to animatedAmbient.copy(alpha = 0.96f),
                        0.54f to animatedAmbient.copy(alpha = 0.52f),
                        0.76f to Color.Transparent,
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
                        0.56f to animatedAmbient.copy(alpha = 0.84f),
                        0.70f to animatedAmbient,
                        1.0f to Color.Black.copy(alpha = 0.24f),
                    ),
                ),
        )

        // 2. Main vertical layout: Pinned Top Spotlight Stage + Scrollable Poster Shelves
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinned top editorial metadata stage (46% height)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.45f)
                    .padding(start = 76.dp, end = 28.dp, top = 28.dp, bottom = 6.dp),
            ) {
                if (activeCard != null) {
                    val enrichKey = if (activeCard.detailKind != null && activeCard.detailId != null) {
                        "${activeCard.detailKind}:${activeCard.detailId}"
                    } else null
                    val enriched = enrichKey?.let { enrichmentCache[it] }
                    val preloadedLogo = if (activeCard.detailKind != null && activeCard.tmdbId != null) {
                        val lKey = "${activeCard.detailKind}:${activeCard.tmdbId}"
                        prefetchedLogos[lKey] ?: logos[lKey]?.let { api.artwork.resolve(it) }
                    } else null
                    val logoUrl = enriched?.logoUrl ?: preloadedLogo
                    val overview = enriched?.overview ?: activeCard.overview
                    val castLine = enriched?.castLine ?: activeCard.castLine

                    AnimatedContent(
                        targetState = activeCard.key,
                        transitionSpec = {
                            fadeIn(tween(220)).togetherWith(fadeOut(tween(140)))
                        },
                        label = "tvSpotlightMeta",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.56f),
                    ) { _ ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                        .fillMaxWidth(0.86f)
                                        .height(66.dp),
                                    error = {
                                        Text(
                                            text = activeCard.title,
                                            color = Color.White,
                                            fontSize = 32.sp,
                                            lineHeight = 36.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.6).sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            } else {
                                Text(
                                    text = activeCard.title,
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    lineHeight = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.6).sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (!activeCard.subtitle.isNullOrBlank()) {
                                Text(
                                    text = activeCard.subtitle,
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // Meta line: Year · Runtime · Genres · ★ Rating · Content Rating Pill
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
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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
                                                color = Color.White.copy(alpha = 0.88f),
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

            // Bottom 55%: Pure theatrical poster shelves
            val shelfListState = rememberLazyListState()
            CompositionLocalProvider(LocalBringIntoViewSpec provides shelfBringIntoView) {
                LazyColumn(
                    state = shelfListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.55f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(shelves, key = { _, s -> s.id }) { shelfIdx, shelf ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(start = 76.dp, end = 24.dp),
                            ) {
                                Text(
                                    text = shelf.title,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.2).sp,
                                )
                                if (!shelf.subtitle.isNullOrBlank()) {
                                    Text(
                                        text = "· ${shelf.subtitle}",
                                        color = Color.White.copy(alpha = 0.52f),
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
                                    itemsIndexed(shelf.items, key = { _, c -> c.key }) { cardIdx, card ->
                                        TvCinemaPosterTile(
                                            card = card,
                                            style = shelf.cardStyle,
                                            modifier = if (shelfIdx == 0 && cardIdx == 0) {
                                                Modifier.focusRequester(firstCardFocus)
                                            } else {
                                                Modifier
                                            },
                                            onFocused = { focusedCard = card },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Top-Right Floating HUD: Live Clock Pill (and Mini Music Player when playing)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 24.dp),
        ) {
            val activeAudio = coordinator.activeItem
            if (activeAudio != null && !activeAudio.isVideo) {
                TvTopRightMiniPlayer(
                    coordinator = coordinator,
                    item = activeAudio,
                )
            }
            if (clockText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
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
private fun TvCinemaPosterTile(
    card: TvSpotlightCard,
    style: TvCardStyle,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(durationMillis = 190, easing = FastOutSlowInEasing),
        label = "tvTileScale",
    )
    val tileWidth: Dp
    val tileHeight: Dp
    val cornerRadius: Dp
    when (style) {
        TvCardStyle.POSTER -> {
            tileWidth = 132.dp
            tileHeight = 196.dp
            cornerRadius = 11.dp
        }
        TvCardStyle.WIDESCREEN -> {
            tileWidth = 224.dp
            tileHeight = 126.dp
            cornerRadius = 11.dp
        }
        TvCardStyle.SQUARE -> {
            tileWidth = 148.dp
            tileHeight = 148.dp
            cornerRadius = 11.dp
        }
    }
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .width(tileWidth)
            .height(tileHeight)
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
            fallbackIcon = card.fallbackIcon,
            maxPx = 600,
            modifier = Modifier.fillMaxSize(),
        )

        // Subtle dark bottom gradient if showing progress bar or Live badge
        if (card.progress != null || card.isLive) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))),
                    ),
            )
        }

        // Top-left badge (e.g. S4·E6 or ★ pinned)
        if (!card.badge.isNullOrBlank()) {
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

        // Live emerald pill for Live TV cards
        if (card.isLive) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xCC065F46))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34D399)),
                )
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        // Sleek Plex-style bottom progress bar on Continue Watching posters
        if (card.progress != null && card.progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(card.progress.coerceIn(0.04f, 1f))
                        .clip(CircleShape)
                        .background(SoulSyncColors.AccentLight),
                )
            }
        }
    }
}

/**
 * Extracts a rich, cinema-tuned deep ambient hue from artwork so the full-screen canvas
 * shifts smoothly like Plex TV while guaranteeing high-contrast legibility for white text.
 */
internal fun extractCinemaAmbientColor(bitmap: Bitmap): Color {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return Color(0xFF161326)
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0L
    val stepX = (w / 10).coerceAtLeast(1)
    val stepY = (h / 10).coerceAtLeast(1)
    val hsv = FloatArray(3)
    var bestSat = 0f
    var bestColor = 0
    for (y in 0 until h step stepY) {
        for (x in 0 until w step stepX) {
            val px = bitmap.getPixel(x, y)
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            val sat = hsv[1]
            val v = hsv[2]
            if (v > 0.16f && v < 0.92f) {
                val weight = (1 + (sat * 3f).toInt()).toLong()
                rSum += r * weight
                gSum += g * weight
                bSum += b * weight
                count += weight
                if (sat > bestSat && v > 0.24f) {
                    bestSat = sat
                    bestColor = px
                }
            }
        }
    }
    if (count == 0L) return Color(0xFF161326)
    val avgR = (rSum / count).toInt()
    val avgG = (gSum / count).toInt()
    val avgB = (bSum / count).toInt()
    // Blend 65% average + 35% most vibrant accent pixel for rich cinematic warmth
    val mixR = if (bestColor != 0) ((avgR * 0.65f) + (((bestColor shr 16) and 0xFF) * 0.35f)).toInt() else avgR
    val mixG = if (bestColor != 0) ((avgG * 0.65f) + (((bestColor shr 8) and 0xFF) * 0.35f)).toInt() else avgG
    val mixB = if (bestColor != 0) ((avgB * 0.65f) + ((bestColor and 0xFF) * 0.35f)).toInt() else avgB
    android.graphics.Color.RGBToHSV(mixR, mixG, mixB, hsv)
    // Clamp into rich, moody cinema luminance (deep plum, warm mahogany, midnight teal)
    hsv[1] = hsv[1].coerceIn(0.28f, 0.58f)
    hsv[2] = hsv[2].coerceIn(0.16f, 0.26f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

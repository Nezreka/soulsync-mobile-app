package net.ssync.app.player

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
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
import net.ssync.player.CastMember
import net.ssync.player.CrewMember
import net.ssync.player.DetailEpisode
import net.ssync.player.ExtraTitle
import net.ssync.player.MovieDetail
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import net.ssync.player.ShowDetail
import net.ssync.player.TitleExtras
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtDetailRuntime(mins: Int?): String? {
    if (mins == null || mins <= 0) return null
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TvCinemaMovieDetail(
    movie: MovieDetail,
    extras: TitleExtras?,
    discoveryRails: List<DiscoveryRail> = emptyList(),
    inWatchlist: Boolean,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onStartWatchParty: () -> Unit,
    onReportIssue: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit,
    onRequestUnowned: (ExtraTitle) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    val density = LocalDensity.current

    val posterUrl = api.artwork.moviePoster(movie.id, width = 600)
    val backdropUrl = if (movie.hasBackdrop) api.artwork.movieBackdrop(movie.id, width = 1920) else posterUrl

    // the logo the library already has for this film comes with its page; the lookup below is only
    // for a title that has none stored
    var logoUrl by remember(movie.id) { mutableStateOf(api.artwork.resolve(movie.logo)) }
    var targetAmbient by remember(movie.id) { mutableStateOf(Color(0xFF151326)) }

    LaunchedEffect(movie.id, backdropUrl) {
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .size(48)
                    .allowHardware(false)
                    .build()
                val bmp = (imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                bmp?.let { extractCinemaAmbientColor(it) }
            }.getOrNull()
        }
        if (extracted != null) targetAmbient = extracted

        // Check app-wide logo cache first (0ms), fallback to API
        val tmdbId = HomeWarmCache.catalog.movies.firstOrNull { it.id == movie.id }?.tmdbId
        if (tmdbId != null) {
            val lk = "movie:$tmdbId"
            val cached = HomeWarmCache.resolvedLogos[lk]
            if (!logoUrl.isNullOrBlank()) {
                // the page's own logo stands
            } else if (cached != null) {
                logoUrl = cached
            } else {
                (api.fetchLogos(listOf(lk)) as? MusicApiResult.Success)?.data?.get(lk)?.let { raw ->
                    api.artwork.resolve(raw)?.let { resolved ->
                        HomeWarmCache.resolvedLogos[lk] = resolved
                        logoUrl = resolved
                    }
                }
            }
        }
    }

    val animatedAmbient by animateColorAsState(
        targetValue = targetAmbient,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "tvMovieDetailAmbient",
    )

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(movie.id) {
        delay(80L)
        runCatching { playFocus.requestFocus() }
    }

    val directors = remember(movie.crew) { movie.crew.filter { it.job.equals("Director", ignoreCase = true) } }
    val writers = remember(movie.crew) {
        movie.crew.filter {
            it.job.equals("Writer", ignoreCase = true) ||
                it.job.equals("Screenplay", ignoreCase = true) ||
                it.job.equals("Story", ignoreCase = true)
        }.distinctBy { it.name }.take(3)
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

    val verticalBringIntoView = remember(density) {
        val topPad = with(density) { 24.dp.toPx() }
        val bottomPad = with(density) { 32.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                return when {
                    offset < topPad -> offset - topPad
                    offset + size > containerSize - bottomPad -> (offset + size) - (containerSize - bottomPad)
                    else -> 0f
                }
            }
        }
    }

    val similarList = remember(extras) {
        val recs = extras?.recommendations.orEmpty()
        val sims = extras?.similar.orEmpty()
        (recs + sims).distinctBy { it.tmdbId }.take(18)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedAmbient),
    ) {
        // Feathered full-bleed backdrop anchored top-right
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(backdropUrl)
                .size(1920, 1080)
                .precision(Precision.INEXACT)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.76f),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to animatedAmbient,
                        0.38f to animatedAmbient.copy(alpha = 0.94f),
                        0.60f to animatedAmbient.copy(alpha = 0.45f),
                        0.82f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.45f to Color.Transparent,
                        0.68f to animatedAmbient.copy(alpha = 0.92f),
                        0.80f to animatedAmbient,
                        1.0f to Color.Black.copy(alpha = 0.28f),
                    ),
                ),
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalBringIntoView) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 28.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            // 1. Theatrical Hero Stage
            item(key = "hero") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 78.dp, end = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!logoUrl.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(logoUrl)
                                    .size(960)
                                    .precision(Precision.INEXACT)
                                    .crossfade(true)
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = movie.title,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .height(74.dp),
                                error = {
                                    Text(
                                        text = movie.title,
                                        color = Color.White,
                                        fontSize = 36.sp,
                                        lineHeight = 40.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.7).sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        } else {
                            Text(
                                text = movie.title,
                                color = Color.White,
                                fontSize = 36.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.7).sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (!movie.tagline.isNullOrBlank()) {
                            Text(
                                text = "“${movie.tagline}”",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Rich metadata strip
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val meta = listOfNotNull(
                                movie.year?.toString(),
                                fmtDetailRuntime(movie.runtimeMinutes),
                                movie.studio?.takeIf { it.isNotBlank() },
                            ).joinToString("  ·  ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (movie.rating != null && movie.rating!! > 0.0) {
                                Text(
                                    text = "★ ${"%.1f".format(movie.rating)}",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                            if (!movie.contentRating.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        text = movie.contentRating!!,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (movie.watched == true) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF22C55E).copy(alpha = 0.22f))
                                        .border(1.dp, Color(0xFF22C55E).copy(alpha = 0.55f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4ADE80), modifier = Modifier.size(12.dp))
                                    Text("Watched", color = Color(0xFF86EFAC), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Clickable Genre pills -> instant search
                        if (movie.genres.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                movie.genres.take(5).forEach { g ->
                                    TvClickableTagChip(
                                        label = g,
                                        onClick = { onNavigate(PlayerDestination.SearchQuery(g)) },
                                    )
                                }
                            }
                        }

                        if (!movie.overview.isNullOrBlank()) {
                            Text(
                                text = movie.overview!!,
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        // Key credits line
                        val creditParts = buildList {
                            if (directors.isNotEmpty()) add("Directed by ${directors.joinToString(", ") { it.name }}")
                            if (writers.isNotEmpty()) add("Written by ${writers.joinToString(", ") { it.name }}")
                        }
                        if (creditParts.isNotEmpty()) {
                            Text(
                                text = creditParts.joinToString("   ·   "),
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Native Color-Shift Action Bar (Zero Double Borders!)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TvDetailActionButton(
                                label = "Play Film",
                                icon = Icons.Default.PlayArrow,
                                primary = true,
                                modifier = Modifier.focusRequester(playFocus),
                                onClick = onPlay,
                            )
                            TvDetailActionButton(
                                label = if (inWatchlist) "In Watchlist" else "Watchlist",
                                icon = if (inWatchlist) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                                active = inWatchlist,
                                onClick = onToggleWatchlist,
                            )
                            TvDetailActionButton(
                                label = "Watch Party",
                                icon = Icons.Default.Group,
                                onClick = onStartWatchParty,
                            )
                            TvDetailIconAction(
                                icon = Icons.Default.Flag,
                                contentDescription = "Report issue",
                                onClick = onReportIssue,
                            )
                        }
                    }

                    // Framed Key Art Poster Showcase on the right (clears top-right clock cleanly)
                    Box(
                        modifier = Modifier
                            .padding(top = 36.dp)
                            .width(156.dp)
                            .height(234.dp)
                            .shadow(22.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                    ) {
                        SoulSyncArtwork(
                            url = posterUrl,
                            contentDescription = movie.title,
                            fallbackIcon = Icons.Default.Movie,
                            maxPx = 600,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 2. Cast & Crew Showcase
            if (movie.cast.isNotEmpty()) {
                item(key = "cast") {
                    TvDetailCastShelf(
                        title = "Cast & Characters",
                        cast = movie.cast.take(18),
                        api = api,
                        rowBringIntoView = rowBringIntoView,
                        onPersonClick = { p ->
                            p.tmdbId?.let { onNavigate(PlayerDestination.PersonDetail(it, p.name)) }
                        },
                    )
                }
            }

            // 3. About This Film — Rich Data Bento Grid
            item(key = "facts") {
                val facts = buildList {
                    movie.studio?.takeIf { it.isNotBlank() }?.let { add("Studio" to it) }
                    movie.year?.let { add("Release Year" to it.toString()) }
                    fmtDetailRuntime(movie.runtimeMinutes)?.let { add("Runtime" to it) }
                    movie.contentRating?.takeIf { it.isNotBlank() }?.let { add("Rated" to it) }
                    movie.rating?.takeIf { it > 0.0 }?.let { add("TMDB Score" to "★ ${"%.1f".format(it)} / 10") }
                    extras?.collection?.name?.takeIf { it.isNotBlank() }?.let { add("Franchise" to it) }
                    if (directors.isNotEmpty()) add("Director" to directors.joinToString(", ") { it.name })
                }
                if (facts.isNotEmpty()) {
                    TvDetailFactsBento(
                        title = "About This Film",
                        facts = facts,
                        keywords = extras?.keywords.orEmpty().take(10),
                        onKeywordClick = { kw -> onNavigate(PlayerDestination.SearchQuery(kw)) },
                    )
                }
            }

            // 3b. Library-Only Discovery Rails ("More by Director", "More with Actor", "More from Studio")
            val ownedDiscoveryRails = discoveryRails.mapNotNull { rail ->
                val ownedOnly = rail.items.filter { it.libraryId != null }
                if (ownedOnly.size >= 2) rail.copy(items = ownedOnly) else null
            }
            ownedDiscoveryRails.forEach { rail ->
                item(key = "disc-${rail.key}") {
                    TvDetailOwnedDiscoveryShelf(
                        rail = rail,
                        rowBringIntoView = rowBringIntoView,
                        onOpenItem = { item ->
                            val libId = item.libraryId ?: return@TvDetailOwnedDiscoveryShelf
                            if (item.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                            else onNavigate(PlayerDestination.ShowDetail(libId))
                        },
                    )
                }
            }

            // 4. Franchise Collection Rail (if part of a TMDB franchise)
            val franchise = extras?.collection
            if (franchise != null && franchise.items.size > 1) {
                item(key = "franchise") {
                    TvDetailExtraTitlesShelf(
                        title = franchise.name ?: "Franchise Collection",
                        subtitle = "${franchise.items.size} films in franchise",
                        items = franchise.items,
                        kind = "movie",
                        api = api,
                        rowBringIntoView = rowBringIntoView,
                        onOpenOwned = { id -> onNavigate(PlayerDestination.MovieDetail(id)) },
                        onRequestUnowned = onRequestUnowned,
                    )
                }
            }

            // 5. Recommended & Similar Films
            if (similarList.isNotEmpty()) {
                item(key = "similar") {
                    TvDetailExtraTitlesShelf(
                        title = "More Like This",
                        subtitle = "Recommended & similar",
                        items = similarList,
                        kind = "movie",
                        api = api,
                        rowBringIntoView = rowBringIntoView,
                        onOpenOwned = { id -> onNavigate(PlayerDestination.MovieDetail(id)) },
                        onRequestUnowned = onRequestUnowned,
                    )
                }
            }
        }
        }

        // Top-right Live Clock + Mini Music Player
        TvDetailTopRightHud(coordinator = coordinator, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun TvCinemaShowDetail(
    show: ShowDetail,
    extras: TitleExtras?,
    discoveryRails: List<DiscoveryRail> = emptyList(),
    inWatchlist: Boolean,
    initialSeason: Int?,
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    onPlayEpisode: (DetailEpisode, Int) -> Unit,
    onOpenEpisodeDetail: (Int) -> Unit,
    onToggleWatchlist: () -> Unit,
    onStartWatchParty: () -> Unit,
    onReportIssue: () -> Unit,
    onNavigate: (PlayerDestination) -> Unit,
    onRequestUnowned: (ExtraTitle) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    val density = LocalDensity.current

    val posterUrl = api.artwork.showPoster(show.id, width = 600)
    val backdropUrl = if (show.hasBackdrop) api.artwork.showBackdrop(show.id, width = 1920) else posterUrl

    var logoUrl by remember(show.id) { mutableStateOf(api.artwork.resolve(show.logo)) }
    var targetAmbient by remember(show.id) { mutableStateOf(Color(0xFF151326)) }

    val orderedSeasons = remember(show.seasons) {
        show.seasons.sortedBy { if (it.seasonNumber == 0) 9999 else it.seasonNumber }
    }
    var selectedSeasonNum by remember(show.id, initialSeason) {
        mutableIntStateOf(
            initialSeason?.takeIf { n -> orderedSeasons.any { it.seasonNumber == n } }
                ?: orderedSeasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber
                ?: orderedSeasons.firstOrNull()?.seasonNumber
                ?: 1,
        )
    }
    val activeSeason = orderedSeasons.firstOrNull { it.seasonNumber == selectedSeasonNum } ?: orderedSeasons.firstOrNull()
    val totalEpisodes = remember(show.seasons) { show.seasons.sumOf { it.episodes.size } }
    val firstPlayable = remember(orderedSeasons) {
        orderedSeasons.firstNotNullOfOrNull { s ->
            s.episodes.firstOrNull { it.owned && it.watched != true }?.let { s.seasonNumber to it }
        } ?: orderedSeasons.firstNotNullOfOrNull { s ->
            s.episodes.firstOrNull { it.owned }?.let { s.seasonNumber to it }
        }
    }

    LaunchedEffect(show.id, backdropUrl) {
        val extracted = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(context)
                    .data(backdropUrl)
                    .size(48)
                    .allowHardware(false)
                    .build()
                val bmp = (imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                bmp?.let { extractCinemaAmbientColor(it) }
            }.getOrNull()
        }
        if (extracted != null) targetAmbient = extracted

        val tmdbId = HomeWarmCache.catalog.shows.firstOrNull { it.id == show.id }?.tmdbId
        if (tmdbId != null) {
            val lk = "show:$tmdbId"
            val cached = HomeWarmCache.resolvedLogos[lk]
            if (!logoUrl.isNullOrBlank()) {
                // the page's own logo stands
            } else if (cached != null) {
                logoUrl = cached
            } else {
                (api.fetchLogos(listOf(lk)) as? MusicApiResult.Success)?.data?.get(lk)?.let { raw ->
                    api.artwork.resolve(raw)?.let { resolved ->
                        HomeWarmCache.resolvedLogos[lk] = resolved
                        logoUrl = resolved
                    }
                }
            }
        }
    }

    val animatedAmbient by animateColorAsState(
        targetValue = targetAmbient,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "tvShowDetailAmbient",
    )

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(show.id) {
        delay(80L)
        runCatching { playFocus.requestFocus() }
    }

    val creators = remember(show.crew) {
        show.crew.filter {
            it.job.contains("Creator", ignoreCase = true) ||
                it.job.contains("Executive Producer", ignoreCase = true) ||
                it.job.equals("Director", ignoreCase = true)
        }.distinctBy { it.name }.take(3)
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

    val verticalBringIntoView = remember(density) {
        val topPad = with(density) { 24.dp.toPx() }
        val bottomPad = with(density) { 32.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (containerSize <= 0f) return 0f
                return when {
                    offset < topPad -> offset - topPad
                    offset + size > containerSize - bottomPad -> (offset + size) - (containerSize - bottomPad)
                    else -> 0f
                }
            }
        }
    }

    val similarList = remember(extras) {
        (extras?.recommendations.orEmpty() + extras?.similar.orEmpty()).distinctBy { it.tmdbId }.take(18)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(animatedAmbient),
    ) {
        // Feathered full-bleed backdrop
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(backdropUrl)
                .size(1920, 1080)
                .precision(Precision.INEXACT)
                .crossfade(true)
                .build(),
            imageLoader = imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .fillMaxHeight(0.74f),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to animatedAmbient,
                        0.38f to animatedAmbient.copy(alpha = 0.94f),
                        0.60f to animatedAmbient.copy(alpha = 0.45f),
                        0.82f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.18f),
                        0.45f to Color.Transparent,
                        0.68f to animatedAmbient.copy(alpha = 0.92f),
                        0.80f to animatedAmbient,
                        1.0f to Color.Black.copy(alpha = 0.28f),
                    ),
                ),
        )

        CompositionLocalProvider(LocalBringIntoViewSpec provides verticalBringIntoView) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 28.dp, bottom = 52.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 1. Theatrical Hero Stage
            item(key = "hero") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 78.dp, end = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!logoUrl.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(logoUrl)
                                    .size(960)
                                    .precision(Precision.INEXACT)
                                    .crossfade(true)
                                    .build(),
                                imageLoader = imageLoader,
                                contentDescription = show.title,
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.BottomStart,
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .height(74.dp),
                                error = {
                                    Text(
                                        text = show.title,
                                        color = Color.White,
                                        fontSize = 36.sp,
                                        lineHeight = 40.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = (-0.7).sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        } else {
                            Text(
                                text = show.title,
                                color = Color.White,
                                fontSize = 36.sp,
                                lineHeight = 40.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.7).sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (!show.tagline.isNullOrBlank()) {
                            Text(
                                text = "“${show.tagline}”",
                                color = Color.White.copy(alpha = 0.78f),
                                fontSize = 15.sp,
                                fontStyle = FontStyle.Italic,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            val meta = listOfNotNull(
                                show.year?.toString(),
                                "${orderedSeasons.size} ${if (orderedSeasons.size == 1) "Season" else "Seasons"}",
                                if (totalEpisodes > 0) "$totalEpisodes Episodes" else null,
                                show.network?.takeIf { it.isNotBlank() },
                            ).joinToString("  ·  ")
                            if (meta.isNotBlank()) {
                                Text(
                                    text = meta,
                                    color = Color.White.copy(alpha = 0.88f),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (show.rating != null && show.rating!! > 0.0) {
                                Text(
                                    text = "★ ${"%.1f".format(show.rating)}",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                            }
                            if (!show.contentRating.isNullOrBlank()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(5.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(show.contentRating!!, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (show.genres.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                show.genres.take(5).forEach { g ->
                                    TvClickableTagChip(
                                        label = g,
                                        onClick = { onNavigate(PlayerDestination.SearchQuery(g)) },
                                    )
                                }
                            }
                        }

                        if (!show.overview.isNullOrBlank()) {
                            Text(
                                text = show.overview!!,
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (creators.isNotEmpty()) {
                            Text(
                                text = "Created & Produced by ${creators.joinToString(", ") { it.name }}",
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (firstPlayable != null) {
                                val (sNum, ep) = firstPlayable
                                TvDetailActionButton(
                                    label = "Play S${sNum} E${ep.episodeNumber}",
                                    icon = Icons.Default.PlayArrow,
                                    primary = true,
                                    modifier = Modifier.focusRequester(playFocus),
                                    onClick = { onPlayEpisode(ep, sNum) },
                                )
                            }
                            TvDetailActionButton(
                                label = if (inWatchlist) "In Watchlist" else "Watchlist",
                                icon = if (inWatchlist) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                                active = inWatchlist,
                                modifier = if (firstPlayable == null) Modifier.focusRequester(playFocus) else Modifier,
                                onClick = onToggleWatchlist,
                            )
                            TvDetailActionButton(
                                label = "Watch Party",
                                icon = Icons.Default.Group,
                                onClick = onStartWatchParty,
                            )
                            TvDetailIconAction(
                                icon = Icons.Default.Flag,
                                contentDescription = "Report issue",
                                onClick = onReportIssue,
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .padding(top = 36.dp)
                            .width(156.dp)
                            .height(234.dp)
                            .shadow(22.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black, spotColor = Color.Black)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp)),
                    ) {
                        SoulSyncArtwork(
                            url = posterUrl,
                            contentDescription = show.title,
                            fallbackIcon = Icons.Default.Tv,
                            maxPx = 600,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 2. Interactive Season Selector + Large 16:9 Episode Cards
            if (orderedSeasons.isNotEmpty()) {
                item(key = "seasons-episodes") {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
                            LazyRow(
                                contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(orderedSeasons, key = { it.seasonNumber }) { s ->
                                    val lbl = if (s.seasonNumber == 0) "Specials" else "Season ${s.seasonNumber}"
                                    val countTxt = if (s.episodes.isNotEmpty()) " · ${s.episodes.size}" else ""
                                    TvCinemaPillTab(
                                        label = "$lbl$countTxt",
                                        selected = s.seasonNumber == selectedSeasonNum,
                                        onClick = { selectedSeasonNum = s.seasonNumber },
                                    )
                                }
                            }
                        }

                        val eps = activeSeason?.episodes.orEmpty()
                        if (eps.isNotEmpty()) {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
                                LazyRow(
                                    contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    items(eps, key = { it.id }) { ep ->
                                        TvDetailEpisodeCard(
                                            ep = ep,
                                            seasonNum = activeSeason?.seasonNumber ?: 1,
                                            stillUrl = if (ep.hasStill) api.artwork.episodeStill(ep.id, width = 780) else backdropUrl,
                                            onClick = {
                                                if (ep.owned) onPlayEpisode(ep, activeSeason?.seasonNumber ?: 1)
                                                else onOpenEpisodeDetail(ep.id)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Cast Showcase
            if (show.cast.isNotEmpty()) {
                item(key = "cast") {
                    TvDetailCastShelf(
                        title = "Series Cast",
                        cast = show.cast.take(18),
                        api = api,
                        rowBringIntoView = rowBringIntoView,
                        onPersonClick = { p ->
                            p.tmdbId?.let { onNavigate(PlayerDestination.PersonDetail(it, p.name)) }
                        },
                    )
                }
            }

            // 4. About This Series — Bento Fact Grid
            item(key = "facts") {
                val facts = buildList {
                    show.network?.takeIf { it.isNotBlank() }?.let { add("Network" to it) }
                    show.year?.let { add("First Aired" to it.toString()) }
                    add("Seasons" to "${orderedSeasons.size}")
                    if (totalEpisodes > 0) add("Episodes" to "$totalEpisodes")
                    show.contentRating?.takeIf { it.isNotBlank() }?.let { add("Rated" to it) }
                    show.rating?.takeIf { it > 0.0 }?.let { add("TMDB Score" to "★ ${"%.1f".format(it)} / 10") }
                }
                if (facts.isNotEmpty()) {
                    TvDetailFactsBento(
                        title = "About This Series",
                        facts = facts,
                        keywords = extras?.keywords.orEmpty().take(10),
                        onKeywordClick = { kw -> onNavigate(PlayerDestination.SearchQuery(kw)) },
                    )
                }
            }

            // 4b. Library-Only Discovery Rails ("More with Actor", "More from Network", "More by Creator")
            val ownedShowRails = discoveryRails.mapNotNull { rail ->
                val ownedOnly = rail.items.filter { it.libraryId != null }
                if (ownedOnly.size >= 2) rail.copy(items = ownedOnly) else null
            }
            ownedShowRails.forEach { rail ->
                item(key = "disc-${rail.key}") {
                    TvDetailOwnedDiscoveryShelf(
                        rail = rail,
                        rowBringIntoView = rowBringIntoView,
                        onOpenItem = { item ->
                            val libId = item.libraryId ?: return@TvDetailOwnedDiscoveryShelf
                            if (item.kind == "movie") onNavigate(PlayerDestination.MovieDetail(libId))
                            else onNavigate(PlayerDestination.ShowDetail(libId))
                        },
                    )
                }
            }

            // 5. Recommended & Similar Shows
            if (similarList.isNotEmpty()) {
                item(key = "similar") {
                    TvDetailExtraTitlesShelf(
                        title = "More Like This",
                        subtitle = "Recommended & similar series",
                        items = similarList,
                        kind = "show",
                        api = api,
                        rowBringIntoView = rowBringIntoView,
                        onOpenOwned = { id -> onNavigate(PlayerDestination.ShowDetail(id)) },
                        onRequestUnowned = onRequestUnowned,
                    )
                }
            }
        }
        }

        TvDetailTopRightHud(coordinator = coordinator, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun TvDetailTopRightHud(
    coordinator: PlaybackCoordinator,
    modifier: Modifier = Modifier,
) {
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            clockText = fmt.format(Date())
            delay(15_000L)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.padding(top = 16.dp, end = 24.dp),
    ) {
        val activeAudio = coordinator.activeItem
        if (activeAudio != null && !activeAudio.isVideo) {
            TvTopRightMiniPlayer(coordinator = coordinator, item = activeAudio)
        }
        if (clockText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.36f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(clockText, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TvDetailActionButton(
    label: String,
    icon: ImageVector,
    primary: Boolean = false,
    active: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "tvActScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            primary && focused -> SoulSyncColors.Accent
            primary -> Color.White
            focused -> Color.White
            active -> SoulSyncColors.Accent.copy(alpha = 0.28f)
            else -> Color.Black.copy(alpha = 0.42f)
        },
        animationSpec = tween(150),
        label = "tvActBg",
    )
    val fg by animateColorAsState(
        targetValue = when {
            primary && focused -> Color.White
            primary -> Color(0xFF0B0D14)
            focused -> Color(0xFF0B0D14)
            else -> Color.White
        },
        animationSpec = tween(150),
        label = "tvActFg",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .height(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .then(
                if (primary || focused) Modifier
                else Modifier.border(
                    1.dp,
                    if (active) SoulSyncColors.AccentLight.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.18f),
                    CircleShape,
                ),
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TvDetailIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(160),
        label = "tvIconActScale",
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.42f))
            .then(
                if (focused) Modifier
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape),
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color(0xFF0B0D14) else Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun TvDetailEpisodeCard(
    ep: DetailEpisode,
    seasonNum: Int,
    stillUrl: String?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "tvEpScale",
    )
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(268.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .then(
                    if (focused) Modifier.shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    else Modifier,
                )
                .clip(shape)
                .background(Color(0xFF141622))
                .then(
                    if (focused) Modifier.border(2.5.dp, Color.White, shape)
                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), shape),
                ),
        ) {
            SoulSyncArtwork(
                url = stillUrl,
                contentDescription = ep.title ?: "Episode ${ep.episodeNumber}",
                fallbackIcon = Icons.Default.Tv,
                maxPx = 640,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (ep.owned) 1f else 0.45f),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent, Color.Black.copy(alpha = 0.65f))),
                    ),
            )
            // Episode number badge top-left
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("S${seasonNum}·E${ep.episodeNumber}", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
            // Resolution + Watched badge top-right
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                if (!ep.resolution.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text(ep.resolution!!, color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (ep.watched == true) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF22C55E)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Watched", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }
            // Runtime + Rating bottom-right
            val subBadge = listOfNotNull(
                ep.runtimeMinutes?.takeIf { it > 0 }?.let { "${it}m" },
                ep.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
            ).joinToString(" · ")
            if (subBadge.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(subBadge, color = Color.White.copy(alpha = 0.92f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            text = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber}",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!ep.overview.isNullOrBlank()) {
            Text(
                text = ep.overview!!,
                color = Color.White.copy(alpha = 0.68f),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvDetailCastShelf(
    title: String,
    cast: List<CastMember>,
    api: PlayerApiClient,
    rowBringIntoView: BringIntoViewSpec,
    onPersonClick: (CastMember) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 78.dp),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
            LazyRow(
                contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(cast, key = { i, c -> "${c.tmdbId ?: i}-${c.name}" }) { _, person ->
                    var focused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(
                        targetValue = if (focused) 1.06f else 1f,
                        animationSpec = tween(160),
                        label = "castScale",
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(104.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .onFocusChanged { focused = it.isFocused || it.hasFocus }
                            .clickable { onPersonClick(person) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(86.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                                .then(
                                    if (focused) Modifier.border(2.5.dp, Color.White, CircleShape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                                ),
                        ) {
                            SoulSyncArtwork(
                                url = api.artwork.resolve(person.photo),
                                contentDescription = person.name,
                                fallbackIcon = Icons.Default.Person,
                                maxPx = 280,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(modifier = Modifier.height(7.dp))
                        Text(
                            text = person.name,
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!person.character.isNullOrBlank()) {
                            Text(
                                text = person.character!!,
                                color = Color.White.copy(alpha = 0.62f),
                                fontSize = 11.sp,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvDetailFactsBento(
    title: String,
    facts: List<Pair<String, String>>,
    keywords: List<String>,
    onKeywordClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 78.dp, end = 36.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            facts.forEach { (k, v) ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.34f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = k.uppercase(),
                        color = Color.White.copy(alpha = 0.52f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.8.sp,
                    )
                    Text(
                        text = v,
                        color = Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (keywords.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keywords.forEach { kw ->
                    TvClickableTagChip(
                        label = "#$kw",
                        onClick = { onKeywordClick(kw) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvClickableTagChip(
    label: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.06f else 1f,
        animationSpec = tween(150),
        label = "tagChipScale",
    )
    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.10f))
            .border(
                1.dp,
                if (focused) Color.White else Color.White.copy(alpha = 0.18f),
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (focused) Color(0xFF0B0D14) else Color.White.copy(alpha = 0.88f),
            fontSize = 11.5.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvDetailOwnedDiscoveryShelf(
    rail: DiscoveryRail,
    rowBringIntoView: BringIntoViewSpec,
    onOpenItem: (DiscoveryCardItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 78.dp),
        ) {
            Text(rail.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("· In Your Library", color = Color.White.copy(alpha = 0.52f), fontSize = 12.sp)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
            LazyRow(
                contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(rail.items, key = { it.key }) { item ->
                    var focused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(
                        targetValue = if (focused) 1.06f else 1f,
                        animationSpec = tween(170),
                        label = "ownedDiscScale",
                    )
                    val shape = RoundedCornerShape(11.dp)
                    Column(
                        modifier = Modifier
                            .width(126.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .onFocusChanged { focused = it.isFocused || it.hasFocus }
                            .clickable { onOpenItem(item) },
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(188.dp)
                                .clip(shape)
                                .background(Color(0xFF141622))
                                .then(
                                    if (focused) Modifier.border(2.5.dp, Color.White, shape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape),
                                ),
                        ) {
                            SoulSyncArtwork(
                                url = item.artworkUrl,
                                contentDescription = item.title,
                                fallbackIcon = if (item.kind == "movie") Icons.Default.Movie else Icons.Default.Tv,
                                maxPx = 500,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvDetailExtraTitlesShelf(
    title: String,
    subtitle: String?,
    items: List<ExtraTitle>,
    kind: String,
    api: PlayerApiClient,
    rowBringIntoView: BringIntoViewSpec,
    onOpenOwned: (Int) -> Unit,
    onRequestUnowned: (ExtraTitle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 78.dp),
        ) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text("· $subtitle", color = Color.White.copy(alpha = 0.55f), fontSize = 12.5.sp)
            }
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
            LazyRow(
                contentPadding = PaddingValues(start = 78.dp, end = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(items, key = { "${kind}-${it.tmdbId}" }) { item ->
                    val ownedId = item.libraryId
                    val isOwned = ownedId != null
                    val art = if (isOwned) {
                        if (kind == "movie") api.artwork.moviePoster(ownedId!!, width = 500)
                        else api.artwork.showPoster(ownedId!!, width = 500)
                    } else {
                        api.artwork.resolve(item.poster)
                    }
                    var focused by remember { mutableStateOf(false) }
                    val scale by animateFloatAsState(
                        targetValue = if (focused) 1.06f else 1f,
                        animationSpec = tween(170),
                        label = "extraScale",
                    )
                    val shape = RoundedCornerShape(11.dp)
                    Column(
                        modifier = Modifier
                            .width(126.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .onFocusChanged { focused = it.isFocused || it.hasFocus }
                            .clickable {
                                if (ownedId != null) {
                                    onOpenOwned(ownedId)
                                } else {
                                    onRequestUnowned(item)
                                }
                            },
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(188.dp)
                                .clip(shape)
                                .background(Color(0xFF141622))
                                .then(
                                    if (focused) Modifier.border(2.5.dp, Color.White, shape)
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape),
                                ),
                        ) {
                            SoulSyncArtwork(
                                url = art,
                                contentDescription = item.title,
                                fallbackIcon = if (kind == "movie") Icons.Default.Movie else Icons.Default.Tv,
                                maxPx = 500,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(if (isOwned) 1f else 0.65f),
                            )
                            LibraryCornerBadge(
                                inLibrary = isOwned,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                            )
                        }
                        Text(
                            text = item.title,
                            color = if (isOwned) Color.White else Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

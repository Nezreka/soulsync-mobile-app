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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
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
import net.ssync.player.CalendarEpisode
import net.ssync.player.CalendarMovie
import net.ssync.player.MusicApiResult
import net.ssync.player.PlayerApiClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class TvCalendarCardItem(
    val key: String,
    val title: String,
    val subtitle: String?,
    val dateKey: String,
    val dateLabel: String,
    val timeLabel: String?,
    val networkOrStudio: String?,
    val runtimeLabel: String?,
    val ratingText: String?,
    val overview: String?,
    val acq: String,
    val progress: Double?,
    val cardArtUrl: String?,
    val backdropUrl: String?,
    val logoKey: String?,
    val isMovie: Boolean,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvCinemaCalendar(
    api: PlayerApiClient,
    coordinator: PlaybackCoordinator,
    weekStart: String,
    todayIso: String,
    weekDays: List<String>,
    selectedDay: String?,
    loading: Boolean,
    episodes: List<CalendarEpisode>,
    movies: List<CalendarMovie>,
    onSelectDay: (String?) -> Unit,
    onShiftWeek: (Int) -> Unit,
    onJumpToday: () -> Unit,
    onOpenEpisodeModal: (CalendarEpisode) -> Unit,
    onOpenMovieModal: (CalendarMovie) -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = LocalSoulSyncImageLoader.current
    val density = LocalDensity.current

    var filterKey by remember { mutableStateOf("all") } // all | owned | missing | wishlist | upcoming

    val allItems = remember(episodes, movies, todayIso, api) {
        val epCards = episodes.map { ep ->
            val cardArt = run {
                val epId = ep.episodeIdInt
                val showId = ep.showId
                when {
                    !ep.notInLibrary && ep.hasStillBool && epId != null -> api.artwork.episodeStill(epId, width = 780)
                    !ep.stillUrl.isNullOrBlank() -> api.artwork.resolve(ep.stillUrl)
                    !ep.notInLibrary && showId != null && ep.showHasBackdropBool -> api.artwork.showBackdrop(showId, width = 1280)
                    !ep.backdropUrl.isNullOrBlank() -> api.artwork.resolve(ep.backdropUrl)
                    !ep.notInLibrary && showId != null -> api.artwork.showPoster(showId, width = 500)
                    !ep.posterUrl.isNullOrBlank() -> api.artwork.resolve(ep.posterUrl)
                    else -> null
                }
            }
            val backdrop = run {
                val showId = ep.showId
                when {
                    !ep.notInLibrary && showId != null && ep.showHasBackdropBool -> api.artwork.showBackdrop(showId, width = 1280)
                    !ep.backdropUrl.isNullOrBlank() -> api.artwork.resolve(ep.backdropUrl)
                    else -> cardArt
                }
            }
            val epCode = "S${ep.seasonNumber} • E${ep.episodeNumber}"
            val sub = listOfNotNull(epCode, ep.title?.takeIf { it.isNotBlank() }).joinToString(" · ")
            TvCalendarCardItem(
                key = "ep-${ep.resolvedTmdbId ?: ep.showId}-${ep.seasonNumber}-${ep.episodeNumber}-${ep.airDate}",
                title = ep.showTitle.ifBlank { "Episode" },
                subtitle = sub,
                dateKey = ep.airDate,
                dateLabel = tvCalFormatDate(ep.airDate, todayIso),
                timeLabel = tvCalFormatTime(ep.airsTime),
                networkOrStudio = ep.network?.takeIf { it.isNotBlank() },
                runtimeLabel = ep.runtimeMinutes?.takeIf { it > 0 }?.let { "${it}m" },
                ratingText = ep.rating?.takeIf { it > 0.0 }?.let { "★ ${"%.1f".format(it)}" },
                overview = ep.overview?.takeIf { it.isNotBlank() },
                acq = ep.acq,
                progress = ep.progress,
                cardArtUrl = cardArt,
                backdropUrl = backdrop,
                logoKey = ep.resolvedTmdbId?.let { "show:$it" },
                isMovie = false,
                onClick = { onOpenEpisodeModal(ep) },
            )
        }
        val movieCards = movies.map { m ->
            val libId = m.effectiveLibraryId
            val art = when {
                libId != null -> api.artwork.movieBackdrop(libId, width = 1280)
                !m.posterUrl.isNullOrBlank() -> api.artwork.resolve(m.posterUrl)
                else -> null
            }
            val relLabel = when (m.effectiveType) {
                "digital" -> "Digital Release"
                "theatrical", "in_cinemas" -> "In Theaters"
                "physical" -> "Physical Release"
                else -> "Movie Release"
            }
            TvCalendarCardItem(
                key = "mov-${m.tmdbId}-${m.effectiveDate}",
                title = m.title,
                subtitle = listOfNotNull(relLabel, m.year?.toString()).joinToString(" · "),
                dateKey = m.effectiveDate,
                dateLabel = tvCalFormatDate(m.effectiveDate, todayIso),
                timeLabel = null,
                networkOrStudio = null,
                runtimeLabel = null,
                ratingText = null,
                overview = null,
                acq = m.acq,
                progress = m.progress,
                cardArtUrl = art,
                backdropUrl = art,
                logoKey = if (m.tmdbId > 0) "movie:${m.tmdbId}" else null,
                isMovie = true,
                onClick = { onOpenMovieModal(m) },
            )
        }
        (epCards + movieCards).sortedWith(compareBy<TvCalendarCardItem> { it.dateKey }.thenBy { it.timeLabel ?: "ZZ" })
    }

    // If Today has zero items after loading finishes, auto-show all 7 days so the screen is immediately rich!
    LaunchedEffect(loading, allItems.size, weekStart) {
        if (!loading && selectedDay != null && allItems.isNotEmpty() && allItems.none { it.dateKey == selectedDay }) {
            onSelectDay(null)
        }
    }

    val byDayAll = remember(allItems) { allItems.groupBy { it.dateKey } }

    val visibleItems = remember(allItems, selectedDay, filterKey) {
        allItems.filter { item ->
            val dayOk = selectedDay == null || item.dateKey == selectedDay
            val filterOk = when (filterKey) {
                "owned" -> item.acq == "owned"
                "missing" -> item.acq == "missing"
                "wishlist" -> item.acq == "wishlist" || item.acq == "downloading"
                "upcoming" -> item.acq == "upcoming"
                else -> true
            }
            dayOk && filterOk
        }
    }

    // Batch-prefetch official TMDB wordmark logos for every show & movie in this week's calendar
    val prefetchedLogos = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(allItems, api) {
        val keys = allItems.mapNotNull { it.logoKey }.distinct().filter { !prefetchedLogos.containsKey(it) }
        keys.chunked(30).forEach { batch ->
            (api.fetchLogos(batch) as? MusicApiResult.Success)?.data?.forEach { (k, raw) ->
                api.artwork.resolve(raw)?.let { resolved -> prefetchedLogos[k] = resolved }
            }
        }
    }

    var focusedItem by remember { mutableStateOf<TvCalendarCardItem?>(null) }
    val activeItem = focusedItem?.takeIf { f -> visibleItems.any { it.key == f.key } }
        ?: visibleItems.firstOrNull()
        ?: allItems.firstOrNull()

    // Dynamic ambient cinema color extraction
    val colorCache = remember { mutableStateMapOf<String, Color>() }
    var targetAmbient by remember { mutableStateOf(Color(0xFF151326)) }
    LaunchedEffect(activeItem?.key) {
        val artUrl = activeItem?.backdropUrl ?: activeItem?.cardArtUrl ?: return@LaunchedEffect
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

    val animatedAmbient by animateColorAsState(
        targetValue = targetAmbient,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "tvCalAmbient",
    )

    // Live clock pill
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        while (true) {
            clockText = fmt.format(Date())
            delay(15_000L)
        }
    }

    val firstCardFocus = remember { FocusRequester() }
    LaunchedEffect(loading, visibleItems.isNotEmpty()) {
        if (!loading && visibleItems.isNotEmpty()) {
            delay(80L)
            runCatching { firstCardFocus.requestFocus() }
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
        val activeBackdrop = activeItem?.backdropUrl ?: activeItem?.cardArtUrl
        AnimatedContent(
            targetState = activeBackdrop,
            transitionSpec = {
                fadeIn(tween(360, easing = FastOutSlowInEasing)).togetherWith(fadeOut(tween(240)))
            },
            label = "tvCalBackdrop",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight(0.64f),
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

        // Seamless cinema feathering into the living ambient canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to animatedAmbient,
                        0.34f to animatedAmbient.copy(alpha = 0.96f),
                        0.56f to animatedAmbient.copy(alpha = 0.50f),
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
                        0.56f to animatedAmbient.copy(alpha = 0.88f),
                        0.68f to animatedAmbient,
                        1.0f to Color.Black.copy(alpha = 0.24f),
                    ),
                ),
        )

        // 2. Vertical Cinema Layout: Top Spotlight (43%) + 7-Day Ribbon + Bottom Release Shelf
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Spotlight Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.43f)
                    .padding(start = 78.dp, end = 28.dp, top = 24.dp, bottom = 8.dp),
            ) {
                if (activeItem != null) {
                    val logoUrl = activeItem.logoKey?.let { prefetchedLogos[it] }
                    val statusTint = tvCalStatusColor(activeItem.acq)
                    val statusText = tvCalStatusLabel(activeItem.acq, activeItem.progress)

                    AnimatedContent(
                        targetState = activeItem.key,
                        transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(130))) },
                        label = "tvCalMeta",
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(0.58f),
                    ) { _ ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Status + Date + Time + Network pill row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(statusTint.copy(alpha = 0.22f))
                                        .border(1.dp, statusTint.copy(alpha = 0.55f), CircleShape)
                                        .padding(horizontal = 9.dp, vertical = 3.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(statusTint),
                                    )
                                    Text(
                                        text = statusText.uppercase(),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.6.sp,
                                    )
                                }
                                val whenText = listOfNotNull(
                                    activeItem.dateLabel,
                                    activeItem.timeLabel,
                                    activeItem.networkOrStudio,
                                ).joinToString("  ·  ")
                                if (whenText.isNotBlank()) {
                                    Text(
                                        text = whenText,
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            if (!logoUrl.isNullOrBlank()) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(logoUrl)
                                        .size(900)
                                        .precision(Precision.INEXACT)
                                        .crossfade(true)
                                        .build(),
                                    imageLoader = imageLoader,
                                    contentDescription = activeItem.title,
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.BottomStart,
                                    modifier = Modifier
                                        .fillMaxWidth(0.84f)
                                        .height(60.dp),
                                    error = {
                                        Text(
                                            text = activeItem.title,
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
                                    text = activeItem.title,
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    lineHeight = 34.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.5).sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (!activeItem.subtitle.isNullOrBlank()) {
                                Text(
                                    text = activeItem.subtitle,
                                    color = Color.White.copy(alpha = 0.92f),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            val metaBits = listOfNotNull(activeItem.runtimeLabel, activeItem.ratingText)
                            if (metaBits.isNotEmpty()) {
                                Text(
                                    text = metaBits.joinToString("  ·  "),
                                    color = Color(0xFFFBBF24),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }

                            if (!activeItem.overview.isNullOrBlank()) {
                                Text(
                                    text = activeItem.overview,
                                    color = Color.White.copy(alpha = 0.82f),
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Release Calendar",
                            color = Color.White,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            text = "Track upcoming episodes, new drops, and missing releases across your watchlist.",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 13.5.sp,
                        )
                    }
                }
            }

            // Middle: Crisp 7-Day Cinema Ribbon + Week Navigation + Filters
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Row 1: Week range + 7 Day Pills + All 7 Days
                CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
                    LazyRow(
                        contentPadding = PaddingValues(start = 78.dp, end = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item(key = "prev-week") {
                            TvCalIconPill(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous week",
                                onClick = { onShiftWeek(-7) },
                            )
                        }
                        item(key = "all-week") {
                            TvCalChipPill(
                                label = "All 7 Days",
                                count = allItems.size,
                                selected = selectedDay == null,
                                onClick = { onSelectDay(null) },
                            )
                        }
                        itemsIndexed(weekDays, key = { _, d -> "day-$d" }) { _, dayIso ->
                            val dayEntries = byDayAll[dayIso].orEmpty()
                            val isToday = dayIso == todayIso
                            val isSel = selectedDay == dayIso
                            val (dow, num) = tvCalShortDay(dayIso, isToday)
                            TvCalDayPill(
                                dow = dow,
                                dayNum = num,
                                count = dayEntries.size,
                                dotColors = dayEntries.map { tvCalStatusColor(it.acq) }.distinct().take(3),
                                isToday = isToday,
                                selected = isSel,
                                onClick = { onSelectDay(if (isSel) null else dayIso) },
                            )
                        }
                        item(key = "next-week") {
                            TvCalIconPill(
                                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next week",
                                onClick = { onShiftWeek(7) },
                            )
                        }
                        item(key = "today-jump") {
                            TvCalChipPill(
                                label = "Today",
                                count = null,
                                selected = false,
                                onClick = onJumpToday,
                            )
                        }
                    }
                }

                // Row 2: Compact Status Filter Chips + Current Day/Count Summary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 78.dp, end = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val heading = if (selectedDay != null) {
                        tvCalFormatDate(selectedDay, todayIso)
                    } else {
                        tvCalRangeLabel(weekStart)
                    }
                    Text(
                        text = heading,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "· ${visibleItems.size} ${if (visibleItems.size == 1) "release" else "releases"}",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val filters = listOf(
                        Triple("all", "All", Color.White),
                        Triple("owned", "Ready", Color(0xFF22C55E)),
                        Triple("missing", "Missing", Color(0xFFF59E0B)),
                        Triple("wishlist", "Wanted", Color(0xFF818CF8)),
                        Triple("upcoming", "Upcoming", Color(0xFF94A3B8)),
                    )
                    filters.forEach { (k, lbl, dot) ->
                        TvCalFilterChip(
                            label = lbl,
                            dotColor = if (k == "all") null else dot,
                            selected = filterKey == k,
                            onClick = { filterKey = k },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Release Shelf: Large 16:9 Cinema Episode / Movie Cards
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.36f),
                contentAlignment = Alignment.CenterStart,
            ) {
                when {
                    loading -> {
                        Row(
                            modifier = Modifier.padding(start = 78.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                color = SoulSyncColors.AccentLight,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Text("Loading releases…", color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp)
                        }
                    }
                    visibleItems.isEmpty() -> {
                        Row(
                            modifier = Modifier.padding(start = 78.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "No releases match this day or filter — pick another day above or choose All 7 Days.",
                                color = Color.White.copy(alpha = 0.70f),
                                fontSize = 14.sp,
                            )
                        }
                    }
                    else -> {
                        CompositionLocalProvider(LocalBringIntoViewSpec provides rowBringIntoView) {
                            LazyRow(
                                contentPadding = PaddingValues(start = 78.dp, end = 36.dp, top = 6.dp, bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                itemsIndexed(visibleItems, key = { _, it -> it.key }) { idx, item ->
                                    TvCinemaCalendarCard(
                                        item = item,
                                        showDateOnCard = selectedDay == null,
                                        modifier = if (idx == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                                        onFocused = { focusedItem = item },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Top-Right Floating HUD: Mini Music Player + Live Clock Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 24.dp),
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
private fun TvCinemaCalendarCard(
    item: TvCalendarCardItem,
    showDateOnCard: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.055f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "tvCalCardScale",
    )
    val shape = RoundedCornerShape(12.dp)
    val statusColor = tvCalStatusColor(item.acq)
    val shortStatus = when (item.acq) {
        "owned" -> "Ready"
        "downloading" -> item.progress?.let { "${it.toInt()}%" } ?: "DL"
        "wishlist" -> "Wanted"
        "missing" -> "Missing"
        else -> "Soon"
    }

    Box(
        modifier = modifier
            .width(248.dp)
            .height(142.dp)
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
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), shape),
            )
            .onFocusChanged { state ->
                val now = state.isFocused || state.hasFocus
                if (now != focused) {
                    focused = now
                    if (now) onFocused()
                }
            }
            .clickable(onClick = item.onClick),
    ) {
        SoulSyncArtwork(
            url = item.cardArtUrl,
            contentDescription = item.title,
            fallbackIcon = if (item.isMovie) Icons.Default.Movie else Icons.Default.Tv,
            maxPx = 640,
            modifier = Modifier.fillMaxSize(),
        )

        // Clean lower-third dark scrim for effortless 10-foot legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.20f),
                        0.42f to Color.Transparent,
                        0.72f to Color.Black.copy(alpha = 0.72f),
                        1.0f to Color.Black.copy(alpha = 0.92f),
                    ),
                ),
        )

        // Top-left status pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.68f))
                .border(1.dp, statusColor.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Text(
                text = shortStatus,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Top-right date/time badge when viewing All 7 Days
        val cornerTime = if (showDateOnCard) {
            listOfNotNull(item.dateLabel.substringBefore(","), item.timeLabel).joinToString(" · ")
        } else {
            item.timeLabel
        }
        if (!cornerTime.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.64f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = cornerTime,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        // Bottom readable title & episode code
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                    text = item.subtitle,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TvCalDayPill(
    dow: String,
    dayNum: String,
    count: Int,
    dotColors: List<Color>,
    isToday: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> SoulSyncColors.Accent.copy(alpha = 0.34f)
        isToday -> Color.White.copy(alpha = 0.14f)
        else -> Color.Black.copy(alpha = 0.32f)
    }
    val fg = if (focused) Color(0xFF0B0D14) else Color.White
    val subFg = if (focused) Color(0xFF334155) else Color.White.copy(alpha = 0.68f)
    val borderColor = when {
        focused -> Color.White
        selected -> SoulSyncColors.AccentLight.copy(alpha = 0.85f)
        isToday -> Color.White.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.12f)
    }

    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = dow.uppercase(),
            color = subFg,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.6.sp,
        )
        Text(
            text = dayNum,
            color = fg,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(6.dp),
        ) {
            if (dotColors.isEmpty()) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (focused) Color.Black.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.16f)),
                )
            } else {
                dotColors.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(c),
                    )
                }
            }
        }
    }
}

@Composable
private fun TvCalChipPill(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> SoulSyncColors.Accent.copy(alpha = 0.34f)
        else -> Color.Black.copy(alpha = 0.32f)
    }
    val fg = if (focused) Color(0xFF0B0D14) else Color.White
    val borderColor = when {
        focused -> Color.White
        selected -> SoulSyncColors.AccentLight.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.14f)
    }
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        if (count != null) {
            Text(
                text = count.toString(),
                color = if (focused) Color(0xFF475569) else Color.White.copy(alpha = 0.65f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TvCalIconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Black.copy(alpha = 0.34f))
            .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.14f), CircleShape)
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color(0xFF0B0D14) else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TvCalFilterChip(
    label: String,
    dotColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.18f)
        else -> Color.Black.copy(alpha = 0.28f)
    }
    val fg = if (focused) Color(0xFF0B0D14) else Color.White
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(
                1.dp,
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.45f)
                    else -> Color.White.copy(alpha = 0.12f)
                },
                CircleShape,
            )
            .onFocusChanged { focused = it.isFocused || it.hasFocus }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(text = label, color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun tvCalStatusColor(acq: String): Color = when (acq) {
    "owned" -> Color(0xFF22C55E)
    "downloading" -> Color(0xFF38BDF8)
    "wishlist" -> Color(0xFF818CF8)
    "missing" -> Color(0xFFF59E0B)
    else -> Color(0xFF94A3B8)
}

private fun tvCalStatusLabel(acq: String, progress: Double?): String = when (acq) {
    "owned" -> "In Library"
    "downloading" -> if (progress != null) "Downloading ${progress.toInt()}%" else "Downloading"
    "wishlist" -> "Wanted"
    "missing" -> "Missing"
    else -> "Upcoming"
}

private fun tvCalFormatDate(isoDate: String, todayIso: String): String {
    if (isoDate == todayIso) return "Today"
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outFmt = SimpleDateFormat("EEE, MMM d", Locale.US)
    return runCatching { inFmt.parse(isoDate)?.let { outFmt.format(it) } }.getOrNull() ?: isoDate
}

private fun tvCalShortDay(isoDate: String, isToday: Boolean): Pair<String, String> {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val wdFmt = SimpleDateFormat("EEE", Locale.US)
    val numFmt = SimpleDateFormat("d", Locale.US)
    val parsed = runCatching { inFmt.parse(isoDate) }.getOrNull()
    val dow = if (isToday) "Today" else (parsed?.let { wdFmt.format(it) } ?: "")
    val num = parsed?.let { numFmt.format(it) } ?: ""
    return dow to num
}

private fun tvCalRangeLabel(startIso: String): String {
    val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val outFmt = SimpleDateFormat("MMM d", Locale.US)
    val cal = java.util.Calendar.getInstance()
    val start = runCatching { inFmt.parse(startIso) }.getOrNull() ?: return startIso
    cal.time = start
    cal.add(java.util.Calendar.DAY_OF_YEAR, 6)
    return "${outFmt.format(start)} – ${outFmt.format(cal.time)}"
}

private fun tvCalFormatTime(airsTime: String?): String? {
    val raw = airsTime?.trim().takeIf { !it.isNullOrBlank() } ?: return null
    val parts = raw.split(":")
    val h24 = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val ampm = if (h24 >= 12) "PM" else "AM"
    val h12 = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return if (m == 0) "$h12 $ampm" else "$h12:%02d $ampm".format(m)
}
